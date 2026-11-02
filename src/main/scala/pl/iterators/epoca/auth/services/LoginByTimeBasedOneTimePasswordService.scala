package pl.iterators.epoca.auth.services

import cats.effect.IO
import cats.effect.kernel.Sync
import cats.effect.unsafe.IORuntime
import pl.iterators.epoca.auth.domain.AccountAuthentication.CredentialMethod
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.auth.domain.AuthDomain._
import pl.iterators.epoca.auth.routers.AuthRouter.LoginHttpRequestContext
import pl.iterators.epoca.auth.services.LoginByTimeBasedOneTimePasswordService.{
  LoginByTimeBasedOneTimePasswordRequest,
  LoginByTimeBasedOneTimePasswordResult
}
import pl.iterators.epoca.auth.services.LoginResolveService.{LoginResolution, UserLogin}
import pl.iterators.epoca.organization.domain.OrganizationUser
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.user.domain.UserDomain.{IdentityType, UserId, UserIdentityId}
import pl.iterators.epoca.user.domain.{UserIdentity, UserWithIdentities}
import pl.iterators.epoca.user.repositories.UserIdentityRepository
import pl.iterators.epoca.user.repositories.UserIdentityRepository.UserIdentityFilters
import pl.iterators.epoca.user.services.UserWithIdentitiesFindService
import pl.iterators.epoca.user.services.UserWithIdentitiesFindService.UserWithIdentitiesFindResult
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.service.SealedMonadServiceIODBIO
import pl.iterators.sealedmonad.syntax._

class LoginByTimeBasedOneTimePasswordService(
  transactor: PostgresIOTransactor,
  userWithIdentitiesFindService: UserWithIdentitiesFindService,
  organizationUserRepository: OrganizationUserRepository,
  userIdentityRepository: UserIdentityRepository,
  timeBasedOneTimePasswordService: TimeBasedOneTimePasswordService,
  loginResolveService: LoginResolveService,
  totpRateLimitService: TotpRateLimitService
)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIODBIO[LoginByTimeBasedOneTimePasswordResult](ioRuntime)
    with LoggingSupport {

  def login(
    prevAuthContext: Option[AuthContext],
    loginRequest: LoginByTimeBasedOneTimePasswordRequest,
    requestContext: LoginHttpRequestContext
  ): IO[LoginByTimeBasedOneTimePasswordResult] =
    (for {
      _ <-
        totpRateLimitService.consume(loginRequest.userIdentityId).ensure(allowed => allowed, LoginByTimeBasedOneTimePasswordResult.InvalidCredentials)
      identity           <- findIdentity(prevAuthContext, loginRequest)
      _                  <- checkPassword(identity, loginRequest)
      userWithIdentities <- findUserWithIdentities(identity)
      orgUsers           <- listOrgUsers(userWithIdentities.id)
      result             <- loginResolve(requestContext, userWithIdentities, orgUsers, prevAuthContext, identity)
    } yield LoginByTimeBasedOneTimePasswordResult.Ok(result)).run

  private def findIdentity(prevAuthContext: Option[AuthContext], request: LoginByTimeBasedOneTimePasswordRequest): StepIO[UserIdentity] =
    transactor
      .execute(
        userIdentityRepository.find(
          UserIdentityFilters(
            id = Some(request.userIdentityId),
            userId = prevAuthContext.map(_.id),
            `type` = Some(IdentityType.TimeBasedOneTimePassword)
          )
        )
      )
      .valueOr(LoginByTimeBasedOneTimePasswordResult.UserOrPasswordNotFound)

  private def checkPassword(userIdentity: UserIdentity, loginRequest: LoginByTimeBasedOneTimePasswordRequest): StepIO[TimeBasedOneTimePasswordValue] =
    timeBasedOneTimePasswordService
      .generateIO(TimeBasedOneTimePasswordSecret(userIdentity.value))
      .ensure(_ == loginRequest.password, LoginByTimeBasedOneTimePasswordResult.InvalidCredentials)

  private def findUserWithIdentities(userIdentity: UserIdentity): StepIO[UserWithIdentities] =
    userWithIdentitiesFindService.find(userIdentity.userId).attemptF {
      case UserWithIdentitiesFindResult.Ok(user) => Sync[IO].pure(Right(user))
      case UserWithIdentitiesFindResult.UserDoNotExist =>
        Logger[IO].warning(s"User ${userIdentity.userId} does not exist") *> Sync[IO]
          .pure(Left(LoginByTimeBasedOneTimePasswordResult.UserOrPasswordNotFound))
    }

  private def listOrgUsers(userId: UserId): StepIO[Seq[OrganizationUser]] =
    transactor.execute {
      organizationUserRepository.list(OrganizationUserFilters(userId = Some(userId)))
    }.seal

  private def loginResolve(
    requestContext: LoginHttpRequestContext,
    user: UserWithIdentities,
    orgUsers: Seq[OrganizationUser],
    prevAuthContext: Option[AuthContext],
    userIdentity: UserIdentity
  ): StepIO[LoginResolution] =
    loginResolveService
      .resolve(UserLogin(requestContext, user, orgUsers, prevAuthContext, userIdentity, Some(CredentialMethod.TimeBasedOneTimePassword)))
      .seal

}

object LoginByTimeBasedOneTimePasswordService {
  final case class LoginByTimeBasedOneTimePasswordRequest(userIdentityId: UserIdentityId, password: TimeBasedOneTimePasswordValue)

  sealed trait LoginByTimeBasedOneTimePasswordResult
  object LoginByTimeBasedOneTimePasswordResult {
    final case class Ok(data: LoginResolution) extends LoginByTimeBasedOneTimePasswordResult
    final case object UserOrPasswordNotFound   extends LoginByTimeBasedOneTimePasswordResult
    final case object InvalidCredentials       extends LoginByTimeBasedOneTimePasswordResult
  }

}
