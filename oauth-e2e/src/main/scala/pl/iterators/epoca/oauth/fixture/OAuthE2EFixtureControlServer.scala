package pl.iterators.epoca.oauth.fixture

import cats.effect.unsafe.IORuntime
import cats.effect.{Deferred, FiberIO, IO, Resource}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.stream.Materializer
import pl.iterators.epoca.oauth.fixture.OAuthE2EFixtureConfig.FixtureRole
import pl.iterators.epoca.oauth.fixture.OAuthE2EFixtureDomain.ControllerInstanceId
import pl.iterators.epoca.oauth.fixture.OAuthE2EFixtureDomain.ControllerLossResult.{AlreadyTerminal, MarkedTerminal}
import pl.iterators.epoca.oauth.fixture.OAuthE2EFixtureDomain.FixtureInitializationResult.{ControllerClaimed, TimeOnlyReady}
import pl.iterators.epoca.utils.db.PostgresIOTransactor

import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt
import scala.util.control.NoStackTrace

private[fixture] sealed trait OAuthE2EFixtureQuarantineStepOutcome
private[fixture] object OAuthE2EFixtureQuarantineStepOutcome {
  case object Completed extends OAuthE2EFixtureQuarantineStepOutcome
  case object Failed    extends OAuthE2EFixtureQuarantineStepOutcome
  case object TimedOut  extends OAuthE2EFixtureQuarantineStepOutcome
}

private[fixture] final case class OAuthE2EFixtureQuarantineOutcome(
  listenerUnbind: OAuthE2EFixtureQuarantineStepOutcome,
  controllerTerminalization: OAuthE2EFixtureQuarantineStepOutcome)

private[fixture] final case class OAuthE2EFixtureQuarantineFailed(outcome: OAuthE2EFixtureQuarantineOutcome)
    extends RuntimeException(
      s"Fixture controller quarantine failed closed: listener=${outcome.listenerUnbind}, controllerLease=${outcome.controllerTerminalization}"
    )
    with NoStackTrace

private[fixture] sealed trait OAuthE2EFixtureTerminationPublishOutcome
private[fixture] object OAuthE2EFixtureTerminationPublishOutcome {
  case object Failed   extends OAuthE2EFixtureTerminationPublishOutcome
  case object TimedOut extends OAuthE2EFixtureTerminationPublishOutcome
}

private[fixture] final case class OAuthE2EFixtureTerminationPublishFailed(outcome: OAuthE2EFixtureTerminationPublishOutcome)
    extends RuntimeException(outcome match {
      case OAuthE2EFixtureTerminationPublishOutcome.Failed   => "Fixture termination signal publication failed"
      case OAuthE2EFixtureTerminationPublishOutcome.TimedOut => "Fixture termination signal publication timed out"
    })
    with NoStackTrace

trait OAuthE2EFixtureControlBinder {
  def bind(
    interface: String,
    port: Int,
    route: Route
  ): Resource[IO, Unit]
}

final class PekkoOAuthE2EFixtureControlBinder(implicit actorSystem: ActorSystem) extends OAuthE2EFixtureControlBinder {
  override def bind(
    interface: String,
    port: Int,
    route: Route
  ): Resource[IO, Unit] =
    Resource
      .make(IO.fromFuture(IO(Http()(actorSystem).newServerAt(interface, port).bindFlow(Route.toFlow(route)(actorSystem)))))(binding =>
        IO.fromFuture(IO(binding.unbind())).void
      )
      .map(_ => ())
}

object OAuthE2EFixtureControlServer {
  private sealed trait ControllerStopRequest
  private case object CoordinatedShutdown                                               extends ControllerStopRequest
  private final case class FatalTermination(signal: OAuthE2EFixtureTerminationRequired) extends ControllerStopRequest

  private val QuarantineAttemptTimeout  = 1.second
  private val TerminationPublishTimeout = 2.seconds

  def resource(
    config: OAuthE2EFixtureConfig,
    repository: Option[OAuthE2EFixtureRepository],
    transactor: PostgresIOTransactor,
    backendCanceller: Option[PostgresOAuthE2EFixtureBackendCanceller],
    terminationPublisher: Option[OAuthE2EFixtureTerminationPublisher],
    handlerFactory: (OAuthE2EFixtureControllerSupervisor, PostgresOAuthE2EFixtureBackendCanceller) => OAuthE2EFixtureControlHandler,
    binder: OAuthE2EFixtureControlBinder
  )(implicit
    runtime: IORuntime,
    executionContext: ExecutionContext,
    materializer: Materializer
  ): Resource[IO, Unit] =
    config.role match {
      case FixtureRole.Disabled => Resource.unit
      case FixtureRole.TimeOnlyReplica =>
        Resource.eval(requiredRepository(repository).flatMap { value =>
          transactor.executeTransactionally(value.initialize(config, None)).flatMap {
            case TimeOnlyReady => IO.unit
            case _             => IO.raiseError(new IllegalStateException("Fixture time-only initialization failed closed"))
          }
        })
      case FixtureRole.Controller =>
        val controllerId = ControllerInstanceId(UUID.randomUUID())
        controllerResource(config, repository, transactor, controllerId, backendCanceller, terminationPublisher, handlerFactory, binder)
    }

  private final case class ControllerResourceState(
    stopRequest: Deferred[IO, ControllerStopRequest],
    stopFiber: FiberIO[Unit],
    stopOutcome: Deferred[IO, Either[Throwable, Unit]],
    externalStop: Deferred[IO, Unit])

  private def controllerResource(
    config: OAuthE2EFixtureConfig,
    repository: Option[OAuthE2EFixtureRepository],
    transactor: PostgresIOTransactor,
    controllerId: ControllerInstanceId,
    backendCanceller: Option[PostgresOAuthE2EFixtureBackendCanceller],
    terminationPublisher: Option[OAuthE2EFixtureTerminationPublisher],
    handlerFactory: (OAuthE2EFixtureControllerSupervisor, PostgresOAuthE2EFixtureBackendCanceller) => OAuthE2EFixtureControlHandler,
    binder: OAuthE2EFixtureControlBinder
  )(implicit
    runtime: IORuntime,
    executionContext: ExecutionContext,
    materializer: Materializer
  ): Resource[IO, Unit] =
    Resource
      .make {
        for {
          value     <- requiredRepository(repository)
          canceller <- IO.fromOption(backendCanceller)(new IllegalStateException("Fixture controller cancel pool is absent"))
          publisher <- IO.fromOption(terminationPublisher)(new IllegalStateException("Fixture external supervisor signal is absent"))
          _         <- claimController(config, value, transactor, controllerId)
          state <- (for {
                     stopRequest  <- Deferred[IO, ControllerStopRequest]
                     externalStop <- Deferred[IO, Unit]
                     supervisor = new OAuthE2EFixtureControllerSupervisor {
                                    override def stopControllerAndDestroyRun: IO[Unit] =
                                      stopRequest.complete(FatalTermination(OAuthE2EFixtureTerminationRequired.withoutMarkedSession)).void *>
                                        externalStop.get

                                    override def stopControllerAndDestroyRun(sessionMarker: String): IO[Unit] =
                                      stopRequest.complete(FatalTermination(OAuthE2EFixtureTerminationRequired(sessionMarker))).void *>
                                        externalStop.get
                                  }
                     listener <- IO.fromOption(config.controlListener)(new IllegalStateException("Fixture controller listener is absent"))
                     router = new OAuthE2EFixtureControlRouter(
                                new OAuthE2EFixtureControlTokenVerifier(listener.token.value),
                                new OAuthE2EFixtureFailedAuthLimiter(() => System.nanoTime()),
                                handlerFactory(supervisor, canceller),
                                supervisor = supervisor
                              )
                     allocated <- binder.bind(listener.interface, listener.port, router.route).allocated
                     state <- (for {
                                quarantineOnce <- quarantineController(allocated._2, terminalizeController(value, transactor, controllerId)).memoize
                                stopOutcome    <- Deferred[IO, Either[Throwable, Unit]]
                                stopFiber <- stopRequest.get
                                               .flatMap(request => completeStopRequest(request, quarantineOnce, publisher))
                                               .attempt
                                               .flatMap(stopOutcome.complete)
                                               .void
                                               .start
                              } yield ControllerResourceState(stopRequest, stopFiber, stopOutcome, externalStop)).onError(_ => allocated._2)
                   } yield state).onError(_ => terminalizeController(value, transactor, controllerId))
        } yield state
      }(state =>
        state.externalStop.complete(()).void *>
          state.stopRequest.complete(CoordinatedShutdown).void *>
          state.stopFiber.joinWithNever *>
          state.stopOutcome.get.rethrow
      )
      .map(_ => ())

  private def completeStopRequest(
    request: ControllerStopRequest,
    quarantine: IO[Unit],
    publisher: OAuthE2EFixtureTerminationPublisher
  ): IO[Unit] = {
    val signal = request match {
      case CoordinatedShutdown        => None
      case FatalTermination(required) => Some(required)
    }
    quarantine.attempt.flatMap {
      case Right(_) => signal.fold(IO.unit)(boundedTerminationPublication(publisher, _))
      case Left(quarantineFailure) =>
        boundedTerminationPublication(publisher, signal.getOrElse(OAuthE2EFixtureTerminationRequired.withoutMarkedSession)).attempt
          .flatMap {
            case Right(_) => IO.raiseError(quarantineFailure)
            case Left(publishFailure) =>
              IO(quarantineFailure.addSuppressed(publishFailure)) *> IO.raiseError(quarantineFailure)
          }
    }
  }

  private def boundedTerminationPublication(publisher: OAuthE2EFixtureTerminationPublisher, signal: OAuthE2EFixtureTerminationRequired): IO[Unit] =
    IO.defer(publisher.publish(signal)).attempt.start.flatMap { fiber =>
      IO.race(fiber.joinWithNever, IO.sleep(TerminationPublishTimeout)).flatMap {
        case Left(Right(_)) => IO.unit
        case Left(Left(_))  => IO.raiseError(OAuthE2EFixtureTerminationPublishFailed(OAuthE2EFixtureTerminationPublishOutcome.Failed))
        case Right(_) =>
          fiber.cancel.start.void *>
            IO.raiseError(OAuthE2EFixtureTerminationPublishFailed(OAuthE2EFixtureTerminationPublishOutcome.TimedOut))
      }
    }

  private def quarantineController(releaseListener: IO[Unit], terminalizeLease: IO[Unit]): IO[Unit] =
    for {
      listenerOutcome <- boundedQuarantineStep(releaseListener)
      leaseOutcome    <- boundedQuarantineStep(terminalizeLease)
      outcome = OAuthE2EFixtureQuarantineOutcome(listenerOutcome, leaseOutcome)
      _ <-
        if (
          listenerOutcome == OAuthE2EFixtureQuarantineStepOutcome.Completed &&
          leaseOutcome == OAuthE2EFixtureQuarantineStepOutcome.Completed
        ) IO.unit
        else IO.raiseError(OAuthE2EFixtureQuarantineFailed(outcome))
    } yield ()

  private def boundedQuarantineStep(step: IO[Unit]): IO[OAuthE2EFixtureQuarantineStepOutcome] =
    step.attempt.start.flatMap { fiber =>
      IO.race(fiber.joinWithNever, IO.sleep(QuarantineAttemptTimeout)).flatMap {
        case Left(Right(_)) => IO.pure(OAuthE2EFixtureQuarantineStepOutcome.Completed)
        case Left(Left(_))  => IO.pure(OAuthE2EFixtureQuarantineStepOutcome.Failed)
        case Right(_)       =>
          // A JDBC Future or a broken listener finalizer can be uncancellable. Do not let cancellation settlement suppress the external stop signal.
          fiber.cancel.start.void.as(OAuthE2EFixtureQuarantineStepOutcome.TimedOut)
      }
    }

  private def claimController(
    config: OAuthE2EFixtureConfig,
    repository: OAuthE2EFixtureRepository,
    transactor: PostgresIOTransactor,
    controllerId: ControllerInstanceId
  ): IO[Unit] =
    transactor.executeTransactionally(repository.initialize(config, Some(controllerId))).flatMap {
      case ControllerClaimed => IO.unit
      case _                 => IO.raiseError(new IllegalStateException("Fixture controller lease was not claimed"))
    }

  private def terminalizeController(
    repository: OAuthE2EFixtureRepository,
    transactor: PostgresIOTransactor,
    controllerId: ControllerInstanceId
  ): IO[Unit] =
    transactor.executeTransactionally(repository.markControllerLost(controllerId)).flatMap {
      case MarkedTerminal | AlreadyTerminal => IO.unit
      case _                                => IO.raiseError(new IllegalStateException("Fixture controller terminalization failed closed"))
    }

  private def requiredRepository(repository: Option[OAuthE2EFixtureRepository]): IO[OAuthE2EFixtureRepository] =
    IO.fromOption(repository)(new IllegalStateException("Fixture repository is absent"))
}
