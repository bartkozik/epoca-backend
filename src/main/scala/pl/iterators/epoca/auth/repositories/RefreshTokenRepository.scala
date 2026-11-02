package pl.iterators.epoca.auth.repositories

import pl.iterators.epoca.auth.domain.AuthDomain._
import pl.iterators.epoca.auth.domain.RefreshToken
import pl.iterators.epoca.auth.repositories.RefreshTokenRepository.RefreshTokenFilters
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.utils.db.FilterParamType
import slick.dbio.DBIO

import java.time.Instant

trait RefreshTokenRepository {
  def upsert(refreshToken: RefreshToken): DBIO[RefreshToken]

  /** A rejected exchange commits device revocation; callers must raise errors only after this action completes. */
  def rotate(
    expected: RefreshToken,
    replacement: RefreshToken,
    now: Instant
  ): DBIO[Option[RefreshToken]]
  def revokeIfStale(presented: RefreshToken): DBIO[Int]
  def find(filter: RefreshTokenFilters): DBIO[Option[RefreshToken]]
  def list(filter: RefreshTokenFilters): DBIO[Seq[RefreshToken]]
  def purge(userId: UserId, deviceId: DeviceId): DBIO[Int]
}

object RefreshTokenRepository {
  case class RefreshTokenFilters(
    userId: Option[UserId] = None,
    deviceId: Option[DeviceId] = None,
    value: Option[RefreshTokenValue] = None)
      extends FilterParamType
}
