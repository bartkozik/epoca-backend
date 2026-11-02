package pl.iterators.epoca.auth.services

import cats.effect.kernel.Sync
import cats.effect.unsafe.IORuntime
import cats.effect.{Clock, IO}
import pl.iterators.epoca.auth.domain.AccountAuthentication.CredentialMethod
import pl.iterators.epoca.auth.domain.AuthDomain._
import pl.iterators.epoca.auth.domain.{AuthContext, UserLoginToken}
import pl.iterators.epoca.auth.repositories.UserLoginTokenRepository
import pl.iterators.epoca.auth.repositories.UserLoginTokenRepository.UserLoginTokenFilters
import pl.iterators.epoca.auth.routers.AuthRouter.LoginHttpRequestContext
import pl.iterators.epoca.auth.services.LoginByTokenService._
import pl.iterators.epoca.auth.services.LoginResolveService.{LoginResolution, UserLogin}
import pl.iterators.epoca.mail.domain.MailDomain.MailAddress
import pl.iterators.epoca.organization.domain.OrganizationUser
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.sms.domain.SmsDomain.PhoneNumber
import pl.iterators.epoca.user.domain.UserDomain._
import pl.iterators.epoca.user.domain.{UserIdentity, UserWithIdentities}
import pl.iterators.epoca.user.repositories.UserIdentityRepository
import pl.iterators.epoca.user.repositories.UserIdentityRepository.UserIdentityFilters
import pl.iterators.epoca.user.services.UserWithIdentitiesFindService
import pl.iterators.epoca.user.services.UserWithIdentitiesFindService.UserWithIdentitiesFindResult
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.service.SealedMonadServiceIODBIO
import pl.iterators.sealedmonad.syntax._

import java.time.Instant

class LoginByTokenService(
  transactor: PostgresIOTransactor,
  loginTokenHashService: LoginTokenHashService,
  userWithIdentitiesFindService: UserWithIdentitiesFindService,
  userLoginTokenRepository: UserLoginTokenRepository,
  organizationUserRepository: OrganizationUserRepository,
  userIdentityRepository: UserIdentityRepository,
  loginResolveService: LoginResolveService
)(implicit
  ioRuntime: IORuntime,
  clock: Clock[IO])
    extends SealedMonadServiceIODBIO[LoginByTokenResult](ioRuntime)
    with LoggingSupport {

  def login(
    prevAuthContext: Option[AuthContext],
    loginByTokenRequest: LoginByTokenRequest,
    requestContext: LoginHttpRequestContext
  ): IO[LoginByTokenResult] =
    (for {
      now <- clock.instantNow.seal
      loginTokenHashed = hashLoginToken(loginByTokenRequest)
      token    <- findLoginToken(now, loginTokenHashed)
      identity <- findUserIdentity(prevAuthContext, token, loginByTokenRequest)
      _        <- confirmIdentity(identity)
      user     <- findUserWithIdentities(identity.userId)
      orgUsers <- listOrgUsers(user.id)
      result   <- loginResolve(requestContext, user, orgUsers, prevAuthContext, identity)
      _        <- purgeLoginToken(token)
    } yield LoginByTokenResult.Ok(result)).run

  private def hashLoginToken(request: LoginByTokenRequest): LoginTokenHash =
    loginTokenHashService.hash(request.token)

  private def findLoginToken(now: Instant, loginTokenHash: LoginTokenHash): StepIO[UserLoginToken] =
    transactor
      .execute(
        userLoginTokenRepository
          .find(UserLoginTokenFilters(loginToken = Some(loginTokenHash), validToAfterOrNone = Some(LoginTokenValidTo(now))))
      )
      .valueOr(LoginByTokenResult.UserOrLoginTokenNotFound)

  private def findUserIdentity(
    prevAuthContext: Option[AuthContext],
    userLoginToken: UserLoginToken,
    loginByTokenRequest: LoginByTokenRequest
  ): StepIO[UserIdentity] =
    transactor
      .execute(userIdentityRepository.find(UserIdentityFilters(id = Some(userLoginToken.userIdentityId), userId = prevAuthContext.map(_.id))))
      .valueOr(LoginByTokenResult.InvalidCredentials)
      // Reject legacy phone codes as well as newly generated phone recovery tokens.
      .ensure(_.`type` == IdentityType.MailAddress, LoginByTokenResult.InvalidCredentials)
      // Recovery codes are returned at signup and do not prove ownership of an unconfirmed identity.
      .ensure(identity => userLoginToken.validTo.isDefined || identity.confirmed, LoginByTokenResult.InvalidCredentials)
      .ensure(
        identity => identity.`type` == IdentityType.MailAddress && loginByTokenRequest.email.exists(_.toLowerCase == identity.value.toLowerCase),
        LoginByTokenResult.InvalidCredentials
      )

  private def confirmIdentity(identity: UserIdentity): StepIO[Unit] =
    if (!identity.confirmed)
      transactor.execute(userIdentityRepository.update(identity.copy(confirmed = UserIdentityConfirmed(true)))).map(_ => ()).seal
    else Sync[IO].pure(()).seal

  private def findUserWithIdentities(userId: UserId): StepIO[UserWithIdentities] = userWithIdentitiesFindService.find(userId).attemptF {
    case UserWithIdentitiesFindResult.Ok(user) => Sync[IO].pure(Right(user))
    case UserWithIdentitiesFindResult.UserDoNotExist =>
      Logger[IO].warning(s"User $userId does not exist") *> Sync[IO].pure(Left(LoginByTokenResult.UserOrLoginTokenNotFound))
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
    loginResolveService.resolve(UserLogin(requestContext, user, orgUsers, prevAuthContext, userIdentity, Some(CredentialMethod.Token))).seal

  private def purgeLoginToken(token: UserLoginToken): StepIO[Unit] =
    transactor.execute(userLoginTokenRepository.purge(UserLoginTokenFilters(id = Some(token.id)))).seal

}

object LoginByTokenService {
  final case class LoginByTokenRequest(
    email: Option[MailAddress],
    phoneNumber: Option[PhoneNumber],
    token: LoginTokenPlain)

  sealed trait LoginByTokenResult
  object LoginByTokenResult {
    final case class Ok(data: LoginResolution) extends LoginByTokenResult
    final case object UserOrLoginTokenNotFound extends LoginByTokenResult
    final case object InvalidCredentials       extends LoginByTokenResult
    final case object TokenExpired             extends LoginByTokenResult
  }

}
