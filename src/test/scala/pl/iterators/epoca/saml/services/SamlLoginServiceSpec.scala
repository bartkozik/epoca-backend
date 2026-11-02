package pl.iterators.epoca.saml.services

import org.mockito.Mockito.verifyNoInteractions
import pl.iterators.epoca.auth.domain.AccountAuthentication
import pl.iterators.epoca.auth.domain.AccountAuthentication.BoundSaml
import pl.iterators.epoca.auth.domain.AuthDomain.{DeviceId, Jwt}
import pl.iterators.epoca.auth.routers.AuthRouter.LoginHttpRequestContext
import pl.iterators.epoca.auth.services.LoginResolveService
import pl.iterators.epoca.auth.services.LoginResolveService.SamlLogin
import pl.iterators.epoca.invitation.services.InvitationAccessService
import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationId, OrganizationSlug}
import pl.iterators.epoca.saml.domain.SamlDomain.SamlOneTimeToken
import pl.iterators.epoca.saml.services.SamlLoginService.{SamlLoginRequest, SamlLoginResult}
import pl.iterators.epoca.user.domain.UserWithIdentities
import pl.iterators.epoca.utils.ServiceSpec

import java.time.Instant
import java.util.UUID

class SamlLoginServiceSpec extends ServiceSpec {
  trait TestCase extends BaseScope {
    val integration: SamlInvitationService = mock[SamlInvitationService]
    val login: LoginResolveService         = mock[LoginResolveService]
    val photos: WorkspaceAvatarSyncService = mock[WorkspaceAvatarSyncService]
    val access = new InvitationAccessService(
      null,
      null,
      null,
      null,
      new pl.iterators.epoca.invitation.services.InvitationSecretService,
      new pl.iterators.epoca.invitation.services.InvitationBrowserSecurity(
        Set("https://app.example.test"),
        "/api",
        false,
        false,
        new pl.iterators.epoca.invitation.services.InvitationSecretService
      ),
      null,
      java.time.Clock.fixed(Instant.parse("2026-10-02T10:00:00Z"), java.time.ZoneOffset.UTC)
    )
    val sut                              = new SamlLoginService(transactor, integration, login, Some(access), Some(photos))
    val slug: OrganizationSlug           = OrganizationSlug("target-organization")
    val request: SamlLoginRequest        = SamlLoginRequest(SamlOneTimeToken(UUID.randomUUID()))
    val context: LoginHttpRequestContext = LoginHttpRequestContext(DeviceId("device"), None)
    val nonce: Some[String]              = Some("browser-nonce")
  }
  "login" should {
    "reject invalid exchanged state without issuing a session" in new TestCase {
      when(integration.exchangeAction(request.token, slug, nonce)).thenReturn(SamlInvitationService.Denied.asDBIO)
      sut.login(request, slug, context, nonce).asserting(_ mustEqual SamlLoginResult.InvalidCallback)
      verifyNoInteractions(login, photos)
    }
    "pass the actual nonce to the atomic exchange and preserve original bound authentication time" in new TestCase {
      val user   = allGenerators[UserWithIdentities].normal.generate
      val at     = Instant.parse("2026-10-02T10:00:00Z")
      val proof  = AccountAuthentication(at, BoundSaml(OrganizationId(UUID.randomUUID()), 3L, "issuer", "subject"))
      val tokens = LoginResolveService.LoginResolution(Jwt("access"), Jwt("refresh"))
      when(integration.exchangeAction(request.token, slug, nonce)).thenReturn(SamlInvitationService.Session(user, Seq.empty, Some(proof), at).asDBIO)
      when(login.resolve(SamlLogin(context, user, Seq.empty, Some(proof), Some(at)))).thenReturn(tokens.asIO)
      sut.login(request, slug, context, nonce).asserting(_ mustEqual SamlLoginResult.LoggedIn(tokens))
      verify(integration).exchangeAction(request.token, slug, nonce)
      verify(login).resolve(SamlLogin(context, user, Seq.empty, Some(proof), Some(at)))
    }
    "return only safe invitation continuation and a server cookie header without issuing global tokens" in new TestCase {
      val dto = allGenerators[InvitationAccessService.SafeAccessDto].normal.generate
        .copy(nextAction = "Accept", expiresAt = Instant.parse("2026-10-02T10:01:00Z"))
      val grant = InvitationAccessService.Granted("raw-context-cookie", dto)
      when(integration.exchangeAction(request.token, slug, nonce)).thenReturn(SamlInvitationService.Continuation(slug, grant).asDBIO)
      sut.login(request, slug, context, nonce).asserting { result =>
        result mustEqual SamlLoginResult
          .InvitationContinuation(slug, "Accept", "epoca_invitation_access=raw-context-cookie; Path=/api; Max-Age=60; HttpOnly; Secure; SameSite=Lax")
        result.toString must not(contain("raw-context-cookie"))
      }
      verifyNoInteractions(login, photos)
    }
    "dispatch only after successful session issuance using verified photo proof" in new TestCase {
      val user           = allGenerators[UserWithIdentities].normal.generate
      val at             = Instant.parse("2026-10-02T10:00:00Z")
      val proof          = WorkspacePhotoProof(OrganizationId(UUID.randomUUID()), 3L, "issuer", "subject", "member@example.com")
      val authentication = Some(AccountAuthentication(at, BoundSaml(proof.organizationId, proof.trustEpoch, proof.issuer, proof.subject)))
      val tokens         = LoginResolveService.LoginResolution(Jwt("access"), Jwt("refresh"))
      when(integration.exchangeAction(request.token, slug, nonce))
        .thenReturn(SamlInvitationService.Session(user, Seq.empty, authentication, at, Some(proof)).asDBIO)
      when(login.resolve(SamlLogin(context, user, Seq.empty, authentication, Some(at)))).thenReturn(tokens.asIO)
      when(photos.schedule(user, proof)).thenReturn(cats.effect.IO.unit)
      sut.login(request, slug, context, nonce).asserting(_ mustEqual SamlLoginResult.LoggedIn(tokens))
      val ordered = org.mockito.Mockito.inOrder(login, photos)
      ordered.verify(login).resolve(SamlLogin(context, user, Seq.empty, authentication, Some(at)))
      ordered.verify(photos).schedule(user, proof)
    }
    "keep successful login usable if photo dispatch itself fails" in new TestCase {
      val user   = allGenerators[UserWithIdentities].normal.generate
      val at     = Instant.parse("2026-10-02T10:00:00Z")
      val proof  = WorkspacePhotoProof(OrganizationId(UUID.randomUUID()), 3L, "issuer", "subject", "member@example.com")
      val tokens = LoginResolveService.LoginResolution(Jwt("access"), Jwt("refresh"))
      when(integration.exchangeAction(request.token, slug, nonce))
        .thenReturn(SamlInvitationService.Session(user, Seq.empty, None, at, Some(proof)).asDBIO)
      when(login.resolve(SamlLogin(context, user, Seq.empty, None, Some(at)))).thenReturn(tokens.asIO)
      when(photos.schedule(user, proof)).thenThrow(new RuntimeException("dispatch unavailable"))
      sut.login(request, slug, context, nonce).asserting(_ mustEqual SamlLoginResult.LoggedIn(tokens))
    }
    "not dispatch photo work when session issuance fails" in new TestCase {
      val user  = allGenerators[UserWithIdentities].normal.generate
      val at    = Instant.parse("2026-10-02T10:00:00Z")
      val proof = WorkspacePhotoProof(OrganizationId(UUID.randomUUID()), 3L, "issuer", "subject", "member@example.com")
      when(integration.exchangeAction(request.token, slug, nonce))
        .thenReturn(SamlInvitationService.Session(user, Seq.empty, None, at, Some(proof)).asDBIO)
      when(login.resolve(SamlLogin(context, user, Seq.empty, None, Some(at))))
        .thenReturn(cats.effect.IO.raiseError(new RuntimeException("session unavailable")))
      sut.login(request, slug, context, nonce).attempt.asserting(_ must beLeft)
      verifyNoInteractions(photos)
    }
    "retain proofNone for active legacy SAML sessions" in new TestCase {
      val user   = allGenerators[UserWithIdentities].normal.generate
      val at     = Instant.parse("2026-10-02T10:00:00Z")
      val tokens = LoginResolveService.LoginResolution(Jwt("access"), Jwt("refresh"))
      when(integration.exchangeAction(request.token, slug, nonce)).thenReturn(SamlInvitationService.Session(user, Seq.empty, None, at).asDBIO)
      when(login.resolve(SamlLogin(context, user, Seq.empty, None, Some(at)))).thenReturn(tokens.asIO)
      sut.login(request, slug, context, nonce).asserting(_ mustEqual SamlLoginResult.LoggedIn(tokens))
      val auth = SamlLogin(context, user, Seq.empty, None, Some(at)).toAuthContext(java.time.ZonedDateTime.ofInstant(at, java.time.ZoneOffset.UTC))
      auth.accountAuthentication must beNone
      auth.fresh must beFalse
    }
  }
}
