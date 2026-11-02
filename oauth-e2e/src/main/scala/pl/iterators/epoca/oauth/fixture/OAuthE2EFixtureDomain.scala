package pl.iterators.epoca.oauth.fixture

import pl.iterators.epoca.client.domain.ClientDomain.ClientId
import pl.iterators.epoca.oauth.domain.OAuthDomain.OAuthClientId
import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationId, OrganizationUserId}
import pl.iterators.epoca.project.domain.ProjectDomain.ProjectId
import pl.iterators.epoca.user.domain.UserDomain.UserId

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

object OAuthE2EFixtureDomain {
  final case class ControllerInstanceId(value: UUID) extends AnyVal

  sealed trait FixtureAlias {
    private[fixture] def databaseValue: String
  }
  object FixtureAlias {
    case object OAuth027 extends FixtureAlias {
      override private[fixture] val databaseValue: String = "oauth027"
    }
    case object OAuth028 extends FixtureAlias {
      override private[fixture] val databaseValue: String = "oauth028"
    }
    case object OAuth029 extends FixtureAlias {
      override private[fixture] val databaseValue: String = "oauth029"
    }
    case object General extends FixtureAlias {
      override private[fixture] val databaseValue: String = "general"
    }

    val values: Vector[FixtureAlias] = Vector(OAuth027, OAuth028, OAuth029, General)

    private[fixture] def fromDatabase(value: String): FixtureAlias =
      values.find(_.databaseValue == value).getOrElse(throw new IllegalStateException("Fixture alias failed closed decoding"))
  }

  final case class FixtureOwnership(
    runId: UUID,
    alias: FixtureAlias,
    ownerUserId: UserId,
    ownerMembershipId: OrganizationUserId,
    organizationId: OrganizationId,
    businessClientId: ClientId,
    projectP1Id: ProjectId,
    projectP2Id: ProjectId,
    foreignOwnerUserId: UserId,
    foreignOwnerMembershipId: OrganizationUserId,
    foreignOrganizationId: OrganizationId,
    foreignBusinessClientId: ClientId,
    foreignProjectId: ProjectId,
    oauthClientId: OAuthClientId)

  sealed trait FixtureSetupPersistenceResult[+A]
  object FixtureSetupPersistenceResult {
    final case class Applied[A](value: A) extends FixtureSetupPersistenceResult[A]
    case object CredentialsUnavailable    extends FixtureSetupPersistenceResult[Nothing]
    case object Conflict                  extends FixtureSetupPersistenceResult[Nothing]
    case object CommandBudgetExhausted    extends FixtureSetupPersistenceResult[Nothing]
  }

  sealed trait FixtureRunState {
    private[fixture] def databaseValue: String
  }
  object FixtureRunState {
    case object AwaitingSetup extends FixtureRunState {
      override private[fixture] val databaseValue: String = "awaiting-setup"
    }
    case object SetupComplete extends FixtureRunState {
      override private[fixture] val databaseValue: String = "setup-complete"
    }
    case object CleanupRequired extends FixtureRunState {
      override private[fixture] val databaseValue: String = "cleanup-required"
    }
    case object Cleaned extends FixtureRunState {
      override private[fixture] val databaseValue: String = "cleaned"
    }

    private[fixture] def fromDatabase(value: String): FixtureRunState =
      value match {
        case AwaitingSetup.databaseValue   => AwaitingSetup
        case SetupComplete.databaseValue   => SetupComplete
        case CleanupRequired.databaseValue => CleanupRequired
        case Cleaned.databaseValue         => Cleaned
        case _                             => throw new IllegalStateException("Fixture run state failed closed decoding")
      }
  }

  sealed trait ControllerLeaseState
  object ControllerLeaseState {
    case object Unclaimed                                         extends ControllerLeaseState
    final case class Claimed(controllerId: ControllerInstanceId)  extends ControllerLeaseState
    final case class Terminal(controllerId: ControllerInstanceId) extends ControllerLeaseState

    private[fixture] def fromDatabase(id: Option[String], state: String): ControllerLeaseState =
      (id.map(value => ControllerInstanceId(UUID.fromString(value))), state) match {
        case (None, "unclaimed")              => Unclaimed
        case (Some(controllerId), "claimed")  => Claimed(controllerId)
        case (Some(controllerId), "terminal") => Terminal(controllerId)
        case _                                => throw new IllegalStateException("Fixture controller lease failed closed decoding")
      }
  }

  sealed trait FixtureInitializationResult
  object FixtureInitializationResult {
    case object Disabled                 extends FixtureInitializationResult
    case object TimeOnlyReady            extends FixtureInitializationResult
    case object ControllerClaimed        extends FixtureInitializationResult
    case object ControllerAlreadyClaimed extends FixtureInitializationResult
    case object ControllerConflict       extends FixtureInitializationResult
    case object ControllerTerminal       extends FixtureInitializationResult
  }

  sealed trait ControllerLossResult
  object ControllerLossResult {
    case object MarkedTerminal  extends ControllerLossResult
    case object AlreadyTerminal extends ControllerLossResult
    case object LeaseMismatch   extends ControllerLossResult
  }

  final case class IdempotencyKeyDigest private (value: String) extends AnyVal
  object IdempotencyKeyDigest {
    def sha256(raw: String): IdempotencyKeyDigest = {
      val digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(UTF_8))
      IdempotencyKeyDigest(digest.iterator.map(byte => f"${byte & 0xff}%02x").mkString)
    }

    private[fixture] def fromDatabase(value: String): IdempotencyKeyDigest = {
      val normalized = value.trim
      if (!normalized.matches("[0-9a-f]{64}"))
        throw new IllegalStateException("Fixture idempotency digest failed closed decoding")
      IdempotencyKeyDigest(normalized)
    }
  }

  sealed trait AdvanceResult
  object AdvanceResult {
    final case class Applied(
      offsetBeforeSeconds: Int,
      offsetAfterSeconds: Int,
      rowVersion: Long,
      acceptedCommandCount: Int)
        extends AdvanceResult
    final case class Idempotent(
      offsetSeconds: Int,
      rowVersion: Long,
      acceptedCommandCount: Int)
        extends AdvanceResult
    final case class Conflict(
      offsetSeconds: Int,
      rowVersion: Long,
      acceptedCommandCount: Int)
        extends AdvanceResult
    final case class CommandBudgetExhausted(
      offsetSeconds: Int,
      rowVersion: Long,
      acceptedCommandCount: Int)
        extends AdvanceResult
  }

  sealed trait RevokeOAuth029PersistenceResult
  object RevokeOAuth029PersistenceResult {
    final case class Applied(ownership: FixtureOwnership)                extends RevokeOAuth029PersistenceResult
    final case class ReconciliationRequired(ownership: FixtureOwnership) extends RevokeOAuth029PersistenceResult
    case object Idempotent                                               extends RevokeOAuth029PersistenceResult
    case object Conflict                                                 extends RevokeOAuth029PersistenceResult
    case object CommandBudgetExhausted                                   extends RevokeOAuth029PersistenceResult
  }

  sealed trait RevokeOAuth029MarkerResult
  object RevokeOAuth029MarkerResult {
    case object Applied    extends RevokeOAuth029MarkerResult
    case object Idempotent extends RevokeOAuth029MarkerResult
    case object Conflict   extends RevokeOAuth029MarkerResult
  }

  sealed trait NonCleanupCommandAdmissionResult[+A]
  object NonCleanupCommandAdmissionResult {
    final case class Applied[A](
      value: A,
      rowVersion: Long,
      acceptedCommandCount: Int)
        extends NonCleanupCommandAdmissionResult[A]
    final case class Idempotent(
      offsetSeconds: Int,
      rowVersion: Long,
      acceptedCommandCount: Int)
        extends NonCleanupCommandAdmissionResult[Nothing]
    final case class Conflict(
      offsetSeconds: Int,
      rowVersion: Long,
      acceptedCommandCount: Int)
        extends NonCleanupCommandAdmissionResult[Nothing]
    final case class BudgetExhausted(
      offsetSeconds: Int,
      rowVersion: Long,
      acceptedCommandCount: Int)
        extends NonCleanupCommandAdmissionResult[Nothing]
  }

  sealed trait CleanupTransitionResult
  object CleanupTransitionResult {
    final case class Applied(state: CleanupStepState, rowVersion: Long)    extends CleanupTransitionResult
    final case class Idempotent(state: CleanupStepState, rowVersion: Long) extends CleanupTransitionResult
    final case class Conflict(state: CleanupStepState, rowVersion: Long)   extends CleanupTransitionResult
  }

  sealed trait CleanupStartResult
  object CleanupStartResult {
    case object Applied        extends CleanupStartResult
    case object Resumable      extends CleanupStartResult
    case object AlreadyCleaned extends CleanupStartResult
    case object Conflict       extends CleanupStartResult
  }

  sealed trait CleanupCompletionResult
  object CleanupCompletionResult {
    case object Cleaned        extends CleanupCompletionResult
    case object AlreadyCleaned extends CleanupCompletionResult
    case object Conflict       extends CleanupCompletionResult
  }

  sealed trait FixtureStatusResult
  object FixtureStatusResult {
    final case class Snapshot(
      runState: FixtureRunState,
      completedCleanupSteps: Int,
      totalCleanupSteps: Int,
      acceptedCommandCount: Int)
        extends FixtureStatusResult
    case object Conflict               extends FixtureStatusResult
    case object CommandBudgetExhausted extends FixtureStatusResult
  }

  sealed trait FixtureAuditCommandCategory {
    private[fixture] def databaseValue: String
  }
  object FixtureAuditCommandCategory {
    case object Setup extends FixtureAuditCommandCategory {
      override private[fixture] val databaseValue: String = "setup"
    }
    case object Advance300Seconds extends FixtureAuditCommandCategory {
      override private[fixture] val databaseValue: String = "advance-300-seconds"
    }
    case object RevokeOAuth029 extends FixtureAuditCommandCategory {
      override private[fixture] val databaseValue: String = "revoke-oauth029"
    }
    case object Status extends FixtureAuditCommandCategory {
      override private[fixture] val databaseValue: String = "status"
    }
    case object CleanupStepTransition extends FixtureAuditCommandCategory {
      override private[fixture] val databaseValue: String = "cleanup-step"
    }

    private[fixture] val nonCleanupValues: Set[FixtureAuditCommandCategory] = Set(Setup, Advance300Seconds, RevokeOAuth029, Status)

    private[fixture] def isSingleUse(value: FixtureAuditCommandCategory): Boolean =
      value == Setup || value == Advance300Seconds || value == RevokeOAuth029

    private[fixture] def fromDatabase(value: String): FixtureAuditCommandCategory =
      value match {
        case Setup.databaseValue                 => Setup
        case Advance300Seconds.databaseValue     => Advance300Seconds
        case RevokeOAuth029.databaseValue        => RevokeOAuth029
        case Status.databaseValue                => Status
        case CleanupStepTransition.databaseValue => CleanupStepTransition
        case _                                   => throw new IllegalStateException("Fixture audit command category failed closed decoding")
      }
  }

  sealed trait FixtureAuditOutcome {
    private[fixture] def databaseValue: String
  }
  object FixtureAuditOutcome {
    case object Applied extends FixtureAuditOutcome {
      override private[fixture] val databaseValue: String = "applied"
    }
    case object Idempotent extends FixtureAuditOutcome {
      override private[fixture] val databaseValue: String = "idempotent"
    }
    case object Conflict extends FixtureAuditOutcome {
      override private[fixture] val databaseValue: String = "conflict"
    }
    case object Failed extends FixtureAuditOutcome {
      override private[fixture] val databaseValue: String = "failed"
    }

    private[fixture] def fromDatabase(value: String): FixtureAuditOutcome =
      value match {
        case Applied.databaseValue    => Applied
        case Idempotent.databaseValue => Idempotent
        case Conflict.databaseValue   => Conflict
        case Failed.databaseValue     => Failed
        case _                        => throw new IllegalStateException("Fixture audit outcome failed closed decoding")
      }
  }

  final case class FixtureAuditRow(
    sequence: Long,
    commandCategory: FixtureAuditCommandCategory,
    idempotencyKeyDigest: IdempotencyKeyDigest,
    outcome: FixtureAuditOutcome,
    authoritativeAt: Instant)

  sealed trait CleanupStep {
    private[fixture] def columnName: String
  }
  object CleanupStep {
    case object OAuth027Revocation extends CleanupStep {
      override private[fixture] val columnName: String = "oauth027_revocation_cleanup_state"
    }
    case object OAuth027Resources extends CleanupStep {
      override private[fixture] val columnName: String = "oauth027_resource_cleanup_state"
    }
    case object OAuth028Revocation extends CleanupStep {
      override private[fixture] val columnName: String = "oauth028_revocation_cleanup_state"
    }
    case object OAuth028Resources extends CleanupStep {
      override private[fixture] val columnName: String = "oauth028_resource_cleanup_state"
    }
    case object OAuth029Revocation extends CleanupStep {
      override private[fixture] val columnName: String = "oauth029_revocation_cleanup_state"
    }
    case object OAuth029Resources extends CleanupStep {
      override private[fixture] val columnName: String = "oauth029_resource_cleanup_state"
    }
    case object GeneralRevocation extends CleanupStep {
      override private[fixture] val columnName: String = "general_revocation_cleanup_state"
    }
    case object GeneralResources extends CleanupStep {
      override private[fixture] val columnName: String = "general_resource_cleanup_state"
    }

    val values: Vector[CleanupStep] = Vector(
      OAuth027Revocation,
      OAuth027Resources,
      OAuth028Revocation,
      OAuth028Resources,
      OAuth029Revocation,
      OAuth029Resources,
      GeneralRevocation,
      GeneralResources
    )
  }

  sealed trait CleanupStepState {
    private[fixture] def databaseValue: String
  }
  object CleanupStepState {
    case object Pending extends CleanupStepState {
      override private[fixture] val databaseValue: String = "pending"
    }
    case object InProgress extends CleanupStepState {
      override private[fixture] val databaseValue: String = "in-progress"
    }
    case object Complete extends CleanupStepState {
      override private[fixture] val databaseValue: String = "complete"
    }

    private[fixture] def fromDatabase(value: String): CleanupStepState =
      value match {
        case Pending.databaseValue    => Pending
        case InProgress.databaseValue => InProgress
        case Complete.databaseValue   => Complete
        case _                        => throw new IllegalStateException("Fixture cleanup step failed closed decoding")
      }
  }

  final case class FixtureRunLedger(
    runId: Option[UUID],
    runState: FixtureRunState,
    offsetSeconds: Int,
    rowVersion: Long,
    controllerLease: ControllerLeaseState,
    acceptedCommandCount: Int,
    advanceIdempotencyKeyDigest: Option[String],
    cleanupIdempotencyKeyDigest: Option[String],
    cleanupSteps: Map[CleanupStep, CleanupStepState])
}
