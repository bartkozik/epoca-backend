package pl.iterators.epoca.workunit.services

import cats.effect.unsafe.IORuntime
import cats.effect.{Clock, IO}
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.workunit.repositories.WorkUnitIdempotencyRepository
import slick.dbio.DBIO

import java.time.Instant
import scala.concurrent.ExecutionContext

class WorkUnitIdempotencyRedactService(
  transactor: PostgresIOTransactor,
  repository: WorkUnitIdempotencyRepository
)(implicit
  runtime: IORuntime,
  clock: Clock[DBIO]) {
  private implicit val ec: ExecutionContext = runtime.compute

  def redact(): IO[Int] =
    transactor.execute {
      clock.realTime.flatMap { now =>
        repository.redactExpired(Instant.ofEpochMilli(now.toMillis))
      }
    }
}
