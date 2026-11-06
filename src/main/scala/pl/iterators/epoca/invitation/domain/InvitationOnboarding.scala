package pl.iterators.epoca.invitation.domain

import io.circe.Json
import pl.iterators.epoca.invitation.domain.InvitationDomain._
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId
import pl.iterators.epoca.user.domain.UserDomain.UserId

import java.time.Instant
import java.util.UUID

/** Persistence records only: public responses must use explicitly sanitized DTOs. */
object InvitationOnboarding {
  final case class InvitationCreateBody(
    firstName: String,
    lastName: String,
    email: String,
    role: pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationUserRole,
    locale: String)

  final case class InvitationAdminDto(
    id: InvitationId,
    email: String,
    firstName: Option[String],
    lastName: Option[String],
    roles: Seq[String],
    protocolVersion: Int,
    revision: String,
    status: String,
    expiresAt: Option[Instant],
    delivery: Json,
    createdAt: Instant,
    modifiedAt: Instant)

  final class Digest private (val bytes: Vector[Byte]) {
    def toArray: Array[Byte]      = bytes.toArray
    override def toString: String = "Digest(<redacted>)"
    override def equals(other: Any): Boolean = other match {
      case value: Digest => bytes == value.bytes
      case _             => false
    }
    override def hashCode(): Int = bytes.hashCode()
  }
  object Digest {
    def apply(bytes: Array[Byte]): Digest = {
      require(bytes.length == 32, "A SHA-256 digest must contain 32 bytes")
      new Digest(bytes.toVector)
    }
  }
  case class Record(
    invitation: Invitation,
    protocolVersion: Int,
    firstName: Option[String],
    lastName: Option[String],
    revision: Long,
    generation: Long,
    tokenHash: Option[Digest],
    acceptedUserId: Option[UserId],
    locale: Option[String],
    canonicalEmail: Option[String]) {
    override def toString: String = s"InvitationOnboarding.Record(${invitation.id},v$protocolVersion,revision=$revision,generation=$generation)"
  }
  sealed trait DeliveryResult { def databaseValue: String }
  object DeliveryResult {
    case object Succeeded extends DeliveryResult { val databaseValue = "Succeeded" }
    case object Failed    extends DeliveryResult { val databaseValue = "Failed" }
    case object Unknown   extends DeliveryResult { val databaseValue = "Unknown" }
    def fromDatabase(value: String): DeliveryResult = value match {
      case "Succeeded" => Succeeded
      case "Failed"    => Failed
      case "Unknown"   => Unknown
      case _           => throw new IllegalStateException("Invalid invitation delivery result")
    }
  }
  case class DeliveryAttempt(
    id: InvitationDeliveryAttemptId,
    invitationId: InvitationId,
    generation: Long,
    startedAt: Instant,
    finishedAt: Option[Instant],
    result: Option[DeliveryResult],
    safeErrorCode: Option[String])

  case class Audit(
    id: InvitationAuditId,
    invitationId: InvitationId,
    actorId: Option[UserId],
    action: String,
    occurredAt: Instant,
    beforeData: Option[Json],
    afterData: Option[Json])
  case class CommandKey(
    actorId: UserId,
    organizationId: OrganizationId,
    operation: String,
    requestId: UUID)
  case class Command(
    key: CommandKey,
    payloadHash: Digest,
    invitationId: InvitationId,
    result: Json) {
    override def toString: String = s"InvitationCommand(${key.requestId},$invitationId)"
  }

  /** Construct only from cryptographically verified ACS output. Email and SAML evidence coexist. */
  case class VerifiedSamlProof(
    organizationId: OrganizationId,
    trustEpoch: Long,
    issuer: String,
    subject: String,
    canonicalEmail: String,
    verifiedAt: Instant) {
    override def toString: String = "VerifiedSamlProof(<redacted>)"
  }
  case class Proof(emailVerifiedAt: Option[Instant], saml: Option[VerifiedSamlProof])
  case class Access(
    id: InvitationAccessId,
    cookieHash: Digest,
    csrfHash: Digest,
    invitationId: InvitationId,
    generation: Long,
    proof: Proof,
    verifiedUserId: Option[UserId],
    samlRequestId: Option[String],
    expiresAt: Instant,
    completedAt: Option[Instant]) {
    override def toString: String = s"InvitationAccess($id,$invitationId,generation=$generation)"
  }
}
