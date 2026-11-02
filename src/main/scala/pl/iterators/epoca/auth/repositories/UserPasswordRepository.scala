package pl.iterators.epoca.auth.repositories

import pl.iterators.epoca.auth.domain.UserPassword
import pl.iterators.epoca.user.domain.UserDomain.UserIdentityId
import slick.dbio.DBIO

trait UserPasswordRepository {
  def upsert(userPassword: UserPassword): DBIO[UserPassword]

  def find(userIdentityId: UserIdentityId): DBIO[Option[UserPassword]]
}
