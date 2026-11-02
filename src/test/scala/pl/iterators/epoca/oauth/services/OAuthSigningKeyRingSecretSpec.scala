package pl.iterators.epoca.oauth.services

import org.specs2.mutable.SpecificationLike
import pl.iterators.epoca.oauth.services.OAuthConfig.OAuthConfigError

import java.nio.charset.StandardCharsets
import java.util.Base64

class OAuthSigningKeyRingSecretSpec extends SpecificationLike {
  private val keyA = Base64.getUrlEncoder.withoutPadding().encodeToString(Array.tabulate[Byte](32)(i => (i + 1).toByte))

  "OAuth signing key ring secret" should {
    "decode a valid version 1 document" in {
      OAuthSigningKeyRingSecret.decode(secret(validJson)) shouldEqual Right(
        OAuthSigningKeyRingSecret
          .Document(version = 1, activeKid = "staging-a", keys = Vector(OAuthSigningKeyRingSecret.RawKey("staging-a", keyA, retired = false)))
      )
    }

    "redact key material from rendered raw documents" in {
      val document = OAuthSigningKeyRingSecret.decode(secret(validJson)).toOption.get

      document.toString must not(contain(keyA))
      document.keys.head.toString must not(contain(keyA))
      document.toString must contain("<redacted>")
    }

    "accept strict-schema documents up to the byte limit and reject larger ones" in {
      val limitJson = sizedValidJson(65536)

      limitJson.getBytes(StandardCharsets.UTF_8).length shouldEqual 65536
      OAuthSigningKeyRingSecret.decode(secret(limitJson)) must beRight

      val oversizedJson = sizedValidJson(65537)

      oversizedJson.getBytes(StandardCharsets.UTF_8).length shouldEqual 65537
      OAuthSigningKeyRingSecret.decode(secret(oversizedJson)) shouldEqual Left(OAuthConfigError.InvalidConfiguration)
    }

    "accept exactly 32 raw keys and reject 33 raw keys" in {
      OAuthSigningKeyRingSecret.decode(secret(jsonWithKeyCount(32))) must beRight
      OAuthSigningKeyRingSecret.decode(secret(jsonWithKeyCount(33))) shouldEqual Left(OAuthConfigError.InvalidConfiguration)
    }

    "reject non-canonical outer Base64URL with mutated unused pad bits" in {
      val json      = sizedValidJson(65536)
      val canonical = secret(json)
      val mutated   = mutateUnusedPadBits(canonical)

      json.getBytes(StandardCharsets.UTF_8).length shouldEqual 65536
      canonical.length % 4 shouldEqual 2
      Base64.getUrlDecoder.decode(mutated).toVector shouldEqual Base64.getUrlDecoder.decode(canonical).toVector
      mutated.equals(canonical) should beFalse
      OAuthSigningKeyRingSecret.decode(canonical) must beRight
      OAuthSigningKeyRingSecret.decode(mutated) shouldEqual Left(OAuthConfigError.InvalidConfiguration)
    }

    "reject padded, standard alphabet, invalid UTF-8, and oversized envelopes" in {
      OAuthSigningKeyRingSecret.decode(secret(validJson) + "=") shouldEqual Left(OAuthConfigError.InvalidConfiguration)
      OAuthSigningKeyRingSecret.decode(secret(validJson).updated(0, '+')) shouldEqual Left(OAuthConfigError.InvalidConfiguration)
      OAuthSigningKeyRingSecret.decode(secret(validJson).updated(0, '/')) shouldEqual Left(OAuthConfigError.InvalidConfiguration)
      OAuthSigningKeyRingSecret.decode(Base64.getUrlEncoder.withoutPadding().encodeToString(Array[Byte](0xc3.toByte, 0x28.toByte))) shouldEqual Left(
        OAuthConfigError.InvalidConfiguration
      )
    }

    "reject duplicate top-level and nested fields before Circe decoding" in {
      OAuthSigningKeyRingSecret.decode(
        secret(s"""{"version":1,"version":1,"activeKid":"staging-a","keys":[{"kid":"staging-a","material":"$keyA","retired":false}]}""")
      ) shouldEqual Left(OAuthConfigError.InvalidConfiguration)
      OAuthSigningKeyRingSecret.decode(
        secret(s"""{"version":1,"activeKid":"staging-a","keys":[{"kid":"staging-a","kid":"staging-b","material":"$keyA","retired":false}]}""")
      ) shouldEqual Left(OAuthConfigError.InvalidConfiguration)
    }

    "reject unknown, missing, wrong type, wrong version, and invalid count documents without echoing values" in {
      val sentinel = "oauth-parser-secret-sentinel"
      val results = Seq(
        s"""{"version":1,"activeKid":"staging-a","extra":"$sentinel","keys":[{"kid":"staging-a","material":"$keyA","retired":false}]}""",
        s"""{"version":1,"keys":[{"kid":"staging-a","material":"$keyA","retired":false}]}""",
        s"""{"version":"1","activeKid":"staging-a","keys":[{"kid":"staging-a","material":"$keyA","retired":false}]}""",
        s"""{"version":2,"activeKid":"staging-a","keys":[{"kid":"staging-a","material":"$keyA","retired":false}]}""",
        s"""{"version":1,"activeKid":"staging-a","keys":[]}""",
        s"""{"version":1,"activeKid":"staging-a","keys":[${Vector
            .fill(33)(s"""{"kid":"staging-a","material":"$keyA","retired":false}""")
            .mkString(",")}]}""",
        s"""{"version":1,"activeKid":"staging-a","keys":[{"kid":"staging-a","material":"$keyA"}]}""",
        s"""{"version":1,"activeKid":"staging-a","keys":[{"kid":"staging-a","material":"$keyA","retired":false,"extra":"$sentinel"}]}""",
        s"""{"version":1,"activeKid":"staging-a","keys":[{"kid":"staging-a","material":1,"retired":false}]}"""
      ).map { json =>
        val result = OAuthSigningKeyRingSecret.decode(secret(json))
        (result shouldEqual Left(OAuthConfigError.InvalidConfiguration)) and (result.toString must not(contain(sentinel)))
      }

      results.reduce(_ and _)
    }
  }

  private def validJson: String =
    s"""{"version":1,"activeKid":"staging-a","keys":[{"kid":"staging-a","material":"$keyA","retired":false}]}"""

  private def sizedValidJson(targetBytes: Int): String = {
    val prefix = """{"version":1,"activeKid":"staging-a","keys":[{"kid":"staging-a","material":""""
    val suffix = """","retired":false}]}"""
    val fillerSize = targetBytes - prefix.getBytes(StandardCharsets.UTF_8).length - keyA.getBytes(StandardCharsets.UTF_8).length - suffix
      .getBytes(StandardCharsets.UTF_8)
      .length
    prefix + keyA + ("A" * fillerSize) + suffix
  }

  private def jsonWithKeyCount(count: Int): String =
    s"""{"version":1,"activeKid":"staging-a","keys":[${Vector
        .fill(count)(s"""{"kid":"staging-a","material":"$keyA","retired":false}""")
        .mkString(",")}]}"""

  private def mutateUnusedPadBits(value: String): String = {
    val index        = Base64UrlAlphabet.indexOf(value.last.toString)
    val mutatedIndex = if (index % 16 == 15) index - 1 else index + 1

    value.dropRight(1) + Base64UrlAlphabet.charAt(mutatedIndex)
  }

  private def secret(json: String): String =
    Base64.getUrlEncoder.withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8))

  private val Base64UrlAlphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
}
