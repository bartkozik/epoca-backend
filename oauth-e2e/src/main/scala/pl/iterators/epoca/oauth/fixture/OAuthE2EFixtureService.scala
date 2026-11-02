package pl.iterators.epoca.oauth.fixture

import cats.effect.IO
import cats.syntax.all._
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.client.domain.Client
import pl.iterators.epoca.client.domain.ClientDomain._
import pl.iterators.epoca.client.repositories.ClientRepository
import pl.iterators.epoca.client.repositories.ClientRepository.ClientFilters
import pl.iterators.epoca.oauth.domain.OAuthDomain._
import pl.iterators.epoca.oauth.fixture.OAuthE2EFixtureCommandExecutor.ExecutionResult
import pl.iterators.epoca.oauth.fixture.OAuthE2EFixtureControlProtocol.ControlRequestContext
import pl.iterators.epoca.oauth.fixture.OAuthE2EFixtureDomain.CleanupCompletionResult.{
  AlreadyCleaned => PersistenceAlreadyCleaned,
  Cleaned => PersistenceCleaned,
  Conflict => PersistenceCleanupCompletionConflict
}
import pl.iterators.epoca.oauth.fixture.OAuthE2EFixtureDomain.CleanupStartResult.{
  AlreadyCleaned => PersistenceCleanupAlreadyCleaned,
  Applied => PersistenceCleanupApplied,
  Conflict => PersistenceCleanupConflict,
  Resumable => PersistenceCleanupResumable
}
import pl.iterators.epoca.oauth.fixture.OAuthE2EFixtureDomain.CleanupTransitionResult.{
  Applied => CleanupStepApplied,
  Idempotent => CleanupStepIdempotent
}
import pl.iterators.epoca.oauth.fixture.OAuthE2EFixtureDomain.FixtureSetupPersistenceResult.{
  Applied => PersistenceApplied,
  CommandBudgetExhausted => PersistenceCommandBudgetExhausted,
  Conflict => PersistenceConflict,
  CredentialsUnavailable => PersistenceCredentialsUnavailable
}
import pl.iterators.epoca.oauth.fixture.OAuthE2EFixtureDomain.RevokeOAuth029MarkerResult.{
  Applied => MarkerApplied,
  Conflict => MarkerConflict,
  Idempotent => MarkerIdempotent
}
import pl.iterators.epoca.oauth.fixture.OAuthE2EFixtureDomain.RevokeOAuth029PersistenceResult.{
  Applied => PersistenceRevokeApplied,
  CommandBudgetExhausted => PersistenceRevokeCommandBudgetExhausted,
  Conflict => PersistenceRevokeConflict,
  Idempotent => PersistenceRevokeIdempotent,
  ReconciliationRequired
}
import pl.iterators.epoca.oauth.fixture.OAuthE2EFixtureDomain.{
  CleanupStep,
  CleanupStepState,
  CleanupTransitionResult,
  FixtureAlias,
  FixtureOwnership,
  FixtureStatusResult,
  IdempotencyKeyDigest
}
import pl.iterators.epoca.oauth.services.OAuthClientManagementService.OAuthClientManagementResult.Success
import pl.iterators.epoca.oauth.services.OAuthClientManagementService.{
  OAuthClientCreateRequest,
  OAuthClientPreparedCreate,
  OAuthClientPreparedMutation
}
import pl.iterators.epoca.oauth.services.OAuthGrantabilityService.OAuthGrantability
import pl.iterators.epoca.oauth.services.{OAuthClientManagementService, OAuthSecretService}
import pl.iterators.epoca.organization.domain.OrganizationDomain._
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.project.domain.Project
import pl.iterators.epoca.project.domain.ProjectDomain._
import pl.iterators.epoca.project.repositories.ProjectRepository
import pl.iterators.epoca.project.repositories.ProjectRepository.ProjectFilters
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.domain.UserDomain._
import pl.iterators.epoca.user.repositories.UserRepository
import pl.iterators.epoca.user.repositories.UserRepository.UserFilters
import pl.iterators.epoca.utils.db.BaseRepository.driver.api.DBIO
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.{WorkUnitArchivedAt, WorkUnitModifiedAt, WorkUnitState}
import pl.iterators.epoca.workunit.repositories.WorkUnitRepository
import pl.iterators.epoca.workunit.repositories.WorkUnitRepository.WorkUnitFilters

import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
import scala.util.control.NoStackTrace

sealed trait OAuthE2EFixtureSetupResult
object OAuthE2EFixtureSetupResult {
  final case class Applied(credentials: OAuthE2EFixtureOneTimeCredentials) extends OAuthE2EFixtureSetupResult {
    override def toString: String = "OAuthE2EFixtureSetupResult.Applied(credentials=<redacted>)"
  }
  case object CredentialsUnavailable extends OAuthE2EFixtureSetupResult
  case object Conflict               extends OAuthE2EFixtureSetupResult
  case object CommandBudgetExhausted extends OAuthE2EFixtureSetupResult
  case object TimedOut               extends OAuthE2EFixtureSetupResult
}

sealed trait OAuthE2EFixtureRevokeResult
object OAuthE2EFixtureRevokeResult {
  case object Applied                extends OAuthE2EFixtureRevokeResult
  case object Idempotent             extends OAuthE2EFixtureRevokeResult
  case object Conflict               extends OAuthE2EFixtureRevokeResult
  case object CommandBudgetExhausted extends OAuthE2EFixtureRevokeResult
  case object TimedOut               extends OAuthE2EFixtureRevokeResult
}

sealed trait OAuthE2EFixtureCleanupResult
object OAuthE2EFixtureCleanupResult {
  case object Cleaned        extends OAuthE2EFixtureCleanupResult
  case object AlreadyCleaned extends OAuthE2EFixtureCleanupResult
  case object Conflict       extends OAuthE2EFixtureCleanupResult
  case object TimedOut       extends OAuthE2EFixtureCleanupResult
}

final case class OAuthE2EFixtureCredential private[fixture] (clientId: OAuthClientPublicId, clientSecret: OAuthClientSecret) {
  override def toString: String = s"OAuthE2EFixtureCredential(clientId=$clientId,clientSecret=<redacted>)"
}

sealed trait OAuthE2EFixtureSanitizedCaseOutcome
object OAuthE2EFixtureSanitizedCaseOutcome {
  case object Passed     extends OAuthE2EFixtureSanitizedCaseOutcome
  case object FailedSafe extends OAuthE2EFixtureSanitizedCaseOutcome
}

final case class OAuthE2EFixtureSanitizedCaseResult private[fixture] (alias: FixtureAlias, outcome: OAuthE2EFixtureSanitizedCaseOutcome)

object OAuthE2EFixtureSanitizedEvidenceInput {
  private[fixture] def fromExactCaseResults(results: Vector[OAuthE2EFixtureSanitizedCaseResult]): OAuthE2EFixtureSanitizedEvidenceInput = {
    val expectedAliases = FixtureAlias.values
    val aliases         = results.map(_.alias)
    if (results.size != expectedAliases.size || aliases.distinct.size != aliases.size || aliases.toSet != expectedAliases.toSet)
      throw new IllegalStateException("Fixture evidence requires the exact closed alias results")

    val byAlias = results.map(result => result.alias -> result).toMap
    new OAuthE2EFixtureSanitizedEvidenceInput(expectedAliases.map(byAlias))
  }
}

final class OAuthE2EFixtureSanitizedEvidenceInput private (val caseResults: Vector[OAuthE2EFixtureSanitizedCaseResult]) {
  override def toString: String = s"OAuthE2EFixtureSanitizedEvidenceInput(caseResults=${caseResults.mkString(",")})"
}

private object OAuthE2EFixtureOneTimeCredentials {
  final case class CredentialState(
    available: Map[FixtureAlias, OAuthE2EFixtureCredential],
    inFlight: Set[FixtureAlias],
    consumed: Set[FixtureAlias],
    caseResults: Map[FixtureAlias, OAuthE2EFixtureSanitizedCaseOutcome],
    sealedForEvidence: Boolean,
    discarded: Boolean)
}

final class OAuthE2EFixtureOneTimeCredentials private[fixture] (values: Map[FixtureAlias, OAuthE2EFixtureCredential]) {
  import OAuthE2EFixtureOneTimeCredentials.CredentialState

  require(values.keySet == FixtureAlias.values.toSet, "Fixture credential map must contain the exact closed aliases")

  private val state =
    new AtomicReference(CredentialState(values, Set.empty, Set.empty, Map.empty, sealedForEvidence = false, discarded = false))

  def aliases: Vector[FixtureAlias] = FixtureAlias.values

  private[fixture] def projectToBroker(alias: FixtureAlias)(brokerChild: OAuthE2EFixtureCredential => IO[OAuthE2EFixtureSanitizedCaseOutcome])
    : IO[OAuthE2EFixtureSanitizedCaseResult] =
    IO.uncancelable { poll =>
      acquire(alias).flatMap { credential =>
        poll(IO.defer(brokerChild(credential))).attempt
          .map(_.fold(_ => OAuthE2EFixtureSanitizedCaseOutcome.FailedSafe, identity))
          .flatMap(outcome => complete(alias, outcome))
          .onCancel(complete(alias, OAuthE2EFixtureSanitizedCaseOutcome.FailedSafe).void)
      }
    }

  private[fixture] def sealForEvidence(): IO[OAuthE2EFixtureSanitizedEvidenceInput] =
    IO.delay {
      @tailrec
      def seal(): OAuthE2EFixtureSanitizedEvidenceInput = {
        val current = state.get()
        if (
          current.sealedForEvidence || current.discarded || current.inFlight.nonEmpty || current.available.nonEmpty ||
          current.consumed != FixtureAlias.values.toSet || current.caseResults.keySet != FixtureAlias.values.toSet
        )
          throw new IllegalStateException("Fixture credential map cannot be sealed for evidence")
        val evidenceInput = OAuthE2EFixtureSanitizedEvidenceInput.fromExactCaseResults(
          FixtureAlias.values.map(alias => OAuthE2EFixtureSanitizedCaseResult(alias, current.caseResults(alias)))
        )
        val sealedState = current.copy(sealedForEvidence = true)
        if (state.compareAndSet(current, sealedState)) evidenceInput
        else seal()
      }

      seal()
    }

  private[fixture] def discardRemaining(): IO[Unit] =
    IO.delay {
      @tailrec
      def discard(): Unit = {
        val current = state.get()
        if (current.sealedForEvidence || current.inFlight.nonEmpty)
          throw new IllegalStateException("Fixture credential map cannot be discarded")
        else if (!current.discarded) {
          val discardedState = current.copy(available = Map.empty, discarded = true)
          if (!state.compareAndSet(current, discardedState)) discard()
        }
      }

      discard()
    }

  /** Transfers the exact closed credential map into the one-time loopback setup response and removes every retained backend reference. */
  private[fixture] def releaseForControlResponse(): IO[Vector[(FixtureAlias, OAuthE2EFixtureCredential)]] =
    IO.delay {
      @tailrec
      def release(): Vector[(FixtureAlias, OAuthE2EFixtureCredential)] = {
        val current = state.get()
        if (
          current.sealedForEvidence || current.discarded || current.inFlight.nonEmpty || current.consumed.nonEmpty ||
          current.caseResults.nonEmpty || current.available.keySet != FixtureAlias.values.toSet
        ) throw new IllegalStateException("Fixture credential map cannot be released")
        val released = current.copy(available = Map.empty, consumed = FixtureAlias.values.toSet, discarded = true)
        if (state.compareAndSet(current, released)) FixtureAlias.values.map(alias => alias -> current.available(alias))
        else release()
      }

      release()
    }

  private[fixture] def sealedForEvidence: Boolean   = state.get().sealedForEvidence
  private[fixture] def retainedCredentialCount: Int = state.get().available.size

  private def acquire(alias: FixtureAlias): IO[OAuthE2EFixtureCredential] =
    IO.delay {
      @tailrec
      def reserve(): OAuthE2EFixtureCredential = {
        val current = state.get()
        current.available.get(alias) match {
          case Some(credential) if !current.sealedForEvidence && !current.discarded && !current.inFlight(alias) =>
            val reserved = current.copy(inFlight = current.inFlight + alias)
            if (state.compareAndSet(current, reserved)) credential else reserve()
          case _ => throw new IllegalStateException("Fixture credential alias is unavailable")
        }
      }

      reserve()
    }

  private def complete(alias: FixtureAlias, outcome: OAuthE2EFixtureSanitizedCaseOutcome): IO[OAuthE2EFixtureSanitizedCaseResult] =
    IO.delay {
      @tailrec
      def record(): OAuthE2EFixtureSanitizedCaseResult = {
        val current = state.get()
        current.caseResults.get(alias) match {
          case Some(recorded) if !current.inFlight(alias) => OAuthE2EFixtureSanitizedCaseResult(alias, recorded)
          case _ if current.inFlight(alias) =>
            val completed = current.copy(
              available = current.available - alias,
              inFlight = current.inFlight - alias,
              consumed = current.consumed + alias,
              caseResults = current.caseResults.updated(alias, outcome)
            )
            if (state.compareAndSet(current, completed)) OAuthE2EFixtureSanitizedCaseResult(alias, outcome)
            else record()
          case _ => throw new IllegalStateException("Fixture credential alias completion failed closed")
        }
      }

      record()
    }

  override def toString: String = s"OAuthE2EFixtureOneTimeCredentials(aliases=${FixtureAlias.values.mkString(",")},values=<redacted>)"
}

final class OAuthE2EFixtureService(
  commandExecutor: OAuthE2EFixtureCommandExecutor,
  fixtureRepository: OAuthE2EFixtureRepository,
  managementService: OAuthClientManagementService,
  secretService: OAuthSecretService,
  userRepository: UserRepository,
  organizationRepository: OrganizationRepository,
  organizationUserRepository: OrganizationUserRepository,
  clientRepository: ClientRepository,
  projectRepository: ProjectRepository,
  workUnitRepository: WorkUnitRepository,
  nanoTime: () => Long = () => System.nanoTime(),
  afterSetupCommit: IO[Unit] = IO.unit,
  afterRevokeCommit: IO[Unit] = IO.unit,
  preparedCreateOverride: Option[() => OAuthClientPreparedCreate] = None,
  afterOwnedClientRevoke: DBIO[Unit] = DBIO.successful(()),
  beforeCleanupRevokeCommit: FixtureAlias => DBIO[Unit] = _ => DBIO.successful(()),
  afterCleanupRevokeCommit: FixtureAlias => IO[Unit] = _ => IO.unit,
  afterCleanupRevocationMarker: FixtureAlias => IO[Unit] = _ => IO.unit,
  afterCleanupResourceCommit: FixtureAlias => IO[Unit] = _ => IO.unit,
  afterCleanupResourceMarker: FixtureAlias => IO[Unit] = _ => IO.unit
)(implicit executionContext: ExecutionContext) {

  private val firstAttempt   = new AtomicReference[Option[(UUID, IdempotencyKeyDigest)]](None)
  private val setupCommitted = new AtomicBoolean(false)

  def setup(
    runId: UUID,
    idempotencyKey: UUID,
    context: ControlRequestContext
  ): IO[OAuthE2EFixtureSetupResult] = {
    val digest         = IdempotencyKeyDigest.sha256(idempotencyKey.toString)
    val attemptMatches = registerAttempt(runId, digest)
    val prepared =
      if (attemptMatches && !setupCommitted.get()) prepareTopology(runId, context).map(Some.apply)
      else IO.pure(None)

    prepared.flatMap { topology =>
      context.ensurePersistenceAllowed(IO(nanoTime())) *>
        commandExecutor
          .execute(context) { transaction =>
            fixtureRepository.setupProgram(
              transaction,
              runId,
              digest,
              attemptMatches,
              topology.fold(Vector.empty[FixtureOwnership])(_.map(_.ownership))
            )(topology match {
              case Some(value) => createTopologyProgram(transaction, value)
              case None        => transaction.failed(new IllegalStateException("Fixture rejected setup attempt reached persistence"))
            })
          }
          .flatMap {
            case ExecutionResult.Succeeded(PersistenceApplied(credentials), _) =>
              IO(setupCommitted.set(true)) *> afterSetupCommit.as(OAuthE2EFixtureSetupResult.Applied(credentials))
            case ExecutionResult.Succeeded(PersistenceCredentialsUnavailable, _) =>
              IO(setupCommitted.set(true)).as(OAuthE2EFixtureSetupResult.CredentialsUnavailable)
            case ExecutionResult.Succeeded(PersistenceConflict, _) =>
              IO.pure(OAuthE2EFixtureSetupResult.Conflict)
            case ExecutionResult.Succeeded(PersistenceCommandBudgetExhausted, _) =>
              IO.pure(OAuthE2EFixtureSetupResult.CommandBudgetExhausted)
            case ExecutionResult.TimedOut(_)   => IO.pure(OAuthE2EFixtureSetupResult.TimedOut)
            case ExecutionResult.Failed(error) => IO.raiseError(error)
          }
    }
  }

  def revokeOAuth029(
    runId: UUID,
    idempotencyKey: UUID,
    context: ControlRequestContext
  ): IO[OAuthE2EFixtureRevokeResult] = {
    val digest        = IdempotencyKeyDigest.sha256(idempotencyKey.toString)
    val correlationId = OAuthAuditCorrelationId(s"oauth-e2e-fixture-revoke-oauth029-$runId")
    commandExecutor
      .execute(context) { transaction =>
        transaction.preparedManagement(
          fixtureRepository.beginRevokeOAuth029(runId, digest)(ownership => preparedOwnedRevoke(ownership, correlationId))
        )
      }
      .flatMap {
        case ExecutionResult.Succeeded(PersistenceRevokeApplied(_), _) =>
          afterRevokeCommit *> completeRevokeMarker(runId, digest, context, applied = true)
        case ExecutionResult.Succeeded(ReconciliationRequired(ownership), _) =>
          reconcileOwnedRevoke(ownership, correlationId, context).flatMap {
            case true  => completeRevokeMarker(runId, digest, context, applied = false)
            case false => IO.raiseError(new IllegalStateException("Fixture oauth029 revoke reconciliation failed closed"))
          }
        case ExecutionResult.Succeeded(PersistenceRevokeIdempotent, _) => IO.pure(OAuthE2EFixtureRevokeResult.Idempotent)
        case ExecutionResult.Succeeded(PersistenceRevokeConflict, _)   => IO.pure(OAuthE2EFixtureRevokeResult.Conflict)
        case ExecutionResult.Succeeded(PersistenceRevokeCommandBudgetExhausted, _) =>
          IO.pure(OAuthE2EFixtureRevokeResult.CommandBudgetExhausted)
        case ExecutionResult.TimedOut(_)   => IO.pure(OAuthE2EFixtureRevokeResult.TimedOut)
        case ExecutionResult.Failed(error) => IO.raiseError(error)
      }
  }

  def cleanup(
    runId: UUID,
    idempotencyKey: UUID,
    context: ControlRequestContext
  ): IO[OAuthE2EFixtureCleanupResult] = {
    val digest = IdempotencyKeyDigest.sha256(idempotencyKey.toString)
    executeCleanup(context)(transaction => fixtureRepository.beginCleanupProgram(transaction, runId, digest))
      .flatMap {
        case PersistenceCleanupApplied | PersistenceCleanupResumable =>
          FixtureAlias.values.traverse_(alias => cleanupAlias(runId, alias, digest, context)) *>
            executeCleanup(context)(transaction => fixtureRepository.completeCleanupProgram(transaction, runId, digest)).flatMap {
              case PersistenceCleaned                   => IO.pure(OAuthE2EFixtureCleanupResult.Cleaned)
              case PersistenceAlreadyCleaned            => IO.pure(OAuthE2EFixtureCleanupResult.AlreadyCleaned)
              case PersistenceCleanupCompletionConflict => IO.pure(OAuthE2EFixtureCleanupResult.Conflict)
            }
        case PersistenceCleanupAlreadyCleaned => IO.pure(OAuthE2EFixtureCleanupResult.AlreadyCleaned)
        case PersistenceCleanupConflict       => IO.pure(OAuthE2EFixtureCleanupResult.Conflict)
      }
      .handleErrorWith {
        case CleanupTimedOut => IO.pure(OAuthE2EFixtureCleanupResult.TimedOut)
        case error           => IO.raiseError(error)
      }
  }

  private def cleanupAlias(
    runId: UUID,
    alias: FixtureAlias,
    digest: IdempotencyKeyDigest,
    context: ControlRequestContext
  ): IO[Unit] =
    cleanupStep(runId, revocationStep(alias), digest, context)(
      cleanupRevoke(runId, alias, digest, context),
      afterCleanupRevokeCommit(alias) *> (alias match {
        case FixtureAlias.OAuth029 => reconcileStartedRevokeOAuth029ForCleanup(runId, context)
        case _                     => IO.unit
      }),
      completeValidatedCleanupStep(runId, alias, revocationStep(alias), digest, context, validateResources = false),
      afterCleanupRevocationMarker(alias)
    ) *>
      cleanupStep(runId, resourceStep(alias), digest, context)(
        cleanupResources(runId, alias, digest, context),
        afterCleanupResourceCommit(alias),
        completeValidatedCleanupStep(runId, alias, resourceStep(alias), digest, context, validateResources = true),
        afterCleanupResourceMarker(alias)
      )

  private def cleanupStep(
    runId: UUID,
    step: CleanupStep,
    digest: IdempotencyKeyDigest,
    context: ControlRequestContext
  )(
    mutation: IO[Unit],
    afterCommit: IO[Unit],
    completeMarker: IO[CleanupTransitionResult],
    afterMarker: IO[Unit]
  ): IO[Unit] =
    transitionCleanupStep(runId, step, CleanupStepState.Pending, CleanupStepState.InProgress, digest, context).flatMap {
      case CleanupStepApplied(CleanupStepState.InProgress, _) | CleanupStepIdempotent(CleanupStepState.InProgress, _) =>
        mutation *> afterCommit *>
          completeMarker.flatMap {
            case CleanupStepApplied(CleanupStepState.Complete, _) | CleanupStepIdempotent(CleanupStepState.Complete, _) => afterMarker
            case _ => IO.raiseError(new IllegalStateException("Fixture cleanup marker failed closed"))
          }
      case CleanupStepIdempotent(CleanupStepState.Complete, _) => IO.unit
      case _                                                   => IO.raiseError(new IllegalStateException("Fixture cleanup step conflicted"))
    }

  private def transitionCleanupStep(
    runId: UUID,
    step: CleanupStep,
    expected: CleanupStepState,
    target: CleanupStepState,
    digest: IdempotencyKeyDigest,
    context: ControlRequestContext
  ) = executeCleanup(context)(transaction => fixtureRepository.transitionCleanupStepForRunProgram(transaction, runId, step, expected, target, digest))

  private def completeValidatedCleanupStep(
    runId: UUID,
    alias: FixtureAlias,
    step: CleanupStep,
    digest: IdempotencyKeyDigest,
    context: ControlRequestContext,
    validateResources: Boolean
  ): IO[CleanupTransitionResult] =
    executeCleanup(context) { transaction =>
      fixtureRepository.cleanupOwnershipForRunProgram(transaction, runId, alias, digest).flatMap { ownership =>
        transaction
          .preparedManagement(managementService.verifyOwnedTerminalRevokeDBIO(ownership.oauthClientId, ownership.organizationId))
          .flatMap {
            case true =>
              val validation = if (validateResources) validateArchivedResourcesProgram(transaction, ownership) else transaction.pure(())
              validation >> fixtureRepository
                .transitionCleanupStepForRunProgram(transaction, runId, step, CleanupStepState.InProgress, CleanupStepState.Complete, digest)
            case false => transaction.failed(new IllegalStateException("Fixture cleanup terminal OAuth state failed closed"))
          }
      }
    }

  private def cleanupRevoke(
    runId: UUID,
    alias: FixtureAlias,
    digest: IdempotencyKeyDigest,
    context: ControlRequestContext
  ): IO[Unit] =
    executeCleanup(context) { transaction =>
      fixtureRepository.cleanupOwnershipForRunProgram(transaction, runId, alias, digest).flatMap { ownership =>
        transaction.preparedManagement(preparedCleanupRevoke(ownership, cleanupCorrelationId(runId, alias), alias))
      }
    }

  private def reconcileStartedRevokeOAuth029ForCleanup(runId: UUID, context: ControlRequestContext): IO[Unit] =
    executeCleanup(context)(transaction => transaction.preparedManagement(fixtureRepository.reconcileStartedRevokeOAuth029ForCleanup(runId)))
      .flatMap {
        case MarkerApplied | MarkerIdempotent => IO.unit
        case MarkerConflict => IO.raiseError(new IllegalStateException("Fixture oauth029 cleanup marker reconciliation failed closed"))
      }

  private def preparedCleanupRevoke(
    ownership: FixtureOwnership,
    correlationId: OAuthAuditCorrelationId,
    alias: FixtureAlias
  ): DBIO[Unit] =
    organizationRepository.find(OrganizationFilters(id = Some(ownership.organizationId), includeArchived = true)).flatMap {
      case Some(organization) if organization.id == ownership.organizationId =>
        managementService
          .revokeOwnedPreparedDBIO(ownerAuth(ownership), organization.slug, ownership.oauthClientId, correlationId, beforeCleanupRevokeCommit(alias))
          .flatMap {
            case Success(value) if value.status == OAuthClientEffectiveStatus.Revoked => DBIO.successful(())
            case pl.iterators.epoca.oauth.services.OAuthClientManagementService.OAuthClientManagementResult.NotActive =>
              verifyCleanupTerminalRevokeDBIO(ownership, correlationId).flatMap {
                case true  => DBIO.successful(())
                case false => DBIO.failed(new IllegalStateException("Fixture cleanup revoke reconciliation failed closed"))
              }
            case result => DBIO.failed(new IllegalStateException(s"Fixture cleanup revoke failed closed: ${result.getClass.getSimpleName}"))
          }
      case _ => DBIO.failed(new IllegalStateException("Fixture cleanup organization is unavailable"))
    }

  private def cleanupResources(
    runId: UUID,
    alias: FixtureAlias,
    digest: IdempotencyKeyDigest,
    context: ControlRequestContext
  ): IO[Unit] =
    executeCleanup(context) { transaction =>
      fixtureRepository.cleanupOwnershipForRunProgram(transaction, runId, alias, digest).flatMap { ownership =>
        transaction
          .preparedManagement(verifyCleanupTerminalRevokeDBIO(ownership, cleanupCorrelationId(runId, alias)))
          .flatMap {
            case true  => archiveOwnedResourcesProgram(transaction, ownership)
            case false => transaction.failed(new IllegalStateException("Fixture cleanup resource precondition failed closed"))
          }
      }
    }

  private def verifyCleanupTerminalRevokeDBIO(ownership: FixtureOwnership, cleanupCorrelationId: OAuthAuditCorrelationId): DBIO[Boolean] =
    managementService.reconcileOwnedRevokeDBIO(ownership.oauthClientId, ownership.organizationId, cleanupCorrelationId).flatMap {
      case true  => DBIO.successful(true)
      case false => managementService.verifyOwnedTerminalRevokeDBIO(ownership.oauthClientId, ownership.organizationId)
    }

  private def archiveOwnedResourcesProgram(transaction: OAuthE2EFixturePinnedTransaction, ownership: FixtureOwnership)
    : OAuthE2EFixtureCommandProgram[Unit] = {
    val projectIds      = Vector(ownership.projectP1Id, ownership.projectP2Id, ownership.foreignProjectId).sortBy(_.toString)
    val clientIds       = Vector(ownership.businessClientId, ownership.foreignBusinessClientId).sortBy(_.toString)
    val organizationIds = Vector(ownership.organizationId, ownership.foreignOrganizationId).sortBy(_.toString)
    val ownerPairs = Vector(
      (ownership.organizationId, ownership.ownerUserId, ownership.ownerMembershipId),
      (ownership.foreignOrganizationId, ownership.foreignOwnerUserId, ownership.foreignOwnerMembershipId)
    ).sortBy(_._2.toString)

    transaction.statement(fixtureRepository.authoritativeNowEpochSeconds).map(authoritativeInstant).flatMap { now =>
      transaction.statement(projectRepository.lockOrdered(ProjectFilters(ids = Some(projectIds), includeArchived = true))).flatMap { projects =>
        requireExactIds(transaction, "projects", projectIds.map(_.toString), projects.map(_.id.toString)).flatMap { _ =>
          loadClientsForUpdate(transaction, clientIds).flatMap { clients =>
            transaction
              .statement(
                workUnitRepository
                  .lockOrdered(WorkUnitFilters(projectIds = Some(projectIds), states = Some(WorkUnitState.values), includeArchived = true))
              )
              .flatMap { workUnits =>
                loadOrganizationsForUpdate(transaction, organizationIds).flatMap { organizations =>
                  requireOwnedResourceRelationships(transaction, ownership, projects, clients, organizations).flatMap { _ =>
                    loadOwnersForUpdate(transaction, ownerPairs).flatMap { case (memberships, owners) =>
                      val archiveProjects = projects.foldLeft(transaction.pure(())) { (program, project) =>
                        val ownerId =
                          if (project.id == ownership.foreignProjectId) ownership.foreignOwnerUserId else ownership.ownerUserId
                        program >> (if (project.archivedAt.nonEmpty) transaction.pure(())
                                    else
                                      requireUpdated(transaction, "project")(
                                        transaction.preparedManagement(
                                          projectRepository.update(
                                            project.copy(
                                              modifiedAt = ProjectModifiedAt(now),
                                              modifiedBy = ownerId,
                                              archivedAt = Some(ProjectArchivedAt(now)),
                                              archivedBy = Some(ownerId)
                                            )
                                          )
                                        )
                                      ))
                      }
                      val archiveClients = clients.foldLeft(transaction.pure(())) { (program, client) =>
                        val ownerId =
                          if (client.id == ownership.foreignBusinessClientId) ownership.foreignOwnerUserId else ownership.ownerUserId
                        program >> (if (client.archivedAt.nonEmpty) transaction.pure(())
                                    else
                                      requireUpdated(transaction, "business client")(
                                        transaction.preparedManagement(
                                          clientRepository.update(
                                            client.copy(
                                              modifiedAt = ClientModifiedAt(now),
                                              modifiedBy = ownerId,
                                              archivedAt = Some(ClientArchivedAt(now)),
                                              archivedBy = Some(ownerId)
                                            )
                                          )
                                        )
                                      ))
                      }
                      val archiveWorkUnits = workUnits.foldLeft(transaction.pure(())) { (program, workUnit) =>
                        program >> (if (workUnit.archivedAt.nonEmpty) transaction.pure(())
                                    else
                                      requireUpdated(transaction, "work unit")(
                                        transaction.preparedManagement(
                                          workUnitRepository.update(
                                            workUnit.copy(
                                              state = WorkUnitState.Archived,
                                              modifiedAt = WorkUnitModifiedAt(now),
                                              archivedAt = Some(WorkUnitArchivedAt(now))
                                            )
                                          )
                                        )
                                      ))
                      }
                      val archiveMemberships = memberships.foldLeft(transaction.pure(())) { (program, membership) =>
                        program >> (if (membership.archivedAt.nonEmpty) transaction.pure(())
                                    else
                                      requireChanged(transaction, "Owner membership archive", 1)(
                                        transaction.statement(organizationUserRepository.delete(membership.id, now))
                                      ))
                      }
                      val archiveOrganizations = organizations.foldLeft(transaction.pure(())) { (program, organization) =>
                        val ownerId =
                          if (organization.id == ownership.foreignOrganizationId) ownership.foreignOwnerUserId else ownership.ownerUserId
                        program >> (if (organization.archivedAt.nonEmpty) transaction.pure(())
                                    else
                                      requireUpdated(transaction, "organization")(
                                        transaction.preparedManagement(
                                          organizationRepository.update(
                                            organization.copy(
                                              modifiedAt = OrganizationModifiedAt(now),
                                              modifiedBy = ownerId,
                                              archivedAt = Some(OrganizationArchivedAt(now))
                                            )
                                          )
                                        )
                                      ))
                      }
                      val archiveOwners = owners.foldLeft(transaction.pure(())) { (program, owner) =>
                        program >> (if (owner.archivedAt.nonEmpty) transaction.pure(())
                                    else
                                      requireUpdated(transaction, "Owner user")(
                                        transaction.preparedManagement(
                                          userRepository.update(owner.copy(modifiedAt = UserModifiedAt(now), archivedAt = Some(UserArchivedAt(now))))
                                        )
                                      ))
                      }

                      archiveProjects >> archiveClients >> archiveWorkUnits >> archiveMemberships >> archiveOrganizations >> archiveOwners
                    }
                  }
                }
              }
          }
        }
      }
    }
  }

  private def validateArchivedResourcesProgram(transaction: OAuthE2EFixturePinnedTransaction, ownership: FixtureOwnership)
    : OAuthE2EFixtureCommandProgram[Unit] = {
    val projectIds      = Vector(ownership.projectP1Id, ownership.projectP2Id, ownership.foreignProjectId).sortBy(_.toString)
    val clientIds       = Vector(ownership.businessClientId, ownership.foreignBusinessClientId).sortBy(_.toString)
    val organizationIds = Vector(ownership.organizationId, ownership.foreignOrganizationId).sortBy(_.toString)
    val ownerPairs = Vector(
      (ownership.organizationId, ownership.ownerUserId, ownership.ownerMembershipId),
      (ownership.foreignOrganizationId, ownership.foreignOwnerUserId, ownership.foreignOwnerMembershipId)
    ).sortBy(_._2.toString)

    transaction.statement(projectRepository.lockOrdered(ProjectFilters(ids = Some(projectIds), includeArchived = true))).flatMap { projects =>
      requireExactIds(transaction, "projects", projectIds.map(_.toString), projects.map(_.id.toString)).flatMap { _ =>
        loadClientsForUpdate(transaction, clientIds).flatMap { clients =>
          transaction
            .statement(
              workUnitRepository
                .lockOrdered(WorkUnitFilters(projectIds = Some(projectIds), states = Some(WorkUnitState.values), includeArchived = true))
            )
            .flatMap { workUnits =>
              loadOrganizationsForUpdate(transaction, organizationIds).flatMap { organizations =>
                requireOwnedResourceRelationships(transaction, ownership, projects, clients, organizations).flatMap { _ =>
                  loadOwnersForUpdate(transaction, ownerPairs).flatMap { case (memberships, owners) =>
                    val expectedProjectOwners = Map(
                      ownership.projectP1Id      -> ownership.ownerUserId,
                      ownership.projectP2Id      -> ownership.ownerUserId,
                      ownership.foreignProjectId -> ownership.foreignOwnerUserId
                    )
                    val expectedWorkUnitOwners = Map(
                      ownership.projectP1Id      -> (ownership.organizationId, ownership.ownerUserId),
                      ownership.projectP2Id      -> (ownership.organizationId, ownership.ownerUserId),
                      ownership.foreignProjectId -> (ownership.foreignOrganizationId, ownership.foreignOwnerUserId)
                    )
                    val expectedClientOwners =
                      Map(ownership.businessClientId -> ownership.ownerUserId, ownership.foreignBusinessClientId -> ownership.foreignOwnerUserId)
                    val projectsArchived = projects
                      .forall(project => project.archivedAt.nonEmpty && expectedProjectOwners.get(project.id).exists(project.archivedBy.contains))
                    val clientsArchived =
                      clients.forall(client => client.archivedAt.nonEmpty && expectedClientOwners.get(client.id).exists(client.archivedBy.contains))
                    val workUnitsArchived = workUnits.forall(workUnit =>
                      expectedWorkUnitOwners.get(workUnit.projectId).contains((workUnit.organizationId, workUnit.userId)) &&
                        workUnit.state == WorkUnitState.Archived && workUnit.archivedAt.nonEmpty
                    )
                    val organizationsArchived = organizations.forall(_.archivedAt.nonEmpty)
                    val membershipsArchived   = memberships.forall(_.archivedAt.nonEmpty)
                    val ownersArchived        = owners.forall(_.archivedAt.nonEmpty)

                    if (projectsArchived && clientsArchived && workUnitsArchived && organizationsArchived && membershipsArchived && ownersArchived)
                      transaction.pure(())
                    else transaction.failed(new IllegalStateException("Fixture cleanup resource marker validation failed closed"))
                  }
                }
              }
            }
        }
      }
    }
  }

  def advance300(
    runId: UUID,
    idempotencyKey: UUID,
    context: ControlRequestContext
  ): IO[OAuthE2EFixtureDomain.AdvanceResult] = {
    val digest = IdempotencyKeyDigest.sha256(idempotencyKey.toString)
    commandExecutor
      .execute(context)(transaction => fixtureRepository.advance300ForRunProgram(transaction, runId, digest))
      .flatMap {
        case ExecutionResult.Succeeded(value, _) => IO.pure(value)
        case ExecutionResult.TimedOut(_)         => IO.raiseError(new java.util.concurrent.TimeoutException("Fixture advance timed out"))
        case ExecutionResult.Failed(error)       => IO.raiseError(error)
      }
  }

  def status(
    runId: UUID,
    idempotencyKey: UUID,
    context: ControlRequestContext
  ): IO[FixtureStatusResult] = {
    val digest = IdempotencyKeyDigest.sha256(idempotencyKey.toString)
    commandExecutor.execute(context)(transaction => fixtureRepository.statusForRunProgram(transaction, runId, digest)).flatMap {
      case ExecutionResult.Succeeded(value, _) => IO.pure(value)
      case ExecutionResult.TimedOut(_)         => IO.raiseError(new java.util.concurrent.TimeoutException("Fixture status timed out"))
      case ExecutionResult.Failed(error)       => IO.raiseError(error)
    }
  }

  private def preparedOwnedRevoke(ownership: FixtureOwnership, correlationId: OAuthAuditCorrelationId) =
    organizationRepository.find(OrganizationFilters(id = Some(ownership.organizationId), includeArchived = true)).flatMap {
      case Some(organization) if organization.id == ownership.organizationId =>
        managementService
          .revokeOwnedPreparedDBIO(ownerAuth(ownership), organization.slug, ownership.oauthClientId, correlationId, afterOwnedClientRevoke)
          .flatMap {
            case Success(value) if value.status == OAuthClientEffectiveStatus.Revoked => DBIO.successful(())
            case result => DBIO.failed(new IllegalStateException(s"Fixture oauth029 revoke failed closed: ${result.getClass.getSimpleName}"))
          }
      case _ => DBIO.failed(new IllegalStateException("Fixture oauth029 organization is unavailable"))
    }

  private def executeCleanup[A](context: ControlRequestContext)(command: OAuthE2EFixturePinnedTransaction => OAuthE2EFixtureCommandProgram[A])
    : IO[A] =
    commandExecutor.executeCleanup(context)(command).flatMap {
      case ExecutionResult.Succeeded(value, _) => IO.pure(value)
      case ExecutionResult.TimedOut(_)         => IO.raiseError(CleanupTimedOut)
      case ExecutionResult.Failed(error)       => IO.raiseError(error)
    }

  private def loadClientsForUpdate(transaction: OAuthE2EFixtureStatementBoundary, ids: Vector[ClientId])
    : OAuthE2EFixtureCommandProgram[Vector[Client]] =
    ids.foldLeft(transaction.pure(Vector.empty[Client])) { (program, id) =>
      program.flatMap(clients =>
        transaction.statement(clientRepository.find(ClientFilters(id = Some(id), includeArchived = true, forUpdate = true))).flatMap {
          case Some(client) => transaction.pure(clients :+ client)
          case None         => transaction.failed(new IllegalStateException("Fixture cleanup business client is unavailable"))
        }
      )
    }

  private def loadOrganizationsForUpdate(transaction: OAuthE2EFixtureStatementBoundary, ids: Vector[OrganizationId])
    : OAuthE2EFixtureCommandProgram[Vector[Organization]] =
    ids.foldLeft(transaction.pure(Vector.empty[Organization])) { (program, id) =>
      program.flatMap(organizations =>
        transaction
          .statement(organizationRepository.find(OrganizationFilters(id = Some(id), includeArchived = true, forUpdate = true)))
          .flatMap {
            case Some(organization) => transaction.pure(organizations :+ organization)
            case None               => transaction.failed(new IllegalStateException("Fixture cleanup organization is unavailable"))
          }
      )
    }

  private def loadOwnersForUpdate(transaction: OAuthE2EFixtureStatementBoundary, pairs: Vector[(OrganizationId, UserId, OrganizationUserId)])
    : OAuthE2EFixtureCommandProgram[(Vector[OrganizationUser], Vector[User])] = {
    val memberships = pairs.foldLeft(transaction.pure(Vector.empty[OrganizationUser])) { case (program, (organizationId, userId, membershipId)) =>
      program.flatMap(values =>
        transaction
          .statement(
            organizationUserRepository
              .find(OrganizationUserFilters(organizationId = Some(organizationId), userId = Some(userId), includeArchived = true, forUpdate = true))
          )
          .flatMap {
            case Some(membership) if membership.id == membershipId && membership.roles == Seq(OrganizationUserRole.Owner) =>
              transaction.pure(values :+ membership)
            case _ => transaction.failed(new IllegalStateException("Fixture cleanup Owner membership is unavailable"))
          }
      )
    }
    memberships.flatMap { lockedMemberships =>
      pairs
        .foldLeft(transaction.pure(Vector.empty[User])) { case (program, (_, userId, _)) =>
          program.flatMap(values =>
            transaction.statement(userRepository.find(UserFilters(id = Some(userId), includeArchived = true, forUpdate = true))).flatMap {
              case Some(user) => transaction.pure(values :+ user)
              case None       => transaction.failed(new IllegalStateException("Fixture cleanup Owner user is unavailable"))
            }
          )
        }
        .map(lockedUsers => lockedMemberships -> lockedUsers)
    }
  }

  private def requireOwnedResourceRelationships(
    transaction: OAuthE2EFixtureStatementBoundary,
    ownership: FixtureOwnership,
    projects: Seq[Project],
    clients: Vector[Client],
    organizations: Vector[Organization]
  ): OAuthE2EFixtureCommandProgram[Unit] = {
    val expectedProjects = Map(
      ownership.projectP1Id -> (ownership.organizationId, ownership.businessClientId, ownership.ownerUserId),
      ownership.projectP2Id -> (ownership.organizationId, ownership.businessClientId, ownership.ownerUserId),
      ownership.foreignProjectId ->
        (ownership.foreignOrganizationId, ownership.foreignBusinessClientId, ownership.foreignOwnerUserId)
    )
    val expectedClients = Map(
      ownership.businessClientId        -> (ownership.organizationId, ownership.ownerUserId),
      ownership.foreignBusinessClientId -> (ownership.foreignOrganizationId, ownership.foreignOwnerUserId)
    )
    val expectedOrganizations =
      Map(ownership.organizationId -> ownership.ownerUserId, ownership.foreignOrganizationId -> ownership.foreignOwnerUserId)
    val projectsMatch =
      projects.forall(project => expectedProjects.get(project.id).contains((project.organizationId, project.clientId, project.createdBy)))
    val clientsMatch       = clients.forall(client => expectedClients.get(client.id).contains((client.organizationId, client.createdBy)))
    val organizationsMatch = organizations.forall(organization => expectedOrganizations.get(organization.id).contains(organization.createdBy))

    if (projectsMatch && clientsMatch && organizationsMatch) transaction.pure(())
    else transaction.failed(new IllegalStateException("Fixture cleanup project ownership failed closed"))
  }

  private def requireExactIds(
    transaction: OAuthE2EFixtureStatementBoundary,
    label: String,
    expected: Vector[String],
    actual: Seq[String]
  ): OAuthE2EFixtureCommandProgram[Unit] =
    if (actual.toVector == expected) transaction.pure(())
    else transaction.failed(new IllegalStateException(s"Fixture cleanup $label failed closed"))

  private def requireUpdated[A](transaction: OAuthE2EFixtureStatementBoundary, label: String)(program: OAuthE2EFixtureCommandProgram[Option[A]])
    : OAuthE2EFixtureCommandProgram[Unit] =
    program.flatMap {
      case Some(_) => transaction.pure(())
      case None    => transaction.failed(new IllegalStateException(s"Fixture $label update affected no rows"))
    }

  private def cleanupCorrelationId(runId: UUID, alias: FixtureAlias): OAuthAuditCorrelationId =
    alias match {
      case FixtureAlias.OAuth029 => OAuthAuditCorrelationId(s"oauth-e2e-fixture-revoke-oauth029-$runId")
      case _                     => OAuthAuditCorrelationId(s"oauth-e2e-fixture-cleanup-${alias.databaseValue}-$runId")
    }

  private def revocationStep(alias: FixtureAlias): CleanupStep = alias match {
    case FixtureAlias.OAuth027 => CleanupStep.OAuth027Revocation
    case FixtureAlias.OAuth028 => CleanupStep.OAuth028Revocation
    case FixtureAlias.OAuth029 => CleanupStep.OAuth029Revocation
    case FixtureAlias.General  => CleanupStep.GeneralRevocation
  }

  private def resourceStep(alias: FixtureAlias): CleanupStep = alias match {
    case FixtureAlias.OAuth027 => CleanupStep.OAuth027Resources
    case FixtureAlias.OAuth028 => CleanupStep.OAuth028Resources
    case FixtureAlias.OAuth029 => CleanupStep.OAuth029Resources
    case FixtureAlias.General  => CleanupStep.GeneralResources
  }

  private case object CleanupTimedOut extends RuntimeException("fixture cleanup timed out") with NoStackTrace

  private def reconcileOwnedRevoke(
    ownership: FixtureOwnership,
    correlationId: OAuthAuditCorrelationId,
    context: ControlRequestContext
  ): IO[Boolean] =
    commandExecutor
      .execute(context)(transaction =>
        transaction.preparedManagement(managementService.reconcileOwnedRevokeDBIO(ownership.oauthClientId, ownership.organizationId, correlationId))
      )
      .flatMap {
        case ExecutionResult.Succeeded(value, _) => IO.pure(value)
        case ExecutionResult.TimedOut(_)         => IO.pure(false)
        case ExecutionResult.Failed(error)       => IO.raiseError(error)
      }

  private def completeRevokeMarker(
    runId: UUID,
    digest: IdempotencyKeyDigest,
    context: ControlRequestContext,
    applied: Boolean
  ): IO[OAuthE2EFixtureRevokeResult] =
    commandExecutor
      .execute(context)(transaction => transaction.preparedManagement(fixtureRepository.completeRevokeOAuth029(runId, digest)))
      .flatMap {
        case ExecutionResult.Succeeded(MarkerApplied, _) if applied         => IO.pure(OAuthE2EFixtureRevokeResult.Applied)
        case ExecutionResult.Succeeded(MarkerApplied | MarkerIdempotent, _) => IO.pure(OAuthE2EFixtureRevokeResult.Idempotent)
        case ExecutionResult.Succeeded(MarkerConflict, _)                   => IO.pure(OAuthE2EFixtureRevokeResult.Conflict)
        case ExecutionResult.TimedOut(_)                                    => IO.pure(OAuthE2EFixtureRevokeResult.TimedOut)
        case ExecutionResult.Failed(error)                                  => IO.raiseError(error)
      }

  private def ownerAuth(ownership: FixtureOwnership): AuthContext =
    AuthContext(
      id = ownership.ownerUserId,
      isSuperAdmin = UserIsSuperAdmin(false),
      identities = Nil,
      authedBy = Set.empty,
      roles = Seq(AuthContext.OrganizationRoles(ownership.organizationId, Seq(OrganizationUserRole.Owner))),
      lastAuthedAt = Instant.EPOCH,
      fresh = true
    )

  private def registerAttempt(runId: UUID, digest: IdempotencyKeyDigest): Boolean = {
    val proposed = Some(runId -> digest)
    firstAttempt.get() match {
      case None           => firstAttempt.compareAndSet(None, proposed) || firstAttempt.get().contains(runId -> digest)
      case Some(existing) => existing == (runId -> digest)
    }
  }

  private def prepareTopology(runId: UUID, context: ControlRequestContext): IO[Vector[PreparedAlias]] =
    FixtureAlias.values.traverse { alias =>
      context.ensurePersistenceAllowed(IO(nanoTime())) *>
        IO.blocking {
          val prepared = preparedCreateOverride.fold(defaultPreparedCreate())(_())
          new PreparedAlias(runId, alias, prepared)
        } <* context.ensurePersistenceAllowed(IO(nanoTime()))
    }

  private def defaultPreparedCreate(): OAuthClientPreparedCreate = {
    val credentials = secretService.generateCredentials()
    OAuthClientPreparedCreate(
      credentials = credentials,
      secretHash = secretService.hash(credentials.clientSecret),
      clientId = OAuthClientId(UUID.randomUUID()),
      serviceAccountUserId = UserId(UUID.randomUUID()),
      membershipId = OrganizationUserId(UUID.randomUUID()),
      auditEventId = OAuthAuditEventId(UUID.randomUUID())
    )
  }

  private def createTopologyProgram(transaction: OAuthE2EFixtureStatementBoundary, prepared: Vector[PreparedAlias])
    : OAuthE2EFixtureCommandProgram[OAuthE2EFixtureOneTimeCredentials] =
    transaction.statement(fixtureRepository.authoritativeNowEpochSeconds).map(authoritativeInstant).flatMap { now =>
      prepared
        .foldLeft(transaction.pure(Map.empty[FixtureAlias, OAuthE2EFixtureCredential])) { (program, alias) =>
          program.flatMap { credentials =>
            createAliasProgram(transaction, alias, now).map(credential => credentials.updated(alias.alias, credential))
          }
        }
        .map(credentials => new OAuthE2EFixtureOneTimeCredentials(credentials))
    }

  private def authoritativeInstant(epochSeconds: BigDecimal): Instant = {
    val seconds = epochSeconds.longValue
    val nanos   = ((epochSeconds - BigDecimal(seconds)) * BigDecimal(1000000000L)).longValue
    Instant.ofEpochSecond(seconds, nanos)
  }

  private def createAliasProgram(
    transaction: OAuthE2EFixtureStatementBoundary,
    prepared: PreparedAlias,
    now: Instant
  ): OAuthE2EFixtureCommandProgram[OAuthE2EFixtureCredential] = {
    val resources     = prepared.resources(now)
    val request       = prepared.request
    val auth          = prepared.auth(resources.owner)
    val correlationId = Some(OAuthAuditCorrelationId(s"oauth-e2e-fixture-${prepared.alias.databaseValue}-${prepared.runId}"))

    val preparedMutation = managementService.prepareCreateMutation(
      auth,
      request,
      prepared.oauth,
      OAuthGrantability(
        resources.organization,
        OAuthScope.values.toSet,
        Set(resources.projectP1.id, resources.projectP2.id),
        mayGrantAllProjects = true
      ),
      now,
      correlationId
    )

    transaction.statement(userRepository.insert(resources.owner)) >>
      transaction.statement(organizationRepository.insert(resources.organization)) >>
      transaction.statement(organizationUserRepository.insertPrepared(resources.ownerMembership)) >>
      transaction.statement(clientRepository.insert(resources.businessClient)) >>
      transaction.statement(projectRepository.insert(resources.projectP1)) >>
      transaction.statement(projectRepository.insert(resources.projectP2)) >>
      transaction.statement(userRepository.insert(resources.foreignOwner)) >>
      transaction.statement(organizationRepository.insert(resources.foreignOrganization)) >>
      transaction.statement(organizationUserRepository.insertPrepared(resources.foreignOwnerMembership)) >>
      transaction.statement(clientRepository.insert(resources.foreignBusinessClient)) >>
      transaction.statement(projectRepository.insert(resources.foreignProject)) >>
      (preparedMutation match {
        case Left(result) =>
          transaction.failed(new IllegalStateException(s"Fixture OAuth create failed closed: ${result.getClass.getSimpleName}"))
        case Right(mutation) => createPreparedOAuthProgram(transaction, prepared, mutation)
      })
  }

  private def createPreparedOAuthProgram(
    transaction: OAuthE2EFixtureStatementBoundary,
    prepared: PreparedAlias,
    mutation: OAuthClientPreparedMutation
  ): OAuthE2EFixtureCommandProgram[OAuthE2EFixtureCredential] = {
    val projectIds = mutation.snapshot.projectAccess match {
      case OAuthProjectAccess.AllProjects                  => Vector.empty
      case OAuthProjectAccess.SelectedProjects(projectIds) => projectIds.toVector.sortBy(_.toString)
    }
    val scopeInserts = mutation.snapshot.scopes.toVector.sortBy(_.value).foldLeft(transaction.pure(())) { (program, scope) =>
      program >> requireChanged(transaction, "OAuth scope", 1)(transaction.statement(managementService.insertPreparedScopeRowDBIO(mutation, scope)))
    }
    val projectInserts = projectIds.foldLeft(transaction.pure(())) { (program, projectId) =>
      program >> requireChanged(transaction, "OAuth project", 1)(
        transaction.statement(managementService.insertPreparedProjectRowDBIO(mutation, projectId))
      )
    }

    transaction.statement(userRepository.insert(mutation.identity)) >>
      transaction.statement(organizationUserRepository.insertPrepared(mutation.membership)) >>
      requireChanged(transaction, "OAuth client", 1)(transaction.statement(managementService.insertPreparedClientRowDBIO(mutation))) >>
      scopeInserts >>
      projectInserts >>
      requireChanged(transaction, "OAuth audit", 1)(transaction.statement(managementService.insertPreparedAuditDBIO(mutation))) >>
      transaction.pure(OAuthE2EFixtureCredential(prepared.oauth.credentials.clientId, prepared.oauth.credentials.clientSecret))
  }

  private def requireChanged(
    transaction: OAuthE2EFixtureStatementBoundary,
    label: String,
    expected: Int
  )(
    program: OAuthE2EFixtureCommandProgram[Int]
  ): OAuthE2EFixtureCommandProgram[Unit] =
    program.flatMap {
      case changed if changed == expected => transaction.pure(())
      case changed                        => transaction.failed(new IllegalStateException(s"Fixture $label insert affected $changed rows"))
    }

  private final class PreparedAlias(
    val runId: UUID,
    val alias: FixtureAlias,
    val oauth: OAuthClientPreparedCreate,
    val ownerUserId: UserId = UserId(UUID.randomUUID()),
    val ownerMembershipId: OrganizationUserId = OrganizationUserId(UUID.randomUUID()),
    val organizationId: OrganizationId = OrganizationId(UUID.randomUUID()),
    val businessClientId: ClientId = ClientId(UUID.randomUUID()),
    val projectP1Id: ProjectId = ProjectId(UUID.randomUUID()),
    val projectP2Id: ProjectId = ProjectId(UUID.randomUUID()),
    val foreignOwnerUserId: UserId = UserId(UUID.randomUUID()),
    val foreignOwnerMembershipId: OrganizationUserId = OrganizationUserId(UUID.randomUUID()),
    val foreignOrganizationId: OrganizationId = OrganizationId(UUID.randomUUID()),
    val foreignBusinessClientId: ClientId = ClientId(UUID.randomUUID()),
    val foreignProjectId: ProjectId = ProjectId(UUID.randomUUID())) {

    val ownership: FixtureOwnership = FixtureOwnership(
      runId,
      alias,
      ownerUserId,
      ownerMembershipId,
      organizationId,
      businessClientId,
      projectP1Id,
      projectP2Id,
      foreignOwnerUserId,
      foreignOwnerMembershipId,
      foreignOrganizationId,
      foreignBusinessClientId,
      foreignProjectId,
      oauth.clientId
    )

    val request: OAuthClientCreateRequest = alias match {
      case FixtureAlias.OAuth027 | FixtureAlias.OAuth029 =>
        OAuthClientCreateRequest(clientName, Set(OAuthScope.ProjectsRead), OAuthProjectAccess.SelectedProjects(Set(projectP1Id)))
      case FixtureAlias.OAuth028 =>
        OAuthClientCreateRequest(
          clientName,
          Set(OAuthScope.OrganizationRead, OAuthScope.ProjectsRead),
          OAuthProjectAccess.SelectedProjects(Set(projectP1Id))
        )
      case FixtureAlias.General =>
        OAuthClientCreateRequest(
          clientName,
          Set(OAuthScope.OrganizationRead, OAuthScope.ProjectsRead, OAuthScope.WorkUnitsWrite),
          OAuthProjectAccess.SelectedProjects(Set(projectP1Id, projectP2Id))
        )
    }

    def auth(owner: User): AuthContext =
      AuthContext(
        id = owner.id,
        isSuperAdmin = owner.isSuperAdmin,
        identities = Nil,
        authedBy = Set.empty,
        roles = Seq(AuthContext.OrganizationRoles(organizationId, Seq(OrganizationUserRole.Owner))),
        lastAuthedAt = Instant.EPOCH,
        fresh = true
      )

    def resources(now: Instant): PreparedResources = {
      val suffix = runId.toString
      val stem   = alias.databaseValue
      val owner = User(
        ownerUserId,
        UserFirstName("OAuth E2E"),
        UserLastName(s"$stem owner $suffix"),
        None,
        None,
        UserIsSuperAdmin(false),
        UserCreatedAt(now),
        UserModifiedAt(now),
        None,
        UserAccountType.Human
      )
      val foreignOwner = owner.copy(id = foreignOwnerUserId, lastName = UserLastName(s"$stem foreign owner $suffix"))
      val organization = Organization(
        organizationId,
        OrganizationName(s"OAuth E2E $stem $suffix"),
        OrganizationSlug(s"oauth-e2e-$stem-$suffix"),
        OrganizationCreatedAt(now),
        ownerUserId,
        OrganizationModifiedAt(now),
        ownerUserId,
        None
      )
      val foreignOrganization = Organization(
        foreignOrganizationId,
        OrganizationName(s"OAuth E2E $stem foreign $suffix"),
        OrganizationSlug(s"oauth-e2e-$stem-foreign-$suffix"),
        OrganizationCreatedAt(now),
        foreignOwnerUserId,
        OrganizationModifiedAt(now),
        foreignOwnerUserId,
        None
      )
      val ownerMembership = OrganizationUser(ownerMembershipId, organizationId, ownerUserId, Seq(OrganizationUserRole.Owner), None)
      val foreignOwnerMembership =
        OrganizationUser(foreignOwnerMembershipId, foreignOrganizationId, foreignOwnerUserId, Seq(OrganizationUserRole.Owner), None)
      val businessClient        = client(businessClientId, organizationId, ownerUserId, s"$stem client $suffix", now)
      val foreignBusinessClient = client(foreignBusinessClientId, foreignOrganizationId, foreignOwnerUserId, s"$stem foreign $suffix", now)
      new PreparedResources(
        owner,
        ownerMembership,
        organization,
        businessClient,
        project(projectP1Id, organizationId, businessClientId, ownerUserId, s"$stem P1 $suffix", now),
        project(projectP2Id, organizationId, businessClientId, ownerUserId, s"$stem P2 $suffix", now),
        foreignOwner,
        foreignOwnerMembership,
        foreignOrganization,
        foreignBusinessClient,
        project(foreignProjectId, foreignOrganizationId, foreignBusinessClientId, foreignOwnerUserId, s"$stem foreign P1 $suffix", now)
      )
    }

    private def client(
      id: ClientId,
      organizationId: OrganizationId,
      ownerId: UserId,
      name: String,
      now: Instant
    ): Client =
      Client(
        id,
        ClientName(name),
        ClientColor("#3355AA"),
        ClientTexture("oauth-e2e"),
        ClientLogo(""),
        organizationId,
        ClientType.External,
        ClientCreatedAt(now),
        ownerId,
        ClientModifiedAt(now),
        ownerId,
        None,
        None
      )

    private def project(
      id: ProjectId,
      organizationId: OrganizationId,
      clientId: ClientId,
      ownerId: UserId,
      name: String,
      now: Instant
    ): Project =
      Project(id, ProjectName(name), None, organizationId, clientId, ProjectCreatedAt(now), ownerId, ProjectModifiedAt(now), ownerId, None, None)

    private def clientName: String = s"OAuth E2E ${alias.databaseValue} $runId"
  }

  private final class PreparedResources(
    val owner: User,
    val ownerMembership: OrganizationUser,
    val organization: Organization,
    val businessClient: Client,
    val projectP1: Project,
    val projectP2: Project,
    val foreignOwner: User,
    val foreignOwnerMembership: OrganizationUser,
    val foreignOrganization: Organization,
    val foreignBusinessClient: Client,
    val foreignProject: Project)
}
