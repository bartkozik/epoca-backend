package pl.iterators.epoca.healthcheck.routers

import org.apache.pekko.http.scaladsl.model.StatusCodes._
import pl.iterators.baklava.core.model.RouteRepresentation
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.utils.RouteDocSpec

class GetHealthRouteSpec extends RouteDocSpec {
  override val routeRepresentation: RouteRepresentation[Unit, Unit] = RouteRepresentation[Unit, Unit]("Health check", "GET", "/health-check")

  trait NoLoggedTestCase extends BaseScope {
    override lazy val authContextForTest: Option[AuthContext] = None
  }

  val routePath = routeRepresentation.path

  routeRepresentation.name should {
    "return NoContent for no-logged user" in new NoLoggedTestCase {
      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual NoContent
      }
    }
  }
}
