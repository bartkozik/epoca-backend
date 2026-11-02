package pl.iterators.epoca.invitation.services

import cats.effect.IO
import pl.iterators.epoca.invitation.domain.InvitationOnboarding.Digest
import pl.iterators.epoca.invitation.repositories.InvitationAccessRepository
import pl.iterators.epoca.utils.db.PostgresIOTransactor

import java.nio.charset.StandardCharsets
import java.time.{Clock, Instant}
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Attempts commit separately before credential checking, even if the following operation fails. */
final class InvitationRateLimitService(
  transactor: PostgresIOTransactor,
  repository: InvitationAccessRepository,
  serverKey: Array[Byte],
  clock: Clock) {
  import InvitationRateLimitService._
  require(serverKey.length >= 32, "Invitation rate key must contain at least 32 bytes")
  private val key = serverKey.clone()
  private[invitation] def keyHash(dimension: String, value: String): Digest = {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(key, "HmacSHA256"))
    Digest(mac.doFinal((dimension + ":" + value).getBytes(StandardCharsets.UTF_8)))
  }
  // IP must already be resolved from the socket peer and the configured trusted-proxy policy.
  def exchange(trustedIp: String): IO[Decision] = consume("Exchange", trustedIp, 30)
  def mutation(cookie: String): IO[Decision]    = consume("Mutation", cookie, 5)
  private def consume(
    dimension: String,
    value: String,
    limit: Long
  ): IO[Decision] = {
    val now   = clock.instant()
    val start = Instant.ofEpochSecond(Math.floorDiv(now.getEpochSecond, 60L) * 60L)
    val end   = start.plusSeconds(60)
    val retry = math.max(1L, end.getEpochSecond - now.getEpochSecond)
    transactor
      .executeTransactionally(repository.consumeRateBucket(dimension, keyHash(dimension, value), start, end, limit))
      .map(allowed => if (allowed) Allowed else Denied(retry))
      .handleError(_ => Denied(retry))
  }
}
object InvitationRateLimitService {
  sealed trait Decision
  case object Allowed                              extends Decision
  final case class Denied(retryAfterSeconds: Long) extends Decision
}
