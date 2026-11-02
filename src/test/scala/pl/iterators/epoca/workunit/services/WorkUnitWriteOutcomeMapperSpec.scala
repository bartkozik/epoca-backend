package pl.iterators.epoca.workunit.services

import io.circe.Json
import org.apache.pekko.http.scaladsl.model.StatusCodes
import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.utils.ServiceSpec
import pl.iterators.epoca.workunit.services.WorkUnitCreateService.WorkUnitCreateResult
import pl.iterators.epoca.workunit.services.WorkUnitDtoService.WorkUnitDto
import pl.iterators.epoca.workunit.services.WorkUnitUpdateService.WorkUnitUpdateResult
import pl.iterators.epoca.workunit.services.WorkUnitWriteOutcomeMapperSpec.ExpectedError

class WorkUnitWriteOutcomeMapperSpec extends ServiceSpec {
  private val correlationId = "correlation-123"
  private val mapper        = new WorkUnitWriteOutcomeMapper()

  lazy val workUnitDtoGenerator: AllGenerators[WorkUnitDto] = allGenerators[WorkUnitDto]

  private def expectedDtoBody(dto: WorkUnitDto): Json = WorkUnitDto.encoder(dto)

  private def expectedErrorBody(expected: ExpectedError, instance: String = s"cid:$correlationId"): Json =
    Json
      .obj(
        "type"     -> Json.fromString(expected.`type`),
        "status"   -> Json.fromInt(expected.status),
        "title"    -> Json.fromString(expected.title),
        "detail"   -> expected.detail.fold(Json.Null)(Json.fromString),
        "instance" -> Json.fromString(instance)
      )
      .dropNullValues

  "create outcome mapping" should {
    "serialize source, effective and override data in the exact successful DTO snapshot" in {
      val ordinaryDto    = workUnitDtoGenerator.normal.generate.copy(`override` = WorkUnitOverrideDto.empty)
      val classification = allGenerators[WorkUnitOverrideDto].normal.generate
      val classifiedDto  = ordinaryDto.copy(`override` = classification)

      Seq(ordinaryDto, classifiedDto).foreach { dto =>
        val outcome = mapper.create(WorkUnitCreateResult.Created(dto), correlationId)

        outcome.status shouldEqual StatusCodes.Created.intValue
        outcome.body shouldEqual expectedDtoBody(dto)
        outcome.resultWorkUnitId shouldEqual Some(dto.id)
      }
      expectedDtoBody(ordinaryDto).asObject.get.contains("pmClassification") should beFalse
      expectedDtoBody(classifiedDto).hcursor.downField("override").get[Boolean]("active") shouldEqual Right(classification.active)
    }

    "map every expected failure to a stable RFC 9457 snapshot" in {
      val cases = Seq(
        ExpectedError(
          WorkUnitCreateResult.OrganizationNotFound,
          StatusCodes.NotFound.intValue,
          "result:work-unit-create:organization-not-found",
          "Organization not found"
        ),
        ExpectedError(
          WorkUnitCreateResult.NoAccessToOrganization,
          StatusCodes.NotFound.intValue,
          "result:work-unit-create:no-access-to-organization",
          "Organization not found"
        ),
        ExpectedError(
          WorkUnitCreateResult.ProjectNotFound,
          StatusCodes.NotFound.intValue,
          "result:work-unit-create:project-not-found",
          "Project not found"
        ),
        ExpectedError(
          WorkUnitCreateResult.NoAccessToProject,
          StatusCodes.Forbidden.intValue,
          "result:work-unit-create:no-access-to-project",
          "Access to project denied"
        ),
        ExpectedError(
          WorkUnitCreateResult.NonPositiveDuration,
          StatusCodes.BadRequest.intValue,
          "result:work-unit-create:non-positive-duration",
          "Invalid duration",
          Some("Duration should be positive")
        ),
        ExpectedError(
          WorkUnitCreateResult.NonNeutralNoteDuration,
          StatusCodes.BadRequest.intValue,
          "result:work-unit-create:non-neutral-note-duration",
          "Invalid note duration",
          Some("Note duration should be zero")
        ),
        ExpectedError(
          WorkUnitCreateResult.FutureWorklogDate,
          StatusCodes.BadRequest.intValue,
          "result:work-unit-create:future-worklog-date",
          "Invalid worklog date",
          Some("Worklog date cannot be in the future")
        ),
        ExpectedError(
          WorkUnitCreateResult.NoAccessToTargetUser,
          StatusCodes.Forbidden.intValue,
          "result:work-unit-create:no-access-to-target-user",
          "Access to target user denied"
        ),
        ExpectedError(
          WorkUnitCreateResult.TargetUserNotAssignedToProject,
          StatusCodes.Forbidden.intValue,
          "result:work-unit-create:target-user-not-assigned-to-project",
          "Target user is not assigned to project"
        ),
        ExpectedError(
          WorkUnitCreateResult.TargetUserNotFound,
          StatusCodes.NotFound.intValue,
          "result:work-unit-create:target-user-not-found",
          "Target user not found"
        )
      )

      cases.foreach { expected =>
        val outcome = mapper.create(expected.result.asInstanceOf[WorkUnitCreateResult], correlationId)
        outcome.status shouldEqual expected.status
        outcome.body shouldEqual expectedErrorBody(expected)
        outcome.resultWorkUnitId shouldEqual None
      }
      success
    }
  }

  "update outcome mapping" should {
    "serialize source, effective and override data in the exact successful DTO snapshot" in {
      val ordinaryDto    = workUnitDtoGenerator.normal.generate.copy(`override` = WorkUnitOverrideDto.empty)
      val classification = allGenerators[WorkUnitOverrideDto].normal.generate
      val classifiedDto  = ordinaryDto.copy(`override` = classification)

      Seq(ordinaryDto, classifiedDto).foreach { dto =>
        val outcome = mapper.update(WorkUnitUpdateResult.Updated(dto), correlationId)

        outcome.status shouldEqual StatusCodes.OK.intValue
        outcome.body shouldEqual expectedDtoBody(dto)
        outcome.resultWorkUnitId shouldEqual Some(dto.id)
      }
      expectedDtoBody(ordinaryDto).asObject.get.contains("pmClassification") should beFalse
      expectedDtoBody(classifiedDto).hcursor.downField("override").get[Boolean]("active") shouldEqual Right(classification.active)
    }

    "map every expected failure to a stable RFC 9457 snapshot" in {
      val cases = Seq(
        ExpectedError(
          WorkUnitUpdateResult.OrganizationNotFound,
          StatusCodes.NotFound.intValue,
          "result:work-unit-update:organization-not-found",
          "Organization not found"
        ),
        ExpectedError(
          WorkUnitUpdateResult.NoAccessToOrganization,
          StatusCodes.NotFound.intValue,
          "result:work-unit-update:no-access-to-organization",
          "Organization not found"
        ),
        ExpectedError(
          WorkUnitUpdateResult.WorkUnitNotFound,
          StatusCodes.NotFound.intValue,
          "result:work-unit-update:work-unit-not-found",
          "Work unit not found"
        ),
        ExpectedError(
          WorkUnitUpdateResult.NoAccessToWorkUnit,
          StatusCodes.Forbidden.intValue,
          "result:work-unit-update:no-access-to-work-unit",
          "Access to work unit denied"
        ),
        ExpectedError(
          WorkUnitUpdateResult.ProjectNotFound,
          StatusCodes.NotFound.intValue,
          "result:work-unit-update:project-not-found",
          "Project not found"
        ),
        ExpectedError(
          WorkUnitUpdateResult.NoAccessToProject,
          StatusCodes.Forbidden.intValue,
          "result:work-unit-update:no-access-to-project",
          "Access to project denied"
        ),
        ExpectedError(
          WorkUnitUpdateResult.WorkUnitInactive,
          StatusCodes.Forbidden.intValue,
          "result:work-unit-update:work-unit-inactive",
          "Work unit inactive",
          Some("Work unit can't be edited anymore")
        ),
        ExpectedError(
          WorkUnitUpdateResult.NonPositiveDuration,
          StatusCodes.BadRequest.intValue,
          "result:work-unit-update:non-positive-duration",
          "Invalid duration",
          Some("Duration should be positive")
        ),
        ExpectedError(
          WorkUnitUpdateResult.NonNeutralNoteDuration,
          StatusCodes.BadRequest.intValue,
          "result:work-unit-update:non-neutral-note-duration",
          "Invalid note duration",
          Some("Note duration should be zero")
        ),
        ExpectedError(
          WorkUnitUpdateResult.FutureWorklogDate,
          StatusCodes.BadRequest.intValue,
          "result:work-unit-update:future-worklog-date",
          "Invalid worklog date",
          Some("Worklog date cannot be in the future")
        )
      )

      cases.foreach { expected =>
        val outcome = mapper.update(expected.result.asInstanceOf[WorkUnitUpdateResult], correlationId)
        outcome.status shouldEqual expected.status
        outcome.body shouldEqual expectedErrorBody(expected)
        outcome.resultWorkUnitId shouldEqual None
      }
      success
    }
  }

  "problem instance mapping" should {
    "percent-encode an unsafe client correlation ID without losing information for create and update errors" in {
      val unsafeCorrelationId = "client % id"
      val expectedInstance    = "cid:client%20%25%20id"
      val createError = ExpectedError(
        WorkUnitCreateResult.ProjectNotFound,
        StatusCodes.NotFound.intValue,
        "result:work-unit-create:project-not-found",
        "Project not found"
      )
      val updateError = ExpectedError(
        WorkUnitUpdateResult.WorkUnitNotFound,
        StatusCodes.NotFound.intValue,
        "result:work-unit-update:work-unit-not-found",
        "Work unit not found"
      )

      mapper.create(WorkUnitCreateResult.ProjectNotFound, unsafeCorrelationId).body shouldEqual
        expectedErrorBody(createError, expectedInstance)
      mapper.update(WorkUnitUpdateResult.WorkUnitNotFound, unsafeCorrelationId).body shouldEqual
        expectedErrorBody(updateError, expectedInstance)
    }

    "use a stable fallback for an empty client correlation ID" in {
      val expectedInstance = "cid:missing"
      val createError = ExpectedError(
        WorkUnitCreateResult.ProjectNotFound,
        StatusCodes.NotFound.intValue,
        "result:work-unit-create:project-not-found",
        "Project not found"
      )
      val updateError = ExpectedError(
        WorkUnitUpdateResult.WorkUnitNotFound,
        StatusCodes.NotFound.intValue,
        "result:work-unit-update:work-unit-not-found",
        "Work unit not found"
      )

      mapper.create(WorkUnitCreateResult.ProjectNotFound, "").body shouldEqual expectedErrorBody(createError, expectedInstance)
      mapper.update(WorkUnitUpdateResult.WorkUnitNotFound, "").body shouldEqual expectedErrorBody(updateError, expectedInstance)
    }
  }
}

object WorkUnitWriteOutcomeMapperSpec {
  final case class ExpectedError(
    result: Any,
    status: Int,
    `type`: String,
    title: String,
    detail: Option[String] = None)
}
