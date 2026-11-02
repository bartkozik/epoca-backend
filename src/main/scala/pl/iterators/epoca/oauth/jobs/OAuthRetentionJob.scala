package pl.iterators.epoca.oauth.jobs

import cats.effect.IO
import pl.iterators.epoca.oauth.repositories.{OAuthAuditRepository, OAuthRateLimitRepository}
import pl.iterators.epoca.oauth.services.OAuthConfig
import pl.iterators.epoca.oauth.time.OAuthAuthoritativeTime
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.job.SchedulableJob.ScheduleJobResult
import pl.iterators.epoca.utils.job.{SchedulableJob, SchedulableJobConfig}

import scala.concurrent.ExecutionContext

class OAuthRetentionJob(
  override val config: SchedulableJobConfig,
  transactor: PostgresIOTransactor,
  authoritativeTime: OAuthAuthoritativeTime,
  auditRepository: OAuthAuditRepository,
  rateLimitRepository: OAuthRateLimitRepository,
  oauthConfig: OAuthConfig
)(implicit ec: ExecutionContext)
    extends SchedulableJob {

  override def apply: IO[ScheduleJobResult] =
    transactor
      .executeTransactionally {
        for {
          now <- authoritativeTime.now
          auditCutoff = now.minusNanos(oauthConfig.authenticationAuditRetention.toNanos)
          deletedAudit   <- auditRepository.deleteExpiredAuthentication(auditCutoff, oauthConfig.auditCleanupBatchSize)
          deletedBuckets <- rateLimitRepository.deleteExpired(now, oauthConfig.rateLimitCleanupBatchSize)
        } yield deletedAudit + deletedBuckets
      }
      .map {
        case deleted if deleted > 0 => ScheduleJobResult.Done
        case _                      => ScheduleJobResult.NoWork
      }
}
