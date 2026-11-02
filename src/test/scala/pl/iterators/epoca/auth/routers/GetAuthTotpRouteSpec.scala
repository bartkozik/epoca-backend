package pl.iterators.epoca.auth.routers

import org.apache.pekko.http.scaladsl.model.StatusCodes.OK
import pl.iterators.baklava.core.model.RouteRepresentation
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.auth.services.TimeBasedOneTimePasswordSecretGetService
import pl.iterators.epoca.auth.services.TimeBasedOneTimePasswordSecretGetService.{
  TimeBasedOneTimePasswordSecretGetResult,
  TimeBasedOneTimePasswordSecretGetResultData
}
import pl.iterators.epoca.utils.{AuthContextProvider, RouteDocSpec}

class GetAuthTotpRouteSpec extends RouteDocSpec {

  override val routeRepresentation: RouteRepresentation[Unit, TimeBasedOneTimePasswordSecretGetResultData] =
    RouteRepresentation[Unit, TimeBasedOneTimePasswordSecretGetResultData]("Login user", "Get", "/auth/totp")

  trait NoLoggedTestCase extends BaseScope {
    override lazy val authContextForTest: Option[AuthContext] = None
  }

  trait LoggedTestCase extends BaseScope {
    override lazy val authContextForTest: Option[AuthContext] = Some(AuthContextProvider.user)

    override lazy val timeBasedOneTimePasswordSecretGetService: TimeBasedOneTimePasswordSecretGetService =
      mock[TimeBasedOneTimePasswordSecretGetService]
  }

  routeRepresentation.name should {
    "be inaccessible for no logged users" in new NoLoggedTestCase {
      TestRequest(routeRepresentation.path, emptyString) ~> allRoutes ~> check {
        response.status shouldBeInaccessible
      }
    }

    "returns Ok when service returns Ok" in new LoggedTestCase {
      val responseObject = routeRepresentation.response.random

      when(timeBasedOneTimePasswordSecretGetService.get(authContextForTest.get))
        .thenReturn(io(TimeBasedOneTimePasswordSecretGetResult.Ok(responseObject.value)))

      TestRequest(routeRepresentation.routePathWithAllParameters) ~> allRoutes ~> check {
        response.status shouldEqual OK
        responseAs[Json] shouldEqualJson responseObject.getJsonString

        verify(timeBasedOneTimePasswordSecretGetService).get(authContextForTest.get)
      }
    }
  }
}
