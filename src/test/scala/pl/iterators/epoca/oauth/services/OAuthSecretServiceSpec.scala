package pl.iterators.epoca.oauth.services

import org.mindrot.jbcrypt.BCrypt
import org.specs2.mutable.SpecificationLike
import pl.iterators.epoca.oauth.domain.OAuthDomain.{OAuthClientSecret, OAuthClientSecretHash}

import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import scala.collection.mutable.ArrayBuffer

class OAuthSecretServiceSpec extends SpecificationLike {
  private val dummyHash = BCrypt.hashpw("epoca-oauth-dummy-v1", BCrypt.gensalt(12))
  private val service   = new OAuthSecretService(OAuthClientSecretHash(dummyHash), new SecureRandom())

  "OAuth client credentials" should {
    "use recognizable versioned Epoca prefixes and statistically safe fixed encodings" in {
      val generated = Vector.fill(8)(service.generateCredentials())

      generated.map(_.clientId.toString) must contain(allOf(Seq.fill(8)(beMatching("epoca_oauth_client_v1_[A-Za-z0-9_-]{22}")): _*))
      generated.map(_.clientSecret.toString) must contain(allOf(Seq.fill(8)(beMatching("epoca_oauth_secret_v1_[A-Za-z0-9_-]{43}")): _*))
      generated.map(_.clientSecret.toString.getBytes(StandardCharsets.UTF_8).length).forall(_ < 72) should beTrue
    }

    "request exactly 128 client-id bits and 256 client-secret bits from SecureRandom" in {
      val random      = new RecordingSecureRandom
      val credentials = new OAuthSecretService(OAuthClientSecretHash(dummyHash), random).generateCredentials()

      random.requestedByteCounts shouldEqual Vector(16, 32)
      decodedSuffix(credentials.clientId.toString, OAuthSecretService.ClientIdPrefix).length shouldEqual 16
      decodedSuffix(credentials.clientSecret.toString, OAuthSecretService.ClientSecretPrefix).length shouldEqual 32
      credentials.clientId.toString must not(contain("="))
      credentials.clientSecret.toString must not(contain("="))
    }

    "hash with bcrypt cost exactly 12 and verify correct, wrong, and malformed inputs safely" in {
      val secret = service.generateCredentials().clientSecret
      val hash   = service.hash(secret)

      hash.toString.split("\\$")(2) shouldEqual "12"
      service.verify(secret, hash) should beTrue
      service.verify(OAuthClientSecret("epoca_oauth_secret_v1_wrong"), hash) should beFalse
      service.verify(secret, OAuthClientSecretHash("not-a-bcrypt-hash")) should beFalse
    }

    "use the configured dummy bcrypt hash when a client is unknown or ineligible" in {
      val candidate = OAuthClientSecret("epoca_oauth_secret_v1_wrong")

      service.verifyOrDummy(candidate, None) should beFalse
      service.verifyOrDummy(candidate, Some(OAuthClientSecretHash(dummyHash))) should beFalse
      service.verifyOrDummy(OAuthClientSecret("epoca-oauth-dummy-v1"), None) should beFalse
    }

    "redact the one-time plaintext secret from value rendering" in {
      val credentials = service.generateCredentials()

      credentials.toString must not(contain(credentials.clientSecret.toString))
      credentials.toString must contain(credentials.clientId.toString)
    }
  }

  private def decodedSuffix(value: String, prefix: String): Array[Byte] =
    Base64.getUrlDecoder.decode(value.stripPrefix(prefix))

  private final class RecordingSecureRandom extends SecureRandom {
    private val byteCounts = ArrayBuffer.empty[Int]
    private var nextValue  = 0

    def requestedByteCounts: Vector[Int] = byteCounts.toVector

    override def nextBytes(bytes: Array[Byte]): Unit = {
      byteCounts += bytes.length
      bytes.indices.foreach { index =>
        bytes(index) = ((nextValue + index) & 0xff).toByte
      }
      nextValue += bytes.length
    }
  }
}
