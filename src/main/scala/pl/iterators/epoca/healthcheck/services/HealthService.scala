package pl.iterators.epoca.healthcheck.services

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import cats.implicits.catsSyntaxEitherId
import pl.iterators.epoca.healthcheck.repositories.HealthRepository
import pl.iterators.epoca.healthcheck.services.HealthService._
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.service.SealedMonadServiceIODBIO
import pl.iterators.sealedmonad.syntax.faSyntax

import scala.concurrent.duration.FiniteDuration

class HealthService(healthRepository: HealthRepository, transactor: PostgresIOTransactor)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIODBIO[HealthCheckResult](ioRuntime)
    with LoggingSupport {

  def getDbHealth: IO[HealthCheckResult] = (for {
    time <- checkConnection()
  } yield HealthCheckResult.Ok(ConnectionDetails(time.toMillis))).run

  private def checkConnection(): StepIO[FiniteDuration] =
    transactor.execute(healthRepository.check()).timed.attempt.attemptF {
      case Left(ex) =>
        Logger[IO]
          .error(ex)(s"Database healthcheck has failed")
          .as(HealthCheckResult.DatabaseUnavailable)
          .map(_.asLeft)
      case Right((time, _)) => IO.pure(Right(time))
    }
}

object HealthService {
  case class ConnectionDetails(latencyMillis: Long)

  sealed trait HealthCheckResult

  object HealthCheckResult {
    case class Ok(details: ConnectionDetails) extends HealthCheckResult
    case object DatabaseUnavailable           extends HealthCheckResult
  }

}
