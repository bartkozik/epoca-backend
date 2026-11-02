package pl.iterators.epoca.invitation.services

import pl.iterators.epoca.organization.domain.OrganizationDomain._
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.organization.repositories.fixtures.{OrganizationFixture, OrganizationUserFixture}
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.domain.UserDomain._
import pl.iterators.epoca.user.repositories.fixtures.UserFixture
import pl.iterators.epoca.utils.PgRepositorySpec
import pl.iterators.epoca.utils.db.BaseTable.driver.api._

import java.time.Instant

class InvitationAccessGuardSpec extends PgRepositorySpec with OrganizationFixture with OrganizationUserFixture with UserFixture {
  private val guard = new InvitationAccessGuard
  private def human =
    allGenerators[User].normal.generate.copy(archivedAt = None, accountType = UserAccountType.Human, isSuperAdmin = UserIsSuperAdmin(false))
  "current invitation access" should {
    Seq(None, Some(OrganizationUserRole.Employee)).foreach { role =>
      s"authorize a current Human superadmin with membership=$role" in withDatabase {
        for {
          actor <- withUser(human.copy(isSuperAdmin = UserIsSuperAdmin(true)))
          org   <- withOrganization(allGenerators[Organization].normal.generate.copy(archivedAt = None))
          _ <- DBIO.sequence(
                 role.toVector.map(value => withOrganizationUser(OrganizationUser(OrganizationUserId.random, org.id, actor.id, Seq(value), None)))
               )
          access <- guard.resolve(actor.id, org.slug, Vector.empty)
        } yield access.map(_.organizationId) must beSome(org.id)
      }
    }
    "reject a superadmin after the current database flag is revoked" in withDatabase {
      for {
        actor  <- withUser(human.copy(isSuperAdmin = UserIsSuperAdmin(true)))
        org    <- withOrganization(allGenerators[Organization].normal.generate.copy(archivedAt = None))
        before <- guard.resolve(actor.id, org.slug, Vector.empty)
        _      <- sqlu"""UPDATE "user" SET is_super_admin=false WHERE id=${actor.id.toString}::uuid"""
        after  <- guard.resolve(actor.id, org.slug, Vector.empty)
      } yield {
        before must beSome
        after must beNone
      }
    }
    Seq("archived", "service-account").foreach { state =>
      s"reject a superadmin with an unavailable $state account" in withDatabase {
        for {
          actor <- withUser(
                     human.copy(
                       isSuperAdmin = UserIsSuperAdmin(true),
                       archivedAt = Option.when(state == "archived")(UserArchivedAt(Instant.now())),
                       accountType = if (state == "service-account") UserAccountType.ServiceAccount else UserAccountType.Human
                     )
                   )
          org    <- withOrganization(allGenerators[Organization].normal.generate.copy(archivedAt = None))
          access <- guard.resolve(actor.id, org.slug, Vector.empty)
        } yield access must beNone
      }
    }
    "reject a superadmin for an archived organization" in withDatabase {
      for {
        actor  <- withUser(human.copy(isSuperAdmin = UserIsSuperAdmin(true)))
        org    <- withOrganization(allGenerators[Organization].normal.generate.copy(archivedAt = Some(OrganizationArchivedAt(Instant.now()))))
        access <- guard.resolve(actor.id, org.slug, Vector.empty)
      } yield access must beNone
    }
    "preserve current member and Human candidate sets for a superadmin" in withDatabase {
      for {
        actor    <- withUser(human.copy(isSuperAdmin = UserIsSuperAdmin(true)))
        member   <- withUser(human)
        archived <- withUser(human.copy(archivedAt = Some(UserArchivedAt(Instant.now()))))
        service  <- withUser(human.copy(accountType = UserAccountType.ServiceAccount))
        org      <- withOrganization(allGenerators[Organization].normal.generate.copy(archivedAt = None))
        candidates = Vector(member.id, archived.id, service.id)
        _ <- DBIO.sequence(
               candidates.map(id =>
                 withOrganizationUser(OrganizationUser(OrganizationUserId.random, org.id, id, Seq(OrganizationUserRole.Employee), None))
               )
             )
        access <- guard.resolve(actor.id, org.slug, candidates)
      } yield {
        access must beSome
        access.get.activeMembers mustEqual Set(member.id, service.id)
        access.get.activeHumanUsers mustEqual Set(actor.id, member.id)
      }
    }
    Seq(OrganizationUserRole.Owner, OrganizationUserRole.Manager, OrganizationUserRole.Employee).foreach { role =>
      s"authorize only current Human Owner, role=$role" in withDatabase {
        for {
          actor  <- withUser(human)
          org    <- withOrganization(allGenerators[Organization].normal.generate.copy(archivedAt = None))
          _      <- withOrganizationUser(OrganizationUser(OrganizationUserId.random, org.id, actor.id, Seq(role), None))
          access <- guard.resolve(actor.id, org.slug, Vector.empty)
        } yield access.isDefined shouldEqual (role == OrganizationUserRole.Owner)
      }
    }
    Seq("archived", "service-account").foreach { state =>
      s"reject a current Owner with an unavailable $state account" in withDatabase {
        for {
          actor <- withUser(
                     allGenerators[User].normal.generate.copy(
                       archivedAt = Option.when(state == "archived")(UserArchivedAt(Instant.now())),
                       accountType = if (state == "service-account") UserAccountType.ServiceAccount else UserAccountType.Human
                     )
                   )
          org    <- withOrganization(allGenerators[Organization].normal.generate.copy(archivedAt = None))
          _      <- withOrganizationUser(OrganizationUser(OrganizationUserId.random, org.id, actor.id, Seq(OrganizationUserRole.Owner), None))
          access <- guard.resolve(actor.id, org.slug, Vector.empty)
        } yield access must beNone
      }
    }
    "reject a revoked Owner" in withDatabase {
      for {
        actor <- withUser(human)
        org   <- withOrganization(allGenerators[Organization].normal.generate.copy(archivedAt = None))
        _ <- withOrganizationUser(
               OrganizationUser(
                 OrganizationUserId.random,
                 org.id,
                 actor.id,
                 Seq(OrganizationUserRole.Owner),
                 Some(OrganizationUserArchivedAt(Instant.now()))
               )
             )
        access <- guard.resolve(actor.id, org.slug, Vector.empty)
      } yield access should beNone
    }
  }
}
