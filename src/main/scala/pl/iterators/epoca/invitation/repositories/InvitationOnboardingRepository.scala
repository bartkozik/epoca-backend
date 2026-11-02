package pl.iterators.epoca.invitation.repositories

import org.postgresql.util.PSQLException
import pl.iterators.epoca.invitation.domain.InvitationDomain._
import pl.iterators.epoca.invitation.domain.InvitationOnboarding._
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId
import pl.iterators.epoca.utils.db.PageSizeAndNumber
import slick.dbio.DBIO

import java.time.Instant

trait InvitationOnboardingRepository {
  def insert(record: Record): DBIO[Unit]
  def find(id: InvitationId): DBIO[Option[Record]]
  def findInOrganization(organizationId: OrganizationId, id: InvitationId): DBIO[Option[Record]]

  /** Caller must hold a transaction for the entire operation after acquiring this lock. */
  def lockInvitation(id: InvitationId): DBIO[Option[Record]]
  def update(
    record: Record,
    expectedRevision: Long,
    expectedGeneration: Long
  ): DBIO[Boolean]
  def findByTokenHash(hash: Digest): DBIO[Option[Record]]
  def findPendingByEmail(organizationId: OrganizationId, canonicalEmail: String): DBIO[Option[Record]]
  def page(
    organizationId: OrganizationId,
    pagination: PageSizeAndNumber,
    canonicalEmail: Option[String] = None
  ): DBIO[Vector[Record]]
  def count(organizationId: OrganizationId, canonicalEmail: Option[String] = None): DBIO[Long]
  def insertAttempt(attempt: DeliveryAttempt): DBIO[Unit]
  def finishAttempt(
    id: InvitationDeliveryAttemptId,
    invitationId: InvitationId,
    generation: Long,
    finishedAt: Instant,
    result: DeliveryResult,
    safeErrorCode: Option[String]
  ): DBIO[Boolean]
  def attempts(invitationId: InvitationId): DBIO[Vector[DeliveryAttempt]]
  def appendAudit(audit: Audit): DBIO[Unit]
  def audit(invitationId: InvitationId): DBIO[Vector[Audit]]
  def insertCommand(command: Command): DBIO[Boolean]
  def findCommand(key: CommandKey): DBIO[Option[Command]]
}
object InvitationOnboardingRepository {
  sealed trait Conflict
  case object PendingEmailConflict extends Conflict
  case object CommandConflict      extends Conflict
  case object CookieHashConflict   extends Conflict

  /** Classify only after rollback: a PostgreSQL violation aborts the current transaction. */
  def conflict(error: Throwable): Option[Conflict] = error match {
    case e: PSQLException if e.getSQLState == "23505" =>
      Option(e.getServerErrorMessage).flatMap(e => Option(e.getConstraint)).flatMap {
        case "invitation_pending_email_key"      => Some(PendingEmailConflict)
        case "invitation_command_pkey"           => Some(CommandConflict)
        case "invitation_access_cookie_hash_key" => Some(CookieHashConflict)
        case _                                   => None
      }
    case _ => None
  }
}
