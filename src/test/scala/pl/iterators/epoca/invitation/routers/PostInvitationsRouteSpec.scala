package pl.iterators.epoca.invitation.routers

import org.apache.pekko.http.scaladsl.model.StatusCodes
import pl.iterators.baklava.core.model.{RouteRepresentation, RouteSecurity}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.invitation.services.InvitationAcceptService
import pl.iterators.epoca.invitation.services.InvitationAcceptService.{InvitationAcceptRequest, InvitationAcceptResult}
import pl.iterators.epoca.utils.{AuthContextProvider, RouteDocSpec}

class PostInvitationsRouteSpec extends RouteDocSpec {
  override val routeRepresentation: RouteRepresentation[InvitationAcceptRequest, Unit] =
    RouteRepresentation[InvitationAcceptRequest, Unit](
      "Accept or reject invitation",
      "POST",
      "/invitations",
      authentication = List(RouteSecurity.Bearer())
    )

  val auth: AuthContext = AuthContextProvider.user
  val routePath: String = routeRepresentation.path
  val routePathWithParams: String =
    routeRepresentation.routePathWithAllParameters

  trait NoLoggedTestCase extends BaseScope {
    override lazy val authContextForTest: Option[AuthContext] = None
  }

  trait LoggedTestCase extends BaseScope {
    override lazy val invitationAcceptService: InvitationAcceptService = vmock[InvitationAcceptService]

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

    "return NotFound if service returns InvitationNotFound" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random

      when(invitationAcceptService.accept(auth, requestObject.value))
        .thenReturn(io(InvitationAcceptResult.InvitationNotFound))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(invitationAcceptService).accept(auth, requestObject.value)
    }

    "return Ok if service returns Accepted" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random

      when(invitationAcceptService.accept(auth, requestObject.value))
        .thenReturn(io(InvitationAcceptResult.Done))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.OK
      }

      verify(invitationAcceptService).accept(auth, requestObject.value)
    }
  }
}
