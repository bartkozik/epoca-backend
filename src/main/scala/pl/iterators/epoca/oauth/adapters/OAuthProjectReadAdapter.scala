package pl.iterators.epoca.oauth.adapters

import cats.effect.IO
import pl.iterators.epoca.client.domain.ClientDomain.ClientId
import pl.iterators.epoca.client.repositories.ClientRepository
import pl.iterators.epoca.client.repositories.ClientRepository.ClientFilters
import pl.iterators.epoca.oauth.adapters.OAuthReadResult.{NotFound, Ok}
import pl.iterators.epoca.oauth.domain.OAuthDomain.OAuthProjectAccess
import pl.iterators.epoca.organization.domain.Organization
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.organization.repositories.OrganizationRepository
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.project.domain.ProjectDomain.ProjectId
import pl.iterators.epoca.project.domain.{Project, ProjectUser}
import pl.iterators.epoca.project.repositories.ProjectRepository.ProjectFilters
import pl.iterators.epoca.project.repositories.ProjectUserRepository.ProjectUserFilters
import pl.iterators.epoca.project.repositories.{ProjectRepository, ProjectUserRepository}
import pl.iterators.epoca.project.services.ProjectDtoService.{ProjectClientDto, ProjectDto, ProjectListDto, ProjectUserDto}
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.domain.UserDomain.UserAccountType
import pl.iterators.epoca.user.repositories.UserRepository
import pl.iterators.epoca.user.repositories.UserRepository.UserFilters
import pl.iterators.epoca.utils.db.{PaginatedResult, PostgresIOTransactor}
import slick.dbio.DBIO

import scala.concurrent.ExecutionContext

trait OAuthProjectReader {
  def list(
    policy: OAuthAccessPolicy,
    slug: OrganizationSlug,
    filters: ProjectFilters
  ): IO[OAuthReadResult[PaginatedResult[ProjectListDto]]]

  def find(
    policy: OAuthAccessPolicy,
    slug: OrganizationSlug,
    projectId: ProjectId
  ): IO[OAuthReadResult[ProjectDto]]
}

final class OAuthProjectReadAdapter(
  transactor: PostgresIOTransactor,
  organizationRepository: OrganizationRepository,
  projectRepository: ProjectRepository,
  projectUserRepository: ProjectUserRepository,
  clientRepository: ClientRepository,
  userRepository: UserRepository
)(implicit ec: ExecutionContext)
    extends OAuthProjectReader {

  def list(
    policy: OAuthAccessPolicy,
    slug: OrganizationSlug,
    filters: ProjectFilters
  ): IO[OAuthReadResult[PaginatedResult[ProjectListDto]]] =
    transactor.executeTransactionally {
      findBoundOrganization(policy, slug).flatMap {
        case None => DBIO.successful(NotFound)
        case Some(_) =>
          listVisibleProjects(policy, filters).flatMap { case (count, projects) =>
            prepareListDtos(projects).map { dtos =>
              Ok(PaginatedResult(count, dtos))
            }
          }
      }
    }

  def find(
    policy: OAuthAccessPolicy,
    slug: OrganizationSlug,
    projectId: ProjectId
  ): IO[OAuthReadResult[ProjectDto]] =
    transactor.executeTransactionally {
      if (!policy.allows(projectId)) DBIO.successful(NotFound)
      else
        findBoundOrganization(policy, slug).flatMap {
          case None => DBIO.successful(NotFound)
          case Some(_) =>
            projectRepository
              .find(ProjectFilters(id = Some(projectId), organizationIds = Some(Seq(policy.organizationId)), includeArchived = true))
              .flatMap {
                case Some(project) if project.organizationId == policy.organizationId && policy.allows(project.id) =>
                  prepareFindDto(project).map(Ok.apply)
                case _ => DBIO.successful(NotFound)
              }
        }
    }

  private def findBoundOrganization(policy: OAuthAccessPolicy, slug: OrganizationSlug): DBIO[Option[Organization]] =
    organizationRepository
      .find(OrganizationFilters(id = Some(policy.organizationId), slug = Some(slug), includeArchived = true))
      .map(_.filter(organization => organization.id == policy.organizationId && organization.slug == slug))

  private def listVisibleProjects(policy: OAuthAccessPolicy, filters: ProjectFilters): DBIO[(Int, Seq[Project])] =
    intersectProjectIds(policy.projectAccess, filters.id, filters.ids) match {
      case Some(projectIds) if projectIds.isEmpty => DBIO.successful(0 -> Seq.empty)
      case boundedIds =>
        val boundedFilter =
          filters.copy(id = None, ids = boundedIds.map(_.toSeq), organizationIds = Some(Seq(policy.organizationId)))
        for {
          count    <- projectRepository.count(boundedFilter)
          projects <- projectRepository.list(boundedFilter)
        } yield count -> projects.filter(project => project.organizationId == policy.organizationId && policy.allows(project.id))
    }

  private def intersectProjectIds(
    access: OAuthProjectAccess,
    requestedId: Option[ProjectId],
    requestedIds: Option[Seq[ProjectId]]
  ): Option[Set[ProjectId]] = {
    val requested = (requestedId.map(Set(_)), requestedIds.map(_.toSet)) match {
      case (Some(single), Some(many)) => Some(single intersect many)
      case (Some(single), None)       => Some(single)
      case (None, Some(many))         => Some(many)
      case (None, None)               => None
    }
    access match {
      case OAuthProjectAccess.AllProjects                  => requested
      case OAuthProjectAccess.SelectedProjects(projectIds) => Some(requested.fold(projectIds)(_ intersect projectIds))
    }
  }

  private def prepareListDtos(projects: Seq[Project]): DBIO[Seq[ProjectListDto]] =
    for {
      clients      <- listClients(projects)
      projectUsers <- listHumanProjectUsers(projects)
      clientMap      = clients.map(client => client.id -> ProjectClientDto.from(client)).toMap
      projectUserMap = projectUsers.groupBy(_.projectId)
    } yield projects.map(project => ProjectListDto.from(project, clientMap, projectUserMap))

  private def prepareFindDto(project: Project): DBIO[ProjectDto] =
    for {
      clients      <- listClients(Seq(project))
      projectUsers <- listHumanProjectUsers(Seq(project))
      users        <- listUsers(projectUsers)
      userMap   = users.map(user => user.id -> user).toMap
      userDtos  = projectUsers.flatMap(projectUser => userMap.get(projectUser.userId).map(ProjectUserDto.from(_, projectUser)))
      clientDto = clients.find(_.id == project.clientId).map(ProjectClientDto.from)
    } yield ProjectDto.from(project, clientDto, userDtos)

  private def listClients(projects: Seq[Project]) = {
    val clientIds: Seq[ClientId] = projects.map(_.clientId).distinct
    if (clientIds.isEmpty) DBIO.successful(Seq.empty)
    else
      clientRepository
        .list(ClientFilters(ids = Some(clientIds), organizationIds = Some(Seq(projects.head.organizationId)), includeArchived = true))
        .map(_.filter(client => clientIds.contains(client.id) && client.organizationId == projects.head.organizationId).distinctBy(_.id))
  }

  private def listHumanProjectUsers(projects: Seq[Project]): DBIO[Seq[ProjectUser]] = {
    val projectIds = projects.map(_.id)
    if (projectIds.isEmpty) DBIO.successful(Seq.empty)
    else
      for {
        projectUsers <- projectUserRepository.list(ProjectUserFilters(projectIds = Some(projectIds)))
        users        <- listUsers(projectUsers)
        humanIds = users.map(_.id).toSet
      } yield projectUsers.filter(projectUser => projectIds.contains(projectUser.projectId) && humanIds.contains(projectUser.userId))
  }

  private def listUsers(projectUsers: Seq[ProjectUser]): DBIO[Seq[User]] = {
    val userIds = projectUsers.map(_.userId).distinct
    if (userIds.isEmpty) DBIO.successful(Seq.empty)
    else
      userRepository
        .list(UserFilters(ids = Some(userIds), includeArchived = true))
        .map(_.filter(user => userIds.contains(user.id) && user.accountType == UserAccountType.Human).distinctBy(_.id))
  }
}
