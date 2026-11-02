package pl.iterators.epoca.utils.job

import cats.effect.IO
import cats.effect.std.UUIDGen
import cats.syntax.all._
import pl.iterators.epoca.utils.job.SchedulableJob.ScheduleJobResult
import pl.iterators.epoca.utils.log.{LoggingSupport, TracingContext}

import scala.concurrent.duration.FiniteDuration

trait SchedulableJobProvider {
  protected def schedulableJobs: List[SchedulableJob]
}

trait ApplicationSchedulableJobProvider extends SchedulableJobProvider with LoggingSupport {
  override protected def schedulableJobs: List[SchedulableJob] = Nil

  def schedulableJobSequence: IO[List[Unit]] =
    Logger[IO].info("Scheduling what needs to be schedule. That's my job") *>
      schedulableJobs
        .flatMap(job => if (job.config.enabled) List.fill(job.config.workers)(job) else List.empty)
        .map(schedule)
        .parSequence

  private def scheduleAtFixedRate(
    apply: IO[ScheduleJobResult],
    name: String,
    initialDelay: FiniteDuration,
    tickTime: FiniteDuration,
    timeout: Option[FiniteDuration]
  ): IO[Unit] = Logger[IO].info(s"Scheduling $name at fixed rate, initialDelay: $initialDelay, tickTime: $tickTime, timeout: $timeout") *>
    IO.sleep(initialDelay) *>
    (for {
      cid    <- UUIDGen[IO].randomUUID
      _      <- TracingContext.set(TracingContext("context", cid.toString, Map("job" -> name)))
      _      <- Logger[IO].info(s"Starting job $name...")
      result <- withTimeout(apply, timeout).attempt
      _      <- logJobResult(name, result)
      _      <- IO.sleep(tickTime)
    } yield ()).foreverM

  private def scheduleWithVariableRate(
    apply: IO[ScheduleJobResult],
    name: String,
    initialDelay: FiniteDuration,
    tickTime: FiniteDuration,
    afterNoWork: FiniteDuration,
    timeout: Option[FiniteDuration]
  ): IO[Unit] = {
    def loop(lastResult: Option[Either[Throwable, ScheduleJobResult]]): IO[Unit] = IO.defer {
      val tick = if (lastResult.exists(_.contains(ScheduleJobResult.Done))) tickTime else afterNoWork
      for {
        cid        <- UUIDGen[IO].randomUUID
        _          <- TracingContext.set(TracingContext("context", cid.toString, Map("job" -> name)))
        _          <- Logger[IO].info(s"Will start job $name in $tick")
        _          <- IO.sleep(tick)
        nextResult <- withTimeout(apply, timeout).attempt
        _          <- logJobResult(name, nextResult)
        _          <- loop(Some(nextResult))
      } yield ()
    }

    Logger[IO].info(s"Scheduling $name at variable rate, initialDelay: $initialDelay, tickTime: $tickTime, $afterNoWork, timeout: $timeout") *> IO
      .sleep(initialDelay) *> loop(None)
  }

  private def schedule(job: SchedulableJob): IO[Unit] =
    if (job.config.scheduleAtFixedRate) {
      scheduleAtFixedRate(job.apply, job.name, job.config.initialDelay, job.config.tickTime, job.config.timeout)
    } else {
      job.config.tickTimeAfterNoWork match {
        case Some(afterNoWork) =>
          scheduleWithVariableRate(job.apply, job.name, job.config.initialDelay, job.config.tickTime, afterNoWork, job.config.timeout)
        case None => scheduleAtFixedRate(job.apply, job.name, job.config.initialDelay, job.config.tickTime, job.config.timeout)
      }
    }

  private def withTimeout(apply: IO[ScheduleJobResult], timeout: Option[FiniteDuration]): IO[ScheduleJobResult] =
    timeout match {
      case Some(t) => apply.timeoutTo(t, IO.pure(ScheduleJobResult.Timeout))
      case None    => apply
    }

  private def logJobResult(name: String, result: Either[Throwable, ScheduleJobResult]): IO[Unit] = result match {
    case Left(e)          => Logger[IO].error(e)(s"Job $name failed with throwable")
    case Right(jobResult) => Logger[IO].info(s"Job $name finished with $jobResult")
  }
}
