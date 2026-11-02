package pl.iterators.epoca.user

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import com.softwaremill.macwire.wire
import com.typesafe.config.Config
import org.apache.pekko.http.scaladsl.server.Route
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.auth.repositories.{UserLoginTokenRepository, UserPasswordRepository}
import pl.iterators.epoca.auth.services.{
  AccountAuthenticationValidator,
  LoginTokenGenerateService,
  LoginTokenGetService,
  PasswordService,
  PasswordValidateService,
  TimeBasedOneTimePasswordService
}
import pl.iterators.epoca.mail.emails.UserIdentityConfirmationCodeMail
import pl.iterators.epoca.sms.services.SmsService
import pl.iterators.epoca.storage.FileStorage
import pl.iterators.epoca.user.repositories._
import pl.iterators.epoca.user.routers.{UserIdentityRouter, UserRouter}
import pl.iterators.epoca.user.services._
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.http.{AuthRouteProvider, RouteProvider}
import pl.iterators.epoca.utils.log.TracingContext
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.WebSocketStreamBackend

import scala.concurrent.ExecutionContext

trait UserModule extends AuthRouteProvider with RouteProvider {
  implicit val executor: ExecutionContext
  implicit val runtime: IORuntime

  val config: Config
  val transactor: PostgresIOTransactor

  val accountAuthenticationValidator: AccountAuthenticationValidator
  val loginTokenGetService: LoginTokenGetService
  protected val passwordService: PasswordService
  protected val userPasswordRepository: UserPasswordRepository
  val passwordValidateService: PasswordValidateService
  val timeBasedOneTimePasswordService: TimeBasedOneTimePasswordService
  val userLoginTokenRepository: UserLoginTokenRepository
  val loginTokenGenerateService: LoginTokenGenerateService

  val smsService: SmsService
  val fileStorage: FileStorage
  val httpClient: WebSocketStreamBackend[IO, Fs2Streams[IO]]
  val mailUserIdentityConfirmationCode: UserIdentityConfirmationCodeMail

  private lazy val userCreateServiceConfig: UserCreateService.Config = UserCreateService.Config(config)

  lazy val emailIdentityRepository: EmailIdentityRepository = wire[EmailIdentityRepositoryImpl]
  lazy val userCreationActions: UserCreationActions         = wire[UserCreationActions]

  lazy val userRepository: UserRepository                         = wire[UserRepositoryImpl]
  lazy val serviceAccountRepository: ServiceAccountRepository     = wire[ServiceAccountRepositoryImpl]
  lazy val userIdentityRepository: UserIdentityRepository         = wire[UserIdentityRepositoryImpl]
  lazy val userAvatarSourceRepository: UserAvatarSourceRepository = wire[UserAvatarSourceRepositoryImpl]

  lazy val userFindService: UserFindService                             = wire[UserFindService]
  lazy val userWithIdentitiesFindService: UserWithIdentitiesFindService = wire[UserWithIdentitiesFindService]
  lazy val userCreateService: UserCreateService                         = wire[UserCreateService]
  private lazy val avatarSyncServiceConfig: AvatarSyncService.Config    = AvatarSyncService.Config(config)
  lazy val avatarSyncService: AvatarSyncService                         = wire[AvatarSyncService]
  lazy val userDeleteService: UserDeleteService                         = wire[UserDeleteService]
  lazy val userListService: UserListService                             = wire[UserListService]
  lazy val userUpdateService: UserUpdateService                         = wire[UserUpdateService]

  lazy val userIdentityCreateService: UserIdentityCreateService   = wire[UserIdentityCreateService]
  lazy val userIdentityDeleteService: UserIdentityDeleteService   = wire[UserIdentityDeleteService]
  lazy val userIdentitySetMainService: UserIdentitySetMainService = wire[UserIdentitySetMainService]
  lazy val userIdentityConfirmService: UserIdentityConfirmService = wire[UserIdentityConfirmService]
  lazy val userIdentityListService: UserIdentityListService       = wire[UserIdentityListService]

  private lazy val userRouter: UserRouter                 = wire[UserRouter]
  private lazy val userIdentityRouter: UserIdentityRouter = wire[UserIdentityRouter]

  abstract override def route(implicit tracingContext: TracingContext): Route = super.route ~ userRouter.routes

  abstract override def route(auth: AuthContext)(implicit tracingContext: TracingContext): Route =
    super.route(auth) ~ userRouter.routes(auth) ~ userIdentityRouter.routes(auth)
}
