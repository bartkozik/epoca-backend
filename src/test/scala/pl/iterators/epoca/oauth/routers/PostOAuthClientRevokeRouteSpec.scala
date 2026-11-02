package pl.iterators.epoca.oauth.routers

import org.apache.pekko.http.scaladsl.model.StatusCodes
import pl.iterators.baklava.core.model.{RouteRepresentation, RouteSecurity}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.oauth.domain.OAuthDomain.{OAuthAuditCorrelationId, OAuthClientPublicId}
import pl.iterators.epoca.oauth.services.OAuthClientManagementService
import pl.iterators.epoca.oauth.services.OAuthClientManagementService.OAuthClientManagementResult.{NotActive, Success}
import pl.iterators.epoca.oauth.services.OAuthClientManagementService.{OAuthClientVersionRequest, OAuthClientView}
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.utils.RouteDocSpec

class PostOAuthClientRevokeRouteSpec extends RouteDocSpec {
  import OAuthClientRouteSpecSupport._

  override val routeRepresentation: RouteRepresentation[OAuthClientVersionRequest, OAuthClientView] =
    RouteRepresentation[OAuthClientVersionRequest, OAuthClientView](
      "Revoke an OAuth client using optimistic concurrency.",
      "POST",
      "/organizations/{organizationSlug}/oauth-clients/{clientId}/revoke",
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
    "return Ok with only the revoked public view" in new LoggedTestCase {
      val requestObject  = routeRepresentation.request.random
      val responseObject = routeRepresentation.response.random
      val correlationId  = Some(OAuthAuditCorrelationId(CorrelationId))
      when(oauthClientManagementService.revoke(auth, organizationSlug, clientId, requestObject.value, correlationId))
        .thenReturn(io(Success(responseObject.value)))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> addHeader("Correlation-Id", CorrelationId) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.OK
        responseAs[Json] shouldEqualJson responseObject.getJsonString
        responseAs[Json].noSpaces.contains("clientSecret") must beFalse
        responseAs[Json].noSpaces.contains("secretHash") must beFalse
      }

      verify(oauthClientManagementService).revoke(auth, organizationSlug, clientId, requestObject.value, correlationId)
    }

    "return the stable not-active problem when revoke is terminal" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random
      val correlationId = Some(OAuthAuditCorrelationId(CorrelationId))
      when(oauthClientManagementService.revoke(auth, organizationSlug, clientId, requestObject.value, correlationId))
        .thenReturn(io(NotActive))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> addHeader("Correlation-Id", CorrelationId) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.Conflict
        responseAs[Json] shouldEqual problem(StatusCodes.Conflict, "not-active", "OAuth client is not active")
      }

      verify(oauthClientManagementService).revoke(auth, organizationSlug, clientId, requestObject.value, correlationId)
    }
  }
}
