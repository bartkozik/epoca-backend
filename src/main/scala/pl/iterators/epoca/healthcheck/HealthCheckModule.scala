package pl.iterators.epoca.healthcheck

import cats.effect.unsafe.IORuntime
import com.softwaremill.macwire._
import com.typesafe.config.Config
import org.apache.pekko.http.scaladsl.server.Route
import pl.iterators.epoca.healthcheck.repositories.{HealthRepository, HealthRepositoryImpl}
import pl.iterators.epoca.healthcheck.routers.HealthCheckRouter
import pl.iterators.epoca.healthcheck.services.HealthService
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.http.RouteProvider
import pl.iterators.epoca.utils.http.directives.SecretKeyConfig
import pl.iterators.epoca.utils.log.TracingContext
import pureconfig._
import pureconfig.generic.auto._

import scala.concurrent.ExecutionContext

trait HealthCheckModule extends RouteProvider {
  val config: Config
  val transactor: PostgresIOTransactor
  protected val secretKeyConfig: SecretKeyConfig

  implicit val executor: ExecutionContext
  implicit val runtime: IORuntime

  lazy val healthRepositoryConfig: HealthRepository.Config =
    ConfigSource.fromConfig(config).at("db.testConnection").loadOrThrow[HealthRepository.Config]
  lazy val healthRepository: HealthRepository = wire[HealthRepositoryImpl]
  lazy val healthService: HealthService       = wire[HealthService]

  lazy val healthCheckRouterConfig: HealthCheckRouter.Config = ConfigSource.fromConfig(config).at("app").loadOrThrow[HealthCheckRouter.Config]
  lazy val healthCheckRouter: HealthCheckRouter              = wire[HealthCheckRouter]

  abstract override def route(implicit tracingContext: TracingContext): Route =
    super.route ~
      healthCheckRouter.routes
}
