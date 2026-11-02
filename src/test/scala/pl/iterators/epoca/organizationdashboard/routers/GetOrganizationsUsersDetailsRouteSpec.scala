package pl.iterators.epoca.organizationdashboard.routers

import org.apache.pekko.http.scaladsl.model.StatusCodes
import pl.iterators.baklava.core.model.{RouteRepresentation, RouteSecurity}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.organizationdashboard.services.OrganizationUserDetailsService
import pl.iterators.epoca.organizationdashboard.services.OrganizationUserDetailsService.{OrganizationUserDetailsResult, OrganizationUserDetailsRow}
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.utils.RouteDocSpec

class GetOrganizationsUsersDetailsRouteSpec extends RouteDocSpec {
  override val routeRepresentation: RouteRepresentation[Unit, Seq[OrganizationUserDetailsRow]] =
    RouteRepresentation[Unit, Seq[OrganizationUserDetailsRow]](
      "List projects in a given organization that belongs to a requested user",
      "GET",
      "/organizations/{slug}/users/{userId}/details",
      authentication = List(RouteSecurity.Bearer())
    )

  val auth: AuthContext      = allGenerators[AuthContext].normal.generate
  val slug: OrganizationSlug = allGenerators[OrganizationSlug].normal.generate
  val userId: UserId         = allGenerators[UserId].normal.generate
  val routePath: String = routeRepresentation.path
    .replace("{slug}", slug)
    .replace("{userId}", userId.toString)

  trait NoLoggedTestCase extends BaseScope {
    override lazy val authContextForTest: Option[AuthContext] = None
  }

  trait LoggedTestCase extends BaseScope {
    override lazy val organizationUserDetailsService: OrganizationUserDetailsService = vmock[OrganizationUserDetailsService]

    override val authContextForTest: Option[AuthContext] = Some(auth)
  }

  routeRepresentation.name should {
    "be inaccessible for no-logged user" in new NoLoggedTestCase {
      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldBeInaccessible
      }
    }
    "return NotFound if service returns OrganizationNotFound" in new LoggedTestCase {
      when(organizationUserDetailsService.details(auth, slug, userId))
        .thenReturn(io(OrganizationUserDetailsResult.OrganizationNotFound))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(organizationUserDetailsService).details(auth, slug, userId)
    }
    "return NotFound if service returns OrganizationNoAccess" in new LoggedTestCase {
      when(organizationUserDetailsService.details(auth, slug, userId))
        .thenReturn(io(OrganizationUserDetailsResult.OrganizationNoAccess))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(organizationUserDetailsService).details(auth, slug, userId)
    }
    "return NotFound if service returns NotAMember" in new LoggedTestCase {
      when(organizationUserDetailsService.details(auth, slug, userId))
        .thenReturn(io(OrganizationUserDetailsResult.NotAMember))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(organizationUserDetailsService).details(auth, slug, userId)
    }
    "return Ok with service data otherwise" in new LoggedTestCase {
      val responseObject = routeRepresentation.response.random
      when(organizationUserDetailsService.details(auth, slug, userId))
        .thenReturn(io(OrganizationUserDetailsResult.Ok(responseObject.value)))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.OK
        responseAs[Json] shouldEqualJson responseObject.getJsonString
      }

      verify(organizationUserDetailsService).details(auth, slug, userId)
    }
  }
}
