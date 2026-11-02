package pl.iterators.epoca.workunit.services

import io.circe.Encoder
import io.circe.syntax._
import org.apache.pekko.http.scaladsl.model.{StatusCode, StatusCodes}
import pl.iterators.epoca.utils.http.Error
import pl.iterators.epoca.utils.json.JsonProtocol
import pl.iterators.epoca.workunit.idempotency.WorkUnitWriteOutcome
import pl.iterators.epoca.workunit.services.WorkUnitCreateService.WorkUnitCreateResult
import pl.iterators.epoca.workunit.services.WorkUnitDtoService.WorkUnitDto
import pl.iterators.epoca.workunit.services.WorkUnitUpdateService.WorkUnitUpdateResult

import java.net.URI

class WorkUnitWriteOutcomeMapper extends JsonProtocol {
  private implicit lazy val workUnitDtoEncoder: Encoder.AsObject[WorkUnitDto] = WorkUnitDto.encoder

  def create(result: WorkUnitCreateResult, correlationId: String): WorkUnitWriteOutcome =
    result match {
      case WorkUnitCreateResult.Created(workUnit) =>
        WorkUnitWriteOutcome(StatusCodes.Created.intValue, workUnit.asJson, Some(workUnit.id))
      case WorkUnitCreateResult.OrganizationNotFound =>
        error(StatusCodes.NotFound, "work-unit-create:organization-not-found", "Organization not found", correlationId)
      case WorkUnitCreateResult.NoAccessToOrganization =>
        error(StatusCodes.NotFound, "work-unit-create:no-access-to-organization", "Organization not found", correlationId)
      case WorkUnitCreateResult.ProjectNotFound =>
        error(StatusCodes.NotFound, "work-unit-create:project-not-found", "Project not found", correlationId)
      case WorkUnitCreateResult.NoAccessToProject =>
        error(StatusCodes.Forbidden, "work-unit-create:no-access-to-project", "Access to project denied", correlationId)
      case WorkUnitCreateResult.NonPositiveDuration =>
        error(
          StatusCodes.BadRequest,
          "work-unit-create:non-positive-duration",
          "Invalid duration",
          correlationId,
          Some("Duration should be positive")
        )
      case WorkUnitCreateResult.NonNeutralNoteDuration =>
        error(
          StatusCodes.BadRequest,
          "work-unit-create:non-neutral-note-duration",
          "Invalid note duration",
          correlationId,
          Some("Note duration should be zero")
        )
      case WorkUnitCreateResult.FutureWorklogDate =>
        error(
          StatusCodes.BadRequest,
          "work-unit-create:future-worklog-date",
          "Invalid worklog date",
          correlationId,
          Some("Worklog date cannot be in the future")
        )
      case WorkUnitCreateResult.NoAccessToTargetUser =>
        error(StatusCodes.Forbidden, "work-unit-create:no-access-to-target-user", "Access to target user denied", correlationId)
      case WorkUnitCreateResult.TargetUserNotAssignedToProject =>
        error(StatusCodes.Forbidden, "work-unit-create:target-user-not-assigned-to-project", "Target user is not assigned to project", correlationId)
      case WorkUnitCreateResult.TargetUserNotFound =>
        error(StatusCodes.NotFound, "work-unit-create:target-user-not-found", "Target user not found", correlationId)
    }

  def update(result: WorkUnitUpdateResult, correlationId: String): WorkUnitWriteOutcome =
    result match {
      case WorkUnitUpdateResult.Updated(workUnit) => WorkUnitWriteOutcome(StatusCodes.OK.intValue, workUnit.asJson, Some(workUnit.id))
      case WorkUnitUpdateResult.OrganizationNotFound =>
        error(StatusCodes.NotFound, "work-unit-update:organization-not-found", "Organization not found", correlationId)
      case WorkUnitUpdateResult.NoAccessToOrganization =>
        error(StatusCodes.NotFound, "work-unit-update:no-access-to-organization", "Organization not found", correlationId)
      case WorkUnitUpdateResult.WorkUnitNotFound =>
        error(StatusCodes.NotFound, "work-unit-update:work-unit-not-found", "Work unit not found", correlationId)
      case WorkUnitUpdateResult.NoAccessToWorkUnit =>
        error(StatusCodes.Forbidden, "work-unit-update:no-access-to-work-unit", "Access to work unit denied", correlationId)
      case WorkUnitUpdateResult.ProjectNotFound =>
        error(StatusCodes.NotFound, "work-unit-update:project-not-found", "Project not found", correlationId)
      case WorkUnitUpdateResult.NoAccessToProject =>
        error(StatusCodes.Forbidden, "work-unit-update:no-access-to-project", "Access to project denied", correlationId)
      case WorkUnitUpdateResult.WorkUnitInactive =>
        error(
          StatusCodes.Forbidden,
          "work-unit-update:work-unit-inactive",
          "Work unit inactive",
          correlationId,
          Some("Work unit can't be edited anymore")
        )
      case WorkUnitUpdateResult.NonPositiveDuration =>
        error(
          StatusCodes.BadRequest,
          "work-unit-update:non-positive-duration",
          "Invalid duration",
          correlationId,
          Some("Duration should be positive")
        )
      case WorkUnitUpdateResult.NonNeutralNoteDuration =>
        error(
          StatusCodes.BadRequest,
          "work-unit-update:non-neutral-note-duration",
          "Invalid note duration",
          correlationId,
          Some("Note duration should be zero")
        )
      case WorkUnitUpdateResult.FutureWorklogDate =>
        error(
          StatusCodes.BadRequest,
          "work-unit-update:future-worklog-date",
          "Invalid worklog date",
          correlationId,
          Some("Worklog date cannot be in the future")
        )
    }

  private def error(
    status: StatusCode,
    typeTag: String,
    title: String,
    correlationId: String,
    detail: Option[String] = None
  ): WorkUnitWriteOutcome = {
    val problem = Error(
      `type` = Some(URI.create(s"result:$typeTag")),
      status = Some(status),
      title = Some(title),
      detail = detail,
      instance = Some(correlationInstance(correlationId))
    )
    val body = Error.encoder[Unit].apply(problem)

    WorkUnitWriteOutcome(status.intValue, body, None)
  }

  private def correlationInstance(correlationId: String): URI =
    new URI("cid", Option(correlationId).filter(_.nonEmpty).getOrElse("missing"), null)
}
