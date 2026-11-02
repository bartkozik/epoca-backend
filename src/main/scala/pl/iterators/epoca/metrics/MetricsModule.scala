package pl.iterators.epoca.metrics

import cats.effect.unsafe.IORuntime
import com.softwaremill.macwire.wire
import com.typesafe.config.Config
import org.apache.pekko.http.scaladsl.server.Route
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.metrics.routers.MetricsRouter
import pl.iterators.epoca.metrics.services.MetricsFrontendErrorService
import pl.iterators.epoca.metrics.services.MetricsFrontendErrorService.MetricsFrontendErrorConfig
import pl.iterators.epoca.utils.http.AuthRouteProvider
import pl.iterators.epoca.utils.log.TracingContext

trait MetricsModule extends AuthRouteProvider {
  val config: Config
  implicit val runtime: IORuntime

  val metricsFrontendErrorConfig: MetricsFrontendErrorConfig = MetricsFrontendErrorConfig(config)

  lazy val metricsFrontendErrorService: MetricsFrontendErrorService = wire[MetricsFrontendErrorService]

  lazy val metricsRouter: MetricsRouter                                                          = wire[MetricsRouter]
  abstract override def route(auth: AuthContext)(implicit tracingContext: TracingContext): Route = super.route(auth) ~ metricsRouter.routes(auth)
}
