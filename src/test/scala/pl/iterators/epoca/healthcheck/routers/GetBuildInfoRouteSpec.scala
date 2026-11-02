package pl.iterators.epoca.healthcheck.routers

import org.apache.pekko.http.scaladsl.model.StatusCodes
import pl.iterators.baklava.core.model.RouteRepresentation
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.healthcheck.routers.HealthCheckRouter.BuildInfo
import pl.iterators.epoca.utils.{OpenApiContractPostProcessor, RouteDocSpec}

class GetBuildInfoRouteSpec extends RouteDocSpec {
  private val responseObject = BuildInfo(OpenApiContractPostProcessor.CommitSha)
  override val routeRepresentation: RouteRepresentation[Unit, BuildInfo] =
    RouteRepresentation[Unit, BuildInfo]("Return public build commit metadata.", "GET", "/build-info", responsePredefinedValue = Some(responseObject))

  trait NoLoggedTestCase extends BaseScope {
    override lazy val authContextForTest: Option[AuthContext] = None
    override lazy val healthCheckRouterConfig: HealthCheckRouter.Config =
      HealthCheckRouter.Config(name = "epoca", environment = "test", version = responseObject.commitSha)
  }

  routeRepresentation.name should {
    "return 200 publicly with the exact configured commit SHA" in new NoLoggedTestCase {
      TestRequest(routeRepresentation.path, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.OK
        responseAs[Json] shouldEqualJson routeRepresentation.response.normal.getJsonString
        responseAs[Json].asObject.map(_.keys.toSet) shouldEqual Some(Set("commitSha"))
      }
    }
  }
}
