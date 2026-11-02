package pl.iterators.epoca.utils.job

import com.typesafe.config.Config

case class CronJobConfig(enabled: Boolean, expression: String)

object CronJobConfig {
  def apply(config: Config): CronJobConfig =
    CronJobConfig(enabled = config.getBoolean("enabled"), expression = config.getString("expression"))
}
