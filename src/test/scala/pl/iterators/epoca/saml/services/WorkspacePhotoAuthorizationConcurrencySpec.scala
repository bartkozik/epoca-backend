package pl.iterators.epoca.saml.services

import io.circe.Json
import pl.iterators.epoca.organization.domain.OrganizationDomain._
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.organization.repositories.OrganizationUserRepositoryImpl
import pl.iterators.epoca.organization.repositories.fixtures.OrganizationFixture
import pl.iterators.epoca.saml.domain.SamlDomain.SamlIsEnabled
import pl.iterators.epoca.saml.domain.{SamlBindingKey, SamlConfig, SamlIdentityBinding}
import pl.iterators.epoca.saml.repositories.{SamlConfigRepositoryImpl, SamlIdentityBindingRepositoryImpl}
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.domain.UserDomain.UserAccountType
import pl.iterators.epoca.user.repositories.fixtures.UserFixture
import pl.iterators.epoca.utils.PgRepositorySpec
import pl.iterators.epoca.utils.db.BaseTable.driver.api._

import java.time.Instant
import scala.concurrent.duration._
import scala.concurrent.{Await, Promise}
import scala.util.Try

class WorkspacePhotoAuthorizationConcurrencySpec extends PgRepositorySpec with UserFixture with OrganizationFixture {
  "Workspace final authorization" should {
    "hold membership authority until the avatar transaction commits against a standalone revocation" in {
      val other = Database.forURL(
        sys.env("TEST_DB_URL"),
        user = sys.env("TEST_DB_USER"),
        password = sys.env("TEST_DB_PASSWORD"),
        driver = "org.postgresql.Driver"
      )
      val configs = new SamlConfigRepositoryImpl
      val fixture = Await.result(
        db.run((for {
          user   <- withUser(allGenerators[User].normal.generate.copy(archivedAt = None, accountType = UserAccountType.Human))
          org    <- withOrganization(allGenerators[Organization].normal.generate.copy(createdBy = user.id, modifiedBy = user.id, archivedAt = None))
          config <- configs.upsert(allGenerators[SamlConfig].normal.generate.copy(organizationId = org.id, isEnabled = SamlIsEnabled(true)))
          epoch  <- configs.trustEpoch(org.id)
          proof = WorkspacePhotoProof(org.id, epoch.get, config.idpEntityId, "subject", "member@example.com")
          _ <- new SamlIdentityBindingRepositoryImpl().insert(
                 SamlIdentityBinding(
                   SamlBindingKey(org.id, epoch.get, proof.issuer, proof.subject),
                   user.id,
                   proof.canonicalEmail,
                   Instant.now(),
                   Json.obj()
                 )
               )
          member <- new OrganizationUserRepositoryImpl()
                      .insertPrepared(OrganizationUser(OrganizationUserId.random, org.id, user.id, Seq(OrganizationUserRole.Manager), None))
        } yield (user, org, member, proof)).transactionally),
        10.seconds
      )
      val (user, org, member, proof) = fixture
      val started                    = Promise[Boolean]()
      val release                    = Promise[Unit]()
      val holder = db.run((for {
        _       <- sql"""SELECT id::text FROM "user" WHERE id=${user.id.toString}::uuid FOR UPDATE""".as[String]
        allowed <- WorkspaceAvatarSyncService.stillAuthorized(user.id, proof)
        _       <- DBIO.from { val _ = started.trySuccess(allowed); release.future }
      } yield allowed).transactionally)
      try {
        Await.result(started.future, 5.seconds) must beTrue
        val revoked = Try(
          Await.result(
            other.run((for {
              _       <- sqlu"SET LOCAL lock_timeout='150ms'"
              updated <- sqlu"UPDATE organization_user SET archived_at=now() WHERE id=${member.id.toString}::uuid"
            } yield updated).transactionally),
            5.seconds
          )
        )
        val _ = release.trySuccess(())
        Await.result(holder, 5.seconds) must beTrue
        revoked.failed.toOption must beSome.which {
          case error: java.sql.SQLException => error.getSQLState == "55P03"
          case _                            => false
        }
        Await.result(other.run(sqlu"UPDATE organization_user SET archived_at=now() WHERE id=${member.id.toString}::uuid"), 5.seconds) mustEqual 1
        Await.result(db.run(WorkspaceAvatarSyncService.stillAuthorized(user.id, proof).transactionally), 5.seconds) must beFalse
      } finally {
        val _ = release.trySuccess(())
        val _ = Await.ready(holder, 5.seconds)
        val _ = Await.result(
          db.run((for {
            _ <- sqlu"DELETE FROM organization_user WHERE id=${member.id.toString}::uuid"
            _ <- sqlu"DELETE FROM saml_identity_binding WHERE organization_id=${org.id.toString}::uuid"
            _ <- sqlu"DELETE FROM saml_config WHERE organization_id=${org.id.toString}::uuid"
            _ <- sqlu"DELETE FROM organization WHERE id=${org.id.toString}::uuid"
            _ <- sqlu"""DELETE FROM "user" WHERE id=${user.id.toString}::uuid"""
          } yield ()).transactionally),
          5.seconds
        )
        other.close()
      }
    }
  }
}
