package pl.iterators.epoca.auth.jobs

import cats.effect.IO
import pl.iterators.epoca.auth.services.LoginTokenPurgeExpiredService
import pl.iterators.epoca.utils.job
import pl.iterators.epoca.utils.job.SchedulableJob
import pl.iterators.epoca.utils.job.SchedulableJob.ScheduleJobResult

class LoginTokenPurgeExpiredJob(override val config: job.SchedulableJobConfig, loginTokenPurgeExpiredService: LoginTokenPurgeExpiredService)
    extends SchedulableJob {

  override def apply: IO[ScheduleJobResult] = loginTokenPurgeExpiredService.purge().map(_ => ScheduleJobResult.Done)
}
