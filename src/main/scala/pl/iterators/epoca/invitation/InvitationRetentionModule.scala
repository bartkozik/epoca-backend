package pl.iterators.epoca.invitation

import cats.effect.unsafe.IORuntime
import com.typesafe.config.Config
import pl.iterators.epoca.invitation.jobs.InvitationRetentionJob
import pl.iterators.epoca.invitation.repositories.InvitationRetentionRepository
import pl.iterators.epoca.oauth.time.PostgresOAuthAuthoritativeTime
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.job.{SchedulableJob, SchedulableJobConfig, SchedulableJobProvider}

import scala.concurrent.ExecutionContext

trait InvitationRetentionModule extends SchedulableJobProvider {
  implicit val executor: ExecutionContext
  implicit val runtime: IORuntime
  val config: Config
  val transactor: PostgresIOTransactor

  private lazy val invitationRetentionJob = new InvitationRetentionJob(
    SchedulableJobConfig(config.getConfig("invitation.retentionSchedulableJob")),
    transactor,
    new PostgresOAuthAuthoritativeTime,
    new InvitationRetentionRepository,
    config.getInt("invitation.retentionCleanupBatchSize")
  )

  abstract override protected def schedulableJobs: List[SchedulableJob] = invitationRetentionJob :: super.schedulableJobs
}
