package pl.iterators.epoca.auth

import cats.effect.unsafe.IORuntime
import com.softwaremill.macwire._
import com.typesafe.config.Config
import org.apache.pekko.http.scaladsl.server.Route
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.auth.jobs.LoginTokenPurgeExpiredJob
import pl.iterators.epoca.auth.repositories._
import pl.iterators.epoca.auth.routers._
import pl.iterators.epoca.auth.services._
import pl.iterators.epoca.invitation.repositories.InvitationAccessRepositoryImpl
import pl.iterators.epoca.mail.emails.UserMagicLoginLink
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.sms.smses.UserTokenSms
import pl.iterators.epoca.user.repositories.{UserIdentityRepository, UserRepository}
import pl.iterators.epoca.user.services.{AvatarSyncService, UserCreateService, UserWithIdentitiesFindService}
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.http.{AuthRouteProvider, RouteProvider}
import pl.iterators.epoca.utils.job.{SchedulableJob, SchedulableJobConfig, SchedulableJobProvider}
import pl.iterators.epoca.utils.log.TracingContext

import scala.concurrent.ExecutionContext

trait AuthModule extends AuthRouteProvider with RouteProvider with SchedulableJobProvider {

  implicit val runtime: IORuntime
  implicit val executor: ExecutionContext

  val transactor: PostgresIOTransactor

  val config: Config

  val userRepository: UserRepository
  val userIdentityRepository: UserIdentityRepository
  val organizationRepository: OrganizationRepository
  val organizationUserRepository: OrganizationUserRepository
  val userWithIdentitiesFindService: UserWithIdentitiesFindService
  val userCreateService: UserCreateService
  val avatarSyncService: AvatarSyncService

  val mailUserMagicLoginLink: UserMagicLoginLink
  val userTokenSms: UserTokenSms

  private lazy val jwtServiceConfig: JwtService.Config = JwtService.Config(config)
  lazy val jwtService: JwtService                      = wire[JwtService]

  lazy val authService: AuthenticateService = wire[JwtAuthenticateService]

  lazy val userAuthenticator: UserAuthenticator = wire[AuthServiceUserAuthenticator]

  protected lazy val refreshTokenRepository: RefreshTokenRepository = wire[RefreshTokenRepositoryImpl]
  protected lazy val userPasswordRepository: UserPasswordRepository = wire[UserPasswordRepositoryImpl]
  lazy val userLoginTokenRepository: UserLoginTokenRepository       = wire[UserLoginTokenRepositoryImpl]

  protected lazy val passwordService: PasswordService = wire[PasswordService]

  private lazy val loginTokenHashServiceConfig                  = LoginTokenHashService.Config(config)
  private lazy val loginTokenHashService: LoginTokenHashService = wire[LoginTokenHashService]

  private lazy val timeBasedOneTimePasswordServiceConfig: TimeBasedOneTimePasswordService.Config =
    TimeBasedOneTimePasswordService.Config(config)
  lazy val timeBasedOneTimePasswordService: TimeBasedOneTimePasswordService = wire[TimeBasedOneTimePasswordService]

  private lazy val passwordValidateServiceConfig: PasswordValidateService.Config = PasswordValidateService.Config(config)
  lazy val passwordValidateService: PasswordValidateService                      = wire[PasswordValidateService]

  private lazy val accountAuthenticationRepository: AccountAuthenticationRepository = wire[AccountAuthenticationRepositoryImpl]
  lazy val accountAuthenticationValidator: AccountAuthenticationValidator           = wire[AccountAuthenticationValidator]
  lazy val samlSessionService: SamlSessionService                                   = wire[SamlSessionService]

  lazy val passwordSetService: PasswordSetService                       = wire[PasswordSetService]
  private lazy val cryptoVerificationService: CryptoVerificationService = wire[CryptoVerificationService]

  private lazy val loginResolveServiceConfig: LoginResolveService.Config = LoginResolveService.Config(config)
  lazy val loginResolveService: LoginResolveService                      = wire[LoginResolveService]

  private lazy val loginTokenGenerateServiceConfig: LoginTokenGenerateService.Config = LoginTokenGenerateService.Config(config)
  lazy val loginTokenGenerateService: LoginTokenGenerateService                      = wire[LoginTokenGenerateService]

  private lazy val loginTokenGetServiceConfig: LoginTokenGetService.Config = LoginTokenGetService.Config(config)
  lazy val loginTokenGetService: LoginTokenGetService                      = wire[LoginTokenGetService]

  private lazy val loginTokenPurgeExpiredService: LoginTokenPurgeExpiredService = wire[LoginTokenPurgeExpiredService]

  protected lazy val loginByPasswordService: LoginByPasswordService = wire[LoginByPasswordService]

  private lazy val firebaseAuthProviderConfig: FirebaseAuthProvider.Config = FirebaseAuthProvider.Config(config)
  private lazy val firebaseAuthProvider: FirebaseAuthProvider              = wire[FirebaseAuthProvider]
  protected lazy val loginByFirebaseService: LoginByFirebaseService        = wire[LoginByFirebaseService]

  protected lazy val loginByRefreshTokenService: LoginByRefreshTokenService = wire[LoginByRefreshTokenService]

  protected lazy val loginByTokenService: LoginByTokenService = wire[LoginByTokenService]

  protected lazy val loginByCryptoVerificationService: LoginByCryptoVerificationService = wire[LoginByCryptoVerificationService]

  private lazy val totpRateLimitService: TotpRateLimitService =
    new TotpRateLimitService(transactor, new InvitationAccessRepositoryImpl, java.time.Clock.systemUTC())

  protected lazy val loginByTimeBasedOneTimePasswordService: LoginByTimeBasedOneTimePasswordService = wire[LoginByTimeBasedOneTimePasswordService]

  private lazy val timeBasedOneTimePasswordSecretGetServiceConfig: TimeBasedOneTimePasswordSecretGetService.Config =
    TimeBasedOneTimePasswordSecretGetService.Config(config)
  protected lazy val timeBasedOneTimePasswordSecretGetService: TimeBasedOneTimePasswordSecretGetService =
    wire[TimeBasedOneTimePasswordSecretGetService]

  protected lazy val refreshTokenDeleteService: RefreshTokenDeleteService                 = wire[RefreshTokenDeleteService]
  protected lazy val refreshTokenListService: RefreshTokenListService                     = wire[RefreshTokenListService]
  protected lazy val refreshTokenValueGeneratorService: RefreshTokenValueGeneratorService = wire[RefreshTokenValueGeneratorService]

  protected lazy val adminImpersonateLoginService: AdminImpersonateLoginService = wire[AdminImpersonateLoginService]

  private lazy val authRouter: AuthRouter = wire[AuthRouter]

  abstract override def route(implicit tracingContext: TracingContext): Route = super.route ~ authRouter.routes
  abstract override def route(authContext: AuthContext)(implicit tracingContext: TracingContext): Route =
    super.route(authContext) ~ authRouter.routes(authContext)

  private lazy val loginTokenPurgeExpiredJob: LoginTokenPurgeExpiredJob = {
    val schedulableJobConfig = SchedulableJobConfig(config.getConfig("user.loginToken.purgeExpiredSchedulableJob"))

    wire[LoginTokenPurgeExpiredJob]
  }

  abstract override def schedulableJobs: List[SchedulableJob] = loginTokenPurgeExpiredJob :: super.schedulableJobs

}
