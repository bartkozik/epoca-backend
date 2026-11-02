package pl.iterators.epoca.oauth.fixture

import com.typesafe.config.{Config, ConfigException}
import pl.iterators.epoca.oauth.services.OAuthConfig

import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.{Base64, Locale}
import scala.util.Try
import scala.util.control.NonFatal

final class OAuthE2EFixtureConfig private (
  val timeEnabled: Boolean,
  val controlListenerEnabled: Boolean,
  val role: OAuthE2EFixtureConfig.FixtureRole,
  val controlListener: Option[OAuthE2EFixtureConfig.ControlListener],
  val controllerLeaseObservationGuard: Option[OAuthE2EFixtureConfig.ControllerLeaseObservationGuard]) {
  override def toString: String = {
    val listenerInterface = controlListener.map(_.interface).getOrElse("<absent>")
    val listenerPort      = controlListener.map(_.port.toString).getOrElse("<absent>")
    val controlToken      = if (controlListener.isDefined) "<redacted>" else "<absent>"
    s"OAuthE2EFixtureConfig(timeEnabled=$timeEnabled,controlListenerEnabled=$controlListenerEnabled," +
      s"role=$role,listenerInterface=$listenerInterface,listenerPort=$listenerPort,controlToken=$controlToken," +
      s"controllerLeaseObservationGuard=${controllerLeaseObservationGuard.getOrElse("<absent>")})"
  }
}

object OAuthE2EFixtureConfig {
  sealed trait OAuthE2EFixtureConfigError
  object OAuthE2EFixtureConfigError {
    case object OAuthDisabledOrInvalid             extends OAuthE2EFixtureConfigError
    case object InvalidEnvironment                 extends OAuthE2EFixtureConfigError
    case object InvalidDisposableAcknowledgement   extends OAuthE2EFixtureConfigError
    case object UnsafeDatabase                     extends OAuthE2EFixtureConfigError
    case object InvalidDeploymentTier              extends OAuthE2EFixtureConfigError
    case object ControlListenerRequiresFixtureTime extends OAuthE2EFixtureConfigError
    case object InvalidControlInterface            extends OAuthE2EFixtureConfigError
    case object InvalidControlPort                 extends OAuthE2EFixtureConfigError
    case object InvalidControlToken                extends OAuthE2EFixtureConfigError
    case object UnexpectedControlConfiguration     extends OAuthE2EFixtureConfigError
    case object ObservedControllerLeaseClaimed     extends OAuthE2EFixtureConfigError
    case object ObservedControllerLeaseTerminal    extends OAuthE2EFixtureConfigError
    case object InvalidConfiguration               extends OAuthE2EFixtureConfigError
  }

  sealed trait FixtureRole
  object FixtureRole {
    case object Disabled        extends FixtureRole
    case object TimeOnlyReplica extends FixtureRole
    case object Controller      extends FixtureRole
  }

  sealed trait ControllerLeaseObservation
  object ControllerLeaseObservation {
    case object ObservedUnclaimed      extends ControllerLeaseObservation
    case object ObservedClaimed        extends ControllerLeaseObservation
    case object ObservedControllerLoss extends ControllerLeaseObservation
  }

  sealed trait ControllerLeaseObservationGuard {
    def validateObserved(observation: ControllerLeaseObservation): Either[OAuthE2EFixtureConfigError, Unit]
  }

  object ControllerLeaseObservationGuard {
    // Task 1 only validates a Task 3 observation; atomic claim/exclusivity and no-takeover remain real-PostgreSQL responsibilities.
    case object RequireObservedUnclaimed extends ControllerLeaseObservationGuard {
      override def validateObserved(observation: ControllerLeaseObservation): Either[OAuthE2EFixtureConfigError, Unit] =
        observation match {
          case ControllerLeaseObservation.ObservedUnclaimed      => Right(())
          case ControllerLeaseObservation.ObservedClaimed        => Left(OAuthE2EFixtureConfigError.ObservedControllerLeaseClaimed)
          case ControllerLeaseObservation.ObservedControllerLoss => Left(OAuthE2EFixtureConfigError.ObservedControllerLeaseTerminal)
        }
    }
  }

  final class ControlToken private[fixture] (private[fixture] val value: String) {
    override def toString: String = "<redacted>"
  }

  private[fixture] trait ControlTokenEntropySource {
    def nextBytes(destination: Array[Byte]): Unit
  }

  final class ControlTokenGenerator private[fixture] (entropySource: ControlTokenEntropySource) {
    def generate(): Either[OAuthE2EFixtureConfigError, ControlToken] = {
      val bytes = new Array[Byte](ControlTokenByteLength)
      entropySource.nextBytes(bytes)
      val value = ControlTokenPrefix + Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)
      validatedControlTokenMaterial(value)
        .toRight(OAuthE2EFixtureConfigError.InvalidControlToken)
        .map(_ => new ControlToken(value))
    }
  }

  object ControlTokenGenerator {
    def secure(): ControlTokenGenerator =
      new ControlTokenGenerator(new ControlTokenEntropySource {
        private val secureRandom = new SecureRandom()

        override def nextBytes(destination: Array[Byte]): Unit = secureRandom.nextBytes(destination)
      })
  }

  final class ControlListener private[fixture] (
    val interface: String,
    val port: Int,
    private[fixture] val token: ControlToken) {
    override def toString: String = s"ControlListener(interface=$interface,port=$port,token=<redacted>)"
  }

  private val FixturePath               = "oauth.e2eFixture"
  private val RequiredEnvironment       = "oauth-e2e"
  private val RequiredAcknowledgement   = "oauth-e2e-disposable-v1"
  private val RequiredDeploymentTier    = "disposable"
  private val RequiredDatabasePrefix    = "oauth_e2e_"
  private val RequiredInterface         = "127.0.0.1"
  private val MinimumPort               = 19000
  private val MaximumPort               = 19999
  private val ControlTokenPrefix        = "oauth-e2e-v1."
  private val ControlTokenByteLength    = 32
  private val ControlTokenPayloadLength = 43
  private val MinimumDistinctTokenBytes = 16
  private val MaximumRejectedPeriod     = 16
  private val Base64UrlPattern          = "^[A-Za-z0-9_-]+$".r
  private val DatabaseNamePattern       = "^[A-Za-z0-9_-]+$".r
  private val KnownPlaceholders =
    Set("youneedtooverrideit", "change-me", "changeme", "development", "dev-secret", "placeholder", "secret", "test-token")

  def load(root: Config): Either[OAuthE2EFixtureConfigError, OAuthE2EFixtureConfig] =
    try loadValidated(root)
    catch {
      case _: ConfigException => Left(OAuthE2EFixtureConfigError.InvalidConfiguration)
      case NonFatal(_)        => Left(OAuthE2EFixtureConfigError.InvalidConfiguration)
    }

  private def loadValidated(root: Config): Either[OAuthE2EFixtureConfigError, OAuthE2EFixtureConfig] = {
    val fixtureConfig          = root.getConfig(FixturePath)
    val timeEnabled            = fixtureConfig.getBoolean("timeEnabled")
    val controlListenerEnabled = fixtureConfig.getBoolean("controlListenerEnabled")

    if (!timeEnabled && !controlListenerEnabled)
      Right(disabled)
    else if (controlListenerEnabled && !timeEnabled)
      Left(OAuthE2EFixtureConfigError.ControlListenerRequiresFixtureTime)
    else
      for {
        oauthConfig <- validatedOAuth(root)
        _           <- exactRootString(root, "app.environment", RequiredEnvironment, OAuthE2EFixtureConfigError.InvalidEnvironment)
        _ <- exactString(fixtureConfig, "disposableAck", RequiredAcknowledgement, OAuthE2EFixtureConfigError.InvalidDisposableAcknowledgement)
        _ <- Either.cond(safeDatabase(root.getString("db.url")), (), OAuthE2EFixtureConfigError.UnsafeDatabase)
        _ <- exactString(fixtureConfig, "deploymentTier", RequiredDeploymentTier, OAuthE2EFixtureConfigError.InvalidDeploymentTier)
        loaded <-
          if (controlListenerEnabled) loadController(root, fixtureConfig, oauthConfig)
          else loadReplica(fixtureConfig)
      } yield loaded
  }

  private def disabled: OAuthE2EFixtureConfig =
    new OAuthE2EFixtureConfig(
      timeEnabled = false,
      controlListenerEnabled = false,
      role = FixtureRole.Disabled,
      controlListener = None,
      controllerLeaseObservationGuard = None
    )

  private def validatedOAuth(root: Config): Either[OAuthE2EFixtureConfigError, OAuthConfig] =
    if (!root.getBoolean("oauth.enabled")) Left(OAuthE2EFixtureConfigError.OAuthDisabledOrInvalid)
    else
      OAuthConfig
        .load(root)
        .left
        .map {
          case OAuthConfig.OAuthConfigError.InvalidConfiguration => OAuthE2EFixtureConfigError.InvalidConfiguration
          case _                                                 => OAuthE2EFixtureConfigError.OAuthDisabledOrInvalid
        }
        .filterOrElse(
          config => config.enabled && config.signingKeyRing.exists(_.activeKey.nonEmpty),
          OAuthE2EFixtureConfigError.OAuthDisabledOrInvalid
        )

  private def loadReplica(fixtureConfig: Config): Either[OAuthE2EFixtureConfigError, OAuthE2EFixtureConfig] = {
    val hasControlMaterial = Vector("listenerInterface", "listenerPort", "controlToken").exists(fixtureConfig.hasPathOrNull)
    Either.cond(
      !hasControlMaterial,
      new OAuthE2EFixtureConfig(
        timeEnabled = true,
        controlListenerEnabled = false,
        role = FixtureRole.TimeOnlyReplica,
        controlListener = None,
        controllerLeaseObservationGuard = None
      ),
      OAuthE2EFixtureConfigError.UnexpectedControlConfiguration
    )
  }

  private def loadController(
    root: Config,
    fixtureConfig: Config,
    oauthConfig: OAuthConfig
  ): Either[OAuthE2EFixtureConfigError, OAuthE2EFixtureConfig] =
    for {
      interface  <- requiredString(fixtureConfig, "listenerInterface", OAuthE2EFixtureConfigError.InvalidControlInterface)
      _          <- Either.cond(interface == RequiredInterface, (), OAuthE2EFixtureConfigError.InvalidControlInterface)
      port       <- requiredInt(fixtureConfig, "listenerPort", OAuthE2EFixtureConfigError.InvalidControlPort)
      _          <- Either.cond(port >= MinimumPort && port <= MaximumPort, (), OAuthE2EFixtureConfigError.InvalidControlPort)
      tokenValue <- requiredString(fixtureConfig, "controlToken", OAuthE2EFixtureConfigError.InvalidControlToken)
      token      <- validateControlToken(root, oauthConfig, tokenValue)
    } yield new OAuthE2EFixtureConfig(
      timeEnabled = true,
      controlListenerEnabled = true,
      role = FixtureRole.Controller,
      controlListener = Some(new ControlListener(interface, port, token)),
      controllerLeaseObservationGuard = Some(ControllerLeaseObservationGuard.RequireObservedUnclaimed)
    )

  private def validateControlToken(
    root: Config,
    oauthConfig: OAuthConfig,
    value: String
  ): Either[OAuthE2EFixtureConfigError, ControlToken] = {
    val decoded     = validatedControlTokenMaterial(value)
    val payload     = value.stripPrefix(ControlTokenPrefix)
    val textSecrets = Vector(root.getString("login.jwtSecret"), root.getString("db.password"))
    val signingSecrets = oauthConfig.signingKeyRing.toVector.flatMap(_.keys).flatMap { key =>
      Try(Base64.getUrlDecoder.decode(key.material)).toOption.map(_.toVector)
    }
    val reused = decoded.exists { material =>
      textSecrets.exists { secret =>
        value == secret ||
        payload == secret ||
        material == secret.getBytes(StandardCharsets.UTF_8).toVector ||
        canonicalBase64UrlMaterial(secret).contains(material)
      } ||
      signingSecrets.contains(material)
    }

    Either.cond(decoded.nonEmpty && !reused, new ControlToken(value), OAuthE2EFixtureConfigError.InvalidControlToken)
  }

  private def validatedControlTokenMaterial(value: String): Option[Vector[Byte]] = {
    val payload = value.stripPrefix(ControlTokenPrefix)
    val exactFormat =
      value.startsWith(ControlTokenPrefix) &&
        value.length == ControlTokenPrefix.length + ControlTokenPayloadLength &&
        payload.length == ControlTokenPayloadLength &&
        Base64UrlPattern.pattern.matcher(payload).matches()

    Option.when(exactFormat)(payload).flatMap { canonicalPayload =>
      canonicalBase64UrlMaterial(canonicalPayload)
        .filter(_.length == ControlTokenByteLength)
        .filterNot(bytes => printableAscii(bytes.toArray).exists(containsKnownPlaceholder))
        .filter(passesObviousWeakMaterialChecks)
    }
  }

  private def canonicalBase64UrlMaterial(value: String): Option[Vector[Byte]] =
    Option.when(Base64UrlPattern.pattern.matcher(value).matches())(value).flatMap { canonicalValue =>
      Try(Base64.getUrlDecoder.decode(canonicalValue)).toOption
        .filter(bytes => Base64.getUrlEncoder.withoutPadding().encodeToString(bytes) == canonicalValue)
        .map(_.toVector)
    }

  // These are closed fail-fast checks for obvious generator/configuration failures, not a mathematical entropy estimate.
  private def passesObviousWeakMaterialChecks(bytes: Vector[Byte]): Boolean = {
    val hasEnoughDiversity = bytes.distinct.size >= MinimumDistinctTokenBytes
    val hasSmallPeriod = (1 to MaximumRejectedPeriod).exists { period =>
      bytes.indices.drop(period).forall(index => bytes(index) == bytes(index % period))
    }
    hasEnoughDiversity && !hasSmallPeriod
  }

  private def safeDatabase(value: String): Boolean = {
    val withoutJdbcPrefix = value.stripPrefix("jdbc:")
    Try(new URI(withoutJdbcPrefix)).toOption.exists { uri =>
      val databaseName = Option(uri.getPath).filter(_.startsWith("/")).map(_.drop(1)).getOrElse("")
      value.startsWith("jdbc:postgresql://") &&
      uri.getScheme == "postgresql" &&
      Option(uri.getHost).exists(_.nonEmpty) &&
      uri.getUserInfo == null &&
      uri.getQuery == null &&
      uri.getFragment == null &&
      !databaseName.contains("/") &&
      DatabaseNamePattern.pattern.matcher(databaseName).matches() &&
      databaseName.startsWith(RequiredDatabasePrefix) &&
      databaseName.length > RequiredDatabasePrefix.length
    }
  }

  private def exactRootString(
    config: Config,
    path: String,
    expected: String,
    error: OAuthE2EFixtureConfigError
  ): Either[OAuthE2EFixtureConfigError, Unit] =
    exactString(config, path, expected, error)

  private def exactString(
    config: Config,
    path: String,
    expected: String,
    error: OAuthE2EFixtureConfigError
  ): Either[OAuthE2EFixtureConfigError, Unit] =
    if (!config.hasPathOrNull(path)) Left(error)
    else Either.cond(config.getString(path) == expected, (), error)

  private def requiredString(
    config: Config,
    path: String,
    error: OAuthE2EFixtureConfigError
  ): Either[OAuthE2EFixtureConfigError, String] =
    if (!config.hasPathOrNull(path)) Left(error)
    else Right(config.getString(path))

  private def requiredInt(
    config: Config,
    path: String,
    error: OAuthE2EFixtureConfigError
  ): Either[OAuthE2EFixtureConfigError, Int] =
    if (!config.hasPathOrNull(path)) Left(error)
    else Right(config.getInt(path))

  private def containsKnownPlaceholder(value: String): Boolean = {
    val normalized = value.trim.toLowerCase(Locale.ROOT)
    KnownPlaceholders.exists(normalized.contains)
  }

  private def printableAscii(bytes: Array[Byte]): Option[String] =
    Option.when(bytes.nonEmpty && bytes.forall(byte => (byte & 0xff) >= 0x20 && (byte & 0xff) <= 0x7e))(new String(bytes, StandardCharsets.US_ASCII))
}
