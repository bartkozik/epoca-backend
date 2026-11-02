package pl.iterators.epoca.oauth.services

import com.typesafe.config.{ConfigFactory, ConfigResolveOptions}
import org.mindrot.jbcrypt.BCrypt
import org.specs2.mutable.SpecificationLike
import pl.iterators.epoca.oauth.services.OAuthConfig.OAuthConfigError

import java.nio.charset.StandardCharsets
import java.util.Base64
import scala.concurrent.duration._

class OAuthConfigSpec extends SpecificationLike {
  private val keyA  = Base64.getUrlEncoder.withoutPadding().encodeToString(Array.tabulate[Byte](32)(i => (i + 1).toByte))
  private val keyB  = Base64.getUrlEncoder.withoutPadding().encodeToString(Array.tabulate[Byte](32)(i => (i + 33).toByte))
  private val dummy = BCrypt.hashpw("epoca-oauth-dummy-v1", BCrypt.gensalt(12))
  private val keyRingSecret = Base64.getUrlEncoder
    .withoutPadding()
    .encodeToString(
      s"""{"version":1,"activeKid":"oauth-a","keys":[{"kid":"oauth-a","material":"$keyA","retired":false}]}""".getBytes(StandardCharsets.UTF_8)
    )

  "OAuth configuration" should {
    "load application defaults disabled without signing keys" in {
      val applicationConfig = ConfigFactory
        .parseResources("application.conf")
        .resolve(ConfigResolveOptions.defaults().setAllowUnresolved(true))
      val loaded = OAuthConfig.load(applicationConfig)

      loaded must beRight.like { case config =>
        config.enabled should beFalse
        config.accessTokenLifetime shouldEqual 300.seconds
        config.ipRateLimit.capacity shouldEqual 60
        config.ipRateLimit.refillPeriod shouldEqual 60.seconds
        config.knownClientFailedSecretRateLimit.capacity shouldEqual 10
        config.knownClientFailedSecretRateLimit.refillPeriod shouldEqual 300.seconds
        config.authenticationAuditRetention shouldEqual 90.days
        config.auditCleanupBatchSize must beBetween(1, 1000)
        config.rateLimitCleanupBatchSize must beBetween(1, 10000)
      }
    }

    "accept an enabled valid overlapping key ring and parsed trusted proxy CIDRs" in {
      val loaded = OAuthConfig.load(validEnabledConfig)

      loaded must beRight.like { case config =>
        config.signingKeyRing.map(_.activeKid) shouldEqual Some("oauth-a")
        config.signingKeyRing.toSeq.flatMap(_.verificationKeys.map(_.kid)) shouldEqual Seq("oauth-a", "oauth-b")
        config.trustedProxyCidrs.map(_.toString) shouldEqual Vector("10.0.0.0/8", "2001:db8:0:0:0:0:0:0/32")
        config.toString must not(contain(keyA))
        config.toString must not(contain(keyB))
        config.toString must not(contain(dummy))
      }
    }

    "load a valid signing key ring from a secret when file keys are absent" in {
      OAuthConfig.load(withoutFileKeys(s"""oauth.enabled = false
                                          |oauth.signing.keyRingB64 = "$keyRingSecret"""".stripMargin)) must beRight.like { case config =>
        config.enabled should beFalse
        config.signingKeyRing.map(_.activeKid) shouldEqual Some("oauth-a")
        config.signingKeyRing.toSeq.flatMap(_.keys.map(_.kid)) shouldEqual Seq("oauth-a")
        config.toString must not(contain(keyA))
      }
    }

    "reject a nonblank secret when file signing keys are present" in {
      OAuthConfig.load(withOverride(s"""oauth.signing.keyRingB64 = "$keyRingSecret"""")) shouldEqual Left(OAuthConfigError.InvalidConfiguration)
    }

    "treat blank secret and empty file keys as absent source when enabled" in {
      OAuthConfig.load(withoutFileKeys("""oauth.enabled = true
                                          |oauth.signing.keyRingB64 = "   """".stripMargin)) shouldEqual Left(OAuthConfigError.MissingActiveKey)
    }

    "reject a secret with leading or trailing whitespace without normalizing it" in {
      OAuthConfig.load(withoutFileKeys(s"""oauth.enabled = false
                                          |oauth.signing.keyRingB64 = " $keyRingSecret"""".stripMargin)) shouldEqual Left(
        OAuthConfigError.InvalidConfiguration
      )
      OAuthConfig.load(withoutFileKeys(s"""oauth.enabled = false
                                          |oauth.signing.keyRingB64 = "$keyRingSecret """".stripMargin)) shouldEqual Left(
        OAuthConfigError.InvalidConfiguration
      )
    }

    "reject a malformed secret even when OAuth is disabled without echoing values" in {
      val sentinel = "oauth-config-secret-sentinel"
      val result = OAuthConfig.load(withoutFileKeys(s"""oauth.enabled = false
                                                       |oauth.signing.keyRingB64 = "$sentinel+"""".stripMargin))

      result shouldEqual Left(OAuthConfigError.InvalidConfiguration)
      result.toString must not(contain(sentinel))
    }

    "reuse signing key semantic validation for a structurally valid secret" in {
      val weakSecret = keyRingSecretWithMaterial("AQID")

      OAuthConfig.load(withoutFileKeys(s"""oauth.enabled = false
                                          |oauth.signing.keyRingB64 = "$weakSecret"""".stripMargin)) shouldEqual Left(OAuthConfigError.WeakSigningKey)
    }

    "reject an enabled configuration when neither file keys nor secret are supplied" in {
      OAuthConfig.load(withoutFileKeys("oauth.enabled = true")) shouldEqual Left(OAuthConfigError.MissingActiveKey)
    }

    "redacts a signing key ring directly" in {
      val keyRing = OAuthConfig.OAuthSigningKeyRing("oauth-a", Vector(OAuthConfig.OAuthSigningKey("oauth-a", keyA, retired = false)))

      keyRing.toString must not(contain(keyA))
      keyRing.toString must contain("<redacted>")
    }

    "redacts internal signing key carriers directly" in {
      val sentinel     = "oauth-internal-carrier-sentinel"
      val decodedBytes = sentinel.getBytes(StandardCharsets.UTF_8).toVector
      val raw          = OAuthConfig.RawSigningKey("oauth-a", sentinel, retired = false)
      val parsed       = OAuthConfig.ParsedKey(OAuthConfig.OAuthSigningKey("oauth-a", sentinel, retired = false), decodedBytes)

      raw.toString must not(contain(sentinel))
      raw.toString must contain("<redacted>")
      parsed.toString must not(contain(sentinel))
      parsed.toString must not(contain(decodedBytes.toString))
      parsed.toString must contain("<redacted>")
    }

    "reject enabled missing active keys, duplicate kids/material, short or malformed material, retired active keys, and human-equal material" in {
      OAuthConfig.load(validEnabledConfig.withoutPath("oauth.signing.activeKid")) shouldEqual Left(OAuthConfigError.MissingActiveKey)
      OAuthConfig.load(
        withKeys(s"""{ kid = "oauth-a", material = "$keyA", retired = false }, { kid = "oauth-a", material = "$keyB", retired = false }""")
      ) shouldEqual Left(OAuthConfigError.DuplicateKeyId)
      OAuthConfig.load(
        withKeys(s"""{ kid = "oauth-a", material = "$keyA", retired = false }, { kid = "oauth-b", material = "$keyA", retired = false }""")
      ) shouldEqual Left(OAuthConfigError.DuplicateKeyMaterial)
      OAuthConfig.load(
        withKeys(s"""{ kid = "oauth-a", material = "AQID", retired = false }, { kid = "oauth-b", material = "$keyB", retired = false }""")
      ) shouldEqual Left(OAuthConfigError.WeakSigningKey)
      OAuthConfig.load(
        withKeys(s"""{ kid = "oauth-a", material = "not+base64", retired = false }, { kid = "oauth-b", material = "$keyB", retired = false }""")
      ) shouldEqual Left(OAuthConfigError.InvalidSigningKey)
      OAuthConfig.load(
        withKeys(s"""{ kid = "oauth-a", material = "$keyA", retired = true }, { kid = "oauth-b", material = "$keyB", retired = false }""")
      ) shouldEqual Left(OAuthConfigError.RetiredActiveKey)
      OAuthConfig.load(withOverride(s"login.jwtSecret = $keyA")) shouldEqual Left(OAuthConfigError.HumanKeyReuse)
    }

    "reject a known development placeholder after base64url decoding" in {
      val encodedPlaceholder =
        Base64.getUrlEncoder.withoutPadding().encodeToString("developmentdevelopmentdevelopmentdevelopment".getBytes(StandardCharsets.UTF_8))

      encodedPlaceholder.toLowerCase must not(contain("development"))
      OAuthConfig.load(
        withKeys(
          s"""{ kid = "oauth-a", material = "$encodedPlaceholder", retired = false }, { kid = "oauth-b", material = "$keyB", retired = false }"""
        )
      ) shouldEqual Left(OAuthConfigError.InvalidSigningKey)
    }

    "reject placeholders, blank or invalid issuer/audience/kid, bad dummy bcrypt, limits, retention, batches, and trusted CIDRs" in {
      OAuthConfig.load(
        withKeys(
          s"""{ kid = "oauth-a", material = "YouNeedToOverrideIt", retired = false }, { kid = "oauth-b", material = "$keyB", retired = false }"""
        )
      ) shouldEqual Left(OAuthConfigError.InvalidSigningKey)
      OAuthConfig.load(
        withKeys(
          s"""{ kid = "oauth-a", material = "developmentdevelopmentdevelopmentdevelopment", retired = false }, { kid = "oauth-b", material = "$keyB", retired = false }"""
        )
      ) shouldEqual Left(OAuthConfigError.InvalidSigningKey)
      OAuthConfig.load(withOverride("oauth.issuer = not-a-uri")) shouldEqual Left(OAuthConfigError.InvalidIssuer)
      OAuthConfig.load(withOverride("oauth.audience = \" \"")) shouldEqual Left(OAuthConfigError.InvalidAudience)
      OAuthConfig.load(withOverride("oauth.signing.activeKid = \" \"")) shouldEqual Left(OAuthConfigError.InvalidKeyId)
      OAuthConfig.load(withOverride("oauth.dummyBcryptHash = nope")) shouldEqual Left(OAuthConfigError.InvalidDummyBcryptHash)
      OAuthConfig.load(withOverride("oauth.ipRateLimit.capacity = 0")) shouldEqual Left(OAuthConfigError.InvalidRateLimit)
      OAuthConfig.load(withOverride("oauth.authenticationAuditRetention = 89d")) shouldEqual Left(OAuthConfigError.InvalidAuditRetention)
      OAuthConfig.load(withOverride("oauth.auditCleanupBatchSize = 0")) shouldEqual Left(OAuthConfigError.InvalidCleanupBatchSize)
      OAuthConfig.load(withOverride("oauth.auditCleanupBatchSize = 1001")) shouldEqual Left(OAuthConfigError.InvalidCleanupBatchSize)
      OAuthConfig.load(withOverride("oauth.rateLimitCleanupBatchSize = 10000")) must beRight
      OAuthConfig.load(withOverride("oauth.rateLimitCleanupBatchSize = 10001")) shouldEqual Left(OAuthConfigError.InvalidCleanupBatchSize)
      OAuthConfig.load(withOverride("oauth.trustedProxyCidrs = [\"10.0.0.0/99\"]")) shouldEqual Left(OAuthConfigError.InvalidTrustedProxyCidr)
    }
  }

  private def validEnabledConfig = ConfigFactory
    .parseString(s"""
      oauth {
        enabled = true
        issuer = "https://api.epoca.example/oauth"
        audience = "epoca-api"
        dummyBcryptHash = "$dummy"
        signing {
          activeKid = "oauth-a"
          keys = [
            { kid = "oauth-a", material = "$keyA", retired = false },
            { kid = "oauth-b", material = "$keyB", retired = false }
          ]
        }
        ipRateLimit { capacity = 60, refillPeriod = 60s }
        knownClientFailedSecretRateLimit { capacity = 10, refillPeriod = 300s }
        authenticationAuditRetention = 90d
        auditCleanupBatchSize = 500
        rateLimitCleanupBatchSize = 500
        trustedProxyCidrs = ["10.0.0.0/8", "2001:db8::/32"]
      }
      login.jwtSecret = "human-key-that-is-distinct"
    """)
    .resolve()

  private def withOverride(value: String) = ConfigFactory.parseString(value).withFallback(validEnabledConfig).resolve()

  private def withKeys(values: String) =
    ConfigFactory.parseString(s"oauth.signing.keys = [$values]").withFallback(validEnabledConfig).resolve()

  private def keyRingSecretWithMaterial(material: String): String =
    Base64.getUrlEncoder
      .withoutPadding()
      .encodeToString(
        s"""{"version":1,"activeKid":"oauth-a","keys":[{"kid":"oauth-a","material":"$material","retired":false}]}""".getBytes(StandardCharsets.UTF_8)
      )

  private def withoutFileKeys(overrideValue: String) =
    ConfigFactory
      .parseString(s"""
        oauth.signing.keys = []
        $overrideValue
      """)
      .withFallback(validEnabledConfig)
      .resolve()
}
