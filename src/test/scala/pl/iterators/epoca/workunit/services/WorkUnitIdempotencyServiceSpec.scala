package pl.iterators.epoca.workunit.services

import cats.effect.unsafe.IORuntime
import cats.effect.{Clock, IO}
import io.circe.Json
import org.mockito.invocation.InvocationOnMock
import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationId, OrganizationSlug}
import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.utils.ServiceSpec
import pl.iterators.epoca.utils.transactor.{FakeTransactor, IsolationLevel}
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.{WorkUnitDuration, WorkUnitEntryType, WorkUnitId}
import pl.iterators.epoca.workunit.idempotency.WorkUnitCommandCanonicalizer.{Version1, Version2}
import pl.iterators.epoca.workunit.idempotency.WorkUnitIdempotencyClaimResult.{Claimed, Existing}
import pl.iterators.epoca.workunit.idempotency.WorkUnitIdempotencyDomain._
import pl.iterators.epoca.workunit.idempotency.WorkUnitIdempotencyOperation.{Create, Update}
import pl.iterators.epoca.workunit.idempotency.WorkUnitIdempotencyRecord.{Completed, Processing, Tombstone}
import pl.iterators.epoca.workunit.idempotency.WorkUnitIdempotencyResult.{KeyConflict, Response, ResultExpired, VersionUnsupported}
import pl.iterators.epoca.workunit.idempotency.{
  CanonicalWorkUnitCommand,
  NewWorkUnitIdempotencyClaim,
  WorkUnitCommandCanonicalizer,
  WorkUnitIdempotencyOperation,
  WorkUnitIdempotencyScope,
  WorkUnitWriteCommand,
  WorkUnitWriteOutcome
}
import pl.iterators.epoca.workunit.repositories.WorkUnitIdempotencyRepository
import pl.iterators.epoca.workunit.services.WorkUnitCreateService.{WorkUnitCreateRequest, WorkUnitCreateResult}
import pl.iterators.epoca.workunit.services.WorkUnitDtoService.WorkUnitDto
import pl.iterators.epoca.workunit.services.WorkUnitIdempotencyPreflightService.{CreatePreflightResult, UpdatePreflightResult}
import pl.iterators.epoca.workunit.services.WorkUnitIdempotencyService.{WorkUnitIdempotencyConfig, WorkUnitIdempotencyProcessingInvariantViolation}
import pl.iterators.epoca.workunit.services.WorkUnitIdempotencyServiceSpec.RecordingFakeTransactor
import pl.iterators.epoca.workunit.services.WorkUnitUpdateService.{WorkUnitUpdateRequest, WorkUnitUpdateResult}
import slick.dbio.DBIO

import java.time.Duration
import java.util.UUID
import scala.concurrent.duration._

class WorkUnitIdempotencyServiceSpec extends ServiceSpec {
  lazy val organizationIdGenerator: AllGenerators[OrganizationId]       = allGenerators[OrganizationId]
  lazy val orgSlugGenerator: AllGenerators[OrganizationSlug]            = allGenerators[OrganizationSlug]
  lazy val createRequestGenerator: AllGenerators[WorkUnitCreateRequest] = allGenerators[WorkUnitCreateRequest]
  lazy val updateRequestGenerator: AllGenerators[WorkUnitUpdateRequest] = allGenerators[WorkUnitUpdateRequest]
  lazy val workUnitDtoGenerator: AllGenerators[WorkUnitDto]             = allGenerators[WorkUnitDto]
  lazy val workUnitIdGenerator: AllGenerators[WorkUnitId]               = allGenerators[WorkUnitId]

  private val key           = WorkUnitIdempotencyKey(UUID.fromString("11111111-1111-1111-1111-111111111111"))
  private val correlationId = "correlation-123"

  trait TestCase extends FixedTimeBaseScope {
    val preflightService: WorkUnitIdempotencyPreflightService = vmock[WorkUnitIdempotencyPreflightService]
    val repository: WorkUnitIdempotencyRepository             = vmock[WorkUnitIdempotencyRepository]
    val outcomeMapper                                         = new WorkUnitWriteOutcomeMapper()
    val config: WorkUnitIdempotencyConfig                     = WorkUnitIdempotencyConfig()
    val recordingTransactor                                   = new RecordingFakeTransactor()

    val sut: WorkUnitIdempotencyService =
      new WorkUnitIdempotencyService(recordingTransactor, preflightService, repository, outcomeMapper, config)

    val organizationId: OrganizationId     = organizationIdGenerator.normal.generate
    val organizationSlug: OrganizationSlug = orgSlugGenerator.normal.generate
    val createRequest: WorkUnitCreateRequest =
      createRequestGenerator.normal.generate.copy(duration = Some(WorkUnitDuration(Duration.ofMinutes(30))), entryType = None, userId = None)
    val scope: WorkUnitIdempotencyScope = WorkUnitIdempotencyScope(organizationId, authUser.id, key)

    def allowCreate(): Unit = {
      when(preflightService.preflightCreate(authUser, organizationSlug))
        .thenReturn(CreatePreflightResult.Allowed(organizationId).asDBIO)
      ()
    }

    def canonicalCreate: CanonicalWorkUnitCommand =
      WorkUnitCommandCanonicalizer.canonicalize(Version1, authUser.id, WorkUnitWriteCommand.Create(createRequest)).toOption.get

    def noteRequest(duration: Option[WorkUnitDuration] = None): WorkUnitCreateRequest =
      createRequest.copy(duration = duration, entryType = Some(WorkUnitEntryType.Note))

    def completed(
      outcome: WorkUnitWriteOutcome,
      version: WorkUnitIdempotencyFingerprintVersion = Version1,
      fingerprint: WorkUnitIdempotencyFingerprint = canonicalCreate.fingerprint,
      canonicalRequest: Json = canonicalCreate.json
    ): Completed =
      Completed(
        WorkUnitIdempotencyClaimId(UUID.randomUUID()),
        scope,
        Create,
        version,
        canonicalRequest,
        fingerprint,
        outcome,
        now.minusSeconds(10),
        now.plusSeconds(3600)
      )

    def processing(fingerprint: WorkUnitIdempotencyFingerprint = canonicalCreate.fingerprint): Processing =
      Processing(WorkUnitIdempotencyClaimId(UUID.randomUUID()), scope, Create, Version1, canonicalCreate.json, fingerprint, now.minusSeconds(10))
  }

  "configuration" should {
    "default response retention to 24 hours and reject shorter retention" in {
      WorkUnitIdempotencyConfig().responseRetention shouldEqual 24.hours
      WorkUnitIdempotencyConfig(48.hours).responseRetention shouldEqual 48.hours
      WorkUnitIdempotencyConfig(23.hours) must throwA[IllegalArgumentException]
    }
  }

  "create" should {
    "run preflight before claim and use a single read-committed transaction" in new TestCase {
      allowCreate()
      val storedOutcome = WorkUnitWriteOutcome(201, Json.obj("stored" -> Json.True), None)
      when(repository.claim(any)).thenReturn(Existing(completed(storedOutcome)).asDBIO)

      sut
        .create(authUser, organizationSlug, key, createRequest, correlationId)(DBIO.failed(new AssertionError("must not run")))
        .asserting(_ shouldEqual Response(storedOutcome, replayed = true))

      val ordered = inOrder(preflightService, repository)
      ordered.verify(preflightService).preflightCreate(authUser, organizationSlug)
      ordered.verify(repository).claim(any)
      recordingTransactor.isolationLevels shouldEqual Vector(IsolationLevel.ReadCommitted)
    }

    "map denied preflight without claiming or evaluating the action" in new TestCase {
      when(preflightService.preflightCreate(authUser, organizationSlug))
        .thenReturn(CreatePreflightResult.NoAccessToOrganization.asDBIO)
      var actionEvaluations = 0

      val result = sut.create(authUser, organizationSlug, key, createRequest, correlationId) {
        actionEvaluations += 1
        WorkUnitCreateResult.NonPositiveDuration.asDBIO
      }

      result.asserting(_ shouldEqual Response(outcomeMapper.create(WorkUnitCreateResult.NoAccessToOrganization, correlationId), replayed = false))
      actionEvaluations shouldEqual 0
      verify(preflightService).preflightCreate(authUser, organizationSlug)
    }

    "claim exact current command data, run the action once, complete once, and retain the response for 24 hours from completion" in new TestCase {
      allowCreate()
      val dto             = workUnitDtoGenerator.maximal.generate
      val expectedOutcome = outcomeMapper.create(WorkUnitCreateResult.Created(dto), correlationId)
      val completedAt     = now.plusSeconds(2.hours.toSeconds)
      val lifecycleClock  = mock[Clock[DBIO]]
      val lifecycleSut =
        new WorkUnitIdempotencyService(recordingTransactor, preflightService, repository, outcomeMapper, config)(ioRuntime, lifecycleClock)
      var actionRuns                                         = 0
      var capturedClaim: Option[NewWorkUnitIdempotencyClaim] = None
      var capturedExpiresAt: Option[java.time.Instant]       = None
      when(lifecycleClock.realTime).thenReturn(now.toEpochMilli.millis.asDBIO, completedAt.toEpochMilli.millis.asDBIO)
      when(repository.claim(any)).thenAnswer { (invocation: InvocationOnMock) =>
        val claim = invocation.getArgument[NewWorkUnitIdempotencyClaim](0)
        capturedClaim = Some(claim)
        Claimed(
          Processing(
            claim.claimId,
            claim.scope,
            claim.operation,
            claim.fingerprintVersion,
            claim.canonicalRequest,
            claim.fingerprint,
            claim.createdAt
          )
        ).asDBIO
      }
      when(repository.complete(any, any, any)).thenAnswer { (invocation: InvocationOnMock) =>
        capturedExpiresAt = Some(invocation.getArgument[java.time.Instant](2))
        1.asDBIO
      }

      lifecycleSut
        .create(authUser, organizationSlug, key, createRequest, correlationId) {
          actionRuns += 1
          WorkUnitCreateResult.Created(dto).asDBIO
        }
        .asserting(_ shouldEqual Response(expectedOutcome, replayed = false))

      val canonical = canonicalCreate
      capturedClaim.map(_.scope) shouldEqual Some(scope)
      capturedClaim.map(_.operation) shouldEqual Some(Create)
      capturedClaim.map(_.fingerprintVersion) shouldEqual Some(Version1)
      capturedClaim.map(_.canonicalRequest) shouldEqual Some(canonical.json)
      capturedClaim.map(_.fingerprint) shouldEqual Some(canonical.fingerprint)
      capturedClaim.map(_.createdAt) shouldEqual Some(now)
      capturedExpiresAt shouldEqual Some(completedAt.plusSeconds(24.hours.toSeconds))
      actionRuns shouldEqual 1
      verify(preflightService).preflightCreate(authUser, organizationSlug)
      verify(repository).claim(any)
      verify(repository).complete(scope, expectedOutcome, completedAt.plusSeconds(24.hours.toSeconds))
    }

    "select V2 and claim normalized Note command data" in new TestCase {
      allowCreate()
      val request         = noteRequest()
      val command         = WorkUnitWriteCommand.Create(request)
      val canonical       = WorkUnitCommandCanonicalizer.canonicalize(Version2, authUser.id, command).toOption.get
      val expectedOutcome = outcomeMapper.create(WorkUnitCreateResult.ProjectNotFound, correlationId)
      var capturedClaim   = Option.empty[NewWorkUnitIdempotencyClaim]
      when(repository.claim(any)).thenAnswer { (invocation: InvocationOnMock) =>
        val claim = invocation.getArgument[NewWorkUnitIdempotencyClaim](0)
        capturedClaim = Some(claim)
        Claimed(
          Processing(
            claim.claimId,
            claim.scope,
            claim.operation,
            claim.fingerprintVersion,
            claim.canonicalRequest,
            claim.fingerprint,
            claim.createdAt
          )
        ).asDBIO
      }
      when(repository.complete(scope, expectedOutcome, now.plusSeconds(24.hours.toSeconds))).thenReturn(1.asDBIO)

      sut
        .create(authUser, organizationSlug, key, request, correlationId)(WorkUnitCreateResult.ProjectNotFound.asDBIO)
        .asserting(_ shouldEqual Response(expectedOutcome, replayed = false))

      capturedClaim.map(_.fingerprintVersion) shouldEqual Some(Version2)
      capturedClaim.map(_.canonicalRequest) shouldEqual Some(canonical.json)
      capturedClaim.map(_.fingerprint) shouldEqual Some(canonical.fingerprint)
      verify(preflightService).preflightCreate(authUser, organizationSlug)
      verify(repository).claim(any)
      verify(repository).complete(scope, expectedOutcome, now.plusSeconds(24.hours.toSeconds))
    }

    "replay the exact historical Completed snapshot without adding entryType or PM classification" in new TestCase {
      allowCreate()
      val historicalBody = Json.obj("id" -> Json.fromString("historical-without-entry-type"))
      val storedOutcome  = WorkUnitWriteOutcome(422, historicalBody, None)
      when(repository.claim(any)).thenReturn(Existing(completed(storedOutcome)).asDBIO)
      var actionEvaluations = 0

      sut
        .create(authUser, organizationSlug, key, createRequest, correlationId) {
          actionEvaluations += 1
          WorkUnitCreateResult.NonPositiveDuration.asDBIO
        }
        .asserting { result =>
          result shouldEqual Response(storedOutcome, replayed = true)
          result.asInstanceOf[Response].outcome.body.asObject.get.contains("pmClassification") should beFalse
        }

      actionEvaluations shouldEqual 0
      verify(preflightService).preflightCreate(authUser, organizationSlug)
      verify(repository).claim(any)
    }

    "replay a V2 Note across missing and explicit zero duration aliases" in new TestCase {
      allowCreate()
      val originalCommand = WorkUnitWriteCommand.Create(noteRequest())
      val retryRequest    = noteRequest(Some(WorkUnitDuration(Duration.ZERO)))
      val canonical       = WorkUnitCommandCanonicalizer.canonicalize(Version2, authUser.id, originalCommand).toOption.get
      val storedOutcome   = WorkUnitWriteOutcome(201, Json.obj("stored" -> Json.True), None)
      when(repository.claim(any)).thenReturn(
        Existing(completed(storedOutcome, version = Version2, fingerprint = canonical.fingerprint, canonicalRequest = canonical.json)).asDBIO
      )
      var actionEvaluations = 0

      sut
        .create(authUser, organizationSlug, key, retryRequest, correlationId) {
          actionEvaluations += 1
          WorkUnitCreateResult.NonNeutralNoteDuration.asDBIO
        }
        .asserting(_ shouldEqual Response(storedOutcome, replayed = true))

      actionEvaluations shouldEqual 0
      verify(preflightService).preflightCreate(authUser, organizationSlug)
      verify(repository).claim(any)
    }

    "return KeyConflict when a V1 record is retried as a Note" in new TestCase {
      allowCreate()
      val storedOutcome = WorkUnitWriteOutcome(201, Json.obj("legacy" -> Json.True), None)
      when(repository.claim(any)).thenReturn(Existing(completed(storedOutcome)).asDBIO)
      var actionEvaluations = 0

      sut
        .create(authUser, organizationSlug, key, noteRequest(), correlationId) {
          actionEvaluations += 1
          WorkUnitCreateResult.NonNeutralNoteDuration.asDBIO
        }
        .asserting(_ shouldEqual KeyConflict)

      actionEvaluations shouldEqual 0
      verify(preflightService).preflightCreate(authUser, organizationSlug)
      verify(repository).claim(any)
    }

    "return ResultExpired for a matching Tombstone without evaluating the action" in new TestCase {
      allowCreate()
      val tombstone = Tombstone(
        WorkUnitIdempotencyClaimId(UUID.randomUUID()),
        scope,
        Create,
        Version1,
        canonicalCreate.fingerprint,
        201,
        None,
        now.minusSeconds(100),
        now.minusSeconds(1)
      )
      when(repository.claim(any)).thenReturn(Existing(tombstone).asDBIO)
      var actionEvaluations = 0

      sut
        .create(authUser, organizationSlug, key, createRequest, correlationId) {
          actionEvaluations += 1
          WorkUnitCreateResult.NonPositiveDuration.asDBIO
        }
        .asserting(_ shouldEqual ResultExpired)

      actionEvaluations shouldEqual 0
      verify(preflightService).preflightCreate(authUser, organizationSlug)
      verify(repository).claim(any)
    }

    "return KeyConflict for a fingerprint mismatch without evaluating the action" in new TestCase {
      allowCreate()
      val mismatch = WorkUnitIdempotencyFingerprint("f" * 64)
      when(repository.claim(any)).thenReturn(Existing(completed(WorkUnitWriteOutcome(201, Json.obj(), None), fingerprint = mismatch)).asDBIO)
      var actionEvaluations = 0

      sut
        .create(authUser, organizationSlug, key, createRequest, correlationId) {
          actionEvaluations += 1
          WorkUnitCreateResult.NonPositiveDuration.asDBIO
        }
        .asserting(_ shouldEqual KeyConflict)

      actionEvaluations shouldEqual 0
      verify(preflightService).preflightCreate(authUser, organizationSlug)
      verify(repository).claim(any)
    }

    "return VersionUnsupported before comparing a stored unsupported version" in new TestCase {
      allowCreate()
      val unsupported = WorkUnitIdempotencyFingerprintVersion(99)
      when(repository.claim(any))
        .thenReturn(Existing(completed(WorkUnitWriteOutcome(201, Json.obj(), None), version = unsupported)).asDBIO)
      var actionEvaluations = 0

      sut
        .create(authUser, organizationSlug, key, createRequest, correlationId) {
          actionEvaluations += 1
          WorkUnitCreateResult.NonPositiveDuration.asDBIO
        }
        .asserting(_ shouldEqual VersionUnsupported)

      actionEvaluations shouldEqual 0
      verify(preflightService).preflightCreate(authUser, organizationSlug)
      verify(repository).claim(any)
    }

    "fail on an Existing Processing invariant without evaluating the action" in new TestCase {
      allowCreate()
      when(repository.claim(any)).thenReturn(Existing(processing()).asDBIO)
      var actionEvaluations = 0

      sut
        .create(authUser, organizationSlug, key, createRequest, correlationId) {
          actionEvaluations += 1
          WorkUnitCreateResult.NonPositiveDuration.asDBIO
        }
        .attempt
        .asserting {
          case Left(error) => error should beAnInstanceOf[WorkUnitIdempotencyProcessingInvariantViolation]
          case Right(_)    => false should beTrue
        }

      actionEvaluations shouldEqual 0
      verify(preflightService).preflightCreate(authUser, organizationSlug)
      verify(repository).claim(any)
    }

    "leave an action exception failed and do not complete" in new TestCase {
      allowCreate()
      when(repository.claim(any)).thenAnswer { (invocation: InvocationOnMock) =>
        val claim = invocation.getArgument[NewWorkUnitIdempotencyClaim](0)
        Claimed(
          Processing(
            claim.claimId,
            claim.scope,
            claim.operation,
            claim.fingerprintVersion,
            claim.canonicalRequest,
            claim.fingerprint,
            claim.createdAt
          )
        ).asDBIO
      }
      val expected = new IllegalStateException("domain action failed")

      sut.create(authUser, organizationSlug, key, createRequest, correlationId)(DBIO.failed(expected)).attempt.asserting {
        case Left(actual) => actual should beTheSameAs(expected)
        case Right(_)     => false should beTrue
      }

      verify(preflightService).preflightCreate(authUser, organizationSlug)
      verify(repository).claim(any)
    }

    "terminalize an expected 4xx result" in new TestCase {
      allowCreate()
      val expectedOutcome = outcomeMapper.create(WorkUnitCreateResult.ProjectNotFound, correlationId)
      when(repository.claim(any)).thenAnswer { (invocation: InvocationOnMock) =>
        val claim = invocation.getArgument[NewWorkUnitIdempotencyClaim](0)
        Claimed(
          Processing(
            claim.claimId,
            claim.scope,
            claim.operation,
            claim.fingerprintVersion,
            claim.canonicalRequest,
            claim.fingerprint,
            claim.createdAt
          )
        ).asDBIO
      }
      when(repository.complete(scope, expectedOutcome, now.plusSeconds(24.hours.toSeconds))).thenReturn(1.asDBIO)

      sut
        .create(authUser, organizationSlug, key, createRequest, correlationId)(WorkUnitCreateResult.ProjectNotFound.asDBIO)
        .asserting(_ shouldEqual Response(expectedOutcome, replayed = false))

      verify(preflightService).preflightCreate(authUser, organizationSlug)
      verify(repository).claim(any)
      verify(repository).complete(scope, expectedOutcome, now.plusSeconds(24.hours.toSeconds))
    }
  }

  "update" should {
    "map a denied target preflight without claiming or evaluating the action" in new TestCase {
      val workUnitId = workUnitIdGenerator.normal.generate
      val request    = updateRequestGenerator.normal.generate
      when(preflightService.preflightUpdate(authUser, organizationSlug, workUnitId))
        .thenReturn(UpdatePreflightResult.NoAccessToWorkUnit.asDBIO)
      var actionEvaluations = 0

      sut
        .update(authUser, organizationSlug, workUnitId, key, request, correlationId) {
          actionEvaluations += 1
          WorkUnitUpdateResult.NonPositiveDuration.asDBIO
        }
        .asserting(_ shouldEqual Response(outcomeMapper.update(WorkUnitUpdateResult.NoAccessToWorkUnit, correlationId), replayed = false))

      actionEvaluations shouldEqual 0
      verify(preflightService).preflightUpdate(authUser, organizationSlug, workUnitId)
    }

    "claim an Update command and terminalize its mapped result" in new TestCase {
      val workUnitId                                              = workUnitIdGenerator.normal.generate
      val request                                                 = updateRequestGenerator.normal.generate
      val dto                                                     = workUnitDtoGenerator.normal.generate
      val updateScope                                             = WorkUnitIdempotencyScope(organizationId, authUser.id, key)
      val expectedOutcome                                         = outcomeMapper.update(WorkUnitUpdateResult.Updated(dto), correlationId)
      var capturedOperation: Option[WorkUnitIdempotencyOperation] = None
      when(preflightService.preflightUpdate(authUser, organizationSlug, workUnitId))
        .thenReturn(UpdatePreflightResult.Allowed(organizationId).asDBIO)
      when(repository.claim(any)).thenAnswer { (invocation: InvocationOnMock) =>
        val claim = invocation.getArgument[NewWorkUnitIdempotencyClaim](0)
        capturedOperation = Some(claim.operation)
        Claimed(
          Processing(
            claim.claimId,
            claim.scope,
            claim.operation,
            claim.fingerprintVersion,
            claim.canonicalRequest,
            claim.fingerprint,
            claim.createdAt
          )
        ).asDBIO
      }
      when(repository.complete(updateScope, expectedOutcome, now.plusSeconds(24.hours.toSeconds))).thenReturn(1.asDBIO)

      sut
        .update(authUser, organizationSlug, workUnitId, key, request, correlationId)(WorkUnitUpdateResult.Updated(dto).asDBIO)
        .asserting(_ shouldEqual Response(expectedOutcome, replayed = false))

      capturedOperation shouldEqual Some(Update(workUnitId))
      verify(preflightService).preflightUpdate(authUser, organizationSlug, workUnitId)
      verify(repository).claim(any)
      verify(repository).complete(updateScope, expectedOutcome, now.plusSeconds(24.hours.toSeconds))
    }
  }
}

object WorkUnitIdempotencyServiceSpec {
  final class RecordingFakeTransactor(implicit runtime: IORuntime) extends FakeTransactor {
    var isolationLevels: Vector[IsolationLevel] = Vector.empty

    override def executeTransactionally[T](level: IsolationLevel)(action: => DBIO[T]): IO[T] = {
      isolationLevels :+= level
      super.executeTransactionally(level)(action)
    }
  }
}
