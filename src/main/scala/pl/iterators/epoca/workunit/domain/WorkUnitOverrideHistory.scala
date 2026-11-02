package pl.iterators.epoca.workunit.domain

import io.circe.Json
import pl.iterators.epoca.user.domain.UserDomain.{UserFullName, UserId}
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.WorkUnitId

import java.time.Instant
import java.util.UUID

final case class WorkUnitOverrideHistory(
  id: UUID,
  workUnitId: WorkUnitId,
  version: Long,
  operation: String,
  actorId: UserId,
  actorDisplayName: UserFullName,
  changedAt: Instant,
  provenance: String,
  before: Option[Json],
  after: Option[Json],
  legacyBefore: WorkUnitOverrideLegacySnapshot = WorkUnitOverrideLegacySnapshot(None, None, None, None, None),
  legacyAfter: WorkUnitOverrideLegacySnapshot = WorkUnitOverrideLegacySnapshot(None, None, None, None, None),
  parentCorrectionId: Option[UUID] = None,
  revertsEventId: Option[UUID] = None)
