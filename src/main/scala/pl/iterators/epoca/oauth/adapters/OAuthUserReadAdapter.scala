package pl.iterators.epoca.oauth.adapters

import cats.effect.IO
import pl.iterators.epoca.client.domain.ClientDomain.{ClientId, ClientName}
import pl.iterators.epoca.client.repositories.ClientRepository
import pl.iterators.epoca.client.repositories.ClientRepository.ClientFilters
import pl.iterators.epoca.oauth.adapters.OAuthReadResult.{NotFound, Ok}
import pl.iterators.epoca.oauth.domain.OAuthDomain.OAuthProjectAccess
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.organization.domain.OrganizationUser
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.organizationdashboard.services.OrganizationUserDetailsService.OrganizationUserDetailsRow
import pl.iterators.epoca.organizationdashboard.services.OrganizationUserListService.OrganizationUserDto
import pl.iterators.epoca.project.domain.ProjectDomain.{ProjectId, ProjectName}
import pl.iterators.epoca.project.domain.{Project, ProjectUser}
import pl.iterators.epoca.project.repositories.ProjectRepository.ProjectFilters
import pl.iterators.epoca.project.repositories.ProjectUserRepository.ProjectUserFilters
import pl.iterators.epoca.project.repositories.{ProjectRepository, ProjectUserRepository}
import pl.iterators.epoca.user.domain.UserDomain.{UserAccountType, UserId, UserIdentityConfirmed}
import pl.iterators.epoca.user.domain.{User, UserIdentity}
import pl.iterators.epoca.user.repositories.UserIdentityRepository.UserIdentityFilters
import pl.iterators.epoca.user.repositories.UserRepository.UserFilters
import pl.iterators.epoca.user.repositories.{UserIdentityRepository, UserRepository}
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import slick.dbio.DBIO

import scala.concurrent.ExecutionContext

trait OAuthUserReader {
  def list(
    policy: OAuthAccessPolicy,
    slug: OrganizationSlug,
    includeArchived: Boolean = false
  ): IO[OAuthReadResult[Seq[OrganizationUserDto]]]

  def details(
    policy: OAuthAccessPolicy,
    slug: OrganizationSlug,
    userId: UserId
  ): IO[OAuthReadResult[Seq[OrganizationUserDetailsRow]]]
}

final class OAuthUserReadAdapter(
  transactor: PostgresIOTransactor,
  organizationRepository: OrganizationRepository,
  organizationUserRepository: OrganizationUserRepository,
  projectRepository: ProjectRepository,
  projectUserRepository: ProjectUserRepository,
  clientRepository: ClientRepository,
  userRepository: UserRepository,
  userIdentityRepository: UserIdentityRepository
)(implicit ec: ExecutionContext)
    extends OAuthUserReader {

  def list(
    policy: OAuthAccessPolicy,
    slug: OrganizationSlug,
    includeArchived: Boolean = false
  ): IO[OAuthReadResult[Seq[OrganizationUserDto]]] =
    transactor.executeTransactionally {
      withBoundOrganization(policy, slug) {
        visibleMemberships(policy).flatMap { case (memberships, users) =>
          listIdentities(users).map { identities =>
            val roleMap     = memberships.map(membership => membership.userId -> membership.roles).toMap
            val identityMap = identities.groupBy(_.userId)
            val dtos = users
              .filter(user => includeArchived || user.archivedAt.isEmpty)
              .map(user => OrganizationUserDto.from(user, roleMap, identityMap))
            Ok(dtos)
          }
        }
      }
    }

  def details(
    policy: OAuthAccessPolicy,
    slug: OrganizationSlug,
    userId: UserId
  ): IO[OAuthReadResult[Seq[OrganizationUserDetailsRow]]] =
    transactor.executeTransactionally {
      withBoundOrganization(policy, slug) {
        for {
          projects     <- visibleProjects(policy)
          projectUsers <- listProjectUsers(projects)
          visibleProjectUsers = projectUsers.filter(projectUser => projectUser.userId == userId)
          memberships <- organizationUserRepository.list(OrganizationUserFilters(organizationId = Some(policy.organizationId), userId = Some(userId)))
          users       <- listHumanUsers(visibleProjectUsers.map(_.userId))
          result <-
            if (
              !memberships.exists(membership =>
                membership.organizationId == policy.organizationId && membership.userId == userId && membership.archivedAt.isEmpty
              ) ||
              !users.exists(_.id == userId)
            ) DBIO.successful(NotFound)
            else prepareDetails(projects, visibleProjectUsers).map(rows => Ok(rows))
        } yield result
      }
    }

  private def withBoundOrganization[A](policy: OAuthAccessPolicy, slug: OrganizationSlug)(query: => DBIO[OAuthReadResult[A]])
    : DBIO[OAuthReadResult[A]] =
    organizationRepository
      .find(OrganizationFilters(id = Some(policy.organizationId), slug = Some(slug), includeArchived = true))
      .flatMap {
        case Some(organization) if organization.id == policy.organizationId && organization.slug == slug => query
        case _                                                                                           => DBIO.successful(NotFound)
      }

  private def visibleMemberships(policy: OAuthAccessPolicy): DBIO[(Seq[OrganizationUser], Seq[User])] =
    for {
      projects     <- visibleProjects(policy)
      projectUsers <- listProjectUsers(projects)
      visibleUserIds = projectUsers.map(_.userId).distinct
      memberships <-
        if (visibleUserIds.isEmpty) DBIO.successful(Seq.empty)
        else
          organizationUserRepository.list(OrganizationUserFilters(organizationId = Some(policy.organizationId), userIds = Some(visibleUserIds)))
      visibleUserIdSet = visibleUserIds.toSet
      boundedMemberships =
        memberships.filter(membership =>
          membership.organizationId == policy.organizationId && visibleUserIdSet.contains(membership.userId) && membership.archivedAt.isEmpty
        )
      memberIds = boundedMemberships.map(_.userId).distinct
      users <- listHumanUsers(memberIds)
      humanIds = users.map(_.id).toSet
    } yield boundedMemberships.filter(membership => humanIds.contains(membership.userId)) -> users

  private def visibleProjects(policy: OAuthAccessPolicy): DBIO[Seq[Project]] = {
    val ids = policy.projectAccess match {
      case OAuthProjectAccess.AllProjects                  => None
      case OAuthProjectAccess.SelectedProjects(projectIds) => Some(projectIds.toSeq)
    }
    if (ids.exists(_.isEmpty)) DBIO.successful(Seq.empty)
    else
      projectRepository
        .list(ProjectFilters(ids = ids, organizationIds = Some(Seq(policy.organizationId))))
        .map(_.filter(project => project.organizationId == policy.organizationId && policy.allows(project.id)))
  }

  private def listProjectUsers(projects: Seq[Project]): DBIO[Seq[ProjectUser]] = {
    val projectIds = projects.map(_.id)
    if (projectIds.isEmpty) DBIO.successful(Seq.empty)
    else
      projectUserRepository
        .list(ProjectUserFilters(projectIds = Some(projectIds)))
        .map(_.filter(projectUser => projectIds.contains(projectUser.projectId) && projectUser.archivedAt.isEmpty))
  }

  private def listHumanUsers(ids: Seq[UserId]): DBIO[Seq[User]] =
    if (ids.isEmpty) DBIO.successful(Seq.empty)
    else
      userRepository
        .list(UserFilters(ids = Some(ids.distinct), includeArchived = true))
        .map(_.filter(user => ids.contains(user.id) && user.accountType == UserAccountType.Human))

  private def listIdentities(users: Seq[User]): DBIO[Seq[UserIdentity]] = {
    val userIds = users.map(_.id)
    if (userIds.isEmpty) DBIO.successful(Seq.empty)
    else
      userIdentityRepository
        .list(UserIdentityFilters(userIds = Some(userIds), confirmed = Some(UserIdentityConfirmed(true))))
        .map(_.filter(identity => userIds.contains(identity.userId)))
  }

  private def prepareDetails(projects: Seq[Project], projectUsers: Seq[ProjectUser]): DBIO[Seq[OrganizationUserDetailsRow]] = {
    val clientIds = projects.map(_.clientId).distinct
    clientRepository
      .list(ClientFilters(ids = Some(clientIds), organizationIds = projects.headOption.map(project => Seq(project.organizationId))))
      .map { clients =>
        val projectMap: Map[ProjectId, (ProjectName, ClientId)] = projects.map(project => project.id -> (project.name -> project.clientId)).toMap
        val clientMap: Map[ClientId, ClientName]                = clients.map(client => client.id -> client.name).toMap
        projectUsers.flatMap { projectUser =>
          for {
            (projectName, clientId) <- projectMap.get(projectUser.projectId)
            clientName              <- clientMap.get(clientId)
          } yield OrganizationUserDetailsRow(projectUser.role, projectUser.projectId, clientId, projectName, clientName)
        }
      }
  }
}
