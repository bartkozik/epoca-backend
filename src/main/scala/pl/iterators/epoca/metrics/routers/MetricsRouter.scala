package pl.iterators.epoca.metrics.routers

import cats.effect.unsafe.IORuntime
import org.apache.pekko.http.scaladsl.model.StatusCodes.OK
import org.apache.pekko.http.scaladsl.server.Route
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.metrics.services.MetricsFrontendErrorService
import pl.iterators.epoca.metrics.services.MetricsFrontendErrorService.{MetricsFrontendErrorRequest, MetricsFrontendErrorResponse}
import pl.iterators.epoca.utils.http.BaseRouter
import pl.iterators.epoca.utils.log.TracingContext

class MetricsRouter(metricsFrontendErrorService: MetricsFrontendErrorService)(implicit runtime: IORuntime) extends BaseRouter {
  import MetricsRouter._
  def routes(authContext: AuthContext)(implicit tracingContext: TracingContext): Route =
    pathPrefix(metricsPath / frontendPath / errorPath) {
      (post & pathEndOrSingleSlash & entity(as[MetricsFrontendErrorRequest])) { request =>
        complete(metricsFrontendErrorService.passToLogger(authContext, request)) { case MetricsFrontendErrorResponse.Ok =>
          OK
        }
      }
    }

}

object MetricsRouter {
  private val metricsPath  = "metrics"
  private val frontendPath = "frontend"
  private val errorPath    = "error"
}
