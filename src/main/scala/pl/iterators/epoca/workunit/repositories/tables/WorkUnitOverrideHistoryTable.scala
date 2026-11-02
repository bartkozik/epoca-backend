package pl.iterators.epoca.workunit.repositories.tables

import io.circe.Json
import pl.iterators.epoca.client.domain.ClientDomain.{ClientId, ClientName}
import pl.iterators.epoca.project.domain.ProjectDomain.{ProjectId, ProjectName}
import pl.iterators.epoca.user.domain.UserDomain.{UserFullName, UserId}
import pl.iterators.epoca.utils.db.BaseTable
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.{WorkUnitId, WorkUnitTaskId}
import pl.iterators.epoca.workunit.domain.{WorkUnitOverrideHistory, WorkUnitOverrideLegacySnapshot}
import slick.lifted.ProvenShape

import java.time.Instant
import java.util.UUID

class WorkUnitOverrideHistoryTable(tag: Tag) extends BaseTable[WorkUnitOverrideHistory](tag, "work_unit_override_history") {
  def id: Rep[UUID]                         = column[UUID]("id")
  def workUnitId: Rep[WorkUnitId]           = column[WorkUnitId]("work_unit_id")
  def version: Rep[Long]                    = column[Long]("version")
  def operation: Rep[String]                = column[String]("operation")
  def actorId: Rep[UserId]                  = column[UserId]("actor_id")
  def actorDisplayName: Rep[UserFullName]   = column[UserFullName]("actor_display_name")
  def changedAt: Rep[Instant]               = column[Instant]("changed_at")
  def provenance: Rep[String]               = column[String]("provenance")
  def before: Rep[Option[Json]]             = column[Option[Json]]("before_snapshot")
  def after: Rep[Option[Json]]              = column[Option[Json]]("after_snapshot")
  def parentCorrectionId: Rep[Option[UUID]] = column[Option[UUID]]("parent_correction_id")
  def revertsEventId: Rep[Option[UUID]]     = column[Option[UUID]]("reverts_event_id")
  private def legacy(prefix: String) = (
    column[Option[ProjectId]](prefix + "_pm_project_id"),
    column[Option[ProjectName]](prefix + "_pm_project_name"),
    column[Option[ClientId]](prefix + "_pm_client_id"),
    column[Option[ClientName]](prefix + "_pm_client_name"),
    column[Option[WorkUnitTaskId]](prefix + "_pm_task_id")
  ) <> (WorkUnitOverrideLegacySnapshot.tupled, WorkUnitOverrideLegacySnapshot.unapply _)

  override def * : ProvenShape[WorkUnitOverrideHistory] =
    (
      id,
      workUnitId,
      version,
      operation,
      actorId,
      actorDisplayName,
      changedAt,
      provenance,
      before,
      after,
      legacy("before"),
      legacy("after"),
      parentCorrectionId,
      revertsEventId
    ) <>
      (WorkUnitOverrideHistory.tupled, WorkUnitOverrideHistory.unapply _)
}
