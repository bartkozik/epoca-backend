package pl.iterators.epoca.auth.repositories.tables

import pl.iterators.epoca.auth.domain.AuthDomain.PasswordHash
import pl.iterators.epoca.auth.domain.UserPassword
import pl.iterators.epoca.user.domain.UserDomain.UserIdentityId
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import pl.iterators.epoca.utils.db.{BaseTable, IdTable}
import slick.lifted.ProvenShape

class UserPasswordTable(tag: Tag) extends BaseTable[UserPassword](tag, "user_password") with IdTable[UserPassword, UserIdentityId] {

  override def * : ProvenShape[UserPassword] =
    (userIdentityId, password) <> (UserPassword.tupled, UserPassword.unapply _)

  override def id: Rep[UserIdentityId] = userIdentityId

  def userIdentityId: Rep[UserIdentityId] = column[UserIdentityId]("user_identity_id")

  def password: Rep[PasswordHash] = column[PasswordHash]("password")
}
