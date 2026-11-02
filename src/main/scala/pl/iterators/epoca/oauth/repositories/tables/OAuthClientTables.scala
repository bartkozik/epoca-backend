package pl.iterators.epoca.oauth.repositories.tables

import com.github.tminglei.slickpg.InetString
import io.circe.Json
import pl.iterators.epoca.oauth.domain.OAuthClient
import pl.iterators.epoca.oauth.domain.OAuthDomain._
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId
import pl.iterators.epoca.project.domain.ProjectDomain.ProjectId
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.utils.db.BaseTable
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import slick.lifted.ProvenShape

import java.util.UUID

final case class OAuthClientScopeRow(oauthClientId: OAuthClientId, scope: OAuthScope)
final case class OAuthClientProjectRow(oauthClientId: OAuthClientId, projectId: ProjectId)
final case class OAuthAuditEventRow(
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
  sourceIp: Option[InetString],
  metadata: Json)
final case class OAuthRateLimitBucketRow(
  dimension: OAuthRateLimitDimension,
  bucketKey: OAuthRateLimitBucketKey,
  availableTokens: OAuthRateLimitAvailableTokens,
  lastRefilledAt: OAuthRateLimitLastRefilledAt,
  expiresAt: OAuthRateLimitExpiresAt)

private[tables] object OAuthClientTableMappings {
  implicit val oauthScopeColumnType: BaseColumnType[OAuthScope] =
    MappedColumnType.base[OAuthScope, String](
      _.value,
      value => OAuthScope.fromValue(value).getOrElse(throw new IllegalArgumentException(s"Unknown persisted OAuth scope: $value"))
    )
}

class OAuthClientTable(tag: Tag) extends BaseTable[OAuthClient](tag, "oauth_client") {
  def id: Rep[OAuthClientId]                           = column[OAuthClientId]("id")
  def clientId: Rep[OAuthClientPublicId]               = column[OAuthClientPublicId]("client_id")
  def organizationId: Rep[OrganizationId]              = column[OrganizationId]("organization_id")
  def serviceAccountUserId: Rep[UserId]                = column[UserId]("service_account_user_id")
  def name: Rep[OAuthClientName]                       = column[OAuthClientName]("name")
  def status: Rep[OAuthClientStatus]                   = column[OAuthClientStatus]("status")
  def projectAccessMode: Rep[OAuthProjectAccessMode]   = column[OAuthProjectAccessMode]("project_access_mode")
  def secretHash: Rep[OAuthClientSecretHash]           = column[OAuthClientSecretHash]("secret_hash")
  def secretVersion: Rep[OAuthClientSecretVersion]     = column[OAuthClientSecretVersion]("secret_version")
  def version: Rep[OAuthClientVersion]                 = column[OAuthClientVersion]("version")
  def policyVersion: Rep[OAuthClientPolicyVersion]     = column[OAuthClientPolicyVersion]("policy_version")
  def createdAt: Rep[OAuthClientCreatedAt]             = column[OAuthClientCreatedAt]("created_at")
  def createdBy: Rep[UserId]                           = column[UserId]("created_by")
  def modifiedAt: Rep[OAuthClientModifiedAt]           = column[OAuthClientModifiedAt]("modified_at")
  def modifiedBy: Rep[UserId]                          = column[UserId]("modified_by")
  def secretRotatedAt: Rep[OAuthClientSecretRotatedAt] = column[OAuthClientSecretRotatedAt]("secret_rotated_at")
  def secretRotatedBy: Rep[UserId]                     = column[UserId]("secret_rotated_by")
  def revokedAt: Rep[Option[OAuthClientRevokedAt]]     = column[Option[OAuthClientRevokedAt]]("revoked_at")
  def revokedBy: Rep[Option[UserId]]                   = column[Option[UserId]]("revoked_by")
  def expiresAt: Rep[Option[OAuthClientExpiresAt]]     = column[Option[OAuthClientExpiresAt]]("expires_at")
  def lastUsedAt: Rep[Option[OAuthClientLastUsedAt]]   = column[Option[OAuthClientLastUsedAt]]("last_used_at")

  override def * : ProvenShape[OAuthClient] =
    (
      id,
      clientId,
      organizationId,
      serviceAccountUserId,
      name,
      status,
      projectAccessMode,
      secretHash,
      secretVersion,
      version,
      policyVersion,
      createdAt,
      createdBy,
      modifiedAt,
      modifiedBy,
      secretRotatedAt,
      secretRotatedBy,
      revokedAt,
      revokedBy,
      expiresAt,
      lastUsedAt
    ) <> (OAuthClient.tupled, OAuthClient.unapply _)
}

class OAuthClientScopeTable(tag: Tag) extends BaseTable[OAuthClientScopeRow](tag, "oauth_client_scope") {
  import OAuthClientTableMappings._

  def oauthClientId: Rep[OAuthClientId] = column[OAuthClientId]("oauth_client_id")
  def scope: Rep[OAuthScope]            = column[OAuthScope]("scope")

  override def * : ProvenShape[OAuthClientScopeRow] = (oauthClientId, scope) <> (OAuthClientScopeRow.tupled, OAuthClientScopeRow.unapply _)
}

class OAuthClientProjectTable(tag: Tag) extends BaseTable[OAuthClientProjectRow](tag, "oauth_client_project") {
  def oauthClientId: Rep[OAuthClientId] = column[OAuthClientId]("oauth_client_id")
  def projectId: Rep[ProjectId]         = column[ProjectId]("project_id")

  override def * : ProvenShape[OAuthClientProjectRow] = (oauthClientId, projectId) <> (OAuthClientProjectRow.tupled, OAuthClientProjectRow.unapply _)
}

class OAuthAuditEventTable(tag: Tag) extends BaseTable[OAuthAuditEventRow](tag, "oauth_audit_event") {
  def id: Rep[OAuthAuditEventId]                          = column[OAuthAuditEventId]("id")
  def occurredAt: Rep[OAuthAuditOccurredAt]               = column[OAuthAuditOccurredAt]("occurred_at")
  def category: Rep[OAuthAuditCategory]                   = column[OAuthAuditCategory]("category")
  def organizationId: Rep[Option[OrganizationId]]         = column[Option[OrganizationId]]("organization_id")
  def oauthClientId: Rep[Option[OAuthClientId]]           = column[Option[OAuthClientId]]("oauth_client_id")
  def actorType: Rep[OAuthAuditActorType]                 = column[OAuthAuditActorType]("actor_type")
  def actorUserId: Rep[Option[UserId]]                    = column[Option[UserId]]("actor_user_id")
  def eventType: Rep[OAuthAuditEventType]                 = column[OAuthAuditEventType]("event_type")
  def outcome: Rep[OAuthAuditOutcome]                     = column[OAuthAuditOutcome]("outcome")
  def correlationId: Rep[Option[OAuthAuditCorrelationId]] = column[Option[OAuthAuditCorrelationId]]("correlation_id")
  def tokenJti: Rep[Option[UUID]]                         = column[Option[UUID]]("token_jti")
  def sourceIp: Rep[Option[InetString]]                   = column[Option[InetString]]("source_ip")
  def metadata: Rep[Json]                                 = column[Json]("metadata")

  override def * : ProvenShape[OAuthAuditEventRow] =
    (
      id,
      occurredAt,
      category,
      organizationId,
      oauthClientId,
      actorType,
      actorUserId,
      eventType,
      outcome,
      correlationId,
      tokenJti,
      sourceIp,
      metadata
    ) <> (OAuthAuditEventRow.tupled, OAuthAuditEventRow.unapply _)
}

class OAuthRateLimitBucketTable(tag: Tag) extends BaseTable[OAuthRateLimitBucketRow](tag, "oauth_rate_limit_bucket") {
  def dimension: Rep[OAuthRateLimitDimension]             = column[OAuthRateLimitDimension]("dimension")
  def bucketKey: Rep[OAuthRateLimitBucketKey]             = column[OAuthRateLimitBucketKey]("bucket_key")
  def availableTokens: Rep[OAuthRateLimitAvailableTokens] = column[OAuthRateLimitAvailableTokens]("available_tokens")
  def lastRefilledAt: Rep[OAuthRateLimitLastRefilledAt]   = column[OAuthRateLimitLastRefilledAt]("last_refilled_at")
  def expiresAt: Rep[OAuthRateLimitExpiresAt]             = column[OAuthRateLimitExpiresAt]("expires_at")

  override def * : ProvenShape[OAuthRateLimitBucketRow] =
    (dimension, bucketKey, availableTokens, lastRefilledAt, expiresAt) <> (OAuthRateLimitBucketRow.tupled, OAuthRateLimitBucketRow.unapply _)
}
