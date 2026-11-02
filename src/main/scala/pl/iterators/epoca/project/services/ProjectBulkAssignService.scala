package pl.iterators.epoca.project.services

import cats.Monad
import cats.effect.unsafe.IORuntime
import cats.effect.{Clock, IO}
import cats.implicits._
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.client.domain.ClientDomain.{ClientId, ClientType}
import pl.iterators.epoca.client.repositories.ClientRepository
import pl.iterators.epoca.client.repositories.ClientRepository.ClientFilters
import pl.iterators.epoca.organization.domain.Organization
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.project.domain.ProjectDomain._
import pl.iterators.epoca.project.domain.ProjectUser
import pl.iterators.epoca.project.repositories.ProjectRepository.ProjectFilters
import pl.iterators.epoca.project.repositories.ProjectUserRepository.{ProjectUserFilters, ProjectUserNotPermitted}
import pl.iterators.epoca.project.repositories._
import pl.iterators.epoca.project.services.ProjectBulkAssignService.ProjectBulkAssignResult._
import pl.iterators.epoca.project.services.ProjectBulkAssignService._
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.random.IdGenerator
import pl.iterators.epoca.utils.service.SealedMonadServiceIODBIO
import pl.iterators.sealedmonad.Sealed
import pl.iterators.sealedmonad.syntax._
import slick.dbio.DBIO

class ProjectBulkAssignService(
  transactor: PostgresIOTransactor,
  clientRepository: ClientRepository,
  projectRepository: ProjectRepository,
  projectUserRepository: ProjectUserRepository,
  organizationRepository: OrganizationRepository,
  organizationUserRepository: OrganizationUserRepository
)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIODBIO[ProjectBulkAssignResult](ioRuntime)
    with LoggingSupport {

  def assign(
    authContext: AuthContext,
    organizationSlug: OrganizationSlug,
    userId: UserId,
    request: ProjectBulkAssignRequest
  ): IO[ProjectBulkAssignResult] = transactor
    .executeTransactionally {
      (for {
        organization      <- findOrganization(organizationSlug)
        _                 <- validateAuthHasRoles(authContext, organization)
        _                 <- validateUserIsMember(userId, organization)
        clientIdsOpt      <- listClientIdsOpt(request, organization)
        projectIds        <- listProjectIds(organization, clientIdsOpt)
        projectUsers      <- listCurrentProjectUsers(projectIds, userId)
        newProjectIds     <- filterExistingProjects(projectIds, projectUsers)
        addedProjectUsers <- prepareProjectUsers(newProjectIds, userId)
        _                 <- insertProjectUsers(addedProjectUsers)
      } yield Assigned(ProjectBulkAssignResponse(addedProjectUsers.map(_.projectId)))).run
    }
    .handleErrorWith {
      case ProjectUserNotPermitted => IO.pure(NotAMember)
      case error                   => IO.raiseError(error)
    }

  private def findOrganization(slug: OrganizationSlug): StepDBIO[Organization] =
    organizationRepository
      .find(OrganizationFilters(slug = Some(slug)))
      .valueOrF(Logger[DBIO].error(s"organization $slug does not exist").as(OrganizationNotFound))

  private def validateAuthHasRoles(authContext: AuthContext, organization: Organization): StepDBIO[Unit] =
    organizationUserRepository
      .find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authContext.id)))
      .ensureF(
        orgUser => authContext.isSuperAdmin || authContext.id == organization.createdBy || orgUser.exists(_.hasManagementRole),
        Logger[DBIO].warning(s"user ${authContext.id} doesn't have proper roles in organization ${organization.id}").as(NoAccessToOrganization)
      )
      .void

  private def validateUserIsMember(userId: UserId, organization: Organization): StepDBIO[Unit] =
    organizationUserRepository
      .find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(userId)))
      .valueOrF {
        Logger[DBIO].error(s"user $userId isn't a member of that organization").as(NotAMember)
      }
      .void

  private def listClientIdsOpt(request: ProjectBulkAssignRequest, organization: Organization): StepDBIO[Option[Seq[ClientId]]] =
    if (request.clientId.isEmpty && request.clientType.isEmpty) {
      Monad[DBIO].pure(Option.empty[Seq[ClientId]]).seal
    } else {
      val clientFilters = ClientFilters(`type` = request.clientType, id = request.clientId, organizationIds = Some(Seq(organization.id)))
      val result: DBIO[Option[Seq[ClientId]]] = clientRepository
        .list(clientFilters)
        .map(res => Some(res.map(_.id)))
      result.seal
    }

  private def listProjectIds(organization: Organization, clientIdsOpt: Option[Seq[ClientId]]): StepDBIO[Seq[ProjectId]] = {
    val filter = ProjectFilters(organizationIds = Some(Seq(organization.id)), clientIds = clientIdsOpt)
    val result: DBIO[Seq[ProjectId]] = projectRepository
      .list(filter)
      .map(_.map(_.id))
    result.seal
  }

  private def listCurrentProjectUsers(projectIds: Seq[ProjectId], userId: UserId): StepDBIO[Seq[ProjectUser]] = {
    val result: DBIO[Seq[ProjectUser]] = projectUserRepository
      .list(ProjectUserFilters(projectIds = Some(projectIds), userId = Some(userId)))
    result.seal
  }

  private def filterExistingProjects(projectIds: Seq[ProjectId], projectUsers: Seq[ProjectUser]): StepDBIO[Seq[ProjectId]] =
    Sealed.liftF(projectIds.diff(projectUsers.map(_.projectId)))

  private def prepareProjectUsers(projectIds: Seq[ProjectId], userId: UserId): StepDBIO[Seq[ProjectUser]] =
    (Clock[DBIO].instantNow.flatMap { now =>
      val result: Seq[DBIO[ProjectUser]] = projectIds
        .map { projectId =>
          IdGenerator.nextId[DBIO, ProjectUserId].map { projectUserId =>
            ProjectUser(projectUserId, projectId, userId, ProjectRole.Member, createdAt = ProjectUserCreatedAt(now), archivedAt = None)
          }
        }
      result.traverse(identity)
    }: DBIO[Seq[ProjectUser]]).seal

  private def insertProjectUsers(projectUsers: Seq[ProjectUser]): StepDBIO[Seq[ProjectUser]] =
    projectUserRepository.insert(projectUsers).seal
}

object ProjectBulkAssignService {
  case class ProjectBulkAssignRequest(clientType: Option[ClientType], clientId: Option[ClientId])
  case class ProjectBulkAssignResponse(changedProjectIds: Seq[ProjectId])
  sealed trait ProjectBulkAssignResult
  object ProjectBulkAssignResult {
    final case class Assigned(result: ProjectBulkAssignResponse) extends ProjectBulkAssignResult
    final case object OrganizationNotFound                       extends ProjectBulkAssignResult
    final case object NoAccessToOrganization                     extends ProjectBulkAssignResult
    final case object NotAMember                                 extends ProjectBulkAssignResult
  }
}
