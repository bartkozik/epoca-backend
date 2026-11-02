package pl.iterators.epoca.invitation.services

import org.specs2.mutable.Specification
import pl.iterators.epoca.invitation.domain.InvitationOnboarding.Digest

class InvitationBrowserSecuritySpec extends Specification {
  private val secrets = new InvitationSecretService
  private val browser = new InvitationBrowserSecurity(Set("https://app.example.test"), "/api", false, false, secrets)
  "invitation browser security" should {
    "reject nonlocal origins in insecure development mode" in {
      new InvitationBrowserSecurity(Set("https://app.example.test"), "/api", true, false, secrets) must throwA[IllegalArgumentException]
    }

    "require exact allowed Origin and application/json for exchange" in {
      browser.exchangeAllowed(Some("https://app.example.test"), Some("application/json")) must beTrue
      browser.exchangeAllowed(Some("https://app.example.test.evil"), Some("application/json")) must beFalse
      browser.exchangeAllowed(None, Some("application/json")) must beFalse
      browser.exchangeAllowed(Some("null"), Some("application/json")) must beFalse
      browser.exchangeAllowed(Some("https://app.example.test"), Some("text/plain")) must beFalse
    }
    "require both origin and the matching CSRF secret for cookie mutations" in {
      val hash = Digest(secrets.hash("csrf"))
      browser.mutationAllowed(Some("https://app.example.test"), Some("csrf"), hash) must beTrue
      browser.mutationAllowed(None, Some("csrf"), hash) must beFalse
      browser.mutationAllowed(Some("https://evil.test"), Some("csrf"), hash) must beFalse
      browser.mutationAllowed(Some("https://app.example.test"), None, hash) must beFalse
      browser.mutationAllowed(Some("https://app.example.test"), Some("wrong"), hash) must beFalse
    }
    "issue a host-only secure HttpOnly Lax cookie scoped to the API" in {
      val cookie = browser.cookie("secret", 900)
      cookie must contain("epoca_invitation_access=secret")
      cookie must contain("Path=/api")
      cookie must contain("HttpOnly")
      cookie must contain("Secure")
      cookie must contain("SameSite=Lax")
      cookie must not contain "Domain="
      browser.clearCookie must contain("Max-Age=0")
    }
    "allow explicit local HTTP and require Secure with cross-site None" in {
      new InvitationBrowserSecurity(Set("http://localhost:3000"), "/api", true, false, secrets).cookie("secret", 900) must not contain "Secure"
      new InvitationBrowserSecurity(Set("https://app.example.test"), "/api", false, true, secrets).cookie("secret", 900) must contain("SameSite=None")
      new InvitationBrowserSecurity(Set("http://localhost:3000"), "/api", true, true, secrets) must throwA[IllegalArgumentException]
      new InvitationBrowserSecurity(Set("*"), "/api", false, false, secrets) must throwA[IllegalArgumentException]
    }
  }
}
