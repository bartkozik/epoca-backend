package pl.iterators.epoca.workunit.repositories

import pl.iterators.epoca.utils.db.BaseRepository.driver.api._
import pl.iterators.epoca.utils.db.PageSizeAndNumber
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.WorkUnitId
import pl.iterators.epoca.workunit.domain.WorkUnitOverrideHistory
import pl.iterators.epoca.workunit.repositories.tables.WorkUnitOverrideHistoryTable

import java.util.UUID

class WorkUnitOverrideHistoryRepositoryImpl extends WorkUnitOverrideHistoryRepository {
  private val table = TableQuery[WorkUnitOverrideHistoryTable]
  override def insert(value: WorkUnitOverrideHistory): DBIO[WorkUnitOverrideHistory] =
    (table += value).andThen(DBIO.successful(value))
  override def find(
    workUnitId: WorkUnitId,
    id: UUID,
    upToVersion: Long
  ): DBIO[Option[WorkUnitOverrideHistory]] =
    table.filter(row => row.workUnitId === workUnitId && row.id === id && row.version <= upToVersion).result.headOption
  override def reversals(
    workUnitId: WorkUnitId,
    eventIds: Seq[UUID],
    upToVersion: Long
  ): DBIO[Seq[WorkUnitOverrideHistory]] =
    if (eventIds.isEmpty) DBIO.successful(Seq.empty)
    else table.filter(row => row.workUnitId === workUnitId && row.version <= upToVersion && (row.revertsEventId inSet eventIds)).result
  override def list(
    workUnitId: WorkUnitId,
    upToVersion: Long,
    page: Option[PageSizeAndNumber]
  ): DBIO[Seq[WorkUnitOverrideHistory]] = {
    val ordered = table.filter(row => row.workUnitId === workUnitId && row.version <= upToVersion).sortBy(_.version.desc)
    page.fold(ordered)(p => ordered.drop(p.offset).take(p.size)).result
  }
  override def count(workUnitId: WorkUnitId, upToVersion: Long): DBIO[Int] =
    table.filter(row => row.workUnitId === workUnitId && row.version <= upToVersion).length.result
}
