package pl.iterators.epoca.workunit.services

import cats.effect.unsafe.IORuntime
import io.circe.Json
import org.specs2.mutable.SpecificationLike
import pl.iterators.epoca.client.domain.Client
import pl.iterators.epoca.client.repositories.ClientRepositoryImpl
import pl.iterators.epoca.organization.domain.Organization
import pl.iterators.epoca.organization.repositories.{OrganizationRepositoryImpl, OrganizationUserRepositoryImpl}
import pl.iterators.epoca.project.domain.Project
import pl.iterators.epoca.project.repositories.ProjectRepositoryImpl
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.repositories.UserRepositoryImpl
import pl.iterators.epoca.utils.Generators
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import pl.iterators.epoca.utils.db.DBIOInstances._
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.transactor.IsolationLevel
import pl.iterators.epoca.workunit.domain.WorkUnit
import pl.iterators.epoca.workunit.domain.WorkUnitDomain._
import pl.iterators.epoca.workunit.idempotency.WorkUnitCommandCanonicalizer.{Version1, Version2}
import pl.iterators.epoca.workunit.idempotency.WorkUnitIdempotencyDomain._
import pl.iterators.epoca.workunit.idempotency.WorkUnitIdempotencyOperation.Create
import pl.iterators.epoca.workunit.idempotency.WorkUnitIdempotencyRecord.Completed
import pl.iterators.epoca.workunit.idempotency.WorkUnitIdempotencyResult.{KeyConflict, Response}
import pl.iterators.epoca.workunit.idempotency._
import pl.iterators.epoca.workunit.repositories.WorkUnitRepository.WorkUnitFilters
import pl.iterators.epoca.workunit.repositories.tables.WorkUnitIdempotencyTable
import pl.iterators.epoca.workunit.repositories.{
  WorkUnitIdempotencyRepository,
  WorkUnitIdempotencyRepositoryImpl,
  WorkUnitRepository,
  WorkUnitRepositoryImpl
}
import pl.iterators.epoca.workunit.services.WorkUnitCreateService.WorkUnitCreateRequest
import pl.iterators.epoca.workunit.services.WorkUnitIdempotencyService.WorkUnitIdempotencyConfig
import pl.iterators.epoca.workunit.services.WorkUnitUpdateService.WorkUnitUpdateRequest
import slick.dbio.DBIO
import slick.jdbc.SetParameter
import slick.util.AsyncExecutor

import java.sql.Types
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ConcurrentLinkedQueue, TimeoutException}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

class WorkUnitIdempotencyConcurrencySpec extends SpecificationLike with Generators {
  sequential

  private implicit val ec: ExecutionContext = ExecutionContext.global
  private implicit val runtime: IORuntime   = IORuntime.global
  private implicit val setUuid: SetParameter[UUID] =
    SetParameter((value, parameters) => parameters.setObject(value, Types.OTHER))

  private val requestTimeout         = 20.seconds
  private val setupCleanupTimeout    = 10.seconds
  private val lockObservationTimeout = 10.seconds
  private val lockPollingInterval    = 25.millis

  private val databaseUrl      = sys.env.getOrElse("TEST_DB_URL", "epoca")
  private val databaseUser     = sys.env.getOrElse("TEST_DB_USER", "postgres")
  private val databasePassword = sys.env.getOrElse("TEST_DB_PASSWORD", "postgres")
  private val executorName     = s"${getClass.getCanonicalName}-${UUID.randomUUID()}"

  private lazy val db = Database.forURL(
    url = databaseUrl,
    user = databaseUser,
    password = databasePassword,
    driver = "org.postgresql.Driver",
    executor = AsyncExecutor(executorName, minThreads = 2, maxThreads = 2, queueSize = 20, maxConnections = 2)
  )

  private lazy val observerDb = Database.forURL(
    url = databaseUrl,
    user = databaseUser,
    password = databasePassword,
    driver = "org.postgresql.Driver",
    executor = AsyncExecutor(s"$executorName-observer", minThreads = 1, maxThreads = 1, queueSize = 20, maxConnections = 1)
  )

  private val transactor                             = new PostgresIOTransactor(db)
  private val idempotencyRepository                  = new WorkUnitIdempotencyRepositoryImpl()
  private val workUnitRepository: WorkUnitRepository = new WorkUnitRepositoryImpl()
  private val userRepository                         = new UserRepositoryImpl()
  private val organizationRepository                 = new OrganizationRepositoryImpl()
  private val organizationUserRepository             = new OrganizationUserRepositoryImpl()
  private val clientRepository                       = new ClientRepositoryImpl()
  private val projectRepository                      = new ProjectRepositoryImpl()
  private val idempotencyTable                       = TableQuery[WorkUnitIdempotencyTable]

  "work unit idempotency" should {
    "deduplicate two concurrent requests on independent PostgreSQL sessions" in {
      val firstClaimed   = Promise[Unit]()
      val releaseFirst   = Promise[Unit]()
      val firstPid       = Promise[Int]()
      val secondPid      = Promise[Int]()
      val actionRuns     = new AtomicInteger(0)
      var fixtureOpt     = Option.empty[Fixture]
      var workUnitIdOpt  = Option.empty[WorkUnitId]
      var scopeOpt       = Option.empty[WorkUnitIdempotencyScope]
      var requestFutures = Vector.empty[(String, Future[WorkUnitIdempotencyResult])]

      runWithTeardown {
        val fixture = setupFixture()
        fixtureOpt = Some(fixture)
        val workUnit = newWorkUnit(fixture)
        workUnitIdOpt = Some(workUnit.id)
        val request = createRequest(workUnit)
        val command = WorkUnitWriteCommand.Create(request)
        val key     = WorkUnitIdempotencyKey(UUID.randomUUID())
        val scope   = WorkUnitIdempotencyScope(fixture.organization.id, fixture.user.id, key)
        scopeOpt = Some(scope)
        val outcome            = expectedOutcome(workUnit)
        val canonical          = WorkUnitCommandCanonicalizer.canonicalize(Version1, scope.actorId, command).toOption.get
        val observedRepository = new CapturingClaimRepository(idempotencyRepository)
        val service            = newService(observedRepository)

        val first = runRequest(service, scope, command, outcome, firstPid) {
          actionRuns.incrementAndGet()
          firstClaimed.trySuccess(())
          DBIO.from(releaseFirst.future).flatMap(_ => workUnitRepository.insert(workUnit))
        }
        requestFutures :+= "first concurrent request" -> first
        await(firstClaimed.future, "first request to claim the idempotency key")

        val second = runRequest(service, scope, command, outcome, secondPid) {
          actionRuns.incrementAndGet()
          DBIO.failed(new AssertionError("the replayed request must not evaluate its domain action"))
        }
        requestFutures :+= "second concurrent request" -> second
        val firstBackendPid  = await(firstPid.future, "first request PostgreSQL backend PID")
        val secondBackendPid = await(secondPid.future, "second request PostgreSQL backend PID")
        firstBackendPid shouldNotEqual secondBackendPid

        val lockObservation = awaitDirectBlock(firstBackendPid, secondBackendPid)
        lockObservation._1 shouldEqual firstBackendPid
        lockObservation._2 shouldEqual Some("Lock")
        lockObservation._3 shouldEqual Some("transactionid")
        second.isCompleted should beFalse
        releaseFirst.trySuccess(()) should beTrue

        val results   = await(first.zip(second), "both concurrent requests")
        val responses = Seq(results._1, results._2).map(requireResponse)
        val states    = ledgerStates(scope)
        val stored    = findLedgerRecord(scope)
        val claims    = observedRepository.capturedClaims

        responses.map(_.outcome) shouldEqual Seq(outcome, outcome)
        responses.map(_.replayed).sorted shouldEqual Seq(false, true)
        actionRuns.get shouldEqual 1
        countWorkUnits(workUnit, fixture) shouldEqual 1
        states shouldEqual Seq("Completed")
        states.count(_ == "Processing") shouldEqual 0
        claims.size shouldEqual 2
        claims.map(claim => (claim.scope, claim.operation, claim.fingerprintVersion, claim.canonicalRequest, claim.fingerprint)) shouldEqual
          Seq.fill(2)((scope, Create, Version1, canonical.json, canonical.fingerprint))
        completedRecordMatches(stored, claims.head, outcome) should beTrue
      } {
        Seq[() => Unit] { () => releaseFirst.trySuccess(()); () } ++
          drainSteps(requestFutures) :+
          (() => fixtureOpt.foreach(fixture => cleanup(fixture, workUnitIdOpt, scopeOpt)))
      }
    }

    "deduplicate concurrent Note duration aliases into one V2 mutation" in {
      val firstClaimed   = Promise[Unit]()
      val releaseFirst   = Promise[Unit]()
      val firstPid       = Promise[Int]()
      val secondPid      = Promise[Int]()
      val actionRuns     = new AtomicInteger(0)
      var fixtureOpt     = Option.empty[Fixture]
      var workUnitIdOpt  = Option.empty[WorkUnitId]
      var scopeOpt       = Option.empty[WorkUnitIdempotencyScope]
      var requestFutures = Vector.empty[(String, Future[WorkUnitIdempotencyResult])]

      runWithTeardown {
        val fixture = setupFixture()
        fixtureOpt = Some(fixture)
        val workUnit = newWorkUnit(fixture).copy(entryType = WorkUnitEntryType.Note, duration = WorkUnitDuration(Duration.ZERO))
        workUnitIdOpt = Some(workUnit.id)
        val missingDurationRequest = createRequest(workUnit).copy(duration = None, entryType = Some(WorkUnitEntryType.Note))
        val explicitZeroRequest    = missingDurationRequest.copy(duration = Some(WorkUnitDuration(Duration.ZERO)))
        val firstCommand           = WorkUnitWriteCommand.Create(missingDurationRequest)
        val secondCommand          = WorkUnitWriteCommand.Create(explicitZeroRequest)
        val key                    = WorkUnitIdempotencyKey(UUID.randomUUID())
        val scope                  = WorkUnitIdempotencyScope(fixture.organization.id, fixture.user.id, key)
        scopeOpt = Some(scope)
        val outcome            = expectedOutcome(workUnit)
        val canonical          = WorkUnitCommandCanonicalizer.canonicalize(Version2, scope.actorId, firstCommand).toOption.get
        val observedRepository = new CapturingClaimRepository(idempotencyRepository)
        val service            = newService(observedRepository)

        val first = runRequest(service, scope, firstCommand, outcome, firstPid) {
          actionRuns.incrementAndGet()
          firstClaimed.trySuccess(())
          DBIO.from(releaseFirst.future).flatMap(_ => workUnitRepository.insert(workUnit))
        }
        requestFutures :+= "first concurrent Note request" -> first
        await(firstClaimed.future, "first Note request to claim the idempotency key")

        val second = runRequest(service, scope, secondCommand, outcome, secondPid) {
          actionRuns.incrementAndGet()
          DBIO.failed(new AssertionError("the semantically equivalent Note replay must not evaluate its domain action"))
        }
        requestFutures :+= "second concurrent Note request" -> second
        val firstBackendPid  = await(firstPid.future, "first Note request PostgreSQL backend PID")
        val secondBackendPid = await(secondPid.future, "second Note request PostgreSQL backend PID")
        firstBackendPid shouldNotEqual secondBackendPid
        awaitDirectBlock(firstBackendPid, secondBackendPid)
        second.isCompleted should beFalse
        releaseFirst.trySuccess(()) should beTrue

        val results   = await(first.zip(second), "both concurrent Note requests")
        val responses = Seq(results._1, results._2).map(requireResponse)
        val stored    = findLedgerRecord(scope)
        val claims    = observedRepository.capturedClaims

        responses.map(_.outcome) shouldEqual Seq(outcome, outcome)
        responses.map(_.replayed).sorted shouldEqual Seq(false, true)
        actionRuns.get shouldEqual 1
        countWorkUnits(workUnit, fixture) shouldEqual 1
        ledgerStates(scope) shouldEqual Seq("Completed")
        claims.size shouldEqual 2
        claims.map(_.fingerprintVersion) shouldEqual Seq.fill(2)(Version2)
        claims.map(_.canonicalRequest) shouldEqual Seq.fill(2)(canonical.json)
        claims.map(_.fingerprint) shouldEqual Seq.fill(2)(canonical.fingerprint)
        stored.map(_.fingerprintVersion) shouldEqual Some(Version2)
        completedRecordMatches(stored, claims.head, outcome) should beTrue
      } {
        Seq[() => Unit] { () => releaseFirst.trySuccess(()); () } ++
          drainSteps(requestFutures) :+
          (() => fixtureOpt.foreach(fixture => cleanup(fixture, workUnitIdOpt, scopeOpt)))
      }
    }

    "return KeyConflict when a persisted V1 Worklog claim is retried as a Note" in {
      val firstActionRuns = new AtomicInteger(0)
      val retryActionRuns = new AtomicInteger(0)
      var fixtureOpt      = Option.empty[Fixture]
      var workUnitIdOpt   = Option.empty[WorkUnitId]
      var scopeOpt        = Option.empty[WorkUnitIdempotencyScope]
      var requestFutures  = Vector.empty[(String, Future[WorkUnitIdempotencyResult])]

      runWithTeardown {
        val fixture = setupFixture()
        fixtureOpt = Some(fixture)
        val workUnit = newWorkUnit(fixture)
        workUnitIdOpt = Some(workUnit.id)
        val worklogRequest = createRequest(workUnit)
        val noteRequest    = worklogRequest.copy(duration = None, entryType = Some(WorkUnitEntryType.Note))
        val worklogCommand = WorkUnitWriteCommand.Create(worklogRequest)
        val noteCommand    = WorkUnitWriteCommand.Create(noteRequest)
        val scope          = WorkUnitIdempotencyScope(fixture.organization.id, fixture.user.id, WorkUnitIdempotencyKey(UUID.randomUUID()))
        scopeOpt = Some(scope)
        val outcome             = expectedOutcome(workUnit)
        val capturingRepository = new CapturingClaimRepository(idempotencyRepository)
        val service             = newService(capturingRepository)

        val first = runRequest(service, scope, worklogCommand, outcome, Promise[Int]()) {
          firstActionRuns.incrementAndGet()
          workUnitRepository.insert(workUnit)
        }
        requestFutures :+= "initial V1 Worklog request" -> first
        requireResponse(await(first, "initial V1 Worklog request")) shouldEqual Response(outcome, replayed = false)

        val retry = runRequest(service, scope, noteCommand, outcome, Promise[Int]()) {
          retryActionRuns.incrementAndGet()
          DBIO.failed(new AssertionError("a Note retry against V1 must not evaluate its domain action"))
        }
        requestFutures :+= "Note retry against V1" -> retry

        await(retry, "Note retry against persisted V1 claim") shouldEqual KeyConflict
        firstActionRuns.get shouldEqual 1
        retryActionRuns.get shouldEqual 0
        countWorkUnits(workUnit, fixture) shouldEqual 1
        ledgerStates(scope) shouldEqual Seq("Completed")
        capturingRepository.capturedClaims.map(_.fingerprintVersion) shouldEqual Seq(Version1, Version2)
        findLedgerRecord(scope).map(_.fingerprintVersion) shouldEqual Some(Version1)
      } {
        drainSteps(requestFutures) :+ (() => fixtureOpt.foreach(fixture => cleanup(fixture, workUnitIdOpt, scopeOpt)))
      }
    }

    "roll back the claim and work unit together, then allow a clean retry" in {
      val expected       = new IllegalStateException("expected domain failure after work unit insert")
      val failedRuns     = new AtomicInteger(0)
      val retryRuns      = new AtomicInteger(0)
      var fixtureOpt     = Option.empty[Fixture]
      var workUnitIdOpt  = Option.empty[WorkUnitId]
      var scopeOpt       = Option.empty[WorkUnitIdempotencyScope]
      var requestFutures = Vector.empty[(String, Future[WorkUnitIdempotencyResult])]

      runWithTeardown {
        val fixture = setupFixture()
        fixtureOpt = Some(fixture)
        val workUnit = newWorkUnit(fixture)
        workUnitIdOpt = Some(workUnit.id)
        val request = createRequest(workUnit)
        val command = WorkUnitWriteCommand.Create(request)
        val scope   = WorkUnitIdempotencyScope(fixture.organization.id, fixture.user.id, WorkUnitIdempotencyKey(UUID.randomUUID()))
        scopeOpt = Some(scope)
        val outcome             = expectedOutcome(workUnit)
        val capturingRepository = new CapturingClaimRepository(idempotencyRepository)
        val service             = newService(capturingRepository)

        val failedRequest = runRequest(service, scope, command, outcome, Promise[Int]()) {
          failedRuns.incrementAndGet()
          workUnitRepository.insert(workUnit).flatMap(_ => DBIO.failed(expected))
        }
        requestFutures :+= "failing request" -> failedRequest

        val thrown = thrownBy(await(failedRequest, "failing idempotent request"))
        thrown should beTheSameAs(expected)
        failedRuns.get shouldEqual 1
        countWorkUnits(workUnit, fixture) shouldEqual 0
        ledgerStates(scope) should beEmpty
        findLedgerRecord(scope) should beNone
        capturingRepository.capturedClaims.size shouldEqual 1

        val retry = runRequest(service, scope, command, outcome, Promise[Int]()) {
          retryRuns.incrementAndGet()
          workUnitRepository.insert(workUnit)
        }
        requestFutures :+= "retry request" -> retry

        requireResponse(await(retry, "retry after rollback")) shouldEqual Response(outcome, replayed = false)
        retryRuns.get shouldEqual 1
        val claimsAfterRetry = capturingRepository.capturedClaims
        claimsAfterRetry.size shouldEqual 2
        val rolledBackClaim = claimsAfterRetry.head
        val retryClaim      = claimsAfterRetry(1)
        rolledBackClaim.claimId shouldNotEqual retryClaim.claimId
        val expectedStored = findLedgerRecord(scope)
        completedRecordMatches(expectedStored, retryClaim, outcome) should beTrue

        val replay = runRequest(service, scope, command, outcome, Promise[Int]()) {
          retryRuns.incrementAndGet()
          DBIO.failed(new AssertionError("the post-retry replay must not evaluate its domain action"))
        }
        requestFutures :+= "post-retry replay" -> replay

        requireResponse(await(replay, "replay after successful retry")) shouldEqual Response(outcome, replayed = true)
        retryRuns.get shouldEqual 1
        countWorkUnits(workUnit, fixture) shouldEqual 1
        ledgerStates(scope) shouldEqual Seq("Completed")
        capturingRepository.capturedClaims.size shouldEqual 3
        findLedgerRecord(scope) shouldEqual expectedStored
      } {
        drainSteps(requestFutures) :+ (() => fixtureOpt.foreach(fixture => cleanup(fixture, workUnitIdOpt, scopeOpt)))
      }
    }

    "replay an Update without running a second mutation or changing modifiedAt" in {
      val actionRuns     = new AtomicInteger(0)
      var fixtureOpt     = Option.empty[Fixture]
      var workUnitIdOpt  = Option.empty[WorkUnitId]
      var scopeOpt       = Option.empty[WorkUnitIdempotencyScope]
      var requestFutures = Vector.empty[(String, Future[WorkUnitIdempotencyResult])]

      runWithTeardown {
        val fixture = setupFixture()
        fixtureOpt = Some(fixture)
        val workUnit = newWorkUnit(fixture)
        workUnitIdOpt = Some(workUnit.id)
        awaitDb(workUnitRepository.insert(workUnit), "initial work unit insert")

        val request = allGenerators[WorkUnitUpdateRequest].normal.generate.copy(
          projectId = None,
          duration = None,
          taskId = None,
          date = None,
          shouldFinalize = false
        )
        val command = WorkUnitWriteCommand.Update(workUnit.id, request)
        val scope   = WorkUnitIdempotencyScope(fixture.organization.id, fixture.user.id, WorkUnitIdempotencyKey(UUID.randomUUID()))
        scopeOpt = Some(scope)
        val outcome = WorkUnitWriteOutcome(
          status = 200,
          body = Json.obj("id" -> Json.fromString(workUnit.id.toString), "stored" -> Json.True),
          resultWorkUnitId = Some(workUnit.id)
        )
        val service          = newService(idempotencyRepository)
        val firstModifiedAt  = java.time.Instant.parse("2026-07-28T10:00:00Z")
        val replayModifiedAt = firstModifiedAt.plusSeconds(60)
        val firstUpdate      = workUnit.updated(request, firstModifiedAt)
        val replayUpdate     = firstUpdate.updated(request, replayModifiedAt)

        val first = runRequest(service, scope, command, outcome, Promise[Int]()) {
          actionRuns.incrementAndGet()
          workUnitRepository.update(firstUpdate).map(_.get)
        }
        requestFutures :+= "first idempotent update" -> first
        requireResponse(await(first, "first idempotent update")) shouldEqual Response(outcome, replayed = false)
        findWorkUnit(workUnit, fixture).map(_.modifiedAt) shouldEqual Some(firstUpdate.modifiedAt)

        val replay = runRequest(service, scope, command, outcome, Promise[Int]()) {
          actionRuns.incrementAndGet()
          workUnitRepository.update(replayUpdate).map(_.get)
        }
        requestFutures :+= "replayed idempotent update" -> replay

        requireResponse(await(replay, "replayed idempotent update")) shouldEqual Response(outcome, replayed = true)
        actionRuns.get shouldEqual 1
        findWorkUnit(workUnit, fixture).map(_.modifiedAt) shouldEqual Some(firstUpdate.modifiedAt)
      } {
        drainSteps(requestFutures) :+ (() => fixtureOpt.foreach(fixture => cleanup(fixture, workUnitIdOpt, scopeOpt)))
      }
    }
  }

  "teardown lifecycle" should {
    "preserve a primary failure and suppress every teardown failure while still running cleanup" in {
      val primaryFailure        = new IllegalStateException("primary failure")
      val firstTeardownFailure  = new IllegalArgumentException("first teardown failure")
      val secondTeardownFailure = new IllegalStateException("second teardown failure")
      var events                = Vector.empty[String]

      val thrown = thrownBy {
        runWithTeardown[Unit] {
          events :+= "body"
          throw primaryFailure
        } {
          Seq[() => Unit](
            () => { events :+= "first teardown"; throw firstTeardownFailure },
            () => { events :+= "second teardown"; throw secondTeardownFailure },
            () => { events :+= "cleanup"; () }
          )
        }
      }

      thrown should beTheSameAs(primaryFailure)
      thrown.getSuppressed.toSeq shouldEqual Seq(firstTeardownFailure, secondTeardownFailure)
      events shouldEqual Seq("body", "first teardown", "second teardown", "cleanup")
    }

    "propagate the first teardown failure and suppress later failures after a successful body" in {
      val firstTeardownFailure  = new IllegalArgumentException("first teardown failure")
      val secondTeardownFailure = new IllegalStateException("second teardown failure")
      var events                = Vector.empty[String]

      val thrown = thrownBy {
        runWithTeardown {
          events :+= "body"
          "body result"
        } {
          Seq[() => Unit](
            () => { events :+= "first teardown"; throw firstTeardownFailure },
            () => { events :+= "second teardown"; throw secondTeardownFailure },
            () => { events :+= "cleanup"; () }
          )
        }
      }

      thrown should beTheSameAs(firstTeardownFailure)
      thrown.getSuppressed.toSeq shouldEqual Seq(secondTeardownFailure)
      events shouldEqual Seq("body", "first teardown", "second teardown", "cleanup")
    }

    "let an InterruptedException escape without running teardown or changing the thread interrupt flag" in {
      val interrupted = new InterruptedException("do not consume interrupts")
      var teardownRan = false

      val caught =
        try {
          runWithTeardown[Unit](throw interrupted) {
            Seq { () => teardownRan = true; () }
          }
          Option.empty[InterruptedException]
        } catch {
          case error: InterruptedException => Some(error)
        }

      caught shouldEqual Some(interrupted)
      teardownRan should beFalse
      Thread.currentThread().isInterrupted should beFalse
    }
  }

  step(closeDatabases())

  private def newService(repository: WorkUnitIdempotencyRepository): WorkUnitIdempotencyService = {
    val preflight = new WorkUnitIdempotencyPreflightService(organizationRepository, organizationUserRepository, workUnitRepository)
    new WorkUnitIdempotencyService(transactor, preflight, repository, new WorkUnitWriteOutcomeMapper(), WorkUnitIdempotencyConfig())
  }

  private def runRequest(
    service: WorkUnitIdempotencyService,
    scope: WorkUnitIdempotencyScope,
    command: WorkUnitWriteCommand,
    outcome: WorkUnitWriteOutcome,
    backendPid: Promise[Int]
  )(
    domainAction: => DBIO[WorkUnit]
  ): Future[WorkUnitIdempotencyResult] =
    transactor
      .executeTransactionally(IsolationLevel.ReadCommitted) {
        for {
          pid    <- sql"SELECT pg_backend_pid()".as[Int].head
          _      <- DBIO.successful(backendPid.trySuccess(pid))
          result <- service.execute(scope, command, (_: WorkUnit) => outcome)(domainAction)
        } yield result
      }
      .unsafeToFuture()

  private def setupFixture(): Fixture = {
    val user         = allGenerators[User].normal.generate.copy(archivedAt = None)
    val organization = allGenerators[Organization].normal.generate.copy(createdBy = user.id, modifiedBy = user.id, archivedAt = None)
    val client = allGenerators[Client].normal.generate.copy(
      organizationId = organization.id,
      createdBy = user.id,
      modifiedBy = user.id,
      archivedAt = None,
      archivedBy = None
    )
    val project = allGenerators[Project].normal.generate.copy(
      organizationId = organization.id,
      clientId = client.id,
      createdBy = user.id,
      modifiedBy = user.id,
      archivedAt = None,
      archivedBy = None
    )

    awaitDb(
      (for {
        storedUser         <- userRepository.insert(user)
        storedOrganization <- organizationRepository.insert(organization)
        storedClient       <- clientRepository.insert(client)
        storedProject      <- projectRepository.insert(project)
      } yield new Fixture(storedUser, storedOrganization, storedClient, storedProject)).transactionally,
      "committed test fixture setup"
    )
  }

  private def newWorkUnit(fixture: Fixture): WorkUnit =
    allGenerators[WorkUnit].normal.generate.copy(
      id = WorkUnitId(UUID.randomUUID()),
      userId = fixture.user.id,
      organizationId = fixture.organization.id,
      projectId = fixture.project.id,
      entryType = WorkUnitEntryType.Worklog,
      duration = WorkUnitDuration(Duration.ofMinutes(30)),
      state = WorkUnitState.Active,
      archivedAt = None
    )

  private def createRequest(workUnit: WorkUnit): WorkUnitCreateRequest =
    WorkUnitCreateRequest(
      projectId = workUnit.projectId,
      date = workUnit.date,
      duration = Some(workUnit.duration),
      description = workUnit.description,
      taskId = workUnit.taskId,
      isDraft = false
    )

  private def expectedOutcome(workUnit: WorkUnit): WorkUnitWriteOutcome =
    WorkUnitWriteOutcome(
      status = 201,
      body = Json.obj("id" -> Json.fromString(workUnit.id.toString), "stored" -> Json.True),
      resultWorkUnitId = Some(workUnit.id)
    )

  private def expectedCompleted(claim: NewWorkUnitIdempotencyClaim, outcome: WorkUnitWriteOutcome): Completed =
    Completed(
      claim.claimId,
      claim.scope,
      claim.operation,
      claim.fingerprintVersion,
      claim.canonicalRequest,
      claim.fingerprint,
      outcome,
      claim.createdAt,
      claim.createdAt.plusMillis(24.hours.toMillis)
    )

  private def completedRecordMatches(
    stored: Option[WorkUnitIdempotencyRecord],
    claim: NewWorkUnitIdempotencyClaim,
    outcome: WorkUnitWriteOutcome
  ): Boolean = {
    val minimumExpiry = claim.createdAt.plusMillis(24.hours.toMillis)
    stored.exists {
      case completed: Completed =>
        completed.copy(responseExpiresAt = minimumExpiry) == expectedCompleted(claim, outcome) &&
        !completed.responseExpiresAt.isBefore(minimumExpiry)
      case _ => false
    }
  }

  private def ledgerStates(scope: WorkUnitIdempotencyScope): Seq[String] =
    awaitDb(
      idempotencyTable
        .filter(row => row.organizationId === scope.organizationId && row.actorId === scope.actorId && row.idempotencyKey === scope.key)
        .map(_.state)
        .result,
      "ledger state query"
    )

  private def findLedgerRecord(scope: WorkUnitIdempotencyScope): Option[WorkUnitIdempotencyRecord] =
    awaitDb(idempotencyRepository.find(scope), "ledger record query")

  private def countWorkUnits(workUnit: WorkUnit, fixture: Fixture): Int =
    awaitDb(
      workUnitRepository.count(WorkUnitFilters(id = Some(workUnit.id), organizationId = Some(fixture.organization.id), includeArchived = true)),
      "work unit count query"
    )

  private def findWorkUnit(workUnit: WorkUnit, fixture: Fixture): Option[WorkUnit] =
    awaitDb(
      workUnitRepository.find(WorkUnitFilters(id = Some(workUnit.id), organizationId = Some(fixture.organization.id), includeArchived = true)),
      "work unit query"
    )

  private def cleanup(
    fixture: Fixture,
    workUnitId: Option[WorkUnitId],
    scope: Option[WorkUnitIdempotencyScope]
  ): Unit = {
    val organizationId = fixture.organization.id.asInstanceOf[UUID]
    val actorId        = fixture.user.id.asInstanceOf[UUID]
    val projectId      = fixture.project.id.asInstanceOf[UUID]
    val clientId       = fixture.client.id.asInstanceOf[UUID]
    val deleteLedger = scope match {
      case Some(value) =>
        val key = value.key.asInstanceOf[UUID]
        sqlu"""DELETE FROM work_unit_idempotency
                WHERE organization_id = $organizationId AND actor_id = $actorId AND idempotency_key = $key"""
      case None => DBIO.successful(0)
    }
    val deleteWorkUnit = workUnitId match {
      case Some(value) =>
        val rawWorkUnitId = value.asInstanceOf[UUID]
        sqlu"""DELETE FROM work_unit WHERE id = $rawWorkUnitId"""
      case None => DBIO.successful(0)
    }

    awaitDb(
      DBIO
        .seq(
          deleteLedger,
          deleteWorkUnit,
          sqlu"""DELETE FROM project WHERE id = $projectId""",
          sqlu"""DELETE FROM client WHERE id = $clientId""",
          sqlu"""DELETE FROM organization WHERE id = $organizationId""",
          sqlu"""DELETE FROM "user" WHERE id = $actorId"""
        )
        .transactionally,
      "committed per-test cleanup"
    )
  }

  private def awaitDirectBlock(firstBackendPid: Int, secondBackendPid: Int): (Int, Option[String], Option[String]) = {
    val deadline        = lockObservationTimeout.fromNow
    var lastObservation = Option.empty[(Int, Option[String], Option[String])]

    while (deadline.hasTimeLeft()) {
      lastObservation = awaitObserver(
        sql"""SELECT blocker.blocker_pid, activity.wait_event_type, activity.wait_event
               FROM pg_stat_activity AS activity
               CROSS JOIN LATERAL unnest(pg_blocking_pids(activity.pid)) AS blocker(blocker_pid)
               WHERE activity.pid = $secondBackendPid AND blocker.blocker_pid = $firstBackendPid"""
          .as[(Int, Option[String], Option[String])]
          .headOption,
        "direct PostgreSQL lock observation"
      )
      lastObservation match {
        case Some(observation) => return observation
        case None              => Thread.sleep(lockPollingInterval.toMillis)
      }
    }

    throw new AssertionError(
      s"Timed out after $lockObservationTimeout waiting for PostgreSQL backend $secondBackendPid to be blocked by exact backend " +
        s"$firstBackendPid; lastObservation=$lastObservation"
    )
  }

  private def requireResponse(result: WorkUnitIdempotencyResult): Response =
    result match {
      case response: Response => response
      case other              => throw new AssertionError(s"Expected an idempotent response, got ${other.getClass.getSimpleName}")
    }

  private def drainSteps(futures: Seq[(String, Future[_])]): Seq[() => Unit] =
    futures.map { case (clue, future) => () => drain(future, clue) }

  private def drain(future: Future[_], clue: String): Unit =
    try {
      Await.ready(future, requestTimeout)
      ()
    } catch {
      case timeout: TimeoutException => throw new AssertionError(s"Timed out draining $clue after $requestTimeout", timeout)
    }

  private def runWithTeardown[A](body: => A)(teardownSteps: => Seq[() => Unit]): A = {
    val bodyResult = capture(body)
    val teardownFailures = capture(teardownSteps) match {
      case Left(error) => Seq(error)
      case Right(steps) =>
        steps.flatMap(step => capture(step()).left.toOption)
    }

    bodyResult match {
      case Left(primaryFailure) =>
        teardownFailures.filterNot(_ eq primaryFailure).foreach(primaryFailure.addSuppressed)
        throw primaryFailure
      case Right(result) =>
        teardownFailures.headOption.foreach { firstFailure =>
          teardownFailures.tail.filterNot(_ eq firstFailure).foreach(firstFailure.addSuppressed)
          throw firstFailure
        }
        result
    }
  }

  private def capture[A](action: => A): Either[Throwable, A] =
    try Right(action)
    catch {
      case NonFatal(error) => Left(error)
    }

  private def thrownBy(action: => Any): Throwable =
    capture(action) match {
      case Left(error) => error
      case Right(_)    => throw new AssertionError("Expected the action to fail")
    }

  private def await[A](future: Future[A], clue: String): A =
    try Await.result(future, requestTimeout)
    catch {
      case timeout: TimeoutException => throw new AssertionError(s"Timed out waiting for $clue after $requestTimeout", timeout)
    }

  private def awaitDb[A](action: DBIO[A], clue: String): A =
    try Await.result(db.run(action), setupCleanupTimeout)
    catch {
      case timeout: TimeoutException => throw new AssertionError(s"Timed out waiting for $clue after $setupCleanupTimeout", timeout)
    }

  private def awaitObserver[A](action: DBIO[A], clue: String): A =
    try Await.result(observerDb.run(action), setupCleanupTimeout)
    catch {
      case timeout: TimeoutException => throw new AssertionError(s"Timed out waiting for $clue after $setupCleanupTimeout", timeout)
    }

  private def closeDatabases(): Unit =
    runWithTeardown(db.close()) {
      Seq(() => observerDb.close())
    }

  private final class Fixture(
    val user: User,
    val organization: Organization,
    val client: Client,
    val project: Project)

  private final class CapturingClaimRepository(delegate: WorkUnitIdempotencyRepository) extends WorkUnitIdempotencyRepository {
    private val claims = new ConcurrentLinkedQueue[NewWorkUnitIdempotencyClaim]()

    def capturedClaims: Seq[NewWorkUnitIdempotencyClaim] = claims.iterator().asScala.toVector

    override def claim(claim: NewWorkUnitIdempotencyClaim): DBIO[WorkUnitIdempotencyClaimResult] =
      DBIO.successful(()).flatMap { _ =>
        claims.add(claim)
        delegate.claim(claim)
      }

    override def complete(
      scope: WorkUnitIdempotencyScope,
      outcome: WorkUnitWriteOutcome,
      expiresAt: java.time.Instant
    ): DBIO[Int] =
      delegate.complete(scope, outcome, expiresAt)

    override def redactExpired(now: java.time.Instant): DBIO[Int] = delegate.redactExpired(now)

    override def find(scope: WorkUnitIdempotencyScope): DBIO[Option[WorkUnitIdempotencyRecord]] = delegate.find(scope)
  }
}
