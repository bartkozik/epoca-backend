package pl.iterators.epoca.organization.routers

import org.apache.pekko.http.scaladsl.model.StatusCodes
import pl.iterators.baklava.core.model.{RouteRepresentation, RouteSecurity}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.organization.domain.{Organization, OrganizationDomain}
import pl.iterators.epoca.organization.services.OrganizationFindService
import pl.iterators.epoca.organization.services.OrganizationFindService.OrganizationFindResult
import pl.iterators.epoca.utils.RouteDocSpec

class GetOrganizationSlugRouteSpec extends RouteDocSpec {
  override val routeRepresentation: RouteRepresentation[Unit, Organization] = RouteRepresentation[Unit, Organization](
    "Find organization by slug - admin has access to all organization, regular user can only find member organizations",
    "GET",
    "/organizations/{slug}",
    authentication = List(RouteSecurity.Bearer())
  )

  val auth: AuthContext                         = allGenerators[AuthContext].normal.generate
  val slug: OrganizationDomain.OrganizationSlug = allGenerators[OrganizationSlug].normal.generate
  val routePath: String                         = routeRepresentation.path.replace("{slug}", slug)

  trait NoLoggedTestCase extends BaseScope {
    override lazy val authContextForTest: Option[AuthContext] = None
  }

  trait LoggedTestCase extends BaseScope {
    override lazy val organizationFindService: OrganizationFindService = vmock[OrganizationFindService]

    override val authContextForTest: Option[AuthContext] = Some(auth)
  }

  routeRepresentation.name should {
    "be inaccessible for no-logged user" in new NoLoggedTestCase {
      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldBeInaccessible
      }
    }

    "return NotFound if service returns NotFound" in new LoggedTestCase {
      when(organizationFindService.find(auth, slug))
        .thenReturn(io(OrganizationFindResult.NotFound))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(organizationFindService).find(auth, slug)
    }

    "return Ok with service data otherwise" in new LoggedTestCase {
      val responseObject = routeRepresentation.response.random
      when(organizationFindService.find(auth, slug))
        .thenReturn(io(OrganizationFindResult.Ok(responseObject.value)))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.OK
        responseAs[Json] shouldEqualJson responseObject.getJsonString
      }

      verify(organizationFindService).find(auth, slug)
    }
  }
}
