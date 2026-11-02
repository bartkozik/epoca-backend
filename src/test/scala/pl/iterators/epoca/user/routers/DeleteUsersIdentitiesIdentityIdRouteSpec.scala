package pl.iterators.epoca.user.routers

import org.apache.pekko.http.scaladsl.model.StatusCodes._
import pl.iterators.baklava.core.model.{RouteRepresentation, RouteSecurity}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.user.domain.UserDomain.UserIdentityId
import pl.iterators.epoca.user.services.UserIdentityDeleteService
import pl.iterators.epoca.user.services.UserIdentityDeleteService.UserIdentityDeleteResult
import pl.iterators.epoca.utils.{AuthContextProvider, RouteDocSpec}

import java.util.UUID

class DeleteUsersIdentitiesIdentityIdRouteSpec extends RouteDocSpec {
  override val routeRepresentation: RouteRepresentation[Unit, Unit] =
    RouteRepresentation[Unit, Unit]("Delete user identity", "DELETE", "/users/identities/{identityId}", authentication = List(RouteSecurity.Bearer()))

  val userIdentityId: UserIdentityId = UserIdentityId(UUID.randomUUID())
  val routePath: String              = routeRepresentation.path.replace("{identityId}", userIdentityId.toString)

  trait NoLoggedTestCase extends BaseScope {
    override val authContextForTest: Option[AuthContext] = None

  }

  trait LoggedTestCase extends BaseScope {
    override lazy val userIdentityDeleteService: UserIdentityDeleteService = vmock[UserIdentityDeleteService]
    override val authContextForTest: Option[AuthContext]                   = Some(AuthContextProvider.user)
  }

  routeRepresentation.name should {
    "should be inaccessible for no logged users" in new NoLoggedTestCase {
      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldBeInaccessible
      }
    }

    "returns NotFound when service returns IdentityNotFound" in new LoggedTestCase {
      val requestObj = routeRepresentation.request.normal

      when(userIdentityDeleteService.delete(authContextForTest.get, userIdentityId))
        .thenReturn(io(UserIdentityDeleteResult.IdentityNotFound))

      TestRequest(routePath, requestJson(requestObj.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual NotFound

        verify(userIdentityDeleteService).delete(authContextForTest.get, userIdentityId)
      }
    }

    "returns Conflict when service returns CanNotDeleteMain" in new LoggedTestCase {
      val requestObj = routeRepresentation.request.normal

      when(userIdentityDeleteService.delete(authContextForTest.get, userIdentityId))
        .thenReturn(io(UserIdentityDeleteResult.CanNotDeleteMain))

      TestRequest(routePath, requestJson(requestObj.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual Conflict

        verify(userIdentityDeleteService).delete(authContextForTest.get, userIdentityId)
      }
    }

    "returns NoContent when service returns Ok" in new LoggedTestCase {
      val requestObj = routeRepresentation.request.normal

      when(userIdentityDeleteService.delete(authContextForTest.get, userIdentityId))
        .thenReturn(io(UserIdentityDeleteResult.Ok))

      TestRequest(routePath, requestJson(requestObj.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual NoContent

        verify(userIdentityDeleteService).delete(authContextForTest.get, userIdentityId)
      }
    }
  }
}
