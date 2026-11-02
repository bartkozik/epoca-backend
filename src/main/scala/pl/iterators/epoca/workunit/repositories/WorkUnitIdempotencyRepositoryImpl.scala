package pl.iterators.epoca.workunit.repositories

import io.circe.Json
import pl.iterators.epoca.utils.db.BaseRepository.driver.api._
import pl.iterators.epoca.workunit.idempotency.WorkUnitIdempotencyClaimResult.{Claimed, Existing}
import pl.iterators.epoca.workunit.idempotency.WorkUnitIdempotencyOperation.{Create, Update}
import pl.iterators.epoca.workunit.idempotency.WorkUnitIdempotencyRecord.Processing
import pl.iterators.epoca.workunit.idempotency._
import pl.iterators.epoca.workunit.repositories.WorkUnitIdempotencyRepository.{
  WorkUnitIdempotencyClaimInvariantViolation,
  WorkUnitIdempotencyCompletionInvariantViolation,
  WorkUnitIdempotencyRecordDecodingException
}
import pl.iterators.epoca.workunit.repositories.tables.WorkUnitIdempotencyTable
import slick.dbio.DBIO
import slick.jdbc.SetParameter
import slick.lifted.TableQuery

import java.sql.{Timestamp, Types}
import java.time.Instant
import java.util.UUID
import scala.concurrent.ExecutionContext

class WorkUnitIdempotencyRepositoryImpl(implicit ec: ExecutionContext) extends WorkUnitIdempotencyRepository {
  private implicit val setUuid: SetParameter[UUID] =
    SetParameter((value, parameters) => parameters.setObject(value, Types.OTHER))

  private implicit val setOptionalUuid: SetParameter[Option[UUID]] = SetParameter { (value, parameters) =>
    value match {
      case Some(uuid) => parameters.setObject(uuid, Types.OTHER)
      case None       => parameters.setNull(Types.OTHER)
    }
  }

  private implicit val setJson: SetParameter[Json] =
    SetParameter((value, parameters) => parameters.setObject(value.noSpaces, Types.OTHER))

  private implicit val setInstant: SetParameter[Instant] =
    SetParameter((value, parameters) => parameters.setTimestamp(Timestamp.from(value)))

  private val table = TableQuery[WorkUnitIdempotencyTable]

  override def claim(claim: NewWorkUnitIdempotencyClaim): DBIO[WorkUnitIdempotencyClaimResult] =
    insertClaim(claim).flatMap {
      case 1 => DBIO.successful(Claimed(toProcessing(claim)))
      case 0 =>
        find(claim.scope).flatMap {
          case Some(record) => DBIO.successful(Existing(record))
          case None =>
            DBIO.failed(WorkUnitIdempotencyClaimInvariantViolation("conflict reported but the existing record was not visible"))
        }
      case insertedRows =>
        DBIO.failed(WorkUnitIdempotencyClaimInvariantViolation(s"single insert affected $insertedRows rows"))
    }

  override def complete(
    scope: WorkUnitIdempotencyScope,
    outcome: WorkUnitWriteOutcome,
    expiresAt: Instant
  ): DBIO[Int] =
    table
      .filter(row => matchesScope(row, scope) && row.state === "Processing")
      .map(row => (row.state, row.outcomeStatus, row.outcomeBody, row.resultWorkUnitId, row.responseExpiresAt))
      .update(("Completed", Some(outcome.status), Some(outcome.body), outcome.resultWorkUnitId, Some(expiresAt)))
      .flatMap {
        case 1           => DBIO.successful(1)
        case updatedRows => DBIO.failed(WorkUnitIdempotencyCompletionInvariantViolation(updatedRows))
      }

  override def redactExpired(now: Instant): DBIO[Int] =
    table
      .filter(row => row.state === "Completed" && row.responseExpiresAt <= Option(now))
      .map(row => (row.state, row.canonicalRequest, row.outcomeBody))
      .update(("Tombstone", None, None))

  override def find(scope: WorkUnitIdempotencyScope): DBIO[Option[WorkUnitIdempotencyRecord]] =
    table
      .filter(row => matchesScope(row, scope))
      .result
      .headOption
      .flatMap {
        case Some(raw) => decode(raw).map(Some(_))
        case None      => DBIO.successful(None)
      }

  private def insertClaim(claim: NewWorkUnitIdempotencyClaim): DBIO[Int] = {
    val claimId            = claim.claimId.asInstanceOf[UUID]
    val organizationId     = claim.scope.organizationId.asInstanceOf[UUID]
    val actorId            = claim.scope.actorId.asInstanceOf[UUID]
    val idempotencyKey     = claim.scope.key.asInstanceOf[UUID]
    val fingerprintVersion = claim.fingerprintVersion.asInstanceOf[Int]
    val fingerprint        = claim.fingerprint.asInstanceOf[String]
    val (operation, targetWorkUnitId) = claim.operation match {
      case Create             => "Create" -> Option.empty[UUID]
      case Update(workUnitId) => "Update" -> Some(workUnitId.asInstanceOf[UUID])
    }

    sqlu"""
      INSERT INTO work_unit_idempotency (
        claim_id, organization_id, actor_id, idempotency_key, operation, target_work_unit_id,
        fingerprint_version, canonical_request, fingerprint, state, outcome_status, outcome_body,
        result_work_unit_id, created_at, response_expires_at
      ) VALUES (
        $claimId, $organizationId, $actorId, $idempotencyKey, $operation, $targetWorkUnitId,
        $fingerprintVersion, ${claim.canonicalRequest}, $fingerprint, 'Processing', NULL, NULL,
        NULL, ${claim.createdAt}, NULL
      )
      ON CONFLICT (organization_id, actor_id, idempotency_key) DO NOTHING
    """
  }

  private def matchesScope(row: WorkUnitIdempotencyTable, scope: WorkUnitIdempotencyScope): Rep[Boolean] =
    row.organizationId === scope.organizationId && row.actorId === scope.actorId && row.idempotencyKey === scope.key

  private def decode(raw: RawWorkUnitIdempotencyRecord): DBIO[WorkUnitIdempotencyRecord] =
    WorkUnitIdempotencyRecord.fromRaw(raw) match {
      case Right(record)         => DBIO.successful(record)
      case Left(validationError) => DBIO.failed(WorkUnitIdempotencyRecordDecodingException(validationError))
    }

  private def toProcessing(claim: NewWorkUnitIdempotencyClaim): Processing =
    Processing(claim.claimId, claim.scope, claim.operation, claim.fingerprintVersion, claim.canonicalRequest, claim.fingerprint, claim.createdAt)
}
