package pl.iterators.epoca.organization.services

import cats.effect.IO
import cats.effect.kernel.{Clock, Sync}
import cats.effect.unsafe.IORuntime
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationId, OrganizationSlug}
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.organization.services.OrganizationUserDeassignService.OrganizationDeassignResult._
import pl.iterators.epoca.organization.services.OrganizationUserDeassignService.{OrganizationDeassignRequest, OrganizationDeassignResult}
import pl.iterators.epoca.organization.services.OrganizationUserMembershipService.OrganizationUserMembershipResult
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.service.SealedMonadServiceIODBIO
import pl.iterators.sealedmonad.Sealed
import pl.iterators.sealedmonad.syntax._

class OrganizationUserDeassignService(
  transactor: PostgresIOTransactor,
  organizationUserMembershipService: OrganizationUserMembershipService,
  organizationRepository: OrganizationRepository,
  organizationUserRepository: OrganizationUserRepository
)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIODBIO[OrganizationDeassignResult](ioRuntime)
    with LoggingSupport {

  def deassign(
    authContext: AuthContext,
    slug: OrganizationSlug,
    request: OrganizationDeassignRequest
  ): IO[OrganizationDeassignResult] =
    (for {
      _            <- Logger[IO].info(s"auth.id=${authContext.id}, organizationSlug=$slug, id=${request.userId}").seal
      organization <- findOrganization(slug)
      _            <- ensureNotCreator(organization, request)
      _            <- checkPermission(authContext, organization)
      _            <- validateRequest(authContext, request)
      orgUser      <- findOrgUser(organization.id, request)
      _            <- deassign(orgUser)
      _            <- Logger[IO].info(s"successfully deassigned user ${request.userId} from organization $slug").seal
    } yield Deassigned).run

  private def findOrganization(slug: OrganizationSlug): StepIO[Organization] =
    transactor
      .execute {
        organizationRepository
          .find(OrganizationFilters(slug = Some(slug)))
      }
      .valueOrF {
        Logger[IO].error(s"organization $slug does not exist").as(OrganizationNotFound)
      }

  private def ensureNotCreator(organization: Organization, request: OrganizationDeassignRequest): StepIO[Unit] =
    Sync[IO]
      .pure(organization.createdBy != request.userId)
      .ensureF(identity, Logger[IO].error("Organization creator cannot be deassigned").as(OwnerNotDeassignable))
      .void

  private def checkPermission(authContext: AuthContext, organization: Organization): StepIO[Unit] =
    if (authContext.isAdmin) {
      Sealed.liftF(())
    } else {
      organizationUserMembershipService
        .membershipById(organization.id, authContext.id)
        .ensureF(
          _ == OrganizationUserMembershipResult.Admin,
          Logger[IO].warning(s"user ${authContext.id} is not org admin").as(OperationNotPermitted)
        )
        .map(_ => ())
    }

  private def validateRequest(authContext: AuthContext, request: OrganizationDeassignRequest): StepIO[Boolean] =
    Sync[IO]
      .pure(authContext.isAdmin || authContext.id != request.userId)
      .ensure(identity, InvalidRequest)

  private def findOrgUser(id: OrganizationId, request: OrganizationDeassignRequest): StepIO[OrganizationUser] =
    transactor
      .execute {
        organizationUserRepository
          .find(OrganizationUserFilters(organizationId = Some(id), userId = Some(request.userId)))
      }
      .valueOrF {
        Logger[IO].error(s"user $request.userId is already not assigned to organization $id").as(UserAlreadyUnassigned)
      }

  private def deassign(orgUser: OrganizationUser): StepIO[Int] =
    Clock[IO].instantNow.flatMap { now =>
      transactor.execute {
        organizationUserRepository
          .delete(orgUser.id, now)
      }
    }.seal
}

object OrganizationUserDeassignService {
  final case class OrganizationDeassignRequest(userId: UserId)

  sealed trait OrganizationDeassignResult
  object OrganizationDeassignResult {
    final case object Deassigned            extends OrganizationDeassignResult
    final case object OperationNotPermitted extends OrganizationDeassignResult
    final case object OwnerNotDeassignable  extends OrganizationDeassignResult
    final case object OrganizationNotFound  extends OrganizationDeassignResult
    final case object UserAlreadyUnassigned extends OrganizationDeassignResult
    final case object InvalidRequest        extends OrganizationDeassignResult
  }
}
