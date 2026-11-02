package pl.iterators.epoca.workunit.services

import pl.iterators.epoca.project.domain.ProjectDomain.ProjectId
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.workunit.domain.WorkUnitDomain._
import pl.iterators.epoca.workunit.services.WorkUnitCreateService.WorkUnitCreateRequest

import java.time.Duration

final case class NormalizedWorkUnitCreateRequest(
  projectId: ProjectId,
  date: WorkUnitDate,
  duration: Option[WorkUnitDuration],
  description: WorkUnitDescription,
  taskId: Option[WorkUnitTaskId],
  isDraft: Boolean,
  entryType: WorkUnitEntryType,
  targetUserId: UserId)

object WorkUnitCreateRequestNormalizer {
  def normalize(actorId: UserId, request: WorkUnitCreateRequest): NormalizedWorkUnitCreateRequest = {
    val entryType = request.entryType.getOrElse(WorkUnitEntryType.Worklog)
    val duration =
      if (entryType == WorkUnitEntryType.Note && request.duration.isEmpty)
        Some(WorkUnitDuration(Duration.ZERO))
      else request.duration

    NormalizedWorkUnitCreateRequest(
      projectId = request.projectId,
      date = request.date,
      duration = duration,
      description = request.description,
      taskId = request.taskId,
      isDraft = request.isDraft,
      entryType = entryType,
      targetUserId = request.userId.getOrElse(actorId)
    )
  }
}
