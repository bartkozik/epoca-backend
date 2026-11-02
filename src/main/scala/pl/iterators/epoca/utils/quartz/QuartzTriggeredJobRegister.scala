package pl.iterators.epoca.utils.quartz

import cats.effect.IO
import org.quartz._
import pl.iterators.epoca.utils.job.{TriggeredJob, TriggeredJobRegister}
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.quartz.QuartzProvider.QuartzRegistrableJob

import scala.jdk.CollectionConverters._

class QuartzTriggeredJobRegister(quartzProvider: QuartzProvider) extends TriggeredJobRegister with LoggingSupport {

  override def register(triggeredJob: TriggeredJob[_]): IO[Unit] =
    Logger[IO].info(s"Registering triggered job class in quartz ${triggeredJob.jobName}") *>
      quartzProvider.register(buildQuartzRegistrableJob(triggeredJob))

  private def buildQuartzRegistrableJob(triggeredJob: TriggeredJob[_]): QuartzRegistrableJob = {
    val job = new Job {
      override def execute(context: JobExecutionContext): Unit =
        triggeredJob.doExecute(context.getMergedJobDataMap.getWrappedMap.asScala.toMap).unsafeRunAndForget()(quartzProvider.ioRuntime)
    }
    QuartzRegistrableJob(triggeredJob.jobName, job)
  }

}
