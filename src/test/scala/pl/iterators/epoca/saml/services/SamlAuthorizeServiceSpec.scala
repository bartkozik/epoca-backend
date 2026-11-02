package pl.iterators.epoca.saml.services

import com.onelogin.saml2.authn.SamlResponse
import org.mockito.Mockito.verifyNoInteractions
import pl.iterators.epoca.organization.domain.Organization
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.organization.repositories.OrganizationRepository
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.saml.domain.SamlDomain._
import pl.iterators.epoca.saml.domain.{SamlAuthContext, SamlConfig, SamlRequest}
import pl.iterators.epoca.saml.repositories.{SamlConfigRepository, SamlRequestRepository}
import pl.iterators.epoca.saml.services.SamlAuthorizeService.SamlAuthorizeResult
import pl.iterators.epoca.utils.ServiceSpec

import java.time.{Clock, Duration, Instant, ZoneOffset}
import java.util.UUID

class SamlAuthorizeServiceSpec extends ServiceSpec {
  trait TestCase extends BaseScope {
    val organizations: OrganizationRepository = mock[OrganizationRepository]
    val configs: SamlConfigRepository         = mock[SamlConfigRepository]
    val requests: SamlRequestRepository       = mock[SamlRequestRepository]
    val utils: SamlUtilsService               = mock[SamlUtilsService]
    val integration: SamlInvitationService    = mock[SamlInvitationService]
    val now: Instant                          = Instant.parse("2026-10-02T10:00:00Z")
    val sut = new SamlAuthorizeService(transactor, organizations, configs, requests, utils, integration, Clock.fixed(now, ZoneOffset.UTC))
    val organization: Organization = allGenerators[Organization].normal.generate
    val config: SamlConfig = allGenerators[SamlConfig].normal.generate
      .copy(organizationId = organization.id, isEnabled = SamlIsEnabled(true), validFor = SamlValidityDuration(Duration.ofMinutes(5)))
    val request: SamlRequest = SamlRequest(
      SamlRequestId("_request"),
      None,
      None,
      SamlRequestUsed(false),
      SamlRequestCreatedAt(now),
      organizationId = Some(organization.id),
      trustEpoch = Some(1L),
      purpose = Some("Login"),
      browserNonceHash = Some(Vector.fill(32)(1.toByte))
    )
    val parsed: SamlResponse     = mock[SamlResponse]
    val context: SamlAuthContext = SamlAuthContext("stable-subject", Map("email" -> List("recipient@example.test")), config.idpEntityId)
    when(utils.extractRequestId("encoded")).thenReturn(Some(request.id).asIO)
    when(requests.find(request.id)).thenReturn(Some(request).asDBIO)
    when(organizations.find(OrganizationFilters(id = Some(organization.id)))).thenReturn(Some(organization).asDBIO)
    when(configs.find(organization.id)).thenReturn(Some(config).asDBIO)
    when(utils.parseResponse("encoded", config, organization)).thenReturn(Some(parsed).asIO)
    when(utils.isValid(parsed, request.id)).thenReturn(true)
    when(utils.getAuthContext(parsed)).thenReturn(Some(context).asIO)
  }
  "authorize" should {
    "extract persisted request provenance before parsing and authorize through one transaction adapter" in new TestCase {
      val token = SamlOneTimeToken(UUID.randomUUID())
      when(integration.authorizeAction(request, config, context)).thenReturn(Some(request.copy(token = Some(token))).asDBIO)
      sut.authorize("encoded", organization.slug).asserting(_ mustEqual SamlAuthorizeResult.Authorized(token))
      verify(organizations).find(OrganizationFilters(id = Some(organization.id)))
      verify(organizations, never).find(OrganizationFilters(slug = Some(organization.slug)))
      verify(integration).authorizeAction(request, config, context)
    }
    "reject callback organization substitution before signed parsing" in new TestCase {
      sut.authorize("encoded", OrganizationSlug("other-organization")).asserting(_ mustEqual SamlAuthorizeResult.ResponseInvalid)
      verify(utils, never).parseResponse(any, any, any)
      verifyNoInteractions(integration)
    }
    "reject a request without persisted organization provenance" in new TestCase {
      when(requests.find(request.id)).thenReturn(Some(request.copy(organizationId = None)).asDBIO)
      sut.authorize("encoded", organization.slug).asserting(_ mustEqual SamlAuthorizeResult.ResponseInvalid)
      verifyNoInteractions(integration)
    }
    "reject a missing response correlation id" in new TestCase {
      when(utils.extractRequestId("encoded")).thenReturn(None.asIO)
      sut.authorize("encoded", organization.slug).asserting(_ mustEqual SamlAuthorizeResult.NoInResponseTo)
      verifyNoInteractions(requests, integration)
    }
    "reject missing used and expired requests before activation" in new TestCase {
      when(requests.find(request.id)).thenReturn(None.asDBIO)
      sut.authorize("encoded", organization.slug).asserting(_ mustEqual SamlAuthorizeResult.RequestNotFound(request.id))
      when(requests.find(request.id)).thenReturn(Some(request.copy(used = SamlRequestUsed(true))).asDBIO)
      sut.authorize("encoded", organization.slug).asserting(_ mustEqual SamlAuthorizeResult.RequestAlreadyUsed)
      when(requests.find(request.id)).thenReturn(Some(request.copy(createdAt = SamlRequestCreatedAt(now.minusSeconds(301)))).asDBIO)
      sut.authorize("encoded", organization.slug).asserting(_ mustEqual SamlAuthorizeResult.RequestExpired)
      verifyNoInteractions(integration)
    }
    "reject missing disabled and changed configuration without user initialization" in new TestCase {
      when(configs.find(organization.id)).thenReturn(None.asDBIO)
      sut.authorize("encoded", organization.slug).asserting(_ mustEqual SamlAuthorizeResult.SamlConfigNotFound)
      when(configs.find(organization.id)).thenReturn(Some(config.copy(isEnabled = SamlIsEnabled(false))).asDBIO)
      sut.authorize("encoded", organization.slug).asserting(_ mustEqual SamlAuthorizeResult.ResponseInvalid)
      verifyNoInteractions(integration)
    }
    "reject unsigned or invalid responses without reading attributes or authorizing" in new TestCase {
      when(utils.isValid(parsed, request.id)).thenReturn(false)
      sut.authorize("encoded", organization.slug).asserting(_ mustEqual SamlAuthorizeResult.ResponseInvalid)
      verify(utils, never).getAuthContext(any)
      verifyNoInteractions(integration)
    }
    "reject invalid attributes and denied locked state without issuing a token" in new TestCase {
      when(utils.getAuthContext(parsed)).thenReturn(None.asIO)
      sut.authorize("encoded", organization.slug).asserting(_ mustEqual SamlAuthorizeResult.ResponseAttributesMissing)
      when(utils.getAuthContext(parsed)).thenReturn(Some(context).asIO)
      when(integration.authorizeAction(request, config, context)).thenReturn(None.asDBIO)
      sut.authorize("encoded", organization.slug).asserting(_ mustEqual SamlAuthorizeResult.ResponseInvalid)
    }
    "sanitize parser exceptions" in new TestCase {
      when(utils.parseResponse("encoded", config, organization))
        .thenReturn(cats.effect.IO.raiseError(new IllegalArgumentException("secret assertion")))
      sut.authorize("encoded", organization.slug).asserting(_ mustEqual SamlAuthorizeResult.ResponseInvalid)
      verifyNoInteractions(integration)
    }
  }
}
