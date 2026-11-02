package pl.iterators.epoca.saml.routers

import org.apache.pekko.http.scaladsl.model.StatusCodes
import pl.iterators.baklava.core.model.RouteRepresentation
import pl.iterators.epoca.auth.domain.AccountAuthentication.{Credential, CredentialMethod}
import pl.iterators.epoca.auth.domain.{AccountAuthentication, AuthContext, AuthContextAuthedBy}
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.saml.domain.SamlConfig
import pl.iterators.epoca.saml.services.SamlConfigUpsertService
import pl.iterators.epoca.saml.services.SamlConfigUpsertService.{SamlConfigUpsertRequest, SamlConfigUpsertResult}
import pl.iterators.epoca.user.domain.UserDomain.{IdentityType, UserIdentityId, UserIsSuperAdmin}
import pl.iterators.epoca.utils.{AuthContextProvider, RouteDocSpec}

import java.time.Instant
import java.util.UUID

class PostSamlConfigRouteSpec extends RouteDocSpec {
  override val routeRepresentation: RouteRepresentation[SamlConfigUpsertRequest, SamlConfig] =
    RouteRepresentation[SamlConfigUpsertRequest, SamlConfig](
      "Create/Update SAML configuration for organization - needs to be an Owner or Admin",
      "POST",
      "/organizations/{organizationSlug}/saml/configs"
    )

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
    override lazy val samlConfigUpsertService: SamlConfigUpsertService = vmock[SamlConfigUpsertService]

    override val authContextForTest: Option[AuthContext] = Some(auth)
  }

  routeRepresentation.name should {
    "be inaccessible for no loggged users" in new NoLoggedTestCase {
      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldBeInaccessible
      }
    }
    "return NotFound if service return OrganizationNotFound" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random
      when(samlConfigUpsertService.upsert(auth, orgSlug, requestObject.value))
        .thenReturn(io(SamlConfigUpsertResult.OrganizationNotFound))
      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }
      verify(samlConfigUpsertService).upsert(auth, orgSlug, requestObject.value)
    }
    "return NotFound if service return NoAccessToOrganization" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random
      when(samlConfigUpsertService.upsert(auth, orgSlug, requestObject.value))
        .thenReturn(io(SamlConfigUpsertResult.NoAccessToOrganization))
      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }
      verify(samlConfigUpsertService).upsert(auth, orgSlug, requestObject.value)
    }
    "return Forbidden if service return PermissionsAreTooLow" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random
      when(samlConfigUpsertService.upsert(auth, orgSlug, requestObject.value))
        .thenReturn(io(SamlConfigUpsertResult.PermissionsAreTooLow))
      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
      }
      verify(samlConfigUpsertService).upsert(auth, orgSlug, requestObject.value)
    }
    "return Created with service data otherwise" in new LoggedTestCase {
      val requestObject  = routeRepresentation.request.random
      val responseObject = routeRepresentation.response.random

      when(samlConfigUpsertService.upsert(auth, orgSlug, requestObject.value))
        .thenReturn(io(SamlConfigUpsertResult.Created(responseObject.value)))
      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.Created
        responseAs[Json] shouldEqualJson responseObject.getJsonString
      }
      verify(samlConfigUpsertService).upsert(auth, orgSlug, requestObject.value)
    }
  }
}
