package pl.iterators.epoca.saml.routers

import com.typesafe.config.ConfigFactory
import org.apache.pekko.http.scaladsl.model.StatusCodes
import pl.iterators.baklava.core.model.RouteRepresentation
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.invitation.services.InvitationSecretService
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.saml.services.SamlAuthnService.{SamlAuthnResult, SamlAuthnResultData}
import pl.iterators.epoca.saml.services.{SamlAuthnService, SamlBrowserPolicy}
import pl.iterators.epoca.utils.RouteDocSpec

class GetSamlAuthnRouteSpec extends RouteDocSpec {
  override val routeRepresentation: RouteRepresentation[Unit, SamlAuthnResultData] =
    RouteRepresentation[Unit, SamlAuthnResultData](
      "Returns encoded SamlResponse and SSO url that should point to an identity provider",
      "GET",
      "/organizations/{organizationSlug}/saml/authn"
    )

  val orgSlug: OrganizationSlug = allGenerators[OrganizationSlug].normal.generate
  val routePath: String         = routeRepresentation.path.replace("{organizationSlug}", orgSlug)

  trait NoLoggedTestCase extends BaseScope {
    override lazy val samlBrowserPolicy: Option[SamlBrowserPolicy] =
      SamlBrowserPolicy.fromConfig(ConfigFactory.parseString("saml.setTokenUrl=\"https://app.example.test/callback\""), new InvitationSecretService)
    override lazy val authContextForTest: Option[AuthContext] = None
    override lazy val samlAuthnService: SamlAuthnService      = vmock[SamlAuthnService]
  }

  routeRepresentation.name should {
    Seq(None, Some(pl.iterators.epoca.utils.AuthContextProvider.user)).foreach { auth =>
      s"forward invitation POST browser evidence and authentication $auth to the shared start service" in new NoLoggedTestCase {
        override lazy val authContextForTest: Option[AuthContext] = auth
        val responseObject                                        = routeRepresentation.response.random
        when(samlAuthnService.authnRequest(orgSlug, auth, Some("context"), Some("https://app.example.test"), Some("csrf")))
          .thenReturn(io(SamlAuthnResult.Ok(responseObject.value, "nonce")))
        Post(routePath) ~> addHeader("Cookie", "epoca_invitation_access=context") ~>
          addHeader("Origin", "https://app.example.test") ~> addHeader("X-CSRF-Token", "csrf") ~> allRoutes ~> check {
            status mustEqual StatusCodes.Created
            responseAs[Json] shouldEqualJson responseObject.getJsonString
            response.headers.filter(_.is("set-cookie")).exists(_.value().contains("epoca_saml_nonce=nonce")) must beTrue
          }
        verify(samlAuthnService).authnRequest(orgSlug, auth, Some("context"), Some("https://app.example.test"), Some("csrf"))
      }
    }
    "reject invitation POST without a context cookie" in new NoLoggedTestCase {
      Post(routePath) ~> allRoutes ~> check(status mustEqual StatusCodes.Forbidden)
      org.mockito.Mockito.verifyNoInteractions(samlAuthnService)
    }
    "keep invalid browser policy unavailable at the route without invoking SAML" in new NoLoggedTestCase {
      override lazy val samlBrowserPolicy: Option[SamlBrowserPolicy] = SamlBrowserPolicy.fromConfig(
        ConfigFactory.parseString("saml.setTokenUrl=\"http://remote.example.test/callback\""),
        new InvitationSecretService
      )
      Get(routePath) ~> allRoutes ~> check(status mustEqual StatusCodes.ServiceUnavailable)
      Post(routePath) ~> addHeader("Cookie", "epoca_invitation_access=context") ~> allRoutes ~> check(status mustEqual StatusCodes.ServiceUnavailable)
      org.mockito.Mockito.verifyNoInteractions(samlAuthnService)
    }

    "return NotFound if service return OrganizationNotFound" in new NoLoggedTestCase {
      when(samlAuthnService.authnRequest(orgSlug, None, None, None, None)).thenReturn(io(SamlAuthnResult.OrganizationNotFound))
      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }
      verify(samlAuthnService).authnRequest(orgSlug, None, None, None, None)
    }
    "return NotFound if service return SamlConfigNotFound" in new NoLoggedTestCase {
      when(samlAuthnService.authnRequest(orgSlug, None, None, None, None)).thenReturn(io(SamlAuthnResult.SamlConfigNotFound))
      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }
      verify(samlAuthnService).authnRequest(orgSlug, None, None, None, None)
    }
    "return Forbidden if service return SamlDisabled" in new NoLoggedTestCase {
      when(samlAuthnService.authnRequest(orgSlug, None, None, None, None)).thenReturn(io(SamlAuthnResult.SamlDisabled))
      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
      }
      verify(samlAuthnService).authnRequest(orgSlug, None, None, None, None)
    }
    "return Created with service data otherwise" in new NoLoggedTestCase {
      val responseObject = routeRepresentation.response.random

      when(samlAuthnService.authnRequest(orgSlug, None, None, None, None)).thenReturn(io(SamlAuthnResult.Ok(responseObject.value, "nonce")))
      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.Created
        responseAs[Json] shouldEqualJson responseObject.getJsonString
        response.headers.filter(_.is("set-cookie")).map(_.value()).exists(_.contains("epoca_saml_nonce=nonce")) must beTrue
      }
      verify(samlAuthnService).authnRequest(orgSlug, None, None, None, None)
    }
  }
}
