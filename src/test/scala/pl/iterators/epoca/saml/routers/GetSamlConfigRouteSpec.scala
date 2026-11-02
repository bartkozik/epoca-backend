package pl.iterators.epoca.saml.routers

import org.apache.pekko.http.scaladsl.model.StatusCodes
import pl.iterators.baklava.core.model.RouteRepresentation
import pl.iterators.epoca.auth.domain.AccountAuthentication.{Credential, CredentialMethod}
import pl.iterators.epoca.auth.domain.{AccountAuthentication, AuthContext, AuthContextAuthedBy}
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.saml.domain.SamlConfig
import pl.iterators.epoca.saml.services.SamlConfigGetService
import pl.iterators.epoca.saml.services.SamlConfigGetService.SamlConfigGetResult
import pl.iterators.epoca.user.domain.UserDomain.{IdentityType, UserIdentityId, UserIsSuperAdmin}
import pl.iterators.epoca.utils.{AuthContextProvider, RouteDocSpec}

import java.time.Instant
import java.util.UUID

class GetSamlConfigRouteSpec extends RouteDocSpec {
  override val routeRepresentation: RouteRepresentation[Unit, SamlConfig] =
    RouteRepresentation[Unit, SamlConfig]("Returns SAML configuration for organization.", "GET", "/organizations/{organizationSlug}/saml/configs")

  val orgSlug: OrganizationSlug = allGenerators[OrganizationSlug].normal.generate
  val routePath: String         = routeRepresentation.path.replace("{organizationSlug}", orgSlug)
  private val identityId        = UserIdentityId(UUID.fromString("20000000-0000-0000-0000-000000000001"))
  val auth: AuthContext = AuthContextProvider.user.copy(
    isSuperAdmin = UserIsSuperAdmin(false),
    authedBy = Set(AuthContextAuthedBy(IdentityType.MailAddress, Some(identityId))),
    accountAuthentication = Some(AccountAuthentication(Instant.now(), Credential(CredentialMethod.Password, identityId)))
  )

  trait NoLoggedTestCase extends BaseScope {
    override lazy val authContextForTest: Option[AuthContext] = None
  }

  trait LoggedTestCase extends BaseScope {
    override lazy val samlConfigGetService: SamlConfigGetService = vmock[SamlConfigGetService]

    override val authContextForTest: Option[AuthContext] = Some(auth)
  }

  routeRepresentation.name should {
    "be inaccessible for no loggged users" in new NoLoggedTestCase {
      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldBeInaccessible
      }
    }
    "return NotFound if service return OrganizationNotFound" in new LoggedTestCase {
      when(samlConfigGetService.get(auth, orgSlug))
        .thenReturn(io(SamlConfigGetResult.OrganizationNotFound))
      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }
      verify(samlConfigGetService).get(auth, orgSlug)
    }
    "return NotFound if service return SamlConfigNotFound" in new LoggedTestCase {
      when(samlConfigGetService.get(auth, orgSlug))
        .thenReturn(io(SamlConfigGetResult.SamlConfigNotFound))
      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }
      verify(samlConfigGetService).get(auth, orgSlug)
    }
    "return NotFound if service return NoAccessToOrganization" in new LoggedTestCase {
      when(samlConfigGetService.get(auth, orgSlug))
        .thenReturn(io(SamlConfigGetResult.NoAccessToOrganization))
      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }
      verify(samlConfigGetService).get(auth, orgSlug)
    }
    "return OK with service data otherwise" in new LoggedTestCase {
      val responseObject = routeRepresentation.response.random

      when(samlConfigGetService.get(auth, orgSlug))
        .thenReturn(io(SamlConfigGetResult.Ok(responseObject.value)))
      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.OK
        responseAs[Json] shouldEqualJson responseObject.getJsonString
      }
      verify(samlConfigGetService).get(auth, orgSlug)
    }
  }
}
