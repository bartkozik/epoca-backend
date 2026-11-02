package pl.iterators.epoca.user.routers

import org.apache.pekko.http.scaladsl.model.StatusCodes._
import pl.iterators.baklava.core.model.{RouteRepresentation, RouteSecurity}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.user.services.UserUpdateService
import pl.iterators.epoca.user.services.UserUpdateService._
import pl.iterators.epoca.utils.RouteDocSpec

import java.util.UUID

class PatchUsersUserIdRouteSpec extends RouteDocSpec {

  override val routeRepresentation: RouteRepresentation[UserUpdateRequest, User] =
    RouteRepresentation[UserUpdateRequest, User]("Update user", "PATCH", "/users/{userId}", authentication = List(RouteSecurity.Bearer()))

  val userId: UserId    = UserId(UUID.randomUUID())
  val routePath: String = routeRepresentation.path.replace("{userId}", userId.toString)

  trait NoLoggedTestCase extends BaseScope {
    override val authContextForTest: Option[AuthContext] = None
  }

  trait LoggedTestCase extends BaseScope {
    override lazy val userUpdateService: UserUpdateService = vmock[UserUpdateService]
    override val authContextForTest: Option[AuthContext]   = Some(allGenerators[AuthContext].normal.generate)
  }

  routeRepresentation.name should {
    "should be inaccessible for no logged users" in new NoLoggedTestCase {
      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldBeInaccessible
      }
    }
    "returns Forbidden for logged user if service returns OperationNotPermitted" in new LoggedTestCase {
      val requestObj = routeRepresentation.request.normal

      when(userUpdateService.update(authContextForTest.get, userId, requestObj.value))
        .thenReturn(io(UserUpdateResult.OperationNotPermitted))

      TestRequest(routePath, requestJson(requestObj.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual Forbidden

        verify(userUpdateService).update(authContextForTest.get, userId, requestObj.value)
      }
    }
    "returns NotFound for logged user if service returns UserDoNotExist" in new LoggedTestCase {
      val requestObj = routeRepresentation.request.normal

      when(userUpdateService.update(authContextForTest.get, userId, requestObj.value))
        .thenReturn(io(UserUpdateResult.UserDoNotExist))

      TestRequest(routePath, requestJson(requestObj.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual NotFound

        verify(userUpdateService).update(authContextForTest.get, userId, requestObj.value)
      }
    }
    "returns OK for logged user if service returns Ok" in new LoggedTestCase {
      val requestObj  = routeRepresentation.request.normal
      val responseObj = routeRepresentation.response.normal

      when(userUpdateService.update(authContextForTest.get, userId, requestObj.value))
        .thenReturn(io(UserUpdateResult.Ok(responseObj.value)))

      TestRequest(routePath, requestJson(requestObj.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual OK
        responseAs[Json] shouldEqualJson responseObj.getJsonString

        verify(userUpdateService).update(authContextForTest.get, userId, requestObj.value)
      }
    }
  }
}
