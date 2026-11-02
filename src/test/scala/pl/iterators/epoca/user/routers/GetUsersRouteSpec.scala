package pl.iterators.epoca.user.routers

import org.apache.pekko.http.scaladsl.model.StatusCodes._
import pl.iterators.baklava.core.model.{RouteParameterRepresentation, RouteRepresentation, RouteSecurity}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.mail.domain.MailDomain.MailAddress
import pl.iterators.epoca.sms.domain.SmsDomain.PhoneNumber
import pl.iterators.epoca.user.domain.UserWithIdentitiesDto
import pl.iterators.epoca.user.repositories.UserRepository.{UserFilters, UserSortBy}
import pl.iterators.epoca.user.services.UserListService
import pl.iterators.epoca.user.services.UserListService.UserListResult
import pl.iterators.epoca.utils.db.PageSizeAndNumber
import pl.iterators.epoca.utils.{AuthContextProvider, RouteDocSpec}

import scala.util.Random

class GetUsersRouteSpec extends RouteDocSpec {

  override val routeRepresentation: RouteRepresentation[Unit, Seq[UserWithIdentitiesDto]] =
    RouteRepresentation[Unit, Seq[UserWithIdentitiesDto]](
      "List users with identities",
      "GET",
      "/users",
      List(
        RouteParameterRepresentation[MailAddress]("email", false, MailAddress("user-c3bf127aa6d6@example.test"), identity),
        RouteParameterRepresentation[PhoneNumber]("phoneNumber", false, PhoneNumber("111222333"), identity),
        RouteParameterRepresentation[Int]("pageSize", false, Random.nextInt(100) + 1, _.toString),
        RouteParameterRepresentation[Int]("pageNumber", false, Random.nextInt(100) + 1, _.toString),
        RouteParameterRepresentation[UserSortBy]("sortBy", false, Random.shuffle(UserSortBy.values).head, _.toString)
      ),
      authentication = List(RouteSecurity.Bearer())
    )

  val routePath = routeRepresentation.path

  trait NoLoggedTestCase extends BaseScope {
    override val authContextForTest: Option[AuthContext] = None
  }

  trait LoggedTestCase extends BaseScope {
    override val authContextForTest: Option[AuthContext] = Some(AuthContextProvider.user)

    override lazy val userListService: UserListService = vmock[UserListService]
  }

  s"GET $routePath" should {
    "should be inaccessible for no logged users" in new NoLoggedTestCase {
      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldBeInaccessible
      }
    }
    "returns Forbidden with proper json when service returns OperationNotPermitted" in new LoggedTestCase {
      private val filters = UserFilters()
      when(userListService.listWithIdentities(authContextForTest.get, filters))
        .thenReturn(io(UserListResult.OperationNotPermitted))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual Forbidden

        verify(userListService).listWithIdentities(authContextForTest.get, filters)
      }
    }
    "returns OK with proper json when service returns OK" in new LoggedTestCase {
      private val filters     = UserFilters()
      private val responseObj = routeRepresentation.response.normal

      when(userListService.listWithIdentities(authContextForTest.get, filters))
        .thenReturn(io(UserListResult.Ok(responseObj.value)))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual OK
        responseAs[Json] shouldEqualJson responseObj.getJsonString

        verify(userListService).listWithIdentities(authContextForTest.get, filters)
      }
    }
    "returns OK with proper json when service returns OK and all optional params provided" in new LoggedTestCase {
      private val filters = UserFilters(
        email = routeRepresentation.getParamValue[MailAddress]("email"),
        phoneNumber = routeRepresentation.getParamValue[PhoneNumber]("phoneNumber"),
        sortBy = routeRepresentation.getParamValue[UserSortBy]("sortBy"),
        pageSizeAndNumber =
          PageSizeAndNumber.fromOptions(routeRepresentation.getParamValue[Int]("pageSize"), routeRepresentation.getParamValue[Int]("pageNumber"))
      )
      private val responseObj = routeRepresentation.response.normal

      when(userListService.listWithIdentities(authContextForTest.get, filters))
        .thenReturn(io(UserListResult.Ok(responseObj.value)))

      TestRequest(routeRepresentation.routePathWithAllParameters, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual OK
        responseAs[Json] shouldEqualJson responseObj.getJsonString

        verify(userListService).listWithIdentities(authContextForTest.get, filters)
      }
    }
  }
}
