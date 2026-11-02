package pl.iterators.epoca.organization.routers

import org.apache.pekko.http.scaladsl.model.StatusCodes
import pl.iterators.baklava.core.model.{RouteRepresentation, RouteSecurity}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.organization.services.OrganizationUserAssignService
import pl.iterators.epoca.organization.services.OrganizationUserAssignService.{OrganizationUserAssignRequest, OrganizationUserAssignResult}
import pl.iterators.epoca.utils.{AuthContextProvider, RouteDocSpec}

class PostOrganizationsUsersAssignRouteSpec extends RouteDocSpec {
  override val routeRepresentation: RouteRepresentation[OrganizationUserAssignRequest, Unit] =
    RouteRepresentation[OrganizationUserAssignRequest, Unit](
      "Assign user to organization",
      "POST",
      "/organizations/{organizationSlug}/users/assign",
      authentication = List(RouteSecurity.Bearer())
    )

  val auth: AuthContext                  = AuthContextProvider.user
  val organizationSlug: OrganizationSlug = allGenerators[OrganizationSlug].normal.generate
  val routePath: String                  = routeRepresentation.path.replace("{organizationSlug}", organizationSlug)

  trait NoLoggedTestCase extends BaseScope {
    override lazy val authContextForTest: Option[AuthContext] = None
  }

  trait LoggedTestCase extends BaseScope {
    override lazy val organizationUserAssignService: OrganizationUserAssignService = vmock[OrganizationUserAssignService]

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

      when(organizationUserAssignService.assign(auth, organizationSlug, request.value))
        .thenReturn(io(OrganizationUserAssignResult.OperationNotPermitted))

      TestRequest(routePath, requestJson(request.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(organizationUserAssignService).assign(auth, organizationSlug, request.value)
    }

    "return NotFound when service returns OrganizationNotFound" in new LoggedTestCase {
      val request = routeRepresentation.request.normal

      when(organizationUserAssignService.assign(auth, organizationSlug, request.value))
        .thenReturn(io(OrganizationUserAssignResult.OrganizationNotFound))

      TestRequest(routePath, requestJson(request.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(organizationUserAssignService).assign(auth, organizationSlug, request.value)
    }

    "return NotFound when service returns UserIsNotOrganizationMember" in new LoggedTestCase {
      val request = routeRepresentation.request.normal

      when(organizationUserAssignService.assign(auth, organizationSlug, request.value))
        .thenReturn(io(OrganizationUserAssignResult.UserIsNotOrganizationMember))

      TestRequest(routePath, requestJson(request.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(organizationUserAssignService).assign(auth, organizationSlug, request.value)
    }

    "return BadRequest when service returns InvalidRequest" in new LoggedTestCase {
      val request = routeRepresentation.request.normal

      when(organizationUserAssignService.assign(auth, organizationSlug, request.value))
        .thenReturn(io(OrganizationUserAssignResult.InvalidRequest))

      TestRequest(routePath, requestJson(request.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.BadRequest
      }

      verify(organizationUserAssignService).assign(auth, organizationSlug, request.value)
    }

    "return Conflict when service returns UserAlreadyAssigned" in new LoggedTestCase {
      val request = routeRepresentation.request.normal

      when(organizationUserAssignService.assign(auth, organizationSlug, request.value))
        .thenReturn(io(OrganizationUserAssignResult.UserAlreadyAssigned))

      TestRequest(routePath, requestJson(request.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.Conflict
      }

      verify(organizationUserAssignService).assign(auth, organizationSlug, request.value)
    }

    "return OK otherwise" in new LoggedTestCase {
      val request = routeRepresentation.request.normal

      when(organizationUserAssignService.assign(auth, organizationSlug, request.value))
        .thenReturn(io(OrganizationUserAssignResult.Assigned))

      TestRequest(routePath, requestJson(request.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.OK
      }

      verify(organizationUserAssignService).assign(auth, organizationSlug, request.value)
    }
  }
}
