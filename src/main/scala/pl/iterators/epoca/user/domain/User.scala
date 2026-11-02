package pl.iterators.epoca.user.domain

import io.scalaland.chimney.dsl._
import pl.iterators.epoca.user.domain.UserDomain._

final case class User(
  id: UserId,
  firstName: UserFirstName,
  lastName: UserLastName,
  avatar: Option[UserAvatar],
  color: Option[UserColor],
  isSuperAdmin: UserIsSuperAdmin,
  createdAt: UserCreatedAt,
  modifiedAt: UserModifiedAt,
  archivedAt: Option[UserArchivedAt],
  accountType: UserAccountType) {
  lazy val fullName: UserFullName = UserFullName(s"$firstName $lastName")
}

object User {
  def tupled: (
    (
      UserId,
      UserFirstName,
      UserLastName,
      Option[UserAvatar],
      Option[UserColor],
      UserIsSuperAdmin,
      UserCreatedAt,
      UserModifiedAt,
      Option[UserArchivedAt],
      UserAccountType
    )
  ) => User = { case (id, firstName, lastName, avatar, color, isSuperAdmin, createdAt, modifiedAt, archivedAt, accountType) =>
    User(id, firstName, lastName, avatar, color, isSuperAdmin, createdAt, modifiedAt, archivedAt, accountType)
  }

  def apply(
    id: UserId,
    firstName: UserFirstName,
    lastName: UserLastName,
    avatar: Option[UserAvatar],
    color: Option[UserColor],
    isSuperAdmin: UserIsSuperAdmin,
    createdAt: UserCreatedAt,
    modifiedAt: UserModifiedAt,
    archivedAt: Option[UserArchivedAt]
  ): User =
    User(id, firstName, lastName, avatar, color, isSuperAdmin, createdAt, modifiedAt, archivedAt, UserAccountType.Human)
}

final case class UserWithIdentities(
  id: UserId,
  identities: Seq[UserIdentity],
  firstName: UserFirstName,
  lastName: UserLastName,
  avatar: Option[UserAvatar],
  color: Option[UserColor],
  isSuperAdmin: UserIsSuperAdmin,
  createdAt: UserCreatedAt,
  modifiedAt: UserModifiedAt,
  archivedAt: Option[UserArchivedAt],
  accountType: UserAccountType)

object UserWithIdentities {
  def apply(user: User, identities: Seq[UserIdentity]): UserWithIdentities =
    user.into[UserWithIdentities].withFieldConst(_.identities, identities).transform
}

final case class UserWithIdentitiesDto(
  id: UserId,
  identities: Seq[UserIdentityDto],
  firstName: UserFirstName,
  lastName: UserLastName,
  avatar: Option[UserAvatar],
  color: Option[UserColor],
  isSuperAdmin: UserIsSuperAdmin,
  createdAt: UserCreatedAt,
  modifiedAt: UserModifiedAt,
  archivedAt: Option[UserArchivedAt],
  accountType: UserAccountType)

object UserWithIdentitiesDto {
  def from(user: UserWithIdentities): UserWithIdentitiesDto =
    user.into[UserWithIdentitiesDto].withFieldConst(_.identities, user.identities.map(UserIdentityDto.from)).transform
}
