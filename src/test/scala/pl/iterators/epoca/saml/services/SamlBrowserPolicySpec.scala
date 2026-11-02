package pl.iterators.epoca.saml.services

import com.typesafe.config.ConfigFactory
import org.specs2.mutable.Specification
import pl.iterators.epoca.invitation.services.InvitationSecretService

class SamlBrowserPolicySpec extends Specification {
  private def policy(extra: String = "", callback: String = "https://app.example.test/saml/callback"): Option[SamlBrowserPolicy] =
    SamlBrowserPolicy.fromConfig(
      ConfigFactory.parseString(s"""saml.setTokenUrl="$callback"
      $extra
    """),
      new InvitationSecretService
    )
  "SAML browser policy" should {
    "derive the exact configured frontend origin and secure nonce cookie independently of invitations" in {
      val p = policy().get
      p.security.originAllowed(Some("https://app.example.test")) must beTrue
      p.security.originAllowed(Some("https://app.example.test.attacker.test")) must beFalse
      p.nonceCookie("random_secret") mustEqual "epoca_saml_nonce=random_secret; Path=/api; Max-Age=900; HttpOnly; Secure; SameSite=Lax"
      p.callback mustEqual "https://app.example.test/saml/callback"
    }
    "permit cross-site cookies only with explicit HTTPS policy" in {
      policy("saml.browser.crossSite=true").get.nonceCookie("value") must contain("SameSite=None")
      policy("saml.browser.crossSite=true\nsaml.browser.localHttp=true", "http://localhost:8000/saml") must beNone
    }
    "require explicit localhost HTTP development opt-in" in {
      policy(callback = "http://localhost:8000/saml") must beNone
      policy("saml.browser.localHttp=true", "http://localhost:8000/saml").get.nonceCookie("value") must not(contain("Secure"))
      policy("saml.browser.localHttp=true", "http://app.example.test/saml") must beNone
    }
    "return unavailable for missing malformed or client-parameterized callback configuration" in {
      SamlBrowserPolicy.fromConfig(ConfigFactory.empty(), new InvitationSecretService) must beNone
      Seq(
        "/relative",
        "javascript:alert(1)",
        "https://user@app.example.test/path",
        "https://app.example.test/path?redirect=other",
        "https://app.example.test/path#secret"
      ).forall(url => policy(callback = url).isEmpty) must beTrue
    }
  }
}
