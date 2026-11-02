package pl.iterators.epoca.oauth.fixture

import pl.iterators.epoca.oauth.fixture.OAuthE2EFixtureDomain.{
  AdvanceResult,
  CleanupCompletionResult,
  CleanupStartResult,
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
  FixtureStatusResult,
  IdempotencyKeyDigest,
  NonCleanupCommandAdmissionResult,
  RevokeOAuth029MarkerResult,
  RevokeOAuth029PersistenceResult
}
import pl.iterators.epoca.oauth.time.OAuthAuthoritativeTime
import pl.iterators.epoca.utils.db.BaseRepository.driver.api.DBIO

import java.time.Instant
import java.util.UUID

trait OAuthE2EFixtureRepository {
  def initialize(config: OAuthE2EFixtureConfig, controllerInstanceId: Option[ControllerInstanceId]): DBIO[FixtureInitializationResult]
  def ledger: DBIO[Option[FixtureRunLedger]]
  def ownershipRows: DBIO[Seq[FixtureOwnership]]
  def markControllerLost(controllerInstanceId: ControllerInstanceId): DBIO[ControllerLossResult]
  def admitNonCleanupCommand[A](category: FixtureAuditCommandCategory, idempotencyKeyDigest: IdempotencyKeyDigest)(onApplied: (Long, Int) => DBIO[A])
    : DBIO[NonCleanupCommandAdmissionResult[A]]
  private[fixture] def admitNonCleanupCommandProgram[A](
    transaction: OAuthE2EFixtureStatementBoundary,
    category: FixtureAuditCommandCategory,
    idempotencyKeyDigest: IdempotencyKeyDigest
  )(
    onApplied: (Long, Int) => OAuthE2EFixtureCommandProgram[A]
  ): OAuthE2EFixtureCommandProgram[NonCleanupCommandAdmissionResult[A]]
  private[fixture] def setupProgram[A](
    transaction: OAuthE2EFixtureStatementBoundary,
    runId: UUID,
    idempotencyKeyDigest: IdempotencyKeyDigest,
    attemptMatches: Boolean,
    ownership: Vector[FixtureOwnership]
  )(
    onApplied: => OAuthE2EFixtureCommandProgram[A]
  ): OAuthE2EFixtureCommandProgram[FixtureSetupPersistenceResult[A]]
  def advance300(idempotencyKeyDigest: IdempotencyKeyDigest): DBIO[AdvanceResult]
  private[fixture] def advance300Program(transaction: OAuthE2EFixtureStatementBoundary, idempotencyKeyDigest: IdempotencyKeyDigest)
    : OAuthE2EFixtureCommandProgram[AdvanceResult]
  private[fixture] def advance300ForRunProgram(
    transaction: OAuthE2EFixtureStatementBoundary,
    runId: UUID,
    idempotencyKeyDigest: IdempotencyKeyDigest
  ): OAuthE2EFixtureCommandProgram[AdvanceResult] = {
    val _ = runId
    advance300Program(transaction, idempotencyKeyDigest)
  }
  private[fixture] def beginRevokeOAuth029(runId: UUID, idempotencyKeyDigest: IdempotencyKeyDigest)(revokeOwnedClient: FixtureOwnership => DBIO[Unit])
    : DBIO[RevokeOAuth029PersistenceResult] = {
    val _ = (runId, idempotencyKeyDigest, revokeOwnedClient)
    DBIO.failed(new UnsupportedOperationException("Fixture oauth029 revoke persistence is unavailable"))
  }
  private[fixture] def completeRevokeOAuth029(runId: UUID, idempotencyKeyDigest: IdempotencyKeyDigest): DBIO[RevokeOAuth029MarkerResult] = {
    val _ = (runId, idempotencyKeyDigest)
    DBIO.failed(new UnsupportedOperationException("Fixture oauth029 revoke marker is unavailable"))
  }
  private[fixture] def reconcileStartedRevokeOAuth029ForCleanup(runId: UUID): DBIO[RevokeOAuth029MarkerResult] = {
    val _ = runId
    DBIO.failed(new UnsupportedOperationException("Fixture oauth029 cleanup reconciliation is unavailable"))
  }
  def transitionCleanupStep(
    step: CleanupStep,
    expected: CleanupStepState,
    target: CleanupStepState,
    idempotencyKeyDigest: IdempotencyKeyDigest
  ): DBIO[CleanupTransitionResult]
  def beginCleanup(runId: UUID, idempotencyKeyDigest: IdempotencyKeyDigest): DBIO[CleanupStartResult]
  private[fixture] def beginCleanupProgram(
    transaction: OAuthE2EFixtureStatementBoundary,
    runId: UUID,
    idempotencyKeyDigest: IdempotencyKeyDigest
  ): OAuthE2EFixtureCommandProgram[CleanupStartResult]
  private[fixture] def cleanupOwnershipForRunProgram(
    transaction: OAuthE2EFixtureStatementBoundary,
    runId: UUID,
    alias: pl.iterators.epoca.oauth.fixture.OAuthE2EFixtureDomain.FixtureAlias,
    idempotencyKeyDigest: IdempotencyKeyDigest
  ): OAuthE2EFixtureCommandProgram[FixtureOwnership]
  private[fixture] def transitionCleanupStepForRunProgram(
    transaction: OAuthE2EFixtureStatementBoundary,
    runId: UUID,
    step: CleanupStep,
    expected: CleanupStepState,
    target: CleanupStepState,
    idempotencyKeyDigest: IdempotencyKeyDigest
  ): OAuthE2EFixtureCommandProgram[CleanupTransitionResult]
  def completeCleanup(runId: UUID, idempotencyKeyDigest: IdempotencyKeyDigest): DBIO[CleanupCompletionResult]
  private[fixture] def completeCleanupProgram(
    transaction: OAuthE2EFixtureStatementBoundary,
    runId: UUID,
    idempotencyKeyDigest: IdempotencyKeyDigest
  ): OAuthE2EFixtureCommandProgram[CleanupCompletionResult]
  private[fixture] def statusForRunProgram(
    transaction: OAuthE2EFixtureStatementBoundary,
    runId: UUID,
    idempotencyKeyDigest: IdempotencyKeyDigest
  ): OAuthE2EFixtureCommandProgram[FixtureStatusResult]
  def auditRows: DBIO[Seq[FixtureAuditRow]]
  private[fixture] def authoritativeNowEpochSeconds: DBIO[BigDecimal] =
    DBIO.failed(new UnsupportedOperationException("Primitive fixture authoritative time is unavailable"))
  def authoritativeNow: DBIO[Instant]
}

final class FixtureOAuthAuthoritativeTime(repository: OAuthE2EFixtureRepository) extends OAuthAuthoritativeTime {
  override def now: DBIO[Instant] = repository.authoritativeNow
}
