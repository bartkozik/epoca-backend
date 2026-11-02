package pl.iterators.epoca.project.services

import cats.effect._
import cats.effect.unsafe.IORuntime
import cats.implicits._
import io.scalaland.chimney.dsl._
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.client.domain.ClientDomain.ClientId
import pl.iterators.epoca.client.repositories.ClientRepository
import pl.iterators.epoca.client.repositories.ClientRepository.ClientFilters
import pl.iterators.epoca.organization.domain.Organization
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.project.domain.ProjectDomain._
import pl.iterators.epoca.project.domain.{Project, ProjectUser, ProjectUserRole}
import pl.iterators.epoca.project.repositories.ProjectRepository.ProjectFilters
import pl.iterators.epoca.project.repositories.ProjectUserRepository.ProjectUserNotPermitted
import pl.iterators.epoca.project.repositories.{ProjectRepository, ProjectUserRepository}
import pl.iterators.epoca.project.services.ProjectCreateService.ProjectCreateResult._
import pl.iterators.epoca.project.services.ProjectCreateService.{ProjectCreateRequest, ProjectCreateResult}
import pl.iterators.epoca.project.services.ProjectDtoService.ProjectDto
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.random.IdGenerator
import pl.iterators.epoca.utils.service.SealedMonadServiceIODBIO
import pl.iterators.sealedmonad.Sealed
import pl.iterators.sealedmonad.syntax._
import slick.dbio.DBIO

class ProjectCreateService(
  transactor: PostgresIOTransactor,
  organizationRepository: OrganizationRepository,
  organizationUserRepository: OrganizationUserRepository,
  projectUserRepository: ProjectUserRepository,
  clientRepository: ClientRepository,
  projectRepository: ProjectRepository,
  projectDtoService: ProjectDtoService
)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIODBIO[ProjectCreateResult](ioRuntime)
    with LoggingSupport {

  def create(
    authContext: AuthContext,
    organizationSlug: OrganizationSlug,
    request: ProjectCreateRequest
  ): IO[ProjectCreateResult] =
    transactor
      .executeTransactionally {
        (for {
          _            <- Logger[DBIO].info(s"auth.id=${authContext.id}, organization=$organizationSlug").seal
          organization <- findOrganization(organizationSlug)
          _            <- checkAccessToOrganization(authContext, organization)
          _            <- clientRepository.lockForMutation(request.clientId).seal
          _            <- checkClientExists(request.clientId, organization)
          _            <- validateUniqueProjectName(organization, request.name)
          _            <- checkUsersHaveAccessToOrganization(organization, request)
          project      <- prepareProject(authContext.id, organization, request)
          result       <- insertProject(project)
          toAssign     <- prepareProjectUsers(authContext.id, project, request)
          _            <- assignUsers(project, toAssign)
          projectDto   <- projectDtoService.prepareFindDto(result).seal
        } yield Created(projectDto)).run
      }
      .handleErrorWith {
        case ProjectUserNotPermitted => IO.pure(ProjectUsersHaveNoAccessToOrganization)
        case error                   => IO.raiseError(error)
      }

  private def findOrganization(slug: OrganizationSlug): StepDBIO[Organization] =
    organizationRepository
      .find(OrganizationFilters(slug = Some(slug)))
      .valueOrF {
        Logger[DBIO].error(s"organization $slug does not exist").as(OrganizationNotFound)
      }

  private def checkAccessToOrganization(authContext: AuthContext, organization: Organization): StepDBIO[Unit] =
    if (authContext.isSuperAdmin) Sealed.liftF(())
    else
      organizationUserRepository
        .find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authContext.id)))
        .ensureF(
          _.exists(_.hasManagementRole),
          Logger[DBIO].warning(s"user ${authContext.id} has insufficient role in organization ${organization.id}").as(NoAccessToOrganization)
        )
        .void

  private def checkClientExists(clientId: ClientId, organization: Organization): StepDBIO[Unit] =
    clientRepository
      .find(ClientFilters(id = Some(clientId), organizationIds = Some(Seq(organization.id))))
      .valueOrF {
        Logger[DBIO].error(s"client $clientId does not exist in organization $organization.id").as(ClientNotFound)
      }
      .void

  private def validateUniqueProjectName(organization: Organization, name: ProjectName): StepDBIO[Unit] =
    projectRepository
      .find(ProjectFilters(organizationIds = Some(Seq(organization.id)), name = Some(name)))
      .ensureF(
        _.isEmpty,
        Logger[DBIO].error(s"project with name $name can't be created - another project with the same name is active").as(ProjectNameNotUnique)
      )
      .void

  private def prepareProject(
    userId: UserId,
    organization: Organization,
    request: ProjectCreateRequest
  ): StepDBIO[Project] =
    (Clock[DBIO].instantNow.flatMap { now =>
      IdGenerator.nextId[DBIO, ProjectId].map { projectId =>
        request
          .into[Project]
          .withFieldConst(_.id, projectId)
          .withFieldConst(_.organizationId, organization.id)
          .withFieldConst(_.createdAt, ProjectCreatedAt(now))
          .withFieldConst(_.createdBy, userId)
          .withFieldConst(_.modifiedAt, ProjectModifiedAt(now))
          .withFieldConst(_.modifiedBy, userId)
          .withFieldConst(_.archivedAt, None)
          .withFieldConst(_.archivedBy, None)
          .transform
      }
    }: DBIO[Project]).seal

  private def insertProject(project: Project): StepDBIO[Project] =
    (Logger[DBIO].info(s"successfully created project ${project.id} in organization ${project.organizationId}") *> projectRepository.insert(
      project
    )).seal

  private def checkUsersHaveAccessToOrganization(organization: Organization, request: ProjectCreateRequest): StepDBIO[Unit] = {
    val projectUserIds = (request.ownerIds ++ request.memberIds).toSeq
    organizationUserRepository
      .list(OrganizationUserFilters(organizationId = Some(organization.id), userIds = Some(projectUserIds)))
      .ensureF(
        _.length == projectUserIds.length,
        Logger[DBIO]
          .error(s"not all users have access to organization ${organization.id}")
          .as(ProjectUsersHaveNoAccessToOrganization)
      )
      .map(_ => ())
  }

  private def prepareProjectUsers(
    creatorId: UserId,
    project: Project,
    request: ProjectCreateRequest
  ): StepDBIO[Seq[ProjectUser]] = ProjectUser
    .getProjectUserChanges(request.ownerIds + creatorId, request.memberIds, Set.empty)
    .toAssign
    .traverse(prepareProjectUser(project, _))

  private def prepareProjectUser(project: Project, projectUserRole: ProjectUserRole): StepDBIO[ProjectUser] =
    (Clock[DBIO].instantNow.flatMap { now =>
      IdGenerator.nextId[DBIO, ProjectUserId].map { projectUserId =>
        ProjectUser(
          id = projectUserId,
          projectId = project.id,
          userId = projectUserRole.userId,
          role = projectUserRole.role,
          createdAt = ProjectUserCreatedAt(now),
          archivedAt = None
        )
      }
    }: DBIO[ProjectUser]).seal

  private def assignUsers(project: Project, projectUsers: Seq[ProjectUser]): StepDBIO[Unit] =
    (Logger[DBIO].info(
      s"successfully assigned project users for project ${project.id} in organization ${project.organizationId}"
    ) *> projectUserRepository.insert(projectUsers)).seal.map(_ => ())

}

object ProjectCreateService {
  final case class ProjectCreateRequest(
    name: ProjectName,
    description: Option[ProjectDescription],
    clientId: ClientId,
    ownerIds: Set[UserId],
    memberIds: Set[UserId])

  sealed trait ProjectCreateResult
  object ProjectCreateResult {
    final case class Created(project: ProjectDto)            extends ProjectCreateResult
    final case object NoAccessToOrganization                 extends ProjectCreateResult
    final case object ProjectUsersHaveNoAccessToOrganization extends ProjectCreateResult
    final case object OrganizationNotFound                   extends ProjectCreateResult
    final case object ClientNotFound                         extends ProjectCreateResult
    final case object ProjectNameNotUnique                   extends ProjectCreateResult
  }

}
