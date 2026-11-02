package pl.iterators.epoca.user.routers

import org.apache.pekko.http.scaladsl.model.StatusCodes._
import pl.iterators.baklava.core.model.{RouteRepresentation, RouteSecurity}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.user.domain.UserDomain._
import pl.iterators.epoca.user.services.UserDeleteService
import pl.iterators.epoca.user.services.UserDeleteService.UserDeleteResult
import pl.iterators.epoca.utils.RouteDocSpec

import java.util.UUID

class DeleteUsersUserIdRouteSpec extends RouteDocSpec {

  override val routeRepresentation: RouteRepresentation[Unit, Unit] =
    RouteRepresentation[Unit, Unit]("Delete user", "DELETE", "/users/{userId}", authentication = List(RouteSecurity.Bearer()))

  val userId: UserId    = UserId(UUID.randomUUID())
  val routePath: String = routeRepresentation.path.replace("{userId}", userId.toString)

  trait NoLoggedTestCase extends BaseScope {
    override val authContextForTest: Option[AuthContext] = None

  }
  trait LoggedTestCase extends BaseScope {
    override lazy val userDeleteService: UserDeleteService = vmock[UserDeleteService]
    override val authContextForTest: Option[AuthContext]   = Some(allGenerators[AuthContext].normal.generate)
  }

  routeRepresentation.name should {
    "should be inaccessible for no logged users" in new NoLoggedTestCase {
      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldBeInaccessible
      }
    }

    "returns Forbidden when service returns OperationNotPermitted" in new LoggedTestCase {
      when(userDeleteService.delete(authContextForTest.get, userId))
        .thenReturn(io(UserDeleteResult.OperationNotPermitted))

      TestRequest(routePath, emptyJson) ~> allRoutes ~> check {
        response.status shouldEqual Forbidden

        verify(userDeleteService).delete(authContextForTest.get, userId)
      }
    }
    "returns NotFound when service returns UserDoNotExist" in new LoggedTestCase {
      when(userDeleteService.delete(authContextForTest.get, userId))
        .thenReturn(io(UserDeleteResult.UserDoNotExist))

      TestRequest(routePath, emptyJson) ~> allRoutes ~> check {
        response.status shouldEqual NotFound

        verify(userDeleteService).delete(authContextForTest.get, userId)
      }
    }
    "returns NoContent for logged user if service returns Ok" in new LoggedTestCase {
      when(userDeleteService.delete(authContextForTest.get, userId))
        .thenReturn(io(UserDeleteResult.Ok))

      TestRequest(routePath, emptyJson) ~> allRoutes ~> check {
        response.status shouldEqual NoContent

        verify(userDeleteService).delete(authContextForTest.get, userId)
      }
    }
  }
}
