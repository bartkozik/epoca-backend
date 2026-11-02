package pl.iterators.epoca.auth.routers

import org.apache.pekko.http.scaladsl.model.StatusCodes.{BadRequest, NotImplemented, OK, Unauthorized}
import pl.iterators.baklava.core.model.{RouteHeaderRepresentation, RouteRepresentation}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.auth.domain.AuthDomain.DeviceId
import pl.iterators.epoca.auth.routers.AuthRouter.LoginHttpRequestContext
import pl.iterators.epoca.auth.services.LoginByFirebaseService
import pl.iterators.epoca.auth.services.LoginByFirebaseService.{LoginByFirebaseRequest, LoginByFirebaseResult}
import pl.iterators.epoca.auth.services.LoginResolveService.LoginResolution
import pl.iterators.epoca.utils.http.directives.ExtractDeviceIdDirective
import pl.iterators.epoca.utils.{AuthContextProvider, RouteDocSpec}

import java.util.UUID

class PostAuthFirebaseRouteSpec extends RouteDocSpec {

  override val routeRepresentation: RouteRepresentation[LoginByFirebaseRequest, LoginResolution] =
    RouteRepresentation[LoginByFirebaseRequest, LoginResolution](
      "Login user by firebase",
      "POST",
      "/auth/firebase",
      headers = List(RouteHeaderRepresentation(ExtractDeviceIdDirective.extractDeviceIdHeaderName, true))
    )

  protected val deviceId: DeviceId = DeviceId(UUID.randomUUID().toString)

  protected def addDeviceIdHeader: RequestTransformer = addHeader(ExtractDeviceIdDirective.extractDeviceIdHeaderName, deviceId)

  trait NoLoggedTestCase extends BaseScope {
    override lazy val authContextForTest: Option[AuthContext] = None

    override lazy val loginByFirebaseService: LoginByFirebaseService = mock[LoginByFirebaseService]
  }

  trait LoggedTestCase extends BaseScope {
    override lazy val authContextForTest: Option[AuthContext] = Some(AuthContextProvider.user)

    override lazy val loginByFirebaseService: LoginByFirebaseService = mock[LoginByFirebaseService]
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
    "returns Unauthorized when service returns InvalidCredentials" in new NoLoggedTestCase {
      val requestObject = routeRepresentation.request.random

      when(loginByFirebaseService.login(None, requestObject.value, LoginHttpRequestContext(deviceId, None)))
        .thenReturn(io(LoginByFirebaseResult.InvalidCredentials))

      TestRequest(
        routeRepresentation.routePathWithAllParameters,
        requestJson(requestObject.getJsonString)
      ) ~> addDeviceIdHeader ~> allRoutes ~> check {
        response.status shouldEqual Unauthorized

        verify(loginByFirebaseService).login(None, requestObject.value, LoginHttpRequestContext(deviceId, None))
      }
    }
    "returns NotImplemented when service returns FirebaseNotConfigured" in new NoLoggedTestCase {
      val requestObject = routeRepresentation.request.random

      when(loginByFirebaseService.login(None, requestObject.value, LoginHttpRequestContext(deviceId, None)))
        .thenReturn(io(LoginByFirebaseResult.FirebaseNotConfigured))

      TestRequest(
        routeRepresentation.routePathWithAllParameters,
        requestJson(requestObject.getJsonString)
      ) ~> addDeviceIdHeader ~> allRoutes ~> check {
        response.status shouldEqual NotImplemented

        verify(loginByFirebaseService).login(None, requestObject.value, LoginHttpRequestContext(deviceId, None))
      }
    }
    "returns Ok when service returns Ok" in new NoLoggedTestCase {
      val requestObject  = routeRepresentation.request.random
      val responseObject = routeRepresentation.response.random

      when(loginByFirebaseService.login(None, requestObject.value, LoginHttpRequestContext(deviceId, None)))
        .thenReturn(io(LoginByFirebaseResult.Ok(responseObject.value)))

      TestRequest(
        routeRepresentation.routePathWithAllParameters,
        requestJson(requestObject.getJsonString)
      ) ~> addDeviceIdHeader ~> allRoutes ~> check {
        response.status shouldEqual OK
        responseAs[Json] shouldEqualJson responseObject.getJsonString

        verify(loginByFirebaseService).login(None, requestObject.value, LoginHttpRequestContext(deviceId, None))
      }
    }
    "returns Ok when service returns Ok for logged user as well" in new LoggedTestCase {
      val requestObject  = routeRepresentation.request.random
      val responseObject = routeRepresentation.response.random

      when(loginByFirebaseService.login(Some(authContextForTest.get), requestObject.value, LoginHttpRequestContext(deviceId, None)))
        .thenReturn(io(LoginByFirebaseResult.Ok(responseObject.value)))

      TestRequest(
        routeRepresentation.routePathWithAllParameters,
        requestJson(requestObject.getJsonString)
      ) ~> addDeviceIdHeader ~> allRoutes ~> check {
        response.status shouldEqual OK
        responseAs[Json] shouldEqualJson responseObject.getJsonString

        verify(loginByFirebaseService).login(Some(authContextForTest.get), requestObject.value, LoginHttpRequestContext(deviceId, None))
      }
    }
  }
}
