package pl.iterators.epoca.user.services

import cats.effect.unsafe.IORuntime
import cats.effect.{Clock, IO}
import io.scalaland.chimney.dsl._
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.domain.UserDomain._
import pl.iterators.epoca.user.repositories.UserRepository
import pl.iterators.epoca.user.repositories.UserRepository.UserFilters
import pl.iterators.epoca.user.services.UserUpdateService.{UserUpdateRequest, UserUpdateResult}
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.service.SealedMonadServiceIODBIO
import pl.iterators.sealedmonad.syntax._

class UserUpdateService(transactor: PostgresIOTransactor, userRepository: UserRepository)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIODBIO[UserUpdateResult](ioRuntime)
    with LoggingSupport {

  def update(
    authContext: AuthContext,
    id: UserId,
    request: UserUpdateRequest
  ): IO[UserUpdateResult] =
    (for {
      _           <- checkAdminOrSelf(authContext, id)
      user        <- findUser(id)
      updatedUser <- updatedUser(user, request)
      result      <- updateUser(updatedUser)
    } yield UserUpdateResult.Ok(result)).run

  private def checkAdminOrSelf(authContext: AuthContext, id: UserId): StepIO[Boolean] = {
    val condition = authContext.isSuperAdmin || authContext.id == id
    IO.pure(condition).ensureF(identity, Logger[IO].warning("Illegal access occurred").as(UserUpdateResult.OperationNotPermitted))
  }

  private def findUser(id: UserId): StepIO[User] =
    transactor.execute(userRepository.find(UserFilters(id = Some(id)))).valueOr(UserUpdateResult.UserDoNotExist)

  private def updatedUser(user: User, request: UserUpdateRequest): StepIO[User] =
    Clock[IO].instantNow.seal.map { now =>
      user
        .using(request)
        .ignoreNoneInPatch
        .patch
        .copy(modifiedAt = UserModifiedAt(now))
    }

  private def updateUser(user: User): StepIO[User] =
    transactor.execute(userRepository.update(user)).valueOr(UserUpdateResult.UserDoNotExist)

}

object UserUpdateService {

  final case class UserUpdateRequest(
    firstName: Option[UserFirstName],
    lastName: Option[UserLastName],
    avatar: Option[UserAvatar],
    color: Option[UserColor])

  sealed trait UserUpdateResult

  object UserUpdateResult {
    case class Ok(user: User)         extends UserUpdateResult
    case object UserDoNotExist        extends UserUpdateResult
    case object OperationNotPermitted extends UserUpdateResult
  }
}
