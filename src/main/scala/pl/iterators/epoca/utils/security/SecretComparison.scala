package pl.iterators.epoca.utils.security

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object SecretComparison {

  /** Compares a caller-supplied value with a configured secret without leaking the position of the first mismatch through timing. */
  def matches(provided: String, expected: String): Boolean =
    MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8))
}
