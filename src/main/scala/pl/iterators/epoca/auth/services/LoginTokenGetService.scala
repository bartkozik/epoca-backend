package pl.iterators.epoca.auth.services

import cats.effect.IO
import cats.effect.kernel.Sync
import cats.effect.unsafe.IORuntime
import com.typesafe.config.{Config => TsConfig}
import pl.iterators.epoca.auth.domain.AuthDomain._
import pl.iterators.epoca.auth.domain.UserLoginToken
import pl.iterators.epoca.auth.repositories.UserLoginTokenRepository
import pl.iterators.epoca.auth.services.LoginTokenGenerateService.UserLoginTokenWithLoginTokenPlain
import pl.iterators.epoca.auth.services.LoginTokenGetService.{LoginTokenGetRequest, LoginTokenGetResult}
import pl.iterators.epoca.mail.domain.MailDomain.MailAddress
import pl.iterators.epoca.mail.emails.UserMagicLoginLink
import pl.iterators.epoca.sms.domain.SmsDomain.PhoneNumber
import pl.iterators.epoca.sms.smses.UserTokenSms
import pl.iterators.epoca.user.domain.UserDomain.IdentityType
import pl.iterators.epoca.user.domain.{User, UserIdentity, UserWithIdentities}
import pl.iterators.epoca.user.repositories.UserRepository
import pl.iterators.epoca.user.repositories.UserRepository.UserFilters
import pl.iterators.epoca.user.services.UserWithIdentitiesFindService
import pl.iterators.epoca.user.services.UserWithIdentitiesFindService.UserWithIdentitiesFindResult
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.service.SealedMonadServiceIODBIO
import pl.iterators.sealedmonad.syntax._

import java.net.URLEncoder

class LoginTokenGetService(
  config: LoginTokenGetService.Config,
  transactor: PostgresIOTransactor,
  loginTokenGenerateService: LoginTokenGenerateService,
  userWithIdentitiesFindService: UserWithIdentitiesFindService,
  userRepository: UserRepository,
  userLoginTokenRepository: UserLoginTokenRepository,
  mailUserMagicLoginLink: UserMagicLoginLink,
  userTokenSms: UserTokenSms
)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIODBIO[LoginTokenGetResult](ioRuntime)
    with LoggingSupport {

  def getToken(request: LoginTokenGetRequest): IO[LoginTokenGetResult] =
    (for {
      _                  <- validateRequest(request)
      user               <- findUser(request)
      userWithIdentities <- findUserWithIdentities(user)
      identity           <- findIdentity(userWithIdentities, request)
      tokenPair          <- generateToken(identity)
      _                  <- insertToken(tokenPair.token)
      _                  <- send(request, tokenPair.loginTokenPlain)
    } yield LoginTokenGetResult.Ok).run

  private def validateRequest(request: LoginTokenGetRequest): StepIO[Boolean] =
    Sync[IO]
      .pure(
        // Phone authentication stays disabled until real delivery and durable abuse controls are implemented.
        request.email.isDefined && request.phoneNumber.isEmpty
      )
      .ensure(identity, LoginTokenGetResult.InvalidRequest)

  private def findUser(request: LoginTokenGetRequest): StepIO[User] =
    transactor
      .execute(
        userRepository
          .find(UserFilters(email = request.email, phoneNumber = request.phoneNumber))
      )
      .valueOr(LoginTokenGetResult.UserNotFound)

  private def findUserWithIdentities(user: User): StepIO[UserWithIdentities] = userWithIdentitiesFindService.find(user.id).attemptF {
    case UserWithIdentitiesFindResult.Ok(user) => Sync[IO].pure(Right(user))
    case UserWithIdentitiesFindResult.UserDoNotExist =>
      Logger[IO].warning(s"User ${user.id} does not exist") *> Sync[IO].pure(Left(LoginTokenGetResult.UserNotFound))
  }

  private def findIdentity(user: UserWithIdentities, request: LoginTokenGetRequest): StepIO[UserIdentity] =
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
      .valueOr(LoginTokenGetResult.UserNotFound)

  private def generateToken(identity: UserIdentity): StepIO[UserLoginTokenWithLoginTokenPlain] =
    loginTokenGenerateService.generateToken(identity, expiring = true).seal

  private def insertToken(token: UserLoginToken): StepIO[UserLoginToken] =
    transactor.execute(userLoginTokenRepository.insert(token)).seal

  private def send(request: LoginTokenGetRequest, loginTokenPlain: LoginTokenPlain): StepIO[Unit] =
    ((request.email, request.phoneNumber) match {
      case (Some(email), None) =>
        val path = config.appFrontEndTokenLoginRoute
          .replace("{token}", URLEncoder.encode(loginTokenPlain, "UTF-8"))
          .replace("{email}", URLEncoder.encode(email, "UTF-8"))
        val link = s"${config.appFrontEndUrl}${path}${request.additionalUrlParams.getOrElse("")}"
        mailUserMagicLoginLink.send(UserMagicLoginLink.Event(email, link))
      case (None, Some(phoneNumber)) =>
        userTokenSms.send(UserTokenSms.Event(phoneNumber, loginTokenPlain)).map(_ => ())
      case _ => IO.pure(())
    }).seal

}

object LoginTokenGetService {
  case class Config(appFrontEndUrl: String, appFrontEndTokenLoginRoute: String)

  object Config {
    def apply(tsConfig: TsConfig): Config =
      Config(appFrontEndUrl = tsConfig.getString("appFrontEnd.url"), appFrontEndTokenLoginRoute = tsConfig.getString("appFrontEnd.tokenLoginRoute"))
  }

  sealed trait LoginTokenGetResult

  final case class LoginTokenGetRequest(
    email: Option[MailAddress],
    phoneNumber: Option[PhoneNumber],
    additionalUrlParams: Option[String])

  object LoginTokenGetResult {
    final case object Ok             extends LoginTokenGetResult
    final case object UserNotFound   extends LoginTokenGetResult
    final case object InvalidRequest extends LoginTokenGetResult
  }

}
