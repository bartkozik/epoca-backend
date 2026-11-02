package pl.iterators.epoca.workunit.domain

import io.scalaland.chimney.dsl._
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId
import pl.iterators.epoca.project.domain.ProjectDomain.ProjectId
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.workunit.domain.WorkUnitDomain._
import pl.iterators.epoca.workunit.services.WorkUnitUpdateService.{WorkUnitUpdatePatch, WorkUnitUpdateRequest}

import java.time.Instant

case class WorkUnit(
  id: WorkUnitId,
  userId: UserId,
  organizationId: OrganizationId,
  projectId: ProjectId,
  date: WorkUnitDate,
  duration: WorkUnitDuration,
  entryType: WorkUnitEntryType,
  description: WorkUnitDescription,
  taskId: Option[WorkUnitTaskId],
  state: WorkUnitState,
  createdAt: WorkUnitCreatedAt,
  modifiedAt: WorkUnitModifiedAt,
  archivedAt: Option[WorkUnitArchivedAt],
  revision: Long = 0,
  overrideState: WorkUnitOverrideState = WorkUnitOverrideState()) {
  def effectiveProjectId: ProjectId           = overrideState.projectId.getOrElse(projectId)
  def effectiveTaskId: Option[WorkUnitTaskId] = if (overrideState.taskIdOverrideSet) overrideState.taskId else taskId
  def updated(request: WorkUnitUpdateRequest, now: Instant): WorkUnit = {
    val (createdAt, state) =
      if (this.state == WorkUnitState.Draft && request.shouldFinalize)
        (WorkUnitCreatedAt(now), WorkUnitState.Active)
      else
        (this.createdAt, this.state)
    val patch = request.into[WorkUnitUpdatePatch].transform
    this
      .using(patch)
      .ignoreNoneInPatch
      .patch
      .copy(createdAt = createdAt, modifiedAt = WorkUnitModifiedAt(now), state = state, revision = revision + 1)
  }
}
