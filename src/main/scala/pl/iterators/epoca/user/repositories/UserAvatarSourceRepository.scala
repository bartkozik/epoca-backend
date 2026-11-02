package pl.iterators.epoca.user.repositories

import pl.iterators.epoca.user.domain.UserAvatarSource
import pl.iterators.epoca.user.domain.UserDomain.UserId
import slick.dbio.DBIO

trait UserAvatarSourceRepository {
  def find(userId: UserId): DBIO[Option[UserAvatarSource]]
  def upsert(source: UserAvatarSource): DBIO[Unit]
}
