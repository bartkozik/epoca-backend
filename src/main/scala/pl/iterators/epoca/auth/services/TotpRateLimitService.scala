package pl.iterators.epoca.auth.services

import cats.effect.IO
import pl.iterators.epoca.invitation.domain.InvitationOnboarding.Digest
import pl.iterators.epoca.invitation.repositories.InvitationAccessRepository
import pl.iterators.epoca.user.domain.UserDomain.UserIdentityId
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import pl.iterators.epoca.utils.db.PostgresIOTransactor

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.{Clock, Instant}
import scala.concurrent.ExecutionContext

/** Shared identity limits commit before checking credentials, including unauthenticated and failed attempts. */
class TotpRateLimitService(
  transactor: PostgresIOTransactor,
  repository: InvitationAccessRepository,
  clock: Clock
)(implicit ec: ExecutionContext) {
  private[auth] def keyHash(identityId: UserIdentityId): Digest =
    Digest(MessageDigest.getInstance("SHA-256").digest(("Totp:" + identityId.toString).getBytes(StandardCharsets.UTF_8)))

  def consume(identityId: UserIdentityId): IO[Boolean] = IO
    .defer {
      val now = clock.instant()
      val key = keyHash(identityId)
      def bucket(
        dimension: String,
        seconds: Long,
        limit: Long
      ): DBIO[Boolean] = {
        val start = Instant.ofEpochSecond(Math.floorDiv(now.getEpochSecond, seconds) * seconds)
        repository.consumeRateBucket(dimension, key, start, start.plusSeconds(seconds), limit)
      }
      transactor.executeTransactionally {
        bucket("TotpMinute", 60, 5).flatMap {
          case false => DBIO.successful(false)
          case true  => bucket("TotpHour", 3600, 20)
        }
      }
    }
    .handleError(_ => false)
}
