package pl.iterators.epoca.auth.domain

import enumeratum.{Enum, EnumEntry}
import pl.iterators.kebs.tag.meta.tagged
import pl.iterators.kebs.tagged._

import java.time.Instant
import java.util.UUID

@tagged trait Domain {

  trait PasswordPlainTag
  type PasswordPlain = String @@ PasswordPlainTag

  trait PasswordHashTag
  type PasswordHash = String @@ PasswordHashTag

  trait Base64PublicKeyTag
  type Base64PublicKey = String @@ Base64PublicKeyTag

  trait CryptoSignatureTag
  type CryptoSignature = String @@ CryptoSignatureTag

  trait CryptoVerificationDataTag
  type CryptoVerificationData = String @@ CryptoVerificationDataTag

  trait LoginTokenIdTag
  type LoginTokenId = UUID @@ LoginTokenIdTag

  trait LoginTokenPlainTag
  type LoginTokenPlain = String @@ LoginTokenPlainTag

  trait LoginTokenHashTag
  type LoginTokenHash = String @@ LoginTokenHashTag

  trait LoginTokenValidToTag
  type LoginTokenValidTo = Instant @@ LoginTokenValidToTag

  trait DeviceIdTag
  type DeviceId = String @@ DeviceIdTag

  trait RefreshTokenValueTag
  type RefreshTokenValue = String @@ RefreshTokenValueTag

  trait RefreshTokenUserAgentTag
  type RefreshTokenUserAgent = String @@ RefreshTokenUserAgentTag

  trait RefreshTokenLastUsedAtTag
  type RefreshTokenLastUsedAt = Instant @@ RefreshTokenLastUsedAtTag

  trait RefreshTokenCreatedAtTag
  type RefreshTokenCreatedAt = Instant @@ RefreshTokenCreatedAtTag

  trait RefreshTokenExpiredAtTag
  type RefreshTokenExpiredAt = Instant @@ RefreshTokenExpiredAtTag

  trait TimeBasedOneTimePasswordSecretTag
  type TimeBasedOneTimePasswordSecret = String @@ TimeBasedOneTimePasswordSecretTag

  trait TimeBasedOneTimePasswordValueTag
  type TimeBasedOneTimePasswordValue = String @@ TimeBasedOneTimePasswordValueTag

  trait JwtTag
  type Jwt = String @@ JwtTag

  trait AuthLevelTag
  type AuthLevel = Int @@ AuthLevelTag
}

object AuthDomain extends Domain {
  sealed trait KeyAlgorithm extends EnumEntry
  object KeyAlgorithm extends Enum[KeyAlgorithm] {
    case object RSA extends KeyAlgorithm
    override def values: IndexedSeq[KeyAlgorithm] = findValues
  }

  sealed trait SignatureAlgorithm extends EnumEntry {
    def keyAlgorithm: KeyAlgorithm
  }
  object SignatureAlgorithm extends Enum[SignatureAlgorithm] {
    case object SHA256withRSA extends SignatureAlgorithm {
      override def keyAlgorithm: KeyAlgorithm = KeyAlgorithm.RSA
    }
    def default: SignatureAlgorithm                     = SignatureAlgorithm.SHA256withRSA
    override def values: IndexedSeq[SignatureAlgorithm] = findValues
  }

  sealed trait RefreshTokenValidForStrategy extends EnumEntry
  object RefreshTokenValidForStrategy extends Enum[RefreshTokenValidForStrategy] {
    case object Off             extends RefreshTokenValidForStrategy
    case object FromLatestUsage extends RefreshTokenValidForStrategy
    case object FromFirstUsage  extends RefreshTokenValidForStrategy

    override def values: IndexedSeq[RefreshTokenValidForStrategy] = findValues
  }
}
