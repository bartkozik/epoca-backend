package pl.iterators.epoca.invitation.services

import pl.iterators.epoca.auth.domain.AccountAuthentication.{Credential, CredentialMethod}
import pl.iterators.epoca.auth.domain.{AccountAuthentication, AuthContext}
import pl.iterators.epoca.auth.repositories.{AccountAuthenticationRepositoryImpl, UserPasswordRepositoryImpl}
import pl.iterators.epoca.auth.services.AccountAuthenticationValidator
import pl.iterators.epoca.invitation.domain.Invitation
import pl.iterators.epoca.invitation.domain.InvitationDomain._
import pl.iterators.epoca.invitation.domain.InvitationOnboarding._
import pl.iterators.epoca.invitation.repositories._
import pl.iterators.epoca.invitation.services.InvitationAcceptService.{InvitationAcceptRequest, InvitationAcceptResult}
import pl.iterators.epoca.organization.domain.Organization
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationUserRole
import pl.iterators.epoca.organization.repositories.OrganizationUserRepositoryImpl
import pl.iterators.epoca.organization.repositories.fixtures.OrganizationFixture
import pl.iterators.epoca.user.domain.UserDomain._
import pl.iterators.epoca.user.domain.{User, UserIdentity}
import pl.iterators.epoca.user.repositories._
import pl.iterators.epoca.user.repositories.fixtures.UserFixture
import pl.iterators.epoca.user.services.UserCreationActions
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import pl.iterators.epoca.utils.{AuthContextProvider, PgRepositorySpec}
import slick.sql.SqlStreamingAction

import java.time.{Clock, Instant, ZoneOffset}
import java.util.UUID

class InvitationAcceptServiceSpec extends PgRepositorySpec with OrganizationFixture with UserFixture {
  private val now         = Instant.parse("2026-10-02T10:00:00Z")
  private val clock       = Clock.fixed(now, ZoneOffset.UTC)
  private val invitations = new InvitationOnboardingRepositoryImpl
  private val identities  = new UserIdentityRepositoryImpl
  private val users       = new UserRepositoryImpl
  private val emails      = new EmailIdentityRepositoryImpl
  private val passwords   = new UserPasswordRepositoryImpl
  private val actions = new InvitationActivationActions(
    invitations,
    new InvitationAccessRepositoryImpl,
    users,
    identities,
    new OrganizationUserRepositoryImpl,
    new UserCreationActions(users, identities, emails, passwords),
    new AccountAuthenticationValidator(new AccountAuthenticationRepositoryImpl, identities, passwords),
    clock,
    new pl.iterators.epoca.saml.repositories.SamlRequestRepositoryImpl,
    new pl.iterators.epoca.saml.repositories.SamlIdentityBindingRepositoryImpl
  )
  private val service = new InvitationAcceptService(null, invitations, new InvitationRepositoryImpl, emails, users, actions, clock)
  private case class Fixture(
    record: Record,
    auth: AuthContext,
    identity: UserIdentity) {
    def accept(authenticated: AuthContext = auth, yes: Boolean = true): DBIO[InvitationAcceptResult] =
      service.acceptAction(authenticated, InvitationAcceptRequest(record.invitation.id, yes))
    def members: SqlStreamingAction[Vector[String], String, Effect] =
      sql"SELECT roles::text FROM organization_user WHERE organization_id=${record.invitation.organizationId.toString}::uuid".as[String]
  }
  private def fixture(kind: IdentityType = IdentityType.MailAddress, version: Int = 1): DBIO[Fixture] = for {
    user <- withUser(allGenerators[User].normal.generate.copy(archivedAt = None, accountType = UserAccountType.Human))
    org  <- withOrganization(allGenerators[Organization].normal.generate.copy(archivedAt = None))
    identity <- identities.insert(
                  allGenerators[UserIdentity].normal.generate.copy(
                    userId = user.id,
                    `type` = kind,
                    value = UserIdentityValue(s"legacy-${UUID.randomUUID()}@example.test"),
                    confirmed = UserIdentityConfirmed(true)
                  )
                )
    invitation = allGenerators[Invitation].normal.generate.copy(
                   organizationId = org.id,
                   createdBy = user.id,
                   modifiedBy = user.id,
                   identityType = kind,
                   identityValue = identity.value,
                   roles = Seq(OrganizationUserRole.Manager),
                   expireAt = if (version == 1) None else Some(InvitationExpireAt(now.plusSeconds(86400))),
                   acceptAt = None,
                   rejectAt = None,
                   archivedAt = None
                 )
    record = Record(invitation, version, Some("Legacy"), Some("Person"), 0, 1, Some(Digest(new Array[Byte](32))), None, Some("en"), None)
    _ <- invitations.insert(record)
  } yield Fixture(
    record,
    AuthContextProvider.user
      .copy(id = user.id, accountAuthentication = Some(AccountAuthentication(now, Credential(CredentialMethod.Token, identity.id)))),
    identity
  )

  "legacy invitations" should {
    "accept a confirmed recipient with current trusted evidence, including no-expiry v1" in withDatabase {
      for {
        f       <- fixture()
        result  <- f.accept()
        members <- f.members
        audits  <- invitations.audit(f.record.invitation.id)
      } yield {
        result mustEqual InvitationAcceptResult.Done
        members mustEqual Vector("{Manager}")
        audits.map(_.action) mustEqual Vector("Accepted")
      }
    }
    "accept confirmed own Phone while preserving the v1 boundary" in withDatabase {
      for {
        f       <- fixture(IdentityType.Phone)
        result  <- f.accept()
        members <- f.members
      } yield {
        result mustEqual InvitationAcceptResult.Done
        members mustEqual Vector("{Manager}")
      }
    }
    "reject without membership or Accepted audit and without requiring fresh login" in withDatabase {
      for {
        f       <- fixture()
        result  <- f.accept(f.auth.copy(accountAuthentication = None), yes = false)
        members <- f.members
        record  <- invitations.find(f.record.invitation.id)
        audits  <- invitations.audit(f.record.invitation.id)
      } yield {
        result mustEqual InvitationAcceptResult.Done
        members must beEmpty
        record.get.invitation.rejectAt must beSome
        record.get.invitation.acceptAt must beNone
        audits.map(_.action) mustEqual Vector("Rejected")
      }
    }
    "reject old JWT and legacy SAML laundering despite fresh/profile flags" in withDatabase {
      for {
        f       <- fixture()
        result  <- f.accept(f.auth.copy(fresh = true, accountAuthentication = None)).asTry
        members <- f.members
      } yield {
        result.failed.get mustEqual InvitationActivationService.Rejected(InvitationActivationService.SignInRequired)
        members must beEmpty
      }
    }
    "never consume v2 through the legacy endpoint" in withDatabase {
      for {
        f       <- fixture(version = 2)
        result  <- f.accept()
        members <- f.members
      } yield {
        result mustEqual InvitationAcceptResult.InvitationNotFound
        members must beEmpty
      }
    }
    "reject an unconfirmed alias even with current proof from another own identity" in withDatabase {
      for {
        f       <- fixture()
        _       <- identities.update(f.identity.copy(confirmed = UserIdentityConfirmed(false)))
        result  <- f.accept()
        members <- f.members
      } yield { result mustEqual InvitationAcceptResult.InvitationNotFound; members must beEmpty }
    }
  }
}
