package pl.iterators.epoca.workunit.services

import pl.iterators.epoca.project.domain.ProjectDomain.ProjectId
import pl.iterators.epoca.user.domain.UserDomain.{UserFullName, UserId}
import pl.iterators.epoca.workunit.domain.{WorkUnit, WorkUnitTaskOverride}

import java.time.Instant
import java.util.UUID

final case class WorkUnitOverrideUndoDto(
  candidateEventId: Option[UUID],
  canUndo: Boolean,
  reason: Option[String])

final case class WorkUnitOverrideActorDto(id: UserId, displayName: UserFullName)
final case class WorkUnitOverridePermissionsDto(
  canEdit: Boolean,
  canDelete: Boolean,
  canViewHistory: Boolean,
  allowedProjectIds: Seq[ProjectId])

object WorkUnitOverridePermissionsDto {
  def apply(): WorkUnitOverridePermissionsDto = WorkUnitOverridePermissionsDto(false, false, false, Nil)
}

final case class WorkUnitOverrideDto(
  active: Boolean,
  hasHistory: Boolean,
  version: Long,
  projectId: Option[ProjectId],
  taskId: WorkUnitTaskOverride,
  lastChangedBy: Option[WorkUnitOverrideActorDto],
  lastChangedAt: Option[Instant],
  permissions: WorkUnitOverridePermissionsDto,
  undo: Option[WorkUnitOverrideUndoDto] = None)

object WorkUnitOverrideDto {
  val empty: WorkUnitOverrideDto =
    WorkUnitOverrideDto(false, false, 0, None, WorkUnitTaskOverride("Inherit"), None, None, WorkUnitOverridePermissionsDto())

  def from(workUnit: WorkUnit, names: Map[UserId, UserFullName]): WorkUnitOverrideDto = {
    val state = workUnit.overrideState
    WorkUnitOverrideDto(
      state.active,
      state.hasHistory,
      state.version,
      state.projectId,
      state.desiredTask,
      state.lastChangedBy.flatMap(id => names.get(id).map(WorkUnitOverrideActorDto(id, _))),
      state.lastChangedAt,
      WorkUnitOverridePermissionsDto()
    )
  }

  def permissions(snapshot: WorkUnitOverridePolicySnapshot, workUnit: WorkUnit): WorkUnitOverridePermissionsDto = {
    val result = WorkUnitOverridePolicy.permissions(snapshot, workUnit)
    WorkUnitOverridePermissionsDto(result.canEdit, result.canDelete, result.canViewHistory, if (result.canEdit) result.allowedProjectIds else Nil)
  }
}
