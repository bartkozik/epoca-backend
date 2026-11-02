package pl.iterators.epoca.user.routers

import org.apache.pekko.http.scaladsl.model.StatusCodes._
import pl.iterators.baklava.core.model.{RouteRepresentation, RouteSecurity}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.user.domain.UserIdentityDto
import pl.iterators.epoca.user.services.UserIdentityListService
import pl.iterators.epoca.user.services.UserIdentityListService.UserIdentityListResult
import pl.iterators.epoca.utils.{AuthContextProvider, RouteDocSpec}

class GetUsersIdentitiesRouteSpec extends RouteDocSpec {

  override val routeRepresentation: RouteRepresentation[Unit, Seq[UserIdentityDto]] =
    RouteRepresentation[Unit, Seq[UserIdentityDto]]("List user identities", "GET", "/users/identities", authentication = List(RouteSecurity.Bearer()))

  val routePath = routeRepresentation.path

  trait NoLoggedTestCase extends BaseScope {
    override val authContextForTest: Option[AuthContext] = None
  }

  trait LoggedTestCase extends BaseScope {
    override val authContextForTest: Option[AuthContext] = Some(AuthContextProvider.user)

    override lazy val userIdentityListService: UserIdentityListService = vmock[UserIdentityListService]
  }

  s"GET $routePath" should {
    "should be inaccessible for no logged users" in new NoLoggedTestCase {
      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldBeInaccessible
      }
    }
    "returns OK with proper json when service returns OK" in new LoggedTestCase {
      val responseObj = routeRepresentation.response.normal

      when(userIdentityListService.list(authContextForTest.get))
        .thenReturn(io(UserIdentityListResult.Ok(responseObj.value)))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual OK
        responseAs[Json] shouldEqualJson responseObj.getJsonString

        verify(userIdentityListService).list(authContextForTest.get)
      }
    }
  }
}
