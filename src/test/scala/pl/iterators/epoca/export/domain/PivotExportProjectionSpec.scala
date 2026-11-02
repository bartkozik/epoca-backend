package pl.iterators.epoca.`export`.domain

import org.specs2.mutable.SpecificationLike
import pl.iterators.epoca.export.domain.PivotExportProjection.Value.{Count, DurationMinutes}
import pl.iterators.epoca.export.domain.PivotExportProjection._
import pl.iterators.epoca.export.domain.WorkUnitPivotExport.{MemberLabel, Presentation, Projection}
import pl.iterators.epoca.stats.domain.StatsDomain.{WorkUnitPivotTableKey, WorkUnitPivotTableValue}
import pl.iterators.epoca.stats.domain.WorkUnitPivotQuery._
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.WorkUnitEntryType

import java.time.{Duration, LocalDate, YearMonth}
import java.util.UUID

class PivotExportProjectionSpec extends SpecificationLike {
  "PivotExportProjection" should {
    "complete only a supplied Month-only axis, preserve sparse metrics, and keep Grand Total last" in {
      val february       = YearMonth.of(2026, 2)
      val march          = YearMonth.of(2026, 3)
      val april          = YearMonth.of(2026, 4)
      val may            = YearMonth.of(2026, 5)
      val june           = YearMonth.of(2026, 6)
      val months         = Vector(february, march, april, may, june, YearMonth.of(2026, 7), YearMonth.of(2026, 8))
      val coordinates    = months.map(month => Coordinate(CoordinateKind.Leaf, Seq(monthSegment(month))))
      val mayCoordinate  = coordinates(3)
      val juneCoordinate = coordinates(4)
      val response = pivotResponse(
        Seq(
          completeCell(CoordinateKind.Leaf, projectAPath, CoordinateKind.Leaf, mayCoordinate.path, 10, 10, 1),
          completeCell(CoordinateKind.Leaf, projectAPath, CoordinateKind.Leaf, juneCoordinate.path, 20, 20, 1),
          completeCell(CoordinateKind.Leaf, projectAPath, CoordinateKind.Leaf, julyPath, 30, 30, 1),
          completeCell(CoordinateKind.Leaf, projectAPath, CoordinateKind.Leaf, augustPath, 40, 40, 1),
          completeCell(CoordinateKind.Leaf, projectAPath, CoordinateKind.GrandTotal, Seq.empty, 100, 25, 4),
          completeCell(CoordinateKind.Leaf, projectBPath, CoordinateKind.Leaf, augustPath, 50, 50, 1),
          completeCell(CoordinateKind.Leaf, projectBPath, CoordinateKind.GrandTotal, Seq.empty, 50, 50, 1)
        ),
        maxDisplayValueCellCount = 1000L
      )
      val monthLabels =
        months.zip(Vector("Luty 2026", "Marzec 2026", "Kwiecień 2026", "Maj 2026", "Czerwiec 2026", "Lipiec 2026", "Sierpień 2026")).map {
          case (month, label) => MemberLabel(WorkUnitPivotTableKey.MonthYearKey, FieldValue.Month(month), label)
        }
      val table = build(
        response,
        canonicalRequest,
        Some(Projection(LocalDate.of(2026, 8, 20))),
        LocalDate.of(2026, 2, 1),
        canonicalPresentation.copy(memberLabels = canonicalPresentation.memberLabels ++ monthLabels)
      ).toOption.get
      val columnCoordinates = table.columns.grouped(canonicalRequest.values.size).map(_.head.coordinate).toVector

      (columnCoordinates shouldEqual coordinates :+ grandTotalCoordinate) and
        ((columnCoordinates(3).asInstanceOf[AnyRef] eq response.cells(0).column.asInstanceOf[AnyRef]) must beTrue) and
        ((columnCoordinates(4).asInstanceOf[AnyRef] eq response.cells(1).column.asInstanceOf[AnyRef]) must beTrue) and
        (table.columns.grouped(3).map(_.head.labels).toVector shouldEqual
          months.map(month => Vector(monthLabels.find(_.value == FieldValue.Month(month)).get.label)) :+ Vector("All")) and
        (table.rows.map(_.values) shouldEqual Vector(
          Vector.fill(9)(None) ++
            Vector(
              duration(10),
              duration(10),
              count(1),
              duration(20),
              duration(20),
              count(1),
              duration(30),
              duration(30),
              count(1),
              duration(40),
              duration(40),
              count(1),
              duration(100),
              duration(25),
              count(4)
            ),
          Vector.fill(18)(None) ++ Vector(duration(50), duration(50), count(1), duration(50), duration(50), count(1))
        ))
    }

    "leave legacy, non-Month, and nested-Month projections byte-for-byte sparse" in {
      val legacy           = build(canonicalResponse, canonicalRequest, canonicalPresentation)
      val projectedLegacy  = build(canonicalResponse, canonicalRequest, None, LocalDate.of(2026, 2, 1), canonicalPresentation).toOption.get
      val yearCoordinate   = Coordinate(CoordinateKind.Leaf, Seq(PathSegment(WorkUnitPivotTableKey.YearKey, FieldValue.Year(2026))))
      val nestedCoordinate = Coordinate(CoordinateKind.Leaf, yearCoordinate.path :+ monthSegment(YearMonth.of(2026, 8)))
      val outOfScope = Seq(
        pivotRequest(Seq.empty, Seq.empty, Seq(WorkUnitPivotTableValue.EntriesCount))                          -> leafCoordinate,
        pivotRequest(Seq.empty, Seq(WorkUnitPivotTableKey.YearKey), Seq(WorkUnitPivotTableValue.EntriesCount)) -> yearCoordinate,
        pivotRequest(
          Seq.empty,
          Seq(WorkUnitPivotTableKey.YearKey, WorkUnitPivotTableKey.MonthYearKey),
          Seq(WorkUnitPivotTableValue.EntriesCount)
        ) -> nestedCoordinate
      )
      val unchanged = outOfScope.map { case (request, coordinate) =>
        val response = pivotResponse(Seq(pivotCell(leafCoordinate, coordinate, metrics(count = Some(1L)))))
        val sparse   = build(response, request, presentation())
        build(response, request, Some(Projection(LocalDate.of(2026, 8, 20))), LocalDate.of(2026, 2, 1), presentation()).toOption.get == sparse
      }

      (projectedLegacy shouldEqual legacy) and
        (PivotCsvEncoder.encode(projectedLegacy, canonicalPresentation.measureLabels).toSeq shouldEqual
          PivotCsvEncoder.encode(legacy, canonicalPresentation.measureLabels).toSeq) and
        (unchanged must contain(beTrue).foreach)
    }

    "saturate expanded display counts and reject a huge calendar range before allocating its coordinates" in {
      val oneCell = pivotResponse(Seq(pivotCell(leafCoordinate, augustCoordinate, metrics(count = Some(1L)))), maxDisplayValueCellCount = 10L)
      val rejected = build(
        oneCell,
        pivotRequest(Seq.empty, Seq(WorkUnitPivotTableKey.MonthYearKey), Seq(WorkUnitPivotTableValue.EntriesCount)),
        Some(Projection(LocalDate.MAX)),
        LocalDate.MIN,
        presentation()
      )

      (saturatingDisplayValueCellCount(Long.MaxValue, 2L, 3L) shouldEqual Long.MaxValue) and
        (rejected must beLeft.like { case error =>
          (error.estimatedDisplayValueCellCount must beGreaterThan(10L)) and
            (error.maxDisplayValueCellCount shouldEqual 10L)
        })
    }

    "project the complete canonical sparse table in semantic axis and requested measure order" in {
      val response = canonicalResponse.copy(filterMembers =
        Seq(FilterMembers(WorkUnitPivotTableKey.ProjectKey, Seq(FilterMember(projectB, available = true), FilterMember(projectA, available = false))))
      )

      build(response, canonicalRequest, canonicalPresentation) shouldEqual Table(
        rowFieldLabels = Vector("Person", "Initiative"),
        columns = Vector(
          ColumnHeader(julyCoordinate, Vector("Zulu July"), WorkUnitPivotTableValue.DurationSum),
          ColumnHeader(julyCoordinate, Vector("Zulu July"), WorkUnitPivotTableValue.DurationAvg),
          ColumnHeader(julyCoordinate, Vector("Zulu July"), WorkUnitPivotTableValue.EntriesCount),
          ColumnHeader(augustCoordinate, Vector("Alpha August"), WorkUnitPivotTableValue.DurationSum),
          ColumnHeader(augustCoordinate, Vector("Alpha August"), WorkUnitPivotTableValue.DurationAvg),
          ColumnHeader(augustCoordinate, Vector("Alpha August"), WorkUnitPivotTableValue.EntriesCount),
          ColumnHeader(grandTotalCoordinate, Vector("All"), WorkUnitPivotTableValue.DurationSum),
          ColumnHeader(grandTotalCoordinate, Vector("All"), WorkUnitPivotTableValue.DurationAvg),
          ColumnHeader(grandTotalCoordinate, Vector("All"), WorkUnitPivotTableValue.EntriesCount)
        ),
        rows = Vector(
          DataRow(
            RowHeader(Vector(Some("User Zulu"), Some("Project Zulu")), CoordinateKind.Leaf),
            Vector(duration(120), duration(120), count(1), duration(180), duration(180), count(1), duration(300), duration(150), count(2))
          ),
          DataRow(
            RowHeader(Vector(Some("User Zulu"), Some("Project Alpha")), CoordinateKind.Leaf),
            Vector(duration(60), duration(60), count(1), None, None, None, duration(60), duration(60), count(1))
          ),
          DataRow(
            RowHeader(Vector(Some("User Zulu"), Some("Subtotal")), CoordinateKind.Subtotal),
            Vector(duration(180), duration(90), count(2), duration(180), duration(180), count(1), duration(360), duration(120), count(3))
          ),
          DataRow(
            RowHeader(Vector(Some("All"), None), CoordinateKind.GrandTotal),
            Vector(duration(180), duration(90), count(2), duration(180), duration(180), count(1), duration(360), duration(120), count(3))
          )
        )
      )
    }

    "order sparse axes by typed values with subtotals after descendants and grand totals last" in {
      val reorderedCells =
        Seq(canonicalResponse.cells(3), canonicalResponse.cells(4), canonicalResponse.cells(1)) ++ canonicalResponse.cells.zipWithIndex.collect {
          case (cell, index) if !Set(1, 3, 4)(index) => cell
        }

      val table = build(canonicalResponse.copy(cells = reorderedCells), canonicalRequest, canonicalPresentation)

      table.columns.grouped(canonicalRequest.values.size).map(_.head.coordinate).toVector shouldEqual Vector(
        julyCoordinate,
        augustCoordinate,
        grandTotalCoordinate
      )
      table.rows.map(_.header) shouldEqual Vector(
        RowHeader(Vector(Some("User Zulu"), Some("Project Zulu")), CoordinateKind.Leaf),
        RowHeader(Vector(Some("User Zulu"), Some("Project Alpha")), CoordinateKind.Leaf),
        RowHeader(Vector(Some("User Zulu"), Some("Subtotal")), CoordinateKind.Subtotal),
        RowHeader(Vector(Some("All"), None), CoordinateKind.GrandTotal)
      )
    }

    "distinguish absent sparse values, exact zeros, and a requested null average" in {
      val request = pivotRequest(
        rows = Seq.empty,
        columns = Seq.empty,
        values = Seq(WorkUnitPivotTableValue.DurationSum, WorkUnitPivotTableValue.DurationAvg, WorkUnitPivotTableValue.EntriesCount)
      )
      val response = pivotResponse(
        Seq(
          pivotCell(
            leafCoordinate,
            leafCoordinate,
            MetricValues(durationSum = Some(Duration.ZERO), durationAvg = Some(None), entriesCount = Some(0L))
          )
        )
      )

      build(response, request, presentation()) shouldEqual Table(
        rowFieldLabels = Vector.empty,
        columns = Vector(
          ColumnHeader(leafCoordinate, Vector.empty, WorkUnitPivotTableValue.DurationSum),
          ColumnHeader(leafCoordinate, Vector.empty, WorkUnitPivotTableValue.DurationAvg),
          ColumnHeader(leafCoordinate, Vector.empty, WorkUnitPivotTableValue.EntriesCount)
        ),
        rows = Vector(DataRow(RowHeader(Vector.empty, CoordinateKind.Leaf), Vector(Some(DurationMinutes(BigInt(0))), None, Some(Count(0L)))))
      )
    }

    "keep a structural leaf row when both axes are empty" in {
      val request  = pivotRequest(Seq.empty, Seq.empty, Seq(WorkUnitPivotTableValue.EntriesCount))
      val response = pivotResponse(Seq(pivotCell(leafCoordinate, leafCoordinate, metrics(count = Some(2L)))))

      build(response, request, presentation()).rows shouldEqual Vector(DataRow(RowHeader(Vector.empty, CoordinateKind.Leaf), Vector(Some(Count(2L)))))
    }

    "project rows-empty and columns-nonempty without synthetic row headers" in {
      val request =
        pivotRequest(rows = Seq.empty, columns = Seq(WorkUnitPivotTableKey.MonthYearKey), values = Seq(WorkUnitPivotTableValue.EntriesCount))
      val response = pivotResponse(
        Seq(
          pivotCell(leafCoordinate, julyCoordinate, metrics(count = Some(1L))),
          pivotCell(leafCoordinate, grandTotalCoordinate, metrics(count = Some(1L)))
        )
      )

      build(response, request, canonicalPresentation) shouldEqual Table(
        rowFieldLabels = Vector.empty,
        columns = Vector(
          ColumnHeader(julyCoordinate, Vector("Zulu July"), WorkUnitPivotTableValue.EntriesCount),
          ColumnHeader(grandTotalCoordinate, Vector("All"), WorkUnitPivotTableValue.EntriesCount)
        ),
        rows = Vector(DataRow(RowHeader(Vector.empty, CoordinateKind.Leaf), Vector(count(1), count(1))))
      )
    }

    "project columns-empty and rows-nonempty with measures directly identifying value columns" in {
      val request = pivotRequest(
        rows = Seq(WorkUnitPivotTableKey.UserKey),
        columns = Seq.empty,
        values = Seq(WorkUnitPivotTableValue.DurationAvg, WorkUnitPivotTableValue.EntriesCount)
      )
      val userCoordinate = Coordinate(CoordinateKind.Leaf, userPath)
      val response = pivotResponse(
        Seq(
          pivotCell(userCoordinate, leafCoordinate, metrics(average = Some(Some(Duration.ofMinutes(10))), count = Some(1L))),
          pivotCell(grandTotalCoordinate, leafCoordinate, metrics(average = Some(Some(Duration.ofMinutes(10))), count = Some(1L)))
        )
      )

      build(response, request, canonicalPresentation) shouldEqual Table(
        rowFieldLabels = Vector("Person"),
        columns = Vector(
          ColumnHeader(leafCoordinate, Vector.empty, WorkUnitPivotTableValue.DurationAvg),
          ColumnHeader(leafCoordinate, Vector.empty, WorkUnitPivotTableValue.EntriesCount)
        ),
        rows = Vector(
          DataRow(RowHeader(Vector(Some("User Zulu")), CoordinateKind.Leaf), Vector(duration(10), count(1))),
          DataRow(RowHeader(Vector(Some("All")), CoordinateKind.GrandTotal), Vector(duration(10), count(1)))
        )
      )
    }

    "place every row and column coordinate kind unambiguously across multiple hierarchy levels" in {
      val year        = FieldValue.Year(2026)
      val yearPath    = Seq(PathSegment(WorkUnitPivotTableKey.YearKey, year))
      val yearColumn  = Coordinate(CoordinateKind.Subtotal, yearPath)
      val monthColumn = Coordinate(CoordinateKind.Leaf, yearPath ++ julyPath)
      val request = pivotRequest(
        rows = Seq(WorkUnitPivotTableKey.UserKey, WorkUnitPivotTableKey.ProjectKey, WorkUnitPivotTableKey.ClientKey),
        columns = Seq(WorkUnitPivotTableKey.YearKey, WorkUnitPivotTableKey.MonthYearKey),
        values = Seq(WorkUnitPivotTableValue.EntriesCount)
      )
      val client       = FieldValue.Uuid(UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc"))
      val clientPath   = projectAPath :+ PathSegment(WorkUnitPivotTableKey.ClientKey, client)
      val leafRow      = Coordinate(CoordinateKind.Leaf, clientPath)
      val projectTotal = Coordinate(CoordinateKind.Subtotal, projectAPath)
      val userTotal    = Coordinate(CoordinateKind.Subtotal, userPath)
      val response = pivotResponse(
        Seq(
          pivotCell(leafRow, monthColumn, metrics(count = Some(1L))),
          pivotCell(leafRow, yearColumn, metrics(count = Some(1L))),
          pivotCell(leafRow, grandTotalCoordinate, metrics(count = Some(1L))),
          pivotCell(projectTotal, monthColumn, metrics(count = Some(1L))),
          pivotCell(userTotal, monthColumn, metrics(count = Some(1L))),
          pivotCell(grandTotalCoordinate, monthColumn, metrics(count = Some(1L)))
        )
      )
      val labels = Seq(MemberLabel(WorkUnitPivotTableKey.ClientKey, client, "Client"), MemberLabel(WorkUnitPivotTableKey.YearKey, year, "FY26"))
      val table  = build(response, request, canonicalPresentation.copy(memberLabels = canonicalPresentation.memberLabels ++ labels))

      table.columns shouldEqual Vector(
        ColumnHeader(monthColumn, Vector("FY26", "Zulu July"), WorkUnitPivotTableValue.EntriesCount),
        ColumnHeader(yearColumn, Vector("FY26", "Subtotal"), WorkUnitPivotTableValue.EntriesCount),
        ColumnHeader(grandTotalCoordinate, Vector("All"), WorkUnitPivotTableValue.EntriesCount)
      )
      table.rows.map(_.header) shouldEqual Vector(
        RowHeader(Vector(Some("User Zulu"), Some("Project Zulu"), Some("Client")), CoordinateKind.Leaf),
        RowHeader(Vector(Some("User Zulu"), Some("Project Zulu"), Some("Subtotal")), CoordinateKind.Subtotal),
        RowHeader(Vector(Some("User Zulu"), Some("Subtotal"), None), CoordinateKind.Subtotal),
        RowHeader(Vector(Some("All"), None, None), CoordinateKind.GrandTotal)
      )
      table.rows.map(_.values) shouldEqual Vector(
        Vector(count(1), count(1), count(1)),
        Vector(count(1), None, None),
        Vector(count(1), None, None),
        Vector(count(1), None, None)
      )
    }

    "use typed dictionary identities and canonical fallbacks for every dimension" in {
      val sharedId     = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd")
      val freshUser    = UUID.fromString("10000000-0000-4000-8000-000000000001")
      val freshProject = UUID.fromString("20000000-0000-4000-8000-000000000002")
      val freshClient  = UUID.fromString("30000000-0000-4000-8000-000000000003")
      val keys = Seq(
        WorkUnitPivotTableKey.UserKey,
        WorkUnitPivotTableKey.ProjectKey,
        WorkUnitPivotTableKey.ClientKey,
        WorkUnitPivotTableKey.YearKey,
        WorkUnitPivotTableKey.MonthYearKey,
        WorkUnitPivotTableKey.DateKey,
        WorkUnitPivotTableKey.EntryTypeKey
      )
      val knownValues = Seq[FieldValue](
        FieldValue.Uuid(sharedId),
        FieldValue.Uuid(sharedId),
        FieldValue.Uuid(sharedId),
        FieldValue.Year(2024),
        FieldValue.Month(YearMonth.of(2024, 5)),
        FieldValue.Date(LocalDate.of(2024, 5, 6)),
        FieldValue.EntryType(WorkUnitEntryType.Note)
      )
      val freshValues = Seq[FieldValue](
        FieldValue.Uuid(freshUser),
        FieldValue.Uuid(freshProject),
        FieldValue.Uuid(freshClient),
        FieldValue.Year(2027),
        FieldValue.Month(YearMonth.of(2027, 9)),
        FieldValue.Date(LocalDate.of(2027, 9, 8)),
        FieldValue.EntryType(WorkUnitEntryType.Worklog)
      )
      val nullClientValues = freshValues.updated(2, FieldValue.Null)
      def row(values: Seq[FieldValue]): Coordinate =
        Coordinate(CoordinateKind.Leaf, keys.zip(values).map { case (key, value) => PathSegment(key, value) })

      val known    = row(knownValues)
      val fresh    = row(freshValues)
      val noClient = row(nullClientValues)
      val response = pivotResponse(
        Seq(
          pivotCell(known, leafCoordinate, metrics(count = Some(1L))),
          pivotCell(fresh, leafCoordinate, metrics(count = Some(2L))),
          pivotCell(noClient, leafCoordinate, metrics(count = Some(3L)))
        )
      )
      val dictionary              = Seq("Known user", "Known project", "Known client", "Known year", "Known month", "Known date", "Known entry")
      val labels                  = keys.zip(knownValues).zip(dictionary).map { case ((key, value), label) => MemberLabel(key, value, label) }
      val sameRawValueOnlyForUser = MemberLabel(WorkUnitPivotTableKey.UserKey, FieldValue.Uuid(freshProject), "Wrong cross-key label")
      val table = build(
        response,
        pivotRequest(keys, Seq.empty, Seq(WorkUnitPivotTableValue.EntriesCount)),
        presentation(memberLabels = labels :+ sameRawValueOnlyForUser)
      )

      table.rows.map(_.header.values) shouldEqual Vector(
        Vector(
          Some(freshUser.toString),
          Some(freshProject.toString),
          Some(freshClient.toString),
          Some("2027"),
          Some("2027-09"),
          Some("2027-09-08"),
          Some("Worklog")
        ),
        Vector(
          Some(freshUser.toString),
          Some(freshProject.toString),
          Some("No account"),
          Some("2027"),
          Some("2027-09"),
          Some("2027-09-08"),
          Some("Worklog")
        ),
        dictionary.map(Some(_)).toVector
      )
    }

    "fail fast rather than reaggregate duplicate coordinate pairs" in {
      val duplicated = pivotCell(leafCoordinate, leafCoordinate, metrics(count = Some(1L)))

      build(
        pivotResponse(Seq(duplicated, duplicated)),
        pivotRequest(Seq.empty, Seq.empty, Seq(WorkUnitPivotTableValue.EntriesCount)),
        presentation()
      ) must throwA[IllegalStateException]
    }

    "fall back to the configured noTaskLabel for a grouped-out task id" in {
      val request = pivotRequest(rows = Seq(WorkUnitPivotTableKey.TaskKey), columns = Seq.empty, values = Seq(WorkUnitPivotTableValue.EntriesCount))
      val nullTaskPath = Seq(PathSegment(WorkUnitPivotTableKey.TaskKey, FieldValue.Null))
      val response =
        pivotResponse(Seq(completeCell(CoordinateKind.Leaf, nullTaskPath, CoordinateKind.Leaf, Seq.empty, 0, 0, 1)), maxDisplayValueCellCount = 1000L)

      val table = build(response, request, presentation(noTaskLabel = "No task"))

      table.rows.map(_.header.values.head) must contain(Some("No task"))
    }

    "fall back to the raw task id string for a grouped task with no configured member label" in {
      val request  = pivotRequest(rows = Seq(WorkUnitPivotTableKey.TaskKey), columns = Seq.empty, values = Seq(WorkUnitPivotTableValue.EntriesCount))
      val taskPath = Seq(PathSegment(WorkUnitPivotTableKey.TaskKey, FieldValue.TaskId("PROJ-123")))
      val response =
        pivotResponse(Seq(completeCell(CoordinateKind.Leaf, taskPath, CoordinateKind.Leaf, Seq.empty, 0, 0, 1)), maxDisplayValueCellCount = 1000L)

      val table = build(response, request, presentation())

      table.rows.map(_.header.values.head) must contain(Some("PROJ-123"))
    }
  }

  "roundedMinutes" should {
    "round nonnegative exact durations half up without overflowing Long nanoseconds" in {
      (roundedMinutes(Duration.ZERO) shouldEqual BigInt(0)) and
        (roundedMinutes(Duration.ofSeconds(29L, 999000000L)) shouldEqual BigInt(0)) and
        (roundedMinutes(Duration.ofSeconds(30L)) shouldEqual BigInt(1)) and
        (roundedMinutes(Duration.ofMinutes(59L).plusSeconds(30L)) shouldEqual BigInt(60)) and
        (roundedMinutes(Duration.ofHours(49L).plusSeconds(30L)) shouldEqual BigInt(2941)) and
        (roundedMinutes(Duration.ofSeconds(Long.MaxValue, 999999999L)) shouldEqual BigInt("153722867280912930"))
    }
  }

  private val user     = FieldValue.Uuid(UUID.fromString("11111111-1111-4111-8111-111111111111"))
  private val projectA = FieldValue.Uuid(UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"))
  private val projectB = FieldValue.Uuid(UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"))
  private val july     = FieldValue.Month(YearMonth.of(2026, 7))
  private val august   = FieldValue.Month(YearMonth.of(2026, 8))

  private val userPath     = Seq(PathSegment(WorkUnitPivotTableKey.UserKey, user))
  private val projectAPath = userPath :+ PathSegment(WorkUnitPivotTableKey.ProjectKey, projectA)
  private val projectBPath = userPath :+ PathSegment(WorkUnitPivotTableKey.ProjectKey, projectB)
  private val julyPath     = Seq(PathSegment(WorkUnitPivotTableKey.MonthYearKey, july))
  private val augustPath   = Seq(PathSegment(WorkUnitPivotTableKey.MonthYearKey, august))

  private val leafCoordinate       = Coordinate(CoordinateKind.Leaf, Seq.empty)
  private val grandTotalCoordinate = Coordinate(CoordinateKind.GrandTotal, Seq.empty)
  private val julyCoordinate       = Coordinate(CoordinateKind.Leaf, julyPath)
  private val augustCoordinate     = Coordinate(CoordinateKind.Leaf, augustPath)

  private val canonicalRequest = pivotRequest(
    rows = Seq(WorkUnitPivotTableKey.UserKey, WorkUnitPivotTableKey.ProjectKey),
    columns = Seq(WorkUnitPivotTableKey.MonthYearKey),
    values = Seq(WorkUnitPivotTableValue.DurationSum, WorkUnitPivotTableValue.DurationAvg, WorkUnitPivotTableValue.EntriesCount)
  )

  private val canonicalPresentation = presentation(
    fieldLabels = Map(
      WorkUnitPivotTableKey.UserKey      -> "Person",
      WorkUnitPivotTableKey.ProjectKey   -> "Initiative",
      WorkUnitPivotTableKey.ClientKey    -> "Account",
      WorkUnitPivotTableKey.YearKey      -> "Year",
      WorkUnitPivotTableKey.MonthYearKey -> "Month",
      WorkUnitPivotTableKey.DateKey      -> "Date",
      WorkUnitPivotTableKey.EntryTypeKey -> "Entry type"
    ),
    memberLabels = Seq(
      MemberLabel(WorkUnitPivotTableKey.UserKey, user, "User Zulu"),
      MemberLabel(WorkUnitPivotTableKey.ProjectKey, projectA, "Project Zulu"),
      MemberLabel(WorkUnitPivotTableKey.ProjectKey, projectB, "Project Alpha"),
      MemberLabel(WorkUnitPivotTableKey.MonthYearKey, july, "Zulu July"),
      MemberLabel(WorkUnitPivotTableKey.MonthYearKey, august, "Alpha August")
    )
  )

  private val canonicalResponse = pivotResponse(
    Seq(
      completeCell(CoordinateKind.Leaf, projectAPath, CoordinateKind.Leaf, julyPath, 120, 120, 1),
      completeCell(CoordinateKind.Leaf, projectAPath, CoordinateKind.Leaf, augustPath, 180, 180, 1),
      completeCell(CoordinateKind.Leaf, projectAPath, CoordinateKind.GrandTotal, Seq.empty, 300, 150, 2),
      completeCell(CoordinateKind.Leaf, projectBPath, CoordinateKind.Leaf, julyPath, 60, 60, 1),
      completeCell(CoordinateKind.Leaf, projectBPath, CoordinateKind.GrandTotal, Seq.empty, 60, 60, 1),
      completeCell(CoordinateKind.Subtotal, userPath, CoordinateKind.Leaf, julyPath, 180, 90, 2),
      completeCell(CoordinateKind.Subtotal, userPath, CoordinateKind.Leaf, augustPath, 180, 180, 1),
      completeCell(CoordinateKind.Subtotal, userPath, CoordinateKind.GrandTotal, Seq.empty, 360, 120, 3),
      completeCell(CoordinateKind.GrandTotal, Seq.empty, CoordinateKind.Leaf, julyPath, 180, 90, 2),
      completeCell(CoordinateKind.GrandTotal, Seq.empty, CoordinateKind.Leaf, augustPath, 180, 180, 1),
      completeCell(CoordinateKind.GrandTotal, Seq.empty, CoordinateKind.GrandTotal, Seq.empty, 360, 120, 3)
    )
  )

  private def pivotRequest(
    rows: Seq[WorkUnitPivotTableKey],
    columns: Seq[WorkUnitPivotTableKey],
    values: Seq[WorkUnitPivotTableValue]
  ): Request =
    Request(rows, columns, values, Seq.empty, Options(rowSubtotals = true, columnSubtotals = true, grandTotals = true))

  private def presentation(
    fieldLabels: Map[WorkUnitPivotTableKey, String] = WorkUnitPivotTableKey.values.map(key => key -> key.entryName).toMap,
    memberLabels: Seq[MemberLabel] = Seq.empty,
    noTaskLabel: String = "No task"
  ): Presentation =
    Presentation(
      fieldLabels = fieldLabels,
      measureLabels = WorkUnitPivotTableValue.values.map(value => value -> value.entryName).toMap,
      subtotalLabel = "Subtotal",
      grandTotalLabel = "All",
      noClientLabel = "No account",
      noTaskLabel = noTaskLabel,
      memberLabels = memberLabels
    )

  private def pivotResponse(cells: Seq[Cell], maxDisplayValueCellCount: Long = 10000L): Response =
    Response(cells, Seq.empty, ResultMetadata(cells.size.toLong, cells.size.toLong, maxDisplayValueCellCount))

  private def monthSegment(month: YearMonth): PathSegment =
    PathSegment(WorkUnitPivotTableKey.MonthYearKey, FieldValue.Month(month))

  private def completeCell(
    rowKind: CoordinateKind,
    rowPath: Seq[PathSegment],
    columnKind: CoordinateKind,
    columnPath: Seq[PathSegment],
    sumMinutes: Long,
    averageMinutes: Long,
    entriesCount: Long
  ): Cell =
    pivotCell(
      Coordinate(rowKind, rowPath),
      Coordinate(columnKind, columnPath),
      MetricValues(
        durationSum = Some(Duration.ofMinutes(sumMinutes)),
        durationAvg = Some(Some(Duration.ofMinutes(averageMinutes))),
        entriesCount = Some(entriesCount)
      )
    )

  private def pivotCell(
    row: Coordinate,
    column: Coordinate,
    values: MetricValues
  ): Cell = Cell(row, column, values)

  private def metrics(
    sum: Option[Duration] = None,
    average: Option[Option[Duration]] = None,
    count: Option[Long]
  ): MetricValues = MetricValues(sum, average, count)

  private def duration(minutes: Long): Option[Value] = Some(DurationMinutes(BigInt(minutes)))
  private def count(value: Long): Option[Value]      = Some(Count(value))
}
