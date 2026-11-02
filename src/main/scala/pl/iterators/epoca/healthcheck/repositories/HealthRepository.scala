package pl.iterators.epoca.healthcheck.repositories

import slick.dbio.DBIO

import scala.concurrent.duration.FiniteDuration

trait HealthRepository {
  def check(): DBIO[Unit]
}

object HealthRepository {
  final case class Config(query: String, timeout: FiniteDuration)
}
