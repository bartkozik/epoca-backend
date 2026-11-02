package pl.iterators.epoca.auth.repositories.tables

import pl.iterators.epoca.auth.domain.AuthDomain._
import pl.iterators.epoca.auth.domain.UserLoginToken
import pl.iterators.epoca.user.domain.UserDomain.UserIdentityId
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import pl.iterators.epoca.utils.db.{BaseTable, IdTable}
import slick.lifted.ProvenShape

class UserLoginTokenTable(tag: Tag) extends BaseTable[UserLoginToken](tag, "user_login_token") with IdTable[UserLoginToken, LoginTokenId] {

  override def * : ProvenShape[UserLoginToken] =
    (id, userIdentityId, loginToken, validTo) <> (UserLoginToken.tupled, UserLoginToken.unapply _)

  override def id: Rep[LoginTokenId] = column[LoginTokenId]("id")

  def userIdentityId: Rep[UserIdentityId] = column[UserIdentityId]("user_identity_id")

  def loginToken: Rep[LoginTokenHash] = column[LoginTokenHash]("login_token")

  def validTo: Rep[Option[LoginTokenValidTo]] = column[Option[LoginTokenValidTo]]("valid_to")

}
