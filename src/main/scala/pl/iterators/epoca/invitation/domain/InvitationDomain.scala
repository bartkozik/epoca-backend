package pl.iterators.epoca.invitation.domain

import pl.iterators.kebs.tag.meta.tagged
import pl.iterators.kebs.tagged._

import java.time.Instant
import java.util.UUID

object InvitationDomain extends Domain {}

@tagged trait Domain {
  trait InvitationDeliveryAttemptIdTag
  type InvitationDeliveryAttemptId = UUID @@ InvitationDeliveryAttemptIdTag
  trait InvitationAuditIdTag
  type InvitationAuditId = UUID @@ InvitationAuditIdTag
  trait InvitationAccessIdTag
  type InvitationAccessId = UUID @@ InvitationAccessIdTag

  trait InvitationIdTag
  type InvitationId = UUID @@ InvitationIdTag

  trait InvitationExpireAtTag
  type InvitationExpireAt = Instant @@ InvitationExpireAtTag

  trait InvitationAcceptAtTag
  type InvitationAcceptAt = Instant @@ InvitationAcceptAtTag

  trait InvitationRejectAtTag
  type InvitationRejectAt = Instant @@ InvitationRejectAtTag

  trait InvitationCreatedAtTag
  type InvitationCreatedAt = Instant @@ InvitationCreatedAtTag

  trait InvitationModifiedAtTag
  type InvitationModifiedAt = Instant @@ InvitationModifiedAtTag

  trait InvitationArchivedAtTag
  type InvitationArchivedAt = Instant @@ InvitationArchivedAtTag
}
