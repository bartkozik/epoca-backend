package pl.iterators.epoca.auth.services

import pl.iterators.epoca.auth.domain.AccountAuthentication.{BoundSaml, Credential, CredentialMethod}
import pl.iterators.epoca.auth.domain.AuthDomain.PasswordHash
import pl.iterators.epoca.auth.domain.{AccountAuthentication, AuthContext, UserPassword}
import pl.iterators.epoca.auth.repositories.{AccountAuthenticationRepositoryImpl, UserPasswordRepositoryImpl}
import pl.iterators.epoca.organization.domain.Organization
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationArchivedAt
import pl.iterators.epoca.organization.repositories.OrganizationRepositoryImpl
import pl.iterators.epoca.saml.domain.SamlConfig
import pl.iterators.epoca.saml.domain.SamlDomain.{SamlIdpEntityId, SamlIsEnabled}
import pl.iterators.epoca.saml.repositories.SamlConfigRepositoryImpl
import pl.iterators.epoca.user.domain.UserDomain._
import pl.iterators.epoca.user.domain.{User, UserIdentity}
import pl.iterators.epoca.user.repositories.{UserIdentityRepositoryImpl, UserRepositoryImpl}
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import pl.iterators.epoca.utils.{AuthContextProvider, PgRepositorySpec}

import java.time.Instant

class AccountAuthenticationValidatorSpec extends PgRepositorySpec {
  private val users      = new UserRepositoryImpl()
  private val identities = new UserIdentityRepositoryImpl()
  private val passwords  = new UserPasswordRepositoryImpl()
  private val validator  = new AccountAuthenticationValidator(new AccountAuthenticationRepositoryImpl(), identities, passwords)
  private val now        = Instant.now()

  private case class Fixture(user: User, identity: UserIdentity) {
    def auth(method: CredentialMethod = CredentialMethod.Token, at: Instant = now): AuthContext =
      AuthContextProvider.user.copy(
        id = user.id,
        fresh = true,
        accountAuthentication = Some(AccountAuthentication(at, Credential(method, identity.id)))
      )
    def insert: DBIO[Unit] = for {
      _ <- users.insert(user)
      _ <- identities.insert(identity)
    } yield ()
  }
  private def fixture: Fixture = {
    val user = allGenerators[User].normal.generate.copy(archivedAt = None, accountType = UserAccountType.Human)
    val identity =
      allGenerators[UserIdentity].normal.generate.copy(userId = user.id, `type` = IdentityType.MailAddress, confirmed = UserIdentityConfirmed(true))
    Fixture(user, identity)
  }

  "trusted account validation" should {
    "reject legacy proof absence regardless of fresh/profile flags" in withDatabase {
      validator.validate(AuthContextProvider.admin.copy(fresh = true), now).map(_ shouldEqual false)
    }
    "accept fresh confirmed-contact tokens and reject expired and future evidence" in withDatabase {
      val f = fixture
      for {
        _        <- f.insert
        fresh    <- validator.validate(f.auth(), now)
        boundary <- validator.validate(f.auth(at = now.minusSeconds(300)), now)
        expired  <- validator.validate(f.auth(at = now.minusSeconds(301)), now)
        future   <- validator.validate(f.auth(at = now.plusSeconds(1)), now)
      } yield Seq(fresh, boundary, expired, future) shouldEqual Seq(true, true, false, false)
    }
    "require current password existence for password evidence" in withDatabase {
      val f = fixture
      for {
        _       <- f.insert
        absent  <- validator.validate(f.auth(CredentialMethod.Password), now)
        _       <- passwords.upsert(UserPassword(f.identity.id, PasswordHash("hash")))
        present <- validator.validate(f.auth(CredentialMethod.Password), now)
        _       <- sqlu"DELETE FROM user_password WHERE user_identity_id = ${f.identity.id.toString}::uuid"
        deleted <- validator.validate(f.auth(CredentialMethod.Password), now)
      } yield Seq(absent, present, deleted) shouldEqual Seq(false, true, false)
    }
    "reject a removed, unconfirmed, or differently owned contact" in withDatabase {
      val f     = fixture
      val other = fixture.user
      for {
        _           <- f.insert
        _           <- users.insert(other)
        _           <- identities.update(f.identity.copy(confirmed = UserIdentityConfirmed(false)))
        unconfirmed <- validator.validate(f.auth(), now)
        _           <- identities.update(f.identity.copy(userId = other.id))
        moved       <- validator.validate(f.auth(), now)
        _           <- sqlu"DELETE FROM user_identity WHERE id = ${f.identity.id.toString}::uuid"
        deleted     <- validator.validate(f.auth(), now)
      } yield Seq(unconfirmed, moved, deleted) shouldEqual Seq(false, false, false)
    }
    "reject archived, missing, and non-Human accounts" in withDatabase {
      val f       = fixture
      val service = fixture.user.copy(accountType = UserAccountType.ServiceAccount)
      for {
        _        <- f.insert
        _        <- users.insert(service)
        missing  <- validator.validate(f.auth().copy(id = fixture.user.id), now)
        nonHuman <- validator.validate(f.auth().copy(id = service.id), now)
        _        <- users.update(f.user.copy(archivedAt = Some(UserArchivedAt(now))))
        archived <- validator.validate(f.auth(), now)
      } yield Seq(missing, nonHuman, archived) shouldEqual Seq(false, false, false)
    }
    "accept verified TOTP and crypto with confirmed=false but reject a mismatched method" in withDatabase {
      val f = fixture
      for {
        _         <- f.insert
        _         <- identities.update(f.identity.copy(`type` = IdentityType.TimeBasedOneTimePassword, confirmed = UserIdentityConfirmed(false)))
        totp      <- validator.validate(f.auth(CredentialMethod.TimeBasedOneTimePassword), now)
        token     <- validator.validate(f.auth(CredentialMethod.Token), now)
        _         <- identities.update(f.identity.copy(`type` = IdentityType.CryptoVerification, confirmed = UserIdentityConfirmed(false)))
        crypto    <- validator.validate(f.auth(CredentialMethod.CryptoVerification), now)
        wrongTotp <- validator.validate(f.auth(CredentialMethod.TimeBasedOneTimePassword), now)
      } yield Seq(totp, token, crypto, wrongTotp) shouldEqual Seq(true, false, true, false)
    }
    "require the current SAML binding, enabled config and epoch without requiring membership in its organization" in withDatabase {
      val f   = fixture
      val org = allGenerators[Organization].normal.generate.copy(createdBy = f.user.id, modifiedBy = f.user.id, archivedAt = None)
      val config = allGenerators[SamlConfig].normal.generate
        .copy(organizationId = org.id, idpEntityId = SamlIdpEntityId("trusted-issuer"), isEnabled = SamlIsEnabled(true))
      val proof = BoundSaml(org.id, 1L, "trusted-issuer", "subject")
      val auth  = f.auth().copy(accountAuthentication = Some(AccountAuthentication(now, proof)))
      for {
        _ <- f.insert
        _ <- new OrganizationRepositoryImpl().insert(org)
        _ <- new SamlConfigRepositoryImpl().upsert(config)
        _ <- sqlu"""INSERT INTO saml_identity_binding(organization_id,trust_epoch,issuer,subject,user_id,canonical_email,verified_at,proof)
          VALUES (${org.id.toString}::uuid,1,'trusted-issuer','subject',${f.user.id.toString}::uuid,'verified@example.test',now(),'{}')"""
        valid        <- validator.validate(auth, now)
        wrongSubject <- validator.validate(auth.copy(accountAuthentication = Some(AccountAuthentication(now, proof.copy(subject = "other")))), now)
        _            <- sqlu"UPDATE saml_config SET trust_epoch = 2 WHERE organization_id = ${org.id.toString}::uuid"
        epoch        <- validator.validate(auth, now)
        _            <- sqlu"UPDATE saml_config SET trust_epoch = 1, is_enabled = false WHERE organization_id = ${org.id.toString}::uuid"
        disabled     <- validator.validate(auth, now)
        _            <- sqlu"UPDATE saml_config SET is_enabled = true WHERE organization_id = ${org.id.toString}::uuid"
        _            <- new OrganizationRepositoryImpl().update(org.copy(archivedAt = Some(OrganizationArchivedAt(now))))
        archived     <- validator.validate(auth, now)
        _            <- new OrganizationRepositoryImpl().update(org)
        _            <- sqlu"DELETE FROM saml_identity_binding WHERE organization_id = ${org.id.toString}::uuid"
        deleted      <- validator.validate(auth, now)
      } yield Seq(valid, wrongSubject, epoch, disabled, archived, deleted) shouldEqual Seq(true, false, false, false, false, false)
    }
    "accept Firebase only for a current confirmed email identity" in withDatabase {
      val f = fixture
      for {
        _     <- f.insert
        mail  <- validator.validate(f.auth(CredentialMethod.Firebase), now)
        _     <- identities.update(f.identity.copy(`type` = IdentityType.Phone))
        phone <- validator.validate(f.auth(CredentialMethod.Firebase), now)
      } yield Seq(mail, phone) shouldEqual Seq(true, false)
    }
  }
}
