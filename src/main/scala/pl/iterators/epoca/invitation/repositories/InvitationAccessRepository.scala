package pl.iterators.epoca.invitation.repositories

import pl.iterators.epoca.invitation.domain.InvitationDomain.InvitationAccessId
import pl.iterators.epoca.invitation.domain.InvitationOnboarding._
import slick.dbio.DBIO

import java.time.Instant

trait InvitationAccessRepository {
  def insert(access: Access): DBIO[Unit]
  def findById(id: InvitationAccessId): DBIO[Option[Access]]
  def lockById(id: InvitationAccessId): DBIO[Option[Access]]
  def rotateCookie(
    access: Access,
    cookieHash: Digest,
    csrfHash: Digest,
    now: Instant
  ): DBIO[Boolean]
  def findByCookieHash(hash: Digest): DBIO[Option[Access]]
  def lockByCookieHash(hash: Digest): DBIO[Option[Access]]

  /** Caller holds canonical recipient/organization/invitation locks, then the access lock; stale generation and completed rows cannot be changed. */
  def update(access: Access, expectedGeneration: Long): DBIO[Boolean]

  /** Caller holds parent and context locks. Does not reopen proofs or alter generation/expiry/completion. */
  def rotateCsrf(id: InvitationAccessId, hash: Digest): DBIO[Unit]

  /** Retain FK-referenced rows, including completed contexts. */
  def revoke(id: InvitationAccessId, now: Instant): DBIO[Unit]
  def consumeRateBucket(
    dimension: String,
    keyHash: Digest,
    windowStart: Instant,
    expiresAt: Instant,
    limit: Long
  ): DBIO[Boolean]
}
