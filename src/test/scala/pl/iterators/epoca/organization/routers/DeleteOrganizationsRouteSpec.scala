package pl.iterators.epoca.organization.routers

import org.apache.pekko.http.scaladsl.model.StatusCodes
import pl.iterators.baklava.core.model.{RouteRepresentation, RouteSecurity}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.organization.domain.Organization
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.organization.services.OrganizationDeleteService
import pl.iterators.epoca.organization.services.OrganizationDeleteService.OrganizationDeleteResult
import pl.iterators.epoca.utils.RouteDocSpec

class DeleteOrganizationsRouteSpec extends RouteDocSpec {
  override val routeRepresentation: RouteRepresentation[Unit, Organization] =
    RouteRepresentation[Unit, Organization](
      "Delete organization",
      "DELETE",
      "/organizations/{organizationSlug}",
      authentication = List(RouteSecurity.Bearer())
    )

  val auth: AuthContext                  = allGenerators[AuthContext].normal.generate
  val organizationSlug: OrganizationSlug = allGenerators[OrganizationSlug].normal.generate
  val routePath: String = routeRepresentation.path
    .replace("{organizationSlug}", organizationSlug)

  trait NoLoggedTestCase extends BaseScope {
    override lazy val authContextForTest: Option[AuthContext] = None
  }

  trait LoggedTestCase extends BaseScope {
    override lazy val organizationDeleteService: OrganizationDeleteService = vmock[OrganizationDeleteService]

    override val authContextForTest: Option[AuthContext] = Some(auth)
  }

  routeRepresentation.name should {
    "be inaccessible for no-logged user" in new NoLoggedTestCase {
      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldBeInaccessible
      }
    }

    "return NotFound when service returns OperationNotPermitted" in new LoggedTestCase {
      when(organizationDeleteService.delete(auth, organizationSlug))
        .thenReturn(io(OrganizationDeleteResult.OperationNotPermitted))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(organizationDeleteService).delete(auth, organizationSlug)
    }

    "return NotFound when service returns OrganizationNotFound" in new LoggedTestCase {
      when(organizationDeleteService.delete(auth, organizationSlug))
        .thenReturn(io(OrganizationDeleteResult.OrganizationNotFound))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(organizationDeleteService).delete(auth, organizationSlug)
    }

    "return BadRequest when service returns OrganizationHasUsers" in new LoggedTestCase {

      when(organizationDeleteService.delete(auth, organizationSlug))
        .thenReturn(io(OrganizationDeleteResult.OrganizationHasUsers))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.BadRequest
      }

      verify(organizationDeleteService).delete(auth, organizationSlug)
    }

    "return NoContent otherwise" in new LoggedTestCase {
      when(organizationDeleteService.delete(auth, organizationSlug))
        .thenReturn(io(OrganizationDeleteResult.Deleted))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NoContent
      }

      verify(organizationDeleteService).delete(auth, organizationSlug)
    }
  }
}
