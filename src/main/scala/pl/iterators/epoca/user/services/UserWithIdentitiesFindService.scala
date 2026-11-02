package pl.iterators.epoca.user.services

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import pl.iterators.epoca.user.domain.UserDomain._
import pl.iterators.epoca.user.domain._
import pl.iterators.epoca.user.repositories.UserIdentityRepository.UserIdentityFilters
import pl.iterators.epoca.user.repositories.UserRepository.UserFilters
import pl.iterators.epoca.user.repositories.{UserIdentityRepository, UserRepository}
import pl.iterators.epoca.user.services.UserWithIdentitiesFindService.UserWithIdentitiesFindResult
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.service.SealedMonadServiceIODBIO
import pl.iterators.sealedmonad.syntax._
class UserWithIdentitiesFindService(
  transactor: PostgresIOTransactor,
  userRepository: UserRepository,
  userIdentityRepository: UserIdentityRepository
)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIODBIO[UserWithIdentitiesFindResult](ioRuntime)
    with LoggingSupport {

  def find(id: UserId): IO[UserWithIdentitiesFindResult] =
    (for {
      user       <- findUser(id)
      identities <- listUserIdentities(id)
    } yield UserWithIdentitiesFindResult.Ok(UserWithIdentities(user, identities))).run

  private def findUser(id: UserId): StepIO[User] =
    transactor.execute(userRepository.find(UserFilters(id = Some(id)))).valueOr(UserWithIdentitiesFindResult.UserDoNotExist)

  private def listUserIdentities(userId: UserId): StepIO[Seq[UserIdentity]] =
    transactor.execute(userIdentityRepository.list(UserIdentityFilters(userId = Some(userId)))).seal
}

object UserWithIdentitiesFindService {

  sealed trait UserWithIdentitiesFindResult
  object UserWithIdentitiesFindResult {
    case class Ok(user: UserWithIdentities) extends UserWithIdentitiesFindResult
    case object UserDoNotExist              extends UserWithIdentitiesFindResult
  }
}
