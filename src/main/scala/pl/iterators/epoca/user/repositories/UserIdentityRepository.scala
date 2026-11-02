package pl.iterators.epoca.user.repositories

import pl.iterators.epoca.user.domain.UserDomain._
import pl.iterators.epoca.user.domain.UserIdentity
import pl.iterators.epoca.utils.db.FilterParamType
import slick.dbio.DBIO

trait UserIdentityRepository {
  import UserIdentityRepository._

  def insert(user: UserIdentity): DBIO[UserIdentity]
  def update(user: UserIdentity): DBIO[Option[UserIdentity]]
  def find(filter: UserIdentityFilters): DBIO[Option[UserIdentity]]
  def list(filter: UserIdentityFilters): DBIO[Seq[UserIdentity]]
  def purge(filter: UserIdentityFilters): DBIO[Unit]
}

object UserIdentityRepository {
  case class UserIdentityFilters(
    id: Option[UserIdentityId] = None,
    userId: Option[UserId] = None,
    userIds: Option[Seq[UserId]] = None,
    value: Option[UserIdentityValue] = None,
    `type`: Option[IdentityType] = None,
    main: Option[UserIdentityMain] = None,
    confirmed: Option[UserIdentityConfirmed] = None,
    includeArchived: Boolean = false,
    forUpdate: Boolean = false)
      extends FilterParamType
}
