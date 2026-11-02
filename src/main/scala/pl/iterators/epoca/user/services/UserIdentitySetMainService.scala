package pl.iterators.epoca.user.services

import cats.effect.IO
import cats.effect.kernel.Sync
import cats.effect.unsafe.IORuntime
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.user.domain.UserDomain._
import pl.iterators.epoca.user.domain._
import pl.iterators.epoca.user.repositories.UserIdentityRepository.UserIdentityFilters
import pl.iterators.epoca.user.repositories.UserRepository.UserFilters
import pl.iterators.epoca.user.repositories.{UserIdentityRepository, UserRepository}
import pl.iterators.epoca.user.services.UserIdentitySetMainService.UserIdentitySetMainResult
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.service.SealedMonadServiceIODBIO
import pl.iterators.sealedmonad.syntax._
import slick.dbio.DBIO
class UserIdentitySetMainService(
  transactor: PostgresIOTransactor,
  userIdentityRepository: UserIdentityRepository,
  userRepository: UserRepository
)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIODBIO[UserIdentitySetMainResult](ioRuntime)
    with LoggingSupport {

  def setMain(authContext: AuthContext, id: UserIdentityId): IO[UserIdentitySetMainResult] = {
    val query = (for {
      // Serialize all operations that lock multiple identities before acquiring individual identity locks.
      _ <- userRepository.find(UserFilters(id = Some(authContext.id), forUpdate = true)).valueOr(UserIdentitySetMainResult.IdentityNotFound)
      requestedIdentity      <- findRequestedIdentity(authContext.id, id)
      currentMainIdentityOpt <- findCurrentMainIdentity(authContext.id, id)
      _                      <- updateCurrentMainIdentity(currentMainIdentityOpt)
      updatedUserIdentity    <- updateRequestedIdentity(requestedIdentity)
    } yield UserIdentitySetMainResult.Ok(UserIdentityDto.from(updatedUserIdentity))).run

    transactor.executeTransactionally(query)
  }

  private def findRequestedIdentity(userId: UserId, id: UserIdentityId): StepDBIO[UserIdentity] =
    userIdentityRepository
      .find(UserIdentityFilters(id = Some(id), userId = Some(userId), forUpdate = true))
      .valueOr(UserIdentitySetMainResult.IdentityNotFound)

  private def findCurrentMainIdentity(userId: UserId, id: UserIdentityId): StepDBIO[Option[UserIdentity]] =
    userIdentityRepository
      .find(UserIdentityFilters(userId = Some(userId), main = Some(UserIdentityMain(true)), forUpdate = true))
      .ensure(!_.map(_.id).contains(id), UserIdentitySetMainResult.AlreadyMain)

  private def updateCurrentMainIdentity(currentMainIdentityOpt: Option[UserIdentity]): StepDBIO[Option[UserIdentity]] =
    ((currentMainIdentityOpt match {
      case Some(currentMainIdentity) =>
        val updated = currentMainIdentity.copy(main = UserIdentityMain(false))
        userIdentityRepository.update(updated)
      case None => Sync[DBIO].pure(None)
    }): DBIO[Option[UserIdentity]]).seal

  private def updateRequestedIdentity(requestedIdentity: UserIdentity): StepDBIO[UserIdentity] = {
    val updated = requestedIdentity.copy(main = UserIdentityMain(true))
    userIdentityRepository.update(updated).valueOr(UserIdentitySetMainResult.IdentityNotFound)
  }
}

object UserIdentitySetMainService {
  sealed trait UserIdentitySetMainResult
  object UserIdentitySetMainResult {
    case class Ok(identity: UserIdentityDto) extends UserIdentitySetMainResult
    case object AlreadyMain                  extends UserIdentitySetMainResult
    case object IdentityNotFound             extends UserIdentitySetMainResult
  }
}
