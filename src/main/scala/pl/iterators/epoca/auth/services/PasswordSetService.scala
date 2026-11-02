package pl.iterators.epoca.auth.services

import cats.Monad
import cats.effect.unsafe.IORuntime
import cats.effect.{Clock, IO}
import pl.iterators.epoca.auth.domain.AuthDomain._
import pl.iterators.epoca.auth.domain.{AuthContext, UserPassword}
import pl.iterators.epoca.auth.repositories.UserPasswordRepository
import pl.iterators.epoca.auth.services.PasswordSetService.{PasswordSetRequest, PasswordSetResult}
import pl.iterators.epoca.auth.services.PasswordValidateService.{PasswordValidateProblem, PasswordValidateResult}
import pl.iterators.epoca.user.domain.UserDomain.{IdentityType, UserAccountType, UserIdentityId}
import pl.iterators.epoca.user.domain.UserIdentity
import pl.iterators.epoca.user.repositories.UserIdentityRepository.UserIdentityFilters
import pl.iterators.epoca.user.repositories.UserRepository.UserFilters
import pl.iterators.epoca.user.repositories.{UserIdentityRepository, UserRepository}
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.service.SealedMonadServiceIODBIO
import pl.iterators.sealedmonad.syntax._
import slick.dbio.DBIO

class PasswordSetService(
  transactor: PostgresIOTransactor,
  passwordValidateService: PasswordValidateService,
  userPasswordRepository: UserPasswordRepository,
  userIdentityRepository: UserIdentityRepository,
  passwordService: PasswordService,
  userRepository: UserRepository,
  accountAuthenticationValidator: AccountAuthenticationValidator
)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIODBIO[PasswordSetResult](ioRuntime) {

  def passwordSet(authContext: AuthContext, request: PasswordSetRequest): IO[PasswordSetResult] =
    transactor.executeTransactionally {
      (for {
        _        <- validateAuthContext(authContext)
        _        <- validateTrustedAuthentication(authContext)
        _        <- checkPassword(request)
        identity <- findUserIdentity(authContext, request)
        userPassword = preparePassword(request)
        _ <- ensurePasswordIsChanged(identity, userPassword)
        _ <- insertPassword(userPassword)
      } yield PasswordSetResult.Ok).run
    }

  private def validateAuthContext(authContext: AuthContext): StepDBIO[Boolean] =
    Monad[DBIO]
      .pure(authContext.fresh && authContext.accountAuthentication.nonEmpty)
      .ensure(identity, PasswordSetResult.InvalidAuthContext)

  private def validateTrustedAuthentication(authContext: AuthContext): StepDBIO[Unit] =
    for {
      _ <- userRepository
             .find(UserFilters(id = Some(authContext.id), forUpdate = true))
             .valueOr(PasswordSetResult.InvalidAuthContext)
             .ensure(user => user.accountType == UserAccountType.Human && user.archivedAt.isEmpty, PasswordSetResult.InvalidAuthContext)
      now <- Clock[DBIO].instantNow.seal
      _   <- accountAuthenticationValidator.validate(authContext, now).ensure(identity, PasswordSetResult.InvalidAuthContext)
    } yield ()

  private def checkPassword(request: PasswordSetRequest): StepDBIO[Unit] =
    Monad[DBIO]
      .pure(passwordValidateService.validate(request.newPassword))
      .attempt {
        case PasswordValidateResult.Valid             => Right(())
        case PasswordValidateResult.Invalid(problems) => Left(PasswordSetResult.InvalidPassword(problems))
      }

  private def findUserIdentity(authContext: AuthContext, request: PasswordSetRequest): StepDBIO[UserIdentity] =
    userIdentityRepository
      .find(UserIdentityFilters(id = Some(request.userIdentityId), userId = Some(authContext.id), forUpdate = true))
      .valueOr(PasswordSetResult.IdentityNotFound)
      .ensure(i => i.`type` == IdentityType.MailAddress || i.`type` == IdentityType.Phone, PasswordSetResult.IdentityNotFound)

  private def ensurePasswordIsChanged(userIdentity: UserIdentity, newPassword: UserPassword): StepDBIO[Option[UserPassword]] =
    userPasswordRepository.find(userIdentity.id).ensure(_.forall(_.password != newPassword.password), PasswordSetResult.PasswordNotChanged)

  private def preparePassword(request: PasswordSetRequest): UserPassword = {
    val hashed = passwordService.encrypt(request.newPassword)
    UserPassword(request.userIdentityId, hashed)
  }

  private def insertPassword(userPassword: UserPassword): StepDBIO[UserPassword] =
    userPasswordRepository
      .upsert(userPassword)
      .seal

}

object PasswordSetService {
  sealed trait PasswordSetResult

  final case class PasswordSetRequest(userIdentityId: UserIdentityId, newPassword: PasswordPlain)

  object PasswordSetResult {
    final case object Ok                                                     extends PasswordSetResult
    final case object IdentityNotFound                                       extends PasswordSetResult
    final case class InvalidPassword(problems: Set[PasswordValidateProblem]) extends PasswordSetResult
    final case object InvalidAuthContext                                     extends PasswordSetResult
    final case object PasswordNotChanged                                     extends PasswordSetResult
  }

}
