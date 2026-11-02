package pl.iterators.epoca.utils.quartz

import cats.effect.IO
import org.quartz._
import org.quartz.impl.JobDetailImpl
import pl.iterators.epoca.utils.job.{CronJob, CronJobScheduler}
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.quartz.QuartzProvider.{QuartzRegistrableJob, QuartzScheduleData}

class QuartzCronJobScheduler(quartzProvider: QuartzProvider) extends CronJobScheduler with LoggingSupport {

  override def schedule(cronJob: CronJob): IO[Unit] =
    for {
      _ <- Logger[IO].info(s"Scheduling cron job ${cronJob.jobName}")
      quartzJob = buildQuartzRegistrableJob(cronJob)
      _ <- quartzProvider.register(quartzJob)
      scheduleData = buildQuartzScheduleData(cronJob, quartzJob)
      _ <- quartzProvider.schedule(scheduleData)
    } yield ()

  private def buildQuartzRegistrableJob(cronJob: CronJob): QuartzRegistrableJob = {
    val job = new Job {
      override def execute(context: JobExecutionContext): Unit =
        cronJob.execute.unsafeRunAndForget()(quartzProvider.ioRuntime)
    }
    QuartzRegistrableJob(cronJob.jobName, job)
  }

  private def buildQuartzScheduleData(cronJob: CronJob, quartzJob: QuartzRegistrableJob): QuartzScheduleData = {

    val jobKey = new JobKey(s"cron-job-execution-${cronJob.config.expression}", quartzJob.jobClassName)

    val jobDetail: JobDetail = {
      val job = new JobDetailImpl

      job.setDescription(cronJob.jobName)
      job.setKey(jobKey)
      job.setJobClass(quartzJob.job.getClass)
      job.setDurability(true)
      job.setRequestsRecovery(true)

      job
    }

    val trigger = TriggerBuilder
      .newTrigger()
      .withIdentity(jobDetail.getKey.toString)
      .withSchedule(CronScheduleBuilder.cronSchedule(cronJob.config.expression))
      .build

    QuartzScheduleData(jobDetail, trigger)
  }
}
