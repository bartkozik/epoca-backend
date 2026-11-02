package pl.iterators.epoca.invitation.routers

import org.apache.pekko.http.scaladsl.model.StatusCodes
import pl.iterators.baklava.core.model.{RouteRepresentation, RouteSecurity}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.invitation.domain.Invitation
import pl.iterators.epoca.invitation.services.InvitationListService
import pl.iterators.epoca.invitation.services.InvitationListService.InvitationListResult
import pl.iterators.epoca.organization.domain.OrganizationDomain
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.utils.db.PaginatedResult
import pl.iterators.epoca.utils.{AuthContextProvider, RouteDocSpec}

class GetInvitationsRouteSpec extends RouteDocSpec {
  override val routeRepresentation: RouteRepresentation[Unit, PaginatedResult[Invitation]] =
    RouteRepresentation[Unit, PaginatedResult[Invitation]]("List invitations", "GET", "/invitations", authentication = List(RouteSecurity.Bearer()))

  val auth: AuthContext                                     = AuthContextProvider.user
  val organizationSlug: OrganizationDomain.OrganizationSlug = allGenerators[OrganizationSlug].normal.generate
  val routePath: String                                     = routeRepresentation.path.replace("{organizationSlug}", organizationSlug)
  val routePathWithParams: String =
    routeRepresentation.routePathWithAllParameters.replace("{organizationSlug}", organizationSlug)

  trait NoLoggedTestCase extends BaseScope {
    override lazy val authContextForTest: Option[AuthContext] = None
  }

  trait LoggedTestCase extends BaseScope {
    override lazy val invitationListService: InvitationListService = vmock[InvitationListService]

    override val authContextForTest: Option[AuthContext] = Some(auth)
  }

  routeRepresentation.name should {
    "be inaccessible for no-logged user" in new NoLoggedTestCase {
      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldBeInaccessible
      }
    }

    "return Ok with service data otherwise" in new LoggedTestCase {
      val responseObject = routeRepresentation.response.random
      when(invitationListService.list(auth))
        .thenReturn(io(InvitationListResult.Ok(responseObject.value)))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.OK
        responseAs[Json] shouldEqualJson responseObject.getJsonString
      }

      verify(invitationListService).list(auth)
    }
  }
}
