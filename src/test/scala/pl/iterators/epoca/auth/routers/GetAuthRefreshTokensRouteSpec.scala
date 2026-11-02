package pl.iterators.epoca.auth.routers

import org.apache.pekko.http.scaladsl.model.StatusCodes.OK
import pl.iterators.baklava.core.model.{RouteRepresentation, RouteSecurity}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.auth.services.RefreshTokenListService
import pl.iterators.epoca.auth.services.RefreshTokenListService.{RefreshTokenDto, RefreshTokenListResult}
import pl.iterators.epoca.utils.{AuthContextProvider, RouteDocSpec}

class GetAuthRefreshTokensRouteSpec extends RouteDocSpec {

  override val routeRepresentation: RouteRepresentation[Unit, Seq[RefreshTokenDto]] =
    RouteRepresentation[Unit, Seq[RefreshTokenDto]](
      "Get request tokens for all user devices",
      "GET",
      "/auth/refresh-tokens",
      authentication = List(RouteSecurity.Bearer())
    )

  val routePath = routeRepresentation.routePathWithAllParameters

  trait NoLoggedTestCase extends BaseScope {
    override val authContextForTest: Option[AuthContext] = None
  }

  trait LoggedTestCase extends BaseScope {
    override val authContextForTest: Option[AuthContext] = Some(AuthContextProvider.user)

    override lazy val refreshTokenListService: RefreshTokenListService = mock[RefreshTokenListService]
  }

  routeRepresentation.name should {
    "should be inaccessible for no logged users" in new NoLoggedTestCase {
      TestRequest(routePath) ~> allRoutes ~> check {
        response.status shouldBeInaccessible
      }
    }
    "returns Ok when service returns Ok" in new LoggedTestCase {
      val responseObject = routeRepresentation.response.random

      when(refreshTokenListService.list(authContextForTest.get))
        .thenReturn(io(RefreshTokenListResult.Ok(responseObject.value)))

      TestRequest(routePath, emptyJson) ~> allRoutes ~> check {
        response.status shouldEqual OK
        responseAs[Json] shouldEqualJson responseObject.getJsonString

        verify(refreshTokenListService).list(authContextForTest.get)
      }
    }
  }
}
