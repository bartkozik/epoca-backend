package pl.iterators.epoca.auth.routers

import org.apache.pekko.http.scaladsl.model.StatusCodes.{BadRequest, OK, Unauthorized}
import pl.iterators.baklava.core.model.{RouteHeaderRepresentation, RouteRepresentation}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.auth.domain.AuthDomain.DeviceId
import pl.iterators.epoca.auth.routers.AuthRouter.LoginHttpRequestContext
import pl.iterators.epoca.auth.services.LoginByRefreshTokenService
import pl.iterators.epoca.auth.services.LoginByRefreshTokenService._
import pl.iterators.epoca.auth.services.LoginResolveService.LoginResolution
import pl.iterators.epoca.utils.RouteDocSpec
import pl.iterators.epoca.utils.http.directives.ExtractDeviceIdDirective

import java.util.UUID

class PostAuthRefreshTokenRouteSpec extends RouteDocSpec {

  override val routeRepresentation: RouteRepresentation[LoginByRefreshTokenRequest, LoginResolution] =
    RouteRepresentation[LoginByRefreshTokenRequest, LoginResolution](
      "Login user by refresh token",
      "POST",
      "/auth/refresh-token",
      headers = List(RouteHeaderRepresentation(ExtractDeviceIdDirective.extractDeviceIdHeaderName, true))
    )

  protected val deviceId: DeviceId = DeviceId(UUID.randomUUID().toString)

  protected def addDeviceIdHeader: RequestTransformer = addHeader(ExtractDeviceIdDirective.extractDeviceIdHeaderName, deviceId)

  trait TestCase extends BaseScope {
    override lazy val authContextForTest: Option[AuthContext] = None

    override lazy val loginByRefreshTokenService: LoginByRefreshTokenService = mock[LoginByRefreshTokenService]
  }

  routeRepresentation.name should {
    "returns BadRequest when incoming request is not json" in new TestCase {
      TestRequest(routeRepresentation.routePathWithAllParameters, emptyString) ~> addDeviceIdHeader ~> allRoutes ~> check {
        response.status shouldEqual BadRequest
      }
    }
    "returns BadRequest when incoming request is empty json" in new TestCase {
      TestRequest(routeRepresentation.routePathWithAllParameters, emptyJson) ~> addDeviceIdHeader ~> allRoutes ~> check {
        response.status shouldEqual BadRequest
      }
    }
    "returns Unauthorized when service returns UserOrRefreshTokenNotFound" in new TestCase {
      val requestObject = routeRepresentation.request.random

      when(loginByRefreshTokenService.login(requestObject.value, LoginHttpRequestContext(deviceId, None)))
        .thenReturn(io(LoginByRefreshTokenResult.UserOrRefreshTokenNotFound))

      TestRequest(
        routeRepresentation.routePathWithAllParameters,
        requestJson(requestObject.getJsonString)
      ) ~> addDeviceIdHeader ~> allRoutes ~> check {
        response.status shouldEqual Unauthorized

        verify(loginByRefreshTokenService).login(requestObject.value, LoginHttpRequestContext(deviceId, None))
      }
    }
    "returns Unauthorized when service returns InvalidRefreshToken" in new TestCase {
      val requestObject = routeRepresentation.request.random

      when(loginByRefreshTokenService.login(requestObject.value, LoginHttpRequestContext(deviceId, None)))
        .thenReturn(io(LoginByRefreshTokenResult.InvalidRefreshToken))

      TestRequest(
        routeRepresentation.routePathWithAllParameters,
        requestJson(requestObject.getJsonString)
      ) ~> addDeviceIdHeader ~> allRoutes ~> check {
        response.status shouldEqual Unauthorized

        verify(loginByRefreshTokenService).login(requestObject.value, LoginHttpRequestContext(deviceId, None))
      }
    }
    "returns Ok when service returns Ok" in new TestCase {
      val requestObject  = routeRepresentation.request.random
      val responseObject = routeRepresentation.response.random

      when(loginByRefreshTokenService.login(requestObject.value, LoginHttpRequestContext(deviceId, None)))
        .thenReturn(io(LoginByRefreshTokenResult.Ok(responseObject.value)))

      TestRequest(
        routeRepresentation.routePathWithAllParameters,
        requestJson(requestObject.getJsonString)
      ) ~> addDeviceIdHeader ~> allRoutes ~> check {
        response.status shouldEqual OK
        responseAs[Json] shouldEqualJson responseObject.getJsonString

        verify(loginByRefreshTokenService).login(requestObject.value, LoginHttpRequestContext(deviceId, None))
      }
    }
  }
}
