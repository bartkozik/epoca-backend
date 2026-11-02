package pl.iterators.epoca.auth.routers

import org.apache.pekko.http.scaladsl.model.StatusCodes.OK
import pl.iterators.baklava.core.model.{RouteRepresentation, RouteSecurity}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.auth.domain.AuthDomain._
import pl.iterators.epoca.auth.services.RefreshTokenDeleteService
import pl.iterators.epoca.auth.services.RefreshTokenDeleteService.{RefreshTokenDeleteRequest, RefreshTokenDeleteResult}
import pl.iterators.epoca.utils.RouteDocSpec

import java.util.UUID

class DeleteAuthRefreshTokensRouteSpec extends RouteDocSpec {

  override val routeRepresentation: RouteRepresentation[Unit, Unit] =
    RouteRepresentation[Unit, Unit](
      "Delete request tokens for device",
      "DELETE",
      "/auth/refresh-tokens/{deviceId}",
      authentication = List(RouteSecurity.Bearer())
    )

  val deviceId: DeviceId = DeviceId(UUID.randomUUID.toString)
  val routePath: String  = routeRepresentation.routePathWithAllParameters.replace("{deviceId}", deviceId)

  trait NoLoggedTestCase extends BaseScope {
    override val authContextForTest: Option[AuthContext] = None
  }

  trait LoggedTestCase extends BaseScope {
    override val authContextForTest: Option[AuthContext] = Some(allGenerators[AuthContext].normal.generate)

    override lazy val refreshTokenDeleteService: RefreshTokenDeleteService = mock[RefreshTokenDeleteService]
  }

  routeRepresentation.name should {
    "should be inaccessible for no logged users" in new NoLoggedTestCase {
      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldBeInaccessible
      }
    }
    "returns OK when service returns Ok" in new LoggedTestCase {
      when(refreshTokenDeleteService.delete(authContextForTest.get, RefreshTokenDeleteRequest(deviceId)))
        .thenReturn(io(RefreshTokenDeleteResult.Ok))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual OK

        verify(refreshTokenDeleteService).delete(authContextForTest.get, RefreshTokenDeleteRequest(deviceId))
      }
    }
  }
}
