package pl.iterators.epoca.auth.routers

import org.apache.pekko.http.scaladsl.model.StatusCodes.{BadRequest, NotFound, OK}
import pl.iterators.baklava.core.model.RouteRepresentation
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.auth.services.LoginTokenGetService
import pl.iterators.epoca.auth.services.LoginTokenGetService.{LoginTokenGetRequest, LoginTokenGetResult}
import pl.iterators.epoca.utils.RouteDocSpec

class PostAuthGetTokenRouteSpec extends RouteDocSpec {

  override val routeRepresentation: RouteRepresentation[LoginTokenGetRequest, Unit] =
    RouteRepresentation[LoginTokenGetRequest, Unit]("Send token to user by email", "POST", "/auth/get-token")

  trait TestCase extends BaseScope {
    override lazy val authContextForTest: Option[AuthContext] = None

    override lazy val loginTokenGetService: LoginTokenGetService = mock[LoginTokenGetService]
  }

  routeRepresentation.name should {
    "returns BadRequest when incoming request is not json" in new TestCase {
      TestRequest(routeRepresentation.routePathWithAllParameters, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual BadRequest
      }
    }
    "returns BadRequest when incoming request is empty json" in new TestCase {
      TestRequest(routeRepresentation.routePathWithAllParameters, emptyJson) ~> allRoutes ~> check {
        response.status shouldEqual BadRequest
      }
    }
    "returns BadRequest when service returns InvalidRequest" in new TestCase {
      val requestObject = routeRepresentation.request.random

      when(loginTokenGetService.getToken(requestObject.value)).thenReturn(io(LoginTokenGetResult.InvalidRequest))

      TestRequest(routeRepresentation.routePathWithAllParameters, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual BadRequest

        verify(loginTokenGetService).getToken(requestObject.value)
      }
    }
    "returns NotFound when service returns UserNotFound" in new TestCase {
      val requestObject = routeRepresentation.request.random

      when(loginTokenGetService.getToken(requestObject.value)).thenReturn(io(LoginTokenGetResult.UserNotFound))

      TestRequest(routeRepresentation.routePathWithAllParameters, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual NotFound

        verify(loginTokenGetService).getToken(requestObject.value)
      }
    }
    "returns Ok when service returns Ok" in new TestCase {
      val requestObject = routeRepresentation.request.random

      when(loginTokenGetService.getToken(requestObject.value)).thenReturn(io(LoginTokenGetResult.Ok))

      TestRequest(routeRepresentation.routePathWithAllParameters, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual OK

        verify(loginTokenGetService).getToken(requestObject.value)
      }
    }
  }
}
