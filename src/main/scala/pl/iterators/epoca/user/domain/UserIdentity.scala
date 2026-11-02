package pl.iterators.epoca.user.domain

import io.scalaland.chimney.dsl._
import pl.iterators.epoca.auth.domain.AuthDomain.DeviceId
import pl.iterators.epoca.mail.domain.MailDomain.MailAddress
import pl.iterators.epoca.sms.domain.SmsDomain.PhoneNumber
import pl.iterators.epoca.user.domain.UserDomain._

final case class UserIdentity(
  id: UserIdentityId,
  userId: UserId,
  `type`: IdentityType,
  value: UserIdentityValue,
  main: UserIdentityMain,
  confirmed: UserIdentityConfirmed,
  confirmationCode: Option[UserIdentityConfirmationCode],
  deviceId: Option[DeviceId],
  createdAt: UserIdentityCreatedAt) {

  lazy val mailAddress: Option[MailAddress] =
    Option.when(`type` == IdentityType.MailAddress)(MailAddress(value))

  lazy val phoneNumber: Option[PhoneNumber] =
    Option.when(`type` == IdentityType.Phone)(PhoneNumber(value))
}

final case class UserIdentityDto(
  id: UserIdentityId,
  userId: UserId,
  `type`: IdentityType,
  value: UserIdentityValue,
  main: UserIdentityMain,
  confirmed: UserIdentityConfirmed,
  deviceId: Option[DeviceId],
  createdAt: UserIdentityCreatedAt)

object UserIdentityDto {
  def from(identity: UserIdentity): UserIdentityDto =
    identity.into[UserIdentityDto].withFieldConst(_.value, if (identity.`type`.revealValue) identity.value else UserIdentityValue("")).transform
}

final case class UserIdentityShortDto(
  id: UserIdentityId,
  `type`: IdentityType,
  value: Option[UserIdentityValue],
  main: UserIdentityMain,
  deviceId: Option[DeviceId])

object UserIdentityShortDto {
  def apply(userIdentity: UserIdentity): UserIdentityShortDto =
    UserIdentityShortDto(
      userIdentity.id,
      userIdentity.`type`,
      Option.when(userIdentity.`type`.revealValue)(userIdentity.value),
      userIdentity.main,
      userIdentity.deviceId
    )
}
