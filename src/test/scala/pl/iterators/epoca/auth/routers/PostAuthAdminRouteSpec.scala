package pl.iterators.epoca.auth.routers

import org.apache.pekko.http.scaladsl.model.StatusCodes.{NotFound, OK, Unauthorized}
import pl.iterators.baklava.core.model.{RouteHeaderRepresentation, RouteRepresentation}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.auth.domain.AuthDomain.DeviceId
import pl.iterators.epoca.auth.routers.AuthRouter.LoginHttpRequestContext
import pl.iterators.epoca.auth.services.AdminImpersonateLoginService
import pl.iterators.epoca.auth.services.AdminImpersonateLoginService.{AdminImpersonateLoginRequest, AdminImpersonateLoginResult}
import pl.iterators.epoca.auth.services.LoginResolveService.LoginResolution
import pl.iterators.epoca.utils.http.directives.ExtractDeviceIdDirective
import pl.iterators.epoca.utils.{AuthContextProvider, RouteDocSpec}

import java.util.UUID

class PostAuthAdminRouteSpec extends RouteDocSpec {
  override val routeRepresentation: RouteRepresentation[AdminImpersonateLoginRequest, LoginResolution] =
    RouteRepresentation[AdminImpersonateLoginRequest, LoginResolution](
      "Generate jwt for specified user by admin",
      "POST",
      "/auth/admin",
      headers = List(RouteHeaderRepresentation(ExtractDeviceIdDirective.extractDeviceIdHeaderName, true))
    )

  protected val deviceId: DeviceId = DeviceId(UUID.randomUUID().toString)

  protected def addDeviceIdHeader: RequestTransformer = addHeader(ExtractDeviceIdDirective.extractDeviceIdHeaderName, deviceId)

  trait NoLoggedTestCase extends BaseScope {
    override lazy val authContextForTest: Option[AuthContext] = None
  }
  trait LoggedTestCase extends BaseScope {
    val requestObject                                                            = routeRepresentation.request.random
    override val authContextForTest: Option[AuthContext]                         = Some(AuthContextProvider.user)
    override lazy val adminImpersonateLoginService: AdminImpersonateLoginService = vmock[AdminImpersonateLoginService]
  }
  routeRepresentation.name should {
    "be inaccessible for no logged users" in new NoLoggedTestCase {
      TestRequest(routeRepresentation.path, emptyString) ~> addDeviceIdHeader ~> allRoutes ~> check {
        response.status shouldEqual Unauthorized
      }
    }
    "return Unauthorized when caller is not an admin" in new LoggedTestCase {
      when(adminImpersonateLoginService.login(authContextForTest.get, requestObject.value, LoginHttpRequestContext(deviceId, None)))
        .thenReturn(io(AdminImpersonateLoginResult.OperationNotPermitted))
      TestRequest(routeRepresentation.path, requestJson(requestObject.getJsonString)) ~> addDeviceIdHeader ~> allRoutes ~> check {
        response.status shouldEqual Unauthorized
        verify(adminImpersonateLoginService).login(authContextForTest.get, requestObject.value, LoginHttpRequestContext(deviceId, None))
      }
    }
    "return NotFound when user does not exist" in new LoggedTestCase {
      when(adminImpersonateLoginService.login(authContextForTest.get, requestObject.value, LoginHttpRequestContext(deviceId, None)))
        .thenReturn(io(AdminImpersonateLoginResult.UserDoesNotExist))
      TestRequest(routeRepresentation.path, requestJson(requestObject.getJsonString)) ~> addDeviceIdHeader ~> allRoutes ~> check {
        response.status shouldEqual NotFound
        verify(adminImpersonateLoginService).login(authContextForTest.get, requestObject.value, LoginHttpRequestContext(deviceId, None))
      }
    }
    "return generated jwt" in new LoggedTestCase {
      val responseObject = routeRepresentation.response.random
      when(adminImpersonateLoginService.login(authContextForTest.get, requestObject.value, LoginHttpRequestContext(deviceId, None)))
        .thenReturn(io(AdminImpersonateLoginResult.Ok(responseObject.value)))
      TestRequest(routeRepresentation.path, requestJson(requestObject.getJsonString)) ~> addDeviceIdHeader ~> allRoutes ~> check {
        response.status shouldEqual OK
        responseAs[Json] shouldEqualJson responseObject.getJsonString
        verify(adminImpersonateLoginService).login(authContextForTest.get, requestObject.value, LoginHttpRequestContext(deviceId, None))
      }
    }
  }
}
