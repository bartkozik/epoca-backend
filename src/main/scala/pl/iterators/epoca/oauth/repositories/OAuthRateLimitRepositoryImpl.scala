package pl.iterators.epoca.oauth.repositories

import pl.iterators.epoca.oauth.domain.OAuthDomain._
import pl.iterators.epoca.oauth.repositories.OAuthRateLimitRepository.RateLimitDecision
import pl.iterators.epoca.oauth.repositories.tables.OAuthRateLimitBucketTable
import pl.iterators.epoca.oauth.services.OAuthConfig.OAuthRateLimit
import pl.iterators.epoca.utils.db.BaseRepository.driver.api._
import slick.jdbc.SetParameter
import slick.lifted.TableQuery

import java.net.InetAddress
import java.sql.Timestamp
import java.time.Instant
import scala.concurrent.ExecutionContext

class OAuthRateLimitRepositoryImpl(ipLimit: OAuthRateLimit, clientFailureLimit: OAuthRateLimit)(implicit ec: ExecutionContext)
    extends OAuthRateLimitRepository {
  private implicit val setInstant: SetParameter[Instant] =
    SetParameter((value, parameters) => parameters.setTimestamp(Timestamp.from(value)))

  private val buckets = TableQuery[OAuthRateLimitBucketTable]

  override def consumeIp(source: InetAddress, now: Instant): DBIO[RateLimitDecision] =
    consume(OAuthRateLimitDimension.Ip, source.getHostAddress, ipLimit, now)

  override def consumeClientFailure(bucketKey: OAuthRateLimitBucketKey, now: Instant): DBIO[RateLimitDecision] =
    consume(OAuthRateLimitDimension.ClientFailure, bucketKey.toString, clientFailureLimit, now)

  override def deleteExpired(now: Instant, batchSize: Int): DBIO[Int] =
    if (batchSize <= 0 || batchSize > 10000) DBIO.failed(new IllegalArgumentException("OAuth rate-limit cleanup batch size must be in [1, 10000]"))
    else
      sqlu"""
        WITH expired AS MATERIALIZED (
          SELECT dimension, bucket_key
          FROM oauth_rate_limit_bucket
          WHERE expires_at < $now
          ORDER BY expires_at, dimension, bucket_key
          LIMIT $batchSize
          FOR UPDATE SKIP LOCKED
        )
        DELETE FROM oauth_rate_limit_bucket target
        USING expired
        WHERE target.dimension = expired.dimension AND target.bucket_key = expired.bucket_key
      """

  private def consume(
    dimension: OAuthRateLimitDimension,
    key: String,
    limit: OAuthRateLimit,
    now: Instant
  ): DBIO[RateLimitDecision] = {
    val capacity           = limit.capacity.toDouble
    val refillPeriodNanos  = limit.refillPeriod.toNanos.toDouble
    val refillPerNano      = capacity / refillPeriodNanos
    val requestedExpiresAt = now.plusNanos(limit.refillPeriod.toNanos)
    val bucketKey          = OAuthRateLimitBucketKey(key)
    val dimensionName      = dimension.entryName
    val query              = buckets.filter(row => row.dimension === dimension && row.bucketKey === bucketKey)

    for {
      _ <- sqlu"""
        INSERT INTO oauth_rate_limit_bucket (
          dimension, bucket_key, available_tokens, last_refilled_at, expires_at
        ) VALUES (
          $dimensionName, $key, $capacity, $now, $requestedExpiresAt
        )
        ON CONFLICT (dimension, bucket_key) DO NOTHING
      """
      locked <- query.forUpdate.result.head
      effectiveNow    = if (now.isAfter(locked.lastRefilledAt)) now else locked.lastRefilledAt
      elapsedNanos    = java.time.Duration.between(locked.lastRefilledAt, effectiveNow).toNanos
      effectiveTokens = math.min(capacity, locked.availableTokens.asInstanceOf[Double] + elapsedNanos.toDouble * refillPerNano)
      allowed         = effectiveTokens >= 1.0d
      remaining       = if (allowed) effectiveTokens - 1.0d else effectiveTokens
      updated = locked.copy(
                  availableTokens = OAuthRateLimitAvailableTokens(remaining),
                  lastRefilledAt = OAuthRateLimitLastRefilledAt(effectiveNow),
                  expiresAt = OAuthRateLimitExpiresAt(effectiveNow.plusNanos(limit.refillPeriod.toNanos))
                )
      changed <- query.update(updated)
      _ <-
        if (changed == 1) DBIO.successful(())
        else DBIO.failed(new IllegalStateException(s"OAuth rate-limit bucket update affected $changed rows"))
    } yield
      if (allowed) RateLimitDecision.Allowed(remaining)
      else {
        val retryNanos = math.ceil((1.0d - effectiveTokens) / refillPerNano).toLong
        RateLimitDecision.Limited(math.max(1L, (retryNanos + 999999999L) / 1000000000L))
      }
  }
}
