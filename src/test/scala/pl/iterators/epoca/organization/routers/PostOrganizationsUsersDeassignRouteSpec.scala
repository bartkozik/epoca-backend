package pl.iterators.epoca.organization.routers

import org.apache.pekko.http.scaladsl.model.StatusCodes
import pl.iterators.baklava.core.model.{RouteRepresentation, RouteSecurity}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.organization.services.OrganizationUserDeassignService
import pl.iterators.epoca.organization.services.OrganizationUserDeassignService.{OrganizationDeassignRequest, OrganizationDeassignResult}
import pl.iterators.epoca.utils.{AuthContextProvider, RouteDocSpec}

class PostOrganizationsUsersDeassignRouteSpec extends RouteDocSpec {
  override val routeRepresentation: RouteRepresentation[OrganizationDeassignRequest, Unit] =
    RouteRepresentation[OrganizationDeassignRequest, Unit](
      "Deassign user from organization",
      "POST",
      "/organizations/{organizationSlug}/users/deassign",
      authentication = List(RouteSecurity.Bearer())
    )

  val auth: AuthContext                  = AuthContextProvider.user
  val organizationSlug: OrganizationSlug = allGenerators[OrganizationSlug].normal.generate
  val routePath: String                  = routeRepresentation.path.replace("{organizationSlug}", organizationSlug)

  trait NoLoggedTestCase extends BaseScope {
    override lazy val authContextForTest: Option[AuthContext] = None
  }

  trait LoggedTestCase extends BaseScope {
    override lazy val organizationUserDeassignService: OrganizationUserDeassignService = vmock[OrganizationUserDeassignService]

    override val authContextForTest: Option[AuthContext] = Some(auth)
  }

  routeRepresentation.name should {
    "be inaccessible for no-logged user" in new NoLoggedTestCase {
      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldBeInaccessible
      }
    }

    "be BadRequest on empty json" in new LoggedTestCase {
      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.BadRequest
      }
    }

    "return NotFound when service returns OperationNotPermitted" in new LoggedTestCase {
      val request = routeRepresentation.request.normal

      when(organizationUserDeassignService.deassign(auth, organizationSlug, request.value))
        .thenReturn(io(OrganizationDeassignResult.OperationNotPermitted))

      TestRequest(routePath, requestJson(request.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(organizationUserDeassignService).deassign(auth, organizationSlug, request.value)
    }

    "return NotFound when service returns OrganizationNotFound" in new LoggedTestCase {
      val request = routeRepresentation.request.normal

      when(organizationUserDeassignService.deassign(auth, organizationSlug, request.value))
        .thenReturn(io(OrganizationDeassignResult.OrganizationNotFound))

      TestRequest(routePath, requestJson(request.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(organizationUserDeassignService).deassign(auth, organizationSlug, request.value)
    }

    "return BadRequest when service returns UserAlreadyUnassigned" in new LoggedTestCase {
      val request = routeRepresentation.request.normal

      when(organizationUserDeassignService.deassign(auth, organizationSlug, request.value))
        .thenReturn(io(OrganizationDeassignResult.UserAlreadyUnassigned))

      TestRequest(routePath, requestJson(request.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.BadRequest
      }

      verify(organizationUserDeassignService).deassign(auth, organizationSlug, request.value)
    }

    "return BadRequest when service returns InvalidRequest" in new LoggedTestCase {
      val request = routeRepresentation.request.normal

      when(organizationUserDeassignService.deassign(auth, organizationSlug, request.value))
        .thenReturn(io(OrganizationDeassignResult.InvalidRequest))

      TestRequest(routePath, requestJson(request.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.BadRequest
      }

      verify(organizationUserDeassignService).deassign(auth, organizationSlug, request.value)
    }

    "return NoContent otherwise" in new LoggedTestCase {
      val request = routeRepresentation.request.normal

      when(organizationUserDeassignService.deassign(auth, organizationSlug, request.value))
        .thenReturn(io(OrganizationDeassignResult.Deassigned))

      TestRequest(routePath, requestJson(request.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NoContent
      }

      verify(organizationUserDeassignService).deassign(auth, organizationSlug, request.value)
    }
  }
}
