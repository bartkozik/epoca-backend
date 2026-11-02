package pl.iterators.epoca.oauth.services

import org.mindrot.jbcrypt.BCrypt
import pl.iterators.epoca.oauth.domain.OAuthDomain.{OAuthClientCredentials, OAuthClientPublicId, OAuthClientSecret, OAuthClientSecretHash}

import java.security.SecureRandom
import java.util.Base64
import scala.util.control.NonFatal

trait OAuthSecretVerifier {
  def verify(secret: OAuthClientSecret, hash: OAuthClientSecretHash): Boolean
  def verifyOrDummy(secret: OAuthClientSecret, eligibleHash: Option[OAuthClientSecretHash]): Boolean
}

final class OAuthSecretService(dummyHash: OAuthClientSecretHash, secureRandom: SecureRandom = new SecureRandom()) extends OAuthSecretVerifier {
  import OAuthSecretService._

  def generateCredentials(): OAuthClientCredentials =
    OAuthClientCredentials(
      OAuthClientPublicId(ClientIdPrefix + randomUrlSafe(ClientIdRandomBytes)),
      OAuthClientSecret(ClientSecretPrefix + randomUrlSafe(ClientSecretRandomBytes))
    )

  def hash(secret: OAuthClientSecret): OAuthClientSecretHash =
    OAuthClientSecretHash(BCrypt.hashpw(secret, BCrypt.gensalt(BcryptCost, secureRandom)))

  def verify(secret: OAuthClientSecret, hash: OAuthClientSecretHash): Boolean =
    try BCrypt.checkpw(secret, hash)
    catch {
      case NonFatal(_) => false
    }

  def verifyOrDummy(secret: OAuthClientSecret, eligibleHash: Option[OAuthClientSecretHash]): Boolean =
    eligibleHash match {
      case Some(hash) => verify(secret, hash)
      case None =>
        val _ = verify(secret, dummyHash)
        false
    }

  private def randomUrlSafe(byteCount: Int): String = {
    val bytes = new Array[Byte](byteCount)
    secureRandom.nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)
  }
}

object OAuthSecretService {
  val ClientIdPrefix: String     = "epoca_oauth_client_v1_"
  val ClientSecretPrefix: String = "epoca_oauth_secret_v1_"

  private val ClientIdRandomBytes     = 16
  private val ClientSecretRandomBytes = 32
  private val BcryptCost              = 12
}
