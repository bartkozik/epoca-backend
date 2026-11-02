package pl.iterators.epoca.auth.services

import cats.effect.IO
import cats.effect.kernel.Sync
import cats.effect.unsafe.IORuntime
import pl.iterators.epoca.auth.domain.AccountAuthentication.CredentialMethod
import pl.iterators.epoca.auth.domain.AuthDomain._
import pl.iterators.epoca.auth.domain.{AuthContext, UserPassword}
import pl.iterators.epoca.auth.repositories.UserPasswordRepository
import pl.iterators.epoca.auth.routers.AuthRouter.LoginHttpRequestContext
import pl.iterators.epoca.auth.services.LoginByPasswordService.{LoginRequest, LoginResult}
import pl.iterators.epoca.auth.services.LoginResolveService.{LoginResolution, UserLogin}
import pl.iterators.epoca.mail.domain.MailDomain.MailAddress
import pl.iterators.epoca.organization.domain.OrganizationUser
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.sms.domain.SmsDomain.PhoneNumber
import pl.iterators.epoca.user.domain.UserDomain.{IdentityType, UserId}
import pl.iterators.epoca.user.domain.{User, UserIdentity, UserWithIdentities}
import pl.iterators.epoca.user.repositories.UserRepository
import pl.iterators.epoca.user.repositories.UserRepository.UserFilters
import pl.iterators.epoca.user.services.UserWithIdentitiesFindService
import pl.iterators.epoca.user.services.UserWithIdentitiesFindService.UserWithIdentitiesFindResult
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.service.SealedMonadServiceIODBIO
import pl.iterators.sealedmonad.syntax._

class LoginByPasswordService(
  transactor: PostgresIOTransactor,
  userWithIdentitiesFindService: UserWithIdentitiesFindService,
  organizationUserRepository: OrganizationUserRepository,
  userRepository: UserRepository,
  userPasswordRepository: UserPasswordRepository,
  passwordService: PasswordService,
  loginResolveService: LoginResolveService
)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIODBIO[LoginResult](ioRuntime)
    with LoggingSupport {

  def login(
    prevAuthContext: Option[AuthContext],
    loginRequest: LoginRequest,
    requestContext: LoginHttpRequestContext
  ): IO[LoginResult] =
    (for {
      _                  <- validateRequest(loginRequest)
      user               <- findUser(prevAuthContext, loginRequest)
      userWithIdentities <- findUserWithIdentities(user)
      identity           <- findIdentity(userWithIdentities, loginRequest)
      password           <- findPassword(identity)
      _                  <- checkPassword(password, loginRequest)
      _                  <- ensureIdentityIsConfirmed(identity)
      orgUsers           <- listOrgUsers(user.id)
      result             <- loginResolve(requestContext, userWithIdentities, orgUsers, prevAuthContext, identity)
    } yield LoginResult.Ok(result)).run

  private def validateRequest(request: LoginRequest): StepIO[Boolean] =
    Sync[IO]
      .pure(
        (request.email.isDefined && request.phoneNumber.isEmpty) ||
          (request.email.isEmpty && request.phoneNumber.isDefined)
      )
      .ensure(identity, LoginResult.InvalidRequest)

  private def findUser(prevAuthContext: Option[AuthContext], request: LoginRequest): StepIO[User] =
    transactor
      .execute(
        userRepository
          .find(UserFilters(id = prevAuthContext.map(_.id), email = request.email, phoneNumber = request.phoneNumber))
      )
      .valueOr(LoginResult.UserOrPasswordNotFound)

  private def findUserWithIdentities(user: User): StepIO[UserWithIdentities] = userWithIdentitiesFindService.find(user.id).attemptF {
    case UserWithIdentitiesFindResult.Ok(user) => Sync[IO].pure(Right(user))
    case UserWithIdentitiesFindResult.UserDoNotExist =>
      Logger[IO].warning(s"User ${user.id} does not exist") *> Sync[IO].pure(Left(LoginResult.UserOrPasswordNotFound))
  }

  private def findIdentity(user: UserWithIdentities, request: LoginRequest): StepIO[UserIdentity] =
    Sync[IO]
      .pure(user.identities.find { i =>
        request.email.fold {
          request.phoneNumber.fold(false) { phoneNumber =>
            PhoneNumber(i.value) == phoneNumber && i.`type` == IdentityType.Phone
          }
        } { email =>
          MailAddress(i.value) == email && i.`type` == IdentityType.MailAddress
        }
      })
      .valueOr(LoginResult.UserOrPasswordNotFound)

  private def findPassword(userIdentity: UserIdentity): StepIO[UserPassword] =
    transactor
      .execute(
        userPasswordRepository
          .find(userIdentity.id)
      )
      .valueOr(LoginResult.UserOrPasswordNotFound)

  private def checkPassword(userPassword: UserPassword, loginRequest: LoginRequest): StepIO[Boolean] =
    Sync[IO]
      .pure(passwordService.check(loginRequest.password, userPassword.password))
      .ensure(identity, LoginResult.InvalidCredentials)

  private def ensureIdentityIsConfirmed(userIdentity: UserIdentity): StepIO[Boolean] =
    Sync[IO].pure(userIdentity.confirmed).ensure(identity, LoginResult.IdentityNotConfirmed)

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
    loginResolveService.resolve(UserLogin(requestContext, user, orgUsers, prevAuthContext, userIdentity, Some(CredentialMethod.Password))).seal

}

object LoginByPasswordService {
  final case class LoginRequest(
    email: Option[MailAddress],
    phoneNumber: Option[PhoneNumber],
    password: PasswordPlain)

  sealed trait LoginResult
  object LoginResult {
    final case class Ok(data: LoginResolution) extends LoginResult
    final case object IdentityNotConfirmed     extends LoginResult
    final case object UserOrPasswordNotFound   extends LoginResult
    final case object InvalidRequest           extends LoginResult
    final case object InvalidCredentials       extends LoginResult
  }

}
