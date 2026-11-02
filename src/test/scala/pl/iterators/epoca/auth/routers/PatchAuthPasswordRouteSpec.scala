package pl.iterators.epoca.auth.routers

import org.apache.pekko.http.scaladsl.model.StatusCodes.{BadRequest, Forbidden, NotFound, OK}
import pl.iterators.baklava.core.model.{RouteRepresentation, RouteSecurity}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.auth.services.PasswordSetService
import pl.iterators.epoca.auth.services.PasswordSetService.{PasswordSetRequest, PasswordSetResult}
import pl.iterators.epoca.utils.{AuthContextProvider, RouteDocSpec}

class PatchAuthPasswordRouteSpec extends RouteDocSpec {

  override val routeRepresentation: RouteRepresentation[PasswordSetRequest, Unit] =
    RouteRepresentation[PasswordSetRequest, Unit]("Set user password", "PATCH", "/auth/password", authentication = List(RouteSecurity.Bearer()))

  trait NoLoggedTestCase extends BaseScope {
    override val authContextForTest: Option[AuthContext] = None
  }

  trait LoggedTestCase extends BaseScope {
    override val authContextForTest: Option[AuthContext] = Some(AuthContextProvider.user)

    override lazy val passwordSetService: PasswordSetService = mock[PasswordSetService]
  }

  routeRepresentation.name should {
    "should be inaccessible for no logged users" in new NoLoggedTestCase {
      TestRequest(routeRepresentation.routePathWithAllParameters) ~> allRoutes ~> check {
        response.status shouldBeInaccessible
      }
    }
    "returns BadRequest when incoming request is not json" in new LoggedTestCase {
      TestRequest(routeRepresentation.routePathWithAllParameters) ~> allRoutes ~> check {
        response.status shouldEqual BadRequest
      }
    }
    "returns BadRequest when incoming request is empty json" in new LoggedTestCase {
      TestRequest(routeRepresentation.routePathWithAllParameters, emptyJson) ~> allRoutes ~> check {
        response.status shouldEqual BadRequest
      }
    }
    "returns BadRequest when service returns InvalidPassword" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random

      when(passwordSetService.passwordSet(authContextForTest.get, requestObject.value)).thenReturn(io(PasswordSetResult.InvalidPassword(Set.empty)))

      TestRequest(routeRepresentation.routePathWithAllParameters, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual BadRequest

        verify(passwordSetService).passwordSet(authContextForTest.get, requestObject.value)
      }
    }
    "returns BadRequest when service returns PasswordNotChanged" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random

      when(passwordSetService.passwordSet(authContextForTest.get, requestObject.value)).thenReturn(io(PasswordSetResult.PasswordNotChanged))

      TestRequest(routeRepresentation.routePathWithAllParameters, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual BadRequest

        verify(passwordSetService).passwordSet(authContextForTest.get, requestObject.value)
      }
    }
    "returns NotFound when service returns IdentityNotFound" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random

      when(passwordSetService.passwordSet(authContextForTest.get, requestObject.value)).thenReturn(io(PasswordSetResult.IdentityNotFound))

      TestRequest(routeRepresentation.routePathWithAllParameters, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual NotFound

        verify(passwordSetService).passwordSet(authContextForTest.get, requestObject.value)
      }
    }
    "returns Forbidden when service returns InvalidAuthContext" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random

      when(passwordSetService.passwordSet(authContextForTest.get, requestObject.value)).thenReturn(io(PasswordSetResult.InvalidAuthContext))

      TestRequest(routeRepresentation.routePathWithAllParameters, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual Forbidden

        verify(passwordSetService).passwordSet(authContextForTest.get, requestObject.value)
      }
    }
    "returns Ok when service returns Ok" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random

      when(passwordSetService.passwordSet(authContextForTest.get, requestObject.value)).thenReturn(io(PasswordSetResult.Ok))

      TestRequest(routeRepresentation.routePathWithAllParameters, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual OK

        verify(passwordSetService).passwordSet(authContextForTest.get, requestObject.value)
      }
    }
  }
}
