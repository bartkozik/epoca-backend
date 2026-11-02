package pl.iterators.epoca.stats.domain

import io.scalaland.chimney.dsl._
import pl.iterators.epoca.client.domain.ClientDomain.ClientId
import pl.iterators.epoca.project.domain.ProjectDomain.ProjectId
import pl.iterators.epoca.stats.domain.StatsDomain._
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.{WorkUnitDate, WorkUnitEntryType, WorkUnitTaskId}

import java.time.Duration

case class WorkUnitPivotTableRow(
  year: Option[StatsYear] = None,
  monthYear: Option[StatsYearMonth] = None,
  date: Option[WorkUnitDate] = None,
  userId: Option[UserId] = None,
  projectId: Option[ProjectId] = None,
  clientId: Option[ClientId] = None,
  entryType: Option[WorkUnitEntryType] = None,
  taskId: Option[Option[WorkUnitTaskId]] = None,
  entriesCount: Option[StatsEntriesCount] = None,
  durationSum: Option[Duration] = None,
  durationAvg: Option[Duration] = None)

object WorkUnitPivotTableRow {
  def from(values: Seq[WorkUnitPivotTableValue], processor: WorkUnitPivotTableProcessor): WorkUnitPivotTableRow = {
    val worklogs = processor.query.filter(_.workUnit.entryType == WorkUnitEntryType.Worklog)

    lazy val entriesCount: StatsEntriesCount = StatsEntriesCount(processor.query.length.toLong)
    lazy val durationSum: Duration = worklogs
      .foldLeft(Duration.ZERO) { case (acc, entry) =>
        acc.plus(entry.workUnit.duration)
      }
    lazy val durationAvg: Duration = if (worklogs.isEmpty) Duration.ZERO else durationSum.dividedBy(worklogs.length.toLong)
    processor
      .into[WorkUnitPivotTableRow]
      .withFieldConst(_.entriesCount, Option.when(values.contains(WorkUnitPivotTableValue.EntriesCount))(entriesCount))
      .withFieldConst(_.durationSum, Option.when(values.contains(WorkUnitPivotTableValue.DurationSum))(durationSum))
      .withFieldConst(_.durationAvg, Option.when(values.contains(WorkUnitPivotTableValue.DurationAvg))(durationAvg))
      .transform
  }
}
