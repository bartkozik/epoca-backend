package pl.iterators.epoca.oauth.services

import cats.effect.IO
import io.circe.Json
import pl.iterators.epoca.oauth.domain.OAuthDomain._
import pl.iterators.epoca.oauth.repositories.OAuthAuditRepository.NewOAuthAuditEvent
import pl.iterators.epoca.oauth.repositories.OAuthClientRepository.OAuthClientSnapshot
import pl.iterators.epoca.oauth.repositories.OAuthRateLimitRepository.RateLimitDecision
import pl.iterators.epoca.oauth.repositories.{OAuthAuditRepository, OAuthClientRepository, OAuthRateLimitRepository}
import pl.iterators.epoca.oauth.services.OAuthTokenService.OAuthTokenResult._
import pl.iterators.epoca.oauth.services.OAuthTokenService._
import pl.iterators.epoca.oauth.time.OAuthAuthoritativeTime
import pl.iterators.epoca.utils.db.BaseRepository.driver.api.DBIO
import pl.iterators.epoca.utils.db.PostgresIOTransactor

import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.{Base64, UUID}
import scala.concurrent.ExecutionContext

trait OAuthTokenIssuer {
  def issue(
    credentials: Option[OAuthClientCredentials],
    requestedScope: Option[String],
    sourceIp: InetAddress,
    correlationId: Option[OAuthAuditCorrelationId]
  ): IO[OAuthTokenResult]
}

final class OAuthTokenService(
  transactor: PostgresIOTransactor,
  authoritativeTime: OAuthAuthoritativeTime,
  clientRepository: OAuthClientRepository,
  auditRepository: OAuthAuditRepository,
  rateLimitRepository: OAuthRateLimitRepository,
  secretService: OAuthSecretVerifier,
  jwtService: OAuthTokenSigner,
  issuer: String,
  audience: String
)(implicit ec: ExecutionContext)
    extends OAuthTokenIssuer {

  override def issue(
    credentials: Option[OAuthClientCredentials],
    requestedScope: Option[String],
    sourceIp: InetAddress,
    correlationId: Option[OAuthAuditCorrelationId]
  ): IO[OAuthTokenResult] =
    transactor
      .executeTransactionally(issueTransaction(credentials, requestedScope, sourceIp, correlationId))
      .attempt
      .map {
        case Right(result) => result
        case Left(_)       => TemporarilyUnavailable
      }

  private def issueTransaction(
    credentials: Option[OAuthClientCredentials],
    requestedScope: Option[String],
    sourceIp: InetAddress,
    correlationId: Option[OAuthAuditCorrelationId]
  ): DBIO[OAuthTokenResult] =
    for {
      now      <- authoritativeTime.now
      ipBucket <- rateLimitRepository.consumeIp(sourceIp, now)
      result <- ipBucket match {
                  case RateLimitDecision.Limited(retryAfterSeconds) =>
                    auditRepository
                      .insert(ipRateLimitAudit(now, correlationId, sourceIp))
                      .map(_ => RateLimited(retryAfterSeconds))
                  case RateLimitDecision.Allowed(_) => authenticate(credentials, requestedScope, sourceIp, correlationId, now)
                }
    } yield result

  private def authenticate(
    credentials: Option[OAuthClientCredentials],
    requestedScope: Option[String],
    sourceIp: InetAddress,
    correlationId: Option[OAuthAuditCorrelationId],
    now: Instant
  ): DBIO[OAuthTokenResult] =
    credentials match {
      case None =>
        auditUnknownClient(now, correlationId, sourceIp, OAuthAuditFailureCategory.MalformedCredentials).map(_ => InvalidClient)
      case Some(value) =>
        clientRepository.findPublic(value.clientId).flatMap {
          case None =>
            transactor
              .from(IO.blocking(secretService.verifyOrDummy(value.clientSecret, eligibleHash = None)))
              .flatMap(_ => auditUnknownClientFailure(value.clientId, now, correlationId, sourceIp, OAuthAuditFailureCategory.UnknownClient))
          case Some(visible) =>
            transactor.from(IO.blocking(secretService.verifyOrDummy(value.clientSecret, Some(visible.client.secretHash)))).flatMap { secretMatches =>
              OAuthClientEffectiveStatus.resolve(visible.client.status, visible.client.expiresAt, now) match {
                case OAuthClientEffectiveStatus.Revoked =>
                  rejectKnownClientLocked(visible, now, correlationId, sourceIp, OAuthAuditFailureCategory.RevokedClient)
                case OAuthClientEffectiveStatus.Expired =>
                  rejectKnownClientLocked(visible, now, correlationId, sourceIp, OAuthAuditFailureCategory.ExpiredClient)
                case OAuthClientEffectiveStatus.Active if !secretMatches =>
                  rejectKnownClientLocked(visible, now, correlationId, sourceIp, OAuthAuditFailureCategory.InvalidSecret)
                case OAuthClientEffectiveStatus.Active =>
                  issueLocked(visible, value.clientSecret, requestedScope, sourceIp, correlationId, now)
              }
            }
        }
    }

  private def rejectKnownClientLocked(
    visible: OAuthClientSnapshot,
    precheckNow: Instant,
    correlationId: Option[OAuthAuditCorrelationId],
    sourceIp: InetAddress,
    fallbackCategory: OAuthAuditFailureCategory
  ): DBIO[OAuthTokenResult] =
    clientRepository.lock(visible.client.id).flatMap {
      case Some(current) =>
        authoritativeTime.now.flatMap { rejectionNow =>
          auditLockedKnownClientFailure(current, rejectionNow, correlationId, sourceIp, finalFailureCategory(current, rejectionNow, fallbackCategory))
        }
      case None =>
        auditUnknownClientFailure(visible.client.clientId, precheckNow, correlationId, sourceIp, OAuthAuditFailureCategory.UnknownClient)
    }

  private def auditLockedKnownClientFailure(
    snapshot: OAuthClientSnapshot,
    now: Instant,
    correlationId: Option[OAuthAuditCorrelationId],
    sourceIp: InetAddress,
    failureCategory: OAuthAuditFailureCategory
  ): DBIO[OAuthTokenResult] =
    rateLimitRepository.consumeClientFailure(clientFailureBucketKey(snapshot.client.clientId), now).flatMap {
      case RateLimitDecision.Allowed(_) =>
        auditRepository
          .insert(failureAudit(snapshot, now, correlationId, sourceIp, OAuthAuditEventType.InvalidClient, failureCategory))
          .map(_ => InvalidClient)
      case RateLimitDecision.Limited(retryAfterSeconds) =>
        auditRepository
          .insert(failureAudit(snapshot, now, correlationId, sourceIp, OAuthAuditEventType.RateLimit, OAuthAuditFailureCategory.ClientRateLimit))
          .map(_ => RateLimited(retryAfterSeconds))
    }

  private def issueLocked(
    visible: OAuthClientSnapshot,
    secret: OAuthClientSecret,
    requestedScope: Option[String],
    sourceIp: InetAddress,
    correlationId: Option[OAuthAuditCorrelationId],
    now: Instant
  ): DBIO[OAuthTokenResult] =
    clientRepository.lock(visible.client.id).flatMap {
      case Some(current) if current.client.secretVersion != visible.client.secretVersion =>
        authoritativeTime.now.flatMap { rejectionNow =>
          auditLockedKnownClientFailure(
            current,
            rejectionNow,
            correlationId,
            sourceIp,
            finalFailureCategory(current, rejectionNow, OAuthAuditFailureCategory.InvalidSecret)
          )
        }
      case Some(current) =>
        transactor.from(IO.blocking(secretService.verify(secret, current.client.secretHash))).flatMap { secretMatches =>
          authoritativeTime.now.flatMap { finalNow =>
            OAuthClientEffectiveStatus.resolve(current.client.status, current.client.expiresAt, finalNow) match {
              case OAuthClientEffectiveStatus.Revoked =>
                auditLockedKnownClientFailure(current, finalNow, correlationId, sourceIp, OAuthAuditFailureCategory.RevokedClient)
              case OAuthClientEffectiveStatus.Expired =>
                auditLockedKnownClientFailure(current, finalNow, correlationId, sourceIp, OAuthAuditFailureCategory.ExpiredClient)
              case OAuthClientEffectiveStatus.Active if !secretMatches =>
                auditLockedKnownClientFailure(current, finalNow, correlationId, sourceIp, OAuthAuditFailureCategory.InvalidSecret)
              case OAuthClientEffectiveStatus.Active => issueActive(current, requestedScope, sourceIp, correlationId, finalNow)
            }
          }
        }
      case None => auditUnknownClientFailure(visible.client.clientId, now, correlationId, sourceIp, OAuthAuditFailureCategory.UnknownClient)
    }

  private def finalFailureCategory(
    snapshot: OAuthClientSnapshot,
    now: Instant,
    fallback: OAuthAuditFailureCategory
  ): OAuthAuditFailureCategory =
    OAuthClientEffectiveStatus.resolve(snapshot.client.status, snapshot.client.expiresAt, now) match {
      case OAuthClientEffectiveStatus.Revoked => OAuthAuditFailureCategory.RevokedClient
      case OAuthClientEffectiveStatus.Expired => OAuthAuditFailureCategory.ExpiredClient
      case OAuthClientEffectiveStatus.Active  => fallback
    }

  private def issueActive(
    snapshot: OAuthClientSnapshot,
    requestedScope: Option[String],
    sourceIp: InetAddress,
    correlationId: Option[OAuthAuditCorrelationId],
    lockedNow: Instant
  ): DBIO[OAuthTokenResult] =
    OAuthScopeService.resolveRequested(requestedScope, snapshot.scopes) match {
      case Left(_) =>
        auditRepository
          .insert(
            failureAudit(
              snapshot,
              lockedNow,
              correlationId,
              sourceIp,
              OAuthAuditEventType.InvalidScope,
              invalidScopeCategory(requestedScope, snapshot.scopes)
            )
          )
          .map(_ => InvalidScope)
      case Right(scopes) =>
        val issuedAt = lockedNow.truncatedTo(ChronoUnit.SECONDS)
        val tokenId  = UUID.randomUUID()
        val claims = OAuthTokenClaims(
          issuer,
          audience,
          snapshot.client.serviceAccountUserId,
          snapshot.client.clientId,
          snapshot.client.organizationId,
          scopes,
          snapshot.client.policyVersion.asInstanceOf[Long],
          tokenId,
          issuedAt,
          issuedAt.plusSeconds(OAuthJwtService.TokenLifetimeSeconds)
        )
        jwtService.sign(claims) match {
          case Left(_) => DBIO.failed(new IllegalStateException("OAuth access-token signing failed"))
          case Right(token) =>
            auditRepository
              .insert(successAudit(snapshot, scopes, tokenId, sourceIp, correlationId, lockedNow))
              .map(_ => Issued(OAuthTokenResponse(token, scope = OAuthScopeService.render(scopes))))
        }
    }

  private def successAudit(
    snapshot: OAuthClientSnapshot,
    scopes: Set[OAuthScope],
    tokenId: UUID,
    sourceIp: InetAddress,
    correlationId: Option[OAuthAuditCorrelationId],
    occurredAt: Instant
  ): NewOAuthAuditEvent =
    NewOAuthAuditEvent(
      OAuthAuditEventId(UUID.randomUUID()),
      OAuthAuditOccurredAt(occurredAt),
      OAuthAuditCategory.Authentication,
      Some(snapshot.client.organizationId),
      Some(snapshot.client.id),
      OAuthAuditActorType.ServiceAccount,
      Some(snapshot.client.serviceAccountUserId),
      OAuthAuditEventType.TokenIssued,
      OAuthAuditOutcome.Success,
      correlationId,
      Some(tokenId),
      Some(sourceIp),
      Json.obj(
        "scopes"        -> Json.arr(OAuthScope.values.filter(scopes).map(scope => Json.fromString(scope.value)): _*),
        "projectMode"   -> Json.fromString(snapshot.client.projectAccessMode.entryName),
        "projectIds"    -> projectIdsJson(snapshot.projectAccess),
        "policyVersion" -> Json.fromLong(snapshot.client.policyVersion.asInstanceOf[Long]),
        "secretVersion" -> Json.fromLong(snapshot.client.secretVersion.asInstanceOf[Long])
      )
    )

  private def auditUnknownClient(
    now: Instant,
    correlationId: Option[OAuthAuditCorrelationId],
    sourceIp: InetAddress,
    failureCategory: OAuthAuditFailureCategory
  ): DBIO[Unit] =
    auditRepository
      .insertUnknownClientFailure(OAuthAuditEventId(UUID.randomUUID()), OAuthAuditOccurredAt(now), correlationId, Some(sourceIp), failureCategory)
      .map(_ => ())

  private def auditUnknownClientFailure(
    clientId: OAuthClientPublicId,
    now: Instant,
    correlationId: Option[OAuthAuditCorrelationId],
    sourceIp: InetAddress,
    failureCategory: OAuthAuditFailureCategory
  ): DBIO[OAuthTokenResult] =
    rateLimitRepository.consumeClientFailure(clientFailureBucketKey(clientId), now).flatMap {
      case RateLimitDecision.Allowed(_) => auditUnknownClient(now, correlationId, sourceIp, failureCategory).map(_ => InvalidClient)
      case RateLimitDecision.Limited(retryAfterSeconds) =>
        auditRepository.insert(unknownClientRateLimitAudit(now, correlationId, sourceIp)).map(_ => RateLimited(retryAfterSeconds))
    }

  private def failureAudit(
    snapshot: OAuthClientSnapshot,
    now: Instant,
    correlationId: Option[OAuthAuditCorrelationId],
    sourceIp: InetAddress,
    eventType: OAuthAuditEventType,
    failureCategory: OAuthAuditFailureCategory
  ): NewOAuthAuditEvent =
    NewOAuthAuditEvent(
      OAuthAuditEventId(UUID.randomUUID()),
      OAuthAuditOccurredAt(now),
      OAuthAuditCategory.Authentication,
      Some(snapshot.client.organizationId),
      Some(snapshot.client.id),
      OAuthAuditActorType.Anonymous,
      None,
      eventType,
      OAuthAuditOutcome.Failure,
      correlationId,
      tokenJti = None,
      Some(sourceIp),
      Json.obj("failureCategory" -> Json.fromString(failureCategory.entryName))
    )

  private def ipRateLimitAudit(
    now: Instant,
    correlationId: Option[OAuthAuditCorrelationId],
    sourceIp: InetAddress
  ): NewOAuthAuditEvent =
    NewOAuthAuditEvent(
      OAuthAuditEventId(UUID.randomUUID()),
      OAuthAuditOccurredAt(now),
      OAuthAuditCategory.Authentication,
      organizationId = None,
      oauthClientId = None,
      OAuthAuditActorType.Anonymous,
      actorUserId = None,
      OAuthAuditEventType.RateLimit,
      OAuthAuditOutcome.Failure,
      correlationId,
      tokenJti = None,
      Some(sourceIp),
      Json.obj("failureCategory" -> Json.fromString(OAuthAuditFailureCategory.IpRateLimit.entryName))
    )

  private def unknownClientRateLimitAudit(
    now: Instant,
    correlationId: Option[OAuthAuditCorrelationId],
    sourceIp: InetAddress
  ): NewOAuthAuditEvent =
    NewOAuthAuditEvent(
      OAuthAuditEventId(UUID.randomUUID()),
      OAuthAuditOccurredAt(now),
      OAuthAuditCategory.Authentication,
      organizationId = None,
      oauthClientId = None,
      OAuthAuditActorType.Anonymous,
      actorUserId = None,
      OAuthAuditEventType.RateLimit,
      OAuthAuditOutcome.Failure,
      correlationId,
      tokenJti = None,
      Some(sourceIp),
      Json.obj("failureCategory" -> Json.fromString(OAuthAuditFailureCategory.ClientRateLimit.entryName))
    )

  private def clientFailureBucketKey(clientId: OAuthClientPublicId): OAuthRateLimitBucketKey = {
    val digest = MessageDigest.getInstance("SHA-256").digest(clientId.toString.getBytes(StandardCharsets.UTF_8))
    OAuthRateLimitBucketKey(Base64.getUrlEncoder.withoutPadding().encodeToString(digest))
  }

  private def projectIdsJson(access: OAuthProjectAccess): Json = {
    val projectIds = access match {
      case OAuthProjectAccess.AllProjects                  => Seq.empty
      case OAuthProjectAccess.SelectedProjects(projectIds) => projectIds.toSeq.sortBy(_.toString)
    }
    Json.arr(projectIds.map(projectId => Json.fromString(projectId.toString)): _*)
  }

  private def invalidScopeCategory(requestedScope: Option[String], granted: Set[OAuthScope]): OAuthAuditFailureCategory =
    requestedScope match {
      case None => OAuthAuditFailureCategory.MalformedScope
      case Some(value) =>
        val names  = value.split("\\s+").filter(_.nonEmpty).toVector
        val parsed = names.flatMap(OAuthScope.fromValue).toSet
        if (names.isEmpty) OAuthAuditFailureCategory.MalformedScope
        else if (parsed.size != names.distinct.size) OAuthAuditFailureCategory.UnknownScope
        else if (!parsed.subsetOf(granted)) OAuthAuditFailureCategory.UngrantedScope
        else OAuthAuditFailureCategory.MalformedScope
    }
}

object OAuthTokenService {
  final case class OAuthTokenResponse(
    access_token: String,
    token_type: String = "Bearer",
    expires_in: Int = OAuthJwtService.TokenLifetimeSeconds.toInt,
    scope: String) {
    override def toString: String = s"OAuthTokenResponse(access_token=<redacted>,token_type=$token_type,expires_in=$expires_in,scope=$scope)"
  }

  sealed trait OAuthTokenResult
  object OAuthTokenResult {
    final case class Issued(response: OAuthTokenResponse) extends OAuthTokenResult
    case object InvalidRequest                            extends OAuthTokenResult
    case object UnsupportedGrantType                      extends OAuthTokenResult
    case object InvalidScope                              extends OAuthTokenResult
    case object InvalidClient                             extends OAuthTokenResult
    final case class RateLimited(retryAfterSeconds: Long) extends OAuthTokenResult
    case object TemporarilyUnavailable                    extends OAuthTokenResult
  }
}
