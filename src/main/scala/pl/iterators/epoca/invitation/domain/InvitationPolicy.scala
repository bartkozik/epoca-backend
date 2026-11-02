package pl.iterators.epoca.invitation.domain

import java.time.Instant

object InvitationPolicy {
  def isAcceptable(
    now: Instant,
    expiresAt: Option[Instant],
    acceptedAt: Option[Instant],
    rejectedAt: Option[Instant],
    archivedAt: Option[Instant]
  ): Boolean =
    acceptedAt.isEmpty && rejectedAt.isEmpty && archivedAt.isEmpty && expiresAt.forall(now.isBefore)

  def deliveryUnknown(
    now: Instant,
    startedAt: Instant,
    finishedAt: Option[Instant]
  ): Boolean =
    finishedAt.isEmpty && !now.isBefore(startedAt.plusSeconds(120))

  def nextAllowedSend(startedAt: Instant): Instant = startedAt.plusSeconds(60)
}
