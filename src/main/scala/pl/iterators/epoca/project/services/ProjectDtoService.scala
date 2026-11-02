package pl.iterators.epoca.project.services

import cats.effect.Sync
import cats.effect.unsafe.IORuntime
import cats.implicits._
import io.scalaland.chimney.dsl._
import pl.iterators.epoca.client.domain.Client
import pl.iterators.epoca.client.domain.ClientDomain._
import pl.iterators.epoca.client.repositories.ClientRepository
import pl.iterators.epoca.client.repositories.ClientRepository.ClientFilters
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId
import pl.iterators.epoca.project.domain.ProjectDomain._
import pl.iterators.epoca.project.domain.{Project, ProjectUser}
import pl.iterators.epoca.project.repositories.ProjectUserRepository
import pl.iterators.epoca.project.repositories.ProjectUserRepository.ProjectUserFilters
import pl.iterators.epoca.project.services.ProjectDtoService.{ProjectClientDto, ProjectDto, ProjectListDto, ProjectUserDto}
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.domain.UserDomain.{UserAvatar, UserFirstName, UserId, UserLastName}
import pl.iterators.epoca.user.repositories.UserRepository
import pl.iterators.epoca.user.repositories.UserRepository.UserFilters
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.service.SealedMonadServiceIODBIO
import pl.iterators.sealedmonad.syntax._
import slick.dbio.DBIO

class ProjectDtoService(
  clientRepository: ClientRepository,
  projectUserRepository: ProjectUserRepository,
  userRepository: UserRepository
)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIODBIO(ioRuntime)
    with LoggingSupport {

  def prepareListDtos(projects: Seq[Project]): DBIO[Seq[ProjectListDto]] =
    (for {
      clientMap      <- getClientMap(projects)
      projectUserMap <- getProjectUserMap(projects)
    } yield prepareProjectListDtos(projects, clientMap, projectUserMap)).run

  def prepareFindDto(project: Project): DBIO[ProjectDto] =
    (for {
      client       <- findClient(project)
      projectUsers <- listProjectUserDtos(project)
    } yield ProjectDto.from(project, client, projectUsers)).run

  private def getClientMap(projects: Seq[Project]): StepDBIO[Map[ClientId, ProjectClientDto]] = {
    val clientIds = projects.map(project => project.clientId).distinct
    (clientRepository
      .list(ClientFilters(ids = Some(clientIds), includeArchived = true))
      .map(_.map(client => client.id -> ProjectClientDto.from(client)).toMap): DBIO[Map[ClientId, ProjectClientDto]]).seal
  }

  private def findClient(project: Project): StepDBIO[Option[ProjectClientDto]] =
    (clientRepository
      .find(ClientFilters(id = Some(project.clientId), includeArchived = true))
      .map(_.map(ProjectClientDto.from)): DBIO[Option[ProjectClientDto]]).seal

  private def getProjectUserMap(projects: Seq[Project]): StepDBIO[Map[ProjectId, Seq[ProjectUser]]] =
    listProjectUsers(projects).map(_.groupBy(_.projectId))

  private def listProjectUserDtos(project: Project): StepDBIO[Seq[ProjectUserDto]] =
    for {
      projectUsers <- listProjectUsers(Seq(project))
      users        <- listUsers(projectUsers)
      userDtos     <- prepareProjectUserDtos(projectUsers, users)
    } yield userDtos

  private def listProjectUsers(projects: Seq[Project]): StepDBIO[Seq[ProjectUser]] =
    projectUserRepository.list(ProjectUserFilters(projectIds = Some(projects.map(_.id)))).seal

  private def listUsers(projectUsers: Seq[ProjectUser]): StepDBIO[Seq[User]] =
    userRepository
      .list(UserFilters(ids = Some(projectUsers.map(_.userId))))
      .seal

  private def prepareProjectUserDtos(projectUsers: Seq[ProjectUser], users: Seq[User]): StepDBIO[Seq[ProjectUserDto]] = {
    val usersMap = users.map(user => user.id -> user).toMap

    (projectUsers.traverse(prepareProjectUserDto(usersMap, _)).map(_.flatten): DBIO[Seq[ProjectUserDto]]).seal
  }
  private def prepareProjectUserDto(usersMap: Map[UserId, User], projectUser: ProjectUser): DBIO[Option[ProjectUserDto]] =
    usersMap.get(projectUser.userId) match {
      case Some(user) =>
        Sync[DBIO].pure(Some(ProjectUserDto.from(user, projectUser)))
      case None =>
        Logger[DBIO]
          .error(s"user with ${projectUser.userId} does not exist")
          .map(_ => None)
    }

  private def prepareProjectListDtos(
    projects: Seq[Project],
    clientMap: Map[ClientId, ProjectClientDto],
    userCountMap: Map[ProjectId, Seq[ProjectUser]]
  ): Seq[ProjectListDto] =
    projects.map(r => ProjectListDto.from(r, clientMap, userCountMap))

}

object ProjectDtoService {
  final case class ProjectListDto(
    id: ProjectId,
    name: ProjectName,
    description: Option[ProjectDescription],
    client: Option[ProjectClientDto],
    userCount: ProjectUserCount,
    userIds: Seq[UserId],
    organizationId: OrganizationId,
    createdAt: ProjectCreatedAt,
    createdBy: UserId,
    modifiedAt: ProjectModifiedAt,
    modifiedBy: UserId,
    archivedAt: Option[ProjectArchivedAt])

  final case class ProjectUserDto(
    id: UserId,
    role: ProjectRole,
    firstName: UserFirstName,
    lastName: UserLastName,
    avatar: Option[UserAvatar],
    archivedAt: Option[ProjectUserArchivedAt])

  final case class ProjectDto(
    id: ProjectId,
    name: ProjectName,
    description: Option[ProjectDescription],
    client: Option[ProjectClientDto],
    projectUsers: Seq[ProjectUserDto],
    organizationId: OrganizationId,
    createdAt: ProjectCreatedAt,
    createdBy: UserId,
    modifiedAt: ProjectModifiedAt,
    modifiedBy: UserId,
    archivedAt: Option[ProjectArchivedAt],
    archivedBy: Option[UserId])

  final case class ProjectClientDto(
    id: ClientId,
    name: ClientName,
    color: ClientColor,
    `type`: ClientType,
    texture: ClientTexture)

  object ProjectListDto {
    def from(
      project: Project,
      clientMap: Map[ClientId, ProjectClientDto],
      projectUserMap: Map[ProjectId, Seq[ProjectUser]]
    ): ProjectListDto =
      project
        .into[ProjectListDto]
        .withFieldComputed(_.client, project => clientMap.get(project.clientId))
        .withFieldComputed(_.userCount, project => ProjectUserCount(projectUserMap.getOrElse(project.id, Seq()).length))
        .withFieldComputed(_.userIds, project => projectUserMap.getOrElse(project.id, Seq()).map(_.userId))
        .transform
  }

  object ProjectUserDto {
    def from(user: User, projectUser: ProjectUser): ProjectUserDto = user
      .into[ProjectUserDto]
      .withFieldConst(_.role, projectUser.role)
      .withFieldConst(_.archivedAt, projectUser.archivedAt)
      .transform
  }

  object ProjectDto {
    def from(
      project: Project,
      client: Option[ProjectClientDto],
      projectUsers: Seq[ProjectUserDto]
    ): ProjectDto =
      project
        .into[ProjectDto]
        .withFieldConst(_.client, client)
        .withFieldConst(_.projectUsers, projectUsers)
        .transform
  }

  object ProjectClientDto {
    def from(client: Client): ProjectClientDto = client.transformInto[ProjectClientDto]
  }
}
