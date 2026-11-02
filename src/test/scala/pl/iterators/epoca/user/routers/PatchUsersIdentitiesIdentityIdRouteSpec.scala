package pl.iterators.epoca.user.routers

import org.apache.pekko.http.scaladsl.model.StatusCodes._
import pl.iterators.baklava.core.model.{RouteRepresentation, RouteSecurity}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.user.domain.UserDomain.UserIdentityId
import pl.iterators.epoca.user.domain.UserIdentityDto
import pl.iterators.epoca.user.services.UserIdentityConfirmService
import pl.iterators.epoca.user.services.UserIdentityConfirmService.{UserIdentityConfirmRequest, UserIdentityConfirmResult}
import pl.iterators.epoca.utils.{AuthContextProvider, RouteDocSpec}

import java.util.UUID

class PatchUsersIdentitiesIdentityIdRouteSpec extends RouteDocSpec {
  override val routeRepresentation: RouteRepresentation[UserIdentityConfirmRequest, UserIdentityDto] =
    RouteRepresentation[UserIdentityConfirmRequest, UserIdentityDto](
      "Confirm user identity",
      "PATCH",
      "/users/identities/{identityId}",
      authentication = List(RouteSecurity.Bearer())
    )

  val userIdentityId: UserIdentityId = UserIdentityId(UUID.randomUUID())
  val routePath: String              = routeRepresentation.path.replace("{identityId}", userIdentityId.toString)

  trait NoLoggedTestCase extends BaseScope {
    override val authContextForTest: Option[AuthContext] = None

  }

  trait LoggedTestCase extends BaseScope {
    override lazy val userIdentityConfirmService: UserIdentityConfirmService = vmock[UserIdentityConfirmService]
    override val authContextForTest: Option[AuthContext]                     = Some(AuthContextProvider.user)
  }

  routeRepresentation.name should {
    "should be inaccessible for no logged users" in new NoLoggedTestCase {
      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldBeInaccessible
      }
    }

    "returns Conflict when service returns InvalidCode" in new LoggedTestCase {
      val requestObj = routeRepresentation.request.normal

      when(userIdentityConfirmService.confirm(authContextForTest.get, userIdentityId, requestObj.value))
        .thenReturn(io(UserIdentityConfirmResult.InvalidCode))

      TestRequest(routePath, requestJson(requestObj.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual Conflict

        verify(userIdentityConfirmService).confirm(authContextForTest.get, userIdentityId, requestObj.value)
      }
    }

    "returns NotFound when service returns IdentityNotFound" in new LoggedTestCase {
      val requestObj = routeRepresentation.request.normal

      when(userIdentityConfirmService.confirm(authContextForTest.get, userIdentityId, requestObj.value))
        .thenReturn(io(UserIdentityConfirmResult.IdentityNotFound))

      TestRequest(routePath, requestJson(requestObj.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual NotFound

        verify(userIdentityConfirmService).confirm(authContextForTest.get, userIdentityId, requestObj.value)
      }
    }

    "returns OK when service returns Ok" in new LoggedTestCase {
      val requestObj  = routeRepresentation.request.normal
      val responseObj = routeRepresentation.response.normal

      when(userIdentityConfirmService.confirm(authContextForTest.get, userIdentityId, requestObj.value))
        .thenReturn(io(UserIdentityConfirmResult.Ok(responseObj.value)))

      TestRequest(routePath, requestJson(requestObj.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual OK
        responseAs[Json] shouldEqualJson responseObj.getJsonString

        verify(userIdentityConfirmService).confirm(authContextForTest.get, userIdentityId, requestObj.value)
      }
    }
  }
}
