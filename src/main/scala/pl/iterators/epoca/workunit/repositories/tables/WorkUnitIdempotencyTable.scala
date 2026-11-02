package pl.iterators.epoca.workunit.repositories.tables

import io.circe.Json
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.utils.db.BaseTable
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.WorkUnitId
import pl.iterators.epoca.workunit.idempotency.WorkUnitIdempotencyDomain._
import pl.iterators.epoca.workunit.idempotency.{RawWorkUnitIdempotencyRecord, WorkUnitIdempotencyScope}
import slick.lifted.ProvenShape

import java.time.Instant

class WorkUnitIdempotencyTable(tag: Tag) extends BaseTable[RawWorkUnitIdempotencyRecord](tag, "work_unit_idempotency") {
  def claimId: Rep[WorkUnitIdempotencyClaimId]    = column[WorkUnitIdempotencyClaimId]("claim_id")
  def organizationId: Rep[OrganizationId]         = column[OrganizationId]("organization_id")
  def actorId: Rep[UserId]                        = column[UserId]("actor_id")
  def idempotencyKey: Rep[WorkUnitIdempotencyKey] = column[WorkUnitIdempotencyKey]("idempotency_key")
  def operation: Rep[String]                      = column[String]("operation")
  def targetWorkUnitId: Rep[Option[WorkUnitId]]   = column[Option[WorkUnitId]]("target_work_unit_id")
  def fingerprintVersion: Rep[WorkUnitIdempotencyFingerprintVersion] =
    column[WorkUnitIdempotencyFingerprintVersion]("fingerprint_version")
  def canonicalRequest: Rep[Option[Json]]              = column[Option[Json]]("canonical_request")
  def fingerprint: Rep[WorkUnitIdempotencyFingerprint] = column[WorkUnitIdempotencyFingerprint]("fingerprint")
  def state: Rep[String]                               = column[String]("state")
  def outcomeStatus: Rep[Option[Int]]                  = column[Option[Int]]("outcome_status")
  def outcomeBody: Rep[Option[Json]]                   = column[Option[Json]]("outcome_body")
  def resultWorkUnitId: Rep[Option[WorkUnitId]]        = column[Option[WorkUnitId]]("result_work_unit_id")
  def createdAt: Rep[Instant]                          = column[Instant]("created_at")
  def responseExpiresAt: Rep[Option[Instant]]          = column[Option[Instant]]("response_expires_at")

  override def * : ProvenShape[RawWorkUnitIdempotencyRecord] =
    (
      claimId,
      organizationId,
      actorId,
      idempotencyKey,
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
    ) <> (WorkUnitIdempotencyTable.fromTuple, WorkUnitIdempotencyTable.toTuple)
}

object WorkUnitIdempotencyTable {
  private type RowTuple = (
    WorkUnitIdempotencyClaimId,
    OrganizationId,
    UserId,
    WorkUnitIdempotencyKey,
    String,
    Option[WorkUnitId],
    WorkUnitIdempotencyFingerprintVersion,
    Option[Json],
    WorkUnitIdempotencyFingerprint,
    String,
    Option[Int],
    Option[Json],
    Option[WorkUnitId],
    Instant,
    Option[Instant]
  )

  private def fromTuple(row: RowTuple): RawWorkUnitIdempotencyRecord =
    RawWorkUnitIdempotencyRecord(
      row._1,
      WorkUnitIdempotencyScope(row._2, row._3, row._4),
      row._5,
      row._6,
      row._7,
      row._8,
      row._9,
      row._10,
      row._11,
      row._12,
      row._13,
      row._14,
      row._15
    )

  private def toTuple(row: RawWorkUnitIdempotencyRecord): Option[RowTuple] =
    Some(
      (
        row.claimId,
        row.scope.organizationId,
        row.scope.actorId,
        row.scope.key,
        row.operation,
        row.targetWorkUnitId,
        row.fingerprintVersion,
        row.canonicalRequest,
        row.fingerprint,
        row.state,
        row.outcomeStatus,
        row.outcomeBody,
        row.resultWorkUnitId,
        row.createdAt,
        row.responseExpiresAt
      )
    )
}
