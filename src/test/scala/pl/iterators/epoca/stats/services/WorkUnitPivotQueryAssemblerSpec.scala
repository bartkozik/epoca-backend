package pl.iterators.epoca.stats.services

import org.specs2.mutable.SpecificationLike
import pl.iterators.epoca.stats.domain.StatsDomain.{WorkUnitPivotTableKey, WorkUnitPivotTableValue}
import pl.iterators.epoca.stats.domain.WorkUnitPivotQuery._
import pl.iterators.epoca.stats.repositories.WorkUnitPivotQueryRepository.AggregateRow
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.WorkUnitEntryType

import java.time.{Duration, LocalDate, YearMonth}
import java.util.UUID

class WorkUnitPivotQueryAssemblerSpec extends SpecificationLike {
  private val assembler = new WorkUnitPivotQueryAssembler()

  "WorkUnitPivotQueryAssembler" should {
    "order unordered sparse aggregates depth-first by rows and then columns" in {
      val request = pivotRequest(
        rows = Seq(WorkUnitPivotTableKey.UserKey, WorkUnitPivotTableKey.ProjectKey),
        columns = Seq(WorkUnitPivotTableKey.YearKey, WorkUnitPivotTableKey.MonthYearKey),
        values = Seq(WorkUnitPivotTableValue.EntriesCount)
      )
      val userA         = uuid("00000000-0000-0000-0000-000000000001")
      val userB         = uuid("00000000-0000-0000-0000-000000000002")
      val project1      = uuid("00000000-0000-0000-0000-000000000011")
      val project2      = uuid("00000000-0000-0000-0000-000000000012")
      val project3      = uuid("00000000-0000-0000-0000-000000000013")
      val userAPath     = Seq(PathSegment(WorkUnitPivotTableKey.UserKey, userA))
      val userBPath     = Seq(PathSegment(WorkUnitPivotTableKey.UserKey, userB))
      val userAProject1 = userAPath :+ PathSegment(WorkUnitPivotTableKey.ProjectKey, project1)
      val userAProject2 = userAPath :+ PathSegment(WorkUnitPivotTableKey.ProjectKey, project2)
      val userBProject3 = userBPath :+ PathSegment(WorkUnitPivotTableKey.ProjectKey, project3)
      val yearPath      = Seq(PathSegment(WorkUnitPivotTableKey.YearKey, FieldValue.Year(2026)))
      val julyPath      = yearPath :+ PathSegment(WorkUnitPivotTableKey.MonthYearKey, FieldValue.Month(YearMonth.of(2026, 7)))
      val augustPath    = yearPath :+ PathSegment(WorkUnitPivotTableKey.MonthYearKey, FieldValue.Month(YearMonth.of(2026, 8)))
      val emptyPath     = Seq.empty[PathSegment]

      val aggregates = Seq(
        aggregate(userAPath, emptyPath, 6L),
        aggregate(userBProject3, julyPath, 7L),
        aggregate(emptyPath, emptyPath, 10L),
        aggregate(userAProject1, yearPath, 3L),
        aggregate(userAProject1, augustPath, 2L),
        aggregate(userAProject1, julyPath, 1L),
        aggregate(userAProject2, julyPath, 5L),
        aggregate(userBPath, julyPath, 8L),
        aggregate(userAProject1, emptyPath, 4L)
      )

      val response =
        assembler.assemble(request, aggregates, availableFilterMembers = Map.empty, displayValueCellCount = 72L, maxDisplayValueCellCount = 10000L)

      response.cells.map(cell => cell.row.path -> cell.column.path) shouldEqual Seq(
        userAProject1 -> julyPath,
        userAProject1 -> augustPath,
        userAProject1 -> yearPath,
        userAProject1 -> emptyPath,
        userAProject2 -> julyPath,
        userAPath     -> emptyPath,
        userBProject3 -> julyPath,
        userBPath     -> julyPath,
        emptyPath     -> emptyPath
      )
      response.cells.map(_.values.entriesCount) shouldEqual Seq(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 10L).map(Some(_))
      response.cells.map(cell => cell.row.kind -> cell.column.kind) shouldEqual Seq(
        CoordinateKind.Leaf       -> CoordinateKind.Leaf,
        CoordinateKind.Leaf       -> CoordinateKind.Leaf,
        CoordinateKind.Leaf       -> CoordinateKind.Subtotal,
        CoordinateKind.Leaf       -> CoordinateKind.GrandTotal,
        CoordinateKind.Leaf       -> CoordinateKind.Leaf,
        CoordinateKind.Subtotal   -> CoordinateKind.GrandTotal,
        CoordinateKind.Leaf       -> CoordinateKind.Leaf,
        CoordinateKind.Subtotal   -> CoordinateKind.Leaf,
        CoordinateKind.GrandTotal -> CoordinateKind.GrandTotal
      )
      response.result shouldEqual ResultMetadata(cellCount = 9L, displayValueCellCount = 72L, maxDisplayValueCellCount = 10000L)
    }

    "keep an empty axis structural, preserve sparse cells, and project only requested metrics" in {
      val request = pivotRequest(rows = Seq.empty, columns = Seq.empty, values = Seq(WorkUnitPivotTableValue.DurationAvg))
      val noteOnly =
        AggregateRow(rowPath = Seq.empty, columnPath = Seq.empty, entriesCount = 1L, durationSumNanos = BigInt(0), durationAvgNanos = None)

      val response = assembler.assemble(request, Seq(noteOnly), Map.empty, displayValueCellCount = 1L, maxDisplayValueCellCount = 10L)

      response.cells shouldEqual Seq(
        Cell(
          row = Coordinate(CoordinateKind.Leaf, Seq.empty),
          column = Coordinate(CoordinateKind.Leaf, Seq.empty),
          values = MetricValues(durationSum = None, durationAvg = Some(None), entriesCount = None)
        )
      )
      response.result.cellCount shouldEqual 1L
    }

    "convert exact nanoseconds without overflow and reject values outside java Duration" in {
      val request = pivotRequest(
        rows = Seq.empty,
        columns = Seq.empty,
        values = Seq(WorkUnitPivotTableValue.DurationSum, WorkUnitPivotTableValue.DurationAvg, WorkUnitPivotTableValue.EntriesCount)
      )
      val nanos = BigInt("1000000001")
      val response = assembler.assemble(
        request,
        Seq(AggregateRow(Seq.empty, Seq.empty, 2L, nanos, Some(nanos))),
        Map.empty,
        displayValueCellCount = 3L,
        maxDisplayValueCellCount = 10000L
      )

      response.cells.head.values shouldEqual MetricValues(
        durationSum = Some(Duration.ofSeconds(1L, 1L)),
        durationAvg = Some(Some(Duration.ofSeconds(1L, 1L))),
        entriesCount = Some(2L)
      )

      val billion       = BigInt(1000000000L)
      val maxValidNanos = BigInt(Long.MaxValue) * billion + (billion - 1)
      WorkUnitPivotQueryAssembler.durationFromNanos(maxValidNanos) shouldEqual Duration.ofSeconds(Long.MaxValue, 999999999L)
      WorkUnitPivotQueryAssembler.durationFromNanos((BigInt(Long.MaxValue) + 1) * billion) must throwA[IllegalStateException]
      WorkUnitPivotQueryAssembler.durationFromNanos(BigInt(-1)) must throwA[IllegalStateException]
    }

    "return one deterministic facet per requested filter with available and unavailable groups sorted independently" in {
      val uuidLow  = uuid("7fffffff-ffff-ffff-ffff-ffffffffffff")
      val uuidHigh = uuid("80000000-0000-0000-0000-000000000000")
      val uuidMid  = uuid("80000000-0000-0000-0000-000000000001")
      val request = pivotRequest(
        rows = Seq.empty,
        columns = Seq.empty,
        values = Seq(WorkUnitPivotTableValue.EntriesCount),
        filters = Seq(
          Filter(WorkUnitPivotTableKey.EntryTypeKey, Some(Seq(FieldValue.EntryType(WorkUnitEntryType.Note)))),
          Filter(WorkUnitPivotTableKey.ClientKey, Some(Seq(uuidLow, FieldValue.Null, uuidLow))),
          Filter(WorkUnitPivotTableKey.UserKey, Some(Seq(uuidMid))),
          Filter(WorkUnitPivotTableKey.YearKey, Some(Seq(FieldValue.Year(2026), FieldValue.Year(2024)))),
          Filter(WorkUnitPivotTableKey.MonthYearKey, Some(Seq(FieldValue.Month(YearMonth.of(2026, 6))))),
          Filter(WorkUnitPivotTableKey.DateKey, Some(Seq(FieldValue.Date(LocalDate.of(2026, 6, 30))))),
          Filter(WorkUnitPivotTableKey.ProjectKey, None)
        )
      )
      val available = Map[WorkUnitPivotTableKey, Seq[FieldValue]](
        WorkUnitPivotTableKey.EntryTypeKey -> Seq(FieldValue.EntryType(WorkUnitEntryType.Note), FieldValue.EntryType(WorkUnitEntryType.Worklog)),
        WorkUnitPivotTableKey.ClientKey    -> Seq(FieldValue.Null, uuidHigh, uuidHigh),
        WorkUnitPivotTableKey.UserKey      -> Seq(uuidHigh, uuidLow),
        WorkUnitPivotTableKey.YearKey      -> Seq(FieldValue.Year(2027), FieldValue.Year(2025)),
        WorkUnitPivotTableKey.MonthYearKey -> Seq(FieldValue.Month(YearMonth.of(2026, 8)), FieldValue.Month(YearMonth.of(2026, 7))),
        WorkUnitPivotTableKey.DateKey      -> Seq(FieldValue.Date(LocalDate.of(2026, 8, 1)), FieldValue.Date(LocalDate.of(2026, 7, 1))),
        WorkUnitPivotTableKey.ProjectKey   -> Seq(uuidHigh, uuidLow)
      )

      val first  = assembler.assemble(request, Seq.empty, available, displayValueCellCount = 0L, maxDisplayValueCellCount = 10000L)
      val second = assembler.assemble(request, Seq.empty, available, displayValueCellCount = 0L, maxDisplayValueCellCount = 10000L)

      first shouldEqual second
      first.filterMembers.map(_.key) shouldEqual request.filters.map(_.key)
      first.filterMembers shouldEqual Seq(
        FilterMembers(
          WorkUnitPivotTableKey.EntryTypeKey,
          Seq(
            FilterMember(FieldValue.EntryType(WorkUnitEntryType.Worklog), available = true),
            FilterMember(FieldValue.EntryType(WorkUnitEntryType.Note), available = true)
          )
        ),
        FilterMembers(
          WorkUnitPivotTableKey.ClientKey,
          Seq(FilterMember(uuidHigh, available = true), FilterMember(FieldValue.Null, available = true), FilterMember(uuidLow, available = false))
        ),
        FilterMembers(
          WorkUnitPivotTableKey.UserKey,
          Seq(FilterMember(uuidLow, available = true), FilterMember(uuidHigh, available = true), FilterMember(uuidMid, available = false))
        ),
        FilterMembers(
          WorkUnitPivotTableKey.YearKey,
          Seq(
            FilterMember(FieldValue.Year(2025), available = true),
            FilterMember(FieldValue.Year(2027), available = true),
            FilterMember(FieldValue.Year(2024), available = false),
            FilterMember(FieldValue.Year(2026), available = false)
          )
        ),
        FilterMembers(
          WorkUnitPivotTableKey.MonthYearKey,
          Seq(
            FilterMember(FieldValue.Month(YearMonth.of(2026, 7)), available = true),
            FilterMember(FieldValue.Month(YearMonth.of(2026, 8)), available = true),
            FilterMember(FieldValue.Month(YearMonth.of(2026, 6)), available = false)
          )
        ),
        FilterMembers(
          WorkUnitPivotTableKey.DateKey,
          Seq(
            FilterMember(FieldValue.Date(LocalDate.of(2026, 7, 1)), available = true),
            FilterMember(FieldValue.Date(LocalDate.of(2026, 8, 1)), available = true),
            FilterMember(FieldValue.Date(LocalDate.of(2026, 6, 30)), available = false)
          )
        ),
        FilterMembers(WorkUnitPivotTableKey.ProjectKey, Seq(FilterMember(uuidLow, available = true), FilterMember(uuidHigh, available = true)))
      )
      first.result shouldEqual ResultMetadata(cellCount = 0L, displayValueCellCount = 0L, maxDisplayValueCellCount = 10000L)
    }

    "order TaskKey paths lexicographically with null-task last" in {
      val request  = pivotRequest(rows = Seq(WorkUnitPivotTableKey.TaskKey), columns = Seq.empty, values = Seq(WorkUnitPivotTableValue.EntriesCount))
      val zPath    = Seq(PathSegment(WorkUnitPivotTableKey.TaskKey, FieldValue.TaskId("Z-1")))
      val aPath    = Seq(PathSegment(WorkUnitPivotTableKey.TaskKey, FieldValue.TaskId("A-1")))
      val nullPath = Seq(PathSegment(WorkUnitPivotTableKey.TaskKey, FieldValue.Null))

      val aggregates = Seq(aggregate(nullPath, Seq.empty, 1L), aggregate(zPath, Seq.empty, 1L), aggregate(aPath, Seq.empty, 1L))

      val response = assembler.assemble(request, aggregates, Map.empty, displayValueCellCount = 0L, maxDisplayValueCellCount = 10000L)

      response.cells.map(_.row.path) shouldEqual Seq(aPath, zPath, nullPath)
    }
  }

  private def pivotRequest(
    rows: Seq[WorkUnitPivotTableKey],
    columns: Seq[WorkUnitPivotTableKey],
    values: Seq[WorkUnitPivotTableValue],
    filters: Seq[Filter] = Seq.empty
  ): Request =
    Request(
      rows = rows,
      columns = columns,
      values = values,
      filters = filters,
      options = Options(rowSubtotals = true, columnSubtotals = true, grandTotals = true)
    )

  private def aggregate(
    rowPath: Seq[PathSegment],
    columnPath: Seq[PathSegment],
    entriesCount: Long
  ): AggregateRow =
    AggregateRow(
      rowPath = rowPath,
      columnPath = columnPath,
      entriesCount = entriesCount,
      durationSumNanos = BigInt(entriesCount),
      durationAvgNanos = Some(BigInt(entriesCount))
    )

  private def uuid(value: String): FieldValue = FieldValue.Uuid(UUID.fromString(value))
}
