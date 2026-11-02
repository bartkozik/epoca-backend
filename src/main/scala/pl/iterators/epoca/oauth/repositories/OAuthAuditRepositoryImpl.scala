package pl.iterators.epoca.oauth.repositories

import com.github.tminglei.slickpg.InetString
import io.circe.Json
import pl.iterators.epoca.oauth.domain.OAuthDomain._
import pl.iterators.epoca.oauth.repositories.OAuthAuditRepository.{NewOAuthAuditEvent, OAuthAuditEventFilters}
import pl.iterators.epoca.oauth.repositories.tables.{OAuthAuditEventRow, OAuthAuditEventTable}
import pl.iterators.epoca.utils.db.BaseRepository.driver.api._
import slick.jdbc.SetParameter
import slick.lifted.TableQuery

import java.sql.Timestamp
import java.time.Instant
import scala.concurrent.ExecutionContext

class OAuthAuditRepositoryImpl(implicit ec: ExecutionContext) extends OAuthAuditRepository {
  private implicit val setInstant: SetParameter[Instant] =
    SetParameter((value, parameters) => parameters.setTimestamp(Timestamp.from(value)))

  private val events = TableQuery[OAuthAuditEventTable]

  override def insert(event: NewOAuthAuditEvent): DBIO[OAuthAuditEventRow] = {
    val row = toRow(event)
    (events += row).map(_ => row)
  }

  override private[oauth] def insertPrepared(event: NewOAuthAuditEvent): DBIO[Int] =
    events += toRow(event)

  override def insertUnknownClientFailure(
    id: OAuthAuditEventId,
    occurredAt: OAuthAuditOccurredAt,
    correlationId: Option[OAuthAuditCorrelationId],
    sourceIp: Option[java.net.InetAddress],
    failureCategory: OAuthAuditFailureCategory
  ): DBIO[OAuthAuditEventRow] =
    insert(
      NewOAuthAuditEvent(
        id,
        occurredAt,
        OAuthAuditCategory.Authentication,
        None,
        None,
        OAuthAuditActorType.Anonymous,
        None,
        OAuthAuditEventType.InvalidClient,
        OAuthAuditOutcome.Failure,
        correlationId,
        None,
        sourceIp,
        Json.obj("failureCategory" -> Json.fromString(failureCategory.entryName))
      )
    )

  override def find(id: OAuthAuditEventId): DBIO[Option[OAuthAuditEventRow]] =
    events.filter(_.id === id).result.headOption

  override def list(filters: OAuthAuditEventFilters): DBIO[Seq[OAuthAuditEventRow]] = {
    val newestFirst = filtered(filters).sortBy(row => (row.occurredAt.desc, row.id.desc))
    filters.pageSizeAndNumber.fold(newestFirst)(page => newestFirst.drop(page.offset).take(page.size)).result
  }

  override private[oauth] def listForUpdate(filters: OAuthAuditEventFilters): DBIO[Seq[OAuthAuditEventRow]] = {
    val newestFirst = filtered(filters).sortBy(row => (row.occurredAt.desc, row.id.desc))
    filters.pageSizeAndNumber.fold(newestFirst)(page => newestFirst.drop(page.offset).take(page.size)).forUpdate.result
  }

  override def count(filters: OAuthAuditEventFilters): DBIO[Int] =
    filtered(filters).length.result

  override def deleteExpiredAuthentication(cutoff: Instant, batchSize: Int): DBIO[Int] =
    if (batchSize <= 0 || batchSize > 1000) DBIO.failed(new IllegalArgumentException("OAuth audit cleanup batch size must be in [1, 1000]"))
    else {
      val cutoffTimestamp = cutoff
      sqlu"""
        WITH expired AS MATERIALIZED (
          SELECT id
          FROM oauth_audit_event
          WHERE category = 'Authentication' AND occurred_at < $cutoffTimestamp
          ORDER BY occurred_at, id
          LIMIT $batchSize
          FOR UPDATE SKIP LOCKED
        )
        DELETE FROM oauth_audit_event target
        USING expired
        WHERE target.id = expired.id
      """
    }

  private def filtered(filters: OAuthAuditEventFilters): Query[OAuthAuditEventTable, OAuthAuditEventRow, Seq] =
    events.filter(_.oauthClientId === Option(filters.oauthClientId))

  private def toRow(event: NewOAuthAuditEvent): OAuthAuditEventRow =
    OAuthAuditEventRow(
      event.id,
      event.occurredAt,
      event.category,
      event.organizationId,
      event.oauthClientId,
      event.actorType,
      event.actorUserId,
      event.eventType,
      event.outcome,
      event.correlationId,
      event.tokenJti,
      event.sourceIp.map(address => InetString(address.getHostAddress)),
      event.metadata
    )
}
