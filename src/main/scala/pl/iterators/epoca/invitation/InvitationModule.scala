package pl.iterators.epoca.invitation

import cats.effect.unsafe.IORuntime
import com.softwaremill.macwire.wire
import com.typesafe.config.Config
import org.apache.pekko.http.scaladsl.server.Route
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.invitation.repositories.{
  InvitationAccessRepository,
  InvitationAccessRepositoryImpl,
  InvitationOnboardingRepository,
  InvitationOnboardingRepositoryImpl,
  InvitationRepository,
  InvitationRepositoryImpl
}
import pl.iterators.epoca.invitation.routers.{InvitationAccessRouter, InvitationManagementRouter, InvitationRouter}
import pl.iterators.epoca.invitation.services._
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.organization.services.OrganizationUserMembershipService
import pl.iterators.epoca.organizationdashboard.services.OrganizationUserListService
import pl.iterators.epoca.user.repositories.UserRepository
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.http.{AuthRouteProvider, RouteProvider}
import pl.iterators.epoca.utils.log.TracingContext

import scala.concurrent.ExecutionContext

trait InvitationModule extends AuthRouteProvider with RouteProvider {
  implicit val executor: ExecutionContext
  implicit val runtime: IORuntime
  val config: Config
  val transactor: PostgresIOTransactor
  val invitationSupervisor: cats.effect.std.Supervisor[cats.effect.IO]
  val invitationMailSender: pl.iterators.epoca.mail.services.InvitationMailSender
  lazy val invitationDeliveryService: InvitationDeliveryService = new InvitationDeliveryService(
    transactor,
    new InvitationOnboardingRepositoryImpl,
    organizationRepository,
    invitationMailSender,
    invitationSupervisor,
    java.time.Clock.systemUTC()
  )

  val organizationUserListService: OrganizationUserListService
  val organizationUserMembershipService: OrganizationUserMembershipService
  val organizationUserRepository: OrganizationUserRepository
  val organizationRepository: OrganizationRepository
  val userRepository: UserRepository

  lazy val invitationConfig: InvitationConfig           = InvitationConfig.fromConfig(config)
  lazy val invitationAccessGuard: InvitationAccessGuard = new InvitationAccessGuard
  val trustedClientIpService: pl.iterators.epoca.oauth.services.TrustedClientIpService
  val samlInvitationService: pl.iterators.epoca.saml.services.SamlInvitationService
  val samlAuthnService: pl.iterators.epoca.saml.services.SamlAuthnService
  val samlBrowserPolicy: Option[pl.iterators.epoca.saml.services.SamlBrowserPolicy]
  private lazy val invitationLimits: Option[InvitationRateLimitService] =
    invitationConfig.rateKey.map(key => new InvitationRateLimitService(transactor, invitationAccessRepository, key, invitationClock))
  lazy val configuredInvitationAccess: Option[InvitationAccessService] = invitationConfig.browser.map(browser =>
    new InvitationAccessService(
      transactor,
      invitationOnboardingRepository,
      invitationAccessRepository,
      emailIdentityRepository,
      new InvitationSecretService,
      browser,
      invitationLimits.get,
      invitationClock
    )
  )
  lazy val invitationManagementRouter: InvitationManagementRouter =
    new InvitationManagementRouter(invitationManagementService, invitationDeliveryService, invitationConfig.enabled)
  lazy val invitationAccessRouter: Option[InvitationAccessRouter] = configuredInvitationAccess.map(access =>
    new InvitationAccessRouter(
      access,
      invitationActivationService(access, invitationConfig.browser.get, invitationLimits.get),
      invitationConfig.browser.get,
      samlInvitationService,
      samlAuthnService,
      samlBrowserPolicy,
      transactor,
      trustedClientIpService
    )
  )

  private lazy val invitationClock: java.time.Clock                       = java.time.Clock.systemUTC()
  lazy val invitationOnboardingRepository: InvitationOnboardingRepository = new InvitationOnboardingRepositoryImpl
  lazy val invitationAccessRepository: InvitationAccessRepository         = new InvitationAccessRepositoryImpl
  val emailIdentityRepository: pl.iterators.epoca.user.repositories.EmailIdentityRepository
  val userIdentityRepository: pl.iterators.epoca.user.repositories.UserIdentityRepository
  val userCreationActions: pl.iterators.epoca.user.services.UserCreationActions
  val accountAuthenticationValidator: pl.iterators.epoca.auth.services.AccountAuthenticationValidator
  protected val passwordService: pl.iterators.epoca.auth.services.PasswordService
  val passwordValidateService: pl.iterators.epoca.auth.services.PasswordValidateService
  val loginResolveService: pl.iterators.epoca.auth.services.LoginResolveService
  val samlRequestRepository: pl.iterators.epoca.saml.repositories.SamlRequestRepository
  val samlIdentityBindingRepository: pl.iterators.epoca.saml.repositories.SamlIdentityBindingRepository
  lazy val invitationActivationActions: InvitationActivationActions = wire[InvitationActivationActions]

  /** Task10 supplies validated public-origin/rate configuration; no insecure configuration defaults here. */
  def invitationActivationService(
    access: InvitationAccessService,
    browser: InvitationBrowserSecurity,
    limits: InvitationRateLimitService
  ): InvitationActivationService =
    new InvitationActivationService(
      transactor,
      access,
      invitationActivationActions,
      passwordService,
      passwordValidateService,
      loginResolveService,
      limits,
      browser,
      invitationClock
    )

  lazy val invitationAcceptService: InvitationAcceptService = wire[InvitationAcceptService]
  lazy val invitationListService: InvitationListService     = wire[InvitationListService]

  lazy val invitationRepository: InvitationRepository = wire[InvitationRepositoryImpl]

  lazy val invitationManagementService: InvitationManagementService = new InvitationManagementService(
    transactor,
    new InvitationOnboardingRepositoryImpl,
    invitationRepository,
    new pl.iterators.epoca.user.repositories.EmailIdentityRepositoryImpl,
    new InvitationAccessGuard,
    new InvitationSecretService,
    java.time.Clock.systemUTC()
  )

  lazy val organizationInvitationCreateService: OrganizationInvitationCreateService = wire[OrganizationInvitationCreateService]
  lazy val organizationInvitationListService: OrganizationInvitationListService     = wire[OrganizationInvitationListService]
  lazy val organizationInvitationDeleteService: OrganizationInvitationDeleteService = wire[OrganizationInvitationDeleteService]

  lazy val invitationRouter: InvitationRouter = wire[InvitationRouter]

  abstract override def route(auth: AuthContext)(implicit tracingContext: TracingContext): Route =
    super.route(auth) ~ invitationManagementRouter.authRoutes(auth) ~ invitationRouter.authRoutes(auth) ~ invitationAccessRouter.fold[Route](
      org.apache.pekko.http.scaladsl.server.Directives.reject
    )(_.routes(Some(auth)))

  abstract override def route(implicit tracingContext: TracingContext): Route =
    super.route ~ invitationAccessRouter.fold[Route](org.apache.pekko.http.scaladsl.server.Directives.reject)(_.routes(None))
}
