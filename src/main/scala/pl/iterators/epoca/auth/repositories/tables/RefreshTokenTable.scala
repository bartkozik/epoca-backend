package pl.iterators.epoca.auth.repositories.tables

import pl.iterators.epoca.auth.domain.AuthDomain._
import pl.iterators.epoca.auth.domain.{AccountAuthentication, RefreshToken}
import pl.iterators.epoca.user.domain.UserDomain._
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import pl.iterators.epoca.utils.db.{BaseTable, PrimaryKeyTable}
import slick.lifted.{PrimaryKey, ProvenShape}

class RefreshTokenTable(tag: Tag) extends BaseTable[RefreshToken](tag, "refresh_token") with PrimaryKeyTable[RefreshToken] {

  def accountAuthentication: Rep[Option[AccountAuthentication]] = column[Option[AccountAuthentication]]("account_authentication")

  override def * : ProvenShape[RefreshToken] =
    (
      userId,
      deviceId,
      userAgent,
      value,
      authedBy,
      lastAuthedAt,
      createdAt,
      expiredAt,
      accountAuthentication
    ) <> (RefreshToken.tupled, RefreshToken.unapply _)

  override val primaryKey: PrimaryKey = primaryKey(userId, deviceId)

  def userId: Rep[UserId]                           = column[UserId]("user_id")
  def deviceId: Rep[DeviceId]                       = column[DeviceId]("device_id")
  def userAgent: Rep[Option[RefreshTokenUserAgent]] = column[Option[RefreshTokenUserAgent]]("user_agent")
  def value: Rep[RefreshTokenValue]                 = column[RefreshTokenValue]("value")
  def authedBy: Rep[Seq[UserIdentityId]]            = column[Seq[UserIdentityId]]("authed_by")
  def lastAuthedAt: Rep[RefreshTokenLastUsedAt]     = column[RefreshTokenLastUsedAt]("last_authed_at")
  def createdAt: Rep[RefreshTokenCreatedAt]         = column[RefreshTokenCreatedAt]("created_at")
  def expiredAt: Rep[Option[RefreshTokenExpiredAt]] = column[Option[RefreshTokenExpiredAt]]("expired_at")

}
