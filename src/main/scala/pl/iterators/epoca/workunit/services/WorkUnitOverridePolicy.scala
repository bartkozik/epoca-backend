package pl.iterators.epoca.workunit.services

import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationId, OrganizationUserRole}
import pl.iterators.epoca.organization.domain.OrganizationUser
import pl.iterators.epoca.project.domain.ProjectDomain.{ProjectId, ProjectRole}
import pl.iterators.epoca.project.domain.{Project, ProjectUser}
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.workunit.domain.WorkUnit
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.{WorkUnitEntryType, WorkUnitState}

sealed trait WorkUnitOverrideScope
object WorkUnitOverrideScope {
  case object None                                   extends WorkUnitOverrideScope
  case object All                                    extends WorkUnitOverrideScope
  final case class Owned(projectIds: Set[ProjectId]) extends WorkUnitOverrideScope
}

final case class WorkUnitOverridePermissions(
  canEdit: Boolean,
  canDelete: Boolean,
  canViewHistory: Boolean,
  allowedProjectIds: Seq[ProjectId])

final case class WorkUnitOverridePolicySnapshot(
  actorUserId: UserId,
  isSuperAdmin: Boolean,
  organizationId: OrganizationId,
  organizationUser: Option[OrganizationUser],
  projectUsers: Seq[ProjectUser],
  projects: Seq[Project])

object WorkUnitOverridePolicy {
  def scope(snapshot: WorkUnitOverridePolicySnapshot): WorkUnitOverrideScope =
    if (snapshot.isSuperAdmin) WorkUnitOverrideScope.All
    else
      activeOrganizationUser(snapshot) match {
        case Some(user) if user.roles.exists(OrganizationUserRole.managementAccessRoles.contains) =>
          WorkUnitOverrideScope.All
        case Some(user) if user.roles.exists(role => role == OrganizationUserRole.Employee || role == OrganizationUserRole.External) =>
          val organizationProjectIds = snapshot.projects.iterator
            .filter(_.organizationId == snapshot.organizationId)
            .map(_.id)
            .toSet
          val ownedProjectIds = snapshot.projectUsers.iterator
            .filter(projectUser =>
              projectUser.userId == snapshot.actorUserId &&
                projectUser.archivedAt.isEmpty &&
                projectUser.role == ProjectRole.Owner &&
                organizationProjectIds.contains(projectUser.projectId)
            )
            .map(_.projectId)
            .toSet

          if (ownedProjectIds.isEmpty) WorkUnitOverrideScope.None else WorkUnitOverrideScope.Owned(ownedProjectIds)
        case _ => WorkUnitOverrideScope.None
      }

  def allowedProjectIds(snapshot: WorkUnitOverridePolicySnapshot): Seq[ProjectId] = {
    val activeOrganizationProjectIds = snapshot.projects.iterator
      .filter(project => project.organizationId == snapshot.organizationId && project.archivedAt.isEmpty)
      .map(_.id)
      .toSet

    val allowed = scope(snapshot) match {
      case WorkUnitOverrideScope.None              => Set.empty[ProjectId]
      case WorkUnitOverrideScope.All               => activeOrganizationProjectIds
      case WorkUnitOverrideScope.Owned(projectIds) => activeOrganizationProjectIds.intersect(projectIds)
    }

    allowed.toSeq.sortBy(_.toString)
  }

  def permissions(snapshot: WorkUnitOverridePolicySnapshot, workUnit: WorkUnit): WorkUnitOverridePermissions = {
    val allowed = allowedProjectIds(snapshot)
    val sourceProjectMatchesOrganization =
      snapshot.projects.exists(project => project.id == workUnit.projectId && project.organizationId == snapshot.organizationId)
    val sourceInScope = scope(snapshot) match {
      case WorkUnitOverrideScope.None              => false
      case WorkUnitOverrideScope.All               => true
      case WorkUnitOverrideScope.Owned(projectIds) => projectIds.contains(workUnit.projectId) && projectIds.contains(workUnit.effectiveProjectId)
    }
    val eligible =
      workUnit.organizationId == snapshot.organizationId &&
        workUnit.entryType == WorkUnitEntryType.Worklog &&
        workUnit.state == WorkUnitState.Active &&
        workUnit.archivedAt.isEmpty &&
        sourceProjectMatchesOrganization &&
        sourceInScope

    WorkUnitOverridePermissions(canEdit = eligible, canDelete = eligible, canViewHistory = eligible, allowedProjectIds = allowed)
  }

  def canEdit(
    snapshot: WorkUnitOverridePolicySnapshot,
    workUnit: WorkUnit,
    targetProjectId: Option[ProjectId]
  ): Boolean = {
    val result = permissions(snapshot, workUnit)
    result.canEdit && targetProjectId.forall(result.allowedProjectIds.contains)
  }

  private def activeOrganizationUser(snapshot: WorkUnitOverridePolicySnapshot): Option[OrganizationUser] =
    snapshot.organizationUser.filter(user =>
      user.organizationId == snapshot.organizationId &&
        user.userId == snapshot.actorUserId &&
        user.archivedAt.isEmpty
    )
}
