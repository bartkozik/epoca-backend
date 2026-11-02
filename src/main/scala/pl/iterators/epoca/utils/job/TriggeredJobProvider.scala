package pl.iterators.epoca.utils.job

import cats.effect.IO
import cats.implicits._
import pl.iterators.epoca.utils.log.LoggingSupport

trait TriggeredJobRegister {
  def register(triggeredJob: TriggeredJob[_]): IO[Unit]
}

trait TriggeredJobProvider {
  def triggeredJobs: List[TriggeredJob[_]]

}

trait ApplicationTriggeredJobProvider extends TriggeredJobProvider with LoggingSupport {
  override def triggeredJobs: List[TriggeredJob[_]] = Nil

  def setupTriggeredJobs(triggeredJobRegister: TriggeredJobRegister): IO[List[Unit]] =
    Logger[IO].info("Setting up triggered jobs. That's my job") *>
      triggeredJobs.traverse { job =>
        Logger[IO].info(s"Setting up cron job ${job.jobName}") *>
          triggeredJobRegister.register(job)
      }
}
