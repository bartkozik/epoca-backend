package pl.iterators.epoca.invitation.services

import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.invitation.repositories.InvitationRepository.InvitationFilters
import pl.iterators.epoca.invitation.repositories.InvitationRepositoryImpl
import pl.iterators.epoca.invitation.services.OrganizationInvitationListService.OrganizationInvitationListResult
import pl.iterators.epoca.organization.domain.OrganizationDomain._
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.organization.repositories.fixtures.{OrganizationFixture, OrganizationUserFixture}
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.domain.UserDomain._
import pl.iterators.epoca.user.repositories.fixtures.UserFixture
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import pl.iterators.epoca.utils.transactor.{IsolationLevel, TransactorCall}
import pl.iterators.epoca.utils.{AuthContextProvider, PgRepositorySpec, ServiceSpec}

import java.time.Instant

class OrganizationInvitationListServiceSpec extends PgRepositorySpec with OrganizationFixture with OrganizationUserFixture with UserFixture {
  private val service = new OrganizationInvitationListService(null, new InvitationRepositoryImpl, new InvitationAccessGuard)
  "legacy invitation history" should {
    "allow a current database superadmin even when the token has no admin claim" in withDatabase {
      for {
        actor <-
          withUser(
            allGenerators[User].normal.generate.copy(archivedAt = None, accountType = UserAccountType.Human, isSuperAdmin = UserIsSuperAdmin(true))
          )
        org    <- withOrganization(allGenerators[Organization].normal.generate.copy(archivedAt = None))
        result <- service.listAction(AuthContextProvider.user.copy(id = actor.id), org.slug, InvitationFilters())
      } yield result must beAnInstanceOf[OrganizationInvitationListResult.Ok]
    }
    "deny a revoked database superadmin even when the token still claims admin" in withDatabase {
      for {
        actor <-
          withUser(
            allGenerators[User].normal.generate.copy(archivedAt = None, accountType = UserAccountType.Human, isSuperAdmin = UserIsSuperAdmin(true))
          )
        org    <- withOrganization(allGenerators[Organization].normal.generate.copy(archivedAt = None))
        before <- service.listAction(AuthContextProvider.admin.copy(id = actor.id), org.slug, InvitationFilters())
        _      <- sqlu"""UPDATE "user" SET is_super_admin=false WHERE id=${actor.id.toString}::uuid"""
        after  <- service.listAction(AuthContextProvider.admin.copy(id = actor.id), org.slug, InvitationFilters())
      } yield {
        before must beAnInstanceOf[OrganizationInvitationListResult.Ok]
        after mustEqual OrganizationInvitationListResult.OperationNotPermitted
      }
    }
    Seq(OrganizationUserRole.Owner, OrganizationUserRole.Manager, OrganizationUserRole.Employee).foreach { role =>
      s"require current Owner membership when only the token claims superadmin ($role)" in withDatabase {
        for {
          actor <-
            withUser(
              allGenerators[User].normal.generate.copy(archivedAt = None, accountType = UserAccountType.Human, isSuperAdmin = UserIsSuperAdmin(false))
            )
          org    <- withOrganization(allGenerators[Organization].normal.generate.copy(archivedAt = None))
          _      <- withOrganizationUser(OrganizationUser(OrganizationUserId.random, org.id, actor.id, Seq(role), None))
          result <- service.listAction(AuthContextProvider.admin.copy(id = actor.id), org.slug, InvitationFilters())
        } yield
          if (role == OrganizationUserRole.Owner) result must beAnInstanceOf[OrganizationInvitationListService.OrganizationInvitationListResult.Ok]
          else result mustEqual OrganizationInvitationListService.OrganizationInvitationListResult.OperationNotPermitted
      }
    }
    "deny an archived Owner membership" in withDatabase {
      for {
        actor <-
          withUser(
            allGenerators[User].normal.generate.copy(archivedAt = None, accountType = UserAccountType.Human, isSuperAdmin = UserIsSuperAdmin(false))
          )
        org <- withOrganization(allGenerators[Organization].normal.generate.copy(archivedAt = None))
        _ <- withOrganizationUser(
               OrganizationUser(
                 OrganizationUserId.random,
                 org.id,
                 actor.id,
                 Seq(OrganizationUserRole.Owner),
                 Some(OrganizationUserArchivedAt(Instant.now()))
               )
             )
        result <- service.listAction(AuthContextProvider.admin.copy(id = actor.id), org.slug, InvitationFilters())
      } yield result mustEqual OrganizationInvitationListResult.OperationNotPermitted
    }
    "deny a token-only superadmin with no membership" in withDatabase {
      for {
        actor <-
          withUser(
            allGenerators[User].normal.generate.copy(archivedAt = None, accountType = UserAccountType.Human, isSuperAdmin = UserIsSuperAdmin(false))
          )
        org    <- withOrganization(allGenerators[Organization].normal.generate.copy(archivedAt = None))
        result <- service.listAction(AuthContextProvider.admin.copy(id = actor.id), org.slug, InvitationFilters())
      } yield result mustEqual OrganizationInvitationListService.OrganizationInvitationListResult.OperationNotPermitted
    }
  }
}

class OrganizationInvitationListTransactionSpec extends ServiceSpec {
  "list" should {
    "run access checks, page and count in one RepeatableRead transaction" in new BaseScope {
      val organizationSlug: OrganizationSlug = allGenerators[Organization].normal.generate.slug
      val filter: InvitationFilters          = InvitationFilters()
      val sut = new OrganizationInvitationListService(transactor, null, null) {
        override private[invitation] def listAction(
          authContext: AuthContext,
          slug: OrganizationSlug,
          filters: InvitationFilters
        ): slick.dbio.DBIO[OrganizationInvitationListResult] = {
          require(authContext == authUser && slug == organizationSlug && filters == filter)
          slick.dbio.DBIO.successful(OrganizationInvitationListResult.OperationNotPermitted)
        }
      }

      sut.list(authUser, organizationSlug, filter).asserting(_ shouldEqual OrganizationInvitationListResult.OperationNotPermitted)
      transactorCalls shouldEqual Seq(TransactorCall.ExecuteTransactionallyAt(IsolationLevel.RepeatableRead))
    }
  }
}
