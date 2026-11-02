package pl.iterators.epoca.user.routers

import org.apache.pekko.http.scaladsl.model.StatusCodes._
import pl.iterators.baklava.core.model.{RouteRepresentation, RouteSecurity}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.user.domain.UserDomain.UserIdentityId
import pl.iterators.epoca.user.domain.UserIdentityDto
import pl.iterators.epoca.user.services.UserIdentitySetMainService
import pl.iterators.epoca.user.services.UserIdentitySetMainService.UserIdentitySetMainResult
import pl.iterators.epoca.utils.{AuthContextProvider, RouteDocSpec}

import java.util.UUID

class PostUsersIdentitiesIdentityIdMainRouteSpec extends RouteDocSpec {
  override val routeRepresentation: RouteRepresentation[Unit, UserIdentityDto] =
    RouteRepresentation[Unit, UserIdentityDto](
      "Update main identity",
      "POST",
      "/users/identities/{identityId}/main",
      authentication = List(RouteSecurity.Bearer())
    )

  val userIdentityId: UserIdentityId = UserIdentityId(UUID.randomUUID())
  val routePath: String              = routeRepresentation.path.replace("{identityId}", userIdentityId.toString)

  trait NoLoggedTestCase extends BaseScope {
    override val authContextForTest: Option[AuthContext] = None

  }

  trait LoggedTestCase extends BaseScope {
    override lazy val userIdentitySetMainService: UserIdentitySetMainService = vmock[UserIdentitySetMainService]
    override val authContextForTest: Option[AuthContext]                     = Some(AuthContextProvider.user)
  }

  routeRepresentation.name should {
    "should be inaccessible for no logged users" in new NoLoggedTestCase {
      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldBeInaccessible
      }
    }

    "returns NotFound when service returns IdentityNotFound" in new LoggedTestCase {
      when(userIdentitySetMainService.setMain(authContextForTest.get, userIdentityId))
        .thenReturn(io(UserIdentitySetMainResult.IdentityNotFound))

      TestRequest(routePath) ~> allRoutes ~> check {
        response.status shouldEqual NotFound

        verify(userIdentitySetMainService).setMain(authContextForTest.get, userIdentityId)
      }
    }

    "returns NoContent when service returns AlreadyMain" in new LoggedTestCase {
      when(userIdentitySetMainService.setMain(authContextForTest.get, userIdentityId))
        .thenReturn(io(UserIdentitySetMainResult.AlreadyMain))

      TestRequest(routePath) ~> allRoutes ~> check {
        response.status shouldEqual NoContent

        verify(userIdentitySetMainService).setMain(authContextForTest.get, userIdentityId)
      }
    }

    "returns OK when service returns Ok" in new LoggedTestCase {
      val responseObj = routeRepresentation.response.normal

      when(userIdentitySetMainService.setMain(authContextForTest.get, userIdentityId))
        .thenReturn(io(UserIdentitySetMainResult.Ok(responseObj.value)))

      TestRequest(routePath) ~> allRoutes ~> check {
        response.status shouldEqual OK
        responseAs[Json] shouldEqualJson responseObj.getJsonString

        verify(userIdentitySetMainService).setMain(authContextForTest.get, userIdentityId)
      }
    }
  }
}
