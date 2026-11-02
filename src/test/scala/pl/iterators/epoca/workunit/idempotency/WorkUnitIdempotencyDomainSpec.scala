package pl.iterators.epoca.workunit.idempotency

import io.circe.Json
import org.specs2.execute.Result
import org.specs2.mutable.SpecificationLike
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.WorkUnitId
import pl.iterators.epoca.workunit.idempotency.WorkUnitIdempotencyDomain._
import pl.iterators.epoca.workunit.idempotency.WorkUnitIdempotencyOperation.{Create, Update}
import pl.iterators.epoca.workunit.idempotency.WorkUnitIdempotencyRecord.{Completed, Processing, Tombstone}
import pl.iterators.epoca.workunit.idempotency.WorkUnitIdempotencyRecordValidationError._

import java.time.Instant
import java.util.UUID

class WorkUnitIdempotencyDomainSpec extends SpecificationLike {
  private type ValidationResult = Either[WorkUnitIdempotencyRecordValidationError, WorkUnitIdempotencyRecord]
  private type DecodeCase       = (String, RawWorkUnitIdempotencyRecord, ValidationResult)

  private object DecodeCase {
    def apply(
      name: String,
      raw: RawWorkUnitIdempotencyRecord,
      expected: ValidationResult
    ): DecodeCase =
      (name, raw, expected)
  }

  private val claimId = WorkUnitIdempotencyClaimId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
  private val scope = WorkUnitIdempotencyScope(
    OrganizationId(UUID.fromString("22222222-2222-2222-2222-222222222222")),
    UserId(UUID.fromString("33333333-3333-3333-3333-333333333333")),
    WorkUnitIdempotencyKey(UUID.fromString("44444444-4444-4444-4444-444444444444"))
  )
  private val workUnitId         = WorkUnitId(UUID.fromString("55555555-5555-5555-5555-555555555555"))
  private val resultWorkUnitId   = WorkUnitId(UUID.fromString("66666666-6666-6666-6666-666666666666"))
  private val fingerprintVersion = WorkUnitIdempotencyFingerprintVersion(1)
  private val fingerprint        = WorkUnitIdempotencyFingerprint("a" * 64)
  private val canonicalJson      = Json.obj("command" -> Json.fromString("canonical"))
  private val outcomeJson        = Json.obj("result" -> Json.fromString("stored"))
  private val createdAt          = Instant.parse("2026-07-27T10:00:00Z")
  private val responseExpiresAt  = Instant.parse("2026-07-28T10:00:00Z")

  private def raw(
    operation: String = "Create",
    targetWorkUnitId: Option[WorkUnitId] = None,
    canonicalRequest: Option[Json] = Some(canonicalJson),
    state: String = "Processing",
    outcomeStatus: Option[Int] = None,
    outcomeBody: Option[Json] = None,
    resultWorkUnitId: Option[WorkUnitId] = None,
    responseExpiresAt: Option[Instant] = None
  ): RawWorkUnitIdempotencyRecord =
    RawWorkUnitIdempotencyRecord(
      claimId,
      scope,
      operation,
      targetWorkUnitId,
      fingerprintVersion,
      canonicalRequest,
      fingerprint,
      state,
      outcomeStatus,
      outcomeBody,
      resultWorkUnitId,
      createdAt,
      responseExpiresAt
    )

  private def processing(operation: WorkUnitIdempotencyOperation): Processing =
    Processing(claimId, scope, operation, fingerprintVersion, canonicalJson, fingerprint, createdAt)

  private def completed(operation: WorkUnitIdempotencyOperation, resultId: Option[WorkUnitId]): Completed =
    Completed(
      claimId,
      scope,
      operation,
      fingerprintVersion,
      canonicalJson,
      fingerprint,
      WorkUnitWriteOutcome(201, outcomeJson, resultId),
      createdAt,
      responseExpiresAt
    )

  private def tombstone(operation: WorkUnitIdempotencyOperation, resultId: Option[WorkUnitId]): Tombstone =
    Tombstone(claimId, scope, operation, fingerprintVersion, fingerprint, 201, resultId, createdAt, responseExpiresAt)

  private def invalidShape(
    state: String,
    missing: Seq[String] = Seq.empty,
    unexpectedPresent: Seq[String] = Seq.empty
  ): ValidationResult =
    Left(InvalidRecordShape(state, missing, unexpectedPresent))

  private def assertCases(cases: Seq[DecodeCase]): Result =
    cases.collect {
      case (name, rawRecord, expected) if WorkUnitIdempotencyRecord.fromRaw(rawRecord) != expected =>
        s"$name: expected $expected, got ${WorkUnitIdempotencyRecord.fromRaw(rawRecord)}"
    } should beEmpty

  "WorkUnitIdempotencyRecord.fromRaw" should {
    "decode valid Create and Update Processing rows" in {
      assertCases(
        Seq(
          DecodeCase("Create", raw(), Right(processing(Create))),
          DecodeCase("Update", raw(operation = "Update", targetWorkUnitId = Some(workUnitId)), Right(processing(Update(workUnitId))))
        )
      )
    }

    "decode valid Completed rows with and without a result work unit ID" in {
      val completedRaw =
        raw(state = "Completed", outcomeStatus = Some(201), outcomeBody = Some(outcomeJson), responseExpiresAt = Some(responseExpiresAt))

      assertCases(
        Seq(
          DecodeCase("result Some", completedRaw.copy(resultWorkUnitId = Some(resultWorkUnitId)), Right(completed(Create, Some(resultWorkUnitId)))),
          DecodeCase("result None", completedRaw, Right(completed(Create, None)))
        )
      )
    }

    "decode valid Tombstone rows with and without a result work unit ID" in {
      val tombstoneRaw = raw(canonicalRequest = None, state = "Tombstone", outcomeStatus = Some(201), responseExpiresAt = Some(responseExpiresAt))

      assertCases(
        Seq(
          DecodeCase("result Some", tombstoneRaw.copy(resultWorkUnitId = Some(resultWorkUnitId)), Right(tombstone(Create, Some(resultWorkUnitId)))),
          DecodeCase("result None", tombstoneRaw, Right(tombstone(Create, None)))
        )
      )
    }

    "return exact operation-target, unknown operation, and unknown state errors" in {
      assertCases(
        Seq(
          DecodeCase("Create with target", raw(targetWorkUnitId = Some(workUnitId)), Left(InvalidOperationTarget("Create", Some(workUnitId)))),
          DecodeCase("Update without target", raw(operation = "Update"), Left(InvalidOperationTarget("Update", None))),
          DecodeCase("unknown operation", raw(operation = "Delete"), Left(InvalidOperation("Delete"))),
          DecodeCase("unknown state", raw(state = "Pending"), Left(InvalidState("Pending")))
        )
      )
    }

    "report every invalid Processing field with deterministic diagnostics" in {
      val baseline = raw()
      assertCases(
        Seq(
          DecodeCase("missing canonical", baseline.copy(canonicalRequest = None), invalidShape("Processing", missing = Seq("canonicalRequest"))),
          DecodeCase(
            "status present",
            baseline.copy(outcomeStatus = Some(201)),
            invalidShape("Processing", unexpectedPresent = Seq("outcomeStatus"))
          ),
          DecodeCase(
            "body present",
            baseline.copy(outcomeBody = Some(outcomeJson)),
            invalidShape("Processing", unexpectedPresent = Seq("outcomeBody"))
          ),
          DecodeCase(
            "result present",
            baseline.copy(resultWorkUnitId = Some(resultWorkUnitId)),
            invalidShape("Processing", unexpectedPresent = Seq("resultWorkUnitId"))
          ),
          DecodeCase(
            "expiry present",
            baseline.copy(responseExpiresAt = Some(responseExpiresAt)),
            invalidShape("Processing", unexpectedPresent = Seq("responseExpiresAt"))
          ),
          DecodeCase(
            "all violations",
            baseline.copy(
              canonicalRequest = None,
              outcomeStatus = Some(201),
              outcomeBody = Some(outcomeJson),
              resultWorkUnitId = Some(resultWorkUnitId),
              responseExpiresAt = Some(responseExpiresAt)
            ),
            invalidShape(
              "Processing",
              missing = Seq("canonicalRequest"),
              unexpectedPresent = Seq("outcomeStatus", "outcomeBody", "resultWorkUnitId", "responseExpiresAt")
            )
          )
        )
      )
    }

    "report every missing Completed field with deterministic diagnostics" in {
      val baseline = raw(state = "Completed", outcomeStatus = Some(201), outcomeBody = Some(outcomeJson), responseExpiresAt = Some(responseExpiresAt))
      assertCases(
        Seq(
          DecodeCase("canonical", baseline.copy(canonicalRequest = None), invalidShape("Completed", missing = Seq("canonicalRequest"))),
          DecodeCase("status", baseline.copy(outcomeStatus = None), invalidShape("Completed", missing = Seq("outcomeStatus"))),
          DecodeCase("body", baseline.copy(outcomeBody = None), invalidShape("Completed", missing = Seq("outcomeBody"))),
          DecodeCase("expiry", baseline.copy(responseExpiresAt = None), invalidShape("Completed", missing = Seq("responseExpiresAt"))),
          DecodeCase(
            "all required",
            baseline.copy(canonicalRequest = None, outcomeStatus = None, outcomeBody = None, responseExpiresAt = None),
            invalidShape("Completed", missing = Seq("canonicalRequest", "outcomeStatus", "outcomeBody", "responseExpiresAt"))
          )
        )
      )
    }

    "report every invalid Tombstone field with deterministic diagnostics" in {
      val baseline = raw(canonicalRequest = None, state = "Tombstone", outcomeStatus = Some(201), responseExpiresAt = Some(responseExpiresAt))
      assertCases(
        Seq(
          DecodeCase("missing status", baseline.copy(outcomeStatus = None), invalidShape("Tombstone", missing = Seq("outcomeStatus"))),
          DecodeCase("missing expiry", baseline.copy(responseExpiresAt = None), invalidShape("Tombstone", missing = Seq("responseExpiresAt"))),
          DecodeCase(
            "canonical present",
            baseline.copy(canonicalRequest = Some(canonicalJson)),
            invalidShape("Tombstone", unexpectedPresent = Seq("canonicalRequest"))
          ),
          DecodeCase(
            "body present",
            baseline.copy(outcomeBody = Some(outcomeJson)),
            invalidShape("Tombstone", unexpectedPresent = Seq("outcomeBody"))
          ),
          DecodeCase(
            "all violations",
            baseline.copy(canonicalRequest = Some(canonicalJson), outcomeStatus = None, outcomeBody = Some(outcomeJson), responseExpiresAt = None),
            invalidShape("Tombstone", missing = Seq("outcomeStatus", "responseExpiresAt"), unexpectedPresent = Seq("canonicalRequest", "outcomeBody"))
          )
        )
      )
    }
  }
}
