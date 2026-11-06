package pl.iterators.epoca.organizationdashboard.services

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationId, OrganizationSlug, OrganizationUserRole}
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.organizationdashboard.services.OrganizationUserListService.{OrganizationUserDto, OrganizationUserListResult}
import pl.iterators.epoca.project.domain.ProjectDomain.{ProjectId, ProjectRole}
import pl.iterators.epoca.project.repositories.ProjectRepository.ProjectFilters
import pl.iterators.epoca.project.repositories.ProjectUserRepository.ProjectUserFilters
import pl.iterators.epoca.project.repositories.{ProjectRepository, ProjectUserRepository}
import pl.iterators.epoca.user.domain.UserDomain.{UserAvatar, UserFirstName, UserId, UserIdentityConfirmed, UserLastName}
import pl.iterators.epoca.user.domain.{User, UserIdentity, UserIdentityShortDto}
import pl.iterators.epoca.user.repositories.UserIdentityRepository.UserIdentityFilters
import pl.iterators.epoca.user.repositories.UserRepository.UserFilters
import pl.iterators.epoca.user.repositories.{UserIdentityRepository, UserRepository}
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.service.SealedMonadServiceIODBIO
import pl.iterators.sealedmonad.Sealed
import pl.iterators.sealedmonad.syntax._
import slick.dbio.DBIO

class OrganizationUserListService(
  transactor: PostgresIOTransactor,
  projectUserRepository: ProjectUserRepository,
  projectRepository: ProjectRepository,
  organizationRepository: OrganizationRepository,
  userIdentityRepository: UserIdentityRepository,
  organizationUserRepository: OrganizationUserRepository,
  userRepository: UserRepository
)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIODBIO[OrganizationUserListResult](ioRuntime)
    with LoggingSupport {

  def list(
    authContext: AuthContext,
    slug: OrganizationSlug,
    includeArchived: Boolean = false,
    includeArchivedMembers: Boolean = false
  ): IO[OrganizationUserListResult] = {
    val query = for {
      organization                <- findOrganization(slug)
      requesterOrgUser            <- checkPermission(authContext, organization.id)
      ownedProjectIds             <- listOwnedProjectsIds(authContext)
      ownedOrganizationProjectIds <- listOwnedOrganizationProjects(organization.id, ownedProjectIds)
      members                     <- listOrgUsers(organization, requesterOrgUser, ownedOrganizationProjectIds, includeArchivedMembers)
      (orgUsers, activeMemberIds) = members
      identitiesMap <- listUserIdentities(orgUsers)
      enrichedUsers <- enrichOrgUsers(orgUsers, activeMemberIds, identitiesMap, includeArchived)
    } yield OrganizationUserListResult.Ok(enrichedUsers)
    transactor.execute(query.run)
  }

  private def findOrganization(slug: OrganizationSlug): StepDBIO[Organization] =
    organizationRepository
      .find(OrganizationFilters(slug = Some(slug)))
      .valueOrF {
        Logger[DBIO].error(s"organization $slug does not exist").as(OrganizationUserListResult.OrganizationNotFound)
      }
  private def checkPermission(authContext: AuthContext, id: OrganizationId): StepDBIO[Option[OrganizationUser]] =
    if (authContext.isSuperAdmin) Sealed.liftF(None)
    else
      organizationUserRepository
        .find(OrganizationUserFilters(organizationId = Some(id), userId = Some(authContext.id)))
        .valueOrF {
          Logger[DBIO]
            .warning(s"user ${authContext.id} has no permission")
            .as(OrganizationUserListResult.OperationNotPermitted)
        }
        .map(Option.apply)

  private def listOwnedProjectsIds(authContext: AuthContext): StepDBIO[Seq[ProjectId]] = {
    val res: DBIO[Seq[ProjectId]] = projectUserRepository
      .list(ProjectUserFilters(userId = Some(authContext.id), role = Some(ProjectRole.Owner)))
      .map(_.map(_.projectId))
    res.seal
  }

  private def listOwnedOrganizationProjects(id: OrganizationId, projectIds: Seq[ProjectId]): StepDBIO[Seq[ProjectId]] = {
    val res: DBIO[Seq[ProjectId]] = projectRepository
      .list(ProjectFilters(ids = Some(projectIds), organizationIds = Some(Seq(id))))
      .map(_.map(_.id))
    res.seal
  }

  private def listOrgUsers(
    organization: Organization,
    requesterOrgUser: Option[OrganizationUser],
    ownedProjectIds: Seq[ProjectId],
    includeArchivedMembers: Boolean
  ): StepDBIO[(Seq[OrganizationUser], Set[UserId])] = {
    val filters = OrganizationUserFilters(
      organizationId = Some(organization.id),
      userId = for {
        orgUser <- requesterOrgUser if !orgUser.hasManagementRole && ownedProjectIds.isEmpty
      } yield orgUser.userId
    )
    val result: DBIO[(Seq[OrganizationUser], Set[UserId])] = for {
      active <- organizationUserRepository.list(filters)
      all <-
        if (includeArchivedMembers) organizationUserRepository.list(filters.copy(includeArchived = true))
        else DBIO.successful(active)
    } yield (all, active.map(_.userId).toSet)
    result.seal
  }

  private def listUserIdentities(orgUsers: Seq[OrganizationUser]): StepDBIO[Map[UserId, Seq[UserIdentity]]] = {
    val res: DBIO[Map[UserId, Seq[UserIdentity]]] = userIdentityRepository
      .list(UserIdentityFilters(userIds = Some(orgUsers.map(_.userId)), confirmed = Some(UserIdentityConfirmed(true))))
      .map(_.groupBy(_.userId).toMap)
    res.seal
  }

  private def enrichOrgUsers(
    orgUsers: Seq[OrganizationUser],
    activeMemberIds: Set[UserId],
    identitiesMap: Map[UserId, Seq[UserIdentity]],
    includeArchived: Boolean
  ): StepDBIO[Seq[OrganizationUserDto]] = {
    val userRoleMap = orgUsers.map(entry => entry.userId -> entry.roles).toMap
    val result: DBIO[Seq[OrganizationUserDto]] = userRepository
      .list(UserFilters(ids = Some(orgUsers.map(_.userId)), includeArchived = includeArchived))
      .map(_.map { user =>
        val dto = OrganizationUserDto.from(user, userRoleMap, identitiesMap)
        dto.copy(archived = dto.archived || !activeMemberIds.contains(user.id))
      })
    result.seal
  }
}

object OrganizationUserListService {

  sealed trait OrganizationUserListResult

  final case class OrganizationUserDto(
    id: UserId,
    firstName: UserFirstName,
    lastName: UserLastName,
    organizationRoles: Seq[OrganizationUserRole],
    identities: Seq[UserIdentityShortDto],
    archived: Boolean,
    avatar: Option[UserAvatar] = None)

  object OrganizationUserDto {
    def from(
      user: User,
      userRoleMap: Map[UserId, Seq[OrganizationUserRole]],
      identitiesMap: Map[UserId, Seq[UserIdentity]]
    ): OrganizationUserDto =
      OrganizationUserDto(
        user.id,
        user.firstName,
        user.lastName,
        userRoleMap.getOrElse(user.id, Nil),
        identitiesMap.getOrElse(user.id, Nil).map(UserIdentityShortDto.apply),
        user.archivedAt.isDefined,
        user.avatar
      )
  }

  object OrganizationUserListResult {
    final case class Ok(orgUsers: Seq[OrganizationUserDto]) extends OrganizationUserListResult
    final case object OrganizationNotFound                  extends OrganizationUserListResult
    final case object OperationNotPermitted                 extends OrganizationUserListResult
  }
}
