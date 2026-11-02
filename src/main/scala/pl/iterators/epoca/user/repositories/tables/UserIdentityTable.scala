package pl.iterators.epoca.user.repositories.tables

import pl.iterators.epoca.auth.domain.AuthDomain.DeviceId
import pl.iterators.epoca.user.domain.UserDomain._
import pl.iterators.epoca.user.domain.UserIdentity
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import pl.iterators.epoca.utils.db.{BaseTable, IdTable}
import slick.lifted.ProvenShape

class UserIdentityTable(tag: Tag) extends BaseTable[UserIdentity](tag, "user_identity") with IdTable[UserIdentity, UserIdentityId] {

  override def id: Rep[UserIdentityId] = column[UserIdentityId]("id")

  def userId: Rep[UserId]                                         = column[UserId]("user_id")
  def `type`: Rep[IdentityType]                                   = column[IdentityType]("type")
  def value: Rep[UserIdentityValue]                               = column[UserIdentityValue]("value")
  def main: Rep[UserIdentityMain]                                 = column[UserIdentityMain]("main")
  def confirmed: Rep[UserIdentityConfirmed]                       = column[UserIdentityConfirmed]("confirmed")
  def confirmationCode: Rep[Option[UserIdentityConfirmationCode]] = column[Option[UserIdentityConfirmationCode]]("confirmation_code")
  def deviceId: Rep[Option[DeviceId]]                             = column[Option[DeviceId]]("device_id")
  def createdAt: Rep[UserIdentityCreatedAt]                       = column[UserIdentityCreatedAt]("created_at")

  override def * : ProvenShape[UserIdentity] =
    (id, userId, `type`, value, main, confirmed, confirmationCode, deviceId, createdAt) <> (UserIdentity.tupled, UserIdentity.unapply _)
}
