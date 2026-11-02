package pl.iterators.epoca.workunit.idempotency

import io.circe.Json
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.WorkUnitId
import pl.iterators.epoca.workunit.idempotency.WorkUnitIdempotencyDomain._
import pl.iterators.epoca.workunit.services.WorkUnitCreateService.WorkUnitCreateRequest
import pl.iterators.epoca.workunit.services.WorkUnitUpdateService.WorkUnitUpdateRequest
import pl.iterators.kebs.tag.meta.tagged
import pl.iterators.kebs.tagged._

import java.time.Instant
import java.util.UUID

@tagged trait Domain {
  trait WorkUnitIdempotencyClaimIdTag
  type WorkUnitIdempotencyClaimId = UUID @@ WorkUnitIdempotencyClaimIdTag

  trait WorkUnitIdempotencyKeyTag
  type WorkUnitIdempotencyKey = UUID @@ WorkUnitIdempotencyKeyTag

  trait WorkUnitIdempotencyFingerprintVersionTag
  type WorkUnitIdempotencyFingerprintVersion = Int @@ WorkUnitIdempotencyFingerprintVersionTag

  trait WorkUnitIdempotencyFingerprintTag
  type WorkUnitIdempotencyFingerprint = String @@ WorkUnitIdempotencyFingerprintTag
}

object WorkUnitIdempotencyDomain extends Domain

sealed trait WorkUnitWriteCommand
object WorkUnitWriteCommand {
  final case class Create(request: WorkUnitCreateRequest)                         extends WorkUnitWriteCommand
  final case class Update(workUnitId: WorkUnitId, request: WorkUnitUpdateRequest) extends WorkUnitWriteCommand
}

final case class CanonicalWorkUnitCommand(
  version: WorkUnitIdempotencyFingerprintVersion,
  json: Json,
  fingerprint: WorkUnitIdempotencyFingerprint)

final case class WorkUnitWriteOutcome(
  status: Int,
  body: Json,
  resultWorkUnitId: Option[WorkUnitId])

sealed trait WorkUnitIdempotencyResult
object WorkUnitIdempotencyResult {
  final case class Response(outcome: WorkUnitWriteOutcome, replayed: Boolean) extends WorkUnitIdempotencyResult
  final case object KeyConflict                                               extends WorkUnitIdempotencyResult
  final case object ResultExpired                                             extends WorkUnitIdempotencyResult
  final case object VersionUnsupported                                        extends WorkUnitIdempotencyResult
}

final case class WorkUnitIdempotencyScope(
  organizationId: OrganizationId,
  actorId: UserId,
  key: WorkUnitIdempotencyKey)

sealed trait WorkUnitIdempotencyOperation
object WorkUnitIdempotencyOperation {
  final case object Create                        extends WorkUnitIdempotencyOperation
  final case class Update(workUnitId: WorkUnitId) extends WorkUnitIdempotencyOperation
}

final case class NewWorkUnitIdempotencyClaim(
  claimId: WorkUnitIdempotencyClaimId,
  scope: WorkUnitIdempotencyScope,
  operation: WorkUnitIdempotencyOperation,
  fingerprintVersion: WorkUnitIdempotencyFingerprintVersion,
  canonicalRequest: Json,
  fingerprint: WorkUnitIdempotencyFingerprint,
  createdAt: Instant)

sealed trait WorkUnitIdempotencyRecord {
  def claimId: WorkUnitIdempotencyClaimId
  def scope: WorkUnitIdempotencyScope
  def operation: WorkUnitIdempotencyOperation
  def fingerprintVersion: WorkUnitIdempotencyFingerprintVersion
  def fingerprint: WorkUnitIdempotencyFingerprint
  def createdAt: Instant
}

object WorkUnitIdempotencyRecord {
  final case class Processing(
    claimId: WorkUnitIdempotencyClaimId,
    scope: WorkUnitIdempotencyScope,
    operation: WorkUnitIdempotencyOperation,
    fingerprintVersion: WorkUnitIdempotencyFingerprintVersion,
    canonicalRequest: Json,
    fingerprint: WorkUnitIdempotencyFingerprint,
    createdAt: Instant)
      extends WorkUnitIdempotencyRecord

  final case class Completed(
    claimId: WorkUnitIdempotencyClaimId,
    scope: WorkUnitIdempotencyScope,
    operation: WorkUnitIdempotencyOperation,
    fingerprintVersion: WorkUnitIdempotencyFingerprintVersion,
    canonicalRequest: Json,
    fingerprint: WorkUnitIdempotencyFingerprint,
    outcome: WorkUnitWriteOutcome,
    createdAt: Instant,
    responseExpiresAt: Instant)
      extends WorkUnitIdempotencyRecord

  final case class Tombstone(
    claimId: WorkUnitIdempotencyClaimId,
    scope: WorkUnitIdempotencyScope,
    operation: WorkUnitIdempotencyOperation,
    fingerprintVersion: WorkUnitIdempotencyFingerprintVersion,
    fingerprint: WorkUnitIdempotencyFingerprint,
    outcomeStatus: Int,
    resultWorkUnitId: Option[WorkUnitId],
    createdAt: Instant,
    responseExpiresAt: Instant)
      extends WorkUnitIdempotencyRecord

  def fromRaw(raw: RawWorkUnitIdempotencyRecord): Either[WorkUnitIdempotencyRecordValidationError, WorkUnitIdempotencyRecord] =
    for {
      operation <- decodeOperation(raw.operation, raw.targetWorkUnitId)
      record    <- decodeRecord(raw, operation)
    } yield record

  private def decodeOperation(operation: String, targetWorkUnitId: Option[WorkUnitId])
    : Either[WorkUnitIdempotencyRecordValidationError, WorkUnitIdempotencyOperation] =
    operation match {
      case "Create" =>
        targetWorkUnitId match {
          case None    => Right(WorkUnitIdempotencyOperation.Create)
          case Some(_) => Left(WorkUnitIdempotencyRecordValidationError.InvalidOperationTarget(operation, targetWorkUnitId))
        }
      case "Update" =>
        targetWorkUnitId match {
          case Some(workUnitId) => Right(WorkUnitIdempotencyOperation.Update(workUnitId))
          case None             => Left(WorkUnitIdempotencyRecordValidationError.InvalidOperationTarget(operation, targetWorkUnitId))
        }
      case unsupported => Left(WorkUnitIdempotencyRecordValidationError.InvalidOperation(unsupported))
    }

  private def decodeRecord(raw: RawWorkUnitIdempotencyRecord, operation: WorkUnitIdempotencyOperation)
    : Either[WorkUnitIdempotencyRecordValidationError, WorkUnitIdempotencyRecord] =
    raw.state match {
      case "Processing" => decodeProcessing(raw, operation)
      case "Completed"  => decodeCompleted(raw, operation)
      case "Tombstone"  => decodeTombstone(raw, operation)
      case state        => Left(WorkUnitIdempotencyRecordValidationError.InvalidState(state))
    }

  private def decodeProcessing(raw: RawWorkUnitIdempotencyRecord, operation: WorkUnitIdempotencyOperation)
    : Either[WorkUnitIdempotencyRecordValidationError, Processing] =
    (raw.canonicalRequest, raw.outcomeStatus, raw.outcomeBody, raw.resultWorkUnitId, raw.responseExpiresAt) match {
      case (Some(canonicalRequest), None, None, None, None) =>
        Right(Processing(raw.claimId, raw.scope, operation, raw.fingerprintVersion, canonicalRequest, raw.fingerprint, raw.createdAt))
      case _ =>
        Left(
          invalidRecordShape(
            raw.state,
            requiredFields = Seq("canonicalRequest" -> raw.canonicalRequest.isDefined),
            forbiddenFields = Seq(
              "outcomeStatus"     -> raw.outcomeStatus.isDefined,
              "outcomeBody"       -> raw.outcomeBody.isDefined,
              "resultWorkUnitId"  -> raw.resultWorkUnitId.isDefined,
              "responseExpiresAt" -> raw.responseExpiresAt.isDefined
            )
          )
        )
    }

  private def decodeCompleted(raw: RawWorkUnitIdempotencyRecord, operation: WorkUnitIdempotencyOperation)
    : Either[WorkUnitIdempotencyRecordValidationError, Completed] =
    (raw.canonicalRequest, raw.outcomeStatus, raw.outcomeBody, raw.responseExpiresAt) match {
      case (Some(canonicalRequest), Some(status), Some(body), Some(responseExpiresAt)) =>
        Right(
          Completed(
            raw.claimId,
            raw.scope,
            operation,
            raw.fingerprintVersion,
            canonicalRequest,
            raw.fingerprint,
            WorkUnitWriteOutcome(status, body, raw.resultWorkUnitId),
            raw.createdAt,
            responseExpiresAt
          )
        )
      case _ =>
        Left(
          invalidRecordShape(
            raw.state,
            requiredFields = Seq(
              "canonicalRequest"  -> raw.canonicalRequest.isDefined,
              "outcomeStatus"     -> raw.outcomeStatus.isDefined,
              "outcomeBody"       -> raw.outcomeBody.isDefined,
              "responseExpiresAt" -> raw.responseExpiresAt.isDefined
            ),
            forbiddenFields = Seq.empty
          )
        )
    }

  private def decodeTombstone(raw: RawWorkUnitIdempotencyRecord, operation: WorkUnitIdempotencyOperation)
    : Either[WorkUnitIdempotencyRecordValidationError, Tombstone] =
    (raw.canonicalRequest, raw.outcomeStatus, raw.outcomeBody, raw.responseExpiresAt) match {
      case (None, Some(status), None, Some(responseExpiresAt)) =>
        Right(
          Tombstone(
            raw.claimId,
            raw.scope,
            operation,
            raw.fingerprintVersion,
            raw.fingerprint,
            status,
            raw.resultWorkUnitId,
            raw.createdAt,
            responseExpiresAt
          )
        )
      case _ =>
        Left(
          invalidRecordShape(
            raw.state,
            requiredFields = Seq("outcomeStatus" -> raw.outcomeStatus.isDefined, "responseExpiresAt" -> raw.responseExpiresAt.isDefined),
            forbiddenFields = Seq("canonicalRequest" -> raw.canonicalRequest.isDefined, "outcomeBody" -> raw.outcomeBody.isDefined)
          )
        )
    }

  private def invalidRecordShape(
    state: String,
    requiredFields: Seq[(String, Boolean)],
    forbiddenFields: Seq[(String, Boolean)]
  ): WorkUnitIdempotencyRecordValidationError.InvalidRecordShape =
    WorkUnitIdempotencyRecordValidationError.InvalidRecordShape(
      state,
      missingFields = requiredFields.collect { case (field, false) => field },
      unexpectedPresentFields = forbiddenFields.collect { case (field, true) => field }
    )
}

final case class RawWorkUnitIdempotencyRecord(
  claimId: WorkUnitIdempotencyClaimId,
  scope: WorkUnitIdempotencyScope,
  operation: String,
  targetWorkUnitId: Option[WorkUnitId],
  fingerprintVersion: WorkUnitIdempotencyFingerprintVersion,
  canonicalRequest: Option[Json],
  fingerprint: WorkUnitIdempotencyFingerprint,
  state: String,
  outcomeStatus: Option[Int],
  outcomeBody: Option[Json],
  resultWorkUnitId: Option[WorkUnitId],
  createdAt: Instant,
  responseExpiresAt: Option[Instant])

sealed trait WorkUnitIdempotencyRecordValidationError
object WorkUnitIdempotencyRecordValidationError {
  final case class InvalidOperationTarget(operation: String, targetWorkUnitId: Option[WorkUnitId]) extends WorkUnitIdempotencyRecordValidationError
  final case class InvalidOperation(operation: String)                                             extends WorkUnitIdempotencyRecordValidationError
  final case class InvalidState(state: String)                                                     extends WorkUnitIdempotencyRecordValidationError
  final case class InvalidRecordShape(
    state: String,
    missingFields: Seq[String],
    unexpectedPresentFields: Seq[String])
      extends WorkUnitIdempotencyRecordValidationError
}

sealed trait WorkUnitIdempotencyClaimResult
object WorkUnitIdempotencyClaimResult {
  final case class Claimed(record: WorkUnitIdempotencyRecord.Processing) extends WorkUnitIdempotencyClaimResult
  final case class Existing(record: WorkUnitIdempotencyRecord)           extends WorkUnitIdempotencyClaimResult
}
