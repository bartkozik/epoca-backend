package pl.iterators.epoca.oauth.routers

import org.apache.pekko.http.scaladsl.model.StatusCodes
import pl.iterators.baklava.core.model.{RouteParameterRepresentation, RouteRepresentation, RouteSecurity}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.oauth.domain.OAuthDomain.OAuthClientEffectiveStatus
import pl.iterators.epoca.oauth.services.OAuthClientManagementService
import pl.iterators.epoca.oauth.services.OAuthClientManagementService.OAuthClientManagementResult.Success
import pl.iterators.epoca.oauth.services.OAuthClientManagementService.OAuthClientView
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.utils.RouteDocSpec
import pl.iterators.epoca.utils.db.{PageSizeAndNumber, PaginatedResult}

class GetOAuthClientsRouteSpec extends RouteDocSpec {
  override val routeRepresentation: RouteRepresentation[Unit, PaginatedResult[OAuthClientView]] =
    RouteRepresentation[Unit, PaginatedResult[OAuthClientView]](
      "List OAuth clients with status and pagination filters.",
      "GET",
      "/organizations/{organizationSlug}/oauth-clients",
      List(
        RouteParameterRepresentation[String]("status", false, OAuthClientEffectiveStatus.Active.entryName, identity),
        RouteParameterRepresentation[Int]("pageSize", false, 20, _.toString),
        RouteParameterRepresentation[Int]("pageNumber", false, 1, _.toString)
      ),
      authentication = List(RouteSecurity.Bearer())
    )

  private val auth             = allGenerators[AuthContext].normal.generate
  private val organizationSlug = allGenerators[OrganizationSlug].normal.generate
  private val routePath        = routeRepresentation.routePathWithAllParameters.replace("{organizationSlug}", organizationSlug.toString)

  trait LoggedTestCase extends BaseScope {
    override lazy val oauthClientManagementService: OAuthClientManagementService = vmock[OAuthClientManagementService]
    override protected def oauthManagementEnabled: Boolean                       = true
    override val authContextForTest: Option[AuthContext]                         = Some(auth)
  }

  routeRepresentation.name should {
    "return Ok with the public paginated view and forward all filters" in new LoggedTestCase {
      val responseObject = routeRepresentation.response.random
      val pagination     = Some(PageSizeAndNumber(20, 1))
      when(oauthClientManagementService.list(auth, organizationSlug, Some(OAuthClientEffectiveStatus.Active), pagination))
        .thenReturn(io(Success(responseObject.value)))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.OK
        responseAs[Json] shouldEqualJson responseObject.getJsonString
        responseAs[Json].noSpaces.contains("clientSecret") must beFalse
        responseAs[Json].noSpaces.contains("secretHash") must beFalse
      }

      verify(oauthClientManagementService).list(auth, organizationSlug, Some(OAuthClientEffectiveStatus.Active), pagination)
    }

    "accept Expired as an effective lifecycle filter" in new LoggedTestCase {
      val responseObject = routeRepresentation.response.random
      val pagination     = Some(PageSizeAndNumber(20, 1))
      when(oauthClientManagementService.list(auth, organizationSlug, Some(OAuthClientEffectiveStatus.Expired), pagination))
        .thenReturn(io(Success(responseObject.value)))

      TestRequest(s"/organizations/$organizationSlug/oauth-clients?status=Expired&pageSize=20&pageNumber=1", emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.OK
        responseAs[Json] shouldEqualJson responseObject.getJsonString
      }

      verify(oauthClientManagementService).list(auth, organizationSlug, Some(OAuthClientEffectiveStatus.Expired), pagination)
    }

    "accept Revoked as an effective lifecycle filter" in new LoggedTestCase {
      val responseObject = routeRepresentation.response.random
      val pagination     = Some(PageSizeAndNumber(20, 1))
      when(oauthClientManagementService.list(auth, organizationSlug, Some(OAuthClientEffectiveStatus.Revoked), pagination))
        .thenReturn(io(Success(responseObject.value)))

      TestRequest(s"/organizations/$organizationSlug/oauth-clients?status=Revoked&pageSize=20&pageNumber=1", emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.OK
        responseAs[Json] shouldEqualJson responseObject.getJsonString
      }

      verify(oauthClientManagementService).list(auth, organizationSlug, Some(OAuthClientEffectiveStatus.Revoked), pagination)
    }
  }
}
