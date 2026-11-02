package pl.iterators.epoca.utils.job

import cats.effect.IO

trait StartupJob {
  def apply: IO[Unit]
  def isFailureFatal: Boolean

  final lazy val name = getClass.getName
}
