package pl.iterators.epoca.saml.services

import com.onelogin.saml2.authn.AuthnRequest
import com.typesafe.config.ConfigFactory
import org.mockito.Mockito.verifyNoInteractions
import pl.iterators.epoca.invitation.services.InvitationSecretService
import pl.iterators.epoca.organization.domain.Organization
import pl.iterators.epoca.organization.repositories.OrganizationRepository
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.saml.domain.SamlDomain._
import pl.iterators.epoca.saml.domain.{SamlConfig, SamlRequest}
import pl.iterators.epoca.saml.repositories.SamlConfigRepository
import pl.iterators.epoca.saml.services.SamlAuthnService.SamlAuthnResult
import pl.iterators.epoca.utils.ServiceSpec

import java.time.{Clock, Instant, ZoneOffset}

class SamlAuthnServiceSpec extends ServiceSpec {
  trait TestCase extends BaseScope {
    val organizations: OrganizationRepository = mock[OrganizationRepository]
    val configs: SamlConfigRepository         = mock[SamlConfigRepository]
    val utils: SamlUtilsService               = mock[SamlUtilsService]
    val integration: SamlInvitationService    = mock[SamlInvitationService]
    val secrets                               = new InvitationSecretService
    val now: Instant                          = Instant.parse("2026-10-02T10:00:00Z")
    val clock: Clock                          = Clock.fixed(now, ZoneOffset.UTC)
    val policy: Option[SamlBrowserPolicy] =
      SamlBrowserPolicy.fromConfig(ConfigFactory.parseString("saml.setTokenUrl=\"https://app.example.test/callback\""), secrets)
    val sut                        = new SamlAuthnService(transactor, organizations, configs, utils, integration, secrets, clock, policy)
    val organization: Organization = allGenerators[Organization].normal.generate
    val config: SamlConfig         = allGenerators[SamlConfig].normal.generate.copy(organizationId = organization.id, isEnabled = SamlIsEnabled(true))
    val filter: OrganizationFilters = OrganizationFilters(slug = Some(organization.slug))
  }
  "authnRequest" should {
    "reject an unavailable browser policy before accessing organization state" in new TestCase {
      val unavailable = new SamlAuthnService(transactor, organizations, configs, utils, integration, secrets, clock, None)
      unavailable.authnRequest(organization.slug).asserting(_ mustEqual SamlAuthnResult.Unavailable)
      verifyNoInteractions(organizations, integration)
    }
    "return OrganizationNotFound for a missing organization" in new TestCase {
      when(organizations.find(filter)).thenReturn(None.asDBIO)
      sut.authnRequest(organization.slug).asserting(_ mustEqual SamlAuthnResult.OrganizationNotFound)
      verifyNoInteractions(integration)
    }
    "return SamlConfigNotFound for a missing config" in new TestCase {
      when(organizations.find(filter)).thenReturn(Some(organization).asDBIO)
      when(configs.find(organization.id)).thenReturn(None.asDBIO)
      sut.authnRequest(organization.slug).asserting(_ mustEqual SamlAuthnResult.SamlConfigNotFound)
      verifyNoInteractions(integration)
    }
    "return SamlDisabled for a disabled config" in new TestCase {
      when(organizations.find(filter)).thenReturn(Some(organization).asDBIO)
      when(configs.find(organization.id)).thenReturn(Some(config.copy(isEnabled = SamlIsEnabled(false))).asDBIO)
      sut.authnRequest(organization.slug).asserting(_ mustEqual SamlAuthnResult.SamlDisabled)
      verifyNoInteractions(integration)
    }
    "persist a server-bound nonce request through the atomic start adapter and expose the nonce only to its router" in new TestCase {
      val request = mock[AuthnRequest]
      when(organizations.find(filter)).thenReturn(Some(organization).asDBIO)
      when(configs.find(organization.id)).thenReturn(Some(config).asDBIO)
      when(utils.getAuthnRequest(config, organization)).thenReturn(request.asIO)
      when(request.getId).thenReturn("_request")
      when(utils.buildAndEncodeRequest(request)).thenReturn("encoded".asIO)
      when(integration.startAction(any, any, any, any, any, any)).thenAnswer((r: SamlRequest) => Some(r).asDBIO)
      sut.authnRequest(organization.slug).asserting { result =>
        val success = result.asInstanceOf[SamlAuthnResult.Ok]
        success.data.samlRequest mustEqual "encoded"
        success.nonce.length mustEqual 43
        verify(integration).startAction(
          argThat((r: SamlRequest) =>
            r.organizationId.contains(organization.id) && r.trustEpoch.isEmpty &&
              r.createdAt == now && r.browserNonceHash.contains(secrets.hash(success.nonce).toVector) && r.userId.isEmpty && r.token.isEmpty
          ),
          eqTo(config),
          eqTo(None),
          eqTo(None),
          eqTo(None),
          eqTo(None)
        )
        result.toString must not(contain(success.nonce))
      }
    }
    "reject a failed context start without issuing a browser nonce" in new TestCase {
      val request = mock[AuthnRequest]
      when(organizations.find(filter)).thenReturn(Some(organization).asDBIO)
      when(configs.find(organization.id)).thenReturn(Some(config).asDBIO)
      when(utils.getAuthnRequest(config, organization)).thenReturn(request.asIO)
      when(request.getId).thenReturn("_request")
      when(utils.buildAndEncodeRequest(request)).thenReturn("encoded".asIO)
      when(integration.startAction(any, any, any, any, any, any)).thenReturn(None.asDBIO)
      sut
        .authnRequest(organization.slug, invitationCookie = Some("opaque"), origin = Some("https://app.example.test"), csrf = Some("csrf"))
        .asserting(_ mustEqual SamlAuthnResult.InvalidContext)
    }
  }
}
