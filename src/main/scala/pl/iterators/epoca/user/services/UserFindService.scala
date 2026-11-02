package pl.iterators.epoca.user.services

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.domain.UserDomain._
import pl.iterators.epoca.user.repositories.UserRepository
import pl.iterators.epoca.user.repositories.UserRepository.UserFilters
import pl.iterators.epoca.user.services.UserFindService.UserFindResult
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.service.SealedMonadServiceIODBIO
import pl.iterators.sealedmonad.syntax._

class UserFindService(transactor: PostgresIOTransactor, userRepository: UserRepository)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIODBIO[UserFindResult](ioRuntime)
    with LoggingSupport {

  def find(authContext: AuthContext, id: UserId): IO[UserFindResult] =
    (for {
      _     <- checkAdminOrSelf(authContext, id)
      users <- fetchUser(id)
    } yield UserFindResult.Ok(users)).run

  private def checkAdminOrSelf(authContext: AuthContext, id: UserId): StepIO[Boolean] = {
    val condition = authContext.isAdmin || authContext.id == id
    IO.pure(condition).ensure(identity, UserFindResult.OperationNotPermitted)
  }

  private def fetchUser(id: UserId): StepIO[User] =
    transactor.execute(userRepository.find(UserFilters(id = Some(id)))).valueOr(UserFindResult.UserDoNotExist)
}

object UserFindService {

  sealed trait UserFindResult
  object UserFindResult {
    case class Ok(user: User)         extends UserFindResult
    case object UserDoNotExist        extends UserFindResult
    case object OperationNotPermitted extends UserFindResult
  }
}
