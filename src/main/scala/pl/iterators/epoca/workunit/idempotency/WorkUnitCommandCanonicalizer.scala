package pl.iterators.epoca.workunit.idempotency

import io.circe.Json
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.WorkUnitEntryType
import pl.iterators.epoca.workunit.idempotency.WorkUnitIdempotencyDomain._
import pl.iterators.epoca.workunit.idempotency.WorkUnitWriteCommand.{Create, Update}
import pl.iterators.epoca.workunit.services.WorkUnitCreateRequestNormalizer

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object WorkUnitCommandCanonicalizer {
  val Version1: WorkUnitIdempotencyFingerprintVersion = WorkUnitIdempotencyFingerprintVersion(1)
  val Version2: WorkUnitIdempotencyFingerprintVersion = WorkUnitIdempotencyFingerprintVersion(2)

  sealed trait CanonicalizationError
  object CanonicalizationError {
    final case object V1NotRepresentable extends CanonicalizationError
    final case object VersionUnsupported extends CanonicalizationError
  }

  def versionForNewClaim(actorId: UserId, command: WorkUnitWriteCommand): WorkUnitIdempotencyFingerprintVersion =
    if (isV1Representable(actorId, command)) Version1 else Version2

  def canonicalize(
    version: Int,
    actorId: UserId,
    command: WorkUnitWriteCommand
  ): Either[CanonicalizationError, CanonicalWorkUnitCommand] =
    version match {
      case Version1 if isV1Representable(actorId, command) => Right(canonicalizeV1(command))
      case Version1                                        => Left(CanonicalizationError.V1NotRepresentable)
      case Version2                                        => Right(canonicalizeV2(actorId, command))
      case _                                               => Left(CanonicalizationError.VersionUnsupported)
    }

  private def isV1Representable(actorId: UserId, command: WorkUnitWriteCommand): Boolean =
    command match {
      case Create(request) =>
        val normalized = WorkUnitCreateRequestNormalizer.normalize(actorId, request)
        normalized.entryType == WorkUnitEntryType.Worklog &&
        normalized.targetUserId == actorId &&
        request.duration.isDefined
      case Update(_, _) => true
    }

  private def canonicalizeV1(command: WorkUnitWriteCommand): CanonicalWorkUnitCommand = {
    val json = command match {
      case Create(request) =>
        Json.obj(
          "schemaVersion"    -> Json.fromInt(Version1),
          "method"           -> Json.fromString("POST"),
          "operation"        -> Json.fromString("Create"),
          "targetWorkUnitId" -> Json.Null,
          "payload" -> Json.obj(
            "projectId"   -> Json.fromString(request.projectId.toString),
            "date"        -> Json.fromString(request.date.toString),
            "duration"    -> Json.fromString(request.duration.get.toString),
            "description" -> Json.fromString(request.description),
            "taskId"      -> request.taskId.fold(Json.Null)(value => Json.fromString(value)),
            "isDraft"     -> Json.fromBoolean(request.isDraft)
          )
        )
      case Update(workUnitId, request) =>
        Json.obj(
          "schemaVersion"    -> Json.fromInt(Version1),
          "method"           -> Json.fromString("PATCH"),
          "operation"        -> Json.fromString("Update"),
          "targetWorkUnitId" -> Json.fromString(workUnitId.toString),
          "payload" -> Json.obj(
            "projectId"      -> request.projectId.fold(Json.Null)(id => Json.fromString(id.toString)),
            "date"           -> request.date.fold(Json.Null)(date => Json.fromString(date.toString)),
            "duration"       -> request.duration.fold(Json.Null)(duration => Json.fromString(duration.toString)),
            "description"    -> request.description.fold(Json.Null)(value => Json.fromString(value)),
            "taskId"         -> request.taskId.fold(Json.Null)(value => Json.fromString(value)),
            "shouldFinalize" -> Json.fromBoolean(request.shouldFinalize)
          )
        )
    }

    CanonicalWorkUnitCommand(version = Version1, json = json, fingerprint = fingerprint(json))
  }

  private def canonicalizeV2(actorId: UserId, command: WorkUnitWriteCommand): CanonicalWorkUnitCommand = {
    val json = command match {
      case Create(request) =>
        val normalized = WorkUnitCreateRequestNormalizer.normalize(actorId, request)
        Json.obj(
          "schemaVersion"    -> Json.fromInt(Version2),
          "method"           -> Json.fromString("POST"),
          "operation"        -> Json.fromString("Create"),
          "targetWorkUnitId" -> Json.Null,
          "payload" -> Json.obj(
            "projectId"    -> Json.fromString(normalized.projectId.toString),
            "date"         -> Json.fromString(normalized.date.toString),
            "duration"     -> normalized.duration.fold(Json.Null)(value => Json.fromString(value.toString)),
            "description"  -> Json.fromString(normalized.description),
            "taskId"       -> normalized.taskId.fold(Json.Null)(Json.fromString),
            "isDraft"      -> Json.fromBoolean(normalized.isDraft),
            "entryType"    -> Json.fromString(normalized.entryType.entryName),
            "targetUserId" -> Json.fromString(normalized.targetUserId.toString)
          )
        )
      case Update(workUnitId, request) =>
        Json.obj(
          "schemaVersion"    -> Json.fromInt(Version2),
          "method"           -> Json.fromString("PATCH"),
          "operation"        -> Json.fromString("Update"),
          "targetWorkUnitId" -> Json.fromString(workUnitId.toString),
          "payload" -> Json.obj(
            "projectId"      -> request.projectId.fold(Json.Null)(id => Json.fromString(id.toString)),
            "date"           -> request.date.fold(Json.Null)(date => Json.fromString(date.toString)),
            "duration"       -> request.duration.fold(Json.Null)(duration => Json.fromString(duration.toString)),
            "description"    -> request.description.fold(Json.Null)(value => Json.fromString(value)),
            "taskId"         -> request.taskId.fold(Json.Null)(value => Json.fromString(value)),
            "shouldFinalize" -> Json.fromBoolean(request.shouldFinalize)
          )
        )
    }

    CanonicalWorkUnitCommand(version = Version2, json = json, fingerprint = fingerprint(json))
  }

  private def fingerprint(json: Json): WorkUnitIdempotencyFingerprint =
    WorkUnitIdempotencyFingerprint(
      MessageDigest
        .getInstance("SHA-256")
        .digest(json.noSpaces.getBytes(StandardCharsets.UTF_8))
        .map(byte => f"${byte & 0xff}%02x")
        .mkString
    )
}
