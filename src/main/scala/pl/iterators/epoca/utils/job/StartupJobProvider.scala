package pl.iterators.epoca.utils.job

import cats.effect.IO
import cats.syntax.all._
import pl.iterators.epoca.utils.log.LoggingSupport

trait StartupJobProvider {
  protected def startupJobs: List[StartupJob]
}

trait ApplicationStartupJobProvider extends StartupJobProvider with LoggingSupport {
  override protected def startupJobs: List[StartupJob] = Nil

  def startupJobsSequence: IO[List[Unit]] =
    startupJobs.map { job =>
      Logger[IO].info(s"Executing startup job ${job.name}") *>
        (if (job.isFailureFatal) {
           job.apply.onError(t => Logger[IO].error(t)(s"Failure-fatal startup job ${job.name} failed"))
         } else {
           job.apply.onError(t => Logger[IO].error(t)(s"Non-failure-fatal startup job ${job.name} failed")).recover(_ => ())
         })
    }.parSequence
}
