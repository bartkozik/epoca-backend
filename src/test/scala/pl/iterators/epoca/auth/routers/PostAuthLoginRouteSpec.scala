package pl.iterators.epoca.auth.routers

import org.apache.pekko.http.scaladsl.model.StatusCodes.{BadRequest, Forbidden, OK, Unauthorized}
import pl.iterators.baklava.core.model.{RouteHeaderRepresentation, RouteRepresentation}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.auth.domain.AuthDomain.DeviceId
import pl.iterators.epoca.auth.routers.AuthRouter.LoginHttpRequestContext
import pl.iterators.epoca.auth.services.LoginByPasswordService
import pl.iterators.epoca.auth.services.LoginByPasswordService.{LoginRequest, LoginResult}
import pl.iterators.epoca.auth.services.LoginResolveService.LoginResolution
import pl.iterators.epoca.utils.http.directives.ExtractDeviceIdDirective
import pl.iterators.epoca.utils.{AuthContextProvider, RouteDocSpec}

import java.util.UUID

class PostAuthLoginRouteSpec extends RouteDocSpec {

  override val routeRepresentation: RouteRepresentation[LoginRequest, LoginResolution] =
    RouteRepresentation[LoginRequest, LoginResolution](
      "Login user",
      "POST",
      "/auth/login",
      headers = List(RouteHeaderRepresentation(ExtractDeviceIdDirective.extractDeviceIdHeaderName, true))
    )

  protected val deviceId: DeviceId = DeviceId(UUID.randomUUID().toString)

  protected def addDeviceIdHeader: RequestTransformer = addHeader(ExtractDeviceIdDirective.extractDeviceIdHeaderName, deviceId)

  trait NoLoggedTestCase extends BaseScope {
    override lazy val authContextForTest: Option[AuthContext] = None

    override lazy val loginByPasswordService: LoginByPasswordService = mock[LoginByPasswordService]
  }

  trait LoggedTestCase extends BaseScope {
    override lazy val authContextForTest: Option[AuthContext] = Some(AuthContextProvider.user)

    override lazy val loginByPasswordService: LoginByPasswordService = mock[LoginByPasswordService]
  }

  routeRepresentation.name should {
    "returns BadRequest when incoming request is not json" in new NoLoggedTestCase {
      TestRequest(routeRepresentation.routePathWithAllParameters, emptyString) ~> addDeviceIdHeader ~> allRoutes ~> check {
        response.status shouldEqual BadRequest
      }
    }
    "returns BadRequest when incoming request is empty json" in new NoLoggedTestCase {
      TestRequest(routeRepresentation.routePathWithAllParameters, emptyJson) ~> addDeviceIdHeader ~> allRoutes ~> check {
        response.status shouldEqual BadRequest
      }
    }
    "returns Unauthorized when service returns UserOrPasswordNotFound" in new NoLoggedTestCase {
      val requestObject = routeRepresentation.request.random

      when(loginByPasswordService.login(None, requestObject.value, LoginHttpRequestContext(deviceId, None)))
        .thenReturn(io(LoginResult.UserOrPasswordNotFound))

      TestRequest(
        routeRepresentation.routePathWithAllParameters,
        requestJson(requestObject.getJsonString)
      ) ~> addDeviceIdHeader ~> allRoutes ~> check {
        response.status shouldEqual Unauthorized

        verify(loginByPasswordService).login(None, requestObject.value, LoginHttpRequestContext(deviceId, None))
      }
    }
    "returns Unauthorized when service returns InvalidCredentials" in new NoLoggedTestCase {
      val requestObject = routeRepresentation.request.random

      when(loginByPasswordService.login(None, requestObject.value, LoginHttpRequestContext(deviceId, None)))
        .thenReturn(io(LoginResult.InvalidCredentials))

      TestRequest(
        routeRepresentation.routePathWithAllParameters,
        requestJson(requestObject.getJsonString)
      ) ~> addDeviceIdHeader ~> allRoutes ~> check {
        response.status shouldEqual Unauthorized

        verify(loginByPasswordService).login(None, requestObject.value, LoginHttpRequestContext(deviceId, None))
      }
    }
    "returns Forbidden when service returns IdentityNotVerified" in new NoLoggedTestCase {
      val requestObject = routeRepresentation.request.random

      when(loginByPasswordService.login(None, requestObject.value, LoginHttpRequestContext(deviceId, None)))
        .thenReturn(io(LoginResult.IdentityNotConfirmed))

      TestRequest(
        routeRepresentation.routePathWithAllParameters,
        requestJson(requestObject.getJsonString)
      ) ~> addDeviceIdHeader ~> allRoutes ~> check {
        response.status shouldEqual Forbidden

        verify(loginByPasswordService).login(None, requestObject.value, LoginHttpRequestContext(deviceId, None))
      }
    }
    "returns BadRequest when service returns InvalidRequest" in new NoLoggedTestCase {
      val requestObject = routeRepresentation.request.random

      when(loginByPasswordService.login(None, requestObject.value, LoginHttpRequestContext(deviceId, None)))
        .thenReturn(io(LoginResult.InvalidRequest))

      TestRequest(
        routeRepresentation.routePathWithAllParameters,
        requestJson(requestObject.getJsonString)
      ) ~> addDeviceIdHeader ~> allRoutes ~> check {
        response.status shouldEqual BadRequest

        verify(loginByPasswordService).login(None, requestObject.value, LoginHttpRequestContext(deviceId, None))
      }
    }
    "returns Ok when service returns Ok" in new NoLoggedTestCase {
      val requestObject  = routeRepresentation.request.random
      val responseObject = routeRepresentation.response.random

      when(loginByPasswordService.login(None, requestObject.value, LoginHttpRequestContext(deviceId, None)))
        .thenReturn(io(LoginResult.Ok(responseObject.value)))

      TestRequest(
        routeRepresentation.routePathWithAllParameters,
        requestJson(requestObject.getJsonString)
      ) ~> addDeviceIdHeader ~> allRoutes ~> check {
        response.status shouldEqual OK
        responseAs[Json] shouldEqualJson responseObject.getJsonString

        verify(loginByPasswordService).login(None, requestObject.value, LoginHttpRequestContext(deviceId, None))
      }
    }
    "returns Ok when service returns Ok for logged user as well" in new LoggedTestCase {
      val requestObject  = routeRepresentation.request.random
      val responseObject = routeRepresentation.response.random

      when(loginByPasswordService.login(Some(authContextForTest.get), requestObject.value, LoginHttpRequestContext(deviceId, None)))
        .thenReturn(io(LoginResult.Ok(responseObject.value)))

      TestRequest(
        routeRepresentation.routePathWithAllParameters,
        requestJson(requestObject.getJsonString)
      ) ~> addDeviceIdHeader ~> allRoutes ~> check {
        response.status shouldEqual OK
        responseAs[Json] shouldEqualJson responseObject.getJsonString

        verify(loginByPasswordService).login(Some(authContextForTest.get), requestObject.value, LoginHttpRequestContext(deviceId, None))
      }
    }
  }
}
