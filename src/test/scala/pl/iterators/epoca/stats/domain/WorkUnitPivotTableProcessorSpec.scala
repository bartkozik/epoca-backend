package pl.iterators.epoca.stats.domain

import org.specs2.mutable.SpecificationLike
import pl.iterators.epoca.client.domain.ClientDomain.ClientId
import pl.iterators.epoca.project.domain.ProjectDomain.ProjectId
import pl.iterators.epoca.stats.domain.StatsDomain._
import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.utils.Generators
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.{WorkUnitDate, WorkUnitDuration, WorkUnitEntryType, WorkUnitId, WorkUnitTaskId}
import pl.iterators.epoca.workunit.domain.{WorkUnit, WorkUnitOverrideState}
import pl.iterators.epoca.workunit.repositories.WorkUnitRepository.WorkUnitWithClientId

import java.time.Duration
import java.util.UUID

class WorkUnitPivotTableProcessorSpec extends SpecificationLike with Generators {
  lazy val workUnitGenerator: AllGenerators[WorkUnit] = allGenerators[WorkUnit]

  private def worklog(duration: Duration = Duration.ofHours(1)): WorkUnit =
    workUnitGenerator.normal.generate.copy(duration = WorkUnitDuration(duration), entryType = WorkUnitEntryType.Worklog)

  private def entry(workUnit: WorkUnit, clientId: ClientId = ClientId(UUID.randomUUID())): WorkUnitWithClientId =
    WorkUnitWithClientId(workUnit, clientId)

  private def testCase(
    keys: Seq[WorkUnitPivotTableKey],
    values: Seq[WorkUnitPivotTableValue],
    workUnits: Seq[WorkUnitWithClientId],
    pivotRows: Seq[WorkUnitPivotTableRow]
  ) = {
    val initialProcessors = Seq(WorkUnitPivotTableProcessor(workUnits))
    val result = keys.distinct
      .foldLeft(initialProcessors) { case (builders, key) =>
        builders.flatMap(WorkUnitPivotTableProcessor.buildFrom(_, key))
      }
      .map(WorkUnitPivotTableRow.from(values, _))

    result should containTheSameElementsAs(pivotRows)
  }

  "WorkUnitPivotTableProcessor" should {
    "group manager overrides by effective project and explicitly cleared Task ID" in {
      val source    = worklog().copy(taskId = Some(WorkUnitTaskId("SOURCE-1")))
      val target    = ProjectId(UUID.randomUUID())
      val corrected = source.copy(overrideState = WorkUnitOverrideState(projectId = Some(target), taskIdOverrideSet = true))
      val units     = Seq(entry(corrected))
      val rows      = Seq(WorkUnitPivotTableRow(projectId = Some(target), taskId = Some(None), entriesCount = Some(StatsEntriesCount(1))))

      testCase(Seq(WorkUnitPivotTableKey.ProjectKey, WorkUnitPivotTableKey.TaskKey), Seq(WorkUnitPivotTableValue.EntriesCount), units, rows)
    }

    "empty input" in {
      testCase(Seq(), Seq(), Seq.empty[WorkUnitWithClientId], Seq(WorkUnitPivotTableRow()))
    }
    "ProjectKey" in {
      val first  = worklog()
      val second = first.copy(projectId = worklog().projectId)
      val third  = worklog().copy(projectId = second.projectId)

      val units = Seq(entry(first), entry(second), entry(third))
      val rows = Seq(
        WorkUnitPivotTableRow(entriesCount = Some(StatsEntriesCount(1)), projectId = Some(first.projectId)),
        WorkUnitPivotTableRow(entriesCount = Some(StatsEntriesCount(2)), projectId = Some(second.projectId))
      )

      testCase(Seq(WorkUnitPivotTableKey.ProjectKey), Seq(WorkUnitPivotTableValue.EntriesCount), units, rows)
    }
    "ClientKey" in {
      val first        = worklog()
      val second       = worklog()
      val third        = worklog()
      val firstClient  = ClientId(UUID.randomUUID())
      val secondClient = ClientId(UUID.randomUUID())

      val units = Seq(entry(first, firstClient), entry(second, secondClient), entry(third, secondClient))
      val rows = Seq(
        WorkUnitPivotTableRow(entriesCount = Some(StatsEntriesCount(1)), clientId = Some(firstClient)),
        WorkUnitPivotTableRow(entriesCount = Some(StatsEntriesCount(2)), clientId = Some(secondClient))
      )

      testCase(Seq(WorkUnitPivotTableKey.ClientKey), Seq(WorkUnitPivotTableValue.EntriesCount), units, rows)
    }
    "TaskKey" in {
      val first  = worklog().copy(taskId = Some(WorkUnitTaskId("CT-TASK-1")))
      val second = worklog().copy(taskId = first.taskId)
      val third  = worklog().copy(taskId = None)

      val units = Seq(entry(first), entry(second), entry(third))
      val rows = Seq(
        WorkUnitPivotTableRow(entriesCount = Some(StatsEntriesCount(2)), taskId = Some(first.taskId)),
        WorkUnitPivotTableRow(entriesCount = Some(StatsEntriesCount(1)), taskId = Some(None))
      )

      testCase(Seq(WorkUnitPivotTableKey.TaskKey), Seq(WorkUnitPivotTableValue.EntriesCount), units, rows)
    }
    "UserKey" in {
      val first  = worklog()
      val second = first.copy(userId = worklog().userId)
      val third  = worklog().copy(userId = second.userId)

      val units = Seq(entry(first), entry(second), entry(third))
      val rows = Seq(
        WorkUnitPivotTableRow(entriesCount = Some(StatsEntriesCount(1)), userId = Some(first.userId)),
        WorkUnitPivotTableRow(entriesCount = Some(StatsEntriesCount(2)), userId = Some(second.userId))
      )

      testCase(Seq(WorkUnitPivotTableKey.UserKey), Seq(WorkUnitPivotTableValue.EntriesCount), units, rows)
    }
    "YearKey" in {
      val first  = worklog()
      val second = first.copy(date = WorkUnitDate(first.date.plusYears(1)))
      val third  = worklog().copy(date = second.date)

      val units = Seq(entry(first), entry(second), entry(third))
      val rows = Seq(
        WorkUnitPivotTableRow(entriesCount = Some(StatsEntriesCount(1)), year = Some(StatsYear(first.date.getYear))),
        WorkUnitPivotTableRow(entriesCount = Some(StatsEntriesCount(2)), year = Some(StatsYear(second.date.getYear)))
      )

      testCase(Seq(WorkUnitPivotTableKey.YearKey), Seq(WorkUnitPivotTableValue.EntriesCount), units, rows)
    }
    "MonthYearKey" in {
      val first  = worklog()
      val second = first.copy(date = WorkUnitDate(first.date.plusYears(1)))
      val third  = worklog().copy(date = second.date)

      val units = Seq(entry(first), entry(second), entry(third))
      val rows = Seq(
        WorkUnitPivotTableRow(entriesCount = Some(StatsEntriesCount(1)), monthYear = Some(StatsYearMonth.from(first.date))),
        WorkUnitPivotTableRow(entriesCount = Some(StatsEntriesCount(2)), monthYear = Some(StatsYearMonth.from(second.date)))
      )

      testCase(Seq(WorkUnitPivotTableKey.MonthYearKey), Seq(WorkUnitPivotTableValue.EntriesCount), units, rows)
    }
    "DateKey" in {
      val first  = worklog()
      val second = first.copy(date = worklog().date)
      val third  = worklog().copy(date = second.date)

      val units = Seq(entry(first), entry(second), entry(third))
      val rows = Seq(
        WorkUnitPivotTableRow(entriesCount = Some(StatsEntriesCount(1)), date = Some(first.date)),
        WorkUnitPivotTableRow(entriesCount = Some(StatsEntriesCount(2)), date = Some(second.date))
      )

      testCase(Seq(WorkUnitPivotTableKey.DateKey), Seq(WorkUnitPivotTableValue.EntriesCount), units, rows)
    }
    "EntryTypeKey" in {
      val worklogEntry = worklog()
      val noteEntry = worklogEntry.copy(
        id = WorkUnitId(UUID.fromString("00000000-0000-0000-0000-000000000031")),
        duration = WorkUnitDuration(Duration.ZERO),
        entryType = WorkUnitEntryType.Note
      )
      val units = Seq(entry(worklogEntry), entry(noteEntry))
      val rows = Seq(
        WorkUnitPivotTableRow(entryType = Some(WorkUnitEntryType.Worklog), entriesCount = Some(StatsEntriesCount(1))),
        WorkUnitPivotTableRow(entryType = Some(WorkUnitEntryType.Note), entriesCount = Some(StatsEntriesCount(1)))
      )

      testCase(Seq(WorkUnitPivotTableKey.EntryTypeKey), Seq(WorkUnitPivotTableValue.EntriesCount), units, rows)
    }
    "EntryTypeKey with another grouping key" in {
      val firstProjectId  = ProjectId(UUID.fromString("00000000-0000-0000-0000-000000000041"))
      val secondProjectId = ProjectId(UUID.fromString("00000000-0000-0000-0000-000000000042"))
      val base            = worklog().copy(projectId = firstProjectId)
      val units = Seq(
        entry(base),
        entry(base.copy(entryType = WorkUnitEntryType.Note, duration = WorkUnitDuration(Duration.ZERO))),
        entry(base.copy(projectId = secondProjectId)),
        entry(base.copy(projectId = secondProjectId, entryType = WorkUnitEntryType.Note, duration = WorkUnitDuration(Duration.ZERO)))
      )
      val rows = Seq(
        WorkUnitPivotTableRow(
          entryType = Some(WorkUnitEntryType.Worklog),
          projectId = Some(firstProjectId),
          entriesCount = Some(StatsEntriesCount(1))
        ),
        WorkUnitPivotTableRow(entryType = Some(WorkUnitEntryType.Note), projectId = Some(firstProjectId), entriesCount = Some(StatsEntriesCount(1))),
        WorkUnitPivotTableRow(
          entryType = Some(WorkUnitEntryType.Worklog),
          projectId = Some(secondProjectId),
          entriesCount = Some(StatsEntriesCount(1))
        ),
        WorkUnitPivotTableRow(entryType = Some(WorkUnitEntryType.Note), projectId = Some(secondProjectId), entriesCount = Some(StatsEntriesCount(1)))
      )

      testCase(Seq(WorkUnitPivotTableKey.ProjectKey, WorkUnitPivotTableKey.EntryTypeKey), Seq(WorkUnitPivotTableValue.EntriesCount), units, rows)
    }
    "Value EntriesCount" in {
      val first        = worklog()
      val second       = worklog()
      val third        = worklog()
      val firstClient  = ClientId(UUID.randomUUID())
      val secondClient = ClientId(UUID.randomUUID())

      val units = Seq(entry(first, firstClient), entry(second, secondClient), entry(third, secondClient))
      val rows = Seq(
        WorkUnitPivotTableRow(entriesCount = Some(StatsEntriesCount(1)), clientId = Some(firstClient)),
        WorkUnitPivotTableRow(entriesCount = Some(StatsEntriesCount(2)), clientId = Some(secondClient))
      )

      testCase(Seq(WorkUnitPivotTableKey.ClientKey), Seq(WorkUnitPivotTableValue.EntriesCount), units, rows)
    }
    "Value DurationAvg" in {
      val first        = worklog(Duration.ofMinutes(30))
      val second       = worklog(Duration.ofMinutes(60))
      val third        = worklog(Duration.ofMinutes(90))
      val firstClient  = ClientId(UUID.randomUUID())
      val secondClient = ClientId(UUID.randomUUID())

      val units = Seq(entry(first, firstClient), entry(second, secondClient), entry(third, secondClient))
      val rows = Seq(
        WorkUnitPivotTableRow(durationAvg = Some(first.duration), clientId = Some(firstClient)),
        WorkUnitPivotTableRow(durationAvg = Some(second.duration.plus(third.duration).dividedBy(2L)), clientId = Some(secondClient))
      )

      testCase(Seq(WorkUnitPivotTableKey.ClientKey), Seq(WorkUnitPivotTableValue.DurationAvg), units, rows)
    }
    "Value DurationSum" in {
      val first        = worklog(Duration.ofMinutes(30))
      val second       = worklog(Duration.ofMinutes(60))
      val third        = worklog(Duration.ofMinutes(90))
      val firstClient  = ClientId(UUID.randomUUID())
      val secondClient = ClientId(UUID.randomUUID())

      val units = Seq(entry(first, firstClient), entry(second, secondClient), entry(third, secondClient))
      val rows = Seq(
        WorkUnitPivotTableRow(durationSum = Some(first.duration), clientId = Some(firstClient)),
        WorkUnitPivotTableRow(durationSum = Some(second.duration.plus(third.duration)), clientId = Some(secondClient))
      )

      testCase(Seq(WorkUnitPivotTableKey.ClientKey), Seq(WorkUnitPivotTableValue.DurationSum), units, rows)
    }
    "calculate mixed Worklog and Note metrics from Worklogs only" in {
      val base = worklog(Duration.ofMinutes(30))
      val units = Seq(
        entry(base),
        entry(
          base.copy(id = WorkUnitId(UUID.fromString("00000000-0000-0000-0000-000000000002")), duration = WorkUnitDuration(Duration.ofMinutes(90)))
        ),
        entry(
          base.copy(
            id = WorkUnitId(UUID.fromString("00000000-0000-0000-0000-000000000003")),
            duration = WorkUnitDuration(Duration.ZERO),
            entryType = WorkUnitEntryType.Note
          )
        )
      )
      val row = WorkUnitPivotTableRow(
        entryType = None,
        entriesCount = Some(StatsEntriesCount(3)),
        durationSum = Some(Duration.ofHours(2)),
        durationAvg = Some(Duration.ofHours(1))
      )

      testCase(Seq.empty, WorkUnitPivotTableValue.values, units, Seq(row))
    }
    "return zero time metrics for an all-Note group" in {
      val base  = worklog().copy(duration = WorkUnitDuration(Duration.ZERO), entryType = WorkUnitEntryType.Note)
      val units = Seq(entry(base), entry(base.copy(id = WorkUnitId(UUID.fromString("00000000-0000-0000-0000-000000000012")))))
      val row = WorkUnitPivotTableRow(entriesCount = Some(StatsEntriesCount(2)), durationSum = Some(Duration.ZERO), durationAvg = Some(Duration.ZERO))

      testCase(Seq.empty, WorkUnitPivotTableValue.values, units, Seq(row))
    }
    "ignore a corrupt positive duration on a Note" in {
      val worklog30 = worklog(Duration.ofMinutes(30))
      val corruptNote = worklog(Duration.ofHours(8))
        .copy(id = WorkUnitId(UUID.fromString("00000000-0000-0000-0000-000000000022")), entryType = WorkUnitEntryType.Note)
      val row = WorkUnitPivotTableRow(
        entriesCount = Some(StatsEntriesCount(2)),
        durationSum = Some(Duration.ofMinutes(30)),
        durationAvg = Some(Duration.ofMinutes(30))
      )

      testCase(Seq.empty, WorkUnitPivotTableValue.values, Seq(entry(worklog30), entry(corruptNote)), Seq(row))
    }
  }
}
