package pl.iterators.epoca.organizationdashboard.routers

import org.apache.pekko.http.scaladsl.model.StatusCodes
import pl.iterators.baklava.core.model.{RouteParameterRepresentation, RouteRepresentation, RouteSecurity}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.organizationdashboard.services.OrganizationUserListService
import pl.iterators.epoca.organizationdashboard.services.OrganizationUserListService.{OrganizationUserDto, OrganizationUserListResult}
import pl.iterators.epoca.utils.RouteDocSpec

class GetOrganizationsUsersRouteSpec extends RouteDocSpec {
  override val routeRepresentation: RouteRepresentation[Unit, Seq[OrganizationUserDto]] =
    RouteRepresentation[Unit, Seq[OrganizationUserDto]](
      "List organization users",
      "GET",
      "/organizations/{organizationSlug}/users",
      List(
        RouteParameterRepresentation[Boolean]("includeArchived", false, false, _.toString),
        RouteParameterRepresentation[Boolean]("includeArchivedMembers", false, false, _.toString)
      ),
      authentication = List(RouteSecurity.Bearer())
    )

  val auth: AuthContext                  = allGenerators[AuthContext].normal.generate
  val organizationSlug: OrganizationSlug = allGenerators[OrganizationSlug].normal.generate
  val routePath: String                  = routeRepresentation.path.replace("{organizationSlug}", organizationSlug)

  trait NoLoggedTestCase extends BaseScope {
    override lazy val authContextForTest: Option[AuthContext] = None
  }

  trait LoggedTestCase extends BaseScope {
    override lazy val organizationUserListService: OrganizationUserListService = vmock[OrganizationUserListService]

    override val authContextForTest: Option[AuthContext] = Some(auth)
  }

  routeRepresentation.name should {
    "be inaccessible for no-logged user" in new NoLoggedTestCase {
      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldBeInaccessible
      }
    }

    "return NotFound when service returns OperationNotPermitted" in new LoggedTestCase {
      when(organizationUserListService.list(auth, organizationSlug, includeArchived = false))
        .thenReturn(io(OrganizationUserListResult.OperationNotPermitted))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(organizationUserListService).list(auth, organizationSlug, includeArchived = false)
    }

    "return NotFound when service returns OrganizationNotFound" in new LoggedTestCase {
      when(organizationUserListService.list(auth, organizationSlug, includeArchived = false))
        .thenReturn(io(OrganizationUserListResult.OrganizationNotFound))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(organizationUserListService).list(auth, organizationSlug, includeArchived = false)
    }

    "return Ok with service data otherwise" in new LoggedTestCase {
      val responseObject = routeRepresentation.response.random
      when(organizationUserListService.list(auth, organizationSlug, includeArchived = false))
        .thenReturn(io(OrganizationUserListResult.Ok(responseObject.value)))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.OK
        responseAs[Json] shouldEqualJson responseObject.getJsonString
      }

      verify(organizationUserListService).list(auth, organizationSlug, includeArchived = false)
    }

    "pass explicit includeArchived=false to the service" in new LoggedTestCase {
      val responseObject = routeRepresentation.response.random
      when(organizationUserListService.list(auth, organizationSlug, includeArchived = false))
        .thenReturn(io(OrganizationUserListResult.Ok(responseObject.value)))

      TestRequest(s"$routePath?includeArchived=false", emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.OK
        responseAs[Json] shouldEqualJson responseObject.getJsonString
      }

      verify(organizationUserListService).list(auth, organizationSlug, includeArchived = false)
    }

    "pass includeArchived=true to the service and return archived status" in new LoggedTestCase {
      val archivedUser = allGenerators[OrganizationUserDto].normal.generate.copy(archived = true)
      when(organizationUserListService.list(auth, organizationSlug, includeArchived = true))
        .thenReturn(io(OrganizationUserListResult.Ok(Seq(archivedUser))))

      TestRequest(s"$routePath?includeArchived=true", emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.OK
        responseAs[Json].hcursor.downArray.get[Boolean]("archived") shouldEqual Right(true)
      }

      verify(organizationUserListService).list(auth, organizationSlug, includeArchived = true)
    }

    "pass includeArchivedMembers=true to the service and return archived members" in new LoggedTestCase {
      val archivedMember = allGenerators[OrganizationUserDto].normal.generate.copy(archived = true)
      when(organizationUserListService.list(auth, organizationSlug, includeArchived = true, includeArchivedMembers = true))
        .thenReturn(io(OrganizationUserListResult.Ok(Seq(archivedMember))))

      TestRequest(s"$routePath?includeArchived=true&includeArchivedMembers=true", emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.OK
        responseAs[Json].hcursor.downArray.get[Boolean]("archived") shouldEqual Right(true)
      }

      verify(organizationUserListService).list(auth, organizationSlug, includeArchived = true, includeArchivedMembers = true)
    }
  }
}
