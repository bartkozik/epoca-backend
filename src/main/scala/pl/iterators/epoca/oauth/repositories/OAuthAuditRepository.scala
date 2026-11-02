package pl.iterators.epoca.oauth.repositories

import io.circe.Json
import pl.iterators.epoca.oauth.domain.OAuthDomain._
import pl.iterators.epoca.oauth.repositories.OAuthAuditRepository.{NewOAuthAuditEvent, OAuthAuditEventFilters}
import pl.iterators.epoca.oauth.repositories.tables.OAuthAuditEventRow
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.utils.db.BaseRepository.driver.api.DBIO
import pl.iterators.epoca.utils.db.PageSizeAndNumber

import java.net.InetAddress
import java.time.Instant
import java.util.UUID

trait OAuthAuditRepository {
  def insert(event: NewOAuthAuditEvent): DBIO[OAuthAuditEventRow]
  private[oauth] def insertPrepared(event: NewOAuthAuditEvent): DBIO[Int] = {
    val _ = event.id
    DBIO.failed(new UnsupportedOperationException("Prepared OAuth audit insert is unavailable"))
  }
  def insertUnknownClientFailure(
    id: OAuthAuditEventId,
    occurredAt: OAuthAuditOccurredAt,
    correlationId: Option[OAuthAuditCorrelationId],
    sourceIp: Option[InetAddress],
    failureCategory: OAuthAuditFailureCategory
  ): DBIO[OAuthAuditEventRow]
  def find(id: OAuthAuditEventId): DBIO[Option[OAuthAuditEventRow]]
  def list(filters: OAuthAuditEventFilters): DBIO[Seq[OAuthAuditEventRow]]
  private[oauth] def listForUpdate(filters: OAuthAuditEventFilters): DBIO[Seq[OAuthAuditEventRow]] = list(filters)
  def count(filters: OAuthAuditEventFilters): DBIO[Int]
  def deleteExpiredAuthentication(cutoff: Instant, batchSize: Int): DBIO[Int]
}

object OAuthAuditRepository {
  final case class NewOAuthAuditEvent(
    id: OAuthAuditEventId,
    occurredAt: OAuthAuditOccurredAt,
    category: OAuthAuditCategory,
    organizationId: Option[OrganizationId],
    oauthClientId: Option[OAuthClientId],
    actorType: OAuthAuditActorType,
    actorUserId: Option[UserId],
    eventType: OAuthAuditEventType,
    outcome: OAuthAuditOutcome,
    correlationId: Option[OAuthAuditCorrelationId],
    tokenJti: Option[UUID],
    sourceIp: Option[InetAddress],
    metadata: Json)

  final case class OAuthAuditEventFilters(oauthClientId: OAuthClientId, pageSizeAndNumber: Option[PageSizeAndNumber] = None)
}
