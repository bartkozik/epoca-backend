package pl.iterators.epoca.oauth.repositories

import pl.iterators.epoca.oauth.domain.OAuthDomain.OAuthRateLimitBucketKey
import pl.iterators.epoca.oauth.repositories.OAuthRateLimitRepository.RateLimitDecision
import pl.iterators.epoca.utils.db.BaseRepository.driver.api.DBIO

import java.net.InetAddress
import java.time.Instant

trait OAuthRateLimitRepository {

  /** Must execute inside the caller-owned token issuance transaction. */
  def consumeIp(source: InetAddress, now: Instant): DBIO[RateLimitDecision]

  /** Must execute inside the caller-owned authentication-failure transaction with a stable, non-secret client fingerprint. */
  def consumeClientFailure(bucketKey: OAuthRateLimitBucketKey, now: Instant): DBIO[RateLimitDecision]
  def deleteExpired(now: Instant, batchSize: Int): DBIO[Int]
}

object OAuthRateLimitRepository {
  sealed trait RateLimitDecision
  object RateLimitDecision {
    final case class Allowed(remainingTokens: Double) extends RateLimitDecision
    final case class Limited(retryAfterSeconds: Long) extends RateLimitDecision
  }
}
