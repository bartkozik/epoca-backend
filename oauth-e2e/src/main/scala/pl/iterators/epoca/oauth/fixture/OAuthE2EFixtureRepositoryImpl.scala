package pl.iterators.epoca.oauth.fixture

import pl.iterators.epoca.oauth.fixture.OAuthE2EFixtureConfig.{ControllerLeaseObservation, FixtureRole}
import pl.iterators.epoca.oauth.fixture.OAuthE2EFixtureDomain.AdvanceResult.{
  Applied => AdvanceApplied,
  CommandBudgetExhausted,
  Conflict => AdvanceConflict,
  Idempotent => AdvanceIdempotent
}
import pl.iterators.epoca.oauth.fixture.OAuthE2EFixtureDomain.CleanupCompletionResult.{
  AlreadyCleaned => CleanupAlreadyCleaned,
  Cleaned => CleanupCompleted,
  Conflict => CleanupCompletionConflict
}
import pl.iterators.epoca.oauth.fixture.OAuthE2EFixtureDomain.CleanupStartResult.{
  AlreadyCleaned => CleanupStartAlreadyCleaned,
  Applied => CleanupStartApplied,
  Conflict => CleanupStartConflict,
  Resumable => CleanupStartResumable
}
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
import pl.iterators.epoca.oauth.fixture.OAuthE2EFixtureDomain.FixtureSetupPersistenceResult.{
  Applied => SetupApplied,
  CommandBudgetExhausted => SetupCommandBudgetExhausted,
  Conflict => SetupConflict,
  CredentialsUnavailable
}
import pl.iterators.epoca.oauth.fixture.OAuthE2EFixtureDomain.FixtureStatusResult.{
  CommandBudgetExhausted => StatusCommandBudgetExhausted,
  Conflict => StatusConflict,
  Snapshot => StatusSnapshot
}
import pl.iterators.epoca.oauth.fixture.OAuthE2EFixtureDomain.NonCleanupCommandAdmissionResult.{
  Applied => CommandApplied,
  BudgetExhausted => CommandAdmissionBudgetExhausted,
  Conflict => CommandConflict,
  Idempotent => CommandIdempotent
}
import pl.iterators.epoca.oauth.fixture.OAuthE2EFixtureDomain.RevokeOAuth029MarkerResult.{
  Applied => RevokeMarkerApplied,
  Conflict => RevokeMarkerConflict,
  Idempotent => RevokeMarkerIdempotent
}
import pl.iterators.epoca.oauth.fixture.OAuthE2EFixtureDomain.RevokeOAuth029PersistenceResult.{
  Applied => RevokePersistenceApplied,
  CommandBudgetExhausted => RevokeCommandBudgetExhausted,
  Conflict => RevokePersistenceConflict,
  Idempotent => RevokePersistenceIdempotent,
  ReconciliationRequired
}
import pl.iterators.epoca.oauth.fixture.OAuthE2EFixtureDomain.{
  AdvanceResult,
  CleanupCompletionResult,
  CleanupStartResult,
  CleanupStep,
  CleanupStepState,
  CleanupTransitionResult,
  ControllerInstanceId,
  ControllerLeaseState,
  ControllerLossResult,
  FixtureAlias,
  FixtureAuditCommandCategory,
  FixtureAuditOutcome,
  FixtureAuditRow,
  FixtureInitializationResult,
  FixtureOwnership,
  FixtureRunLedger,
  FixtureRunState,
  FixtureSetupPersistenceResult,
  FixtureStatusResult,
  IdempotencyKeyDigest,
  NonCleanupCommandAdmissionResult,
  RevokeOAuth029MarkerResult,
  RevokeOAuth029PersistenceResult
}
import pl.iterators.epoca.utils.db.BaseRepository.driver.api._
import slick.jdbc.SetParameter

import java.sql.Types
import java.util.UUID
import scala.concurrent.ExecutionContext

final class OAuthE2EFixtureRepositoryImpl(implicit ec: ExecutionContext) extends OAuthE2EFixtureRepository {
  private implicit val setUuid: SetParameter[UUID] =
    SetParameter((value, parameters) => parameters.setObject(value, Types.OTHER))

  override def initialize(config: OAuthE2EFixtureConfig, controllerInstanceId: Option[ControllerInstanceId]): DBIO[FixtureInitializationResult] =
    config.role match {
      case FixtureRole.Disabled => DBIO.successful(Disabled)
      case FixtureRole.TimeOnlyReplica =>
        (initializeSchema >> observedLease(forUpdate = false).flatMap {
          case Terminal(_) => DBIO.successful(ControllerTerminal)
          case _           => DBIO.successful(TimeOnlyReady)
        }).transactionally
      case FixtureRole.Controller =>
        controllerInstanceId match {
          case Some(controllerId) => (initializeSchema >> claimController(config, controllerId)).transactionally
          case None               => DBIO.failed(new IllegalArgumentException("Fixture controller instance is required"))
        }
    }

  override def ledger: DBIO[Option[FixtureRunLedger]] =
    sql"""select
             run_id::text,
             run_state,
             offset_seconds,
             row_version,
             controller_lease_id::text,
             controller_lease_state,
             accepted_command_count,
             advance_idempotency_key_digest,
             cleanup_idempotency_key_digest,
             oauth027_revocation_cleanup_state,
             oauth027_resource_cleanup_state,
             oauth028_revocation_cleanup_state,
             oauth028_resource_cleanup_state,
             oauth029_revocation_cleanup_state,
             oauth029_resource_cleanup_state,
             general_revocation_cleanup_state,
             general_resource_cleanup_state
           from oauth_e2e_fixture_run
           where singleton_key = 1"""
      .as[
        (
          Option[String],
          String,
          Int,
          Long,
          Option[String],
          String,
          Int,
          Option[String],
          Option[String],
          String,
          String,
          String,
          String,
          String,
          String,
          String,
          String
        )
      ]
      .headOption
      .map(_.map(decodeLedger))

  override def ownershipRows: DBIO[Seq[FixtureOwnership]] =
    sql"""select
             run_id::text,
             fixture_alias,
             owner_user_id::text,
             owner_membership_id::text,
             organization_id::text,
             business_client_id::text,
             project_p1_id::text,
             project_p2_id::text,
             foreign_owner_user_id::text,
             foreign_owner_membership_id::text,
             foreign_organization_id::text,
             foreign_business_client_id::text,
             foreign_project_id::text,
             oauth_client_id::text
           from oauth_e2e_fixture_ownership
           order by case fixture_alias
             when 'oauth027' then 1
             when 'oauth028' then 2
             when 'oauth029' then 3
             when 'general' then 4
             else 5
           end"""
      .as[(String, String, String, String, String, String, String, String, String, String, String, String, String, String)]
      .map(_.map(decodeOwnership))

  override def markControllerLost(controllerInstanceId: ControllerInstanceId): DBIO[ControllerLossResult] =
    observedLease(forUpdate = true).flatMap {
      case Claimed(existing) if existing == controllerInstanceId =>
        val rawControllerId = controllerInstanceId.value
        sqlu"""update oauth_e2e_fixture_run
               set controller_lease_state = 'terminal', row_version = row_version + 1, modified_at = clock_timestamp()
               where singleton_key = 1 and controller_lease_state = 'claimed' and controller_lease_id = $rawControllerId""".flatMap {
          case 1       => DBIO.successful(MarkedTerminal)
          case changed => DBIO.failed(new IllegalStateException(s"Fixture controller terminal transition affected $changed rows"))
        }
      case Terminal(existing) if existing == controllerInstanceId => DBIO.successful(AlreadyTerminal)
      case _                                                      => DBIO.successful(LeaseMismatch)
    }.transactionally

  override def advance300(idempotencyKeyDigest: IdempotencyKeyDigest): DBIO[AdvanceResult] =
    advance300Program(OAuthE2EFixtureUnguardedStatementBoundary, idempotencyKeyDigest).dbio.transactionally

  override private[fixture] def advance300Program(transaction: OAuthE2EFixtureStatementBoundary, idempotencyKeyDigest: IdempotencyKeyDigest)
    : OAuthE2EFixtureCommandProgram[AdvanceResult] =
    admitNonCleanupCommandProgram(transaction, FixtureAuditCommandCategory.Advance300Seconds, idempotencyKeyDigest) { (nextVersion, nextCount) =>
      transaction.statement(advanceOffsetForUpdate).flatMap {
        case (0, None) =>
          transaction.statement(updateAdvanceStatement(AdvancedOffsetSeconds, Some(idempotencyKeyDigest.value))).flatMap {
            case 1       => transaction.pure(AdvanceApplied(0, AdvancedOffsetSeconds, nextVersion, nextCount): AdvanceResult)
            case changed => transaction.failed(new IllegalStateException(s"Fixture advance affected $changed rows"))
          }
        case _ => transaction.failed(new IllegalStateException("Fixture advance state failed closed"))
      }
    }.map {
      case CommandApplied(value, _, _)                                        => value
      case CommandIdempotent(offset, rowVersion, acceptedCount)               => AdvanceIdempotent(offset, rowVersion, acceptedCount)
      case CommandConflict(offset, rowVersion, acceptedCount)                 => AdvanceConflict(offset, rowVersion, acceptedCount)
      case CommandAdmissionBudgetExhausted(offset, rowVersion, acceptedCount) => CommandBudgetExhausted(offset, rowVersion, acceptedCount)
    }

  override private[fixture] def advance300ForRunProgram(
    transaction: OAuthE2EFixtureStatementBoundary,
    runId: UUID,
    idempotencyKeyDigest: IdempotencyKeyDigest
  ): OAuthE2EFixtureCommandProgram[AdvanceResult] =
    transaction.statement(runIdentityForUpdateRaw).flatMap { case (rawRunId, rawState, offset, version, count) =>
      val storedRunId = rawRunId.map(UUID.fromString)
      val state       = FixtureRunState.fromDatabase(rawState)
      if (storedRunId.contains(runId) && state == FixtureRunState.SetupComplete)
        advance300Program(transaction, idempotencyKeyDigest)
      else transaction.pure(AdvanceConflict(offset, version, count))
    }

  override private[fixture] def beginRevokeOAuth029(
    runId: UUID,
    idempotencyKeyDigest: IdempotencyKeyDigest
  )(
    revokeOwnedClient: FixtureOwnership => DBIO[Unit]
  ): DBIO[RevokeOAuth029PersistenceResult] =
    revokeStateForUpdate.flatMap { case (storedRunId, runState, startedDigest, completedDigest, rowVersion, acceptedCommandCount) =>
      val runMatches = storedRunId.contains(runId) && runState == FixtureRunState.SetupComplete
      if (!runMatches) DBIO.successful(RevokePersistenceConflict)
      else
        completedDigest match {
          case Some(existing) if existing == idempotencyKeyDigest => DBIO.successful(RevokePersistenceIdempotent)
          case Some(_)                                            => DBIO.successful(RevokePersistenceConflict)
          case None =>
            startedDigest match {
              case Some(existing) if existing == idempotencyKeyDigest =>
                ownedAliasForUpdate(runId, FixtureAlias.OAuth029).map(ReconciliationRequired.apply)
              case Some(_) => DBIO.successful(RevokePersistenceConflict)
              case None =>
                nonCleanupCommandKeyRows.flatMap { existingKeys =>
                  val digestAlreadyUsed = existingKeys.exists { case (_, digest) =>
                    IdempotencyKeyDigest.fromDatabase(digest) == idempotencyKeyDigest
                  }
                  if (digestAlreadyUsed) DBIO.successful(RevokePersistenceConflict)
                  else if (acceptedCommandCount >= MaximumAcceptedCommands) DBIO.successful(RevokeCommandBudgetExhausted)
                  else {
                    val nextVersion = rowVersion + 1L
                    val nextCount   = acceptedCommandCount + 1
                    markRevokeStarted(runId, idempotencyKeyDigest, nextVersion, nextCount).flatMap {
                      case 1 =>
                        ownedAliasForUpdate(runId, FixtureAlias.OAuth029).flatMap(ownership =>
                          revokeOwnedClient(ownership).map(_ => RevokePersistenceApplied(ownership): RevokeOAuth029PersistenceResult)
                        )
                      case changed => DBIO.failed(new IllegalStateException(s"Fixture revoke admission affected $changed rows"))
                    }
                  }
                }
            }
        }
    }

  override private[fixture] def completeRevokeOAuth029(runId: UUID, idempotencyKeyDigest: IdempotencyKeyDigest): DBIO[RevokeOAuth029MarkerResult] =
    revokeStateForUpdate.flatMap { case (storedRunId, runState, startedDigest, completedDigest, rowVersion, _) =>
      val runMatches = storedRunId.contains(runId) && (
        runState == FixtureRunState.SetupComplete ||
          runState == FixtureRunState.CleanupRequired ||
          runState == FixtureRunState.Cleaned
      )
      if (!runMatches) DBIO.successful(RevokeMarkerConflict)
      else
        completedDigest match {
          case Some(existing) if existing == idempotencyKeyDigest => DBIO.successful(RevokeMarkerIdempotent)
          case Some(_)                                            => DBIO.successful(RevokeMarkerConflict)
          case None if runState != FixtureRunState.Cleaned && startedDigest.contains(idempotencyKeyDigest) =>
            markRevokeCompleted(runId, idempotencyKeyDigest, rowVersion + 1L) >>
              insertAudit(FixtureAuditCommandCategory.RevokeOAuth029, idempotencyKeyDigest, FixtureAuditOutcome.Applied)
                .map(_ => RevokeMarkerApplied)
          case None => DBIO.successful(RevokeMarkerConflict)
        }
    }

  override private[fixture] def reconcileStartedRevokeOAuth029ForCleanup(runId: UUID): DBIO[RevokeOAuth029MarkerResult] =
    revokeStateForUpdate.flatMap { case (storedRunId, runState, startedDigest, completedDigest, rowVersion, _) =>
      val runMatches = storedRunId.contains(runId) &&
        (runState == FixtureRunState.CleanupRequired || runState == FixtureRunState.Cleaned)
      if (!runMatches) DBIO.successful(RevokeMarkerConflict)
      else
        (startedDigest, completedDigest) match {
          case (None, None)                                             => DBIO.successful(RevokeMarkerIdempotent)
          case (Some(started), Some(completed)) if started == completed => DBIO.successful(RevokeMarkerIdempotent)
          case (Some(started), None) if runState == FixtureRunState.CleanupRequired =>
            markRevokeCompleted(runId, started, rowVersion + 1L) >>
              insertAudit(FixtureAuditCommandCategory.RevokeOAuth029, started, FixtureAuditOutcome.Applied)
                .map(_ => RevokeMarkerApplied)
          case _ => DBIO.successful(RevokeMarkerConflict)
        }
    }

  override def admitNonCleanupCommand[A](
    category: FixtureAuditCommandCategory,
    idempotencyKeyDigest: IdempotencyKeyDigest
  )(
    onApplied: (Long, Int) => DBIO[A]
  ): DBIO[NonCleanupCommandAdmissionResult[A]] =
    admitNonCleanupCommandProgram(OAuthE2EFixtureUnguardedStatementBoundary, category, idempotencyKeyDigest) { (nextVersion, nextCount) =>
      OAuthE2EFixtureUnguardedStatementBoundary.statement(onApplied(nextVersion, nextCount))
    }.dbio.transactionally

  override private[fixture] def admitNonCleanupCommandProgram[A](
    transaction: OAuthE2EFixtureStatementBoundary,
    category: FixtureAuditCommandCategory,
    idempotencyKeyDigest: IdempotencyKeyDigest
  )(
    onApplied: (Long, Int) => OAuthE2EFixtureCommandProgram[A]
  ): OAuthE2EFixtureCommandProgram[NonCleanupCommandAdmissionResult[A]] =
    if (!FixtureAuditCommandCategory.nonCleanupValues.contains(category))
      transaction.failed(new IllegalArgumentException("Cleanup is not admitted through the non-cleanup command budget"))
    else
      transaction.statement(commandAdmissionStateForUpdate).flatMap { case (offsetSeconds, rowVersion, acceptedCommandCount) =>
        transaction
          .statement(nonCleanupCommandKeyRows)
          .map(_.map { case (existingCategory, existingDigest) =>
            FixtureAuditCommandCategory.fromDatabase(existingCategory) -> IdempotencyKeyDigest.fromDatabase(existingDigest)
          })
          .flatMap { existingKeys =>
            val sameDigest   = existingKeys.find(_._2 == idempotencyKeyDigest)
            val sameCategory = existingKeys.find(_._1 == category)
            if (sameDigest.contains(category -> idempotencyKeyDigest))
              transaction.pure(CommandIdempotent(offsetSeconds, rowVersion, acceptedCommandCount))
            else if (sameDigest.nonEmpty || (FixtureAuditCommandCategory.isSingleUse(category) && sameCategory.nonEmpty))
              transaction.pure(CommandConflict(offsetSeconds, rowVersion, acceptedCommandCount))
            else if (acceptedCommandCount >= MaximumAcceptedCommands)
              transaction.pure(CommandAdmissionBudgetExhausted(offsetSeconds, rowVersion, acceptedCommandCount))
            else {
              val nextVersion = rowVersion + 1L
              val nextCount   = acceptedCommandCount + 1
              transaction.statement(updateCommandBudgetStatement(nextVersion, nextCount)).flatMap {
                case 1 =>
                  onApplied(nextVersion, nextCount).flatMap { value =>
                    transaction.statement(insertAuditStatement(category, idempotencyKeyDigest, FixtureAuditOutcome.Applied)).flatMap {
                      case 1       => transaction.pure(CommandApplied(value, nextVersion, nextCount))
                      case changed => transaction.failed(new IllegalStateException(s"Fixture audit insert affected $changed rows"))
                    }
                  }
                case changed => transaction.failed(new IllegalStateException(s"Fixture command admission affected $changed rows"))
              }
            }
          }
      }

  override private[fixture] def setupProgram[A](
    transaction: OAuthE2EFixtureStatementBoundary,
    runId: UUID,
    idempotencyKeyDigest: IdempotencyKeyDigest,
    attemptMatches: Boolean,
    ownership: Vector[FixtureOwnership]
  )(
    onApplied: => OAuthE2EFixtureCommandProgram[A]
  ): OAuthE2EFixtureCommandProgram[FixtureSetupPersistenceResult[A]] =
    transaction
      .statement(setupStateForUpdateRaw)
      .map { case (runId, state) => runId.map(UUID.fromString) -> FixtureRunState.fromDatabase(state) }
      .flatMap {
        case (_, FixtureRunState.SetupComplete)                       => transaction.pure(CredentialsUnavailable)
        case (_, FixtureRunState.CleanupRequired)                     => transaction.pure(CredentialsUnavailable)
        case (_, FixtureRunState.Cleaned)                             => transaction.pure(CredentialsUnavailable)
        case (Some(_), FixtureRunState.AwaitingSetup)                 => transaction.pure(SetupConflict)
        case (None, FixtureRunState.AwaitingSetup) if !attemptMatches => transaction.pure(SetupConflict)
        case (None, FixtureRunState.AwaitingSetup) =>
          admitNonCleanupCommandProgram(transaction, FixtureAuditCommandCategory.Setup, idempotencyKeyDigest) { (_, _) =>
            transaction.statement(attachRunStatement(runId)).flatMap {
              case 1 =>
                onApplied.flatMap { value =>
                  ownership.foldLeft(transaction.pure(())) { (program, row) =>
                    program >> transaction.statement(insertOwnershipStatement(row)).flatMap {
                      case 1       => transaction.pure(())
                      case changed => transaction.failed(new IllegalStateException(s"Fixture ownership insert affected $changed rows"))
                    }
                  } >> transaction.statement(markSetupCompleteStatement(runId)).flatMap {
                    case 1       => transaction.pure(value)
                    case changed => transaction.failed(new IllegalStateException(s"Fixture setup completion affected $changed rows"))
                  }
                }
              case changed => transaction.failed(new IllegalStateException(s"Fixture run attachment affected $changed rows"))
            }
          }.map {
            case CommandApplied(value, _, _)        => SetupApplied(value)
            case _: CommandIdempotent               => CredentialsUnavailable
            case _: CommandConflict                 => SetupConflict
            case _: CommandAdmissionBudgetExhausted => SetupCommandBudgetExhausted
          }
      }

  override def transitionCleanupStep(
    step: CleanupStep,
    expected: CleanupStepState,
    target: CleanupStepState,
    idempotencyKeyDigest: IdempotencyKeyDigest
  ): DBIO[CleanupTransitionResult] =
    cleanupStateForUpdate(step).flatMap { case (current, rowVersion, existingDigest) =>
      val transitionAllowed =
        (expected == CleanupStepState.Pending && target == CleanupStepState.InProgress) ||
          (expected == CleanupStepState.InProgress && target == CleanupStepState.Complete)
      val digestMatches = existingDigest.forall(value => IdempotencyKeyDigest.fromDatabase(value) == idempotencyKeyDigest)

      val (mutation, outcome, result) =
        if (!digestMatches)
          (DBIO.successful(()), FixtureAuditOutcome.Conflict, CleanupConflict(current, rowVersion))
        else if (transitionAllowed && current == expected) {
          val nextVersion = rowVersion + 1L
          (updateCleanupStep(step, target, nextVersion, idempotencyKeyDigest), FixtureAuditOutcome.Applied, CleanupApplied(target, nextVersion))
        } else if (transitionAllowed && current == target)
          (DBIO.successful(()), FixtureAuditOutcome.Idempotent, CleanupIdempotent(current, rowVersion))
        else
          (DBIO.successful(()), FixtureAuditOutcome.Conflict, CleanupConflict(current, rowVersion))

      mutation >> insertAudit(FixtureAuditCommandCategory.CleanupStepTransition, idempotencyKeyDigest, outcome).map(_ => result)
    }.transactionally

  override def beginCleanup(runId: UUID, idempotencyKeyDigest: IdempotencyKeyDigest): DBIO[CleanupStartResult] =
    beginCleanupProgram(OAuthE2EFixtureUnguardedStatementBoundary, runId, idempotencyKeyDigest).dbio.transactionally

  override private[fixture] def beginCleanupProgram(
    transaction: OAuthE2EFixtureStatementBoundary,
    runId: UUID,
    idempotencyKeyDigest: IdempotencyKeyDigest
  ): OAuthE2EFixtureCommandProgram[CleanupStartResult] =
    transaction.statement(cleanupLedgerForUpdateRaw).map(decodeCleanupLedger).flatMap { case (storedRunId, runState, storedDigest, _) =>
      val digestMatches = storedDigest.forall(value => IdempotencyKeyDigest.fromDatabase(value) == idempotencyKeyDigest)
      if (!storedRunId.contains(runId) || !digestMatches) transaction.pure(CleanupStartConflict)
      else
        runState match {
          case FixtureRunState.SetupComplete if storedDigest.isEmpty =>
            transaction.statement(nonCleanupCommandKeyRows).flatMap { existingKeys =>
              val digestAlreadyUsed = existingKeys.exists { case (_, digest) =>
                IdempotencyKeyDigest.fromDatabase(digest) == idempotencyKeyDigest
              }
              if (digestAlreadyUsed) transaction.pure(CleanupStartConflict)
              else
                transaction.statement(startCleanupStatement(runId, idempotencyKeyDigest)).flatMap {
                  case 1       => transaction.pure(CleanupStartApplied)
                  case changed => transaction.failed(new IllegalStateException(s"Fixture cleanup start affected $changed rows"))
                }
            }
          case FixtureRunState.CleanupRequired => transaction.pure(CleanupStartResumable)
          case FixtureRunState.Cleaned         => transaction.pure(CleanupStartAlreadyCleaned)
          case _                               => transaction.pure(CleanupStartConflict)
        }
    }

  override private[fixture] def cleanupOwnershipForRunProgram(
    transaction: OAuthE2EFixtureStatementBoundary,
    runId: UUID,
    alias: FixtureAlias,
    idempotencyKeyDigest: IdempotencyKeyDigest
  ): OAuthE2EFixtureCommandProgram[FixtureOwnership] =
    transaction.statement(cleanupLedgerForUpdateRaw).map(decodeCleanupLedger).flatMap { case (storedRunId, runState, storedDigest, _) =>
      val digestMatches = storedDigest.exists(value => IdempotencyKeyDigest.fromDatabase(value) == idempotencyKeyDigest)
      if (
        !storedRunId.contains(runId) ||
        (runState != FixtureRunState.CleanupRequired && runState != FixtureRunState.Cleaned) ||
        !digestMatches
      )
        transaction.failed(new IllegalStateException("Fixture cleanup ownership failed closed"))
      else
        transaction.statement(ownershipForAliasRaw(runId, alias)).flatMap {
          case Some(raw) => transaction.pure(decodeOwnership(raw))
          case None      => transaction.failed(new IllegalStateException("Fixture cleanup ownership is unavailable"))
        }
    }

  override private[fixture] def transitionCleanupStepForRunProgram(
    transaction: OAuthE2EFixtureStatementBoundary,
    runId: UUID,
    step: CleanupStep,
    expected: CleanupStepState,
    target: CleanupStepState,
    idempotencyKeyDigest: IdempotencyKeyDigest
  ): OAuthE2EFixtureCommandProgram[CleanupTransitionResult] =
    transaction.statement(cleanupStateForRunForUpdateRaw(runId, step)).flatMap {
      case Some((rawState, rowVersion, existingDigest, rawRunState)) =>
        val current  = CleanupStepState.fromDatabase(rawState)
        val runState = FixtureRunState.fromDatabase(rawRunState)
        val transitionAllowed =
          (expected == CleanupStepState.Pending && target == CleanupStepState.InProgress) ||
            (expected == CleanupStepState.InProgress && target == CleanupStepState.Complete)
        val digestMatches = existingDigest.exists(value => IdempotencyKeyDigest.fromDatabase(value) == idempotencyKeyDigest)
        if (runState == FixtureRunState.Cleaned && digestMatches && current == CleanupStepState.Complete)
          transaction.pure(CleanupIdempotent(CleanupStepState.Complete, rowVersion))
        else {
          val (mutation, outcome, result) =
            if (runState != FixtureRunState.CleanupRequired || !digestMatches)
              (transaction.pure(()), FixtureAuditOutcome.Conflict, CleanupConflict(current, rowVersion))
            else if (transitionAllowed && current == expected) {
              val nextVersion = rowVersion + 1L
              (
                transaction.statement(updateCleanupStepStatement(step, target, nextVersion, idempotencyKeyDigest)).flatMap {
                  case 1       => transaction.pure(())
                  case changed => transaction.failed(new IllegalStateException(s"Fixture cleanup transition affected $changed rows"))
                },
                FixtureAuditOutcome.Applied,
                CleanupApplied(target, nextVersion)
              )
            } else if (transitionAllowed && (current == target || (target == CleanupStepState.InProgress && current == CleanupStepState.Complete)))
              (transaction.pure(()), FixtureAuditOutcome.Idempotent, CleanupIdempotent(current, rowVersion))
            else
              (transaction.pure(()), FixtureAuditOutcome.Conflict, CleanupConflict(current, rowVersion))

          mutation >> transaction
            .statement(insertAuditStatement(FixtureAuditCommandCategory.CleanupStepTransition, idempotencyKeyDigest, outcome))
            .map(_ => result)
        }
      case None => transaction.failed(new IllegalStateException("Fixture cleanup run is unavailable"))
    }

  override def completeCleanup(runId: UUID, idempotencyKeyDigest: IdempotencyKeyDigest): DBIO[CleanupCompletionResult] =
    completeCleanupProgram(OAuthE2EFixtureUnguardedStatementBoundary, runId, idempotencyKeyDigest).dbio.transactionally

  override private[fixture] def completeCleanupProgram(
    transaction: OAuthE2EFixtureStatementBoundary,
    runId: UUID,
    idempotencyKeyDigest: IdempotencyKeyDigest
  ): OAuthE2EFixtureCommandProgram[CleanupCompletionResult] =
    transaction.statement(cleanupLedgerForUpdateRaw).map(decodeCleanupLedger).flatMap { case (storedRunId, runState, storedDigest, cleanupStates) =>
      val digestMatches = storedDigest.exists(value => IdempotencyKeyDigest.fromDatabase(value) == idempotencyKeyDigest)
      if (!storedRunId.contains(runId) || !digestMatches) transaction.pure(CleanupCompletionConflict)
      else
        runState match {
          case FixtureRunState.Cleaned => transaction.pure(CleanupAlreadyCleaned)
          case FixtureRunState.CleanupRequired if cleanupStates.forall(_ == CleanupStepState.Complete) =>
            transaction.statement(completeCleanupStatement(runId, idempotencyKeyDigest)).flatMap {
              case 1       => transaction.pure(CleanupCompleted)
              case changed => transaction.failed(new IllegalStateException(s"Fixture cleanup completion affected $changed rows"))
            }
          case _ => transaction.pure(CleanupCompletionConflict)
        }
    }

  override private[fixture] def statusForRunProgram(
    transaction: OAuthE2EFixtureStatementBoundary,
    runId: UUID,
    idempotencyKeyDigest: IdempotencyKeyDigest
  ): OAuthE2EFixtureCommandProgram[FixtureStatusResult] =
    transaction.statement(runIdentityForUpdateRaw).flatMap { case (rawRunId, _, _, _, _) =>
      if (!rawRunId.exists(value => UUID.fromString(value) == runId)) transaction.pure(StatusConflict)
      else
        admitNonCleanupCommandProgram(transaction, FixtureAuditCommandCategory.Status, idempotencyKeyDigest) { (_, _) =>
          statusSnapshotProgram(transaction)
        }.flatMap {
          case CommandApplied(value, _, _)        => transaction.pure(value)
          case _: CommandIdempotent               => statusSnapshotProgram(transaction)
          case _: CommandConflict                 => transaction.pure(StatusConflict)
          case _: CommandAdmissionBudgetExhausted => transaction.pure(StatusCommandBudgetExhausted)
        }
    }

  private def statusSnapshotProgram(transaction: OAuthE2EFixtureStatementBoundary): OAuthE2EFixtureCommandProgram[FixtureStatusResult] =
    transaction.statement(cleanupLedgerForUpdateRaw).map(decodeCleanupLedger).flatMap { case (_, runState, _, cleanupSteps) =>
      transaction.statement(commandAdmissionStateForUpdate).map { case (_, _, acceptedCommandCount) =>
        StatusSnapshot(
          runState,
          cleanupSteps.count(_ == CleanupStepState.Complete),
          CleanupStep.values.size,
          acceptedCommandCount
        ): FixtureStatusResult
      }
    }

  override def auditRows: DBIO[Seq[FixtureAuditRow]] =
    sql"""select sequence, command_category, idempotency_key_digest, outcome_category,
                  extract(epoch from authoritative_at)
           from oauth_e2e_fixture_audit
           order by sequence"""
      .as[(Long, String, String, String, BigDecimal)]
      .map(_.map { case (sequence, commandCategory, digest, outcome, epochSeconds) =>
        val seconds = epochSeconds.longValue
        val nanos   = ((epochSeconds - BigDecimal(seconds)) * BigDecimal(1000000000L)).longValue
        FixtureAuditRow(
          sequence = sequence,
          commandCategory = FixtureAuditCommandCategory.fromDatabase(commandCategory),
          idempotencyKeyDigest = IdempotencyKeyDigest.fromDatabase(digest),
          outcome = FixtureAuditOutcome.fromDatabase(outcome),
          authoritativeAt = java.time.Instant.ofEpochSecond(seconds, nanos)
        )
      })

  override private[fixture] def authoritativeNowEpochSeconds: DBIO[BigDecimal] =
    sql"""select extract(epoch from clock_timestamp() + offset_seconds * interval '1 second')
           from oauth_e2e_fixture_run
           where singleton_key = 1""".as[BigDecimal].head

  override def authoritativeNow: DBIO[java.time.Instant] =
    authoritativeNowEpochSeconds.map { epochSeconds =>
      val seconds = epochSeconds.longValue
      val nanos   = ((epochSeconds - BigDecimal(seconds)) * BigDecimal(1000000000L)).longValue
      java.time.Instant.ofEpochSecond(seconds, nanos)
    }

  private val initializeSchema: DBIO[Unit] =
    for {
      _ <- sql"select pg_catalog.pg_advisory_xact_lock(522, 3)::text".as[String].head
      _ <- sqlu"""create table if not exists oauth_e2e_fixture_run (
             singleton_key smallint primary key,
             run_id uuid unique,
             run_state varchar(32) not null,
             offset_seconds integer not null,
             row_version bigint not null,
             controller_lease_id uuid,
             controller_lease_state varchar(16) not null,
             accepted_command_count integer not null,
             advance_idempotency_key_digest char(64),
             revoke_oauth029_started_digest char(64),
             revoke_oauth029_completed_digest char(64),
             cleanup_idempotency_key_digest char(64),
             oauth027_revocation_cleanup_state varchar(16) not null,
             oauth027_resource_cleanup_state varchar(16) not null,
             oauth028_revocation_cleanup_state varchar(16) not null,
             oauth028_resource_cleanup_state varchar(16) not null,
             oauth029_revocation_cleanup_state varchar(16) not null,
             oauth029_resource_cleanup_state varchar(16) not null,
             general_revocation_cleanup_state varchar(16) not null,
             general_resource_cleanup_state varchar(16) not null,
             created_at timestamptz not null default clock_timestamp(),
             modified_at timestamptz not null default clock_timestamp(),
             constraint oauth_e2e_fixture_run_singleton_check check (singleton_key = 1),
             constraint oauth_e2e_fixture_run_state_check
               check (run_state in ('awaiting-setup', 'setup-complete', 'cleanup-required', 'cleaned')),
             constraint oauth_e2e_fixture_run_offset_check check (offset_seconds in (0, 300)),
             constraint oauth_e2e_fixture_run_version_check check (row_version >= 0),
             constraint oauth_e2e_fixture_run_command_budget_check check (accepted_command_count between 0 and 32),
             constraint oauth_e2e_fixture_run_lease_check check (
               (controller_lease_state = 'unclaimed' and controller_lease_id is null) or
               (controller_lease_state in ('claimed', 'terminal') and controller_lease_id is not null)
             ),
             constraint oauth_e2e_fixture_run_advance_check check (
               (offset_seconds = 0 and advance_idempotency_key_digest is null) or
               (offset_seconds = 300 and advance_idempotency_key_digest ~ '^[0-9a-f]{64}$$')
             ),
             constraint oauth_e2e_fixture_run_revoke_check check (
               (revoke_oauth029_started_digest is null and revoke_oauth029_completed_digest is null) or
               (revoke_oauth029_started_digest is not null and
                 revoke_oauth029_started_digest ~ '^[0-9a-f]{64}$$' and
                 (revoke_oauth029_completed_digest is null or revoke_oauth029_completed_digest = revoke_oauth029_started_digest))
             ),
             constraint oauth_e2e_fixture_run_cleanup_digest_check check (
               cleanup_idempotency_key_digest is null or cleanup_idempotency_key_digest ~ '^[0-9a-f]{64}$$'
             ),
             constraint oauth_e2e_fixture_run_cleanup_check check (
               oauth027_revocation_cleanup_state in ('pending', 'in-progress', 'complete') and
               oauth027_resource_cleanup_state in ('pending', 'in-progress', 'complete') and
               oauth028_revocation_cleanup_state in ('pending', 'in-progress', 'complete') and
               oauth028_resource_cleanup_state in ('pending', 'in-progress', 'complete') and
               oauth029_revocation_cleanup_state in ('pending', 'in-progress', 'complete') and
               oauth029_resource_cleanup_state in ('pending', 'in-progress', 'complete') and
               general_revocation_cleanup_state in ('pending', 'in-progress', 'complete') and
               general_resource_cleanup_state in ('pending', 'in-progress', 'complete')
             )
           )"""
      _ <- sqlu"alter table oauth_e2e_fixture_run add column if not exists revoke_oauth029_started_digest char(64)"
      _ <- sqlu"alter table oauth_e2e_fixture_run add column if not exists revoke_oauth029_completed_digest char(64)"
      _ <- sqlu"alter table oauth_e2e_fixture_run drop constraint if exists oauth_e2e_fixture_run_revoke_check"
      _ <- sqlu"""alter table oauth_e2e_fixture_run add constraint oauth_e2e_fixture_run_revoke_check check (
             (revoke_oauth029_started_digest is null and revoke_oauth029_completed_digest is null) or
             (revoke_oauth029_started_digest is not null and
               revoke_oauth029_started_digest ~ '^[0-9a-f]{64}$$' and
               (revoke_oauth029_completed_digest is null or revoke_oauth029_completed_digest = revoke_oauth029_started_digest))
           )"""
      _ <- sqlu"""create table if not exists oauth_e2e_fixture_audit (
             sequence bigserial primary key,
             command_category varchar(32) not null,
             idempotency_key_digest char(64) not null,
             outcome_category varchar(24) not null,
             authoritative_at timestamptz not null,
             constraint oauth_e2e_fixture_audit_command_check check (
               command_category in ('setup', 'advance-300-seconds', 'revoke-oauth029', 'status', 'cleanup-step')
             ),
             constraint oauth_e2e_fixture_audit_digest_check check (idempotency_key_digest ~ '^[0-9a-f]{64}$$'),
             constraint oauth_e2e_fixture_audit_outcome_check check (
               outcome_category in ('applied', 'idempotent', 'conflict', 'failed')
             )
           )"""
      _ <- sqlu"""insert into oauth_e2e_fixture_run (
             singleton_key, run_id, run_state, offset_seconds, row_version,
             controller_lease_id, controller_lease_state, accepted_command_count,
             advance_idempotency_key_digest, revoke_oauth029_started_digest, revoke_oauth029_completed_digest,
             cleanup_idempotency_key_digest,
             oauth027_revocation_cleanup_state, oauth027_resource_cleanup_state,
             oauth028_revocation_cleanup_state, oauth028_resource_cleanup_state,
             oauth029_revocation_cleanup_state, oauth029_resource_cleanup_state,
             general_revocation_cleanup_state, general_resource_cleanup_state
           ) values (
             1, null, 'awaiting-setup', 0, 0,
             null, 'unclaimed', 0,
             null, null, null,
             null,
             'pending', 'pending',
             'pending', 'pending',
             'pending', 'pending',
             'pending', 'pending'
           ) on conflict (singleton_key) do nothing"""
      _ <- sqlu"""create table if not exists oauth_e2e_fixture_ownership (
             fixture_alias varchar(16) primary key,
             run_id uuid not null references oauth_e2e_fixture_run(run_id),
             owner_user_id uuid not null unique references public."user"(id),
             owner_membership_id uuid not null unique references public.organization_user(id),
             organization_id uuid not null unique references public.organization(id),
             business_client_id uuid not null unique references public.client(id),
             project_p1_id uuid not null unique references public.project(id),
             project_p2_id uuid not null unique references public.project(id),
             foreign_owner_user_id uuid not null unique references public."user"(id),
             foreign_owner_membership_id uuid not null unique references public.organization_user(id),
             foreign_organization_id uuid not null unique references public.organization(id),
             foreign_business_client_id uuid not null unique references public.client(id),
             foreign_project_id uuid not null unique references public.project(id),
             oauth_client_id uuid not null unique references public.oauth_client(id),
             constraint oauth_e2e_fixture_ownership_alias_check check (fixture_alias in ('oauth027', 'oauth028', 'oauth029', 'general')),
             constraint oauth_e2e_fixture_ownership_org_boundary_check check (organization_id <> foreign_organization_id),
             constraint oauth_e2e_fixture_ownership_owner_boundary_check check (owner_user_id <> foreign_owner_user_id),
             constraint oauth_e2e_fixture_ownership_project_boundary_check check (
               project_p1_id <> project_p2_id and project_p1_id <> foreign_project_id and project_p2_id <> foreign_project_id
             )
           )"""
    } yield ()

  private val MaximumAcceptedCommands = 32
  private val AdvancedOffsetSeconds   = 300

  private val setupStateForUpdateRaw: DBIO[(Option[String], String)] =
    sql"""select run_id::text, run_state
           from oauth_e2e_fixture_run
           where singleton_key = 1
           for update"""
      .as[(Option[String], String)]
      .head

  private def attachRunStatement(runId: UUID): DBIO[Int] =
    sqlu"""update oauth_e2e_fixture_run
           set run_id = $runId, modified_at = clock_timestamp()
           where singleton_key = 1 and run_id is null and run_state = 'awaiting-setup'"""

  private def markSetupCompleteStatement(runId: UUID): DBIO[Int] =
    sqlu"""update oauth_e2e_fixture_run
           set run_state = 'setup-complete', modified_at = clock_timestamp()
           where singleton_key = 1 and run_id = $runId and run_state = 'awaiting-setup'"""

  private def insertOwnershipStatement(row: FixtureOwnership): DBIO[Int] = {
    val alias                    = row.alias.databaseValue
    val runId                    = row.runId
    val ownerUserId              = row.ownerUserId.asInstanceOf[UUID]
    val ownerMembershipId        = row.ownerMembershipId.asInstanceOf[UUID]
    val organizationId           = row.organizationId.asInstanceOf[UUID]
    val businessClientId         = row.businessClientId.asInstanceOf[UUID]
    val projectP1Id              = row.projectP1Id.asInstanceOf[UUID]
    val projectP2Id              = row.projectP2Id.asInstanceOf[UUID]
    val foreignOwnerUserId       = row.foreignOwnerUserId.asInstanceOf[UUID]
    val foreignOwnerMembershipId = row.foreignOwnerMembershipId.asInstanceOf[UUID]
    val foreignOrganizationId    = row.foreignOrganizationId.asInstanceOf[UUID]
    val foreignBusinessClientId  = row.foreignBusinessClientId.asInstanceOf[UUID]
    val foreignProjectId         = row.foreignProjectId.asInstanceOf[UUID]
    val oauthClientId            = row.oauthClientId.asInstanceOf[UUID]
    sqlu"""insert into oauth_e2e_fixture_ownership (
             fixture_alias, run_id, owner_user_id, owner_membership_id, organization_id,
             business_client_id, project_p1_id, project_p2_id,
             foreign_owner_user_id, foreign_owner_membership_id, foreign_organization_id,
             foreign_business_client_id, foreign_project_id, oauth_client_id
           ) values (
             $alias, $runId, $ownerUserId, $ownerMembershipId, $organizationId,
             $businessClientId, $projectP1Id, $projectP2Id,
             $foreignOwnerUserId, $foreignOwnerMembershipId, $foreignOrganizationId,
             $foreignBusinessClientId, $foreignProjectId, $oauthClientId
           )"""
  }

  private val commandAdmissionStateForUpdate: DBIO[(Int, Long, Int)] =
    sql"""select offset_seconds, row_version, accepted_command_count
           from oauth_e2e_fixture_run
           where singleton_key = 1
           for update""".as[(Int, Long, Int)].head

  private val advanceOffsetForUpdate: DBIO[(Int, Option[String])] =
    sql"""select offset_seconds, advance_idempotency_key_digest
           from oauth_e2e_fixture_run
           where singleton_key = 1
           for update""".as[(Int, Option[String])].head

  private val runIdentityForUpdateRaw: DBIO[(Option[String], String, Int, Long, Int)] =
    sql"""select run_id::text, run_state, offset_seconds, row_version, accepted_command_count
           from oauth_e2e_fixture_run
           where singleton_key = 1
           for update"""
      .as[(Option[String], String, Int, Long, Int)]
      .head

  private val revokeStateForUpdate: DBIO[(Option[UUID], FixtureRunState, Option[IdempotencyKeyDigest], Option[IdempotencyKeyDigest], Long, Int)] =
    sql"""select run_id::text, run_state, revoke_oauth029_started_digest, revoke_oauth029_completed_digest,
                  row_version, accepted_command_count
           from oauth_e2e_fixture_run
           where singleton_key = 1
           for update"""
      .as[(Option[String], String, Option[String], Option[String], Long, Int)]
      .head
      .map { case (runId, state, started, completed, version, count) =>
        (
          runId.map(UUID.fromString),
          FixtureRunState.fromDatabase(state),
          started.map(IdempotencyKeyDigest.fromDatabase),
          completed.map(IdempotencyKeyDigest.fromDatabase),
          version,
          count
        )
      }

  private def ownedAliasForUpdate(runId: UUID, alias: FixtureAlias): DBIO[FixtureOwnership] = {
    val rawAlias = alias.databaseValue
    sql"""select
             run_id::text, fixture_alias, owner_user_id::text, owner_membership_id::text, organization_id::text,
             business_client_id::text, project_p1_id::text, project_p2_id::text,
             foreign_owner_user_id::text, foreign_owner_membership_id::text, foreign_organization_id::text,
             foreign_business_client_id::text, foreign_project_id::text, oauth_client_id::text
           from oauth_e2e_fixture_ownership
           where run_id = $runId and fixture_alias = $rawAlias
           for update"""
      .as[(String, String, String, String, String, String, String, String, String, String, String, String, String, String)]
      .head
      .map(decodeOwnership)
  }

  private def markRevokeStarted(
    runId: UUID,
    digest: IdempotencyKeyDigest,
    rowVersion: Long,
    acceptedCommandCount: Int
  ): DBIO[Int] = {
    val rawDigest = digest.value
    sqlu"""update oauth_e2e_fixture_run
           set revoke_oauth029_started_digest = $rawDigest,
               row_version = $rowVersion,
               accepted_command_count = $acceptedCommandCount,
               modified_at = clock_timestamp()
           where singleton_key = 1 and run_id = $runId and revoke_oauth029_started_digest is null"""
  }

  private def markRevokeCompleted(
    runId: UUID,
    digest: IdempotencyKeyDigest,
    rowVersion: Long
  ): DBIO[Unit] = {
    val rawDigest = digest.value
    sqlu"""update oauth_e2e_fixture_run
           set revoke_oauth029_completed_digest = $rawDigest,
               row_version = $rowVersion,
               modified_at = clock_timestamp()
           where singleton_key = 1 and run_id = $runId
             and revoke_oauth029_started_digest = $rawDigest and revoke_oauth029_completed_digest is null""".flatMap {
      case 1       => DBIO.successful(())
      case changed => DBIO.failed(new IllegalStateException(s"Fixture revoke marker affected $changed rows"))
    }
  }

  private val nonCleanupCommandKeyRows: DBIO[Seq[(String, String)]] =
    sql"""select command_category, idempotency_key_digest
           from oauth_e2e_fixture_audit
           where command_category <> 'cleanup-step'
           union
           select 'revoke-oauth029', revoke_oauth029_started_digest
           from oauth_e2e_fixture_run
           where singleton_key = 1 and revoke_oauth029_started_digest is not null
           union
           select 'cleanup-step', cleanup_idempotency_key_digest
           from oauth_e2e_fixture_run
           where singleton_key = 1 and cleanup_idempotency_key_digest is not null"""
      .as[(String, String)]

  private def updateCommandBudgetStatement(rowVersion: Long, acceptedCommandCount: Int): DBIO[Int] =
    sqlu"""update oauth_e2e_fixture_run
           set row_version = $rowVersion,
               accepted_command_count = $acceptedCommandCount,
               modified_at = clock_timestamp()
           where singleton_key = 1"""

  private def updateAdvanceStatement(offsetSeconds: Int, digest: Option[String]): DBIO[Int] =
    sqlu"""update oauth_e2e_fixture_run
           set offset_seconds = $offsetSeconds,
               advance_idempotency_key_digest = $digest,
               modified_at = clock_timestamp()
           where singleton_key = 1"""

  private def insertAudit(
    commandCategory: FixtureAuditCommandCategory,
    digest: IdempotencyKeyDigest,
    outcome: FixtureAuditOutcome
  ): DBIO[Unit] =
    insertAuditStatement(commandCategory, digest, outcome).flatMap {
      case 1       => DBIO.successful(())
      case changed => DBIO.failed(new IllegalStateException(s"Fixture audit insert affected $changed rows"))
    }

  private def insertAuditStatement(
    commandCategory: FixtureAuditCommandCategory,
    digest: IdempotencyKeyDigest,
    outcome: FixtureAuditOutcome
  ): DBIO[Int] = {
    val rawCommandCategory = commandCategory.databaseValue
    val rawDigest          = digest.value
    val rawOutcome         = outcome.databaseValue
    sqlu"""insert into oauth_e2e_fixture_audit (
             command_category, idempotency_key_digest, outcome_category, authoritative_at
           ) select
             $rawCommandCategory, $rawDigest, $rawOutcome,
             clock_timestamp() + offset_seconds * interval '1 second'
           from oauth_e2e_fixture_run
           where singleton_key = 1"""
  }

  private def cleanupStateForUpdate(step: CleanupStep): DBIO[(CleanupStepState, Long, Option[String])] = {
    val query = step match {
      case CleanupStep.OAuth027Revocation =>
        sql"select oauth027_revocation_cleanup_state, row_version, cleanup_idempotency_key_digest from oauth_e2e_fixture_run where singleton_key = 1 for update"
          .as[(String, Long, Option[String])]
      case CleanupStep.OAuth027Resources =>
        sql"select oauth027_resource_cleanup_state, row_version, cleanup_idempotency_key_digest from oauth_e2e_fixture_run where singleton_key = 1 for update"
          .as[(String, Long, Option[String])]
      case CleanupStep.OAuth028Revocation =>
        sql"select oauth028_revocation_cleanup_state, row_version, cleanup_idempotency_key_digest from oauth_e2e_fixture_run where singleton_key = 1 for update"
          .as[(String, Long, Option[String])]
      case CleanupStep.OAuth028Resources =>
        sql"select oauth028_resource_cleanup_state, row_version, cleanup_idempotency_key_digest from oauth_e2e_fixture_run where singleton_key = 1 for update"
          .as[(String, Long, Option[String])]
      case CleanupStep.OAuth029Revocation =>
        sql"select oauth029_revocation_cleanup_state, row_version, cleanup_idempotency_key_digest from oauth_e2e_fixture_run where singleton_key = 1 for update"
          .as[(String, Long, Option[String])]
      case CleanupStep.OAuth029Resources =>
        sql"select oauth029_resource_cleanup_state, row_version, cleanup_idempotency_key_digest from oauth_e2e_fixture_run where singleton_key = 1 for update"
          .as[(String, Long, Option[String])]
      case CleanupStep.GeneralRevocation =>
        sql"select general_revocation_cleanup_state, row_version, cleanup_idempotency_key_digest from oauth_e2e_fixture_run where singleton_key = 1 for update"
          .as[(String, Long, Option[String])]
      case CleanupStep.GeneralResources =>
        sql"select general_resource_cleanup_state, row_version, cleanup_idempotency_key_digest from oauth_e2e_fixture_run where singleton_key = 1 for update"
          .as[(String, Long, Option[String])]
    }
    query.head.map { case (state, rowVersion, digest) =>
      (CleanupStepState.fromDatabase(state), rowVersion, digest.map(_.trim))
    }
  }

  private def updateCleanupStep(
    step: CleanupStep,
    target: CleanupStepState,
    rowVersion: Long,
    idempotencyKeyDigest: IdempotencyKeyDigest
  ): DBIO[Unit] = {
    val rawTarget = target.databaseValue
    val rawDigest = idempotencyKeyDigest.value
    val update = step match {
      case CleanupStep.OAuth027Revocation =>
        sqlu"update oauth_e2e_fixture_run set oauth027_revocation_cleanup_state = $rawTarget, cleanup_idempotency_key_digest = coalesce(cleanup_idempotency_key_digest, $rawDigest), row_version = $rowVersion, modified_at = clock_timestamp() where singleton_key = 1"
      case CleanupStep.OAuth027Resources =>
        sqlu"update oauth_e2e_fixture_run set oauth027_resource_cleanup_state = $rawTarget, cleanup_idempotency_key_digest = coalesce(cleanup_idempotency_key_digest, $rawDigest), row_version = $rowVersion, modified_at = clock_timestamp() where singleton_key = 1"
      case CleanupStep.OAuth028Revocation =>
        sqlu"update oauth_e2e_fixture_run set oauth028_revocation_cleanup_state = $rawTarget, cleanup_idempotency_key_digest = coalesce(cleanup_idempotency_key_digest, $rawDigest), row_version = $rowVersion, modified_at = clock_timestamp() where singleton_key = 1"
      case CleanupStep.OAuth028Resources =>
        sqlu"update oauth_e2e_fixture_run set oauth028_resource_cleanup_state = $rawTarget, cleanup_idempotency_key_digest = coalesce(cleanup_idempotency_key_digest, $rawDigest), row_version = $rowVersion, modified_at = clock_timestamp() where singleton_key = 1"
      case CleanupStep.OAuth029Revocation =>
        sqlu"update oauth_e2e_fixture_run set oauth029_revocation_cleanup_state = $rawTarget, cleanup_idempotency_key_digest = coalesce(cleanup_idempotency_key_digest, $rawDigest), row_version = $rowVersion, modified_at = clock_timestamp() where singleton_key = 1"
      case CleanupStep.OAuth029Resources =>
        sqlu"update oauth_e2e_fixture_run set oauth029_resource_cleanup_state = $rawTarget, cleanup_idempotency_key_digest = coalesce(cleanup_idempotency_key_digest, $rawDigest), row_version = $rowVersion, modified_at = clock_timestamp() where singleton_key = 1"
      case CleanupStep.GeneralRevocation =>
        sqlu"update oauth_e2e_fixture_run set general_revocation_cleanup_state = $rawTarget, cleanup_idempotency_key_digest = coalesce(cleanup_idempotency_key_digest, $rawDigest), row_version = $rowVersion, modified_at = clock_timestamp() where singleton_key = 1"
      case CleanupStep.GeneralResources =>
        sqlu"update oauth_e2e_fixture_run set general_resource_cleanup_state = $rawTarget, cleanup_idempotency_key_digest = coalesce(cleanup_idempotency_key_digest, $rawDigest), row_version = $rowVersion, modified_at = clock_timestamp() where singleton_key = 1"
    }
    update.flatMap {
      case 1       => DBIO.successful(())
      case changed => DBIO.failed(new IllegalStateException(s"Fixture cleanup transition affected $changed rows"))
    }
  }

  private val cleanupLedgerForUpdateRaw
    : DBIO[(Option[String], String, Option[String], String, String, String, String, String, String, String, String)] =
    sql"""select
             run_id::text,
             run_state,
             cleanup_idempotency_key_digest,
             oauth027_revocation_cleanup_state,
             oauth027_resource_cleanup_state,
             oauth028_revocation_cleanup_state,
             oauth028_resource_cleanup_state,
             oauth029_revocation_cleanup_state,
             oauth029_resource_cleanup_state,
             general_revocation_cleanup_state,
             general_resource_cleanup_state
           from oauth_e2e_fixture_run
           where singleton_key = 1
           for update"""
      .as[(Option[String], String, Option[String], String, String, String, String, String, String, String, String)]
      .head

  private def decodeCleanupLedger(raw: (Option[String], String, Option[String], String, String, String, String, String, String, String, String))
    : (Option[UUID], FixtureRunState, Option[String], Vector[CleanupStepState]) = {
    val states = raw.productIterator.drop(3).map(value => CleanupStepState.fromDatabase(value.asInstanceOf[String])).toVector
    (raw._1.map(UUID.fromString), FixtureRunState.fromDatabase(raw._2), raw._3.map(_.trim), states)
  }

  private def startCleanupStatement(runId: UUID, idempotencyKeyDigest: IdempotencyKeyDigest): DBIO[Int] = {
    val rawDigest = idempotencyKeyDigest.value
    sqlu"""update oauth_e2e_fixture_run
           set run_state = 'cleanup-required',
               cleanup_idempotency_key_digest = $rawDigest,
               row_version = row_version + 1,
               modified_at = clock_timestamp()
           where singleton_key = 1 and run_id = $runId and run_state = 'setup-complete'
             and cleanup_idempotency_key_digest is null"""
  }

  private def cleanupStateForRunForUpdateRaw(runId: UUID, step: CleanupStep): DBIO[Option[(String, Long, Option[String], String)]] = {
    val query = step match {
      case CleanupStep.OAuth027Revocation =>
        sql"select oauth027_revocation_cleanup_state, row_version, cleanup_idempotency_key_digest, run_state from oauth_e2e_fixture_run where singleton_key = 1 and run_id = $runId for update"
          .as[(String, Long, Option[String], String)]
      case CleanupStep.OAuth027Resources =>
        sql"select oauth027_resource_cleanup_state, row_version, cleanup_idempotency_key_digest, run_state from oauth_e2e_fixture_run where singleton_key = 1 and run_id = $runId for update"
          .as[(String, Long, Option[String], String)]
      case CleanupStep.OAuth028Revocation =>
        sql"select oauth028_revocation_cleanup_state, row_version, cleanup_idempotency_key_digest, run_state from oauth_e2e_fixture_run where singleton_key = 1 and run_id = $runId for update"
          .as[(String, Long, Option[String], String)]
      case CleanupStep.OAuth028Resources =>
        sql"select oauth028_resource_cleanup_state, row_version, cleanup_idempotency_key_digest, run_state from oauth_e2e_fixture_run where singleton_key = 1 and run_id = $runId for update"
          .as[(String, Long, Option[String], String)]
      case CleanupStep.OAuth029Revocation =>
        sql"select oauth029_revocation_cleanup_state, row_version, cleanup_idempotency_key_digest, run_state from oauth_e2e_fixture_run where singleton_key = 1 and run_id = $runId for update"
          .as[(String, Long, Option[String], String)]
      case CleanupStep.OAuth029Resources =>
        sql"select oauth029_resource_cleanup_state, row_version, cleanup_idempotency_key_digest, run_state from oauth_e2e_fixture_run where singleton_key = 1 and run_id = $runId for update"
          .as[(String, Long, Option[String], String)]
      case CleanupStep.GeneralRevocation =>
        sql"select general_revocation_cleanup_state, row_version, cleanup_idempotency_key_digest, run_state from oauth_e2e_fixture_run where singleton_key = 1 and run_id = $runId for update"
          .as[(String, Long, Option[String], String)]
      case CleanupStep.GeneralResources =>
        sql"select general_resource_cleanup_state, row_version, cleanup_idempotency_key_digest, run_state from oauth_e2e_fixture_run where singleton_key = 1 and run_id = $runId for update"
          .as[(String, Long, Option[String], String)]
    }
    query.headOption
  }

  private def updateCleanupStepStatement(
    step: CleanupStep,
    target: CleanupStepState,
    rowVersion: Long,
    idempotencyKeyDigest: IdempotencyKeyDigest
  ): DBIO[Int] = {
    val rawTarget = target.databaseValue
    val rawDigest = idempotencyKeyDigest.value
    step match {
      case CleanupStep.OAuth027Revocation =>
        sqlu"update oauth_e2e_fixture_run set oauth027_revocation_cleanup_state = $rawTarget, row_version = $rowVersion, modified_at = clock_timestamp() where singleton_key = 1 and cleanup_idempotency_key_digest = $rawDigest"
      case CleanupStep.OAuth027Resources =>
        sqlu"update oauth_e2e_fixture_run set oauth027_resource_cleanup_state = $rawTarget, row_version = $rowVersion, modified_at = clock_timestamp() where singleton_key = 1 and cleanup_idempotency_key_digest = $rawDigest"
      case CleanupStep.OAuth028Revocation =>
        sqlu"update oauth_e2e_fixture_run set oauth028_revocation_cleanup_state = $rawTarget, row_version = $rowVersion, modified_at = clock_timestamp() where singleton_key = 1 and cleanup_idempotency_key_digest = $rawDigest"
      case CleanupStep.OAuth028Resources =>
        sqlu"update oauth_e2e_fixture_run set oauth028_resource_cleanup_state = $rawTarget, row_version = $rowVersion, modified_at = clock_timestamp() where singleton_key = 1 and cleanup_idempotency_key_digest = $rawDigest"
      case CleanupStep.OAuth029Revocation =>
        sqlu"update oauth_e2e_fixture_run set oauth029_revocation_cleanup_state = $rawTarget, row_version = $rowVersion, modified_at = clock_timestamp() where singleton_key = 1 and cleanup_idempotency_key_digest = $rawDigest"
      case CleanupStep.OAuth029Resources =>
        sqlu"update oauth_e2e_fixture_run set oauth029_resource_cleanup_state = $rawTarget, row_version = $rowVersion, modified_at = clock_timestamp() where singleton_key = 1 and cleanup_idempotency_key_digest = $rawDigest"
      case CleanupStep.GeneralRevocation =>
        sqlu"update oauth_e2e_fixture_run set general_revocation_cleanup_state = $rawTarget, row_version = $rowVersion, modified_at = clock_timestamp() where singleton_key = 1 and cleanup_idempotency_key_digest = $rawDigest"
      case CleanupStep.GeneralResources =>
        sqlu"update oauth_e2e_fixture_run set general_resource_cleanup_state = $rawTarget, row_version = $rowVersion, modified_at = clock_timestamp() where singleton_key = 1 and cleanup_idempotency_key_digest = $rawDigest"
    }
  }

  private def ownershipForAliasRaw(runId: UUID, alias: FixtureAlias)
    : DBIO[Option[(String, String, String, String, String, String, String, String, String, String, String, String, String, String)]] = {
    val rawAlias = alias.databaseValue
    sql"""select
             run_id::text, fixture_alias, owner_user_id::text, owner_membership_id::text, organization_id::text,
             business_client_id::text, project_p1_id::text, project_p2_id::text,
             foreign_owner_user_id::text, foreign_owner_membership_id::text, foreign_organization_id::text,
             foreign_business_client_id::text, foreign_project_id::text, oauth_client_id::text
           from oauth_e2e_fixture_ownership
           where run_id = $runId and fixture_alias = $rawAlias
           for update"""
      .as[(String, String, String, String, String, String, String, String, String, String, String, String, String, String)]
      .headOption
  }

  private def completeCleanupStatement(runId: UUID, idempotencyKeyDigest: IdempotencyKeyDigest): DBIO[Int] = {
    val rawDigest = idempotencyKeyDigest.value
    sqlu"""update oauth_e2e_fixture_run
           set run_state = 'cleaned', row_version = row_version + 1, modified_at = clock_timestamp()
           where singleton_key = 1 and run_id = $runId and run_state = 'cleanup-required'
             and cleanup_idempotency_key_digest = $rawDigest
             and oauth027_revocation_cleanup_state = 'complete'
             and oauth027_resource_cleanup_state = 'complete'
             and oauth028_revocation_cleanup_state = 'complete'
             and oauth028_resource_cleanup_state = 'complete'
             and oauth029_revocation_cleanup_state = 'complete'
             and oauth029_resource_cleanup_state = 'complete'
             and general_revocation_cleanup_state = 'complete'
             and general_resource_cleanup_state = 'complete'"""
  }

  private def claimController(config: OAuthE2EFixtureConfig, controllerInstanceId: ControllerInstanceId): DBIO[FixtureInitializationResult] = {
    val rawControllerId = controllerInstanceId.value
    observedLease(forUpdate = true).flatMap {
      case Unclaimed =>
        validateUnclaimedObservation(config) >>
          sqlu"""update oauth_e2e_fixture_run
                 set controller_lease_id = $rawControllerId,
                     controller_lease_state = 'claimed',
                     row_version = row_version + 1,
                     modified_at = clock_timestamp()
                 where singleton_key = 1 and controller_lease_state = 'unclaimed' and controller_lease_id is null""".flatMap {
            case 1       => DBIO.successful(ControllerClaimed)
            case changed => DBIO.failed(new IllegalStateException(s"Fixture controller claim affected $changed rows"))
          }
      case Claimed(existing) if existing == controllerInstanceId => DBIO.successful(ControllerAlreadyClaimed)
      case Claimed(_)                                            => DBIO.successful(ControllerConflict)
      case Terminal(_)                                           => DBIO.successful(ControllerTerminal)
    }
  }

  private def validateUnclaimedObservation(config: OAuthE2EFixtureConfig): DBIO[Unit] =
    config.controllerLeaseObservationGuard match {
      case Some(guard) =>
        guard.validateObserved(ControllerLeaseObservation.ObservedUnclaimed) match {
          case Right(()) => DBIO.successful(())
          case Left(_)   => DBIO.failed(new IllegalStateException("Fixture controller observation guard rejected startup"))
        }
      case None => DBIO.failed(new IllegalStateException("Fixture controller observation guard is absent"))
    }

  private def observedLease(forUpdate: Boolean): DBIO[ControllerLeaseState] = {
    val query =
      if (forUpdate)
        sql"""select controller_lease_id::text, controller_lease_state
               from oauth_e2e_fixture_run
               where singleton_key = 1
               for update""".as[(Option[String], String)]
      else
        sql"""select controller_lease_id::text, controller_lease_state
               from oauth_e2e_fixture_run
               where singleton_key = 1""".as[(Option[String], String)]

    query.head.map { case (id, state) => ControllerLeaseState.fromDatabase(id, state) }
  }

  private def decodeLedger(
    raw: (
      Option[String],
      String,
      Int,
      Long,
      Option[String],
      String,
      Int,
      Option[String],
      Option[String],
      String,
      String,
      String,
      String,
      String,
      String,
      String,
      String
    )
  ): FixtureRunLedger = {
    val cleanupStates =
      CleanupStep.values.zip(raw.productIterator.drop(9).map(value => CleanupStepState.fromDatabase(value.asInstanceOf[String]))).toMap
    FixtureRunLedger(
      runId = raw._1.map(UUID.fromString),
      runState = FixtureRunState.fromDatabase(raw._2),
      offsetSeconds = raw._3,
      rowVersion = raw._4,
      controllerLease = ControllerLeaseState.fromDatabase(raw._5, raw._6),
      acceptedCommandCount = raw._7,
      advanceIdempotencyKeyDigest = raw._8.map(_.trim),
      cleanupIdempotencyKeyDigest = raw._9.map(_.trim),
      cleanupSteps = cleanupStates
    )
  }

  private def decodeOwnership(raw: (String, String, String, String, String, String, String, String, String, String, String, String, String, String))
    : FixtureOwnership =
    FixtureOwnership(
      runId = UUID.fromString(raw._1),
      alias = OAuthE2EFixtureDomain.FixtureAlias.fromDatabase(raw._2),
      ownerUserId = pl.iterators.epoca.user.domain.UserDomain.UserId(UUID.fromString(raw._3)),
      ownerMembershipId = pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationUserId(UUID.fromString(raw._4)),
      organizationId = pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId(UUID.fromString(raw._5)),
      businessClientId = pl.iterators.epoca.client.domain.ClientDomain.ClientId(UUID.fromString(raw._6)),
      projectP1Id = pl.iterators.epoca.project.domain.ProjectDomain.ProjectId(UUID.fromString(raw._7)),
      projectP2Id = pl.iterators.epoca.project.domain.ProjectDomain.ProjectId(UUID.fromString(raw._8)),
      foreignOwnerUserId = pl.iterators.epoca.user.domain.UserDomain.UserId(UUID.fromString(raw._9)),
      foreignOwnerMembershipId = pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationUserId(UUID.fromString(raw._10)),
      foreignOrganizationId = pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId(UUID.fromString(raw._11)),
      foreignBusinessClientId = pl.iterators.epoca.client.domain.ClientDomain.ClientId(UUID.fromString(raw._12)),
      foreignProjectId = pl.iterators.epoca.project.domain.ProjectDomain.ProjectId(UUID.fromString(raw._13)),
      oauthClientId = pl.iterators.epoca.oauth.domain.OAuthDomain.OAuthClientId(UUID.fromString(raw._14))
    )
}
