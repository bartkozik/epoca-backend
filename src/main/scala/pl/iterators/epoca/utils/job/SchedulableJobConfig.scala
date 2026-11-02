package pl.iterators.epoca.utils.job

import com.typesafe.config.Config

import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.Try

case class SchedulableJobConfig(
  enabled: Boolean,
  workers: Int,
  scheduleAtFixedRate: Boolean,
  initialDelay: FiniteDuration,
  tickTime: FiniteDuration,
  tickTimeAfterNoWork: Option[FiniteDuration],
  timeout: Option[FiniteDuration])

object SchedulableJobConfig {
  def apply(config: Config): SchedulableJobConfig =
    SchedulableJobConfig(
      enabled = config.getBoolean("enabled"),
      workers = Try(config.getInt("workers")).toOption.getOrElse(1),
      scheduleAtFixedRate = Try(config.getBoolean("scheduleAtFixedRate")).toOption.getOrElse(false),
      initialDelay = Duration.fromNanos(config.getDuration("initialDelay").toNanos),
      tickTime = Duration.fromNanos(config.getDuration("tickTime").toNanos),
      tickTimeAfterNoWork = Try(Duration.fromNanos(config.getDuration("tickTimeAfterNoWork").toNanos)).toOption,
      timeout = Try(Duration.fromNanos(config.getDuration("timeout").toNanos)).toOption
    )
}
