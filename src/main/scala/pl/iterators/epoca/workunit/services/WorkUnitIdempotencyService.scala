package pl.iterators.epoca.workunit.services

import cats.effect.unsafe.IORuntime
import cats.effect.{Clock, IO}
import com.typesafe.config.Config
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.utils.db.DBIOInstances._
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.random.IdGenerator
import pl.iterators.epoca.utils.transactor.IsolationLevel
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.WorkUnitId
import pl.iterators.epoca.workunit.idempotency.WorkUnitCommandCanonicalizer.CanonicalizationError
import pl.iterators.epoca.workunit.idempotency.WorkUnitIdempotencyClaimResult.{Claimed, Existing}
import pl.iterators.epoca.workunit.idempotency.WorkUnitIdempotencyDomain._
import pl.iterators.epoca.workunit.idempotency.WorkUnitIdempotencyOperation.{Create, Update}
import pl.iterators.epoca.workunit.idempotency.WorkUnitIdempotencyRecord.{Completed, Processing, Tombstone}
import pl.iterators.epoca.workunit.idempotency.WorkUnitIdempotencyResult.{KeyConflict, Response, ResultExpired, VersionUnsupported}
import pl.iterators.epoca.workunit.idempotency.{
  NewWorkUnitIdempotencyClaim,
  WorkUnitCommandCanonicalizer,
  WorkUnitIdempotencyRecord,
  WorkUnitIdempotencyResult,
  WorkUnitIdempotencyScope,
  WorkUnitWriteCommand,
  WorkUnitWriteOutcome
}
import pl.iterators.epoca.workunit.repositories.WorkUnitIdempotencyRepository
import pl.iterators.epoca.workunit.services.WorkUnitCreateService.{WorkUnitCreateRequest, WorkUnitCreateResult}
import pl.iterators.epoca.workunit.services.WorkUnitIdempotencyPreflightService.{CreatePreflightResult, UpdatePreflightResult}
import pl.iterators.epoca.workunit.services.WorkUnitIdempotencyService.WorkUnitIdempotencyConfig
import pl.iterators.epoca.workunit.services.WorkUnitUpdateService.{WorkUnitUpdateRequest, WorkUnitUpdateResult}
import slick.dbio.DBIO

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.jdk.DurationConverters._

class WorkUnitIdempotencyService(
  transactor: PostgresIOTransactor,
  preflightService: WorkUnitIdempotencyPreflightService,
  repository: WorkUnitIdempotencyRepository,
  outcomeMapper: WorkUnitWriteOutcomeMapper,
  config: WorkUnitIdempotencyConfig
)(implicit
  runtime: IORuntime,
  clock: Clock[DBIO]) {
  private implicit val ec: ExecutionContext = runtime.compute

  def create(
    authContext: AuthContext,
    organizationSlug: OrganizationSlug,
    key: WorkUnitIdempotencyKey,
    request: WorkUnitCreateRequest,
    correlationId: String
  )(
    domainAction: => DBIO[WorkUnitCreateResult]
  ): IO[WorkUnitIdempotencyResult] =
    transactor.executeTransactionally(IsolationLevel.ReadCommitted) {
      preflightService.preflightCreate(authContext, organizationSlug).flatMap {
        case CreatePreflightResult.Allowed(organizationId) =>
          execute(
            WorkUnitIdempotencyScope(organizationId, authContext.id, key),
            WorkUnitWriteCommand.Create(request),
            result => outcomeMapper.create(result, correlationId)
          )(domainAction)
        case CreatePreflightResult.OrganizationNotFound =>
          preflightFailure(outcomeMapper.create(WorkUnitCreateResult.OrganizationNotFound, correlationId))
        case CreatePreflightResult.NoAccessToOrganization =>
          preflightFailure(outcomeMapper.create(WorkUnitCreateResult.NoAccessToOrganization, correlationId))
      }
    }

  def update(
    authContext: AuthContext,
    organizationSlug: OrganizationSlug,
    workUnitId: WorkUnitId,
    key: WorkUnitIdempotencyKey,
    request: WorkUnitUpdateRequest,
    correlationId: String
  )(
    domainAction: => DBIO[WorkUnitUpdateResult]
  ): IO[WorkUnitIdempotencyResult] =
    transactor.executeTransactionally(IsolationLevel.ReadCommitted) {
      preflightService.preflightUpdate(authContext, organizationSlug, workUnitId).flatMap {
        case UpdatePreflightResult.Allowed(organizationId) =>
          execute(
            WorkUnitIdempotencyScope(organizationId, authContext.id, key),
            WorkUnitWriteCommand.Update(workUnitId, request),
            result => outcomeMapper.update(result, correlationId)
          )(domainAction)
        case UpdatePreflightResult.OrganizationNotFound =>
          preflightFailure(outcomeMapper.update(WorkUnitUpdateResult.OrganizationNotFound, correlationId))
        case UpdatePreflightResult.NoAccessToOrganization =>
          preflightFailure(outcomeMapper.update(WorkUnitUpdateResult.NoAccessToOrganization, correlationId))
        case UpdatePreflightResult.WorkUnitNotFound =>
          preflightFailure(outcomeMapper.update(WorkUnitUpdateResult.WorkUnitNotFound, correlationId))
        case UpdatePreflightResult.NoAccessToWorkUnit =>
          preflightFailure(outcomeMapper.update(WorkUnitUpdateResult.NoAccessToWorkUnit, correlationId))
      }
    }

  private[epoca] def execute[Result](
    scope: WorkUnitIdempotencyScope,
    command: WorkUnitWriteCommand,
    mapOutcome: Result => WorkUnitWriteOutcome
  )(
    domainAction: => DBIO[Result]
  ): DBIO[WorkUnitIdempotencyResult] = {
    val version = WorkUnitCommandCanonicalizer.versionForNewClaim(scope.actorId, command)
    WorkUnitCommandCanonicalizer.canonicalize(version, scope.actorId, command) match {
      case Left(CanonicalizationError.V1NotRepresentable) => DBIO.successful(KeyConflict)
      case Left(CanonicalizationError.VersionUnsupported) => DBIO.successful(VersionUnsupported)
      case Right(canonical) =>
        val operation = command match {
          case WorkUnitWriteCommand.Create(_)             => Create
          case WorkUnitWriteCommand.Update(workUnitId, _) => Update(workUnitId)
        }
        for {
          claimedAt <- clock.realTime.map(duration => java.time.Instant.ofEpochMilli(duration.toMillis))
          claimId   <- IdGenerator.nextId[DBIO, WorkUnitIdempotencyClaimId]
          claim = NewWorkUnitIdempotencyClaim(claimId, scope, operation, canonical.version, canonical.json, canonical.fingerprint, claimedAt)
          claimResult <- repository.claim(claim)
          result <- claimResult match {
                      case Claimed(_)       => completeNewClaim(scope, mapOutcome)(domainAction)
                      case Existing(record) => replay(command, record)
                    }
        } yield result
    }
  }

  private def completeNewClaim[Result](scope: WorkUnitIdempotencyScope, mapOutcome: Result => WorkUnitWriteOutcome)(domainAction: => DBIO[Result])
    : DBIO[WorkUnitIdempotencyResult] =
    for {
      domainResult <- domainAction
      outcome = mapOutcome(domainResult)
      completedAt <- clock.realTime.map(duration => java.time.Instant.ofEpochMilli(duration.toMillis))
      expiresAt = completedAt.plusMillis(config.responseRetention.toMillis)
      _ <- repository.complete(scope, outcome, expiresAt)
    } yield Response(outcome, replayed = false)

  private def replay(command: WorkUnitWriteCommand, record: WorkUnitIdempotencyRecord): DBIO[WorkUnitIdempotencyResult] =
    WorkUnitCommandCanonicalizer.canonicalize(record.fingerprintVersion, record.scope.actorId, command) match {
      case Left(CanonicalizationError.V1NotRepresentable)                  => DBIO.successful(KeyConflict)
      case Left(CanonicalizationError.VersionUnsupported)                  => DBIO.successful(VersionUnsupported)
      case Right(canonical) if canonical.fingerprint != record.fingerprint => DBIO.successful(KeyConflict)
      case Right(_) =>
        record match {
          case completed: Completed => DBIO.successful(Response(completed.outcome, replayed = true))
          case _: Tombstone         => DBIO.successful(ResultExpired)
          case processing: Processing =>
            DBIO.failed(WorkUnitIdempotencyService.WorkUnitIdempotencyProcessingInvariantViolation(processing.scope))
        }
    }

  private def preflightFailure(outcome: WorkUnitWriteOutcome): DBIO[WorkUnitIdempotencyResult] =
    DBIO.successful(Response(outcome, replayed = false))
}

object WorkUnitIdempotencyService {
  final case class WorkUnitIdempotencyConfig(responseRetention: FiniteDuration = 24.hours) {
    require(responseRetention >= 24.hours, "Work unit idempotency response retention must be at least 24 hours")
  }

  object WorkUnitIdempotencyConfig {
    def apply(config: Config): WorkUnitIdempotencyConfig =
      WorkUnitIdempotencyConfig(config.getDuration("workUnit.idempotency.responseRetention").toScala)
  }

  final case class WorkUnitIdempotencyProcessingInvariantViolation(scope: WorkUnitIdempotencyScope)
      extends RuntimeException("A committed Processing work unit idempotency record is not replayable")
}
