package pl.iterators.epoca.user.domain

import enumeratum.{Enum, EnumEntry}
import pl.iterators.epoca.user.domain.UserDomain.IdentityType
import pl.iterators.epoca.utils.regex.HexColorValidator
import pl.iterators.kebs.tag.meta.tagged
import pl.iterators.kebs.tagged._

import java.time.Instant
import java.util.UUID
import scala.collection.immutable

@tagged trait Domain {
  trait UserIdTag
  type UserId = UUID @@ UserIdTag

  trait UserFirstNameTag
  type UserFirstName = String @@ UserFirstNameTag

  object UserFirstName {
    sealed trait Error
    case object Empty extends Error
    def validate(firstName: String): Either[Empty.type, String] = if (firstName.trim.isEmpty) Left(Empty) else Right(firstName.trim)
  }

  trait UserLastNameTag
  type UserLastName = String @@ UserLastNameTag

  object UserLastName {
    sealed trait Error
    case object Empty extends Error
    def validate(lastName: String): Either[Empty.type, String] = if (lastName.trim.isEmpty) Left(Empty) else Right(lastName.trim)
  }

  trait UserFullNameTag
  type UserFullName = String @@ UserFullNameTag

  trait UserAvatarTag
  type UserAvatar = String @@ UserAvatarTag

  trait UserColorTag
  type UserColor = String @@ UserColorTag

  object UserColor {
    sealed trait Error
    case object Invalid extends Error
    def validate(color: String): Either[Invalid.type, String] = if (!HexColorValidator.validate(color)) Left(Invalid) else Right(color)
  }

  trait UserIsSuperAdminTag
  type UserIsSuperAdmin = Boolean @@ UserIsSuperAdminTag

  trait UserCreatedAtTag
  type UserCreatedAt = Instant @@ UserCreatedAtTag

  trait UserModifiedAtTag
  type UserModifiedAt = Instant @@ UserModifiedAtTag

  trait UserArchivedAtTag
  type UserArchivedAt = Instant @@ UserArchivedAtTag

  trait UserIdentityIdTag
  type UserIdentityId = UUID @@ UserIdentityIdTag

  trait UserIdentityValueTag
  type UserIdentityValue = String @@ UserIdentityValueTag

  trait UserIdentityValueVerifyTokenTag
  type UserIdentityValueVerifyToken = String @@ UserIdentityValueVerifyTokenTag

  object UserIdentityValue {
    def isValid(identityType: IdentityType, value: UserIdentityValue): Boolean =
      identityType match {
        case IdentityType.MailAddress              => value.matches(emailRegex)
        case IdentityType.Phone                    => value.matches(phoneRegex)
        case IdentityType.CryptoVerification       => true
        case IdentityType.Saml                     => true
        case IdentityType.AdminImpersonate         => true
        case IdentityType.TimeBasedOneTimePassword => value.matches(timeBasedOneTimePasswordRegex)
      }

    // https://www.twilio.com/docs/glossary/what-e164
    private val phoneRegex = """^\+[1-9]\d{1,14}$"""

    // Regex for RFC-5322 Validation
    private val emailRegex = """^[a-zA-Z0-9_!#$%&’*+/=?`{|}~^.-]+@[a-zA-Z0-9.-]+$"""

    // Valid base32 string
    private val timeBasedOneTimePasswordRegex = """^[A-Z2-7]+$"""
  }

  trait UserIdentityMainTag
  type UserIdentityMain = Boolean @@ UserIdentityMainTag

  trait UserIdentityConfirmedTag
  type UserIdentityConfirmed = Boolean @@ UserIdentityConfirmedTag

  trait UserIdentityConfirmationCodeTag
  type UserIdentityConfirmationCode = String @@ UserIdentityConfirmationCodeTag

  trait UserIdentityCreatedAtTag
  type UserIdentityCreatedAt = Instant @@ UserIdentityCreatedAtTag
}

object UserDomain extends Domain {
  sealed trait UserAccountType extends EnumEntry
  object UserAccountType extends Enum[UserAccountType] {
    case object Human          extends UserAccountType
    case object ServiceAccount extends UserAccountType

    override val values: IndexedSeq[UserAccountType] = findValues
  }

  sealed trait IdentityType extends EnumEntry {
    def revealValue: Boolean
  }
  object IdentityType extends Enum[IdentityType] {
    case object MailAddress extends IdentityType {
      override val revealValue: Boolean = true
    }
    case object Phone extends IdentityType {
      override val revealValue: Boolean = true
    }
    case object Saml extends IdentityType {
      override val revealValue: Boolean = true
    }
    case object CryptoVerification extends IdentityType {
      override val revealValue: Boolean = false
    }
    case object AdminImpersonate extends IdentityType {
      override val revealValue: Boolean = false
    }
    case object TimeBasedOneTimePassword extends IdentityType {
      override val revealValue: Boolean = false
    }

    override def values: immutable.IndexedSeq[IdentityType] = findValues
    val confirmationRequiredTypes: Set[IdentityType]        = Set(MailAddress, Phone)
  }

}
