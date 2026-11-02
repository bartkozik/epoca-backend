package pl.iterators.epoca.workunit.repositories.tables

import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId
import pl.iterators.epoca.project.domain.ProjectDomain.ProjectId
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import pl.iterators.epoca.utils.db.{BaseTable, IdTable, SoftDeleteTable}
import pl.iterators.epoca.workunit.domain.WorkUnitDomain._
import pl.iterators.epoca.workunit.domain.{WorkUnit, WorkUnitOverrideState}
import slick.lifted.{MappedProjection, ProvenShape}

import java.time.Instant

class WorkUnitTable(tag: Tag)
    extends BaseTable[WorkUnit](tag, "work_unit")
    with IdTable[WorkUnit, WorkUnitId]
    with SoftDeleteTable[WorkUnit, WorkUnitId] {

  def id: Rep[WorkUnitId]                         = column[WorkUnitId]("id")
  def organizationId: Rep[OrganizationId]         = column[OrganizationId]("organization_id")
  def userId: Rep[UserId]                         = column[UserId]("user_id")
  def projectId: Rep[ProjectId]                   = column[ProjectId]("project_id")
  def date: Rep[WorkUnitDate]                     = column[WorkUnitDate]("date")
  def duration: Rep[WorkUnitDuration]             = column[WorkUnitDuration]("duration")
  def entryType: Rep[WorkUnitEntryType]           = column[WorkUnitEntryType]("entry_type")
  def description: Rep[WorkUnitDescription]       = column[WorkUnitDescription]("description")
  def taskId: Rep[Option[WorkUnitTaskId]]         = column[Option[WorkUnitTaskId]]("task_id")
  def state: Rep[WorkUnitState]                   = column[WorkUnitState]("state")
  def createdAt: Rep[WorkUnitCreatedAt]           = column[WorkUnitCreatedAt]("created_at")
  def modifiedAt: Rep[WorkUnitModifiedAt]         = column[WorkUnitModifiedAt]("modified_at")
  def archivedAt: Rep[Option[WorkUnitArchivedAt]] = column[Option[WorkUnitArchivedAt]]("archived_at")

  override def deletedAt: Rep[Option[Instant]] = archivedAt

  def revision: Rep[Long]                         = column[Long]("revision")
  def overrideProjectId: Rep[Option[ProjectId]]   = column[Option[ProjectId]]("override_project_id")
  def taskIdOverrideSet: Rep[Boolean]             = column[Boolean]("task_id_override_set")
  def overrideTaskId: Rep[Option[WorkUnitTaskId]] = column[Option[WorkUnitTaskId]]("override_task_id")
  def overrideVersion: Rep[Long]                  = column[Long]("override_version")
  def overrideHasHistory: Rep[Boolean]            = column[Boolean]("override_has_history")
  def overrideLastChangedBy: Rep[Option[UserId]]  = column[Option[UserId]]("override_last_changed_by")
  def overrideLastChangedAt: Rep[Option[Instant]] = column[Option[Instant]]("override_last_changed_at")
  // Match the expression index and raw report SQL exactly; Option.getOrElse emits CASE.
  def effectiveProjectId: Rep[ProjectId] =
    SimpleFunction.binary[Option[ProjectId], ProjectId, ProjectId]("coalesce").apply(overrideProjectId, projectId)
  def effectiveTaskId: Rep[Option[WorkUnitTaskId]] = Case.If(taskIdOverrideSet).Then(overrideTaskId).Else(taskId)
  def overrideState: MappedProjection[WorkUnitOverrideState] = (
    overrideProjectId,
    taskIdOverrideSet,
    overrideTaskId,
    overrideVersion,
    overrideHasHistory,
    overrideLastChangedBy,
    overrideLastChangedAt
  ) <> (WorkUnitOverrideState.tupled, WorkUnitOverrideState.unapply _)

  override def * : ProvenShape[WorkUnit] =
    (
      id,
      userId,
      organizationId,
      projectId,
      date,
      duration,
      entryType,
      description,
      taskId,
      state,
      createdAt,
      modifiedAt,
      archivedAt,
      revision,
      overrideState
    ) <> (WorkUnit.tupled, WorkUnit.unapply _)

}
