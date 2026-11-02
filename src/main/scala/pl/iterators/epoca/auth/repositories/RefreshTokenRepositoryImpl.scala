package pl.iterators.epoca.auth.repositories

import pl.iterators.epoca.auth.domain.AuthDomain.DeviceId
import pl.iterators.epoca.auth.domain.RefreshToken
import pl.iterators.epoca.auth.repositories.RefreshTokenRepository.RefreshTokenFilters
import pl.iterators.epoca.auth.repositories.tables.RefreshTokenTable
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.utils.db.BaseRepository.driver.api._
import pl.iterators.epoca.utils.db.FilteringRepository
import slick.dbio.DBIO
import slick.lifted.{Query, TableQuery}

import java.time.Instant
import scala.concurrent.ExecutionContext

class RefreshTokenRepositoryImpl(implicit e: ExecutionContext)
    extends FilteringRepository[RefreshToken, RefreshTokenTable, RefreshTokenFilters]
    with RefreshTokenRepository {

  override def upsert(token: RefreshToken): DBIO[RefreshToken] =
    table.insertOrUpdate(token).map(_ => token)

  override def rotate(
    expected: RefreshToken,
    replacement: RefreshToken,
    now: Instant
  ): DBIO[Option[RefreshToken]] = {
    require(expected.userId == replacement.userId && expected.deviceId == replacement.deviceId, "Refresh rotation cannot change its owner or device")
    val device = table.filter(r => r.userId === expected.userId && r.deviceId === expected.deviceId)
    device.forUpdate.result.headOption.flatMap {
      case Some(current)
          if current.value == expected.value && current.expiredAt.forall(_.isAfter(now)) && replacement.expiredAt.exists(_.isAfter(now)) =>
        device.update(replacement).map(_ => Option(replacement))
      case Some(_) => device.delete.map(_ => Option.empty[RefreshToken])
      case None    => DBIO.successful(Option.empty[RefreshToken])
    }.transactionally
  }

  override def revokeIfStale(presented: RefreshToken): DBIO[Int] = {
    val device = table.filter(r => r.userId === presented.userId && r.deviceId === presented.deviceId)
    device.forUpdate.result.headOption.flatMap {
      case Some(current) if current.value != presented.value => device.delete
      case _                                                 => DBIO.successful(0)
    }.transactionally
  }

  override def purge(userId: UserId, deviceId: DeviceId): DBIO[Int] =
    table.filter(r => r.userId === userId && r.deviceId === deviceId).delete

  override protected def prepareFilterQuery(tableQuery: Query[RefreshTokenTable, RefreshToken, Seq], filters: RefreshTokenFilters)
    : Query[RefreshTokenTable, RefreshToken, Seq] =
    table
      .filterOpt(filters.userId)(_.userId === _)
      .filterOpt(filters.deviceId)(_.deviceId === _)
      .filterOpt(filters.value)(_.value === _.value)

  override protected val table: TableQuery[RefreshTokenTable] = TableQuery[RefreshTokenTable]
}
