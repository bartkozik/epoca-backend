package pl.iterators.epoca.utils.job

import cats.effect.IO

trait CronJob {
  def config: CronJobConfig

  def execute: IO[Unit]

  final lazy val jobName = getClass.getName

}
