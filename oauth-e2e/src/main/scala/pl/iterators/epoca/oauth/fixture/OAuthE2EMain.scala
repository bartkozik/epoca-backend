package pl.iterators.epoca.oauth.fixture

import cats.effect.unsafe.IORuntime
import cats.effect.{IO, Resource}
import com.typesafe.config.{Config, ConfigFactory}
import org.apache.pekko.actor.ActorSystem
import pl.iterators.epoca.main.{ApplicationLoader, ApplicationRuntime}
import pl.iterators.epoca.oauth.fixture.OAuthE2EFixtureConfig.FixtureRole
import pl.iterators.epoca.oauth.time.{OAuthAuthoritativeTime, PostgresOAuthAuthoritativeTime}
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.job.TriggeredJobScheduler
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.WebSocketStreamBackend

import scala.concurrent.ExecutionContext

class OAuthE2EApplicationLoader(
  config: Config,
  executor: ExecutionContext,
  runtime: IORuntime,
  transactor: PostgresIOTransactor,
  httpBackend: WebSocketStreamBackend[IO, Fs2Streams[IO]],
  scheduler: TriggeredJobScheduler,
  invitationSupervisor: cats.effect.std.Supervisor[IO])
    extends ApplicationLoader(config, executor, runtime, transactor, httpBackend, scheduler, invitationSupervisor) {
  lazy val oauthE2EFixtureConfig: OAuthE2EFixtureConfig = OAuthE2EFixtureConfig.load(config) match {
    case Right(value) => value
    case Left(error)  => throw new IllegalArgumentException(s"Invalid OAuth E2E fixture configuration: $error")
  }
  lazy val oauthE2EFixtureRepository: Option[OAuthE2EFixtureRepository] =
    Option.when(oauthE2EFixtureConfig.timeEnabled)(new OAuthE2EFixtureRepositoryImpl()(executor))
  override lazy val oauthAuthoritativeTime: OAuthAuthoritativeTime = oauthE2EFixtureRepository
    .map(repository => OAuthE2EApplicationLoader.authoritativeTimeFor(oauthE2EFixtureConfig, repository)(executor))
    .getOrElse(new PostgresOAuthAuthoritativeTime()(executor))
}

object OAuthE2EApplicationLoader {
  private[fixture] def authoritativeTimeFor(config: OAuthE2EFixtureConfig, repository: OAuthE2EFixtureRepository)(implicit ec: ExecutionContext)
    : OAuthAuthoritativeTime =
    if (config.timeEnabled) new FixtureOAuthAuthoritativeTime(repository) else new PostgresOAuthAuthoritativeTime()
}

object OAuthE2EMain extends ApplicationRuntime {
  override protected def loadConfig(): Config =
    ConfigFactory.load().withFallback(ConfigFactory.parseResources("oauth-e2e.conf")).resolve()

  override protected def createLoader(
    config: Config,
    transactor: PostgresIOTransactor,
    httpClient: WebSocketStreamBackend[IO, Fs2Streams[IO]],
    scheduler: TriggeredJobScheduler,
    invitationSupervisor: cats.effect.std.Supervisor[IO]
  ): ApplicationLoader = new OAuthE2EApplicationLoader(config, runtime.compute, runtime, transactor, httpClient, scheduler, invitationSupervisor)

  override protected def additionalResources(loader: ApplicationLoader, actorSystem: ActorSystem): Resource[IO, Unit] = loader match {
    case fixtureLoader: OAuthE2EApplicationLoader => fixtureControlResource(fixtureLoader, actorSystem)
    case _ => Resource.eval(IO.raiseError(new IllegalStateException("OAuth E2E application loader is required")))
  }

  private def fixtureControlResource(loader: OAuthE2EApplicationLoader, actorSystem: ActorSystem): Resource[IO, Unit] = {
    implicit val system: ActorSystem                                = actorSystem
    implicit val executor: ExecutionContext                         = loader.executor
    implicit val materializer: org.apache.pekko.stream.Materializer = org.apache.pekko.stream.SystemMaterializer(actorSystem).materializer
    implicit val ioRuntime: cats.effect.unsafe.IORuntime            = loader.runtime

    val controllerResources = loader.oauthE2EFixtureConfig.role match {
      case FixtureRole.Controller =>
        PostgresOAuthE2EFixtureBackendCanceller
          .resource(loader.config)
          .map(canceller => (Some(canceller), Some(AtomicFileOAuthE2EFixtureTerminationPublisher.production)))
      case _ =>
        Resource.pure[IO, (Option[PostgresOAuthE2EFixtureBackendCanceller], Option[OAuthE2EFixtureTerminationPublisher])]((None, None))
    }

    controllerResources.flatMap { case (canceller, terminationPublisher) =>
      OAuthE2EFixtureControlServer.resource(
        loader.oauthE2EFixtureConfig,
        loader.oauthE2EFixtureRepository,
        loader.transactor,
        canceller,
        terminationPublisher,
        (supervisor, backendCanceller) =>
          new OAuthE2EFixtureServiceControlHandler(
            new OAuthE2EFixtureService(
              new OAuthE2EFixtureCommandExecutor(loader.transactor, backendCanceller, supervisor),
              loader.oauthE2EFixtureRepository.getOrElse(throw new IllegalStateException("Fixture controller repository is absent")),
              loader.oauthClientManagementService,
              loader.oauthSecretService,
              loader.userRepository,
              loader.organizationRepository,
              loader.organizationUserRepository,
              loader.clientRepository,
              loader.projectRepository,
              loader.workUnitRepository
            )
          ),
        new PekkoOAuthE2EFixtureControlBinder()
      )
    }
  }
}
