package pl.iterators.epoca.workunit.jobs

import cats.effect.IO
import pl.iterators.epoca.utils.job
import pl.iterators.epoca.utils.job.SchedulableJob
import pl.iterators.epoca.utils.job.SchedulableJob.ScheduleJobResult
import pl.iterators.epoca.workunit.services.WorkUnitIdempotencyRedactService

class WorkUnitIdempotencyRedactJob(override val config: job.SchedulableJobConfig, redactService: WorkUnitIdempotencyRedactService)
    extends SchedulableJob {

  override def apply: IO[ScheduleJobResult] =
    redactService.redact().map {
      case count if count > 0 => ScheduleJobResult.Done
      case _                  => ScheduleJobResult.NoWork
    }
}
