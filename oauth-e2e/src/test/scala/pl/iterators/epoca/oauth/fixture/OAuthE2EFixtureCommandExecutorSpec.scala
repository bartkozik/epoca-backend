package pl.iterators.epoca.oauth.fixture

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import com.typesafe.config.{Config, ConfigFactory, ConfigResolveOptions}
import org.specs2.mutable.SpecificationLike
import pl.iterators.epoca.oauth.fixture.OAuthE2EFixtureCommandExecutor.ExecutionResult
import pl.iterators.epoca.oauth.fixture.OAuthE2EFixtureControlProtocol.{ControlDeadline, ControlRequestContext}
import pl.iterators.epoca.oauth.fixture.OAuthE2EFixtureDomain.AdvanceResult.{Applied, Conflict, Idempotent}
import pl.iterators.epoca.oauth.fixture.OAuthE2EFixtureDomain.CleanupTransitionResult.{Applied => CleanupApplied}
import pl.iterators.epoca.oauth.fixture.OAuthE2EFixtureDomain.NonCleanupCommandAdmissionResult.{
  Applied => CommandApplied,
  BudgetExhausted => CommandBudgetReached
}
import pl.iterators.epoca.oauth.fixture.OAuthE2EFixtureDomain.{
  CleanupStep,
  CleanupStepState,
  ControllerInstanceId,
  FixtureAuditCommandCategory,
  IdempotencyKeyDigest
}
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import slick.util.AsyncExecutor

import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import java.util.{Base64, Properties, UUID}
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Promise}
import scala.util.Try

class OAuthE2EFixtureCommandExecutorSpec extends SpecificationLike {
  sequential

  private implicit val runtime: IORuntime   = IORuntime.global
  private implicit val ec: ExecutionContext = ExecutionContext.global

  "OAuth E2E fixture command executor" should {
    "pin every guarded statement to one PostgreSQL PID and apply bounded local timeouts" in withDatabases("pinned") { (database, _) =>
      val executor = fixtureExecutor(database)
      val admitted = System.nanoTime()

      val result = executor
        .execute(ControlDeadline.command(admitted)) { transaction =>
          for {
            first <- transaction.statement(
                       sql"select pg_backend_pid(), current_setting('statement_timeout'), current_setting('lock_timeout')"
                         .as[(Int, String, String)]
                         .head
                     )
            second <- transaction.statement(sql"select pg_backend_pid()".as[Int].head)
          } yield (first, second)
        }
        .unsafeRunSync()

      result must beLike { case ExecutionResult.Succeeded((first, second), capturedPid) =>
        first._1 shouldEqual capturedPid
        second shouldEqual capturedPid
        durationMillis(first._2) must beBetween(1L, 8000L)
        durationMillis(first._3) must beBetween(1L, 1000L)
      }
    }

    "refuse persistence when the request active deadline already expired" in withDatabases("expired") { (database, _) =>
      @volatile var openedPersistence = false
      val executor                    = fixtureExecutor(database, nanoTime = () => 101L)
      val deadline                    = ControlDeadline.testing(admittedAtNanos = 0L, activeNanos = 100L, totalNanos = 200L)

      val result = executor
        .execute(deadline)(transaction => transaction.statement(DBIO.successful { openedPersistence = true; () }))
        .unsafeRunSync()

      result shouldEqual ExecutionResult.TimedOut(settled = true)
      openedPersistence should beFalse
    }

    "recheck the cancellation generation immediately before opening persistence" in withDatabases("cancelled-generation") { (database, _) =>
      @volatile var openedPersistence = false
      val context                     = ControlRequestContext(ControlDeadline.command(System.nanoTime()))
      val executor = new OAuthE2EFixtureCommandExecutor(
        new PostgresIOTransactor(database),
        new PostgresOAuthE2EFixtureBackendCanceller(database),
        OAuthE2EFixtureControllerSupervisor.noop,
        beforePersistenceOpen = _.invalidate
      )

      val result = executor
        .execute(context)(transaction => transaction.statement(DBIO.successful { openedPersistence = true; () }))
        .unsafeRunSync()

      result shouldEqual ExecutionResult.TimedOut(settled = true)
      openedPersistence should beFalse
    }

    "preserve the exact session marker when success reaches the boundary before it is observed after active expiry" in withDatabases("late-success") {
      (database, _) =>
        val clock     = new AtomicLong(0L)
        val stopCount = new AtomicInteger(0)
        val marker    = "oauth-e2e-fixture-command-command-observed-after-cutoff"
        val observed  = Promise[String]()
        val supervisor = new OAuthE2EFixtureControllerSupervisor {
          override def stopControllerAndDestroyRun: IO[Unit] = IO { stopCount.incrementAndGet(); () }
          override def stopControllerAndDestroyRun(sessionMarker: String): IO[Unit] =
            IO { observed.trySuccess(sessionMarker); stopCount.incrementAndGet(); () }
        }
        val deadline = ControlDeadline.testing(admittedAtNanos = 0L, activeNanos = 1000000000L, totalNanos = 2000000000L)
        val executor = new OAuthE2EFixtureCommandExecutor(
          new PostgresIOTransactor(database),
          new PostgresOAuthE2EFixtureBackendCanceller(database),
          supervisor,
          nanoTime = () => clock.get(),
          sessionMarker = () => marker
        )

        val result = executor
          .execute(deadline) { transaction =>
            transaction.statement(SimpleDBIO[String] { _ => clock.set(1000000000L); "late" })
          }
          .unsafeRunSync()

        (result, stopCount.get(), Await.result(observed.future, 1.second)) shouldEqual
          ((ExecutionResult.TimedOut(settled = true), 1, marker))
    }

    "keep the real PostgreSQL child attached until outer cancellation reaches definitive lock-timeout rollback" in {
      val primary      = database("outer-cancel-primary", maximumConnections = 2)
      val cancelDb     = database("outer-cancel-control", maximumConnections = 1)
      val observer     = database("outer-cancel-observer", maximumConnections = 2)
      val blockerLease = Promise[Unit]()
      val blockerReady = Promise[Int]()
      val observedPid  = Promise[Int]()
      val marker       = "oauth-e2e-fixture-command-command-outer-cancel"
      val handoff      = Promise[String]()
      val supervisor = new OAuthE2EFixtureControllerSupervisor {
        override def stopControllerAndDestroyRun: IO[Unit] = IO.raiseError(new AssertionError("fatal handoff lost its exact session marker"))
        override def stopControllerAndDestroyRun(sessionMarker: String): IO[Unit] =
          IO { handoff.trySuccess(sessionMarker); () }
      }

      try {
        prepareProbe(primary)
        val lockHolder = observer.run((for {
          pid <- sql"select pg_backend_pid()".as[Int].head
          _   <- sql"select probe_value from oauth_e2e_fixture_command_probe where singleton_key = 1 for update".as[Int].head
          _   <- DBIO.successful { blockerReady.trySuccess(pid); () }
          _   <- DBIO.from(blockerLease.future)
        } yield ()).transactionally)
        val blockerPid = Await.result(blockerReady.future, 5.seconds)
        val executor = new OAuthE2EFixtureCommandExecutor(
          new PostgresIOTransactor(primary),
          new PostgresOAuthE2EFixtureBackendCanceller(cancelDb),
          supervisor,
          backendPidObserved = pid => { observedPid.trySuccess(pid); () },
          sessionMarker = () => marker
        )
        val deadline = ControlDeadline.testing(System.nanoTime(), activeNanos = 3000000000L, totalNanos = 4000000000L)
        val outer = executor
          .execute(deadline)(transaction =>
            transaction.statement(sqlu"update oauth_e2e_fixture_command_probe set probe_value = probe_value + 1 where singleton_key = 1")
          )
          .start
          .unsafeRunSync()
        val pid = Await.result(observedPid.future, 5.seconds)
        awaitSessionCount(observer, marker, expected = 1)
        awaitExactPostgresBlock(observer, blockerPid, pid)

        val cancellation = outer.cancel.start.unsafeRunSync()
        cancellation.joinWithNever.map(_ => true).timeoutTo(50.millis, IO.pure(false)).unsafeRunSync() should beFalse
        cancellation.joinWithNever.timeout(2.seconds).unsafeRunSync()
        awaitSessionCount(observer, marker, expected = 0)
        handoff.future.value should beNone
        blockerLease.trySuccess(()) should beTrue
        Await.result(lockHolder, 2.seconds)

        Await.result(observer.run(probeValue), 2.seconds) shouldEqual 0
      } finally {
        val _ = blockerLease.trySuccess(())
        val _ = Try(Await.result(observer.run(sqlu"drop table if exists oauth_e2e_fixture_command_probe"), 2.seconds))
        primary.close()
        cancelDb.close()
        observer.close()
      }
    }

    "bound a missing backend PID by the absolute deadline and complete the exact fatal handoff" in {
      val primary   = database("missing-pid-primary", maximumConnections = 1)
      val cancelDb  = database("missing-pid-cancel", maximumConnections = 1)
      val poolLease = Promise[Unit]()
      val poolReady = Promise[Unit]()
      val handoff   = Promise[String]()
      val marker    = "oauth-e2e-fixture-command-command-missing-pid"
      val occupiedPool = primary.run(
        sql"select pg_backend_pid()"
          .as[Int]
          .head
          .flatMap(_ => DBIO.successful { poolReady.trySuccess(()); () } >> DBIO.from(poolLease.future))
          .withPinnedSession
      )
      val supervisor = new OAuthE2EFixtureControllerSupervisor {
        override def stopControllerAndDestroyRun: IO[Unit] = IO.raiseError(new AssertionError("fatal handoff lost its exact session marker"))
        override def stopControllerAndDestroyRun(sessionMarker: String): IO[Unit] = IO { handoff.trySuccess(sessionMarker); () }
      }

      try {
        Await.result(poolReady.future, 5.seconds)
        val deadline = ControlDeadline.testing(System.nanoTime(), activeNanos = 100000000L, totalNanos = 300000000L)
        val result = new OAuthE2EFixtureCommandExecutor(
          new PostgresIOTransactor(primary),
          new PostgresOAuthE2EFixtureBackendCanceller(cancelDb),
          supervisor,
          sessionMarker = () => marker
        ).execute(deadline)(transaction => transaction.statement(sql"select 1".as[Int].head)).unsafeToFuture()

        Await.result(result, 1.second) shouldEqual ExecutionResult.TimedOut(settled = false)
        Await.result(handoff.future, 1.second) shouldEqual marker
      } finally {
        val _ = poolLease.trySuccess(())
        val _ = Await.ready(occupiedPool, 2.seconds)
        primary.close()
        cancelDb.close()
      }
    }

    "reject a hidden composite statement before its second SQL boundary can mutate after the active cutoff" in withDatabases("hidden-composite") {
      (database, _) =>
        prepareProbe(database)
        val clock    = new AtomicLong(0L)
        val deadline = ControlDeadline.testing(admittedAtNanos = 0L, activeNanos = 1000000000L, totalNanos = 2000000000L)
        val result = fixtureExecutor(database, nanoTime = () => clock.get())
          .execute(deadline) { transaction =>
            val first = sql"select 1".as[Int].head.map { value =>
              clock.set(1000000000L)
              value
            }
            val composite = first >> sqlu"update oauth_e2e_fixture_command_probe set probe_value = probe_value + 1 where singleton_key = 1"
            transaction.statement(composite)
          }
          .unsafeRunSync()

        result must beLike { case ExecutionResult.Failed(error) =>
          error.getClass.getSimpleName must contain("CompositeFixtureStatementRejected")
        }
        Await.result(database.run(probeValue), 2.seconds) shouldEqual 0
    }

    "apply the separate 10-second statement and 2-second lock caps for cleanup" in withDatabases("cleanup-limits") { (database, _) =>
      val result = fixtureExecutor(database)
        .executeCleanup(ControlDeadline.cleanup(System.nanoTime())) { transaction =>
          transaction.statement(sql"select current_setting('statement_timeout'), current_setting('lock_timeout')".as[(String, String)].head)
        }
        .unsafeRunSync()

      result must beLike { case ExecutionResult.Succeeded((statementTimeout, lockTimeout), _) =>
        durationMillis(statementTimeout) must beBetween(1L, 10000L)
        durationMillis(lockTimeout) must beBetween(1L, 2000L)
      }
    }

    "settle a real held-lock timeout before returning and leave no delayed mutation" in withDatabases("held-lock") { (database, blockerDatabase) =>
      prepareProbe(database)
      val acquired    = Promise[Int]()
      val release     = Promise[Unit]()
      val observedPid = Promise[Int]()
      val holder = blockerDatabase.run((for {
        pid <- sql"select pg_backend_pid()".as[Int].head
        _   <- sql"select probe_value from oauth_e2e_fixture_command_probe where singleton_key = 1 for update".as[Int].head
        _   <- DBIO.successful { acquired.trySuccess(pid); () }
        _   <- DBIO.from(release.future)
      } yield ()).transactionally)
      val blockerPid = Await.result(acquired.future, 5.seconds)

      val admitted = System.nanoTime()
      val deadline = ControlDeadline.testing(admitted, activeNanos = 1500000000L, totalNanos = 2500000000L)
      val resultFuture = fixtureExecutor(
        database,
        backendPidObserved = pid => {
          val _ = observedPid.trySuccess(pid)
          ()
        }
      )
        .execute(deadline) { transaction =>
          transaction.statement(
            sql"select probe_value from oauth_e2e_fixture_command_probe where singleton_key = 1 for update".as[Int].head
          ) >> transaction.statement(sqlu"update oauth_e2e_fixture_command_probe set probe_value = probe_value + 1 where singleton_key = 1")
        }
        .unsafeToFuture()

      val waitingPid = Await.result(observedPid.future, 5.seconds)
      waitingPid must_!= blockerPid
      awaitExactPostgresBlock(database, blockerPid, waitingPid)
      val result = Await.result(resultFuture, 4.seconds)
      result must beAnInstanceOf[ExecutionResult.TimedOut]
      release.trySuccess(()) should beTrue
      Await.result(holder, 5.seconds)
      Await.result(database.run(probeValue), 5.seconds) shouldEqual 0
    }

    "cancel through a separate PostgreSQL backend rather than the pinned command session" in withDatabases("cancel-connection") {
      (database, cancelDatabase) =>
        val marker    = "oauth-e2e-fixture-command-command-separate-cancel"
        val targetPid = Promise[Int]()
        val pending = database.run(
          sql"select set_config('application_name', $marker, true), pg_backend_pid()"
            .as[(String, Int)]
            .head
            .flatMap { case (_, pid) =>
              DBIO.successful { targetPid.trySuccess(pid); () } >> sql"select pg_sleep(5)".as[Unit].head
            }
            .transactionally
            .withPinnedSession
        )
        val pinnedPid    = Await.result(targetPid.future, 5.seconds)
        val cancellation = new PostgresOAuthE2EFixtureBackendCanceller(cancelDatabase).cancelBackend(pinnedPid, marker).unsafeRunSync()

        cancellation.cancelled should beTrue
        cancellation.cancellerPid must_!= pinnedPid
        Await.ready(pending, 5.seconds).value.flatMap(_.failed.toOption) must beSome
    }

    "cancel a stuck pinned command through a dedicated pool and reject a late reused-session marker" in {
      val primary   = database("dedicated-cancel-primary", maximumConnections = 1)
      val cancelDb  = database("dedicated-cancel-control", maximumConnections = 1)
      val canceller = new PostgresOAuthE2EFixtureBackendCanceller(cancelDb)
      val observed  = Promise[Int]()
      val marker    = "oauth-e2e-fixture-command-command-dedicated-cancel"
      try {
        val deadline = ControlDeadline.testing(System.nanoTime(), activeNanos = 300000000L, totalNanos = 2000000000L)
        val result = new OAuthE2EFixtureCommandExecutor(
          new PostgresIOTransactor(primary),
          canceller,
          OAuthE2EFixtureControllerSupervisor.noop,
          backendPidObserved = pid => { observed.trySuccess(pid); () },
          sessionMarker = () => marker
        ).execute(deadline)(transaction => transaction.statement(sql"select pg_sleep(5)".as[Unit].head)).unsafeRunSync()
        val pid        = Await.result(observed.future, 5.seconds)
        val lateCancel = canceller.cancelBackend(pid, marker).unsafeRunSync()

        (result, lateCancel.matchedSession, lateCancel.cancelled) shouldEqual
          ((ExecutionResult.TimedOut(settled = true), false, false))
      } finally {
        primary.close()
        cancelDb.close()
      }
    }

    "bound cumulative statements by one absolute active deadline and roll the transaction back" in withDatabases("cumulative") { (database, _) =>
      prepareProbe(database)
      val admitted = System.nanoTime()
      val deadline = ControlDeadline.testing(admitted, activeNanos = 180000000L, totalNanos = 900000000L)

      val result = fixtureExecutor(database)
        .execute(deadline) { transaction =>
          transaction.statement(sql"select pg_sleep(0.12)".as[Unit].head) >>
            transaction.statement(sql"select pg_sleep(0.12)".as[Unit].head) >>
            transaction.statement(sqlu"update oauth_e2e_fixture_command_probe set probe_value = probe_value + 1 where singleton_key = 1")
        }
        .unsafeRunSync()

      result must beAnInstanceOf[ExecutionResult.TimedOut]
      Await.result(database.run(probeValue), 5.seconds) shouldEqual 0
    }

    "execute the real repository advance program with atomic applied, replay, and conflict semantics" in withFixtureRepository("repository-program") {
      (database, repository) =>
        val executor = fixtureExecutor(database)
        val first    = IdempotencyKeyDigest.sha256("command-repository-program-first")
        val other    = IdempotencyKeyDigest.sha256("command-repository-program-other")

        def execute(digest: IdempotencyKeyDigest) =
          executor
            .execute(ControlDeadline.command(System.nanoTime()))(transaction => repository.advance300Program(transaction, digest))
            .unsafeRunSync()

        execute(first) must beLike { case ExecutionResult.Succeeded(value, _) =>
          value shouldEqual Applied(0, 300, 2L, 1)
        }
        execute(first) must beLike { case ExecutionResult.Succeeded(value, _) =>
          value shouldEqual Idempotent(300, 2L, 1)
        }
        execute(other) must beLike { case ExecutionResult.Succeeded(value, _) =>
          value shouldEqual Conflict(300, 2L, 1)
        }
        Await.result(database.run(repository.auditRows), 5.seconds) must haveSize(1)
    }

    "admit the 32nd real repository command and reject the 33rd while cleanup remains outside admission" in withFixtureRepository(
      "repository-budget"
    ) { (database, repository) =>
      (1 to 31).foreach { command =>
        Await.result(
          database.run(
            repository.admitNonCleanupCommand(
              FixtureAuditCommandCategory.Status,
              IdempotencyKeyDigest.sha256(s"command-repository-program-seed-$command")
            )((_, _) => DBIO.successful(command))
          ),
          5.seconds
        ) must beAnInstanceOf[CommandApplied[Int]]
      }

      val executor = fixtureExecutor(database)
      val command32 = executor
        .execute(ControlDeadline.command(System.nanoTime()))(transaction =>
          repository.admitNonCleanupCommandProgram(
            transaction,
            FixtureAuditCommandCategory.Status,
            IdempotencyKeyDigest.sha256("command-repository-program-command-32")
          )((_, _) => transaction.pure(32))
        )
        .unsafeRunSync()
      val command33 = executor
        .execute(ControlDeadline.command(System.nanoTime()))(transaction =>
          repository.admitNonCleanupCommandProgram(
            transaction,
            FixtureAuditCommandCategory.Status,
            IdempotencyKeyDigest.sha256("command-repository-program-command-33")
          )((_, _) => transaction.pure(33))
        )
        .unsafeRunSync()

      command32 must beLike { case ExecutionResult.Succeeded(value, _) =>
        value shouldEqual CommandApplied(32, 33L, 32)
      }
      command33 must beLike { case ExecutionResult.Succeeded(value, _) =>
        value shouldEqual CommandBudgetReached(0, 33L, 32)
      }
      Await.result(
        database.run(
          repository.transitionCleanupStep(
            CleanupStep.GeneralResources,
            CleanupStepState.Pending,
            CleanupStepState.InProgress,
            IdempotencyKeyDigest.sha256("command-repository-program-cleanup-after-budget")
          )
        ),
        5.seconds
      ) shouldEqual CleanupApplied(CleanupStepState.InProgress, 34L)
      Await.result(database.run(repository.auditRows), 5.seconds) must haveSize(33)
    }

    "expire after budget and offset mutate at boundary five, then roll the whole repository transaction back before audit" in withFixtureRepository(
      "repository-boundary"
    ) { (database, repository) =>
      val clock               = new AtomicLong(0L)
      val completedStatements = new AtomicInteger(0)
      val executor = new OAuthE2EFixtureCommandExecutor(
        new PostgresIOTransactor(database),
        new PostgresOAuthE2EFixtureBackendCanceller(database),
        OAuthE2EFixtureControllerSupervisor.noop,
        nanoTime = () => clock.get(),
        statementCompleted = statement => {
          completedStatements.set(statement)
          if (statement == 5) clock.set(1000000000L)
        }
      )
      val deadline = ControlDeadline.testing(admittedAtNanos = 0L, activeNanos = 1000000000L, totalNanos = 2000000000L)

      val result = executor
        .execute(deadline)(transaction =>
          repository.advance300Program(transaction, IdempotencyKeyDigest.sha256("command-repository-program-deadline"))
        )
        .unsafeRunSync()

      result shouldEqual ExecutionResult.TimedOut(settled = true)
      completedStatements.get() shouldEqual 5
      val ledger = Await.result(database.run(repository.ledger), 5.seconds).getOrElse(throw new AssertionError("fixture ledger is absent"))
      (ledger.offsetSeconds, ledger.rowVersion, ledger.acceptedCommandCount) shouldEqual ((0, 1L, 0))
      Await.result(database.run(repository.auditRows), 5.seconds) should beEmpty
    }
  }

  private def fixtureExecutor(
    database: Database,
    nanoTime: () => Long = () => System.nanoTime(),
    backendPidObserved: Int => Unit = _ => (),
    supervisor: OAuthE2EFixtureControllerSupervisor = OAuthE2EFixtureControllerSupervisor.noop
  ): OAuthE2EFixtureCommandExecutor =
    new OAuthE2EFixtureCommandExecutor(
      new PostgresIOTransactor(database),
      new PostgresOAuthE2EFixtureBackendCanceller(database),
      supervisor,
      nanoTime,
      backendPidObserved
    )

  private def prepareProbe(database: Database): Unit =
    Await.result(
      database.run(
        DBIO.seq(
          sqlu"drop table if exists oauth_e2e_fixture_command_probe",
          sqlu"create table oauth_e2e_fixture_command_probe (singleton_key smallint primary key, probe_value integer not null)",
          sqlu"insert into oauth_e2e_fixture_command_probe (singleton_key, probe_value) values (1, 0)"
        )
      ),
      5.seconds
    )

  private val probeValue: DBIO[Int] =
    sql"select probe_value from oauth_e2e_fixture_command_probe where singleton_key = 1".as[Int].head

  private def durationMillis(value: String): Long =
    if (value.endsWith("ms")) value.stripSuffix("ms").toLong
    else if (value.endsWith("s")) BigDecimal(value.stripSuffix("s")).*(BigDecimal(1000)).toLong
    else throw new AssertionError(s"Unexpected PostgreSQL duration: $value")

  private def withDatabases[T](name: String)(body: (Database, Database) => T): T = {
    val primary = database(s"$name-primary")
    val blocker = database(s"$name-blocker")
    try body(primary, blocker)
    finally {
      val _ = Await.result(primary.run(sqlu"drop table if exists oauth_e2e_fixture_command_probe"), 5.seconds)
      primary.close()
      blocker.close()
    }
  }

  private def withFixtureRepository[T](name: String)(body: (Database, OAuthE2EFixtureRepositoryImpl) => T): T = {
    val fixtureDatabase = database(name)
    val repository      = new OAuthE2EFixtureRepositoryImpl()
    try {
      dropFixtureTables(fixtureDatabase)
      Await.result(fixtureDatabase.run(repository.initialize(controllerConfig, Some(ControllerInstanceId(UUID.randomUUID())))), 5.seconds)
      body(fixtureDatabase, repository)
    } finally {
      dropFixtureTables(fixtureDatabase)
      fixtureDatabase.close()
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

  private def awaitExactPostgresBlock(
    database: Database,
    blockerPid: Int,
    waitingPid: Int
  ): Unit = {
    val deadline = 1.second.fromNow
    var observed = false
    while (deadline.hasTimeLeft() && !observed)
      observed = Await
        .result(
          database.run(sql"""select $blockerPid = any(pg_catalog.pg_blocking_pids(activity.pid))
                 from pg_catalog.pg_stat_activity as activity
                 where activity.pid = $waitingPid and activity.wait_event_type = 'Lock'""".as[Boolean].headOption),
          1.second
        )
        .contains(true)
    if (!observed) throw new AssertionError(s"Expected PostgreSQL backend $waitingPid to be blocked by exact backend $blockerPid")
  }

  private def awaitSessionCount(
    database: Database,
    marker: String,
    expected: Int
  ): Unit = {
    val deadline = 3.seconds.fromNow
    var count    = -1
    while (deadline.hasTimeLeft() && count != expected)
      count =
        Await.result(database.run(sql"select count(*) from pg_catalog.pg_stat_activity where application_name = $marker".as[Int].head), 1.second)
    if (count != expected) throw new AssertionError(s"Expected exact PostgreSQL session count $expected for $marker, observed $count")
  }

  private def database(name: String, maximumConnections: Int = 3): Database =
    Database.forURL(
      url = sys.env.getOrElse("TEST_DB_URL", "epoca"),
      user = sys.env.getOrElse("TEST_DB_USER", "postgres"),
      password = sys.env.getOrElse("TEST_DB_PASSWORD", "postgres"),
      driver = "org.postgresql.Driver",
      executor = AsyncExecutor(
        s"$name-${UUID.randomUUID()}",
        minThreads = maximumConnections,
        maxThreads = maximumConnections,
        queueSize = 20,
        maxConnections = maximumConnections
      ),
      prop = new Properties()
    )

  private val applicationConfig = ConfigFactory
    .parseResources("oauth-e2e.conf")
    .withFallback(ConfigFactory.parseResources("application.conf"))
    .resolve(ConfigResolveOptions.defaults().setAllowUnresolved(true))
  private val signingKeyA  = encodedBytes(1)
  private val signingKeyB  = encodedBytes(33)
  private val controlToken = s"oauth-e2e-v1.${encodedBytes(65)}"

  private lazy val controllerConfig: OAuthE2EFixtureConfig = requireConfig(
    ConfigFactory
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
        oauth.e2eFixture.controlListenerEnabled = true
        oauth.e2eFixture.disposableAck = "oauth-e2e-disposable-v1"
        oauth.e2eFixture.deploymentTier = "disposable"
        oauth.e2eFixture.listenerInterface = "127.0.0.1"
        oauth.e2eFixture.listenerPort = 19000
        oauth.e2eFixture.controlToken = "$controlToken"
      """)
      .withFallback(applicationConfig)
      .resolve(ConfigResolveOptions.defaults().setAllowUnresolved(true))
  )

  private def requireConfig(config: Config): OAuthE2EFixtureConfig =
    OAuthE2EFixtureConfig.load(config) match {
      case Right(value) => value
      case Left(error)  => throw new AssertionError(s"Expected valid fixture test configuration, got $error")
    }

  private def encodedBytes(start: Int): String =
    Base64.getUrlEncoder.withoutPadding().encodeToString(Array.tabulate[Byte](32)(index => (start + index).toByte))
}
