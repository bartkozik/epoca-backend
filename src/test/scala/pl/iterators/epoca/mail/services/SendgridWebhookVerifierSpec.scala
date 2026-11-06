package pl.iterators.epoca.mail.services

import com.typesafe.config.ConfigFactory
import org.specs2.mutable.Specification

import java.nio.charset.StandardCharsets.UTF_8
import java.security.spec.ECGenParameterSpec
import java.security.{KeyPairGenerator, Signature}
import java.time.{Clock, Instant, ZoneOffset}
import java.util.Base64
import scala.concurrent.duration._

class SendgridWebhookVerifierSpec extends Specification {
  private val generator = KeyPairGenerator.getInstance("EC")
  generator.initialize(new ECGenParameterSpec("secp256r1"))
  private val key       = generator.generateKeyPair()
  private val publicKey = Base64.getEncoder.encodeToString(key.getPublic.getEncoded)
  private val now       = Instant.parse("2026-10-08T12:00:00Z")
  private val timestamp = now.getEpochSecond.toString
  private val payload   = "[ {\"email\": \"ż@example.test\"} ]\r\n".getBytes(UTF_8)
  private val verifier =
    new SendgridWebhookVerifier(SendgridWebhookVerifier.Config(publicKey, 5.minutes, 30.seconds, 1048576), Clock.fixed(now, ZoneOffset.UTC))

  private def signature(ts: String, body: Array[Byte]): String = {
    val signer = Signature.getInstance("SHA256withECDSA")
    signer.initSign(key.getPrivate)
    signer.update(ts.getBytes(UTF_8))
    signer.update(body)
    Base64.getEncoder.encodeToString(signer.sign())
  }

  "SendGrid signature verifier" should {
    "accept SendGrid's published Java SDK interoperability fixture including trailing CRLF" in {
      val fixtureKey = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE83T4O/n84iotIvIW4mdBgQ/7dAfSmpqIM8kF9mN1flpVKS3GRqe62gw+2fNNRaINXvVpiglSI8eNEc6wEA3F+g=="
      val fixtureSignature = "MEUCIGHQVtGj+Y3LkG9fLcxf3qfI10QysgDWmMOVmxG0u6ZUAiEAyBiXDWzM+uOe5W0JuG+luQAbPIqHh89M15TluLtEZtM="
      val fixtureBody =
        """[{"email":"hello@world.com","event":"dropped","reason":"Bounced Address","sg_event_id":"ZHJvcC0xMDk5NDkxOS1MUnpYbF9OSFN0T0doUTRrb2ZTbV9BLTA","sg_message_id":"LRzXl_NHStOGhQ4kofSm_A.filterdrecv-p3mdw1-756b745b58-kmzbl-18-5F5FC76C-9.0","smtp-id":"<LRzXl_NHStOGhQ4kofSm_A@ismtpd0039p1iad1.sendgrid.net>","timestamp":1600112492}]""" + "\r\n"
      val fixtureVerifier =
        new SendgridWebhookVerifier(SendgridWebhookVerifier.Config(fixtureKey), Clock.fixed(Instant.ofEpochSecond(1600112502L), ZoneOffset.UTC))
      fixtureVerifier.verify(fixtureSignature, "1600112502", fixtureBody.getBytes(UTF_8)) must beTrue
      fixtureVerifier.verify(fixtureSignature, "1600112502", fixtureBody.trim.getBytes(UTF_8)) must beFalse
    }
    "verify the timestamp followed by the exact signed bytes" in {
      verifier.available must beTrue
      verifier.verify(signature(timestamp, payload), timestamp, payload) must beTrue
    }
    "reject modified body or timestamp and signatures made by another key" in {
      verifier.verify(signature(timestamp, payload), timestamp, payload ++ Array[Byte](32)) must beFalse
      verifier.verify(signature(timestamp, payload), (now.getEpochSecond - 1).toString, payload) must beFalse
      val otherKey = Base64.getEncoder.encodeToString(generator.generateKeyPair().getPublic.getEncoded)
      new SendgridWebhookVerifier(SendgridWebhookVerifier.Config(otherKey, 5.minutes, 30.seconds, 1048576), Clock.fixed(now, ZoneOffset.UTC))
        .verify(signature(timestamp, payload), timestamp, payload) must beFalse
    }
    "bound replay age and future clock skew even for valid signatures" in {
      val stale  = now.minusSeconds(301).getEpochSecond.toString
      val future = now.plusSeconds(31).getEpochSecond.toString
      verifier.verify(signature(stale, payload), stale, payload) must beFalse
      verifier.verify(signature(future, payload), future, payload) must beFalse
      val boundary = now.minusSeconds(300).getEpochSecond.toString
      verifier.verify(signature(boundary, payload), boundary, payload) must beTrue
    }
    "reject absent malformed oversized or noncanonical header values without throwing" in {
      Seq("", "not-base64", "AAAA", "a" * 1025).forall(sig => !verifier.verify(sig, timestamp, payload)) must beTrue
      Seq("", "yesterday", "-1", "+1791460800", "9" * 40).forall(ts => !verifier.verify(signature(ts, payload), ts, payload)) must beTrue
      val large = new Array[Byte](1048577)
      verifier.verify(signature(timestamp, large), timestamp, large) must beFalse
    }
    "remain unavailable with missing or malformed keys" in {
      Seq("", " ", "invalid-key").forall { raw =>
        val unavailable = new SendgridWebhookVerifier(SendgridWebhookVerifier.Config(raw, 5.minutes, 30.seconds, 1048576))
        !unavailable.available && !unavailable.verify(signature(timestamp, payload), timestamp, payload)
      } must beTrue
      SendgridWebhookVerifier.Config(ConfigFactory.empty()).publicKey mustEqual ""
    }
    "reject invalid verification bounds" in {
      SendgridWebhookVerifier.Config(publicKey, Duration.Zero, 30.seconds, 1024) must throwA[IllegalArgumentException]
      SendgridWebhookVerifier.Config(publicKey, 5.minutes, -1.second, 1024) must throwA[IllegalArgumentException]
      SendgridWebhookVerifier.Config(publicKey, 5.minutes, 30.seconds, 0) must throwA[IllegalArgumentException]
    }
  }
}
