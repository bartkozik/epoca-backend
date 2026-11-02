package pl.iterators.epoca.oauth

import com.softwaremill.macwire.wire
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.oauth.policy.OAuthOperationRegistry
import pl.iterators.epoca.oauth.routers.{OAuthAuthenticator, OAuthClientRouter, OAuthPrincipalAuthenticator, OAuthTokenRouter}
import pl.iterators.epoca.oauth.services.{
  OAuthClientManagementService,
  OAuthGrantabilityService,
  OAuthJwtService,
  OAuthSecretService,
  OAuthTokenIssuer,
  OAuthTokenService,
  TrustedClientIpService
}
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.project.repositories.ProjectRepository
import pl.iterators.epoca.user.repositories.{ServiceAccountRepository, UserRepository}
import pl.iterators.epoca.utils.http.{AuthRouteProvider, RouteProvider}
import pl.iterators.epoca.utils.log.TracingContext

trait OAuthModule extends OAuthPersistenceModule with AuthRouteProvider with RouteProvider {
  val userRepository: UserRepository
  val serviceAccountRepository: ServiceAccountRepository
  val organizationRepository: OrganizationRepository
  val organizationUserRepository: OrganizationUserRepository
  val projectRepository: ProjectRepository

  lazy val oauthSecretService: OAuthSecretService                     = new OAuthSecretService(oauthConfig.dummyBcryptHash)
  lazy val oauthGrantabilityService: OAuthGrantabilityService         = wire[OAuthGrantabilityService]
  lazy val oauthClientManagementService: OAuthClientManagementService = wire[OAuthClientManagementService]
  private lazy val oauthClientRouter: OAuthClientRouter               = wire[OAuthClientRouter]
  protected def oauthManagementEnabled: Boolean                       = oauthConfig.enabled
  lazy val oauthJwtService: OAuthJwtService =
    new OAuthJwtService(
      oauthConfig.issuer,
      oauthConfig.audience,
      oauthConfig.signingKeyRing.getOrElse(throw new IllegalStateException("Enabled OAuth requires a validated signing key ring"))
    )
  lazy val oauthTokenIssuer: OAuthTokenIssuer =
    new OAuthTokenService(
      transactor,
      oauthAuthoritativeTime,
      oauthClientRepository,
      oauthAuditRepository,
      oauthRateLimitRepository,
      oauthSecretService,
      oauthJwtService,
      oauthConfig.issuer,
      oauthConfig.audience
    )
  lazy val trustedClientIpService: TrustedClientIpService = new TrustedClientIpService(oauthConfig.trustedProxyCidrs)
  private lazy val oauthTokenRouter: OAuthTokenRouter     = new OAuthTokenRouter(oauthTokenIssuer, trustedClientIpService)
  protected def oauthTokenEnabled: Boolean                = oauthConfig.enabled
  lazy val oauthOperationRegistry: OAuthOperationRegistry = OAuthOperationRegistry.default
  lazy val oauthPrincipalAuthenticator: OAuthPrincipalAuthenticator =
    if (oauthTokenEnabled)
      new OAuthAuthenticator(transactor, oauthAuthoritativeTime, oauthJwtService, oauthClientRepository, serviceAccountRepository)
    else OAuthPrincipalAuthenticator.disabled

  abstract override def route(auth: AuthContext)(implicit tracingContext: TracingContext): Route =
    super.route(auth) ~ Option.when(oauthManagementEnabled)(oauthClientRouter.authRoutes(auth)).getOrElse(oauthClientRouter.unavailableRoutes)

  abstract override def route(implicit tracingContext: TracingContext): Route =
    super.route ~ Option.when(oauthTokenEnabled)(oauthTokenRouter.routes).getOrElse(path("oauth" / "token")(complete(StatusCodes.NotFound)))
}
