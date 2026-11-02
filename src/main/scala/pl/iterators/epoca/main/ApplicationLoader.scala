package pl.iterators.epoca.main

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import com.typesafe.config.Config
import org.apache.pekko.event.Logging
import org.apache.pekko.http.scaladsl.model.Uri.Path
import org.apache.pekko.http.scaladsl.model.headers.{Authorization, HttpCredentials, OAuth2BearerToken, RawHeader}
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, HttpMethods, HttpRequest, HttpResponse, StatusCodes}
import org.apache.pekko.http.scaladsl.server.directives.SecurityDirectives
import org.apache.pekko.http.scaladsl.server.{PathMatcher, Route}
import org.apache.pekko.stream.Materializer
import org.slf4j.MDC
import pl.iterators.baklava.routes.BaklavaRoutes
import pl.iterators.epoca.archiver.ArchiverModule
import pl.iterators.epoca.auth.AuthModule
import pl.iterators.epoca.auth.domain.AuthenticatedPrincipal
import pl.iterators.epoca.auth.domain.AuthenticatedPrincipal.{HumanPrincipal, OAuthPrincipal}
import pl.iterators.epoca.client.ClientModule
import pl.iterators.epoca.export.ExportModule
import pl.iterators.epoca.healthcheck.HealthCheckModule
import pl.iterators.epoca.invitation.{InvitationModule, InvitationRetentionModule}
import pl.iterators.epoca.mail.MailModule
import pl.iterators.epoca.metrics.MetricsModule
import pl.iterators.epoca.oauth.OAuthModule
import pl.iterators.epoca.oauth.domain.OAuthAuthContext
import pl.iterators.epoca.oauth.policy.OAuthOperationRegistry
import pl.iterators.epoca.oauth.policy.OAuthOperationRegistry.OAuthOperationClassification._
import pl.iterators.epoca.organization.OrganizationModule
import pl.iterators.epoca.organizationdashboard.OrganizationDashboardModule
import pl.iterators.epoca.project.ProjectModule
import pl.iterators.epoca.saml.SamlModule
import pl.iterators.epoca.sms.SmsModule
import pl.iterators.epoca.stats.StatsModule
import pl.iterators.epoca.storage.{FileStorage, FileStorageFactory}
import pl.iterators.epoca.unarchiver.UnarchiverModule
import pl.iterators.epoca.user.UserModule
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.http.directives.SecretKeyConfig
import pl.iterators.epoca.utils.http.{ApplicationRouteProvider, Handlers}
import pl.iterators.epoca.utils.job._
import pl.iterators.epoca.utils.log.CorrelationIdDirectives.withCorrelationId
import pl.iterators.epoca.utils.log.CustomLoggingDirectives._
import pl.iterators.epoca.utils.log.{CorrelationIdBackend, LoggingSupport, TracingContext}
import pl.iterators.epoca.workunit.WorkUnitModule
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.logging.{LogLevel, LoggingBackend}
import sttp.client4.{WebSocketStreamBackend, logging}

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

class ApplicationLoader(
  val config: Config,
  implicit val executor: ExecutionContext,
  implicit val runtime: IORuntime,
  val transactor: PostgresIOTransactor,
  httpBackend: WebSocketStreamBackend[IO, Fs2Streams[IO]],
  val triggeredJobScheduler: TriggeredJobScheduler,
  val invitationSupervisor: cats.effect.std.Supervisor[IO])
    extends ApplicationRouteProvider
    with ApplicationCronJobProvider
    with ApplicationSchedulableJobProvider
    with ApplicationStartupJobProvider
    with ApplicationTriggeredJobProvider
    with Handlers
    with LoggingSupport
    with ArchiverModule
    with AuthModule
    with SamlModule
    with StatsModule
    with SmsModule
    with HealthCheckModule
    with MailModule
    with InvitationModule
    with InvitationRetentionModule
    with MetricsModule
    with OrganizationModule
    with OAuthModule
    with OrganizationDashboardModule
    with UnarchiverModule
    with UserModule
    with ClientModule
    with ProjectModule
    with WorkUnitModule
    with ExportModule {
  require(!invitationConfig.enabled || invitationConfig.browser.isDefined, "Invalid invitation configuration")
  locally {
    val _ = pl.iterators.epoca.auth.services.LoginResolveService.Config(config)
    val _ = pl.iterators.epoca.auth.services.LoginTokenGenerateService.Config(config)
    BaklavaSecurity.validate(config)
  }
  override def samlInvitationAccess: Option[pl.iterators.epoca.invitation.services.InvitationAccessService] = configuredInvitationAccess

  protected lazy val secretKeyConfig: SecretKeyConfig = SecretKeyConfig(config)

  lazy val fileStorage: FileStorage = FileStorageFactory(config)

  val httpClientLogger: logging.Logger[IO] = new logging.Logger[IO] {
    override def apply(level: LogLevel, message: => String): IO[Unit] = level match {
      case LogLevel.Trace => Logger[IO].debug(message)
      case LogLevel.Debug => Logger[IO].debug(message)
      case LogLevel.Info  => Logger[IO].info(message)
      case LogLevel.Warn  => Logger[IO].warning(message)
      case LogLevel.Error => Logger[IO].error(message)
    }
    override def apply(
      level: LogLevel,
      message: => String,
      t: Throwable
    ): IO[Unit] = level match {
      case LogLevel.Trace => Logger[IO].warning(t)(message)
      case LogLevel.Debug => Logger[IO].warning(t)(message)
      case LogLevel.Info  => Logger[IO].warning(t)(message)
      case LogLevel.Warn  => Logger[IO].warning(t)(message)
      case LogLevel.Error => Logger[IO].error(t)(message)
    }
  }
  val httpClient: WebSocketStreamBackend[IO, Fs2Streams[IO]] =
    CorrelationIdBackend(LoggingBackend(httpBackend, httpClientLogger), headerName = "Correlation-Id")

  val invitationHttpClient: WebSocketStreamBackend[IO, Fs2Streams[IO]] =
    CorrelationIdBackend(httpBackend, headerName = "Correlation-Id")

  private val logAction = (tracingContext: Option[TracingContext]) =>
    (requestOrResponse: String) => {
      tracingContext.foreach(tc => MDC.put(tc.contextName, tc.toString))
      loggingAdapter.log(Logging.LogLevel(config.getInt("log.logLevelRequestResponse")), requestOrResponse)
      tracingContext.foreach(tc => MDC.remove(tc.contextName))
    }

  private lazy val apiPrefix: String            = config.getString("app.apiPrefix")
  private lazy val apiVersion: String           = config.getString("app.apiVersion")
  lazy val pathPrefixMatcher: PathMatcher[Unit] = Slash ~ apiPrefix / apiVersion

  private val loggingRules = ApplicationLoader.loggingRules(apiPrefix, apiVersion)

  private def logRequest(tracingContext: Option[TracingContext]) = withRequestLogged(logAction(tracingContext), loggingRules)

  private def logResult(tracingContext: Option[TracingContext]) = withResultLogged(logAction(tracingContext), loggingRules)

  private val contextName = "context"

  private lazy val docRoutes: Route = pathPrefix(apiPrefix)(BaklavaRoutes.routes(config))

  private def principalAuthenticator(credentials: Option[HttpCredentials]): Future[SecurityDirectives.AuthenticationResult[AuthenticatedPrincipal]] =
    userAuthenticator(credentials).flatMap {
      case Right(auth) => Future.successful(Right(HumanPrincipal(auth)))
      case Left(challenge) =>
        credentials match {
          case Some(_: OAuth2BearerToken) => oauthPrincipalAuthenticator(credentials).map(_.map(OAuthPrincipal.apply))
          case _ =>
            Future.successful(Left(challenge): SecurityDirectives.AuthenticationResult[AuthenticatedPrincipal])
        }
    }

  lazy val routes: Route =
    withCorrelationId(headerName = "Correlation-Id", correlationIdGenerator = UUID.randomUUID().toString) { correlationId =>
      val baseTracingContext: TracingContext = TracingContext(contextName, correlationId, Map.empty)
      logRequest(Some(baseTracingContext)) {
        handleExceptions(exceptionHandler(Some(baseTracingContext), logResult(Some(baseTracingContext)))) {
          handleRejections(rejectionHandler(Some(baseTracingContext), logResult(Some(baseTracingContext)))) {
            rawPathPrefix(pathPrefixMatcher) {
              authenticateOrRejectWithChallenge(principalAuthenticator _) {
                case HumanPrincipal(auth) =>
                  val userTracingContext: TracingContext = baseTracingContext.withMoreContext("uid" -> auth.id.toString)
                  handleExceptions(exceptionHandler(Some(userTracingContext), logResult(Some(userTracingContext)))) {
                    handleRejections(rejectionHandler(Some(userTracingContext), logResult(Some(userTracingContext)))) {
                      logResult(Some(userTracingContext)) {
                        pl.iterators.epoca.auth.services.SamlSessionScope.guard(auth, samlSessionService.currentOrganization(auth)) { scopedAuth =>
                          route(scopedAuth)(userTracingContext) ~ route(userTracingContext)
                        }
                      }
                    }
                  }
                case OAuthPrincipal(auth) =>
                  val oauthTracingContext = baseTracingContext
                    .withMoreContext("oauthActorId" -> auth.actorId.toString)
                    .withMoreContext("oauthClientId" -> auth.clientId.toString)
                    .withMoreContext("oauthOrganizationId" -> auth.organizationId.toString)
                    .withMoreContext("oauthTokenId" -> auth.tokenId.toString)
                  handleExceptions(exceptionHandler(Some(oauthTracingContext), logResult(Some(oauthTracingContext)))) {
                    handleRejections(rejectionHandler(Some(oauthTracingContext), logResult(Some(oauthTracingContext)))) {
                      oauthDispatch(auth, oauthTracingContext)
                    }
                  }
              } ~ logResult(Some(baseTracingContext)) {
                route(baseTracingContext)
              }
            } ~ docRoutes
          }
        }
      }
    }

  private def oauthDispatch(auth: OAuthAuthContext, tracingContext: TracingContext): Route =
    extractRequest { request =>
      oauthOperationRegistry.classify(request.method, request.uri.path.toString()) match {
        case OAuthEnabled(operation) if auth.scopes.contains(operation.requiredScope) =>
          val operationContext = tracingContext.withMoreContext("oauthOperationId" -> operation.id)
          logResult(Some(operationContext))(oauthRoute(auth)(operationContext))
        case OAuthEnabled(operation) =>
          val challenge = s"Bearer error=\"insufficient_scope\", scope=\"${operation.requiredScope.value}\""
          logResult(Some(tracingContext)) {
            complete(
              HttpResponse(
                status = StatusCodes.Forbidden,
                headers = List(RawHeader("WWW-Authenticate", challenge)),
                entity =
                  HttpEntity(ContentTypes.`application/json`, io.circe.Json.obj("error" -> io.circe.Json.fromString("insufficient_scope")).noSpaces)
              )
            )
          }
        case HumanOnly | Unclassified =>
          logResult(Some(tracingContext)) {
            complete(
              HttpResponse(
                status = StatusCodes.Forbidden,
                headers =
                  Option.when(pl.iterators.epoca.utils.http.InvitationSensitiveRequest(request))(RawHeader("Cache-Control", "no-store")).toList,
                entity = HttpEntity(
                  ContentTypes.`application/json`,
                  io.circe.Json
                    .obj(
                      "type"   -> io.circe.Json.fromString("result:oauth:operation-not-permitted"),
                      "status" -> io.circe.Json.fromInt(StatusCodes.Forbidden.intValue),
                      "title"  -> io.circe.Json.fromString("OAuth operation not permitted")
                    )
                    .noSpaces
                )
              )
            )
          }
        case Unprotected => reject
      }
    }
}

object ApplicationLoader {
  private[main] def loggingRules(apiPrefix: String, apiVersion: String): Seq[CustomLogRule] =
    Seq(
      WhenPathStartsWith(Path(s"/$apiPrefix/$apiVersion/auth")) -> HideEntity(inRequest = true, inResponse = true),
      SensitiveOAuthEntity(apiPrefix, apiVersion)               -> HideEntity(inRequest = true, inResponse = true),
      OAuthCapableEntity                                        -> HideEntity(inRequest = true, inResponse = true),
      AnyRequest                                                -> HideHeaders(Authorization)
    )

  private final case class SensitiveOAuthEntity(apiPrefix: String, apiVersion: String) extends CustomLogPredicate {
    override def apply(httpRequest: HttpRequest)(implicit ec: ExecutionContext, mat: Materializer): Boolean =
      matches(httpRequest)

    override def apply(httpResponse: HttpResponse, httpRequest: HttpRequest)(implicit ec: ExecutionContext, mat: Materializer): Boolean =
      matches(httpRequest)

    private def matches(request: HttpRequest): Boolean = {
      val segments = request.uri.path.toString().split('/').iterator.filter(_.nonEmpty).toVector
      request.method == HttpMethods.POST && (segments match {
        case Vector(`apiPrefix`, `apiVersion`, "oauth", "token")                                        => true
        case Vector(`apiPrefix`, `apiVersion`, "organizations", _, "oauth-clients")                     => true
        case Vector(`apiPrefix`, `apiVersion`, "organizations", _, "oauth-clients", _, "rotate-secret") => true
        case _                                                                                          => false
      })
    }
  }

  private object OAuthCapableEntity extends CustomLogPredicate {
    override def apply(httpRequest: HttpRequest)(implicit ec: ExecutionContext, mat: Materializer): Boolean =
      matches(httpRequest)

    override def apply(httpResponse: HttpResponse, httpRequest: HttpRequest)(implicit ec: ExecutionContext, mat: Materializer): Boolean =
      matches(httpRequest)

    private def matches(request: HttpRequest): Boolean =
      OAuthOperationRegistry.default.classify(request.method, request.uri.path.toString()).isInstanceOf[OAuthEnabled]
  }
}
