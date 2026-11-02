package pl.iterators.epoca.invitation.jobs

import cats.effect.IO
import pl.iterators.epoca.invitation.repositories.InvitationRetentionRepository
import pl.iterators.epoca.oauth.time.OAuthAuthoritativeTime
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.job.SchedulableJob.ScheduleJobResult
import pl.iterators.epoca.utils.job.{SchedulableJob, SchedulableJobConfig}

class InvitationRetentionJob(
  override val config: SchedulableJobConfig,
  transactor: PostgresIOTransactor,
  authoritativeTime: OAuthAuthoritativeTime,
  repository: InvitationRetentionRepository,
  batchSize: Int)
    extends SchedulableJob {
  require(batchSize > 0, "Cleanup batch size must be positive")

  override def apply: IO[ScheduleJobResult] =
    transactor.execute(authoritativeTime.now).flatMap { now =>
      // Separate transactions avoid holding rate-bucket locks while acquiring access/request locks.
      for {
        buckets <- transactor.executeTransactionally(repository.deleteExpiredRateBuckets(now, batchSize))
        access  <- transactor.executeTransactionally(repository.deleteExpiredAccess(now, batchSize))
      } yield if (buckets + access > 0) ScheduleJobResult.Done else ScheduleJobResult.NoWork
    }
}
