package pl.iterators.epoca.user.services

import cats.effect.IO
import cats.effect.kernel.Sync
import cats.effect.unsafe.IORuntime
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.user.domain.UserDomain._
import pl.iterators.epoca.user.domain._
import pl.iterators.epoca.user.repositories.UserIdentityRepository
import pl.iterators.epoca.user.repositories.UserIdentityRepository.UserIdentityFilters
import pl.iterators.epoca.user.services.UserIdentityDeleteService.UserIdentityDeleteResult
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.service.SealedMonadServiceIODBIO
import pl.iterators.sealedmonad.syntax._
import slick.dbio.DBIO
class UserIdentityDeleteService(transactor: PostgresIOTransactor, userIdentityRepository: UserIdentityRepository)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIODBIO[UserIdentityDeleteResult](ioRuntime)
    with LoggingSupport {

  def delete(authContext: AuthContext, userIdentityId: UserIdentityId): IO[UserIdentityDeleteResult] = {
    val query = (for {
      _ <- validateAuthContext(authContext)
      _ <- findUserIdentity(authContext.id, userIdentityId)
      _ <- deleteIdentity(userIdentityId)
    } yield UserIdentityDeleteResult.Ok).run

    transactor.executeTransactionally(query)
  }

  private def validateAuthContext(authContext: AuthContext): StepDBIO[Boolean] =
    Sync[DBIO]
      .pure(authContext.fresh)
      .ensure(identity, UserIdentityDeleteResult.IdentityNotFresh)

  private def findUserIdentity(userId: UserId, userIdentityId: UserIdentityId): StepDBIO[UserIdentity] =
    userIdentityRepository
      .find(UserIdentityFilters(id = Some(userIdentityId), userId = Some(userId), forUpdate = true))
      .valueOr(UserIdentityDeleteResult.IdentityNotFound)
      .ensure(!_.main, UserIdentityDeleteResult.CanNotDeleteMain)

  private def deleteIdentity(userIdentityId: UserIdentityId): StepDBIO[Unit] =
    userIdentityRepository.purge(UserIdentityFilters(id = Some(userIdentityId))).seal
}

object UserIdentityDeleteService {
  sealed trait UserIdentityDeleteResult
  object UserIdentityDeleteResult {
    case object Ok               extends UserIdentityDeleteResult
    case object IdentityNotFound extends UserIdentityDeleteResult
    case object CanNotDeleteMain extends UserIdentityDeleteResult
    case object IdentityNotFresh extends UserIdentityDeleteResult
  }
}
