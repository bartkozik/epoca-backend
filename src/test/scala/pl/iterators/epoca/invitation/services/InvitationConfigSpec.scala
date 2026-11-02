package pl.iterators.epoca.invitation.services

import com.typesafe.config.ConfigFactory
import org.specs2.mutable.Specification

class InvitationConfigSpec extends Specification {
  "invitation configuration" should {
    "default to disabled without origins or a rate key" in {
      InvitationConfig.fromConfig(ConfigFactory.empty()).enabled must beFalse
    }
    "fail closed for enabled configuration without secrets" in {
      InvitationConfig.fromConfig(ConfigFactory.parseString("invitation.featureEnabled=true")) must throwA[IllegalArgumentException]
    }
    "validate exact origins and the application cookie path" in {
      val config = ConfigFactory.parseString("""invitation {
        featureEnabled=true
        allowedOrigins=["https://app.example.com"]
        rateKey="01234567890123456789012345678901"
      }
      app.apiPrefix="api"
      app.apiVersion="v1"
      """)
      val loaded = InvitationConfig.fromConfig(config)
      loaded.browser.get.cookie("safe", 900) must contain("Path=/api/v1; Max-Age=900; HttpOnly; Secure; SameSite=Lax")
      InvitationConfig.fromConfig(
        ConfigFactory.parseString("invitation.allowedOrigins=[\"https://app.example.com/path\"]").withFallback(config)
      ) must throwA[IllegalArgumentException]
    }
  }
}
