package pl.iterators.epoca.oauth.services

import com.typesafe.config.{Config, ConfigException}
import org.mindrot.jbcrypt.BCrypt
import pl.iterators.epoca.oauth.domain.OAuthDomain.OAuthClientSecretHash

import java.net.{InetAddress, URI}
import java.nio.charset.StandardCharsets
import java.util.{Base64, Locale}
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.jdk.DurationConverters._
import scala.util.Try
import scala.util.control.NonFatal

final case class OAuthConfig(
  enabled: Boolean,
  issuer: String,
  audience: String,
  dummyBcryptHash: OAuthClientSecretHash,
  signingKeyRing: Option[OAuthConfig.OAuthSigningKeyRing],
  ipRateLimit: OAuthConfig.OAuthRateLimit,
  knownClientFailedSecretRateLimit: OAuthConfig.OAuthRateLimit,
  authenticationAuditRetention: FiniteDuration,
  auditCleanupBatchSize: Int,
  rateLimitCleanupBatchSize: Int,
  trustedProxyCidrs: Vector[OAuthConfig.OAuthTrustedProxyCidr]) {
  val accessTokenLifetime: FiniteDuration = OAuthJwtService.TokenLifetimeSeconds.seconds

  override def toString: String =
    s"OAuthConfig(enabled=$enabled,issuer=$issuer,audience=$audience,accessTokenLifetime=$accessTokenLifetime," +
      s"signingKeyRing=$signingKeyRing,ipRateLimit=$ipRateLimit," +
      s"knownClientFailedSecretRateLimit=$knownClientFailedSecretRateLimit," +
      s"authenticationAuditRetention=$authenticationAuditRetention,auditCleanupBatchSize=$auditCleanupBatchSize," +
      s"rateLimitCleanupBatchSize=$rateLimitCleanupBatchSize,trustedProxyCidrs=$trustedProxyCidrs,dummyBcryptHash=<redacted>)"
}

object OAuthConfig {
  sealed trait OAuthConfigError
  object OAuthConfigError {
    case object MissingActiveKey        extends OAuthConfigError
    case object DuplicateKeyId          extends OAuthConfigError
    case object DuplicateKeyMaterial    extends OAuthConfigError
    case object WeakSigningKey          extends OAuthConfigError
    case object InvalidSigningKey       extends OAuthConfigError
    case object RetiredActiveKey        extends OAuthConfigError
    case object HumanKeyReuse           extends OAuthConfigError
    case object InvalidIssuer           extends OAuthConfigError
    case object InvalidAudience         extends OAuthConfigError
    case object InvalidKeyId            extends OAuthConfigError
    case object InvalidDummyBcryptHash  extends OAuthConfigError
    case object InvalidRateLimit        extends OAuthConfigError
    case object InvalidAuditRetention   extends OAuthConfigError
    case object InvalidCleanupBatchSize extends OAuthConfigError
    case object InvalidTrustedProxyCidr extends OAuthConfigError
    case object InvalidConfiguration    extends OAuthConfigError
  }

  final case class OAuthSigningKey(
    kid: String,
    material: String,
    retired: Boolean) {
    override def toString: String = s"OAuthSigningKey(kid=$kid,material=<redacted>,retired=$retired)"
  }

  final case class OAuthSigningKeyRing(activeKid: String, keys: Vector[OAuthSigningKey]) {
    def activeKey: Option[OAuthSigningKey] = keys.find(key => key.kid == activeKid && !key.retired)

    def verificationKeys: Vector[OAuthSigningKey] = keys.filterNot(_.retired)

    override def toString: String = s"OAuthSigningKeyRing(activeKid=$activeKid,keys=<redacted>)"
  }

  final case class OAuthRateLimit(capacity: Int, refillPeriod: FiniteDuration)

  final case class OAuthTrustedProxyCidr private (network: Vector[Byte], prefixLength: Int) {
    def contains(address: InetAddress): Boolean =
      address.getAddress.toVector.length == network.length && masked(address.getAddress.toVector, prefixLength) == network

    override def toString: String = s"${InetAddress.getByAddress(network.toArray).getHostAddress}/$prefixLength"
  }

  private[services] final case class RawSigningKey(
    kid: String,
    material: String,
    retired: Boolean) {
    override def toString: String = s"RawSigningKey(kid=$kid,material=<redacted>,retired=$retired)"
  }
  private[services] final case class ParsedKey(value: OAuthSigningKey, decodedMaterial: Vector[Byte]) {
    override def toString: String = s"ParsedKey(value=$value,decodedMaterial=<redacted>)"
  }

  private val BcryptHashPattern            = "^\\$2[ayb]\\$12\\$[./A-Za-z0-9]{53}$".r
  private val IdentifierPattern            = "^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$".r
  private val AudiencePattern              = "^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$".r
  private val Base64UrlPattern             = "^[A-Za-z0-9_-]+$".r
  private val MaxKeyCount                  = 32
  private val MaxRateCapacity              = 1000000
  private val MaxRatePeriod                = 1.day
  private val MaxAuditCleanupBatchSize     = 1000
  private val MaxRateLimitCleanupBatchSize = 10000
  private val MaxTrustedProxyCidrs         = 128
  private val KnownPlaceholders            = Set("youneedtooverrideit", "change-me", "changeme", "development", "dev-secret", "placeholder", "secret")

  def load(root: Config): Either[OAuthConfigError, OAuthConfig] =
    try loadValidated(root)
    catch {
      case _: ConfigException => Left(OAuthConfigError.InvalidConfiguration)
      case NonFatal(_)        => Left(OAuthConfigError.InvalidConfiguration)
    }

  private def loadValidated(root: Config): Either[OAuthConfigError, OAuthConfig] = {
    val config                    = root.getConfig("oauth")
    val enabled                   = config.getBoolean("enabled")
    val issuer                    = config.getString("issuer")
    val audience                  = config.getString("audience")
    val dummyBcryptHash           = config.getString("dummyBcryptHash")
    val authenticationRetention   = config.getDuration("authenticationAuditRetention").toScala
    val auditCleanupBatchSize     = config.getInt("auditCleanupBatchSize")
    val rateLimitCleanupBatchSize = config.getInt("rateLimitCleanupBatchSize")
    val humanKey                  = root.getString("login.jwtSecret")

    for {
      _               <- validateIssuer(issuer)
      _               <- validateAudience(audience)
      dummyHash       <- validateDummyHash(dummyBcryptHash)
      ipRateLimit     <- readRateLimit(config, "ipRateLimit")
      clientRateLimit <- readRateLimit(config, "knownClientFailedSecretRateLimit")
      _               <- Either.cond(authenticationRetention == 90.days, (), OAuthConfigError.InvalidAuditRetention)
      _               <- validateCleanupBatch(auditCleanupBatchSize, MaxAuditCleanupBatchSize)
      _               <- validateCleanupBatch(rateLimitCleanupBatchSize, MaxRateLimitCleanupBatchSize)
      trustedCidrs    <- readTrustedProxyCidrs(config)
      signingKeyRing  <- readSigningKeyRing(config, enabled, humanKey)
    } yield OAuthConfig(
      enabled,
      issuer,
      audience,
      dummyHash,
      signingKeyRing,
      ipRateLimit,
      clientRateLimit,
      authenticationRetention,
      auditCleanupBatchSize,
      rateLimitCleanupBatchSize,
      trustedCidrs
    )
  }

  private def validateIssuer(value: String): Either[OAuthConfigError, Unit] = {
    val valid = Try(new URI(value)).toOption.exists(uri =>
      uri.getScheme == "https" &&
        Option(uri.getHost).exists(_.nonEmpty) &&
        uri.getUserInfo == null &&
        uri.getQuery == null &&
        uri.getFragment == null
    )
    Either.cond(valid, (), OAuthConfigError.InvalidIssuer)
  }

  private def validateAudience(value: String): Either[OAuthConfigError, Unit] =
    Either.cond(AudiencePattern.pattern.matcher(value).matches(), (), OAuthConfigError.InvalidAudience)

  private def validateDummyHash(value: String): Either[OAuthConfigError, OAuthClientSecretHash] = {
    val structurallyValid = BcryptHashPattern.pattern.matcher(value).matches()
    val libraryAccepts    = structurallyValid && Try(BCrypt.checkpw("epoca-oauth-dummy-validation", value)).isSuccess
    Either.cond(libraryAccepts, OAuthClientSecretHash(value), OAuthConfigError.InvalidDummyBcryptHash)
  }

  private def readRateLimit(config: Config, path: String): Either[OAuthConfigError, OAuthRateLimit] = {
    val rateConfig   = config.getConfig(path)
    val capacity     = rateConfig.getInt("capacity")
    val refillPeriod = rateConfig.getDuration("refillPeriod").toScala
    Either.cond(
      capacity > 0 && capacity <= MaxRateCapacity && refillPeriod > Duration.Zero && refillPeriod <= MaxRatePeriod,
      OAuthRateLimit(capacity, refillPeriod),
      OAuthConfigError.InvalidRateLimit
    )
  }

  private def validateCleanupBatch(value: Int, maximum: Int): Either[OAuthConfigError, Unit] =
    Either.cond(value > 0 && value <= maximum, (), OAuthConfigError.InvalidCleanupBatchSize)

  private def readSigningKeyRing(
    config: Config,
    enabled: Boolean,
    humanKey: String
  ): Either[OAuthConfigError, Option[OAuthSigningKeyRing]] = {
    val signingConfig = config.getConfig("signing")
    val fileKeys =
      if (signingConfig.hasPath("keys")) signingConfig.getConfigList("keys").asScala.toVector
      else Vector.empty
    val activeKid = if (signingConfig.hasPath("activeKid")) Some(signingConfig.getString("activeKid")) else None
    val keyRingValue =
      if (signingConfig.hasPath("keyRingB64")) Some(signingConfig.getString("keyRingB64")).filter(_.trim.nonEmpty)
      else None

    if (fileKeys.nonEmpty && keyRingValue.nonEmpty) Left(OAuthConfigError.InvalidConfiguration)
    else
      keyRingValue match {
        case Some(secret) =>
          OAuthSigningKeyRingSecret
            .decode(secret)
            .flatMap(document => validateSigningKeyRing(document.activeKid, document.keys.map(toRawSigningKey), humanKey).map(Some(_)))
        case None if fileKeys.isEmpty && !enabled => Right(None)
        case None if fileKeys.isEmpty             => Left(OAuthConfigError.MissingActiveKey)
        case None =>
          for {
            selectedKid <- activeKid.toRight(OAuthConfigError.MissingActiveKey)
            rawKeys     <- sequence(fileKeys.map(readKeyConfig))
            keyRing     <- validateSigningKeyRing(selectedKid, rawKeys, humanKey)
          } yield Some(keyRing)
      }
  }

  private def readKeyConfig(config: Config): Either[OAuthConfigError, RawSigningKey] = {
    val kid      = config.getString("kid")
    val material = config.getString("material")
    val retired  = config.hasPath("retired") && config.getBoolean("retired")
    Right(RawSigningKey(kid, material, retired))
  }

  private def toRawSigningKey(key: OAuthSigningKeyRingSecret.RawKey): RawSigningKey =
    RawSigningKey(key.kid, key.material, key.retired)

  private def validateSigningKeyRing(
    activeKid: String,
    rawKeys: Vector[RawSigningKey],
    humanKey: String
  ): Either[OAuthConfigError, OAuthSigningKeyRing] =
    for {
      _          <- Either.cond(rawKeys.size <= MaxKeyCount, (), OAuthConfigError.InvalidSigningKey)
      parsedKeys <- sequence(rawKeys.map(readKey))
      _          <- Either.cond(parsedKeys.map(_.value.kid).distinct.size == parsedKeys.size, (), OAuthConfigError.DuplicateKeyId)
      _          <- Either.cond(parsedKeys.map(_.decodedMaterial).distinct.size == parsedKeys.size, (), OAuthConfigError.DuplicateKeyMaterial)
      _          <- Either.cond(parsedKeys.forall(key => !humanKeyReuse(key, humanKey)), (), OAuthConfigError.HumanKeyReuse)
      _          <- validateKeyId(activeKid)
      activeKey  <- parsedKeys.find(_.value.kid == activeKid).toRight(OAuthConfigError.MissingActiveKey)
      _          <- Either.cond(!activeKey.value.retired, (), OAuthConfigError.RetiredActiveKey)
    } yield OAuthSigningKeyRing(activeKid, parsedKeys.map(_.value))

  private def readKey(key: RawSigningKey): Either[OAuthConfigError, ParsedKey] = {
    val kid      = key.kid
    val material = key.material
    val retired  = key.retired
    for {
      _       <- validateKeyId(kid)
      decoded <- decodeKeyMaterial(material)
    } yield ParsedKey(OAuthSigningKey(kid, material, retired), decoded)
  }

  private def validateKeyId(value: String): Either[OAuthConfigError, Unit] =
    Either.cond(IdentifierPattern.pattern.matcher(value).matches(), (), OAuthConfigError.InvalidKeyId)

  private def decodeKeyMaterial(value: String): Either[OAuthConfigError, Vector[Byte]] =
    if (containsKnownPlaceholder(value) || !Base64UrlPattern.pattern.matcher(value).matches())
      Left(OAuthConfigError.InvalidSigningKey)
    else
      Try(Base64.getUrlDecoder.decode(value)).toEither.left
        .map(_ => OAuthConfigError.InvalidSigningKey)
        .flatMap { bytes =>
          if (printableAscii(bytes).exists(containsKnownPlaceholder)) Left(OAuthConfigError.InvalidSigningKey)
          else Either.cond(bytes.length >= 32, bytes.toVector, OAuthConfigError.WeakSigningKey)
        }

  private def containsKnownPlaceholder(value: String): Boolean = {
    val normalized = value.trim.toLowerCase(Locale.ROOT)
    KnownPlaceholders.exists(normalized.contains)
  }

  private def printableAscii(bytes: Array[Byte]): Option[String] =
    Option.when(bytes.nonEmpty && bytes.forall(byte => (byte & 0xff) >= 0x20 && (byte & 0xff) <= 0x7e))(new String(bytes, StandardCharsets.US_ASCII))

  private def humanKeyReuse(key: ParsedKey, humanKey: String): Boolean =
    key.value.material == humanKey || key.decodedMaterial == humanKey.getBytes(StandardCharsets.UTF_8).toVector

  private def readTrustedProxyCidrs(config: Config): Either[OAuthConfigError, Vector[OAuthTrustedProxyCidr]] = {
    val values = config.getStringList("trustedProxyCidrs").asScala.toVector
    if (values.size > MaxTrustedProxyCidrs) Left(OAuthConfigError.InvalidTrustedProxyCidr)
    else sequence(values.map(parseCidr))
  }

  private def parseCidr(value: String): Either[OAuthConfigError, OAuthTrustedProxyCidr] =
    value.split("/", -1).toVector match {
      case Vector(addressValue, prefixValue) =>
        for {
          address <- parseAddress(addressValue)
          prefix  <- Try(prefixValue.toInt).toEither.left.map(_ => OAuthConfigError.InvalidTrustedProxyCidr)
          _       <- Either.cond(prefix >= 0 && prefix <= address.length * 8, (), OAuthConfigError.InvalidTrustedProxyCidr)
        } yield OAuthTrustedProxyCidr(masked(address, prefix), prefix)
      case _ => Left(OAuthConfigError.InvalidTrustedProxyCidr)
    }

  private def parseAddress(value: String): Either[OAuthConfigError, Vector[Byte]] =
    if (value.contains(":")) {
      val literal = value.nonEmpty && value.forall(character => character == ':' || Character.digit(character, 16) >= 0)
      Try(InetAddress.getByName(value).getAddress.toVector).toEither.left
        .map(_ => OAuthConfigError.InvalidTrustedProxyCidr)
        .filterOrElse(bytes => literal && bytes.length == 16, OAuthConfigError.InvalidTrustedProxyCidr)
    } else {
      val octets = value.split("\\.", -1).toVector
      val parsed =
        octets.map(octet => Try(octet.toInt).toOption.filter(number => octet.nonEmpty && octet.forall(_.isDigit) && number >= 0 && number <= 255))
      Either.cond(octets.size == 4 && parsed.forall(_.nonEmpty), parsed.flatten.map(_.toByte), OAuthConfigError.InvalidTrustedProxyCidr)
    }

  private def masked(address: Vector[Byte], prefixLength: Int): Vector[Byte] =
    address.zipWithIndex.map { case (byte, index) =>
      val remainingBits = prefixLength - index * 8
      if (remainingBits >= 8) byte
      else if (remainingBits <= 0) 0.toByte
      else (byte & (0xff << (8 - remainingBits))).toByte
    }

  private def sequence[A](values: Vector[Either[OAuthConfigError, A]]): Either[OAuthConfigError, Vector[A]] =
    values.foldLeft[Either[OAuthConfigError, Vector[A]]](Right(Vector.empty)) { (result, value) =>
      for {
        accumulated <- result
        current     <- value
      } yield accumulated :+ current
    }
}
