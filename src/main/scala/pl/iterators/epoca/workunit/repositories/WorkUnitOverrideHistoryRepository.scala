package pl.iterators.epoca.workunit.repositories

import pl.iterators.epoca.utils.db.PageSizeAndNumber
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.WorkUnitId
import pl.iterators.epoca.workunit.domain.WorkUnitOverrideHistory
import slick.dbio.DBIO

import java.util.UUID

trait WorkUnitOverrideHistoryRepository {
  def insert(value: WorkUnitOverrideHistory): DBIO[WorkUnitOverrideHistory]
  def find(
    workUnitId: WorkUnitId,
    id: UUID,
    upToVersion: Long
  ): DBIO[Option[WorkUnitOverrideHistory]]
  def reversals(
    workUnitId: WorkUnitId,
    eventIds: Seq[UUID],
    upToVersion: Long
  ): DBIO[Seq[WorkUnitOverrideHistory]]
  def list(
    workUnitId: WorkUnitId,
    upToVersion: Long,
    page: Option[PageSizeAndNumber]
  ): DBIO[Seq[WorkUnitOverrideHistory]]
  def count(workUnitId: WorkUnitId, upToVersion: Long): DBIO[Int]
}
