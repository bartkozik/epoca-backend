package pl.iterators.epoca.utils.job

import cats.effect.IO

trait SchedulableJob {
  final val name = getClass.getName

  def apply: IO[SchedulableJob.ScheduleJobResult]

  def config: SchedulableJobConfig
}

object SchedulableJob {
  sealed trait ScheduleJobResult
  object ScheduleJobResult {
    case object Done    extends ScheduleJobResult
    case object NoWork  extends ScheduleJobResult
    case object Timeout extends ScheduleJobResult
  }
}
