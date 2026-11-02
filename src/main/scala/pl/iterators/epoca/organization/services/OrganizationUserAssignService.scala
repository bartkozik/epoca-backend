package pl.iterators.epoca.organization.services

import cats.effect.IO
import cats.effect.kernel.Sync
import cats.effect.unsafe.IORuntime
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationId, OrganizationSlug, OrganizationUserRole}
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories._
import pl.iterators.epoca.organization.services.OrganizationUserAssignService.OrganizationUserAssignResult._
import pl.iterators.epoca.organization.services.OrganizationUserAssignService._
import pl.iterators.epoca.organization.services.OrganizationUserMembershipService.OrganizationUserMembershipResult
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.service.SealedMonadServiceIODBIO
import pl.iterators.sealedmonad.Sealed
import pl.iterators.sealedmonad.syntax._

class OrganizationUserAssignService(
  transactor: PostgresIOTransactor,
  organizationUserMembershipService: OrganizationUserMembershipService,
  organizationRepository: OrganizationRepository,
  organizationUserRepository: OrganizationUserRepository
)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIODBIO[OrganizationUserAssignResult](ioRuntime)
    with LoggingSupport {

  def assign(
    authContext: AuthContext,
    slug: OrganizationSlug,
    request: OrganizationUserAssignRequest
  ): IO[OrganizationUserAssignResult] =
    (for {
      _            <- Logger[IO].info(s"auth.id=${authContext.id}, organizationSlug=$slug, userId=$request").seal
      _            <- validateRolesNonEmpty(request)
      organization <- findOrganization(slug)
      _            <- checkPermission(authContext, organization)
      _            <- validateRequest(authContext, request)
      orgUser      <- findOrgUser(organization.id, request)
      _            <- upsertOrgUser(orgUser, request)
      _ <- Logger[IO]
             .info(s" successfully assigned user $request to organization ${organization.id}")
             .seal
    } yield Assigned).run

  private def validateRolesNonEmpty(request: OrganizationUserAssignRequest): StepIO[Unit] =
    ().liftSealed[IO, OrganizationUserAssignResult]
      .ensureF(_ => request.roles.nonEmpty, Logger[IO].warning("request have empty roles").as(EmptyRoles))

  private def findOrganization(slug: OrganizationSlug): StepIO[Organization] =
    transactor
      .execute {
        organizationRepository
          .find(OrganizationFilters(slug = Some(slug)))
      }
      .valueOrF {
        Logger[IO].error(s"organization $slug does not exist").as(OrganizationNotFound)
      }

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
        .void
    }

  private def validateRequest(authContext: AuthContext, request: OrganizationUserAssignRequest): StepIO[Boolean] =
    Sync[IO]
      .pure(authContext.isAdmin || authContext.id != request.userId || request.roles.contains(OrganizationUserRole.Owner))
      .ensure(identity, InvalidRequest)

  private def findOrgUser(id: OrganizationId, request: OrganizationUserAssignRequest): StepIO[OrganizationUser] =
    transactor
      .execute(
        organizationUserRepository
          .find(OrganizationUserFilters(organizationId = Some(id), userId = Some(request.userId)))
      )
      .valueOrF(Logger[IO].error(s"user is not organization member or hasn't accepted invitation").as(UserIsNotOrganizationMember))

  private def upsertOrgUser(organizationUser: OrganizationUser, request: OrganizationUserAssignRequest): StepIO[OrganizationUser] =
    transactor.execute {
      organizationUserRepository.upsert(organizationUser.copy(roles = request.roles))
    }.seal
}

object OrganizationUserAssignService {
  final case class OrganizationUserAssignRequest(userId: UserId, roles: Seq[OrganizationUserRole])

  sealed trait OrganizationUserAssignResult
  object OrganizationUserAssignResult {
    final case object Assigned                    extends OrganizationUserAssignResult
    final case object OrganizationNotFound        extends OrganizationUserAssignResult
    final case object OperationNotPermitted       extends OrganizationUserAssignResult
    final case object InvalidRequest              extends OrganizationUserAssignResult
    final case object UserIsNotOrganizationMember extends OrganizationUserAssignResult
    final case object EmptyRoles                  extends OrganizationUserAssignResult
    final case object UserAlreadyAssigned         extends OrganizationUserAssignResult
  }
}
