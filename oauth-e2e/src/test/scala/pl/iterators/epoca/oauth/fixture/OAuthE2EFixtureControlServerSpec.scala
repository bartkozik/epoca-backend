package pl.iterators.epoca.oauth.fixture

import cats.effect.unsafe.IORuntime
import cats.effect.{Deferred, IO, Resource}
import com.typesafe.config.{Config, ConfigFactory, ConfigResolveOptions}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.stream.{Materializer, SystemMaterializer}
import org.specs2.mutable.SpecificationLike
import pl.iterators.epoca.oauth.fixture.OAuthE2EFixtureDomain.ControllerLeaseState.{Claimed, Terminal, Unclaimed}
import pl.iterators.epoca.oauth.fixture.OAuthE2EFixtureDomain.{
  AdvanceResult,
  CleanupStep,
  CleanupStepState,
  CleanupTransitionResult,
  ControllerInstanceId,
  ControllerLossResult,
  FixtureAuditCommandCategory,
  FixtureAuditRow,
  FixtureInitializationResult,
  FixtureOwnership,
  FixtureRunLedger,
  FixtureSetupPersistenceResult,
  IdempotencyKeyDigest,
  NonCleanupCommandAdmissionResult
}
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import slick.util.AsyncExecutor

import java.util.{Base64, Properties, UUID}
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Promise}

class OAuthE2EFixtureControlServerSpec extends SpecificationLike {
  sequential

  private implicit val runtime: IORuntime         = IORuntime.global
  private implicit val ec: ExecutionContext       = ExecutionContext.global
  private implicit val actorSystem: ActorSystem   = ActorSystem("oauth-e2e-command-control-server", ConfigFactory.parseString("pekko.loglevel=OFF"))
  private implicit val materializer: Materializer = SystemMaterializer(actorSystem).materializer

  "OAuth E2E fixture control server resource" should {
    "claim exactly one controller, bind only loopback, then unbind and terminalize its lease" in isolatedFixtureTables("controller") {
      (database, repository) =>
        val binder     = new RecordingBinder
        val transactor = new PostgresIOTransactor(database)
        val allocated  = serverResource(controllerConfig, Some(repository), transactor, database, binder).allocated.unsafeRunSync()

        binder.binds.map { case (interface, port, _) => (interface, port) } shouldEqual Vector("127.0.0.1" -> 19042)
        Await.result(database.run(repository.ledger), 5.seconds).map(_.controllerLease) must beSome(beAnInstanceOf[Claimed])

        allocated._2.unsafeRunSync()
        binder.unbindCount shouldEqual 1
        Await.result(database.run(repository.ledger), 5.seconds).map(_.controllerLease) must beSome(beAnInstanceOf[Terminal])
    }

    "fail a second controller before another listener can bind" in isolatedFixtureTables("second-controller") { (database, repository) =>
      val binder     = new RecordingBinder
      val transactor = new PostgresIOTransactor(database)
      val first      = serverResource(controllerConfig, Some(repository), transactor, database, binder).allocated.unsafeRunSync()

      serverResource(controllerConfig, Some(repository), transactor, database, binder).use(_ => IO.unit).attempt.unsafeRunSync() must beLeft
      binder.binds must haveSize(1)

      first._2.unsafeRunSync()
      binder.unbindCount shouldEqual 1
    }

    "let the fail-closed supervisor unbind and terminalize the controller exactly once" in isolatedFixtureTables("supervisor-stop") {
      (database, repository) =>
        val binder               = new RecordingBinder
        val publisher            = new RecordingTerminationPublisher
        val transactor           = new PostgresIOTransactor(database)
        @volatile var supervisor = Option.empty[OAuthE2EFixtureControllerSupervisor]
        val allocated = OAuthE2EFixtureControlServer
          .resource(
            controllerConfig,
            Some(repository),
            transactor,
            Some(new PostgresOAuthE2EFixtureBackendCanceller(database)),
            Some(publisher),
            (value, _) => {
              supervisor = Some(value)
              OAuthE2EFixtureControlHandler.unavailable
            },
            binder
          )
          .allocated
          .unsafeRunSync()

        val marker  = "oauth-e2e-fixture-command-00000000-0000-4000-8000-000000000522"
        val stopped = Deferred[IO, Unit].unsafeRunSync()
        val stopFiber = (supervisor
          .getOrElse(throw new AssertionError("supervisor was not projected"))
          .stopControllerAndDestroyRun(marker) *> stopped.complete(()).void).start.unsafeRunSync()

        publisher.published.get.timeout(5.seconds).unsafeRunSync()
        binder.unbindCount shouldEqual 1
        Await.result(database.run(repository.ledger), 5.seconds).map(_.controllerLease) must beSome(beAnInstanceOf[Terminal])
        publisher.signals shouldEqual Vector(OAuthE2EFixtureTerminationRequired(marker))
        stopped.tryGet.unsafeRunSync() should beNone

        allocated._2.unsafeRunSync()
        stopped.get.timeout(5.seconds).unsafeRunSync()
        stopFiber.joinWithNever.unsafeRunSync()
        binder.unbindCount shouldEqual 1
    }

    "terminalize a claimed controller when acquisition fails before the listener binds" in isolatedFixtureTables("acquire-failure") {
      (database, repository) =>
        val binder     = new RecordingBinder
        val transactor = new PostgresIOTransactor(database)
        val result = OAuthE2EFixtureControlServer
          .resource(
            controllerConfig,
            Some(repository),
            transactor,
            Some(new PostgresOAuthE2EFixtureBackendCanceller(database)),
            Some(new RecordingTerminationPublisher),
            (_, _) => throw new IllegalStateException("synthetic handler construction failure"),
            binder
          )
          .use(_ => IO.unit)
          .attempt
          .unsafeRunSync()
        val lease = Await.result(database.run(repository.ledger), 5.seconds).map(_.controllerLease)

        (result.isLeft, binder.binds.isEmpty, lease.exists(_.isInstanceOf[Terminal])) shouldEqual ((true, true, true))
    }

    "emit an unmarked fatal signal after a pre-persistence quarantine and wait for external process stop" in isolatedFixtureTables("pre-db-fatal") {
      (database, repository) =>
        val binder               = new RecordingBinder
        val publisher            = new RecordingTerminationPublisher
        val transactor           = new PostgresIOTransactor(database)
        @volatile var supervisor = Option.empty[OAuthE2EFixtureControllerSupervisor]
        val allocated = OAuthE2EFixtureControlServer
          .resource(
            controllerConfig,
            Some(repository),
            transactor,
            Some(new PostgresOAuthE2EFixtureBackendCanceller(database)),
            Some(publisher),
            (value, _) => {
              supervisor = Some(value)
              OAuthE2EFixtureControlHandler.unavailable
            },
            binder
          )
          .allocated
          .unsafeRunSync()
        val stopped = Deferred[IO, Unit].unsafeRunSync()
        val stopFiber = (supervisor.getOrElse(throw new AssertionError("supervisor was not projected")).stopControllerAndDestroyRun *>
          stopped.complete(()).void).start.unsafeRunSync()

        publisher.published.get.timeout(5.seconds).unsafeRunSync()
        publisher.signals shouldEqual Vector(OAuthE2EFixtureTerminationRequired.withoutMarkedSession)
        binder.unbindCount shouldEqual 1
        Await.result(database.run(repository.ledger), 5.seconds).map(_.controllerLease) must beSome(beAnInstanceOf[Terminal])
        stopped.tryGet.unsafeRunSync() should beNone

        allocated._2.unsafeRunSync()
        stopped.get.timeout(5.seconds).unsafeRunSync()
        stopFiber.joinWithNever.unsafeRunSync()
        binder.unbindCount shouldEqual 1
    }

    "share one complete listener release when supervisor stop races resource finalization" in isolatedFixtureTables("release-race") {
      (database, repository) =>
        val releaseStarted       = Deferred[IO, Unit].unsafeRunSync()
        val allowRelease         = Deferred[IO, Unit].unsafeRunSync()
        val binder               = new BlockingReleaseBinder(releaseStarted, allowRelease)
        val transactor           = new PostgresIOTransactor(database)
        @volatile var supervisor = Option.empty[OAuthE2EFixtureControllerSupervisor]
        val allocated = OAuthE2EFixtureControlServer
          .resource(
            controllerConfig,
            Some(repository),
            transactor,
            Some(new PostgresOAuthE2EFixtureBackendCanceller(database)),
            Some(new RecordingTerminationPublisher),
            (value, _) => {
              supervisor = Some(value)
              OAuthE2EFixtureControlHandler.unavailable
            },
            binder
          )
          .allocated
          .unsafeRunSync()

        (for {
          stopFiber    <- supervisor.getOrElse(throw new AssertionError("supervisor was not projected")).stopControllerAndDestroyRun.start
          _            <- releaseStarted.get
          releaseFiber <- allocated._2.start
          _            <- IO.cede *> IO.cede
          _            <- allowRelease.complete(())
          _            <- stopFiber.joinWithNever
          _            <- releaseFiber.joinWithNever
        } yield ()).unsafeRunSync()
        val lease = Await.result(database.run(repository.ledger), 5.seconds).map(_.controllerLease)

        (binder.unbindCount, binder.cancelCount, lease.exists(_.isInstanceOf[Terminal])) shouldEqual ((1, 0, true))
    }

    "publish the exact external signal and complete external stop when listener unbind hangs" in isolatedFixtureTables("unbind-hang") {
      (database, repository) =>
        val (publisher, releaseResult) = exerciseFailedQuarantine(database, repository, new HangingReleaseBinder)

        publisher.signals must haveSize(1)
        releaseResult.left.toOption shouldEqual Some(
          OAuthE2EFixtureQuarantineFailed(
            OAuthE2EFixtureQuarantineOutcome(OAuthE2EFixtureQuarantineStepOutcome.TimedOut, OAuthE2EFixtureQuarantineStepOutcome.Completed)
          )
        )
        Await.result(database.run(repository.ledger), 5.seconds).map(_.controllerLease) must beSome(beAnInstanceOf[Terminal])
    }

    "publish the exact external signal, still terminalize, and preserve a listener unbind failure" in isolatedFixtureTables("unbind-failure") {
      (database, repository) =>
        val (publisher, releaseResult) = exerciseFailedQuarantine(database, repository, new FailingReleaseBinder)

        publisher.signals must haveSize(1)
        releaseResult.left.toOption shouldEqual Some(
          OAuthE2EFixtureQuarantineFailed(
            OAuthE2EFixtureQuarantineOutcome(OAuthE2EFixtureQuarantineStepOutcome.Failed, OAuthE2EFixtureQuarantineStepOutcome.Completed)
          )
        )
        Await.result(database.run(repository.ledger), 5.seconds).map(_.controllerLease) must beSome(beAnInstanceOf[Terminal])
    }

    "publish the exact external signal and complete external stop when lease terminalization hangs" in isolatedFixtureTables("terminal-hang") {
      (database, delegate) =>
        val terminalizationStarted = Promise[Unit]()
        val allowTerminalization   = Promise[Unit]()
        val repository = new DelegatingFixtureRepository(
          delegate,
          controllerId =>
            DBIO.successful { terminalizationStarted.trySuccess(()); () } >>
              DBIO.from(allowTerminalization.future) >> delegate.markControllerLost(controllerId)
        )
        val (publisher, releaseResult) = exerciseFailedQuarantine(database, repository, new RecordingBinder)

        Await.result(terminalizationStarted.future, 2.seconds)
        publisher.signals must haveSize(1)
        releaseResult.left.toOption shouldEqual Some(
          OAuthE2EFixtureQuarantineFailed(
            OAuthE2EFixtureQuarantineOutcome(OAuthE2EFixtureQuarantineStepOutcome.Completed, OAuthE2EFixtureQuarantineStepOutcome.TimedOut)
          )
        )
        Await.result(database.run(delegate.ledger), 5.seconds).map(_.controllerLease) must beSome(beAnInstanceOf[Claimed])
        allowTerminalization.trySuccess(()) should beTrue
        awaitTerminalLease(database, delegate)
        Await.result(database.run(delegate.ledger), 5.seconds).map(_.controllerLease) must beSome(beAnInstanceOf[Terminal])
    }

    "publish the exact external signal and preserve a lease terminalization failure" in isolatedFixtureTables("terminal-failure") {
      (database, delegate) =>
        val repository = new DelegatingFixtureRepository(delegate, _ => DBIO.failed(new IllegalStateException("synthetic terminalization failure")))
        val (publisher, releaseResult) = exerciseFailedQuarantine(database, repository, new RecordingBinder)

        publisher.signals must haveSize(1)
        releaseResult.left.toOption shouldEqual Some(
          OAuthE2EFixtureQuarantineFailed(
            OAuthE2EFixtureQuarantineOutcome(OAuthE2EFixtureQuarantineStepOutcome.Completed, OAuthE2EFixtureQuarantineStepOutcome.Failed)
          )
        )
        Await.result(database.run(delegate.ledger), 5.seconds).map(_.controllerLease) must beSome(beAnInstanceOf[Claimed])
    }

    "bound a hanging termination publisher so the external watchdog can stop the disposable process" in isolatedFixtureTables("publish-hang") {
      (database, repository) =>
        val publisher     = new HangingTerminationPublisher
        val releaseResult = exercisePublisherFailure(database, repository, publisher)

        publisher.started.get.timeout(2500.millis).unsafeRunSync()
        releaseResult.left.toOption.map(_.getMessage) shouldEqual Some("Fixture termination signal publication timed out")
    }

    "redact a termination publisher failure while preserving the external watchdog handoff" in isolatedFixtureTables("publish-failure") {
      (database, repository) =>
        val releaseResult = exercisePublisherFailure(database, repository, new FailingTerminationPublisher)

        releaseResult.left.toOption.map(_.getMessage) shouldEqual Some("Fixture termination signal publication failed")
        releaseResult.left.toOption.map(_.getMessage.contains("publisher-secret-sentinel")) shouldEqual Some(false)
    }

    "initialize a time-only replica without binding a listener" in isolatedFixtureTables("replica") { (database, repository) =>
      val binder = new RecordingBinder

      serverResource(replicaConfig, Some(repository), new PostgresIOTransactor(database), database, binder).use(_ => IO.unit).unsafeRunSync()

      binder.binds should beEmpty
      binder.unbindCount shouldEqual 0
      Await.result(database.run(repository.ledger), 5.seconds).map(_.controllerLease) shouldEqual Some(Unclaimed)
    }

    "remain inert when both fixture capabilities are disabled" in isolatedFixtureTables("disabled") { (database, _) =>
      val binder = new RecordingBinder

      serverResource(disabledConfig, None, new PostgresIOTransactor(database), database, binder).use(_ => IO.unit).unsafeRunSync()

      binder.binds should beEmpty
      fixtureTableCount(database) shouldEqual 0
    }
  }

  step(Await.result(actorSystem.terminate(), 10.seconds))

  private def serverResource(
    config: OAuthE2EFixtureConfig,
    repository: Option[OAuthE2EFixtureRepository],
    transactor: PostgresIOTransactor,
    database: Database,
    binder: OAuthE2EFixtureControlBinder
  ): Resource[IO, Unit] =
    OAuthE2EFixtureControlServer.resource(
      config,
      repository,
      transactor,
      Option.when(config.role == OAuthE2EFixtureConfig.FixtureRole.Controller)(new PostgresOAuthE2EFixtureBackendCanceller(database)),
      Option.when(config.role == OAuthE2EFixtureConfig.FixtureRole.Controller)(new RecordingTerminationPublisher),
      (_, _) => OAuthE2EFixtureControlHandler.unavailable,
      binder
    )

  private final class RecordingBinder extends OAuthE2EFixtureControlBinder {
    val binds: ArrayBuffer[(String, Int, Route)] = ArrayBuffer.empty[(String, Int, Route)]
    @volatile var unbindCount                    = 0

    override def bind(
      interface: String,
      port: Int,
      route: Route
    ): Resource[IO, Unit] =
      Resource.make(IO { binds += ((interface, port, route)); () })(_ => IO(unbindCount += 1))
  }

  private final class RecordingTerminationPublisher extends OAuthE2EFixtureTerminationPublisher {
    val signals: ArrayBuffer[OAuthE2EFixtureTerminationRequired] = ArrayBuffer.empty
    val published: Deferred[IO, Unit]                            = Deferred[IO, Unit].unsafeRunSync()

    override def publish(signal: OAuthE2EFixtureTerminationRequired): IO[Unit] =
      IO(signals += signal) *> published.complete(()).void
  }

  private final class HangingTerminationPublisher extends OAuthE2EFixtureTerminationPublisher {
    val started: Deferred[IO, Unit] = Deferred[IO, Unit].unsafeRunSync()

    override def publish(signal: OAuthE2EFixtureTerminationRequired): IO[Unit] =
      started.complete(()).void *> IO.never
  }

  private final class FailingTerminationPublisher extends OAuthE2EFixtureTerminationPublisher {
    override def publish(signal: OAuthE2EFixtureTerminationRequired): IO[Unit] =
      IO.raiseError(new IllegalStateException("publisher-secret-sentinel"))
  }

  private final class BlockingReleaseBinder(releaseStarted: Deferred[IO, Unit], allowRelease: Deferred[IO, Unit])
      extends OAuthE2EFixtureControlBinder {
    @volatile var unbindCount = 0
    @volatile var cancelCount = 0

    override def bind(
      interface: String,
      port: Int,
      route: Route
    ): Resource[IO, Unit] =
      Resource.make(IO.unit)(_ => (releaseStarted.complete(()).void *> allowRelease.get *> IO(unbindCount += 1)).onCancel(IO(cancelCount += 1)))
  }

  private final class HangingReleaseBinder extends OAuthE2EFixtureControlBinder {
    override def bind(
      interface: String,
      port: Int,
      route: Route
    ): Resource[IO, Unit] = Resource.make(IO.unit)(_ => IO.never)
  }

  private final class FailingReleaseBinder extends OAuthE2EFixtureControlBinder {
    override def bind(
      interface: String,
      port: Int,
      route: Route
    ): Resource[IO, Unit] =
      Resource.make(IO.unit)(_ => IO.raiseError(new IllegalStateException("synthetic listener unbind failure")))
  }

  private final class DelegatingFixtureRepository(
    delegate: OAuthE2EFixtureRepository,
    controllerLoss: ControllerInstanceId => DBIO[ControllerLossResult])
      extends OAuthE2EFixtureRepository {
    override def initialize(config: OAuthE2EFixtureConfig, controllerInstanceId: Option[ControllerInstanceId]): DBIO[FixtureInitializationResult] =
      delegate.initialize(config, controllerInstanceId)
    override def ledger: DBIO[Option[FixtureRunLedger]]                                                     = delegate.ledger
    override def ownershipRows: DBIO[Seq[FixtureOwnership]]                                                 = delegate.ownershipRows
    override def markControllerLost(controllerInstanceId: ControllerInstanceId): DBIO[ControllerLossResult] = controllerLoss(controllerInstanceId)
    override private[fixture] def setupProgram[A](
      transaction: OAuthE2EFixtureStatementBoundary,
      runId: UUID,
      idempotencyKeyDigest: IdempotencyKeyDigest,
      attemptMatches: Boolean,
      ownership: Vector[FixtureOwnership]
    )(
      onApplied: => OAuthE2EFixtureCommandProgram[A]
    ): OAuthE2EFixtureCommandProgram[FixtureSetupPersistenceResult[A]] =
      delegate.setupProgram(transaction, runId, idempotencyKeyDigest, attemptMatches, ownership)(onApplied)
    override def admitNonCleanupCommand[A](
      category: FixtureAuditCommandCategory,
      idempotencyKeyDigest: IdempotencyKeyDigest
    )(
      onApplied: (Long, Int) => DBIO[A]
    ): DBIO[NonCleanupCommandAdmissionResult[A]] = delegate.admitNonCleanupCommand(category, idempotencyKeyDigest)(onApplied)
    override private[fixture] def admitNonCleanupCommandProgram[A](
      transaction: OAuthE2EFixtureStatementBoundary,
      category: FixtureAuditCommandCategory,
      idempotencyKeyDigest: IdempotencyKeyDigest
    )(
      onApplied: (Long, Int) => OAuthE2EFixtureCommandProgram[A]
    ): OAuthE2EFixtureCommandProgram[NonCleanupCommandAdmissionResult[A]] =
      delegate.admitNonCleanupCommandProgram(transaction, category, idempotencyKeyDigest)(onApplied)
    override def advance300(idempotencyKeyDigest: IdempotencyKeyDigest): DBIO[AdvanceResult] = delegate.advance300(idempotencyKeyDigest)
    override private[fixture] def advance300Program(transaction: OAuthE2EFixtureStatementBoundary, idempotencyKeyDigest: IdempotencyKeyDigest)
      : OAuthE2EFixtureCommandProgram[AdvanceResult] = delegate.advance300Program(transaction, idempotencyKeyDigest)
    override def transitionCleanupStep(
      step: CleanupStep,
      expected: CleanupStepState,
      target: CleanupStepState,
      idempotencyKeyDigest: IdempotencyKeyDigest
    ): DBIO[CleanupTransitionResult] = delegate.transitionCleanupStep(step, expected, target, idempotencyKeyDigest)
    override def beginCleanup(runId: UUID, idempotencyKeyDigest: IdempotencyKeyDigest): DBIO[OAuthE2EFixtureDomain.CleanupStartResult] =
      delegate.beginCleanup(runId, idempotencyKeyDigest)
    override private[fixture] def beginCleanupProgram(
      transaction: OAuthE2EFixtureStatementBoundary,
      runId: UUID,
      idempotencyKeyDigest: IdempotencyKeyDigest
    ): OAuthE2EFixtureCommandProgram[OAuthE2EFixtureDomain.CleanupStartResult] =
      delegate.beginCleanupProgram(transaction, runId, idempotencyKeyDigest)
    override private[fixture] def cleanupOwnershipForRunProgram(
      transaction: OAuthE2EFixtureStatementBoundary,
      runId: UUID,
      alias: OAuthE2EFixtureDomain.FixtureAlias,
      idempotencyKeyDigest: IdempotencyKeyDigest
    ): OAuthE2EFixtureCommandProgram[OAuthE2EFixtureDomain.FixtureOwnership] =
      delegate.cleanupOwnershipForRunProgram(transaction, runId, alias, idempotencyKeyDigest)
    override private[fixture] def transitionCleanupStepForRunProgram(
      transaction: OAuthE2EFixtureStatementBoundary,
      runId: UUID,
      step: CleanupStep,
      expected: CleanupStepState,
      target: CleanupStepState,
      idempotencyKeyDigest: IdempotencyKeyDigest
    ): OAuthE2EFixtureCommandProgram[CleanupTransitionResult] =
      delegate.transitionCleanupStepForRunProgram(transaction, runId, step, expected, target, idempotencyKeyDigest)
    override def completeCleanup(runId: UUID, idempotencyKeyDigest: IdempotencyKeyDigest): DBIO[OAuthE2EFixtureDomain.CleanupCompletionResult] =
      delegate.completeCleanup(runId, idempotencyKeyDigest)
    override private[fixture] def completeCleanupProgram(
      transaction: OAuthE2EFixtureStatementBoundary,
      runId: UUID,
      idempotencyKeyDigest: IdempotencyKeyDigest
    ): OAuthE2EFixtureCommandProgram[OAuthE2EFixtureDomain.CleanupCompletionResult] =
      delegate.completeCleanupProgram(transaction, runId, idempotencyKeyDigest)
    override private[fixture] def statusForRunProgram(
      transaction: OAuthE2EFixtureStatementBoundary,
      runId: UUID,
      idempotencyKeyDigest: IdempotencyKeyDigest
    ): OAuthE2EFixtureCommandProgram[OAuthE2EFixtureDomain.FixtureStatusResult] =
      delegate.statusForRunProgram(transaction, runId, idempotencyKeyDigest)
    override def auditRows: DBIO[Seq[FixtureAuditRow]]     = delegate.auditRows
    override def authoritativeNow: DBIO[java.time.Instant] = delegate.authoritativeNow
  }

  private def exerciseFailedQuarantine(
    database: Database,
    repository: OAuthE2EFixtureRepository,
    binder: OAuthE2EFixtureControlBinder
  ): (RecordingTerminationPublisher, Either[Throwable, Unit]) = {
    val publisher            = new RecordingTerminationPublisher
    val transactor           = new PostgresIOTransactor(database)
    @volatile var supervisor = Option.empty[OAuthE2EFixtureControllerSupervisor]
    val allocated = OAuthE2EFixtureControlServer
      .resource(
        controllerConfig,
        Some(repository),
        transactor,
        Some(new PostgresOAuthE2EFixtureBackendCanceller(database)),
        Some(publisher),
        (value, _) => {
          supervisor = Some(value)
          OAuthE2EFixtureControlHandler.unavailable
        },
        binder
      )
      .allocated
      .unsafeRunSync()
    val marker = "oauth-e2e-fixture-command-00000000-0000-4000-8000-000000000522"
    val stopFiber =
      supervisor.getOrElse(throw new AssertionError("supervisor was not projected")).stopControllerAndDestroyRun(marker).start.unsafeRunSync()

    publisher.published.get.timeout(2500.millis).unsafeRunSync()
    val releaseResult = allocated._2.attempt.timeout(4.seconds).unsafeRunSync()
    stopFiber.joinWithNever.timeout(2.seconds).unsafeRunSync()
    publisher.signals shouldEqual Vector(OAuthE2EFixtureTerminationRequired(marker))
    publisher -> releaseResult
  }

  private def exercisePublisherFailure(
    database: Database,
    repository: OAuthE2EFixtureRepository,
    publisher: OAuthE2EFixtureTerminationPublisher
  ): Either[Throwable, Unit] = {
    val transactor           = new PostgresIOTransactor(database)
    @volatile var supervisor = Option.empty[OAuthE2EFixtureControllerSupervisor]
    val allocated = OAuthE2EFixtureControlServer
      .resource(
        controllerConfig,
        Some(repository),
        transactor,
        Some(new PostgresOAuthE2EFixtureBackendCanceller(database)),
        Some(publisher),
        (value, _) => {
          supervisor = Some(value)
          OAuthE2EFixtureControlHandler.unavailable
        },
        new RecordingBinder
      )
      .allocated
      .unsafeRunSync()
    val marker = "oauth-e2e-fixture-command-00000000-0000-4000-8000-000000000522"
    val stopFiber =
      supervisor.getOrElse(throw new AssertionError("supervisor was not projected")).stopControllerAndDestroyRun(marker).start.unsafeRunSync()

    val releaseResult = allocated._2.attempt.timeout(3500.millis).unsafeRunSync()
    stopFiber.joinWithNever.timeout(1.second).unsafeRunSync()
    releaseResult
  }

  private def awaitTerminalLease(database: Database, repository: OAuthE2EFixtureRepository): Unit = {
    val deadline = 3.seconds.fromNow
    var terminal = false
    while (deadline.hasTimeLeft() && !terminal)
      terminal = Await.result(database.run(repository.ledger), 1.second).exists(_.controllerLease.isInstanceOf[Terminal])
    if (!terminal) throw new AssertionError("Fixture controller lease did not eventually become terminal")
  }

  private def isolatedFixtureTables[T](name: String)(body: (Database, OAuthE2EFixtureRepositoryImpl) => T): T = {
    val database   = testDatabase(name)
    val repository = new OAuthE2EFixtureRepositoryImpl()
    dropFixtureTables(database)
    try body(database, repository)
    finally {
      dropFixtureTables(database)
      database.close()
    }
  }

  private def dropFixtureTables(database: Database): Unit =
    Await.result(
      database.run(
        DBIO.seq(
          sqlu"drop table if exists oauth_e2e_fixture_ownership",
          sqlu"drop table if exists oauth_e2e_fixture_audit",
          sqlu"drop table if exists oauth_e2e_fixture_run"
        )
      ),
      5.seconds
    )

  private def fixtureTableCount(database: Database): Int =
    Await.result(
      database.run(
        sql"select count(*) from information_schema.tables where table_schema = 'public' and table_name in ('oauth_e2e_fixture_run', 'oauth_e2e_fixture_audit')"
          .as[Int]
          .head
      ),
      5.seconds
    )

  private def testDatabase(name: String): Database =
    Database.forURL(
      url = sys.env.getOrElse("TEST_DB_URL", "epoca"),
      user = sys.env.getOrElse("TEST_DB_USER", "postgres"),
      password = sys.env.getOrElse("TEST_DB_PASSWORD", "postgres"),
      driver = "org.postgresql.Driver",
      executor = AsyncExecutor(s"command-server-$name-${UUID.randomUUID()}", minThreads = 2, maxThreads = 2, queueSize = 20, maxConnections = 2),
      prop = new Properties()
    )

  private val applicationConfig = ConfigFactory
    .parseResources("oauth-e2e.conf")
    .withFallback(ConfigFactory.parseResources("application.conf"))
    .resolve(ConfigResolveOptions.defaults().setAllowUnresolved(true))
  private val signingKeyA  = encodedBytes(1)
  private val signingKeyB  = encodedBytes(33)
  private val controlToken = s"oauth-e2e-v1.${encodedBytes(65)}"

  private lazy val replicaRoot: Config = ConfigFactory
    .parseString(s"""
      app.environment = "oauth-e2e"
      db.url = "jdbc:postgresql://127.0.0.1:55422/oauth_e2e_repository"
      db.password = "oauth-e2e-command-database-secret"
      login.jwtSecret = "oauth-e2e-command-login-secret"
      oauth.enabled = true
      oauth.signing.activeKid = "oauth-a"
      oauth.signing.keys = [
        { kid = "oauth-a", material = "$signingKeyA", retired = false },
        { kid = "oauth-b", material = "$signingKeyB", retired = false }
      ]
      oauth.e2eFixture.timeEnabled = true
      oauth.e2eFixture.controlListenerEnabled = false
      oauth.e2eFixture.disposableAck = "oauth-e2e-disposable-v1"
      oauth.e2eFixture.deploymentTier = "disposable"
    """)
    .withFallback(applicationConfig)
    .resolve(ConfigResolveOptions.defaults().setAllowUnresolved(true))

  private lazy val controllerConfig: OAuthE2EFixtureConfig = requireConfig(
    ConfigFactory
      .parseString(s"""
        oauth.e2eFixture.controlListenerEnabled = true
        oauth.e2eFixture.listenerInterface = "127.0.0.1"
        oauth.e2eFixture.listenerPort = 19042
        oauth.e2eFixture.controlToken = "$controlToken"
      """)
      .withFallback(replicaRoot)
      .resolve(ConfigResolveOptions.defaults().setAllowUnresolved(true))
  )
  private lazy val replicaConfig: OAuthE2EFixtureConfig  = requireConfig(replicaRoot)
  private lazy val disabledConfig: OAuthE2EFixtureConfig = requireConfig(applicationConfig)

  private def requireConfig(config: Config): OAuthE2EFixtureConfig =
    OAuthE2EFixtureConfig.load(config).fold(error => throw new AssertionError(s"Invalid fixture config: $error"), identity)

  private def encodedBytes(start: Int): String =
    Base64.getUrlEncoder.withoutPadding().encodeToString(Array.tabulate[Byte](32)(index => (start + index).toByte))
}
