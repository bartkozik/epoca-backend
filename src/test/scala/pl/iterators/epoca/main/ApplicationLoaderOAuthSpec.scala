package pl.iterators.epoca.main

import org.apache.pekko.http.scaladsl.model.headers.{Authorization, HttpChallenge, OAuth2BearerToken}
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import org.apache.pekko.http.scaladsl.server.directives.{AuthenticationResult, SecurityDirectives}
import org.apache.pekko.http.scaladsl.server.{PathMatcher, RequestContext, Route, RouteResult}
import pl.iterators.epoca.auth.domain.{AccountAuthentication, AuthContext, AuthContextAuthedBy}
import pl.iterators.epoca.auth.routers.UserAuthenticator
import pl.iterators.epoca.auth.services.SamlSessionService
import pl.iterators.epoca.oauth.domain.OAuthAuthContext
import pl.iterators.epoca.oauth.domain.OAuthDomain.{OAuthClientId, OAuthProjectAccess, OAuthScope}
import pl.iterators.epoca.oauth.routers.OAuthPrincipalAuthenticator
import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationId, OrganizationSlug, OrganizationUserRole}
import pl.iterators.epoca.user.domain.UserDomain.{IdentityType, UserId, UserIsSuperAdmin}
import pl.iterators.epoca.utils.log.TracingContext
import pl.iterators.epoca.utils.{AuthContextProvider, RouteSpec}

import java.time.Instant
import java.util.UUID
import scala.concurrent.Future
import scala.jdk.CollectionConverters._

class ApplicationLoaderOAuthSpec extends RouteSpec {
  private val humanAuth = AuthContextProvider.user
  private val oauthAuth = OAuthAuthContext(
    UserId(UUID.randomUUID()),
    OAuthClientId(UUID.randomUUID()),
    OrganizationId(UUID.randomUUID()),
    Set(OAuthScope.OrganizationRead),
    OAuthProjectAccess.AllProjects,
    UUID.randomUUID(),
    policyVersion = 3L
  )

  "principal dispatch" should {
    "enforce bound SAML scope in the actual human dispatcher" in new PrincipalCase {
      val organization = OrganizationId(UUID.randomUUID())
      val foreign      = OrganizationId(UUID.randomUUID())
      val saml = humanAuth.copy(
        isSuperAdmin = UserIsSuperAdmin(true),
        authedBy = Set(AuthContextAuthedBy(IdentityType.Saml, None)),
        roles = Seq(
          AuthContext.OrganizationRoles(organization, Seq(OrganizationUserRole.Owner)),
          AuthContext.OrganizationRoles(foreign, Seq(OrganizationUserRole.Owner))
        ),
        accountAuthentication = Some(AccountAuthentication(Instant.now(), AccountAuthentication.BoundSaml(organization, 1L, "issuer", "subject")))
      )
      override lazy val userAuthenticator: UserAuthenticator   = _ => Future.successful(AuthenticationResult.success(saml))
      override lazy val samlSessionService: SamlSessionService = mock[SamlSessionService]
      override def route(auth: AuthContext)(implicit tracingContext: TracingContext): RequestContext => Future[RouteResult] =
        complete(s"${auth.isAdmin}:${auth.roles.map(_.organizationId).mkString(",")}")
      when(samlSessionService.currentOrganization(saml)).thenReturn(io(Some(OrganizationSlug("acme"))))
      Seq(Get("/api/v1/organizations/foreign/projects"), Post("/api/v1/users/identities"), Post("/api/v1/auth/admin")).foreach { request =>
        request ~> Authorization(OAuth2BearerToken("bound-saml")) ~> allRoutes ~> check(status shouldEqual StatusCodes.Forbidden)
      }
      Get("/api/v1/organizations/acme/projects") ~> Authorization(OAuth2BearerToken("bound-saml")) ~> allRoutes ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[String] shouldEqual s"false:$organization"
      }
      when(samlSessionService.currentOrganization(saml)).thenReturn(io(None))
      Get("/api/v1/organizations/acme/projects") ~> Authorization(OAuth2BearerToken("revoked-saml")) ~> allRoutes ~> check(
        status shouldEqual StatusCodes.Forbidden
      )
    }

    "deny legacy SAML sessions before privileged human dispatch" in new PrincipalCase {
      private val legacySaml = humanAuth.copy(
        isSuperAdmin = pl.iterators.epoca.user.domain.UserDomain.UserIsSuperAdmin(true),
        authedBy = Set(pl.iterators.epoca.auth.domain.AuthContextAuthedBy(pl.iterators.epoca.user.domain.UserDomain.IdentityType.Saml, None)),
        accountAuthentication = None
      )
      override lazy val userAuthenticator: UserAuthenticator = _ => Future.successful(AuthenticationResult.success(legacySaml))
      Get("/api/v1/organizations/acme/oauth-clients") ~> Authorization(OAuth2BearerToken("legacy-saml")) ~> allRoutes ~> check {
        status shouldEqual StatusCodes.Forbidden
      }
    }

    "deny OAuth principals on every invitation cookie action and new management path" in new OAuthPrincipalCase(oauthAuth) {
      Seq(
        Get("/api/v1/invitation-access"),
        Post("/api/v1/invitation-access/exchange"),
        Post("/api/v1/invitation-access/accept"),
        Post("/api/v1/invitation-access/password"),
        Post("/api/v1/invitation-access/saml"),
        Delete("/api/v1/invitation-access"),
        Patch("/api/v1/organizations/acme/invitations/00000000-0000-0000-0000-000000000001"),
        Post("/api/v1/organizations/acme/invitations/00000000-0000-0000-0000-000000000001/resend")
      ).foreach { request =>
        request ~> Authorization(OAuth2BearerToken("oauth-token")) ~> allRoutes ~> check {
          status shouldEqual StatusCodes.Forbidden
          if (request.uri.path.toString.startsWith("/api/v1/invitation-access")) header("Cache-Control").map(_.value()) shouldEqual Some("no-store")
        }
      }
    }

    "preserve the existing human route tree without evaluating OAuth authentication" in new HumanPrincipalCase {
      Get("/api/v1/organizations/acme/oauth-clients") ~> Authorization(OAuth2BearerToken("human-token")) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.OK
        responseAs[String] shouldEqual s"human:${humanAuth.id}"
      }
    }

    "dispatch an OAuth principal only to the OAuth route tree" in new OAuthPrincipalCase(oauthAuth) {
      Get("/api/v1/organizations") ~> Authorization(OAuth2BearerToken("oauth-token")) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.OK
        responseAs[String] shouldEqual s"oauth:${oauthAuth.clientId}"
      }
    }

    "deny human-only and insufficient-scope operations before invoking an OAuth adapter" in new OAuthPrincipalCase(oauthAuth) {
      Get("/api/v1/organizations/acme/oauth-clients") ~> Authorization(OAuth2BearerToken("oauth-token")) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        responseAs[Json].hcursor.get[String]("type") shouldEqual Right("result:oauth:operation-not-permitted")
      }

      val projectsOnly = oauthAuth.copy(scopes = Set(OAuthScope.ProjectsRead))
      overrideOAuthResult(projectsOnly)
      Get("/api/v1/organizations") ~> Authorization(OAuth2BearerToken("oauth-token-no-scope")) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        responseAs[Json].hcursor.get[String]("error") shouldEqual Right("insufficient_scope")
        header("WWW-Authenticate").exists(_.value().contains("insufficient_scope")) should beTrue
      }
    }

    "return one generic challenge when neither token type authenticates" in new RejectedPrincipalCase {
      Get("/api/v1/organizations") ~> Authorization(OAuth2BearerToken("wrong-token-type-or-key")) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.Unauthorized
        response.headers.filter(_.is("www-authenticate")).map(_.value()) shouldEqual List("Bearer realm=\"epoca-backend\"")
      }
    }

    "keep every fixture-control path outside the public application route tree" in new OAuthPrincipalCase(oauthAuth) {
      Seq(
        Post("/oauth-e2e-fixture/v1/runs"),
        Post(s"/oauth-e2e-fixture/v1/runs/${UUID.randomUUID()}/advance-300-seconds"),
        Post(s"/api/v1/oauth-e2e-fixture/runs/${UUID.randomUUID()}")
      ).foreach { request =>
        request ~> Authorization(OAuth2BearerToken("oauth-token")) ~> allRoutes ~> check {
          response.status shouldEqual StatusCodes.NotFound
        }
      }
    }

    "log only safe OAuth context while redacting request, response, and bearer values" in new LoggedOAuthPrincipalCase {
      val requestSentinel  = "oauth-project-business-payload-sentinel"
      val responseSentinel = "oauth-export-business-response-sentinel"
      val bearerSentinel   = "oauth-accepted-bearer-sentinel"

      Post(
        "/api/v1/organizations/acme/work-units",
        HttpEntity(ContentTypes.`application/json`, s"{\"description\":\"$requestSentinel\"}")
      ) ~> Authorization(OAuth2BearerToken(bearerSentinel)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.OK
      }

      val logs = loggedMessages
      Seq(
        !logs.contains(requestSentinel),
        !logs.contains(responseSentinel),
        !logs.contains(bearerSentinel),
        receivedContext.exists(_.contextMap.get("oauthActorId").contains(oauthAuth.actorId.toString)),
        receivedContext.exists(_.contextMap.get("oauthClientId").contains(oauthAuth.clientId.toString)),
        receivedContext.exists(_.contextMap.get("oauthOrganizationId").contains(oauthAuth.organizationId.toString)),
        receivedContext.exists(_.contextMap.get("oauthTokenId").contains(oauthAuth.tokenId.toString)),
        receivedContext.exists(_.contextMap.get("oauthOperationId").contains("work-units.create"))
      ) shouldEqual Seq.fill(8)(true)
    }
  }

  trait PrincipalCase extends BaseScope {
    override val authContextForTest                        = None
    override lazy val pathPrefixMatcher: PathMatcher[Unit] = Slash ~ "api" / "v1"

    override def route(auth: AuthContext)(implicit tracingContext: TracingContext): RequestContext => Future[RouteResult] =
      path("organizations" / Segment / "oauth-clients")(_ => complete(s"human:${auth.id}"))

    override def oauthRoute(auth: OAuthAuthContext)(implicit tracingContext: TracingContext): Route = {
      val _ = tracingContext
      path("organizations")(get(complete(s"oauth:${auth.clientId}")))
    }
  }

  trait HumanPrincipalCase extends PrincipalCase {
    override lazy val userAuthenticator: UserAuthenticator = _ => Future.successful(AuthenticationResult.success(humanAuth))
    override lazy val oauthPrincipalAuthenticator: OAuthPrincipalAuthenticator = _ =>
      throw new AssertionError("human success must not evaluate OAuth authentication")
  }

  class OAuthPrincipalCase(initial: OAuthAuthContext) extends PrincipalCase {
    private var oauthResult = initial

    def overrideOAuthResult(value: OAuthAuthContext): Unit = oauthResult = value

    override lazy val userAuthenticator: UserAuthenticator                     = _ => Future.successful(GenericChallenge)
    override lazy val oauthPrincipalAuthenticator: OAuthPrincipalAuthenticator = _ => Future.successful(AuthenticationResult.success(oauthResult))
  }

  class LoggedOAuthPrincipalCase extends OAuthPrincipalCase(oauthAuth.copy(scopes = Set(OAuthScope.WorkUnitsWrite))) {
    var receivedContext: Option[TracingContext] = None

    override lazy val logger: org.slf4j.Logger = {
      val captured = mock[org.slf4j.Logger]
      when(captured.isInfoEnabled).thenReturn(true)
      captured
    }

    override def oauthRoute(auth: OAuthAuthContext)(implicit tracingContext: TracingContext): RequestContext => Future[RouteResult] = {
      val _ = auth
      receivedContext = Some(tracingContext)
      path("organizations" / Segment / "work-units") { _ =>
        post(complete(HttpEntity(ContentTypes.`application/json`, "oauth-export-business-response-sentinel")))
      }
    }

    def loggedMessages: String =
      mockingDetails(logger).getInvocations.asScala.flatMap(_.getArguments.collect { case value: String => value }).mkString("\n")
  }

  trait RejectedPrincipalCase extends PrincipalCase {
    override lazy val userAuthenticator: UserAuthenticator                     = _ => Future.successful(GenericChallenge)
    override lazy val oauthPrincipalAuthenticator: OAuthPrincipalAuthenticator = _ => Future.successful(GenericChallenge)
  }

  private val GenericChallenge: SecurityDirectives.AuthenticationResult[Nothing] =
    AuthenticationResult.failWithChallenge(HttpChallenge("Bearer", "epoca-backend"))
}
