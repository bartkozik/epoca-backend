package pl.iterators.epoca.auth.repositories

import pl.iterators.epoca.auth.domain.AuthDomain._
import pl.iterators.epoca.auth.domain.UserLoginToken
import pl.iterators.epoca.auth.repositories.UserLoginTokenRepository.UserLoginTokenFilters
import pl.iterators.epoca.utils.db.FilterParamType
import slick.dbio.DBIO

trait UserLoginTokenRepository {
  def insert(userLoginToken: UserLoginToken): DBIO[UserLoginToken]

  def find(filters: UserLoginTokenFilters): DBIO[Option[UserLoginToken]]

  def purge(filters: UserLoginTokenFilters): DBIO[Unit]
}

object UserLoginTokenRepository {
  final case class UserLoginTokenFilters(
    id: Option[LoginTokenId] = None,
    loginToken: Option[LoginTokenHash] = None,
    validToBefore: Option[LoginTokenValidTo] = None,
    validToAfterOrNone: Option[LoginTokenValidTo] = None)
      extends FilterParamType
}
