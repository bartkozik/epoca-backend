package pl.iterators.epoca.metrics.routers

import org.apache.pekko.http.scaladsl.model.StatusCodes
import pl.iterators.baklava.core.model.{RouteRepresentation, RouteSecurity}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.metrics.services.MetricsFrontendErrorService
import pl.iterators.epoca.metrics.services.MetricsFrontendErrorService.{MetricsFrontendErrorRequest, MetricsFrontendErrorResponse}
import pl.iterators.epoca.utils.RouteDocSpec

class PostMetricsLogFrontendErrorRouteSpec extends RouteDocSpec {
  override val routeRepresentation: RouteRepresentation[MetricsFrontendErrorRequest, Unit] =
    RouteRepresentation[MetricsFrontendErrorRequest, Unit](
      "Logs error from frontend to backend logger.",
      "POST",
      "/metrics/frontend/error",
      authentication = List(RouteSecurity.Bearer())
    )
  val auth: AuthContext = allGenerators[AuthContext].normal.generate
  val routePath         = routeRepresentation.path

  trait NoLoggedTestCase extends BaseScope {
    override lazy val authContextForTest: Option[AuthContext] = None
  }

  trait LoggedTestCase extends BaseScope {
    override lazy val metricsFrontendErrorService: MetricsFrontendErrorService = vmock[MetricsFrontendErrorService]

    override val authContextForTest: Option[AuthContext] = Some(auth)
  }

  routeRepresentation.name should {
    "be inaccessible for no-logged user" in new NoLoggedTestCase {
      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldBeInaccessible
      }
    }

    "return BadRequest when incoming request is not json" in new LoggedTestCase {
      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.BadRequest
      }
    }

    "return Ok and log payload to logger" in new LoggedTestCase {
      val request = routeRepresentation.request.random

      when(metricsFrontendErrorService.passToLogger(auth, request.value))
        .thenReturn(io(MetricsFrontendErrorResponse.Ok))

      TestRequest(routePath, requestJson(request.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.OK
      }

      verify(metricsFrontendErrorService).passToLogger(auth, request.value)
    }
  }
}
