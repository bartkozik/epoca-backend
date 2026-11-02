package pl.iterators.epoca.auth.routers

import org.apache.pekko.http.scaladsl.model.StatusCodes.{BadRequest, OK, Unauthorized}
import pl.iterators.baklava.core.model.{RouteHeaderRepresentation, RouteRepresentation}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.auth.domain.AuthDomain.DeviceId
import pl.iterators.epoca.auth.routers.AuthRouter.LoginHttpRequestContext
import pl.iterators.epoca.auth.services.LoginByCryptoVerificationService
import pl.iterators.epoca.auth.services.LoginByCryptoVerificationService.{LoginByCryptoVerificationRequest, LoginByCryptoVerificationResult}
import pl.iterators.epoca.auth.services.LoginResolveService.LoginResolution
import pl.iterators.epoca.utils.http.directives.ExtractDeviceIdDirective
import pl.iterators.epoca.utils.{AuthContextProvider, RouteDocSpec}

import java.util.UUID

class PostAuthCryptoRouteSpec extends RouteDocSpec {

  override val routeRepresentation: RouteRepresentation[LoginByCryptoVerificationRequest, LoginResolution] =
    RouteRepresentation[LoginByCryptoVerificationRequest, LoginResolution](
      "Login user",
      "POST",
      "/auth/crypto",
      headers = List(RouteHeaderRepresentation(ExtractDeviceIdDirective.extractDeviceIdHeaderName, true))
    )

  protected val deviceId: DeviceId = DeviceId(UUID.randomUUID().toString)

  protected def addDeviceIdHeader: RequestTransformer = addHeader(ExtractDeviceIdDirective.extractDeviceIdHeaderName, deviceId)

  trait NoLoggedTestCase extends BaseScope {
    override lazy val authContextForTest: Option[AuthContext] = None

    override lazy val loginByCryptoVerificationService: LoginByCryptoVerificationService = mock[LoginByCryptoVerificationService]
  }

  trait LoggedTestCase extends BaseScope {
    override lazy val authContextForTest: Option[AuthContext] = Some(AuthContextProvider.user)

    override lazy val loginByCryptoVerificationService: LoginByCryptoVerificationService = mock[LoginByCryptoVerificationService]
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
    "returns Unauthorized when service returns UserNotFound" in new NoLoggedTestCase {
      val requestObject = routeRepresentation.request.random

      when(loginByCryptoVerificationService.login(None, requestObject.value, LoginHttpRequestContext(deviceId, None)))
        .thenReturn(io(LoginByCryptoVerificationResult.UserNotFound))

      TestRequest(
        routeRepresentation.routePathWithAllParameters,
        requestJson(requestObject.getJsonString)
      ) ~> addDeviceIdHeader ~> allRoutes ~> check {
        response.status shouldEqual Unauthorized

        verify(loginByCryptoVerificationService).login(None, requestObject.value, LoginHttpRequestContext(deviceId, None))
      }
    }
    "returns Unauthorized when service returns InvalidCredentials" in new NoLoggedTestCase {
      val requestObject = routeRepresentation.request.random

      when(loginByCryptoVerificationService.login(None, requestObject.value, LoginHttpRequestContext(deviceId, None)))
        .thenReturn(io(LoginByCryptoVerificationResult.InvalidCredentials))

      TestRequest(
        routeRepresentation.routePathWithAllParameters,
        requestJson(requestObject.getJsonString)
      ) ~> addDeviceIdHeader ~> allRoutes ~> check {
        response.status shouldEqual Unauthorized

        verify(loginByCryptoVerificationService).login(None, requestObject.value, LoginHttpRequestContext(deviceId, None))
      }
    }
    "returns Ok when service returns Ok" in new NoLoggedTestCase {
      val requestObject  = routeRepresentation.request.random
      val responseObject = routeRepresentation.response.random

      when(loginByCryptoVerificationService.login(None, requestObject.value, LoginHttpRequestContext(deviceId, None)))
        .thenReturn(io(LoginByCryptoVerificationResult.Ok(responseObject.value)))

      TestRequest(
        routeRepresentation.routePathWithAllParameters,
        requestJson(requestObject.getJsonString)
      ) ~> addDeviceIdHeader ~> allRoutes ~> check {
        response.status shouldEqual OK
        responseAs[Json] shouldEqualJson responseObject.getJsonString

        verify(loginByCryptoVerificationService).login(None, requestObject.value, LoginHttpRequestContext(deviceId, None))
      }
    }
    "returns Ok when service returns Ok for logged user as well" in new LoggedTestCase {
      val requestObject  = routeRepresentation.request.random
      val responseObject = routeRepresentation.response.random

      when(loginByCryptoVerificationService.login(Some(authContextForTest.get), requestObject.value, LoginHttpRequestContext(deviceId, None)))
        .thenReturn(io(LoginByCryptoVerificationResult.Ok(responseObject.value)))

      TestRequest(
        routeRepresentation.routePathWithAllParameters,
        requestJson(requestObject.getJsonString)
      ) ~> addDeviceIdHeader ~> allRoutes ~> check {
        response.status shouldEqual OK
        responseAs[Json] shouldEqualJson responseObject.getJsonString

        verify(loginByCryptoVerificationService).login(Some(authContextForTest.get), requestObject.value, LoginHttpRequestContext(deviceId, None))
      }
    }
  }
}
