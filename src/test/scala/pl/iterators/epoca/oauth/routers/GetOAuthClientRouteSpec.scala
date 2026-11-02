package pl.iterators.epoca.oauth.routers

import io.circe.syntax._
import org.apache.pekko.http.scaladsl.model.StatusCodes
import pl.iterators.baklava.core.model.{RouteRepresentation, RouteSecurity}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.oauth.domain.OAuthDomain.{OAuthClientEffectiveStatus, OAuthClientPublicId}
import pl.iterators.epoca.oauth.services.OAuthClientManagementService
import pl.iterators.epoca.oauth.services.OAuthClientManagementService.OAuthClientManagementResult.Success
import pl.iterators.epoca.oauth.services.OAuthClientManagementService.OAuthClientView
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.utils.RouteDocSpec

class GetOAuthClientRouteSpec extends RouteDocSpec {
  override val routeRepresentation: RouteRepresentation[Unit, OAuthClientView] =
    RouteRepresentation[Unit, OAuthClientView](
      "Get one OAuth client without secret material.",
      "GET",
      "/organizations/{organizationSlug}/oauth-clients/{clientId}",
      authentication = List(RouteSecurity.Bearer())
    )

  private val auth             = allGenerators[AuthContext].normal.generate
  private val organizationSlug = allGenerators[OrganizationSlug].normal.generate
  private val clientId         = allGenerators[OAuthClientPublicId].normal.generate
  private val routePath        = routeRepresentation.path.replace("{organizationSlug}", organizationSlug).replace("{clientId}", clientId.toString)

  trait LoggedTestCase extends BaseScope {
    override lazy val oauthClientManagementService: OAuthClientManagementService = vmock[OAuthClientManagementService]
    override protected def oauthManagementEnabled: Boolean                       = true
    override val authContextForTest: Option[AuthContext]                         = Some(auth)
  }

  routeRepresentation.name should {
    "return Ok with an expired public view and no secret material" in new LoggedTestCase {
      val responseObject = routeRepresentation.response.random
      val expiredView    = responseObject.value.copy(status = OAuthClientEffectiveStatus.Expired)
      when(oauthClientManagementService.get(auth, organizationSlug, clientId)).thenReturn(io(Success(expiredView)))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.OK
        responseAs[Json] shouldEqual expiredView.asJson
        responseAs[Json].noSpaces.contains("clientSecret") must beFalse
        responseAs[Json].noSpaces.contains("secretHash") must beFalse
      }

      verify(oauthClientManagementService).get(auth, organizationSlug, clientId)
    }
  }
}
