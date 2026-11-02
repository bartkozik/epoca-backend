package pl.iterators.epoca.auth.repositories

import pl.iterators.epoca.auth.domain.AccountAuthentication.BoundSaml
import pl.iterators.epoca.user.domain.UserDomain.UserId
import slick.dbio.DBIO

trait AccountAuthenticationRepository {
  def activeHumanForShare(userId: UserId): DBIO[Boolean]
  def currentSamlBindingForShare(userId: UserId, evidence: BoundSaml): DBIO[Boolean]
}
