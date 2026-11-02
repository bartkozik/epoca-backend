package pl.iterators.epoca.invitation

import cats.effect.std.Supervisor
import cats.effect.unsafe.IORuntime
import cats.effect.{IO, Resource}
import cats.syntax.functor._
import com.typesafe.config.{Config, ConfigFactory}
import io.circe.Json
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives._
import pl.iterators.epoca.auth.domain.AuthDomain.PasswordPlain
import pl.iterators.epoca.auth.domain.UserPassword
import pl.iterators.epoca.mail.domain.InvitationMailResult
import pl.iterators.epoca.mail.emails.UserOrganizationInvitation
import pl.iterators.epoca.mail.services.InvitationMailSender
import pl.iterators.epoca.main.{ApplicationLoader, ApplicationRuntime}
import pl.iterators.epoca.organization.domain.OrganizationDomain._
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.user.domain.UserDomain._
import pl.iterators.epoca.user.domain.{User, UserIdentity}
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.job.{CronJob, SchedulableJob, TriggeredJob, TriggeredJobScheduler}
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.WebSocketStreamBackend

import java.net.URI
import java.time.Instant
import java.util.UUID
import scala.concurrent.ExecutionContext

/** Test classpath only. Real API/authentication, synthetic mail captured only in this process. */
object InvitationBrowserTestMain extends ApplicationRuntime {
  private def required(name: String): String = sys.env.getOrElse(name, throw new IllegalArgumentException(s"Missing $name"))
  private lazy val databaseName              = required("INVITATION_BROWSER_DATABASE")
  private lazy val controlKey                = required("INVITATION_BROWSER_CONTROL_KEY")
  private lazy val frontend                  = required("INVITATION_BROWSER_FRONTEND")
  override protected def loadConfig(): Config = {
    require(required("INVITATION_BROWSER_ENABLED") == "true", "Explicit test runtime opt-in required")
    require(databaseName.matches("ct208_invitation_browser_[a-z0-9_]{8,64}"), "Owned database name required")
    val jdbc = new URI(required("DB_URL").stripPrefix("jdbc:"))
    require(
      jdbc.getScheme == "postgresql" && jdbc.getHost == "epoca-ct208-db" && jdbc.getPort == 5432 &&
        jdbc.getPath == "/" + databaseName && jdbc.getQuery == null && jdbc.getUserInfo == null,
      "Owned PostgreSQL host and database required"
    )
    val origin = new URI(frontend)
    require(
      origin.getScheme == "http" && origin.getHost == "127.0.0.1" && origin.getPort > 0 &&
        Option(origin.getPath).forall(_.isEmpty) && origin.getQuery == null && origin.getFragment == null && origin.getUserInfo == null,
      "Exact loopback frontend origin required"
    )
    require(controlKey.matches("[a-f0-9]{64}"), "Run-specific hexadecimal control key required")
    ConfigFactory
      .parseString(s"""
      kamon.enabled = false
      mail.sync.enabled = false
      oauth.enabled = false
      invitation.featureEnabled = true
      invitation.localHttp = true
      invitation.crossSite = false
      invitation.allowedOrigins = ["$frontend"]
      invitation.rateKey = "$controlKey"
      appFrontEnd.url = "$frontend"
      app.apiPrefix = "api"
      app.apiVersion = "v1"
      pekko.loglevel = "WARNING"
    """)
      .withFallback(super.loadConfig())
      .resolve()
  }
  override protected def createLoader(
    config: Config,
    transactor: PostgresIOTransactor,
    httpClient: WebSocketStreamBackend[IO, Fs2Streams[IO]],
    scheduler: TriggeredJobScheduler,
    invitationSupervisor: Supervisor[IO]
  ): ApplicationLoader =
    new InvitationBrowserApplicationLoader(config, runtime.compute, runtime, transactor, httpClient, scheduler, invitationSupervisor)

  override protected def additionalResources(loader: ApplicationLoader, actorSystem: ActorSystem): Resource[IO, Unit] = {
    val fixture                      = loader.asInstanceOf[InvitationBrowserApplicationLoader]
    implicit val system: ActorSystem = actorSystem
    val controlPort                  = required("INVITATION_BROWSER_CONTROL_PORT").toInt
    Resource.eval(fixture.seed(databaseName, required("INVITATION_BROWSER_OWNER_PASSWORD"))).flatMap { metadata =>
      val route = extractRequest { request =>
        val authorized = request.uri.authority.host.address() == "127.0.0.1" &&
          request.headers.exists(h =>
            h.is("x-invitation-browser-control") &&
              java.security.MessageDigest
                .isEqual(h.value().getBytes(java.nio.charset.StandardCharsets.UTF_8), controlKey.getBytes(java.nio.charset.StandardCharsets.UTF_8))
          )
        respondWithHeader(RawHeader("Cache-Control", "no-store")) {
          if (!authorized) complete(StatusCodes.Forbidden)
          else
            get {
              path("health")(complete(HttpEntity(ContentTypes.`application/json`, metadata.noSpaces))) ~
                path("mail") {
                  parameter("email") { email =>
                    if (!email.endsWith("@example.test")) complete(StatusCodes.BadRequest)
                    else
                      fixture.captured(email) match {
                        case None => complete(StatusCodes.NotFound)
                        case Some(event) =>
                          complete(
                            HttpEntity(
                              ContentTypes.`application/json`,
                              Json
                                .obj(
                                  "link"      -> Json.fromString(frontend + "/invitations/accept#token=" + event.secret),
                                  "expiresAt" -> Json.fromString(event.expiresAt.toString)
                                )
                                .noSpaces
                            )
                          )
                      }
                  }
                }
            }
        }
      }
      Resource
        .make(IO.fromFuture(IO(Http().newServerAt("127.0.0.1", controlPort).bind(route))))(binding => IO.fromFuture(IO(binding.unbind())).void)
        .void
    }
  }
}

final class InvitationBrowserApplicationLoader(
  config: Config,
  executor: ExecutionContext,
  runtime: IORuntime,
  transactor: PostgresIOTransactor,
  backend: WebSocketStreamBackend[IO, Fs2Streams[IO]],
  scheduler: TriggeredJobScheduler,
  supervisor: Supervisor[IO])
    extends ApplicationLoader(config, executor, runtime, transactor, backend, scheduler, supervisor) {
  private var messages = Vector.empty[UserOrganizationInvitation.Event]
  override lazy val invitationMailSender: InvitationMailSender = new InvitationMailSender {
    override def send(event: UserOrganizationInvitation.Event): IO[InvitationMailResult] = IO.delay {
      require(event.email.endsWith("@example.test"), "Only synthetic invitation recipients are allowed")
      InvitationBrowserApplicationLoader.this.synchronized(messages :+= event)
      InvitationMailResult.Sent
    }
  }
  def captured(email: String): Option[UserOrganizationInvitation.Event] = synchronized(messages.reverseIterator.find(_.email == email))
  override def schedulableJobs: List[SchedulableJob]                    = Nil
  override def cronJobs: List[CronJob]                                  = Nil
  override def triggeredJobs: List[TriggeredJob[_]]                     = Nil

  def seed(expectedDatabase: String, ownerPassword: String): IO[Json] = {
    implicit val ec: ExecutionContext = executor
    val now                           = Instant.now()
    val owner = User(
      UserId(UUID.randomUUID()),
      UserFirstName("Invitation"),
      UserLastName("Owner"),
      None,
      None,
      UserIsSuperAdmin(false),
      UserCreatedAt(now),
      UserModifiedAt(now),
      None,
      UserAccountType.Human
    )
    val identity = UserIdentity(
      UserIdentityId(UUID.randomUUID()),
      owner.id,
      IdentityType.MailAddress,
      UserIdentityValue("owner@example.test"),
      UserIdentityMain(true),
      UserIdentityConfirmed(true),
      None,
      None,
      UserIdentityCreatedAt(now)
    )
    val org = Organization(
      OrganizationId(UUID.randomUUID()),
      OrganizationName("CT208 Browser"),
      OrganizationSlug(expectedDatabase.replace('_', '-')),
      OrganizationCreatedAt(now),
      owner.id,
      OrganizationModifiedAt(now),
      owner.id,
      None
    )
    IO.blocking(passwordService.encrypt(PasswordPlain(ownerPassword))).flatMap { hash =>
      transactor.executeTransactionally(
        for {
          actual   <- sql"SELECT current_database()".as[String].head
          _        <- if (actual == expectedDatabase) DBIO.successful(()) else DBIO.failed(new IllegalArgumentException("Wrong fixture database"))
          _        <- emailIdentityRepository.lock(identity.value)
          existing <- emailIdentityRepository.resolve(identity.value)
          _ <- if (existing == pl.iterators.epoca.user.repositories.EmailIdentityRepository.Resolution.Empty) DBIO.successful(())
               else DBIO.failed(new IllegalArgumentException("Fixture requires a fresh database"))
          _ <- userRepository.insert(owner)
          _ <- userIdentityRepository.insert(identity)
          _ <- userPasswordRepository.upsert(UserPassword(identity.id, hash))
          _ <- organizationRepository.insert(org)
          _ <- organizationUserRepository.upsert(OrganizationUser(OrganizationUserId.random, org.id, owner.id, Seq(OrganizationUserRole.Owner), None))
        } yield Json.obj(
          "organizationId"   -> Json.fromString(org.id.toString),
          "organizationSlug" -> Json.fromString(org.slug),
          "ownerEmail"       -> Json.fromString(identity.value)
        )
      )
    }
  }
}
