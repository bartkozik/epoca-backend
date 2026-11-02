package pl.iterators.epoca.mail.jobs

import cats.effect.IO
import pl.iterators.epoca.mail.services.SendgridMailSyncService
import pl.iterators.epoca.utils.job
import pl.iterators.epoca.utils.job.SchedulableJob
import pl.iterators.epoca.utils.job.SchedulableJob.ScheduleJobResult
import pl.iterators.epoca.utils.log.LoggingSupport

class SendgridMailSyncScheduledJob(override val config: job.SchedulableJobConfig, sendgridMailSyncService: SendgridMailSyncService)
    extends SchedulableJob
    with LoggingSupport {
  override def apply: IO[ScheduleJobResult] = sendgridMailSyncService.synchronize().map(_ => ScheduleJobResult.Done)
}
