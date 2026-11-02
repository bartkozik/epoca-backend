package pl.iterators.epoca.utils.job

import cats.effect.IO
import cats.implicits._
import pl.iterators.epoca.utils.log.LoggingSupport

trait CronJobScheduler {
  def schedule(cronJob: CronJob): IO[Unit]
}

trait CronJobProvider {
  def cronJobs: List[CronJob]

}

trait ApplicationCronJobProvider extends CronJobProvider with LoggingSupport {
  override def cronJobs: List[CronJob] = Nil

  def setupCronJobs(cronJobScheduler: CronJobScheduler): IO[List[Unit]] =
    Logger[IO].info("Setting up cron jobs. That's my job") *>
      cronJobs.traverse { job =>
        if (job.config.enabled)
          Logger[IO].info(s"Setting up cron job ${job.jobName}") *>
            cronJobScheduler.schedule(job)
        else
          Logger[IO].info(s"Setting up cron job is disabled for ${job.jobName}")
      }
}
