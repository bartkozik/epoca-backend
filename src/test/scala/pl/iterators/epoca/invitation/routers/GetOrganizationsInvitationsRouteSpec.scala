package pl.iterators.epoca.invitation.routers

import org.apache.pekko.http.scaladsl.model.StatusCodes
import pl.iterators.baklava.core.model.{RouteParameterRepresentation, RouteRepresentation, RouteSecurity}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.invitation.domain.Invitation
import pl.iterators.epoca.invitation.domain.InvitationDomain.InvitationId
import pl.iterators.epoca.invitation.repositories.InvitationRepository.InvitationFilters
import pl.iterators.epoca.invitation.services.OrganizationInvitationListService
import pl.iterators.epoca.invitation.services.OrganizationInvitationListService.OrganizationInvitationListResult
import pl.iterators.epoca.organization.domain.OrganizationDomain
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.utils.db.{PageSizeAndNumber, PaginatedResult}
import pl.iterators.epoca.utils.{AuthContextProvider, RouteDocSpec}

import java.util.UUID
import scala.util.Random

class GetOrganizationsInvitationsRouteSpec extends RouteDocSpec {
  override val routeRepresentation: RouteRepresentation[Unit, PaginatedResult[Invitation]] =
    RouteRepresentation[Unit, PaginatedResult[Invitation]](
      "List invitations",
      "GET",
      "/organizations/{organizationSlug}/invitations",
      List(
        RouteParameterRepresentation[UUID]("id", false, UUID.randomUUID(), _.toString),
        RouteParameterRepresentation[Int]("pageSize", false, Random.nextInt(100) + 1, _.toString),
        RouteParameterRepresentation[Int]("pageNumber", false, Random.nextInt(100) + 1, _.toString)
      ),
      authentication = List(RouteSecurity.Bearer())
    )

  val auth: AuthContext                                     = AuthContextProvider.user
  val organizationSlug: OrganizationDomain.OrganizationSlug = allGenerators[OrganizationSlug].normal.generate
  val routePath: String                                     = routeRepresentation.path.replace("{organizationSlug}", organizationSlug)
  val routePathWithParams: String =
    routeRepresentation.routePathWithAllParameters.replace("{organizationSlug}", organizationSlug.toString)

  trait NoLoggedTestCase extends BaseScope {
    override lazy val authContextForTest: Option[AuthContext] = None
  }

  trait LoggedTestCase extends BaseScope {
    override lazy val organizationInvitationListService: OrganizationInvitationListService = vmock[OrganizationInvitationListService]

    override val authContextForTest: Option[AuthContext] = Some(auth)
  }

  routeRepresentation.name should {
    "be inaccessible for no-logged user" in new NoLoggedTestCase {
      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldBeInaccessible
      }
    }
    "return NotFound when service returns OrganizationNotFound" in new LoggedTestCase {
      val filter = InvitationFilters()
      when(organizationInvitationListService.list(auth, organizationSlug, filter))
        .thenReturn(io(OrganizationInvitationListResult.OrganizationNotFound))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(organizationInvitationListService).list(auth, organizationSlug, filter)
    }

    "return NotFound when service returns OperationNotPermitted" in new LoggedTestCase {
      val filter = InvitationFilters()
      when(organizationInvitationListService.list(auth, organizationSlug, filter))
        .thenReturn(io(OrganizationInvitationListResult.OperationNotPermitted))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(organizationInvitationListService).list(auth, organizationSlug, filter)
    }

    "return Ok with service data otherwise" in new LoggedTestCase {
      val responseObject = routeRepresentation.response.random
      val filter         = InvitationFilters()
      when(organizationInvitationListService.list(auth, organizationSlug, filter))
        .thenReturn(io(OrganizationInvitationListResult.Ok(responseObject.value)))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.OK
        responseAs[Json] shouldEqualJson responseObject.getJsonString
      }

      verify(organizationInvitationListService).list(auth, organizationSlug, filter)
    }

    "return Ok with service data when all optional params provided" in new LoggedTestCase {
      val responseObject = routeRepresentation.response.random
      val filter = InvitationFilters(
        id = routeRepresentation.getParamValue[InvitationId]("id"),
        pageSizeAndNumber =
          PageSizeAndNumber.fromOptions(routeRepresentation.getParamValue[Int]("pageSize"), routeRepresentation.getParamValue[Int]("pageNumber"))
      )
      when(organizationInvitationListService.list(auth, organizationSlug, filter))
        .thenReturn(io(OrganizationInvitationListResult.Ok(responseObject.value)))

      TestRequest(routePathWithParams, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.OK
        responseAs[Json] shouldEqualJson responseObject.getJsonString
      }

      verify(organizationInvitationListService).list(auth, organizationSlug, filter)
    }
  }
}
