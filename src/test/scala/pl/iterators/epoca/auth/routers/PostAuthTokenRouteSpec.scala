package pl.iterators.epoca.auth.routers

import org.apache.pekko.http.scaladsl.model.StatusCodes.{BadRequest, Conflict, OK, Unauthorized}
import pl.iterators.baklava.core.model.{RouteHeaderRepresentation, RouteRepresentation}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.auth.domain.AuthDomain.DeviceId
import pl.iterators.epoca.auth.routers.AuthRouter.LoginHttpRequestContext
import pl.iterators.epoca.auth.services.LoginByTokenService
import pl.iterators.epoca.auth.services.LoginByTokenService.{LoginByTokenRequest, LoginByTokenResult}
import pl.iterators.epoca.auth.services.LoginResolveService.LoginResolution
import pl.iterators.epoca.utils.http.directives.ExtractDeviceIdDirective
import pl.iterators.epoca.utils.{AuthContextProvider, RouteDocSpec}

import java.util.UUID

class PostAuthTokenRouteSpec extends RouteDocSpec {

  override val routeRepresentation: RouteRepresentation[LoginByTokenRequest, LoginResolution] =
    RouteRepresentation[LoginByTokenRequest, LoginResolution](
      "Login user by token",
      "POST",
      "/auth/token",
      headers = List(RouteHeaderRepresentation(ExtractDeviceIdDirective.extractDeviceIdHeaderName, true))
    )

  protected val deviceId: DeviceId = DeviceId(UUID.randomUUID().toString)

  protected def addDeviceIdHeader: RequestTransformer = addHeader(ExtractDeviceIdDirective.extractDeviceIdHeaderName, deviceId)

  trait NoLoggedTestCase extends BaseScope {
    override lazy val authContextForTest: Option[AuthContext] = None

    override lazy val loginByTokenService: LoginByTokenService = mock[LoginByTokenService]
  }

  trait LoggedTestCase extends BaseScope {
    override lazy val authContextForTest: Option[AuthContext] = Some(AuthContextProvider.user)

    override lazy val loginByTokenService: LoginByTokenService = mock[LoginByTokenService]
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

      when(loginByTokenService.login(None, requestObject.value, LoginHttpRequestContext(deviceId, None)))
        .thenReturn(io(LoginByTokenResult.UserOrLoginTokenNotFound))

      TestRequest(
        routeRepresentation.routePathWithAllParameters,
        requestJson(requestObject.getJsonString)
      ) ~> addDeviceIdHeader ~> allRoutes ~> check {
        response.status shouldEqual Unauthorized

        verify(loginByTokenService).login(None, requestObject.value, LoginHttpRequestContext(deviceId, None))
      }
    }
    "returns Unauthorized when service returns InvalidCredentials" in new NoLoggedTestCase {
      val requestObject = routeRepresentation.request.random

      when(loginByTokenService.login(None, requestObject.value, LoginHttpRequestContext(deviceId, None)))
        .thenReturn(io(LoginByTokenResult.InvalidCredentials))

      TestRequest(
        routeRepresentation.routePathWithAllParameters,
        requestJson(requestObject.getJsonString)
      ) ~> addDeviceIdHeader ~> allRoutes ~> check {
        response.status shouldEqual Unauthorized

        verify(loginByTokenService).login(None, requestObject.value, LoginHttpRequestContext(deviceId, None))
      }
    }
    "returns Conflict when service returns TokenExpired" in new NoLoggedTestCase {
      val requestObject = routeRepresentation.request.random

      when(loginByTokenService.login(None, requestObject.value, LoginHttpRequestContext(deviceId, None)))
        .thenReturn(io(LoginByTokenResult.TokenExpired))

      TestRequest(
        routeRepresentation.routePathWithAllParameters,
        requestJson(requestObject.getJsonString)
      ) ~> addDeviceIdHeader ~> allRoutes ~> check {
        response.status shouldEqual Conflict

        verify(loginByTokenService).login(None, requestObject.value, LoginHttpRequestContext(deviceId, None))
      }
    }
    "returns Ok when service returns Ok" in new NoLoggedTestCase {
      val requestObject  = routeRepresentation.request.random
      val responseObject = routeRepresentation.response.random

      when(loginByTokenService.login(None, requestObject.value, LoginHttpRequestContext(deviceId, None)))
        .thenReturn(io(LoginByTokenResult.Ok(responseObject.value)))

      TestRequest(
        routeRepresentation.routePathWithAllParameters,
        requestJson(requestObject.getJsonString)
      ) ~> addDeviceIdHeader ~> allRoutes ~> check {
        response.status shouldEqual OK
        responseAs[Json] shouldEqualJson responseObject.getJsonString

        verify(loginByTokenService).login(None, requestObject.value, LoginHttpRequestContext(deviceId, None))
      }
    }
    "returns Ok when service returns Ok for logged user as well" in new LoggedTestCase {
      val requestObject  = routeRepresentation.request.random
      val responseObject = routeRepresentation.response.random

      when(loginByTokenService.login(Some(authContextForTest.get), requestObject.value, LoginHttpRequestContext(deviceId, None)))
        .thenReturn(io(LoginByTokenResult.Ok(responseObject.value)))

      TestRequest(
        routeRepresentation.routePathWithAllParameters,
        requestJson(requestObject.getJsonString)
      ) ~> addDeviceIdHeader ~> allRoutes ~> check {
        response.status shouldEqual OK
        responseAs[Json] shouldEqualJson responseObject.getJsonString

        verify(loginByTokenService).login(Some(authContextForTest.get), requestObject.value, LoginHttpRequestContext(deviceId, None))
      }
    }
  }
}
