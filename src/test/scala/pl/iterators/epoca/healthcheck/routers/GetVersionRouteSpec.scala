package pl.iterators.epoca.healthcheck.routers

import org.apache.pekko.http.scaladsl.model.StatusCodes._
import pl.iterators.baklava.core.model.RouteRepresentation
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.utils.RouteDocSpec

class GetVersionRouteSpec extends RouteDocSpec {

  override val routeRepresentation: RouteRepresentation[Unit, HealthCheckRouter.Config] =
    RouteRepresentation[Unit, HealthCheckRouter.Config]("Returns version and other env variables", "GET", "/version")

  trait NoLoggedTestCase extends BaseScope {
    override lazy val authContextForTest: Option[AuthContext] = None

    val responseObj = routeRepresentation.response.random

    override lazy val healthCheckRouterConfig = responseObj.value
  }

  val routePath = routeRepresentation.path

  routeRepresentation.name should {
    "return Ok and version from config" in new NoLoggedTestCase {
      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual OK
        responseAs[Json] shouldEqualJson responseObj.getJsonString
      }
    }
  }
}
