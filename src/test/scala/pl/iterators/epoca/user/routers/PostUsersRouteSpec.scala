package pl.iterators.epoca.user.routers

import org.apache.pekko.http.scaladsl.model.StatusCodes.{BadRequest, Conflict, Forbidden, OK}
import pl.iterators.baklava.core.model.RouteRepresentation
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.user.services.UserCreateService
import pl.iterators.epoca.user.services.UserCreateService.{UserCreateRequest, UserCreateResult, UserCreateResultData}
import pl.iterators.epoca.utils.RouteDocSpec

class PostUsersRouteSpec extends RouteDocSpec {
  override val routeRepresentation: RouteRepresentation[UserCreateRequest, UserCreateResultData] =
    RouteRepresentation[UserCreateRequest, UserCreateResultData]("Create user and send him login token if no password is provided", "POST", "/users")

  val routePath = routeRepresentation.path

  trait NoLoggedTestCase extends BaseScope {
    override val authContextForTest: Option[AuthContext] = None

    override lazy val userCreateService: UserCreateService = vmock[UserCreateService]
  }

  routeRepresentation.name should {
    "should be inaccessible for no logged users" in new NoLoggedTestCase {
      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldBeInaccessible
      }
    }
    "returns Conflict for nonlogged user if service returns EmailAlreadyExist" in new NoLoggedTestCase {
      val requestObj = routeRepresentation.request.normal

      when(userCreateService.create(requestObj.value))
        .thenReturn(io(UserCreateResult.IdentityAlreadyExist))

      TestRequest(routePath, requestJson(requestObj.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual Conflict

        verify(userCreateService).create(requestObj.value)
      }
    }
    "returns BadRequest for nonlogged user if service returns EmailAlreadyExist" in new NoLoggedTestCase {
      val requestObj = routeRepresentation.request.normal

      when(userCreateService.create(requestObj.value))
        .thenReturn(io(UserCreateResult.InvalidPassword(Set.empty)))

      TestRequest(routePath, requestJson(requestObj.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual BadRequest

        verify(userCreateService).create(requestObj.value)
      }
    }
    "returns Forbidden for nonlogged user if service rejects the super admin key" in new NoLoggedTestCase {
      val requestObj = routeRepresentation.request.normal

      when(userCreateService.create(requestObj.value))
        .thenReturn(io(UserCreateResult.SuperAdminKeyRejected))

      TestRequest(routePath, requestJson(requestObj.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual Forbidden

        verify(userCreateService).create(requestObj.value)
      }
    }
    "returns OK for nonlogged user otherwise" in new NoLoggedTestCase {
      val requestObj  = routeRepresentation.request.normal
      val responseObj = routeRepresentation.response.normal

      when(userCreateService.create(requestObj.value))
        .thenReturn(io(UserCreateResult.Ok(responseObj.value)))

      TestRequest(routePath, requestJson(requestObj.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual OK
        responseAs[Json] shouldEqualJson responseObj.getJsonString

        verify(userCreateService).create(requestObj.value)
      }
    }
  }
}
