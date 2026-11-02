package pl.iterators.epoca.invitation.routers

import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshaller._
import pl.iterators.baklava.core.model.{RouteRepresentation, RouteSecurity}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.invitation.domain.Invitation
import pl.iterators.epoca.invitation.services.OrganizationInvitationCreateService
import pl.iterators.epoca.invitation.services.OrganizationInvitationCreateService.{
  OrganizationInvitationCreateRequest,
  OrganizationInvitationCreateResult
}
import pl.iterators.epoca.organization.domain.OrganizationDomain
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.utils.{AuthContextProvider, RouteDocSpec}

class PostOrganizationsInvitationsRouteSpec extends RouteDocSpec {
  override val routeRepresentation: RouteRepresentation[OrganizationInvitationCreateRequest, Invitation] =
    RouteRepresentation[OrganizationInvitationCreateRequest, Invitation](
      "Create new invitation",
      "POST",
      "/organizations/{organizationSlug}/invitations",
      authentication = List(RouteSecurity.Bearer())
    )

  val auth: AuthContext                                     = AuthContextProvider.user
  val organizationSlug: OrganizationDomain.OrganizationSlug = allGenerators[OrganizationSlug].normal.generate
  val routePath: String                                     = routeRepresentation.path.replace("{organizationSlug}", organizationSlug)

  trait NoLoggedTestCase extends BaseScope {
    override lazy val authContextForTest: Option[AuthContext] = None
  }

  trait LoggedTestCase extends BaseScope {
    override lazy val organizationInvitationCreateService: OrganizationInvitationCreateService = vmock[OrganizationInvitationCreateService]

    override val authContextForTest: Option[AuthContext] = Some(auth)
  }

  routeRepresentation.name should {
    "be inaccessible for no-logged user" in new NoLoggedTestCase {
      TestRequest(routePath) ~> allRoutes ~> check {
        response.status shouldBeInaccessible
      }
    }

    "return BadRequest when incoming request is not json" in new LoggedTestCase {
      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.BadRequest
      }
    }
    "return NotFound when service returns OrganizationNotFound" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random
      when(organizationInvitationCreateService.create(auth, organizationSlug, requestObject.value))
        .thenReturn(io(OrganizationInvitationCreateResult.OrganizationNotFound))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(organizationInvitationCreateService).create(auth, organizationSlug, requestObject.value)
    }

    "return BadRequest when service returns NoAccessToOrganization" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random
      when(organizationInvitationCreateService.create(auth, organizationSlug, requestObject.value))
        .thenReturn(io(OrganizationInvitationCreateResult.InvalidRequest))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.BadRequest
      }

      verify(organizationInvitationCreateService).create(auth, organizationSlug, requestObject.value)
    }

    "return Conflict when service returns AlreadyMember" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random
      when(organizationInvitationCreateService.create(auth, organizationSlug, requestObject.value))
        .thenReturn(io(OrganizationInvitationCreateResult.AlreadyMember))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.Conflict
      }

      verify(organizationInvitationCreateService).create(auth, organizationSlug, requestObject.value)
    }

    "return NotFound when service returns NoAccessToOrganization" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random
      when(organizationInvitationCreateService.create(auth, organizationSlug, requestObject.value))
        .thenReturn(io(OrganizationInvitationCreateResult.OperationNotPermitted))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(organizationInvitationCreateService).create(auth, organizationSlug, requestObject.value)
    }

    Seq(OrganizationInvitationCreateResult.InvitationAlreadyExists, OrganizationInvitationCreateResult.IdentityConflict).foreach { result =>
      s"return Conflict for $result" in new LoggedTestCase {
        val requestObject = routeRepresentation.request.random
        when(organizationInvitationCreateService.create(auth, organizationSlug, requestObject.value)).thenReturn(io(result))
        TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
          response.status shouldEqual StatusCodes.Conflict
        }
        verify(organizationInvitationCreateService).create(auth, organizationSlug, requestObject.value)
      }
    }

    "return Created with service data otherwise" in new LoggedTestCase {
      val requestObject  = routeRepresentation.request.random
      val responseObject = routeRepresentation.response.random
      when(organizationInvitationCreateService.create(auth, organizationSlug, requestObject.value))
        .thenReturn(io(OrganizationInvitationCreateResult.Created(responseObject.value)))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.Created
        responseAs[Json] shouldEqualJson responseObject.getJsonString
      }

      verify(organizationInvitationCreateService).create(auth, organizationSlug, requestObject.value)
    }
  }
}
