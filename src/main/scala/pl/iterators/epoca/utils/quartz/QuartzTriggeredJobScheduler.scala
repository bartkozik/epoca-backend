package pl.iterators.epoca.utils.quartz

import cats.effect.IO
import cats.implicits._
import org.quartz.impl.JobDetailImpl
import org.quartz.{JobDataMap, JobDetail, JobKey, TriggerBuilder}
import pl.iterators.epoca.utils.job.{TriggeredJob, TriggeredJobScheduler}
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.quartz.QuartzProvider.QuartzScheduleData

import java.time.{LocalDateTime, ZoneId}
import java.util.{Date, UUID}
import scala.jdk.CollectionConverters._

class QuartzTriggeredJobScheduler(quartzProvider: QuartzProvider) extends TriggeredJobScheduler with LoggingSupport {
  override def schedule[T](
    job: TriggeredJob[T],
    dates: List[LocalDateTime],
    data: T
  ): IO[Unit] =
    buildQuartzScheduleData(job.jobName, dates, job.codec.to(data)).traverse { jobData =>
      quartzProvider.schedule(jobData)
    }.void

  private def buildQuartzScheduleData(
    jobName: String,
    dates: List[LocalDateTime],
    data: Map[String, Any]
  ): List[QuartzScheduleData] = {
    val executionUuid = UUID.randomUUID()
    dates.zipWithIndex.map { case (date, idx) =>
      val jobKey = new JobKey(s"${executionUuid.toString}-$idx", jobName)

      val jobDetail: JobDetail = {
        val job = new JobDetailImpl

        job.setDescription(jobName)
        job.setKey(jobKey)
        job.setJobClass(quartzProvider.getRegisteredJob(jobName).getOrElse(sys.error(s"Unable to find job class with name ${jobName}")).getClass)
        job.setJobDataMap(new JobDataMap(data.asJava))
        job.setDurability(true)
        job.setRequestsRecovery(true)

        job
      }

      val trigger = TriggerBuilder
        .newTrigger()
        .startAt(Date.from(date.atZone(ZoneId.systemDefault()).toInstant))
        .withIdentity(jobDetail.getKey.toString)
        .build

      QuartzScheduleData(jobDetail, trigger)
    }
  }

}
