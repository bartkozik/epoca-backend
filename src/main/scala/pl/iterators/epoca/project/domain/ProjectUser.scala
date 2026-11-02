package pl.iterators.epoca.project.domain

import pl.iterators.epoca.project.domain.ProjectDomain.ProjectRole.{Member, Owner}
import pl.iterators.epoca.project.domain.ProjectDomain._
import pl.iterators.epoca.user.domain.UserDomain.UserId

case class ProjectUser(
  id: ProjectUserId,
  projectId: ProjectId,
  userId: UserId,
  role: ProjectRole,
  createdAt: ProjectUserCreatedAt,
  archivedAt: Option[ProjectUserArchivedAt])

final case class ProjectUsersChanges(toAssign: Seq[ProjectUserRole], toDeassign: Seq[ProjectUserId])

final case class ProjectUserRole(userId: UserId, role: ProjectRole)
object ProjectUser {
  def getProjectUserChanges(
    ownerIds: Set[UserId],
    memberIds: Set[UserId],
    currentProjectUsers: Set[ProjectUser]
  ): ProjectUsersChanges = {
    val onlyMemberIds = memberIds.diff(ownerIds)

    ProjectUsersChanges(
      getAssignedUsers(ownerIds, onlyMemberIds, currentProjectUsers),
      getDeassignedUserIds(ownerIds, onlyMemberIds, currentProjectUsers)
    )
  }

  private def getAssignedUsers(
    ownerIds: Set[UserId],
    memberIds: Set[UserId],
    currentProjectUsers: Set[ProjectUser]
  ): Seq[ProjectUserRole] = {
    val assignedOwners = ownerIds
      .diff(currentProjectUsers.filter(_.role == Owner).map(_.userId))
      .map(ProjectUserRole(_, Owner))
      .toSeq

    val assignedMembers = memberIds
      .diff(currentProjectUsers.map(_.userId))
      .map(ProjectUserRole(_, Member))
      .toSeq

    assignedOwners ++ assignedMembers
  }

  private def getDeassignedUserIds(
    ownerIds: Set[UserId],
    memberIds: Set[UserId],
    currentProjectUsers: Set[ProjectUser]
  ): Seq[ProjectUserId] = {
    val deassignedOwnerIds = currentProjectUsers
      .filter(_.role == Owner)
      .filterNot(projectUser => ownerIds.contains(projectUser.userId))
      .map(_.id)
      .toSeq

    val deassignedMemberIds = currentProjectUsers
      .filter(_.role == Member)
      .filterNot(projectUser => memberIds.contains(projectUser.userId))
      .map(_.id)
      .toSeq

    deassignedOwnerIds ++ deassignedMemberIds
  }
}
