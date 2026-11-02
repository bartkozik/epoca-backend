package pl.iterators.epoca.user.services

import cats.effect.IO
import cats.effect.kernel.Sync
import cats.effect.unsafe.IORuntime
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.user.domain.{UserWithIdentities, UserWithIdentitiesDto}
import pl.iterators.epoca.user.repositories.UserRepository
import pl.iterators.epoca.user.repositories.UserRepository.UserFilters
import pl.iterators.epoca.user.services.UserListService._
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.service.SealedMonadServiceIODBIO
import pl.iterators.sealedmonad.syntax._

class UserListService(transactor: PostgresIOTransactor, userRepository: UserRepository)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIODBIO[UserListResult](ioRuntime)
    with LoggingSupport {

  def listWithIdentities(authContext: AuthContext, filters: UserFilters): IO[UserListResult] =
    (for {
      _     <- checkAdmin(authContext)
      users <- fetchUsersWithIdentities(filters)
    } yield UserListResult.Ok(users.map(UserWithIdentitiesDto.from))).run

  private def checkAdmin(authContext: AuthContext): StepIO[Boolean] =
    IO
      .pure(authContext.isAdmin)
      .ensureF(identity, Logger[IO].warning("Illegal access occurred") *> Sync[IO].pure(UserListResult.OperationNotPermitted))

  private def fetchUsersWithIdentities(filters: UserFilters): StepIO[Seq[UserWithIdentities]] =
    transactor.execute(userRepository.listWithIdentities(filters)).seal
}

object UserListService {

  sealed trait UserListResult
  object UserListResult {
    case class Ok(users: Seq[UserWithIdentitiesDto]) extends UserListResult
    case object OperationNotPermitted                extends UserListResult
  }
}
