package pl.iterators.epoca.invitation.services

import java.nio.charset.StandardCharsets
import java.security.{MessageDigest, SecureRandom}
import java.util.Base64

final class InvitationSecretService {
  private val secureRandom = new SecureRandom()

  def generate(): String = {
    val bytes = new Array[Byte](32)
    secureRandom.nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)
  }

  /** Stable for one browser context; distinct from the cookie digest stored in the database. */
  def csrfToken(cookie: String): String =
    Base64.getUrlEncoder.withoutPadding().encodeToString(hash("invitation-csrf:v1:" + cookie))

  def hash(secret: String): Array[Byte] =
    MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8))

  def matches(secret: String, expected: Array[Byte]): Boolean =
    MessageDigest.isEqual(hash(secret), expected)
}
