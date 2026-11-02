package pl.iterators.epoca.utils.job

import cats.effect.IO

import java.time.LocalDateTime

trait TriggeredJobScheduler {
  def schedule[T](
    job: TriggeredJob[T],
    dates: List[LocalDateTime],
    data: T
  ): IO[Unit]
}
