package pl.iterators.epoca.stats.domain

import pl.iterators.epoca.client.domain.ClientDomain.ClientId
import pl.iterators.epoca.project.domain.ProjectDomain.ProjectId
import pl.iterators.epoca.stats.domain.StatsDomain.{StatsYear, StatsYearMonth, WorkUnitPivotTableKey}
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.{WorkUnitDate, WorkUnitEntryType, WorkUnitTaskId}
import pl.iterators.epoca.workunit.repositories.WorkUnitRepository.WorkUnitWithClientId

case class WorkUnitPivotTableProcessor(
  query: Seq[WorkUnitWithClientId],
  year: Option[StatsYear] = None,
  monthYear: Option[StatsYearMonth] = None,
  date: Option[WorkUnitDate] = None,
  userId: Option[UserId] = None,
  projectId: Option[ProjectId] = None,
  clientId: Option[ClientId] = None,
  entryType: Option[WorkUnitEntryType] = None,
  taskId: Option[Option[WorkUnitTaskId]] = None)

object WorkUnitPivotTableProcessor {
  def buildFrom(processor: WorkUnitPivotTableProcessor, key: WorkUnitPivotTableKey): Seq[WorkUnitPivotTableProcessor] =
    key match {
      case WorkUnitPivotTableKey.UserKey =>
        processor.query
          .groupBy(_.workUnit.userId)
          .map { case (userId, entries) =>
            processor.copy(query = entries, userId = Some(userId))
          }
          .toSeq
      case WorkUnitPivotTableKey.ProjectKey =>
        processor.query
          .groupBy(_.workUnit.effectiveProjectId)
          .map { case (projectId, entries) =>
            processor.copy(query = entries, projectId = Some(projectId))
          }
          .toSeq
      case WorkUnitPivotTableKey.ClientKey =>
        processor.query
          .groupBy(_.clientId)
          .map { case (clientId, entries) =>
            processor.copy(query = entries, clientId = Some(clientId))
          }
          .toSeq
      case WorkUnitPivotTableKey.YearKey =>
        processor.query
          .groupBy(_.workUnit.date.getYear)
          .map { case (year, entries) =>
            processor.copy(query = entries, year = Some(StatsYear(year)))
          }
          .toSeq
      case WorkUnitPivotTableKey.MonthYearKey =>
        processor.query
          .groupBy(entry => StatsYearMonth.from(entry.workUnit.date))
          .map { case (yearMonth, entries) =>
            processor.copy(query = entries, monthYear = Some(yearMonth))
          }
          .toSeq
      case WorkUnitPivotTableKey.DateKey =>
        processor.query
          .groupBy(_.workUnit.date)
          .map { case (date, entries) =>
            processor.copy(query = entries, date = Some(date))
          }
          .toSeq
      case WorkUnitPivotTableKey.EntryTypeKey =>
        processor.query
          .groupBy(_.workUnit.entryType)
          .map { case (entryType, entries) =>
            processor.copy(query = entries, entryType = Some(entryType))
          }
          .toSeq
      case WorkUnitPivotTableKey.TaskKey =>
        processor.query
          .groupBy(_.workUnit.effectiveTaskId)
          .map { case (taskId, entries) =>
            processor.copy(query = entries, taskId = Some(taskId))
          }
          .toSeq
    }

}
