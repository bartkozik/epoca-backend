package pl.iterators.epoca.auth.services

import org.mindrot.jbcrypt.BCrypt
import pl.iterators.epoca.auth.domain.AuthDomain.{PasswordHash, PasswordPlain}

class PasswordService {
  def encrypt(password: PasswordPlain): PasswordHash =
    PasswordHash(hashPassword(password))

  def check(plain: PasswordPlain, hash: PasswordHash): Boolean =
    checkPassword(plain, hash)

  private def hashPassword(password: String): String = BCrypt.hashpw(password, BCrypt.gensalt(12))

  private def checkPassword(plain: String, hashed: String): Boolean = BCrypt.checkpw(plain, hashed)
}
