package pl.iterators.epoca.healthcheck.routers

import cats.effect.unsafe.IORuntime
import io.circe.Json
import org.apache.pekko.http.scaladsl.model.StatusCodes._
import org.apache.pekko.http.scaladsl.server.Route
import pl.iterators.epoca.healthcheck.routers.HealthCheckRouter._
import pl.iterators.epoca.healthcheck.services.HealthService
import pl.iterators.epoca.healthcheck.services.HealthService.HealthCheckResult
import pl.iterators.epoca.utils.http.BaseRouter
import pl.iterators.epoca.utils.http.directives.{SecretKeyConfig, SecretKeyDirective}
import pl.iterators.epoca.utils.log.TracingContext

class HealthCheckRouter(
  config: HealthCheckRouter.Config,
  healthService: HealthService,
  secretKeyConfig: SecretKeyConfig
)(implicit runtime: IORuntime)
    extends BaseRouter {

  def routes(implicit tracingContext: TracingContext): Route =
    pathPrefix(healthPath) {
      (get & pathEndOrSingleSlash) {
        complete {
          NoContent
        }
      } ~ SecretKeyDirective.secretKeyProtection(secretKeyConfig) { _ =>
        (get & path(dbHealthPath) & pathEndOrSingleSlash) {
          complete(healthService.getDbHealth) {
            case HealthCheckResult.Ok(details) =>
              OK -> details
            case r @ HealthCheckResult.DatabaseUnavailable =>
              error(ServiceUnavailable, typeFromResult[r.type], "Database unavailable")
          }
        }
      }
    } ~ (get & path(versionPath) & pathEndOrSingleSlash) {
      complete {
        OK -> config
      }
    } ~ (get & path(buildInfoPath) & pathEndOrSingleSlash) {
      complete {
        OK -> BuildInfo(config.version)
      }
    } ~ (post & path(echoPath) & pathEndOrSingleSlash & entity(as[Json])) { json =>
      complete {
        OK -> json
      }
    }
}

object HealthCheckRouter {
  final case class BuildInfo(commitSha: String)

  final case class Config(
    name: String,
    environment: String,
    version: String)
  val healthPath    = "health-check"
  val dbHealthPath  = "db"
  val versionPath   = "version"
  val buildInfoPath = "build-info"
  val echoPath      = "echo"
}
