package pl.iterators.epoca.organization.routers

import org.apache.pekko.http.scaladsl.model.StatusCodes
import pl.iterators.baklava.core.model.{RouteRepresentation, RouteSecurity}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.organization.domain.Organization
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.organization.services.OrganizationUpdateService
import pl.iterators.epoca.organization.services.OrganizationUpdateService.{OrganizationUpdateRequest, OrganizationUpdateResult}
import pl.iterators.epoca.utils.RouteDocSpec

class PatchOrganizationsRouteSpec extends RouteDocSpec {
  override val routeRepresentation: RouteRepresentation[OrganizationUpdateRequest, Organization] =
    RouteRepresentation[OrganizationUpdateRequest, Organization](
      "Update organization",
      "PATCH",
      "/organizations/{organizationSlug}",
      authentication = List(RouteSecurity.Bearer())
    )

  val auth: AuthContext                  = allGenerators[AuthContext].normal.generate
  val organizationSlug: OrganizationSlug = allGenerators[OrganizationSlug].normal.generate
  val routePath: String                  = routeRepresentation.path.replace("{organizationSlug}", organizationSlug)

  trait NoLoggedTestCase extends BaseScope {
    override lazy val authContextForTest: Option[AuthContext] = None
  }

  trait LoggedTestCase extends BaseScope {
    override lazy val organizationUpdateService: OrganizationUpdateService = vmock[OrganizationUpdateService]

    override val authContextForTest: Option[AuthContext] = Some(auth)
  }

  routeRepresentation.name should {
    "be inaccessible for no-logged user" in new NoLoggedTestCase {
      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldBeInaccessible
      }
    }

    "return BadRequest when incoming request is not json" in new LoggedTestCase {
      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.BadRequest
      }
    }

    "return NotFound when service returns OperationNotPermitted" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random

      when(organizationUpdateService.update(auth, organizationSlug, requestObject.value))
        .thenReturn(io(OrganizationUpdateResult.OperationNotPermitted))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(organizationUpdateService).update(auth, organizationSlug, requestObject.value)
    }

    "return NotFound when service returns OrganizationNotFound" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random

      when(organizationUpdateService.update(auth, organizationSlug, requestObject.value))
        .thenReturn(io(OrganizationUpdateResult.OrganizationNotFound))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(organizationUpdateService).update(auth, organizationSlug, requestObject.value)
    }

    "return OK with service data otherwise" in new LoggedTestCase {
      val requestObject  = routeRepresentation.request.random
      val responseObject = routeRepresentation.response.random
      when(organizationUpdateService.update(auth, organizationSlug, requestObject.value))
        .thenReturn(io(OrganizationUpdateResult.Updated(responseObject.value)))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.OK
        responseAs[Json] shouldEqualJson responseObject.getJsonString
      }

      verify(organizationUpdateService).update(auth, organizationSlug, requestObject.value)
    }
  }
}
