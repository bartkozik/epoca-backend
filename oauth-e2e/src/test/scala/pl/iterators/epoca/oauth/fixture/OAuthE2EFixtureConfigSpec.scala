package pl.iterators.epoca.oauth.fixture

import com.typesafe.config.{Config, ConfigFactory, ConfigResolveOptions}
import org.specs2.mutable.SpecificationLike
import pl.iterators.epoca.oauth.fixture.OAuthE2EFixtureConfig.{ControllerLeaseObservation, FixtureRole, OAuthE2EFixtureConfigError}

import java.nio.charset.StandardCharsets
import java.util.Base64

class OAuthE2EFixtureConfigSpec extends SpecificationLike with org.mockito.MockitoSugar {
  private val applicationConfig = ConfigFactory
    .parseResources("oauth-e2e.conf")
    .withFallback(ConfigFactory.parseResources("application.conf"))
    .resolve(ConfigResolveOptions.defaults().setAllowUnresolved(true))

  private val signingKeyA    = encodedBytes(1)
  private val signingKeyB    = encodedBytes(33)
  private val controlToken   = versionedToken(65)
  private val loginSecret    = "oauth-e2e-login-jwt-secret-distinct-from-control"
  private val databaseSecret = "oauth-e2e-database-secret-distinct-from-control"

  "OAuth E2E fixture configuration" should {
    "wire fixture time only into a guarded E2E application loader" in {
      val guarded = applicationLoader(withOverride(""))
      guarded.oauthAuthoritativeTime must beAnInstanceOf[FixtureOAuthAuthoritativeTime]
      guarded.oauthE2EFixtureRepository must beSome

      val disabled = applicationLoader(applicationConfig)
      disabled.oauthAuthoritativeTime must beAnInstanceOf[pl.iterators.epoca.oauth.time.PostgresOAuthAuthoritativeTime]
      disabled.oauthE2EFixtureRepository must beNone
    }

    "reject unsafe configuration before constructing the E2E time provider" in {
      applicationLoader(withOverride("app.environment = production")).oauthAuthoritativeTime must throwA[IllegalArgumentException]
    }

    "load application defaults with both capabilities disabled" in {
      OAuthE2EFixtureConfig.load(applicationConfig) must beRight.like { case config =>
        config.timeEnabled should beFalse
        config.controlListenerEnabled should beFalse
        config.role shouldEqual FixtureRole.Disabled
        config.controllerLeaseObservationGuard should beNone
      }
    }

    "reject every base guard independently" in {
      OAuthE2EFixtureConfig.load(withOverride("oauth.enabled = false")) shouldEqual
        Left(OAuthE2EFixtureConfigError.OAuthDisabledOrInvalid)
      OAuthE2EFixtureConfig.load(withOverride("app.environment = other")) shouldEqual
        Left(OAuthE2EFixtureConfigError.InvalidEnvironment)
      OAuthE2EFixtureConfig.load(validControllerConfig.withoutPath("oauth.e2eFixture.disposableAck")) shouldEqual
        Left(OAuthE2EFixtureConfigError.InvalidDisposableAcknowledgement)
      OAuthE2EFixtureConfig.load(withOverride("oauth.e2eFixture.disposableAck = wrong")) shouldEqual
        Left(OAuthE2EFixtureConfigError.InvalidDisposableAcknowledgement)
      OAuthE2EFixtureConfig.load(withOverride("db.url = \"jdbc:postgresql://127.0.0.1:5432/epoca\"")) shouldEqual
        Left(OAuthE2EFixtureConfigError.UnsafeDatabase)
      OAuthE2EFixtureConfig.load(validControllerConfig.withoutPath("oauth.e2eFixture.deploymentTier")) shouldEqual
        Left(OAuthE2EFixtureConfigError.InvalidDeploymentTier)
      Seq("staging", "production", "Disposable").foreach { value =>
        OAuthE2EFixtureConfig.load(withOverride(s"oauth.e2eFixture.deploymentTier = $value")) shouldEqual
          Left(OAuthE2EFixtureConfigError.InvalidDeploymentTier)
      }
      success
    }

    "not treat localhost or otherwise valid controller values as compensation for a failed base guard" in {
      val wrongEnvironmentAndUnsafeDatabase = withOverride("""
          app.environment = production
          db.url = "jdbc:postgresql://127.0.0.1:5432/epoca"
          oauth.e2eFixture.listenerInterface = "127.0.0.1"
          oauth.e2eFixture.listenerPort = 19000
        """)

      OAuthE2EFixtureConfig.load(wrongEnvironmentAndUnsafeDatabase) shouldEqual
        Left(OAuthE2EFixtureConfigError.InvalidEnvironment)
    }

    "reject null and wrong types for both capability flags as InvalidConfiguration" in {
      val malformed = Seq(
        "time-null"       -> "oauth.e2eFixture.timeEnabled = null",
        "time-object"     -> "oauth.e2eFixture.timeEnabled = { unsafe = true }",
        "listener-null"   -> "oauth.e2eFixture.controlListenerEnabled = null",
        "listener-object" -> "oauth.e2eFixture.controlListenerEnabled = { unsafe = true }"
      )

      malformed.foreach { case (_, overrideValue) =>
        OAuthE2EFixtureConfig.load(withOverride(overrideValue)) shouldEqual Left(OAuthE2EFixtureConfigError.InvalidConfiguration)
      }
      success
    }

    "reject null and wrong types for every base guard as InvalidConfiguration without echoing values" in {
      val sentinel = "oauth-e2e-type-sentinel"
      val malformed = Seq(
        "oauth-null"         -> "oauth.enabled = null",
        "oauth-object"       -> s"oauth.enabled = { unsafe = \"$sentinel\" }",
        "environment-null"   -> "app.environment = null",
        "environment-object" -> s"app.environment = { unsafe = \"$sentinel\" }",
        "ack-null"           -> "oauth.e2eFixture.disposableAck = null",
        "ack-object"         -> s"oauth.e2eFixture.disposableAck = { unsafe = \"$sentinel\" }",
        "database-null"      -> "db.url = null",
        "database-object"    -> s"db.url = { unsafe = \"$sentinel\" }",
        "tier-null"          -> "oauth.e2eFixture.deploymentTier = null",
        "tier-object"        -> s"oauth.e2eFixture.deploymentTier = { unsafe = \"$sentinel\" }"
      )

      malformed.foreach { case (_, overrideValue) =>
        val result = OAuthE2EFixtureConfig.load(withOverride(overrideValue))
        result shouldEqual Left(OAuthE2EFixtureConfigError.InvalidConfiguration)
        result.toString must not(contain(sentinel))
      }
      success
    }

    "reject a control listener unless fixture time is enabled" in {
      OAuthE2EFixtureConfig.load(withOverride("oauth.e2eFixture.timeEnabled = false")) shouldEqual
        Left(OAuthE2EFixtureConfigError.ControlListenerRequiresFixtureTime)
    }

    "require the exact loopback interface for the controller" in {
      OAuthE2EFixtureConfig.load(validControllerConfig.withoutPath("oauth.e2eFixture.listenerInterface")) shouldEqual
        Left(OAuthE2EFixtureConfigError.InvalidControlInterface)
      Seq("0.0.0.0", "::1", "localhost", "127.0.0.2").foreach { value =>
        OAuthE2EFixtureConfig.load(withOverride(s"oauth.e2eFixture.listenerInterface = \"$value\"")) shouldEqual
          Left(OAuthE2EFixtureConfigError.InvalidControlInterface)
      }
      success
    }

    "require a controller port in the exact 19000 to 19999 range" in {
      OAuthE2EFixtureConfig.load(validControllerConfig.withoutPath("oauth.e2eFixture.listenerPort")) shouldEqual
        Left(OAuthE2EFixtureConfigError.InvalidControlPort)
      Seq(18999, 20000, 0, 65536).foreach { value =>
        OAuthE2EFixtureConfig.load(withOverride(s"oauth.e2eFixture.listenerPort = $value")) shouldEqual
          Left(OAuthE2EFixtureConfigError.InvalidControlPort)
      }
      OAuthE2EFixtureConfig.load(withOverride("oauth.e2eFixture.listenerPort = 19000")) must beRight
      OAuthE2EFixtureConfig.load(withOverride("oauth.e2eFixture.listenerPort = 19999")) must beRight
    }

    "reject null and wrong controller interface, port, and token types as InvalidConfiguration without echoing values" in {
      val sentinel = "oauth-e2e-controller-type-sentinel"
      val malformed = Seq(
        "interface-null"   -> "oauth.e2eFixture.listenerInterface = null",
        "interface-object" -> s"oauth.e2eFixture.listenerInterface = { unsafe = \"$sentinel\" }",
        "port-null"        -> "oauth.e2eFixture.listenerPort = null",
        "port-object"      -> s"oauth.e2eFixture.listenerPort = { unsafe = \"$sentinel\" }",
        "token-null"       -> "oauth.e2eFixture.controlToken = null",
        "token-object"     -> s"oauth.e2eFixture.controlToken = { unsafe = \"$sentinel\" }"
      )

      malformed.foreach { case (_, overrideValue) =>
        val result = OAuthE2EFixtureConfig.load(withOverride(overrideValue))
        result shouldEqual Left(OAuthE2EFixtureConfigError.InvalidConfiguration)
        result.toString must not(contain(sentinel))
      }
      success
    }

    "require a non-placeholder controller token decoding to at least 32 bytes" in {
      OAuthE2EFixtureConfig.load(validControllerConfig.withoutPath("oauth.e2eFixture.controlToken")) shouldEqual
        Left(OAuthE2EFixtureConfigError.InvalidControlToken)
      OAuthE2EFixtureConfig.load(withOverride("oauth.e2eFixture.controlToken = \"not+base64url\"")) shouldEqual
        Left(OAuthE2EFixtureConfigError.InvalidControlToken)
      OAuthE2EFixtureConfig.load(withOverride(s"oauth.e2eFixture.controlToken = \"${versionedToken(1, size = 31)}\"")) shouldEqual
        Left(OAuthE2EFixtureConfigError.InvalidControlToken)
      OAuthE2EFixtureConfig.load(withOverride(s"oauth.e2eFixture.controlToken = \"${encodedBytes(65)}\"")) shouldEqual
        Left(OAuthE2EFixtureConfigError.InvalidControlToken)
      OAuthE2EFixtureConfig.load(
        withOverride(s"oauth.e2eFixture.controlToken = \"${controlToken.replace("oauth-e2e-v1.", "oauth-e2e-v2.")}\"")
      ) shouldEqual
        Left(OAuthE2EFixtureConfigError.InvalidControlToken)
      OAuthE2EFixtureConfig.load(withOverride(s"oauth.e2eFixture.controlToken = \"${controlToken}A\"")) shouldEqual
        Left(OAuthE2EFixtureConfigError.InvalidControlToken)

      val placeholderBytes = Array.fill[Byte](32)('x'.toByte)
      "development".getBytes(StandardCharsets.UTF_8).copyToArray(placeholderBytes)
      val encodedPlaceholder = versionedToken(placeholderBytes)
      OAuthE2EFixtureConfig.load(withOverride(s"oauth.e2eFixture.controlToken = \"$encodedPlaceholder\"")) shouldEqual
        Left(OAuthE2EFixtureConfigError.InvalidControlToken)
    }

    "round-trip the one versioned token format through a deterministic generator seam without exposing it in rendering" in {
      val expectedBytes = tokenBytes(65)
      val source        = entropySource(expectedBytes)

      new OAuthE2EFixtureConfig.ControlTokenGenerator(source).generate() must beRight.like { case token =>
        token.value shouldEqual controlToken
        token.toString shouldEqual "<redacted>"
        OAuthE2EFixtureConfig.load(withOverride(s"oauth.e2eFixture.controlToken = \"${token.value}\"")) must beRight
      }
    }

    "reject obvious all-zero, repeated, small-period, and low-diversity token material without claiming entropy proof" in {
      val weakMaterials = Seq(
        "all-zero"      -> Array.fill[Byte](32)(0),
        "repeated"      -> Array.fill[Byte](32)(0x5a.toByte),
        "small-period"  -> (tokenBytes(1, 16) ++ tokenBytes(1, 16)),
        "low-diversity" -> Array[Byte](0, 1, 4, 9, 2, 10, 5, 3, 7, 6, 8, 11, 12, 13, 14, 0, 2, 5, 10, 4, 14, 11, 9, 8, 1, 12, 7, 3, 13, 6, 0, 14)
      )

      weakMaterials.foreach { case (_, bytes) =>
        val weakToken = versionedToken(bytes)
        OAuthE2EFixtureConfig.load(withOverride(s"oauth.e2eFixture.controlToken = \"$weakToken\"")) shouldEqual
          Left(OAuthE2EFixtureConfigError.InvalidControlToken)
        new OAuthE2EFixtureConfig.ControlTokenGenerator(entropySource(bytes)).generate() shouldEqual
          Left(OAuthE2EFixtureConfigError.InvalidControlToken)
      }
      success
    }

    "reject a controller token reusing login, OAuth signing, or database secrets" in {
      OAuthE2EFixtureConfig.load(withOverride(s"login.jwtSecret = \"$controlToken\"")) shouldEqual
        Left(OAuthE2EFixtureConfigError.InvalidControlToken)
      OAuthE2EFixtureConfig.load(withOverride(s"db.password = \"$controlToken\"")) shouldEqual
        Left(OAuthE2EFixtureConfigError.InvalidControlToken)
      OAuthE2EFixtureConfig.load(
        withOverride(s"oauth.e2eFixture.controlToken = \"${versionedToken(Base64.getUrlDecoder.decode(signingKeyA))}\"")
      ) shouldEqual
        Left(OAuthE2EFixtureConfigError.InvalidControlToken)
    }

    "reject a login JWT secret equal to the canonical control-token payload without echoing it" in {
      val payload = controlToken.stripPrefix("oauth-e2e-v1.")
      val result  = OAuthE2EFixtureConfig.load(withOverride(s"login.jwtSecret = \"$payload\""))

      result shouldEqual Left(OAuthE2EFixtureConfigError.InvalidControlToken)
      result.toString must not(contain(payload))
    }

    "reject a database secret equal to the canonical control-token payload without echoing it" in {
      val payload = controlToken.stripPrefix("oauth-e2e-v1.")
      val result  = OAuthE2EFixtureConfig.load(withOverride(s"db.password = \"$payload\""))

      result shouldEqual Left(OAuthE2EFixtureConfigError.InvalidControlToken)
      result.toString must not(contain(payload))
    }

    "reject a non-canonical Base64URL alias of OAuth signing-key bytes" in {
      val canonicalAlias    = versionedToken(Base64.getUrlDecoder.decode(signingKeyA))
      val nonCanonicalAlias = alternateLastCharacterAlias(canonicalAlias)

      nonCanonicalAlias shouldNotEqual canonicalAlias
      Base64.getUrlDecoder.decode(nonCanonicalAlias.stripPrefix("oauth-e2e-v1.")).toVector shouldEqual
        Base64.getUrlDecoder.decode(canonicalAlias.stripPrefix("oauth-e2e-v1.")).toVector
      OAuthE2EFixtureConfig.load(withOverride(s"oauth.e2eFixture.controlToken = \"$nonCanonicalAlias\"")) shouldEqual
        Left(OAuthE2EFixtureConfigError.InvalidControlToken)
    }

    "reject listener material on a time-only replica" in {
      val replica = validReplicaConfig

      OAuthE2EFixtureConfig.load(withOverride("oauth.e2eFixture.listenerInterface = \"127.0.0.1\"", replica)) shouldEqual
        Left(OAuthE2EFixtureConfigError.UnexpectedControlConfiguration)
      OAuthE2EFixtureConfig.load(withOverride("oauth.e2eFixture.listenerPort = 19000", replica)) shouldEqual
        Left(OAuthE2EFixtureConfigError.UnexpectedControlConfiguration)
      OAuthE2EFixtureConfig.load(withOverride(s"oauth.e2eFixture.controlToken = \"$controlToken\"", replica)) shouldEqual
        Left(OAuthE2EFixtureConfigError.UnexpectedControlConfiguration)
    }

    "reject explicit null listener material on a time-only replica" in {
      Seq("listenerInterface", "listenerPort", "controlToken").foreach { path =>
        OAuthE2EFixtureConfig.load(withOverride(s"oauth.e2eFixture.$path = null", validReplicaConfig)) shouldEqual
          Left(OAuthE2EFixtureConfigError.UnexpectedControlConfiguration)
      }
      success
    }

    "fail closed on observed lease results while deferring atomic exclusivity and no-takeover proof to Task 3 real PostgreSQL" in {
      OAuthE2EFixtureConfig.load(validControllerConfig) must beRight.like { case config =>
        config.role shouldEqual FixtureRole.Controller
        config.controllerLeaseObservationGuard must beSome.which { guard =>
          guard.validateObserved(ControllerLeaseObservation.ObservedUnclaimed) == Right(()) &&
          guard.validateObserved(ControllerLeaseObservation.ObservedClaimed) == Left(OAuthE2EFixtureConfigError.ObservedControllerLeaseClaimed) &&
          guard.validateObserved(ControllerLeaseObservation.ObservedControllerLoss) == Left(
            OAuthE2EFixtureConfigError.ObservedControllerLeaseTerminal
          )
        }
        config.toString must contain("controllerLeaseObservationGuard=RequireObservedUnclaimed")
      }
      OAuthE2EFixtureConfig.load(validReplicaConfig) must beRight.like { case config =>
        config.role shouldEqual FixtureRole.TimeOnlyReplica
        config.controllerLeaseObservationGuard should beNone
      }
    }

    "redact the control token and all sibling secrets from successful and failed rendering" in {
      OAuthE2EFixtureConfig.load(validControllerConfig) must beRight.like { case config =>
        val rendered = config.toString
        rendered must contain("controlToken=<redacted>")
        rendered must not(contain(controlToken))
        rendered must not(contain(loginSecret))
        rendered must not(contain(databaseSecret))
        rendered must not(contain(signingKeyA))
        rendered must not(contain(signingKeyB))
      }

      val failed          = OAuthE2EFixtureConfig.load(withOverride(s"login.jwtSecret = \"$controlToken\""))
      val renderedFailure = failed.toString
      renderedFailure must not(contain(controlToken))
      renderedFailure must not(contain(databaseSecret))
      renderedFailure must not(contain(signingKeyA))
      renderedFailure must not(contain(signingKeyB))
    }
  }

  private lazy val validReplicaConfig: Config = ConfigFactory
    .parseString(s"""
      app.environment = "oauth-e2e"
      db.url = "jdbc:postgresql://127.0.0.1:5432/oauth_e2e_run_001"
      db.password = "$databaseSecret"
      login.jwtSecret = "$loginSecret"

      oauth.enabled = true
      oauth.signing.activeKid = "oauth-a"
      oauth.signing.keys = [
        { kid = "oauth-a", material = "$signingKeyA", retired = false },
        { kid = "oauth-b", material = "$signingKeyB", retired = false }
      ]
      oauth.e2eFixture.timeEnabled = true
      oauth.e2eFixture.controlListenerEnabled = false
      oauth.e2eFixture.disposableAck = "oauth-e2e-disposable-v1"
      oauth.e2eFixture.deploymentTier = "disposable"
    """)
    .withFallback(applicationConfig)
    .resolve(ConfigResolveOptions.defaults().setAllowUnresolved(true))

  private lazy val validControllerConfig: Config = ConfigFactory
    .parseString(s"""
      oauth.e2eFixture.controlListenerEnabled = true
      oauth.e2eFixture.listenerInterface = "127.0.0.1"
      oauth.e2eFixture.listenerPort = 19000
      oauth.e2eFixture.controlToken = "$controlToken"
    """)
    .withFallback(validReplicaConfig)
    .resolve(ConfigResolveOptions.defaults().setAllowUnresolved(true))

  private def withOverride(value: String, fallback: Config = validControllerConfig): Config =
    ConfigFactory.parseString(value).withFallback(fallback).resolve(ConfigResolveOptions.defaults().setAllowUnresolved(true))

  private def encodedBytes(start: Int, size: Int = 32): String =
    Base64.getUrlEncoder.withoutPadding().encodeToString(tokenBytes(start, size))

  private def versionedToken(start: Int, size: Int = 32): String = versionedToken(tokenBytes(start, size))

  private def versionedToken(bytes: Array[Byte]): String =
    s"oauth-e2e-v1.${Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)}"

  private def tokenBytes(start: Int, size: Int = 32): Array[Byte] =
    Array.tabulate[Byte](size)(index => (start + index).toByte)

  private def entropySource(bytes: Array[Byte]): OAuthE2EFixtureConfig.ControlTokenEntropySource =
    new OAuthE2EFixtureConfig.ControlTokenEntropySource {
      override def nextBytes(destination: Array[Byte]): Unit = {
        bytes.copyToArray(destination)
        ()
      }
    }

  private def alternateLastCharacterAlias(value: String): String = {
    val alphabet  = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    val lastIndex = alphabet.indexOf(value.takeRight(1))
    value.dropRight(1) + alphabet.charAt(lastIndex + 1)
  }
  private def applicationLoader(config: Config): OAuthE2EApplicationLoader =
    new OAuthE2EApplicationLoader(
      config,
      scala.concurrent.ExecutionContext.global,
      cats.effect.unsafe.IORuntime.global,
      mock[pl.iterators.epoca.utils.db.PostgresIOTransactor],
      mock[sttp.client4.WebSocketStreamBackend[cats.effect.IO, sttp.capabilities.fs2.Fs2Streams[cats.effect.IO]]],
      mock[pl.iterators.epoca.utils.job.TriggeredJobScheduler],
      mock[cats.effect.std.Supervisor[cats.effect.IO]]
    )

}
