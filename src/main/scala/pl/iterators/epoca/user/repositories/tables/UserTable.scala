package pl.iterators.epoca.user.repositories.tables

import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.domain.UserDomain._
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import pl.iterators.epoca.utils.db.{BaseTable, IdTable}
import slick.lifted.ProvenShape

class UserTable(tag: Tag) extends BaseTable[User](tag, "user") with IdTable[User, UserId] {

  override def id: Rep[UserId]                = column[UserId]("id")
  def firstName: Rep[UserFirstName]           = column[UserFirstName]("first_name")
  def lastName: Rep[UserLastName]             = column[UserLastName]("last_name")
  def avatar: Rep[Option[UserAvatar]]         = column[Option[UserAvatar]]("avatar")
  def color: Rep[Option[UserColor]]           = column[Option[UserColor]]("color")
  def isSuperAdmin: Rep[UserIsSuperAdmin]     = column[UserIsSuperAdmin]("is_super_admin")
  def createdAt: Rep[UserCreatedAt]           = column[UserCreatedAt]("created_at")
  def modifiedAt: Rep[UserModifiedAt]         = column[UserModifiedAt]("modified_at")
  def archivedAt: Rep[Option[UserArchivedAt]] = column[Option[UserArchivedAt]]("archived_at")
  def accountType: Rep[UserAccountType]       = column[UserAccountType]("account_type")

  override def * : ProvenShape[User] =
    (id, firstName, lastName, avatar, color, isSuperAdmin, createdAt, modifiedAt, archivedAt, accountType) <> (User.tupled, User.unapply _)
}
