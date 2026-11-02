package pl.iterators.epoca.user.services

import cats.effect.unsafe.IORuntime
import cats.effect.{Clock, IO}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.domain.UserDomain._
import pl.iterators.epoca.user.repositories.UserIdentityRepository.UserIdentityFilters
import pl.iterators.epoca.user.repositories.UserRepository.UserFilters
import pl.iterators.epoca.user.repositories.{UserIdentityRepository, UserRepository}
import pl.iterators.epoca.user.services.UserDeleteService._
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.service.SealedMonadServiceIODBIO
import pl.iterators.sealedmonad.syntax._

class UserDeleteService(
  transactor: PostgresIOTransactor,
  userRepository: UserRepository,
  userIdentityRepository: UserIdentityRepository
)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIODBIO[UserDeleteResult](ioRuntime)
    with LoggingSupport {

  def delete(authContext: AuthContext, id: UserId): IO[UserDeleteResult] =
    (for {
      _            <- checkAdminOrSelf(authContext, id)
      user         <- findUser(id)
      archivedUser <- deletedUser(user)
      _            <- deleteUser(archivedUser)
      _            <- purgeUserIdentities(user)
      _            <- Logger[IO].info(s"Deleting user $id").seal
    } yield UserDeleteResult.Ok).run

  private def checkAdminOrSelf(authContext: AuthContext, id: UserId): StepIO[Boolean] = {
    val condition = authContext.isAdmin || authContext.id == id
    IO.pure(condition).ensure(identity, UserDeleteResult.OperationNotPermitted)
  }

  private def findUser(id: UserId): StepIO[User] =
    transactor.execute(userRepository.find(UserFilters(id = Some(id)))).valueOr(UserDeleteResult.UserDoNotExist)

  private def deletedUser(user: User): StepIO[User] =
    Clock[IO].instantNow.seal.map { now =>
      user.copy(archivedAt = Some(UserArchivedAt(now)))
    }

  private def deleteUser(user: User): StepIO[Option[User]] =
    transactor.execute(userRepository.update(user)).seal

  private def purgeUserIdentities(user: User): StepIO[Unit] =
    transactor.execute(userIdentityRepository.purge(UserIdentityFilters(userId = Some(user.id)))).seal
}

object UserDeleteService {
  sealed trait UserDeleteResult

  object UserDeleteResult {
    case object Ok                    extends UserDeleteResult
    case object UserDoNotExist        extends UserDeleteResult
    case object OperationNotPermitted extends UserDeleteResult
  }
}
