package pl.iterators.epoca.user.routers

import io.circe.syntax._
import org.apache.pekko.http.scaladsl.model.StatusCodes._
import pl.iterators.baklava.core.model.{RouteRepresentation, RouteSecurity}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.user.domain.UserDomain._
import pl.iterators.epoca.user.domain.{UserIdentity, UserIdentityDto}
import pl.iterators.epoca.user.services.UserIdentityCreateService
import pl.iterators.epoca.user.services.UserIdentityCreateService.{UserIdentityCreateRequest, UserIdentityCreateResult}
import pl.iterators.epoca.utils.{AuthContextProvider, RouteDocSpec}

class PostUsersIdentitiesRouteSpec extends RouteDocSpec {
  override val routeRepresentation: RouteRepresentation[UserIdentityCreateRequest, UserIdentityDto] =
    RouteRepresentation[UserIdentityCreateRequest, UserIdentityDto](
      "Create user identity and send email/sms",
      "POST",
      "/users/identities",
      authentication = List(RouteSecurity.Bearer())
    )

  val routePath: String = routeRepresentation.path

  trait NoLoggedTestCase extends BaseScope {
    override val authContextForTest: Option[AuthContext] = None

  }
  trait LoggedTestCase extends BaseScope {
    override lazy val userIdentityCreateService: UserIdentityCreateService = vmock[UserIdentityCreateService]
    override val authContextForTest: Option[AuthContext]                   = Some(AuthContextProvider.user)
  }

  routeRepresentation.name should {
    "should be inaccessible for no logged users" in new NoLoggedTestCase {
      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldBeInaccessible
      }
    }

    "returns BadRequest when service returns InvalidIdentityValue" in new LoggedTestCase {
      val requestObj = routeRepresentation.request.normal

      when(userIdentityCreateService.create(authContextForTest.get, requestObj.value))
        .thenReturn(io(UserIdentityCreateResult.InvalidRequest))

      TestRequest(routePath, requestJson(requestObj.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual BadRequest

        verify(userIdentityCreateService).create(authContextForTest.get, requestObj.value)
      }
    }

    "returns Conflict when service returns IdentityValueAlreadyExist" in new LoggedTestCase {
      val requestObj = routeRepresentation.request.normal

      when(userIdentityCreateService.create(authContextForTest.get, requestObj.value))
        .thenReturn(io(UserIdentityCreateResult.IdentityValueAlreadyExist))

      TestRequest(routePath, requestJson(requestObj.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual Conflict

        verify(userIdentityCreateService).create(authContextForTest.get, requestObj.value)
      }
    }

    "returns Conflict when service returns NoMainIdentity" in new LoggedTestCase {
      val requestObj = routeRepresentation.request.normal

      when(userIdentityCreateService.create(authContextForTest.get, requestObj.value))
        .thenReturn(io(UserIdentityCreateResult.NoMainIdentity))

      TestRequest(routePath, requestJson(requestObj.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual Conflict

        verify(userIdentityCreateService).create(authContextForTest.get, requestObj.value)
      }
    }

    "redact returned TOTP secrets and confirmation codes at the HTTP boundary" in new LoggedTestCase {
      val requestObj = routeRepresentation.request.normal
      val identity = allGenerators[UserIdentity].normal.generate.copy(
        `type` = IdentityType.TimeBasedOneTimePassword,
        value = UserIdentityValue("secret-totp"),
        confirmationCode = Some(UserIdentityConfirmationCode("secret-code"))
      )
      when(userIdentityCreateService.create(authContextForTest.get, requestObj.value)).thenReturn(io(UserIdentityCreateResult.Ok(identity)))
      TestRequest(routePath, requestJson(requestObj.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual OK
        responseAs[Json].hcursor.get[String]("value") shouldEqual Right("")
        responseAs[Json].hcursor.downField("confirmationCode").focus shouldEqual None
        verify(userIdentityCreateService).create(authContextForTest.get, requestObj.value)
      }
    }
    "returns Ok otherwise" in new LoggedTestCase {
      val requestObj  = routeRepresentation.request.normal
      val responseObj = allGenerators[UserIdentity].normal.generate

      when(userIdentityCreateService.create(authContextForTest.get, requestObj.value))
        .thenReturn(io(UserIdentityCreateResult.Ok(responseObj)))

      TestRequest(routePath, requestJson(requestObj.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual OK
        responseAs[Json] shouldEqual UserIdentityDto.from(responseObj).asJson

        verify(userIdentityCreateService).create(authContextForTest.get, requestObj.value)
      }
    }
  }
}
