package pl.iterators.epoca.saml

import cats.effect.unsafe.IORuntime
import com.softwaremill.macwire.wire
import com.typesafe.config.Config
import org.apache.pekko.http.scaladsl.server.Route
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.auth.services.LoginResolveService
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.saml.repositories._
import pl.iterators.epoca.saml.routers.SamlRouter
import pl.iterators.epoca.saml.services._
import pl.iterators.epoca.user.repositories.{EmailIdentityRepository, UserRepository}
import pl.iterators.epoca.user.services.{UserCreateService, UserWithIdentitiesFindService}
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.http.{AuthRouteProvider, RouteProvider}
import pl.iterators.epoca.utils.log.TracingContext

import scala.concurrent.ExecutionContext

trait SamlModule extends AuthRouteProvider with RouteProvider {
  implicit val executor: ExecutionContext
  implicit val runtime: IORuntime

  val config: Config
  val transactor: PostgresIOTransactor

  lazy val globalSamlConfig: SamlUtilsService.Config = SamlUtilsService.Config.apply(config)

  val emailIdentityRepository: EmailIdentityRepository
  val userRepository: UserRepository
  val organizationRepository: OrganizationRepository
  val organizationUserRepository: OrganizationUserRepository

  val userWithIdentitiesFindService: UserWithIdentitiesFindService
  val userCreateService: UserCreateService
  val loginResolveService: LoginResolveService

  lazy val samlConfigRepository: SamlConfigRepository = wire[SamlConfigRepositoryImpl]
  lazy val samlIdentityBindingRepository: pl.iterators.epoca.saml.repositories.SamlIdentityBindingRepository =
    new pl.iterators.epoca.saml.repositories.SamlIdentityBindingRepositoryImpl
  lazy val samlRequestRepository: SamlRequestRepository = wire[SamlRequestRepositoryImpl]

  val userIdentityRepository: pl.iterators.epoca.user.repositories.UserIdentityRepository
  val userCreationActions: pl.iterators.epoca.user.services.UserCreationActions
  val accountAuthenticationValidator: pl.iterators.epoca.auth.services.AccountAuthenticationValidator
  val invitationOnboardingRepository: pl.iterators.epoca.invitation.repositories.InvitationOnboardingRepository
  val invitationAccessRepository: pl.iterators.epoca.invitation.repositories.InvitationAccessRepository
  private lazy val samlClock: java.time.Clock = java.time.Clock.systemUTC()
  private lazy val samlSecrets: pl.iterators.epoca.invitation.services.InvitationSecretService =
    new pl.iterators.epoca.invitation.services.InvitationSecretService
  lazy val samlBrowserPolicy: Option[SamlBrowserPolicy] = SamlBrowserPolicy.fromConfig(config, samlSecrets)

  def samlInvitationAccess: Option[pl.iterators.epoca.invitation.services.InvitationAccessService] = None
  lazy val samlInvitationService: SamlInvitationService = new SamlInvitationService(
    samlRequestRepository,
    samlConfigRepository,
    samlIdentityBindingRepository,
    invitationOnboardingRepository,
    invitationAccessRepository,
    emailIdentityRepository,
    organizationRepository,
    userRepository,
    userIdentityRepository,
    organizationUserRepository,
    accountAuthenticationValidator,
    samlSecrets,
    samlClock,
    samlInvitationAccess
  )

  lazy val samlUtilsService: SamlUtilsService = wire[SamlUtilsService]
  lazy val samlAuthnService: SamlAuthnService = new SamlAuthnService(
    transactor,
    organizationRepository,
    samlConfigRepository,
    samlUtilsService,
    samlInvitationService,
    samlSecrets,
    samlClock,
    samlBrowserPolicy
  )
  lazy val samlAuthorizeService: SamlAuthorizeService = new SamlAuthorizeService(
    transactor,
    organizationRepository,
    samlConfigRepository,
    samlRequestRepository,
    samlUtilsService,
    samlInvitationService,
    samlClock
  )
  val avatarSyncService: pl.iterators.epoca.user.services.AvatarSyncService
  val userAvatarSourceRepository: pl.iterators.epoca.user.repositories.UserAvatarSourceRepository
  private lazy val workspacePhotoConfig: WorkspaceDirectoryPhotoProvider.Config = {
    val value = WorkspaceDirectoryPhotoProvider.Config.fromConfig(config)
    require(!value.enabled || config.getBoolean("user.avatarSync.enabled"), "Workspace photos require user.avatarSync.enabled")
    value
  }
  lazy val workspaceDirectoryPhotoProvider: WorkspaceDirectoryPhotoProvider =
    new WorkspaceDirectoryPhotoProvider(workspacePhotoConfig, new GoogleWorkspacePhotoTransport(workspacePhotoConfig.maxBytes))
  lazy val workspaceAvatarSyncService: WorkspaceAvatarSyncService =
    new WorkspaceAvatarSyncService(workspaceDirectoryPhotoProvider, avatarSyncService, userAvatarSourceRepository, transactor)
  lazy val samlLoginService: SamlLoginService =
    new SamlLoginService(transactor, samlInvitationService, loginResolveService, samlInvitationAccess, Some(workspaceAvatarSyncService))
  lazy val samlConfigXmlService: SamlConfigXmlService       = wire[SamlConfigXmlService]
  lazy val samlConfigUpsertService: SamlConfigUpsertService = wire[SamlConfigUpsertService]
  lazy val samlConfigGetService: SamlConfigGetService       = wire[SamlConfigGetService]

  lazy val samlRouter: SamlRouter = wire[SamlRouter]

  abstract override def route(auth: AuthContext)(implicit tracingContext: TracingContext): Route = super.route(auth) ~ samlRouter.authRoutes(auth)

  abstract override def route(implicit tracingContext: TracingContext): Route = super.route ~ samlRouter.routes(globalSamlConfig)
}
