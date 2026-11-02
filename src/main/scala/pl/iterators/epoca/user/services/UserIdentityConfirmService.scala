package pl.iterators.epoca.user.services

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.user.domain.UserDomain._
import pl.iterators.epoca.user.domain._
import pl.iterators.epoca.user.repositories.UserIdentityRepository
import pl.iterators.epoca.user.repositories.UserIdentityRepository.UserIdentityFilters
import pl.iterators.epoca.user.services.UserIdentityConfirmService.{UserIdentityConfirmRequest, UserIdentityConfirmResult}
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.service.SealedMonadServiceIODBIO
import pl.iterators.sealedmonad.syntax._
import slick.dbio.DBIO

class UserIdentityConfirmService(transactor: PostgresIOTransactor, userIdentityRepository: UserIdentityRepository)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIODBIO[UserIdentityConfirmResult](ioRuntime)
    with LoggingSupport {

  def confirm(
    authContext: AuthContext,
    userIdentityId: UserIdentityId,
    request: UserIdentityConfirmRequest
  ): IO[UserIdentityConfirmResult] = {
    val query = (for {
      userIdentity        <- findUserIdentity(authContext.id, userIdentityId)
      _                   <- validateCode(userIdentity, request.confirmationCode)
      updatedUserIdentity <- updateUserIdentity(userIdentity)
    } yield UserIdentityConfirmResult.Ok(UserIdentityDto.from(updatedUserIdentity))).run

    transactor.executeTransactionally(query)
  }

  private def findUserIdentity(userId: UserId, userIdentityId: UserIdentityId): StepDBIO[UserIdentity] =
    userIdentityRepository
      .find(UserIdentityFilters(id = Some(userIdentityId), userId = Some(userId), forUpdate = true))
      .valueOr(UserIdentityConfirmResult.IdentityNotFound)

  private def validateCode(userIdentity: UserIdentity, code: UserIdentityConfirmationCode): StepDBIO[Boolean] =
    userIdentity.confirmationCode
      .exists(_.trim == code.trim)
      .liftSealed[DBIO, UserIdentityConfirmResult]
      .ensureF[UserIdentityConfirmResult](
        identity,
        Logger[DBIO]
          .info(s"Invalid confirmation code for user identity ${userIdentity.id}")
          .map(_ => UserIdentityConfirmResult.InvalidCode)
      )

  private def updateUserIdentity(userIdentity: UserIdentity): StepDBIO[UserIdentity] = {
    val updated = userIdentity.copy(confirmed = UserIdentityConfirmed(true), confirmationCode = None)
    userIdentityRepository.update(updated).valueOr(UserIdentityConfirmResult.IdentityNotFound)
  }
}

object UserIdentityConfirmService {
  final case class UserIdentityConfirmRequest(confirmationCode: UserIdentityConfirmationCode)

  sealed trait UserIdentityConfirmResult
  object UserIdentityConfirmResult {
    case class Ok(identity: UserIdentityDto) extends UserIdentityConfirmResult
    case object InvalidCode                  extends UserIdentityConfirmResult
    case object IdentityNotFound             extends UserIdentityConfirmResult
  }
}
