package pl.iterators.epoca.oauth.fixture

import com.typesafe.config.{Config, ConfigFactory, ConfigResolveOptions}
import org.postgresql.util.PSQLException
import pl.iterators.epoca.oauth.fixture.OAuthE2EFixtureDomain.AdvanceResult.{Applied, CommandBudgetExhausted, Conflict, Idempotent}
import pl.iterators.epoca.oauth.fixture.OAuthE2EFixtureDomain.CleanupTransitionResult.{
  Applied => CleanupApplied,
  Conflict => CleanupConflict,
  Idempotent => CleanupIdempotent
}
import pl.iterators.epoca.oauth.fixture.OAuthE2EFixtureDomain.ControllerLeaseState.{Claimed, Terminal, Unclaimed}
import pl.iterators.epoca.oauth.fixture.OAuthE2EFixtureDomain.ControllerLossResult.{AlreadyTerminal, LeaseMismatch, MarkedTerminal}
import pl.iterators.epoca.oauth.fixture.OAuthE2EFixtureDomain.FixtureInitializationResult.{
  ControllerAlreadyClaimed,
  ControllerClaimed,
  ControllerConflict,
  ControllerTerminal,
  Disabled,
  TimeOnlyReady
}
import pl.iterators.epoca.oauth.fixture.OAuthE2EFixtureDomain.NonCleanupCommandAdmissionResult.{
  Applied => CommandApplied,
  BudgetExhausted => CommandBudgetReached,
  Conflict => CommandConflict,
  Idempotent => CommandIdempotent
}
import pl.iterators.epoca.oauth.fixture.OAuthE2EFixtureDomain.{
  CleanupStep,
  CleanupStepState,
  ControllerInstanceId,
  FixtureAuditCommandCategory,
  FixtureAuditOutcome,
  FixtureInitializationResult,
  FixtureRunState,
  IdempotencyKeyDigest
}
import pl.iterators.epoca.oauth.time.PostgresOAuthAuthoritativeTime
import pl.iterators.epoca.utils.PgRepositorySpec
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import slick.util.AsyncExecutor

import java.util.{Base64, Properties, UUID}
import scala.concurrent.duration._
import scala.concurrent.{Await, Future, Promise}

class OAuthE2EFixtureRepositorySpec extends PgRepositorySpec {
  sequential

  private val repository = new OAuthE2EFixtureRepositoryImpl()

  "OAuth E2E fixture repository" should {
    "leave no fixture table or ledger when both capabilities are disabled" in isolatedTables {
      awaitDb(repository.initialize(disabledConfig, None)) shouldEqual Disabled

      fixtureTableNames should beEmpty
    }

    "create the closed fixture schema and initial controller ledger only after the disposable guard passed" in isolatedTables {
      val controllerId = ControllerInstanceId(UUID.randomUUID())

      awaitDb(repository.initialize(controllerConfig, Some(controllerId))) shouldEqual ControllerClaimed

      val ledger = awaitDb(repository.ledger).getOrElse(throw new AssertionError("fixture ledger was not created"))
      ledger.runId should beNone
      ledger.runState shouldEqual FixtureRunState.AwaitingSetup
      ledger.offsetSeconds shouldEqual 0
      ledger.rowVersion shouldEqual 1L
      ledger.acceptedCommandCount shouldEqual 0
      ledger.controllerLease shouldEqual Claimed(controllerId)
      ledger.advanceIdempotencyKeyDigest should beNone
      ledger.cleanupSteps shouldEqual CleanupStep.values.map(_ -> CleanupStepState.Pending).toMap

      fixtureTableNames shouldEqual Seq("oauth_e2e_fixture_audit", "oauth_e2e_fixture_ownership", "oauth_e2e_fixture_run")
      runTableColumns shouldEqual expectedRunColumns
      auditTableColumns shouldEqual expectedAuditColumns
      awaitDb(sql"select count(*) from oauth_e2e_fixture_run".as[Int].head) shouldEqual 1
      awaitDb(sql"select count(*) from oauth_e2e_fixture_audit".as[Int].head) shouldEqual 0
    }

    "initialize a time-only replica without claiming the controller lease" in isolatedTables {
      awaitDb(repository.initialize(replicaConfig, None)) shouldEqual TimeOnlyReady

      val ledger = awaitDb(repository.ledger).getOrElse(throw new AssertionError("fixture ledger was not created"))
      ledger.controllerLease shouldEqual Unclaimed
      ledger.rowVersion shouldEqual 0L
      ledger.offsetSeconds shouldEqual 0
    }

    "allow one controller, make an exact same-instance retry idempotent, and reject a second controller" in isolatedTables {
      val firstController  = ControllerInstanceId(UUID.randomUUID())
      val secondController = ControllerInstanceId(UUID.randomUUID())

      awaitDb(repository.initialize(controllerConfig, Some(firstController))) shouldEqual ControllerClaimed
      awaitDb(repository.initialize(controllerConfig, Some(firstController))) shouldEqual ControllerAlreadyClaimed
      awaitDb(repository.initialize(controllerConfig, Some(secondController))) shouldEqual ControllerConflict

      val ledger = awaitDb(repository.ledger).getOrElse(throw new AssertionError("fixture ledger was not created"))
      ledger.controllerLease shouldEqual Claimed(firstController)
      ledger.rowVersion shouldEqual 1L
    }

    "make controller loss terminal and reject every in-run takeover" in isolatedTables {
      val firstController  = ControllerInstanceId(UUID.randomUUID())
      val secondController = ControllerInstanceId(UUID.randomUUID())

      awaitDb(repository.initialize(controllerConfig, Some(firstController))) shouldEqual ControllerClaimed
      awaitDb(repository.markControllerLost(secondController)) shouldEqual LeaseMismatch
      awaitDb(repository.markControllerLost(firstController)) shouldEqual MarkedTerminal
      awaitDb(repository.markControllerLost(firstController)) shouldEqual AlreadyTerminal
      awaitDb(repository.initialize(controllerConfig, Some(firstController))) shouldEqual ControllerTerminal
      awaitDb(repository.initialize(controllerConfig, Some(secondController))) shouldEqual ControllerTerminal
      awaitDb(repository.initialize(replicaConfig, None)) shouldEqual ControllerTerminal

      val ledger = awaitDb(repository.ledger).getOrElse(throw new AssertionError("fixture ledger was not created"))
      ledger.controllerLease shouldEqual Terminal(firstController)
      ledger.rowVersion shouldEqual 2L
    }

    "enforce the singleton run ledger in PostgreSQL" in isolatedTables {
      awaitDb(repository.initialize(replicaConfig, None)) shouldEqual TimeOnlyReady

      {
        awaitDb(sqlu"""insert into oauth_e2e_fixture_run (
                    singleton_key, run_state, offset_seconds, row_version, controller_lease_state,
                    accepted_command_count, oauth027_revocation_cleanup_state, oauth027_resource_cleanup_state,
                    oauth028_revocation_cleanup_state, oauth028_resource_cleanup_state,
                    oauth029_revocation_cleanup_state, oauth029_resource_cleanup_state,
                    general_revocation_cleanup_state, general_resource_cleanup_state
                  ) values (
                    2, 'awaiting-setup', 0, 0, 'unclaimed', 0, 'pending', 'pending',
                    'pending', 'pending', 'pending', 'pending', 'pending', 'pending'
                  )""")
      } must throwA[PSQLException]

      awaitDb(sql"select count(*) from oauth_e2e_fixture_run".as[Int].head) shouldEqual 1
    }

    "advance the stored offset exactly once from zero to 300 with closed replay and conflict semantics" in isolatedTables {
      val controllerId = ControllerInstanceId(UUID.randomUUID())
      val firstDigest  = IdempotencyKeyDigest.sha256("repository-advance-first")
      val otherDigest  = IdempotencyKeyDigest.sha256("repository-advance-other")
      awaitDb(repository.initialize(controllerConfig, Some(controllerId))) shouldEqual ControllerClaimed

      awaitDb(repository.advance300(firstDigest)) shouldEqual Applied(
        offsetBeforeSeconds = 0,
        offsetAfterSeconds = 300,
        rowVersion = 2L,
        acceptedCommandCount = 1
      )
      awaitDb(repository.advance300(firstDigest)) shouldEqual Idempotent(offsetSeconds = 300, rowVersion = 2L, acceptedCommandCount = 1)
      awaitDb(repository.advance300(otherDigest)) shouldEqual Conflict(offsetSeconds = 300, rowVersion = 2L, acceptedCommandCount = 1)

      val ledger = awaitDb(repository.ledger).getOrElse(throw new AssertionError("fixture ledger was not created"))
      ledger.offsetSeconds shouldEqual 300
      ledger.advanceIdempotencyKeyDigest shouldEqual Some(firstDigest.value)
      ledger.rowVersion shouldEqual 2L
      ledger.acceptedCommandCount shouldEqual 1

      val audit = awaitDb(repository.auditRows)
      audit.map(_.commandCategory) shouldEqual Seq(FixtureAuditCommandCategory.Advance300Seconds)
      audit.map(_.outcome) shouldEqual Seq(FixtureAuditOutcome.Applied)
      audit.map(_.idempotencyKeyDigest) shouldEqual Seq(firstDigest)
    }

    "replay committed command 32 before the budget check and reject only a genuinely new command 33" in isolatedTables {
      val controllerId = ControllerInstanceId(UUID.randomUUID())
      val digest       = IdempotencyKeyDigest.sha256("repository-command-budget")
      val otherDigest  = IdempotencyKeyDigest.sha256("repository-command-budget-other")
      awaitDb(repository.initialize(controllerConfig, Some(controllerId))) shouldEqual ControllerClaimed
      awaitDb(sqlu"update oauth_e2e_fixture_run set accepted_command_count = 31, row_version = 31 where singleton_key = 1") shouldEqual 1

      awaitDb(repository.advance300(digest)) shouldEqual Applied(0, 300, rowVersion = 32L, acceptedCommandCount = 32)
      awaitDb(repository.advance300(digest)) shouldEqual Idempotent(300, rowVersion = 32L, acceptedCommandCount = 32)
      awaitDb(repository.advance300(otherDigest)) shouldEqual Conflict(300, rowVersion = 32L, acceptedCommandCount = 32)

      val ledger = awaitDb(repository.ledger).getOrElse(throw new AssertionError("fixture ledger was not created"))
      ledger.offsetSeconds shouldEqual 300
      ledger.acceptedCommandCount shouldEqual 32
      ledger.rowVersion shouldEqual 32L
      awaitDb(repository.auditRows).map(_.outcome) shouldEqual Seq(FixtureAuditOutcome.Applied)

      dropFixtureTables()
      awaitDb(repository.initialize(controllerConfig, Some(controllerId))) shouldEqual ControllerClaimed
      awaitDb(sqlu"update oauth_e2e_fixture_run set accepted_command_count = 32, row_version = 32 where singleton_key = 1") shouldEqual 1
      awaitDb(repository.advance300(digest)) shouldEqual CommandBudgetExhausted(0, rowVersion = 32L, acceptedCommandCount = 32)
      awaitDb(repository.auditRows) should beEmpty
    }

    "admit every non-cleanup command under one row lock without double counting and allow cleanup after command 32" in isolatedTables {
      val controllerId = ControllerInstanceId(UUID.randomUUID())
      val command32    = IdempotencyKeyDigest.sha256("command-command-32")
      val command33    = IdempotencyKeyDigest.sha256("command-command-33")
      val conflicting  = IdempotencyKeyDigest.sha256("command-command-conflict")
      val cleanupKey   = IdempotencyKeyDigest.sha256("command-cleanup-after-budget")
      var appliedCalls = 0
      awaitDb(repository.initialize(controllerConfig, Some(controllerId))) shouldEqual ControllerClaimed
      awaitDb(sqlu"update oauth_e2e_fixture_run set accepted_command_count = 31, row_version = 31 where singleton_key = 1") shouldEqual 1

      awaitDb(repository.admitNonCleanupCommand(FixtureAuditCommandCategory.Status, command32) { (_, _) =>
        DBIO.successful { appliedCalls += 1; "status-32" }
      }) shouldEqual CommandApplied("status-32", rowVersion = 32L, acceptedCommandCount = 32)
      awaitDb(repository.admitNonCleanupCommand(FixtureAuditCommandCategory.Status, command32) { (_, _) =>
        DBIO.successful { appliedCalls += 1; "must-not-run" }
      }) shouldEqual CommandIdempotent(offsetSeconds = 0, rowVersion = 32L, acceptedCommandCount = 32)
      awaitDb(
        repository.admitNonCleanupCommand(FixtureAuditCommandCategory.Setup, command32)((_, _) => DBIO.successful("must-not-run"))
      ) shouldEqual CommandConflict(offsetSeconds = 0, rowVersion = 32L, acceptedCommandCount = 32)
      awaitDb(
        repository.admitNonCleanupCommand(FixtureAuditCommandCategory.Status, command33)((_, _) => DBIO.successful("must-not-run"))
      ) shouldEqual CommandBudgetReached(offsetSeconds = 0, rowVersion = 32L, acceptedCommandCount = 32)
      awaitDb(
        repository.admitNonCleanupCommand(FixtureAuditCommandCategory.Status, conflicting)((_, _) => DBIO.successful("must-not-run"))
      ) shouldEqual CommandBudgetReached(offsetSeconds = 0, rowVersion = 32L, acceptedCommandCount = 32)

      appliedCalls shouldEqual 1
      awaitDb(repository.transitionCleanupStep(CleanupStep.OAuth027Revocation, CleanupStepState.Pending, CleanupStepState.InProgress, cleanupKey))
        .must(beAnInstanceOf[CleanupApplied])
      awaitDb(repository.ledger).map(value => (value.acceptedCommandCount, value.rowVersion)) shouldEqual Some(32 -> 33L)
      awaitDb(repository.auditRows).map(_.commandCategory) shouldEqual Seq(
        FixtureAuditCommandCategory.Status,
        FixtureAuditCommandCategory.CleanupStepTransition
      )
    }

    "treat setup, advance, revoke and status as the complete closed non-cleanup admission set" in isolatedTables {
      val controllerId = ControllerInstanceId(UUID.randomUUID())
      awaitDb(repository.initialize(controllerConfig, Some(controllerId))) shouldEqual ControllerClaimed

      val categories = Seq(
        FixtureAuditCommandCategory.Setup,
        FixtureAuditCommandCategory.Advance300Seconds,
        FixtureAuditCommandCategory.RevokeOAuth029,
        FixtureAuditCommandCategory.Status
      )
      categories.zipWithIndex.foreach { case (category, index) =>
        val digest = IdempotencyKeyDigest.sha256(s"command-category-$index")
        awaitDb(repository.admitNonCleanupCommand(category, digest)((_, _) => DBIO.successful(index))) shouldEqual
          CommandApplied(index, rowVersion = index + 2L, acceptedCommandCount = index + 1)
      }

      awaitDb(repository.ledger).map(_.acceptedCommandCount) shouldEqual Some(4)
      awaitDb(repository.auditRows).map(_.commandCategory) shouldEqual categories
    }

    "reject every non-contract offset, fractional write, reset, and malformed digest in PostgreSQL" in isolatedTables {
      val controllerId = ControllerInstanceId(UUID.randomUUID())
      val digest       = IdempotencyKeyDigest.sha256("repository-offset-constraints")
      awaitDb(repository.initialize(controllerConfig, Some(controllerId))) shouldEqual ControllerClaimed

      Seq(
        sqlu"update oauth_e2e_fixture_run set offset_seconds = -1 where singleton_key = 1",
        sqlu"update oauth_e2e_fixture_run set offset_seconds = 301 where singleton_key = 1",
        sqlu"update oauth_e2e_fixture_run set offset_seconds = 0.5 where singleton_key = 1"
      ).foreach(action => mustRejectSql(action))

      awaitDb(repository.advance300(digest)) must beAnInstanceOf[Applied]
      mustRejectSql(sqlu"update oauth_e2e_fixture_run set offset_seconds = 0 where singleton_key = 1")
      mustRejectSql(sqlu"update oauth_e2e_fixture_run set advance_idempotency_key_digest = 'raw-key' where singleton_key = 1")
      mustRejectSql(sqlu"update oauth_e2e_fixture_run set revoke_oauth029_started_digest = 'raw-key' where singleton_key = 1")
      mustRejectSql(sqlu"update oauth_e2e_fixture_run set revoke_oauth029_completed_digest = 'raw-key' where singleton_key = 1")
      awaitDb(repository.ledger).map(_.offsetSeconds) shouldEqual Some(300)
    }

    "transition only one closed cleanup step at a time without changing the non-cleanup command budget" in isolatedTables {
      val controllerId = ControllerInstanceId(UUID.randomUUID())
      val digest       = IdempotencyKeyDigest.sha256("repository-cleanup-step")
      val otherDigest  = IdempotencyKeyDigest.sha256("repository-cleanup-other")
      val step         = CleanupStep.OAuth029Revocation
      awaitDb(repository.initialize(controllerConfig, Some(controllerId))) shouldEqual ControllerClaimed

      awaitDb(repository.transitionCleanupStep(step, CleanupStepState.Pending, CleanupStepState.InProgress, digest)) shouldEqual
        CleanupApplied(CleanupStepState.InProgress, rowVersion = 2L)
      awaitDb(repository.transitionCleanupStep(step, CleanupStepState.Pending, CleanupStepState.InProgress, digest)) shouldEqual
        CleanupIdempotent(CleanupStepState.InProgress, rowVersion = 2L)
      awaitDb(repository.transitionCleanupStep(step, CleanupStepState.Pending, CleanupStepState.InProgress, otherDigest)) shouldEqual
        CleanupConflict(CleanupStepState.InProgress, rowVersion = 2L)
      awaitDb(repository.transitionCleanupStep(step, CleanupStepState.Pending, CleanupStepState.Complete, digest)) shouldEqual
        CleanupConflict(CleanupStepState.InProgress, rowVersion = 2L)
      awaitDb(repository.transitionCleanupStep(step, CleanupStepState.InProgress, CleanupStepState.Complete, digest)) shouldEqual
        CleanupApplied(CleanupStepState.Complete, rowVersion = 3L)
      awaitDb(repository.transitionCleanupStep(step, CleanupStepState.Complete, CleanupStepState.Pending, digest)) shouldEqual
        CleanupConflict(CleanupStepState.Complete, rowVersion = 3L)

      val ledger = awaitDb(repository.ledger).getOrElse(throw new AssertionError("fixture ledger was not created"))
      ledger.cleanupSteps(step) shouldEqual CleanupStepState.Complete
      ledger.cleanupIdempotencyKeyDigest shouldEqual Some(digest.value)
      ledger.acceptedCommandCount shouldEqual 0
      awaitDb(repository.auditRows).map(_.outcome) shouldEqual Seq(
        FixtureAuditOutcome.Applied,
        FixtureAuditOutcome.Idempotent,
        FixtureAuditOutcome.Conflict,
        FixtureAuditOutcome.Conflict,
        FixtureAuditOutcome.Applied,
        FixtureAuditOutcome.Conflict
      )
    }

    "reserve the cleanup digest globally without consuming the non-cleanup command budget" in isolatedTables {
      val controllerId  = ControllerInstanceId(UUID.randomUUID())
      val runId         = UUID.randomUUID()
      val setupDigest   = IdempotencyKeyDigest.sha256("cleanup-setup-reserved")
      val cleanupDigest = IdempotencyKeyDigest.sha256("cleanup-cleanup-reserved")
      val rawRunId      = runId.toString
      awaitDb(repository.initialize(controllerConfig, Some(controllerId))) shouldEqual ControllerClaimed
      awaitDb(repository.admitNonCleanupCommand(FixtureAuditCommandCategory.Setup, setupDigest) { (_, _) =>
        sqlu"update oauth_e2e_fixture_run set run_id = cast($rawRunId as uuid), run_state = 'setup-complete' where singleton_key = 1"
      }) shouldEqual CommandApplied(1, rowVersion = 2L, acceptedCommandCount = 1)

      awaitDb(repository.beginCleanup(runId, setupDigest)) shouldEqual OAuthE2EFixtureDomain.CleanupStartResult.Conflict
      awaitDb(repository.beginCleanup(runId, cleanupDigest)) shouldEqual OAuthE2EFixtureDomain.CleanupStartResult.Applied
      awaitDb(repository.beginCleanup(runId, cleanupDigest)) shouldEqual OAuthE2EFixtureDomain.CleanupStartResult.Resumable
      awaitDb(repository.beginCleanup(runId, IdempotencyKeyDigest.sha256("cleanup-cleanup-other"))) shouldEqual
        OAuthE2EFixtureDomain.CleanupStartResult.Conflict
      awaitDb(
        repository.admitNonCleanupCommand(FixtureAuditCommandCategory.Status, cleanupDigest)((_, _) => DBIO.successful("must-not-run"))
      ) must beLike { case CommandConflict(_, _, _) => ok }
      awaitDb(repository.ledger).map(_.acceptedCommandCount) shouldEqual Some(1)
    }

    "store only closed digests and sanitized audit categories, never raw command or secret-capable material" in isolatedTables {
      val controllerId      = ControllerInstanceId(UUID.randomUUID())
      val rawIdempotencyKey = "repository-raw-idempotency-sentinel"
      val digest            = IdempotencyKeyDigest.sha256(rawIdempotencyKey)
      awaitDb(repository.initialize(controllerConfig, Some(controllerId))) shouldEqual ControllerClaimed
      awaitDb(repository.advance300(digest)) must beAnInstanceOf[Applied]

      val ledger = awaitDb(repository.ledger).getOrElse(throw new AssertionError("fixture ledger was not created"))
      val audit  = awaitDb(repository.auditRows)
      val stored = ledger.toString + audit.mkString

      stored must not(contain(rawIdempotencyKey))
      stored must not(contain(controlToken))
      stored must not(contain(loginSecret))
      stored must not(contain(databaseSecret))
      audit must contain(
        exactly(
          audit.head.copy(
            commandCategory = FixtureAuditCommandCategory.Advance300Seconds,
            idempotencyKeyDigest = digest,
            outcome = FixtureAuditOutcome.Applied
          )
        )
      )
      runTableColumns shouldEqual expectedRunColumns
      auditTableColumns shouldEqual expectedAuditColumns
    }

    "prove concurrent controller initialization waits on the fixed PostgreSQL advisory lock" in isolatedTables {
      verifyConcurrentInitialization(
        name = "fixture-controller-same",
        firstController = ControllerInstanceId(UUID.randomUUID()),
        secondController = None,
        expectedResults = Set(ControllerClaimed, ControllerAlreadyClaimed)
      )

      dropFixtureTables()
      verifyConcurrentInitialization(
        name = "fixture-controller-conflict",
        firstController = ControllerInstanceId(UUID.randomUUID()),
        secondController = Some(ControllerInstanceId(UUID.randomUUID())),
        expectedResults = Set(ControllerClaimed, ControllerConflict)
      )
      awaitDb(repository.ledger).map(_.rowVersion) shouldEqual Some(1L)
    }

    "prove concurrent clock advances wait on the singleton PostgreSQL row lock" in isolatedTables {
      val controllerId = ControllerInstanceId(UUID.randomUUID())
      val sameDigest   = IdempotencyKeyDigest.sha256("repository-concurrent-same")
      awaitDb(repository.initialize(controllerConfig, Some(controllerId))) shouldEqual ControllerClaimed

      withThreeIndependentDatabases("fixture-advance-same") {
        (
          blockerDatabase,
          firstDatabase,
          secondDatabase
        ) =>
          val pendingResults = withLedgerRowLockHeld(blockerDatabase) { (blockerPid, track) =>
            awaitFuture(firstDatabase.run(sql"select offset_seconds from oauth_e2e_fixture_run where singleton_key = 1".as[Int].head)) shouldEqual 0
            val firstPid    = Promise[Int]()
            val firstResult = firstDatabase.run(captureBackendPid(firstPid)(new OAuthE2EFixtureRepositoryImpl().advance300(sameDigest)))
            track(firstResult)
            val firstBackendPid = awaitFuture(firstPid.future)
            awaitPostgresLockWait(blockerPid, firstBackendPid, expectedWaitEvent = "transactionid", expectedLockType = "transactionid")

            val secondPid    = Promise[Int]()
            val secondResult = secondDatabase.run(captureBackendPid(secondPid)(new OAuthE2EFixtureRepositoryImpl().advance300(sameDigest)))
            track(secondResult)
            awaitPostgresLockWait(firstBackendPid, awaitFuture(secondPid.future), expectedWaitEvent = "tuple", expectedLockType = "tuple")
            firstResult.value should beNone
            secondResult.value should beNone
            Seq(firstResult, secondResult)
          }
          val results = awaitFuture(Future.sequence(pendingResults))

          results.collect { case _: Applied => 1 }.sum shouldEqual 1
          results.collect { case _: Idempotent => 1 }.sum shouldEqual 1
          val ledger = awaitDb(repository.ledger).getOrElse(throw new AssertionError("fixture ledger was not created"))
          ledger.offsetSeconds shouldEqual 300
          ledger.rowVersion shouldEqual 2L
          ledger.acceptedCommandCount shouldEqual 1
      }

      dropFixtureTables()
      awaitDb(repository.initialize(controllerConfig, Some(controllerId))) shouldEqual ControllerClaimed

      withThreeIndependentDatabases("fixture-advance-conflict") {
        (
          blockerDatabase,
          firstDatabase,
          secondDatabase
        ) =>
          val firstDigest  = IdempotencyKeyDigest.sha256("repository-concurrent-first")
          val secondDigest = IdempotencyKeyDigest.sha256("repository-concurrent-second")
          val pendingResults = withLedgerRowLockHeld(blockerDatabase) { (blockerPid, track) =>
            awaitFuture(secondDatabase.run(sql"select row_version from oauth_e2e_fixture_run where singleton_key = 1".as[Long].head)) shouldEqual 1L
            val firstPid    = Promise[Int]()
            val firstResult = firstDatabase.run(captureBackendPid(firstPid)(new OAuthE2EFixtureRepositoryImpl().advance300(firstDigest)))
            track(firstResult)
            val firstBackendPid = awaitFuture(firstPid.future)
            awaitPostgresLockWait(blockerPid, firstBackendPid, expectedWaitEvent = "transactionid", expectedLockType = "transactionid")

            val secondPid    = Promise[Int]()
            val secondResult = secondDatabase.run(captureBackendPid(secondPid)(new OAuthE2EFixtureRepositoryImpl().advance300(secondDigest)))
            track(secondResult)
            awaitPostgresLockWait(firstBackendPid, awaitFuture(secondPid.future), expectedWaitEvent = "tuple", expectedLockType = "tuple")
            firstResult.value should beNone
            secondResult.value should beNone
            Seq(firstResult, secondResult)
          }
          val results = awaitFuture(Future.sequence(pendingResults))

          results.collect { case _: Applied => 1 }.sum shouldEqual 1
          results.collect { case _: Conflict => 1 }.sum shouldEqual 1
          val ledger = awaitDb(repository.ledger).getOrElse(throw new AssertionError("fixture ledger was not created"))
          ledger.offsetSeconds shouldEqual 300
          ledger.rowVersion shouldEqual 2L
          ledger.acceptedCommandCount shouldEqual 1
          val storedDigest = awaitDb(repository.auditRows).map(_.idempotencyKeyDigest).toSet
          storedDigest must haveSize(1)
          Set(firstDigest, secondDigest) must contain(storedDigest.head)
      }
    }

    "share one exact database offset between the controller and time-only replica" in isolatedTables {
      val controllerId = ControllerInstanceId(UUID.randomUUID())
      val digest       = IdempotencyKeyDigest.sha256("repository-mixed-instance")

      withIndependentDatabases("fixture-mixed") { (controllerDatabase, replicaDatabase) =>
        val controllerRepository = new OAuthE2EFixtureRepositoryImpl()
        val replicaRepository    = new OAuthE2EFixtureRepositoryImpl()
        awaitFuture(controllerDatabase.run(controllerRepository.initialize(controllerConfig, Some(controllerId)))) shouldEqual ControllerClaimed
        awaitFuture(replicaDatabase.run(replicaRepository.initialize(replicaConfig, None))) shouldEqual TimeOnlyReady

        val controllerBefore = awaitFuture(controllerDatabase.run(controllerRepository.ledger)).map(_.offsetSeconds)
        val replicaBefore    = awaitFuture(replicaDatabase.run(replicaRepository.ledger)).map(_.offsetSeconds)
        controllerBefore shouldEqual Some(0)
        replicaBefore shouldEqual Some(0)

        val controllerTime = new FixtureOAuthAuthoritativeTime(controllerRepository)
        val replicaTime    = new FixtureOAuthAuthoritativeTime(replicaRepository)
        val logicalBefore  = awaitFuture(replicaDatabase.run(replicaTime.now))
        awaitFuture(controllerDatabase.run(controllerRepository.advance300(digest))) must beAnInstanceOf[Applied]
        val logicalAfter = awaitFuture(controllerDatabase.run(controllerTime.now))

        val controllerAfter = awaitFuture(controllerDatabase.run(controllerRepository.ledger)).map(_.offsetSeconds)
        val replicaAfter    = awaitFuture(replicaDatabase.run(replicaRepository.ledger)).map(_.offsetSeconds)
        controllerAfter shouldEqual Some(300)
        replicaAfter shouldEqual Some(300)
        controllerAfter.get - controllerBefore.get shouldEqual 300
        replicaAfter.get - replicaBefore.get shouldEqual 300
        logicalAfter must be_>=(logicalBefore.plusSeconds(300))
      }
    }

    "select fixture time only for guarded time-enabled configurations" in {
      OAuthE2EApplicationLoader.authoritativeTimeFor(disabledConfig, repository) must beAnInstanceOf[PostgresOAuthAuthoritativeTime]
      OAuthE2EApplicationLoader.authoritativeTimeFor(replicaConfig, repository) must beAnInstanceOf[FixtureOAuthAuthoritativeTime]
      OAuthE2EApplicationLoader.authoritativeTimeFor(controllerConfig, repository) must beAnInstanceOf[FixtureOAuthAuthoritativeTime]
    }
  }

  private def isolatedTables[T](body: => T): T = {
    dropFixtureTables()
    try body
    finally dropFixtureTables()
  }

  private def dropFixtureTables(): Unit =
    awaitDb(
      DBIO
        .seq(
          sqlu"drop table if exists oauth_e2e_fixture_ownership",
          sqlu"drop table if exists oauth_e2e_fixture_audit",
          sqlu"drop table if exists oauth_e2e_fixture_run"
        )
        .transactionally
    )

  private def awaitDb[T](action: DBIO[T]): T = Await.result(db.run(action), 20.seconds)

  private def awaitFuture[T](future: Future[T]): T = Await.result(future, 30.seconds)

  private def verifyConcurrentInitialization(
    name: String,
    firstController: ControllerInstanceId,
    secondController: Option[ControllerInstanceId],
    expectedResults: Set[FixtureInitializationResult]
  ) =
    withThreeIndependentDatabases(name) {
      (
        blockerDatabase,
        firstDatabase,
        secondDatabase
      ) =>
        val pendingResults = withAdvisoryLockHeld(blockerDatabase) { (blockerPid, track) =>
          awaitFuture(firstDatabase.run(sql"select 1".as[Int].head)) shouldEqual 1
          val firstPid = Promise[Int]()
          val firstResult =
            firstDatabase.run(captureBackendPid(firstPid)(new OAuthE2EFixtureRepositoryImpl().initialize(controllerConfig, Some(firstController))))
          track(firstResult)
          awaitPostgresLockWait(blockerPid, awaitFuture(firstPid.future), expectedWaitEvent = "advisory", expectedLockType = "advisory")

          val secondPid = Promise[Int]()
          val secondResult = secondDatabase.run(
            captureBackendPid(secondPid)(
              new OAuthE2EFixtureRepositoryImpl().initialize(controllerConfig, Some(secondController.getOrElse(firstController)))
            )
          )
          track(secondResult)

          awaitPostgresLockWait(blockerPid, awaitFuture(secondPid.future), expectedWaitEvent = "advisory", expectedLockType = "advisory")
          firstResult.value should beNone
          secondResult.value should beNone
          Seq(firstResult, secondResult)
        }

        awaitFuture(Future.sequence(pendingResults)).toSet shouldEqual expectedResults
    }

  private def captureBackendPid[T](backendPid: Promise[Int])(action: DBIO[T]): DBIO[T] =
    sql"select pg_backend_pid()"
      .as[Int]
      .head
      .flatMap { value =>
        DBIO.successful { backendPid.trySuccess(value); () } >> action
      }
      .withPinnedSession

  private def withAdvisoryLockHeld[T](database: Database)(body: (Int, Future[_] => Unit) => T): T =
    withPostgresLockHeld(database, sql"select pg_advisory_xact_lock(522, 3)::text".as[String].head.map(_ => ()))(body)

  private def withLedgerRowLockHeld[T](database: Database)(body: (Int, Future[_] => Unit) => T): T =
    withPostgresLockHeld(database, sql"select singleton_key from oauth_e2e_fixture_run where singleton_key = 1 for update".as[Int].head.map(_ => ()))(
      body
    )

  private def withPostgresLockHeld[T](database: Database, acquire: DBIO[Unit])(body: (Int, Future[_] => Unit) => T): T = {
    val acquired = Promise[Int]()
    val release  = Promise[Unit]()
    var tracked  = Vector.empty[Future[_]]
    val holder = database.run((for {
      backendPid <- sql"select pg_backend_pid()".as[Int].head
      _          <- acquire
      _          <- DBIO.successful { acquired.trySuccess(backendPid); () }
      _          <- DBIO.from(release.future)
    } yield ()).transactionally)

    val backendPid               = awaitFuture(acquired.future)
    val track: Future[_] => Unit = future => tracked :+= future
    try body(backendPid, track)
    finally {
      release.trySuccess(())
      awaitFuture(holder)
      tracked.foreach(future => Await.ready(future, 30.seconds))
    }
  }

  private def awaitPostgresLockWait(
    blockerPid: Int,
    waitingPid: Int,
    expectedWaitEvent: String,
    expectedLockType: String
  ): Unit = {
    val deadline        = 10.seconds.fromNow
    var lastObservation = Option.empty[(Option[String], Option[String], Boolean, Boolean)]

    while (deadline.hasTimeLeft()) {
      lastObservation = awaitDb(
        sql"""select activity.wait_event_type,
                      activity.wait_event,
                      $blockerPid = any(pg_catalog.pg_blocking_pids(activity.pid)),
                      exists (
                        select 1
                        from pg_catalog.pg_locks as waiting_lock
                        where waiting_lock.pid = activity.pid
                          and not waiting_lock.granted
                          and waiting_lock.locktype = $expectedLockType
                      )
               from pg_catalog.pg_stat_activity as activity
               where activity.pid = $waitingPid"""
          .as[(Option[String], Option[String], Boolean, Boolean)]
          .headOption
      )
      lastObservation match {
        case Some((Some("Lock"), Some(waitEvent), blockedByExactPid, hasExpectedWaitingLock))
            if waitEvent == expectedWaitEvent && blockedByExactPid && hasExpectedWaitingLock =>
          return
        case _ => ()
      }
    }

    throw new AssertionError(
      s"Expected PostgreSQL backend $waitingPid to wait on $expectedLockType/$expectedWaitEvent held by exact backend $blockerPid; " +
        s"lastObservation=$lastObservation"
    )
  }

  private def withIndependentDatabases[T](name: String)(body: (Database, Database) => T): T = {
    val firstDatabase  = independentDatabase(s"$name-first")
    val secondDatabase = independentDatabase(s"$name-second")
    try body(firstDatabase, secondDatabase)
    finally {
      firstDatabase.close()
      secondDatabase.close()
    }
  }

  private def withThreeIndependentDatabases[T](name: String)(body: (Database, Database, Database) => T): T = {
    val blockerDatabase = independentDatabase(s"$name-blocker")
    val firstDatabase   = independentDatabase(s"$name-first")
    val secondDatabase  = independentDatabase(s"$name-second")
    try body(blockerDatabase, firstDatabase, secondDatabase)
    finally {
      blockerDatabase.close()
      firstDatabase.close()
      secondDatabase.close()
    }
  }

  private def independentDatabase(name: String): Database =
    Database.forURL(
      url = sys.env.getOrElse("TEST_DB_URL", "epoca"),
      user = sys.env.getOrElse("TEST_DB_USER", "postgres"),
      password = sys.env.getOrElse("TEST_DB_PASSWORD", "postgres"),
      driver = "org.postgresql.Driver",
      executor = AsyncExecutor(s"$name-${UUID.randomUUID()}", minThreads = 1, maxThreads = 1, queueSize = 20, maxConnections = 1),
      prop = new Properties()
    )

  private def mustRejectSql(action: DBIO[Int]) =
    awaitDb(action) must throwA[PSQLException]

  private def fixtureTableNames: Seq[String] =
    awaitDb(sql"""select table_name
             from information_schema.tables
             where table_schema = 'public' and table_name like 'oauth_e2e_fixture_%'
             order by table_name""".as[String])

  private def columns(tableName: String): Seq[String] =
    awaitDb(sql"""select column_name
             from information_schema.columns
             where table_schema = 'public' and table_name = $tableName
             order by ordinal_position""".as[String])

  private def runTableColumns: Seq[String]   = columns("oauth_e2e_fixture_run")
  private def auditTableColumns: Seq[String] = columns("oauth_e2e_fixture_audit")

  private val expectedRunColumns = Seq(
    "singleton_key",
    "run_id",
    "run_state",
    "offset_seconds",
    "row_version",
    "controller_lease_id",
    "controller_lease_state",
    "accepted_command_count",
    "advance_idempotency_key_digest",
    "revoke_oauth029_started_digest",
    "revoke_oauth029_completed_digest",
    "cleanup_idempotency_key_digest",
    "oauth027_revocation_cleanup_state",
    "oauth027_resource_cleanup_state",
    "oauth028_revocation_cleanup_state",
    "oauth028_resource_cleanup_state",
    "oauth029_revocation_cleanup_state",
    "oauth029_resource_cleanup_state",
    "general_revocation_cleanup_state",
    "general_resource_cleanup_state",
    "created_at",
    "modified_at"
  )

  private val expectedAuditColumns = Seq("sequence", "command_category", "idempotency_key_digest", "outcome_category", "authoritative_at")

  private val applicationConfig = ConfigFactory
    .parseResources("oauth-e2e.conf")
    .withFallback(ConfigFactory.parseResources("application.conf"))
    .resolve(ConfigResolveOptions.defaults().setAllowUnresolved(true))

  private val signingKeyA    = encodedBytes(1)
  private val signingKeyB    = encodedBytes(33)
  private val controlToken   = versionedToken(65)
  private val loginSecret    = "oauth-e2e-repository-login-secret"
  private val databaseSecret = "oauth-e2e-repository-database-secret"

  private lazy val disabledConfig: OAuthE2EFixtureConfig = requireConfig(applicationConfig)

  private lazy val replicaConfig: OAuthE2EFixtureConfig = requireConfig(
    ConfigFactory
      .parseString(s"""
        app.environment = "oauth-e2e"
        db.url = "jdbc:postgresql://127.0.0.1:55422/oauth_e2e_repository"
        db.password = "$databaseSecret"
        login.jwtSecret = "$loginSecret"

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
  )

  private lazy val controllerConfig: OAuthE2EFixtureConfig = requireConfig(
    ConfigFactory
      .parseString(s"""
        oauth.e2eFixture.controlListenerEnabled = true
        oauth.e2eFixture.listenerInterface = "127.0.0.1"
        oauth.e2eFixture.listenerPort = 19000
        oauth.e2eFixture.controlToken = "$controlToken"
      """)
      .withFallback(replicaRootConfig)
      .resolve(ConfigResolveOptions.defaults().setAllowUnresolved(true))
  )

  private lazy val replicaRootConfig: Config = ConfigFactory
    .parseString(s"""
      app.environment = "oauth-e2e"
      db.url = "jdbc:postgresql://127.0.0.1:55422/oauth_e2e_repository"
      db.password = "$databaseSecret"
      login.jwtSecret = "$loginSecret"

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

  private def requireConfig(config: Config): OAuthE2EFixtureConfig =
    OAuthE2EFixtureConfig.load(config) match {
      case Right(value) => value
      case Left(error)  => throw new AssertionError(s"Expected valid fixture test configuration, got $error")
    }

  private def encodedBytes(start: Int, size: Int = 32): String =
    Base64.getUrlEncoder.withoutPadding().encodeToString(Array.tabulate[Byte](size)(index => (start + index).toByte))

  private def versionedToken(start: Int): String = s"oauth-e2e-v1.${encodedBytes(start)}"
}
