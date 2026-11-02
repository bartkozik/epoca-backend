package pl.iterators.epoca.invitation.routers

import org.apache.pekko.http.scaladsl.model.StatusCodes
import pl.iterators.baklava.core.model.{RouteRepresentation, RouteSecurity}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.invitation.domain.InvitationDomain.InvitationId
import pl.iterators.epoca.invitation.domain.{Invitation, InvitationDomain}
import pl.iterators.epoca.invitation.services.OrganizationInvitationDeleteService
import pl.iterators.epoca.invitation.services.OrganizationInvitationDeleteService.OrganizationInvitationDeleteResult
import pl.iterators.epoca.organization.domain.OrganizationDomain
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.utils.{AuthContextProvider, RouteDocSpec}

class DeleteOrganizationsInvitationsRouteSpec extends RouteDocSpec {
  override val routeRepresentation: RouteRepresentation[Unit, Invitation] =
    RouteRepresentation[Unit, Invitation](
      "Delete invitation",
      "DELETE",
      "/organizations/{organizationSlug}/invitations/{id}",
      authentication = List(RouteSecurity.Bearer())
    )

  val auth: AuthContext                                     = AuthContextProvider.user
  val organizationSlug: OrganizationDomain.OrganizationSlug = allGenerators[OrganizationSlug].normal.generate
  val invitationId: InvitationDomain.InvitationId           = allGenerators[InvitationId].normal.generate
  val routePath: String =
    routeRepresentation.path.replace("{organizationSlug}", organizationSlug).replace("{id}", invitationId.toString)

  trait NoLoggedTestCase extends BaseScope {
    override lazy val authContextForTest: Option[AuthContext] = None
  }

  trait LoggedTestCase extends BaseScope {
    override lazy val organizationInvitationDeleteService: OrganizationInvitationDeleteService = vmock[OrganizationInvitationDeleteService]

    override val authContextForTest: Option[AuthContext] = Some(auth)
  }

  routeRepresentation.name should {
    "be inaccessible for no-logged user" in new NoLoggedTestCase {
      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldBeInaccessible
      }
    }

    "return NotFound when service returns OrganizationNotFound" in new LoggedTestCase {
      when(organizationInvitationDeleteService.delete(auth, organizationSlug, invitationId))
        .thenReturn(io(OrganizationInvitationDeleteResult.OrganizationNotFound))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(organizationInvitationDeleteService).delete(auth, organizationSlug, invitationId)
    }

    "return NotFound when service returns OperationNotPermitted" in new LoggedTestCase {
      when(organizationInvitationDeleteService.delete(auth, organizationSlug, invitationId))
        .thenReturn(io(OrganizationInvitationDeleteResult.OperationNotPermitted))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(organizationInvitationDeleteService).delete(auth, organizationSlug, invitationId)
    }

    "return NotFound when service returns InvitationNotFound" in new LoggedTestCase {
      when(organizationInvitationDeleteService.delete(auth, organizationSlug, invitationId))
        .thenReturn(io(OrganizationInvitationDeleteResult.InvitationNotFound))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(organizationInvitationDeleteService).delete(auth, organizationSlug, invitationId)
    }

    "return Conflict when invitation is already terminal" in new LoggedTestCase {
      when(organizationInvitationDeleteService.delete(auth, organizationSlug, invitationId))
        .thenReturn(io(OrganizationInvitationDeleteResult.InvitationNotPending))
      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.Conflict
      }
      verify(organizationInvitationDeleteService).delete(auth, organizationSlug, invitationId)
    }

    "return NoContent otherwise" in new LoggedTestCase {
      when(organizationInvitationDeleteService.delete(auth, organizationSlug, invitationId))
        .thenReturn(io(OrganizationInvitationDeleteResult.Deleted))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NoContent
      }

      verify(organizationInvitationDeleteService).delete(auth, organizationSlug, invitationId)
    }
  }
}
