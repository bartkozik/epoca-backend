package pl.iterators.epoca.mail.services

import com.typesafe.config.{Config => TsConfig, ConfigFactory}

import java.nio.charset.StandardCharsets.US_ASCII
import java.security.spec.X509EncodedKeySpec
import java.security.{KeyFactory, Signature}
import java.time.Clock
import java.util.Base64
import scala.concurrent.duration._
import scala.jdk.DurationConverters._
import scala.util.Try

/** SendGrid signs SHA-256(timestamp ASCII bytes ++ unmodified request bytes) using ECDSA. */
class SendgridWebhookVerifier(config: SendgridWebhookVerifier.Config, clock: Clock = Clock.systemUTC()) {
  private val publicKey = Try {
    val encoded = config.publicKey.trim
      .replace("-----BEGIN PUBLIC KEY-----", "")
      .replace("-----END PUBLIC KEY-----", "")
      .replaceAll("\\s", "")
    KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Base64.getDecoder.decode(encoded)))
  }.toOption

  val available: Boolean = publicKey.isDefined
  val maxBodyBytes: Long = config.maxBodyBytes

  def verify(
    signature: String,
    timestamp: String,
    body: Array[Byte]
  ): Boolean =
    publicKey.exists { key =>
      signature.nonEmpty && signature.length <= 1024 && body.length.toLong <= maxBodyBytes && freshTimestamp(timestamp) &&
      Try {
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(key)
        verifier.update(timestamp.getBytes(US_ASCII))
        verifier.update(body)
        verifier.verify(Base64.getDecoder.decode(signature))
      }.getOrElse(false)
    }

  private def freshTimestamp(timestamp: String): Boolean =
    timestamp.matches("[0-9]{1,12}") && Try {
      val supplied = timestamp.toLong
      val now      = clock.instant().getEpochSecond
      supplied >= now - config.maxAge.toSeconds && supplied <= now + config.maxFutureSkew.toSeconds
    }.getOrElse(false)
}

object SendgridWebhookVerifier {
  case class Config(
    publicKey: String = "",
    maxAge: FiniteDuration = 5.minutes,
    maxFutureSkew: FiniteDuration = 30.seconds,
    maxBodyBytes: Long = 1048576L) {
    require(maxAge > Duration.Zero && maxAge <= 1.day, "SendGrid webhook maxAge must be positive and at most one day")
    require(maxFutureSkew >= Duration.Zero && maxFutureSkew <= 5.minutes, "SendGrid webhook maxFutureSkew must be between zero and five minutes")
    require(maxBodyBytes > 0L && maxBodyBytes <= 10485760L, "SendGrid webhook maxBodyBytes must be positive and at most 10 MiB")
  }

  object Config {
    def apply(tsConfig: TsConfig): Config = {
      val path = "mail.sendgrid.webhook"
      val c    = if (tsConfig.hasPath(path)) tsConfig.getConfig(path) else ConfigFactory.empty()
      Config(
        publicKey = if (c.hasPath("publicKey")) c.getString("publicKey") else "",
        maxAge = if (c.hasPath("maxAge")) c.getDuration("maxAge").toScala else 5.minutes,
        maxFutureSkew = if (c.hasPath("maxFutureSkew")) c.getDuration("maxFutureSkew").toScala else 30.seconds,
        maxBodyBytes = if (c.hasPath("maxBodyBytes")) c.getLong("maxBodyBytes") else 1048576L
      )
    }
  }
}
