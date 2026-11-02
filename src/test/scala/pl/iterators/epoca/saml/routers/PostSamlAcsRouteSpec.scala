package pl.iterators.epoca.saml.routers

import com.typesafe.config.ConfigFactory
import org.apache.pekko.http.scaladsl.model.headers.Location
import org.apache.pekko.http.scaladsl.model.{FormData, StatusCodes, Uri}
import pl.iterators.baklava.core.model.RouteRepresentation
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.invitation.services.InvitationSecretService
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.saml.domain.SamlDomain.{SamlOneTimeToken, SamlRequestId}
import pl.iterators.epoca.saml.services.SamlAuthorizeService.SamlAuthorizeResult
import pl.iterators.epoca.saml.services.{SamlAuthorizeService, SamlBrowserPolicy}
import pl.iterators.epoca.utils.RouteDocSpec

class PostSamlAcsRouteSpec extends RouteDocSpec {
  override val routeRepresentation: RouteRepresentation[Unit, SamlOneTimeToken] =
    RouteRepresentation[Unit, SamlOneTimeToken](
      "Webhook endpoint used by external identity provider",
      "POST",
      "/organizations/{organizationSlug}/saml/acs"
    )
  val orgSlug: OrganizationSlug = OrganizationSlug("target-org")
  val routePath: String         = routeRepresentation.path.replace("{organizationSlug}", orgSlug)
  val formData: FormData        = FormData("SAMLResponse" -> "synthetic-response")
  trait TestCase extends BaseScope {
    override lazy val authContextForTest: Option[AuthContext] = None
    override lazy val samlBrowserPolicy: Option[SamlBrowserPolicy] =
      SamlBrowserPolicy.fromConfig(ConfigFactory.parseString("saml.setTokenUrl=\"https://app.example.test/callback\""), new InvitationSecretService)
    override lazy val samlAuthorizeService: SamlAuthorizeService = vmock[SamlAuthorizeService]
  }
  "SAML ACS" should {
    Seq(
      SamlAuthorizeResult.OrganizationNotFound,
      SamlAuthorizeResult.SamlConfigNotFound,
      SamlAuthorizeResult.RequestNotFound(SamlRequestId("_missing")),
      SamlAuthorizeResult.NoInResponseTo,
      SamlAuthorizeResult.ResponseInvalid,
      SamlAuthorizeResult.RequestAlreadyUsed,
      SamlAuthorizeResult.RequestExpired,
      SamlAuthorizeResult.ResponseAttributesMissing
    ).foreach { failure =>
      s"return a safe fixed failure redirect for $failure" in new TestCase {
        when(samlAuthorizeService.authorize("synthetic-response", orgSlug)).thenReturn(io(failure))
        TestRequest(routePath, formData.toEntity) ~> allRoutes ~> check {
          status mustEqual StatusCodes.SeeOther
          val target = response.header[Location].get.uri
          target.withQuery(Uri.Query.Empty) mustEqual Uri(globalSamlConfig.failLoginUrl)
          target.query().get("reason") must beSome("SamlFailed")
          target.toString.contains("synthetic-response") must beFalse
        }
        verify(samlAuthorizeService).authorize("synthetic-response", orgSlug)
      }
    }
    "accept the IdP cross-site POST without frontend Origin or browser cookie and use only the fixed callback fragment" in new TestCase {
      val token = routeRepresentation.response.random.value
      when(samlAuthorizeService.authorize("synthetic-response", orgSlug)).thenReturn(io(SamlAuthorizeResult.Authorized(token)))
      TestRequest(routePath, formData.toEntity) ~> allRoutes ~> check {
        status mustEqual StatusCodes.SeeOther
        val target = response.header[Location].get.uri
        target.toString.takeWhile(_ != '#') mustEqual "https://app.example.test/callback"
        target.query().isEmpty must beTrue
        Uri.Query(target.fragment.get).get("token") must beSome(token.toString)
        Uri.Query(target.fragment.get).get("slug") must beSome(orgSlug.toString)
      }
      verify(samlAuthorizeService).authorize("synthetic-response", orgSlug)
    }
  }
}
