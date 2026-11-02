package pl.iterators.epoca.healthcheck.repositories

import pl.iterators.epoca.healthcheck.repositories.HealthRepository.Config
import pl.iterators.epoca.utils.db.BaseRepository.driver.api._
import slick.dbio.DBIO

import scala.concurrent.ExecutionContext

class HealthRepositoryImpl(config: Config)(implicit e: ExecutionContext) extends HealthRepository {
  override def check(): DBIO[Unit] =
    sql"#${config.query}"
      .as[Long]
      .withStatementParameters(statementInit = _.setQueryTimeout(config.timeout.toSeconds.intValue))
      .map(_ => ())
}
