package pl.iterators.epoca.saml.routers

import com.typesafe.config.ConfigFactory
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.mockito.Mockito.verifyNoInteractions
import pl.iterators.baklava.core.model.RouteRepresentation
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.auth.domain.AuthDomain.DeviceId
import pl.iterators.epoca.auth.routers.AuthRouter.LoginHttpRequestContext
import pl.iterators.epoca.auth.services.LoginResolveService.LoginResolution
import pl.iterators.epoca.invitation.services.InvitationSecretService
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.saml.services.SamlLoginService.{SamlLoginRequest, SamlLoginResult}
import pl.iterators.epoca.saml.services.{SamlBrowserPolicy, SamlLoginService}
import pl.iterators.epoca.utils.RouteDocSpec
import pl.iterators.epoca.utils.http.directives.ExtractDeviceIdDirective

class PostSamlLoginRouteSpec extends RouteDocSpec {
  override val routeRepresentation: RouteRepresentation[SamlLoginRequest, LoginResolution] =
    RouteRepresentation[SamlLoginRequest, LoginResolution](
      "Exchange a browser-bound SAML callback",
      "POST",
      "/organizations/{organizationSlug}/saml/login"
    )
  val orgSlug: OrganizationSlug = OrganizationSlug("target-org")
  val routePath: String         = routeRepresentation.path.replace("{organizationSlug}", orgSlug)
  val deviceId: DeviceId        = DeviceId("device")
  def callbackHeaders: RequestTransformer = addHeader(ExtractDeviceIdDirective.extractDeviceIdHeaderName, deviceId) ~>
    addHeader("Origin", "https://app.example.test") ~> addHeader("Cookie", "epoca_saml_nonce=nonce")
  trait TestCase extends BaseScope {
    override lazy val authContextForTest: Option[AuthContext] = None
    override lazy val samlBrowserPolicy: Option[SamlBrowserPolicy] =
      SamlBrowserPolicy.fromConfig(ConfigFactory.parseString("saml.setTokenUrl=\"https://app.example.test/callback\""), new InvitationSecretService)
    override lazy val samlLoginService: SamlLoginService = mock[SamlLoginService]
  }
  "SAML callback exchange" should {
    "return existing token JSON and clear the nonce cookie after a verified session" in new TestCase {
      val request = routeRepresentation.request.random
      val result  = routeRepresentation.response.random
      when(samlLoginService.login(request.value, orgSlug, LoginHttpRequestContext(deviceId, None), Some("nonce")))
        .thenReturn(io(SamlLoginResult.LoggedIn(result.value)))
      TestRequest(routePath, requestJson(request.getJsonString)) ~> callbackHeaders ~> allRoutes ~> check {
        status mustEqual StatusCodes.OK
        responseAs[Json] shouldEqualJson result.getJsonString
        response.headers.filter(_.is("set-cookie")).map(_.value()).exists(_.contains("epoca_saml_nonce=deleted")) must beTrue
      }
    }
    "return the invitation union and only HTTP-only cookies without session tokens or recipient details" in new TestCase {
      val request = routeRepresentation.request.random
      when(samlLoginService.login(request.value, orgSlug, LoginHttpRequestContext(deviceId, None), Some("nonce"))).thenReturn(
        io(
          SamlLoginResult
            .InvitationContinuation(orgSlug, "Accept", "epoca_invitation_access=context; Path=/api; Max-Age=60; HttpOnly; Secure; SameSite=Lax")
        )
      )
      TestRequest(routePath, requestJson(request.getJsonString)) ~> callbackHeaders ~> allRoutes ~> check {
        status mustEqual StatusCodes.OK
        responseAs[Json] shouldEqualJson """{"kind":"invitation","organizationSlug":"target-org","nextAction":"Accept"}"""
        response.headers.count(_.is("set-cookie")) mustEqual 2
        responseAs[String].contains("context") must beFalse
      }
    }
    "reject absent or wrong Origin before calling the exchange service" in new TestCase {
      val request = routeRepresentation.request.random
      TestRequest(routePath, requestJson(request.getJsonString)) ~> allRoutes ~> check(status mustEqual StatusCodes.Forbidden)
      TestRequest(routePath, requestJson(request.getJsonString)) ~> addHeader("Origin", "https://attacker.test") ~> allRoutes ~> check {
        status mustEqual StatusCodes.Forbidden
      }
      verifyNoInteractions(samlLoginService)
    }
    "return a generic invalid callback response for stale nonce org or consumed codes" in new TestCase {
      val request = routeRepresentation.request.random
      when(samlLoginService.login(request.value, orgSlug, LoginHttpRequestContext(deviceId, None), Some("nonce")))
        .thenReturn(io(SamlLoginResult.InvalidCallback))
      TestRequest(routePath, requestJson(request.getJsonString)) ~> callbackHeaders ~> allRoutes ~> check(status mustEqual StatusCodes.NotFound)
    }
    "reject non-JSON content before reading any callback" in new TestCase {
      TestRequest(
        routePath,
        org.apache.pekko.http.scaladsl.model.HttpEntity(org.apache.pekko.http.scaladsl.model.ContentTypes.`text/plain(UTF-8)`, "token=secret")
      ) ~> callbackHeaders ~> allRoutes ~> check(status mustEqual StatusCodes.Forbidden)
      verifyNoInteractions(samlLoginService)
    }
  }
}
