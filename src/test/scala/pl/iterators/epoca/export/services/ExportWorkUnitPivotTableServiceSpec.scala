package pl.iterators.epoca.`export`.services

import cats.effect.IO
import io.circe.Json
import org.mockito.Mockito.verifyNoInteractions
import pl.iterators.epoca.client.domain.ClientDomain.ClientId
import pl.iterators.epoca.export.domain.PivotExportProjection.Value.Count
import pl.iterators.epoca.export.domain.PivotExportProjection.{ColumnHeader, DataRow, RowHeader, Table}
import pl.iterators.epoca.export.domain.SpreadsheetDomain.{SpreadsheetFormat, SpreadsheetName}
import pl.iterators.epoca.export.domain.WorkUnitPivotExport.{
  ExportViolation,
  ExportViolationCode,
  InvalidExportRequest,
  MemberLabel,
  Presentation,
  Projection,
  ValidatedRequest,
  ValidationError
}
import pl.iterators.epoca.export.domain.{PivotExportArtifact, PivotExportArtifactStore}
import pl.iterators.epoca.export.services.ExportWorkUnitPivotTableService.ExportResult
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.project.domain.ProjectDomain.ProjectId
import pl.iterators.epoca.stats.domain.StatsDomain.{WorkUnitPivotTableKey, WorkUnitPivotTableValue}
import pl.iterators.epoca.stats.domain.WorkUnitPivotQuery.{
  Cell,
  Coordinate,
  CoordinateKind,
  FieldValue,
  GlobalFilters,
  InvalidConfigurationError,
  MetricValues,
  Options,
  PathSegment,
  Request,
  Response,
  ResultMetadata,
  ResultTooLargeError,
  Violation,
  ViolationCode
}
import pl.iterators.epoca.stats.services.WorkUnitPivotQueryService
import pl.iterators.epoca.stats.services.WorkUnitPivotQueryService.Result
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.utils.ServiceSpec
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.WorkUnitDate

import java.io.IOException
import java.nio.file.Paths
import java.time.{LocalDate, YearMonth}
import java.util.UUID

class ExportWorkUnitPivotTableServiceSpec extends ServiceSpec {
  trait TestCase extends BaseScope {
    val validator: WorkUnitPivotExportRequestValidator       = vmock[WorkUnitPivotExportRequestValidator]
    val workUnitPivotQueryService: WorkUnitPivotQueryService = vmock[WorkUnitPivotQueryService]
    val artifactStore: PivotExportArtifactStore              = vmock[PivotExportArtifactStore]

    val sut: ExportWorkUnitPivotTableService = wire[ExportWorkUnitPivotTableService]

    val organizationSlug: OrganizationSlug = allGenerators[OrganizationSlug].normal.generate
    val rawRequest: Json                   = Json.obj("export" -> Json.fromString("raw"))
    val userValue: FieldValue              = FieldValue.Uuid(UUID.fromString("10000000-0000-4000-8000-000000000001"))
    val monthValue: FieldValue             = FieldValue.Month(YearMonth.of(2026, 8))
    val rowCoordinate: Coordinate          = Coordinate(CoordinateKind.Leaf, Seq(PathSegment(WorkUnitPivotTableKey.UserKey, userValue)))
    val columnCoordinate: Coordinate       = Coordinate(CoordinateKind.Leaf, Seq(PathSegment(WorkUnitPivotTableKey.MonthYearKey, monthValue)))
    val query: Request = Request(
      rows = Seq(WorkUnitPivotTableKey.UserKey),
      columns = Seq(WorkUnitPivotTableKey.MonthYearKey),
      values = Seq(WorkUnitPivotTableValue.EntriesCount),
      filters = Seq.empty,
      options = Options(rowSubtotals = false, columnSubtotals = false, grandTotals = false)
    )
    val presentation: Presentation = Presentation(
      fieldLabels = WorkUnitPivotTableKey.values.iterator.map(key => key -> s"field-${key.entryName}").toMap,
      measureLabels = WorkUnitPivotTableValue.values.iterator.map(value => value -> s"measure-${value.entryName}").toMap,
      subtotalLabel = "Subtotal",
      grandTotalLabel = "Grand total",
      noClientLabel = "No client",
      noTaskLabel = "No task",
      memberLabels =
        Seq(MemberLabel(WorkUnitPivotTableKey.UserKey, userValue, "Alex"), MemberLabel(WorkUnitPivotTableKey.MonthYearKey, monthValue, "August 2026"))
    )
    val validatedRequest: ValidatedRequest = ValidatedRequest(query, Some(Projection(LocalDate.of(2026, 8, 31))), presentation)
    val closedFilters: GlobalFilters = GlobalFilters(
      dateFrom = WorkUnitDate(LocalDate.of(2026, 8, 1)),
      dateTo = Some(WorkUnitDate(LocalDate.of(2026, 8, 31))),
      projectIds = Seq(allGenerators[ProjectId].normal.generate),
      userIds = Seq(allGenerators[UserId].normal.generate),
      clientIds = Seq(allGenerators[ClientId].normal.generate)
    )
    val openFilters: GlobalFilters = closedFilters.copy(dateTo = None)
    val response: Response = Response(
      cells = Seq(Cell(rowCoordinate, columnCoordinate, MetricValues(None, None, Some(2L)))),
      filterMembers = Seq.empty,
      result = ResultMetadata(cellCount = 1L, displayValueCellCount = 1L, maxDisplayValueCellCount = 10000L)
    )
    val emptyResponse: Response = response.copy(cells = Seq.empty, result = response.result.copy(cellCount = 0L, displayValueCellCount = 0L))
    val expectedTable: Table = Table(
      rowFieldLabels = Vector("field-UserKey"),
      columns = Vector(ColumnHeader(columnCoordinate, Vector("August 2026"), WorkUnitPivotTableValue.EntriesCount)),
      rows = Vector(DataRow(RowHeader(Vector(Some("Alex")), CoordinateKind.Leaf), Vector(Some(Count(2L)))))
    )

    def artifact(fileName: SpreadsheetName, format: SpreadsheetFormat): PivotExportArtifact =
      PivotExportArtifact(Paths.get("/tmp", fileName), fileName, format, length = 42L)

    def stubValid(result: Result, filters: GlobalFilters = closedFilters): Unit = {
      when(validator.validate(rawRequest)).thenReturn(Right(validatedRequest))
      val _ = when(workUnitPivotQueryService.queryValidated(authUser, organizationSlug, query, filters)).thenReturn(IO.pure(result))
    }

    def verifyValidatedQuery(filters: GlobalFilters = closedFilters): Unit = {
      val order = org.mockito.Mockito.inOrder(validator, workUnitPivotQueryService)
      val _     = order.verify(validator).validate(rawRequest)
      val _     = order.verify(workUnitPivotQueryService).queryValidated(authUser, organizationSlug, query, filters)
    }

    def verifyMaterialized(
      fileName: SpreadsheetName,
      format: SpreadsheetFormat,
      filters: GlobalFilters = closedFilters
    ): Unit = {
      val order = org.mockito.Mockito.inOrder(validator, workUnitPivotQueryService, artifactStore)
      val _     = order.verify(validator).validate(rawRequest)
      val _     = order.verify(workUnitPivotQueryService).queryValidated(authUser, organizationSlug, query, filters)
      val _     = order.verify(artifactStore).generate(fileName, format, expectedTable, presentation.measureLabels)
    }
  }

  "export" should {
    "return the exact query validation error before query execution or artifact generation" in new TestCase {
      val error = InvalidConfigurationError(violations = Seq(Violation("values", ViolationCode.EMPTY_VALUES)))
      when(validator.validate(rawRequest)).thenReturn(Left(ValidationError.InvalidQuery(error)))

      sut
        .`export`(authUser, organizationSlug, rawRequest, closedFilters, SpreadsheetFormat.Csv)
        .asserting(_ shouldEqual ExportResult.InvalidConfiguration(error))

      verify(validator).validate(rawRequest)
      verifyNoInteractions(workUnitPivotQueryService, artifactStore)
    }

    "return the exact presentation validation error before query execution or artifact generation" in new TestCase {
      val error =
        InvalidExportRequest(violations = Seq(ExportViolation("presentation.subtotalLabel", ExportViolationCode.INVALID_PRESENTATION_VALUE)))
      when(validator.validate(rawRequest)).thenReturn(Left(ValidationError.InvalidPresentation(error)))

      sut
        .`export`(authUser, organizationSlug, rawRequest, closedFilters, SpreadsheetFormat.Csv)
        .asserting(_ shouldEqual ExportResult.InvalidExportRequest(error))

      verify(validator).validate(rawRequest)
      verifyNoInteractions(workUnitPivotQueryService, artifactStore)
    }

    "preserve malformed-envelope classification before query execution or artifact generation" in new TestCase {
      when(validator.validate(rawRequest)).thenReturn(Left(ValidationError.MalformedEnvelope))

      sut
        .`export`(authUser, organizationSlug, rawRequest, closedFilters, SpreadsheetFormat.Csv)
        .asserting(_ shouldEqual ExportResult.MalformedRequest)

      verify(validator).validate(rawRequest)
      verifyNoInteractions(workUnitPivotQueryService, artifactStore)
    }

    "map every non-OK authoritative query result without artifact generation" in new TestCase {
      val invalid  = InvalidConfigurationError(violations = Seq(Violation("values", ViolationCode.EMPTY_VALUES)))
      val tooLarge = ResultTooLargeError(estimatedDisplayValueCellCount = 10001L, maxDisplayValueCellCount = 10000L)
      val mappings = Seq[(Result, ExportResult)](
        Result.InvalidConfiguration(invalid) -> ExportResult.InvalidConfiguration(invalid),
        Result.TooLarge(tooLarge)            -> ExportResult.TooLarge(tooLarge),
        Result.OrganizationNotFound          -> ExportResult.OrganizationNotFound,
        Result.NoAccessToOrganization        -> ExportResult.NoAccessToOrganization,
        Result.InsufficientRole              -> ExportResult.InsufficientRole
      )
      when(validator.validate(rawRequest)).thenReturn(Right(validatedRequest))
      mappings.foreach { case (queryResult, expected) =>
        when(workUnitPivotQueryService.queryValidated(authUser, organizationSlug, query, closedFilters)).thenReturn(IO.pure(queryResult))
        sut.`export`(authUser, organizationSlug, rawRequest, closedFilters, SpreadsheetFormat.Csv).asserting(_ shouldEqual expected)
      }

      verify(validator, times(mappings.size)).validate(rawRequest)
      verify(workUnitPivotQueryService, times(mappings.size)).queryValidated(authUser, organizationSlug, query, closedFilters)
      verifyNoInteractions(artifactStore)
    }

    "return NoContent for an empty fresh snapshot without creating an artifact" in new TestCase {
      stubValid(Result.Ok(emptyResponse))

      sut
        .`export`(authUser, organizationSlug, rawRequest, closedFilters, SpreadsheetFormat.Csv)
        .asserting(_ shouldEqual ExportResult.NoContent)

      verifyValidatedQuery()
      verifyNoInteractions(artifactStore)
    }

    "complete a supplied Month-only projection before creating the artifact" in new TestCase {
      val months = (2 to 8).map(month => YearMonth.of(2026, month))
      val labels = months.map { month =>
        MemberLabel(WorkUnitPivotTableKey.MonthYearKey, FieldValue.Month(month), s"localized-$month")
      }
      val localizedPresentation = presentation.copy(memberLabels = presentation.memberLabels ++ labels)
      val projectedRequest      = ValidatedRequest(query, Some(Projection(LocalDate.of(2026, 8, 31))), localizedPresentation)
      val projectedFilters      = closedFilters.copy(dateFrom = WorkUnitDate(LocalDate.of(2026, 2, 1)))
      val projectedColumns = months.map { month =>
        val coordinate =
          if (month == YearMonth.of(2026, 8)) columnCoordinate
          else Coordinate(CoordinateKind.Leaf, Seq(PathSegment(WorkUnitPivotTableKey.MonthYearKey, FieldValue.Month(month))))
        ColumnHeader(coordinate, Vector(s"localized-$month"), WorkUnitPivotTableValue.EntriesCount)
      }.toVector
      val projectedTable = Table(
        rowFieldLabels = Vector("field-UserKey"),
        columns = projectedColumns,
        rows = Vector(DataRow(RowHeader(Vector(Some("Alex")), CoordinateKind.Leaf), Vector.fill(6)(None) :+ Some(Count(2L))))
      )
      val fileName  = SpreadsheetName("pivot_table_2026-02-01_2026-08-31.csv")
      val generated = artifact(fileName, SpreadsheetFormat.Csv)
      when(validator.validate(rawRequest)).thenReturn(Right(projectedRequest))
      when(workUnitPivotQueryService.queryValidated(authUser, organizationSlug, query, projectedFilters)).thenReturn(IO.pure(Result.Ok(response)))
      when(artifactStore.generate(fileName, SpreadsheetFormat.Csv, projectedTable, localizedPresentation.measureLabels))
        .thenReturn(IO.pure(generated))

      sut
        .`export`(authUser, organizationSlug, rawRequest, projectedFilters, SpreadsheetFormat.Csv)
        .asserting(_ shouldEqual ExportResult.Ok(generated))

      locally {
        val order = org.mockito.Mockito.inOrder(validator, workUnitPivotQueryService, artifactStore)
        val _     = order.verify(validator).validate(rawRequest)
        val _     = order.verify(workUnitPivotQueryService).queryValidated(authUser, organizationSlug, query, projectedFilters)
        val _     = order.verify(artifactStore).generate(fileName, SpreadsheetFormat.Csv, projectedTable, localizedPresentation.measureLabels)
      }
    }

    "return the existing TooLarge result before artifact generation when Month completion exceeds the response limit" in new TestCase {
      val projectedFilters = closedFilters.copy(dateFrom = WorkUnitDate(LocalDate.of(2026, 2, 1)))
      val limitedResponse  = response.copy(result = response.result.copy(maxDisplayValueCellCount = 6L))
      val tooLarge         = ResultTooLargeError(estimatedDisplayValueCellCount = 7L, maxDisplayValueCellCount = 6L)
      when(validator.validate(rawRequest)).thenReturn(Right(validatedRequest))
      when(workUnitPivotQueryService.queryValidated(authUser, organizationSlug, query, projectedFilters))
        .thenReturn(IO.pure(Result.Ok(limitedResponse)))

      sut
        .`export`(authUser, organizationSlug, rawRequest, projectedFilters, SpreadsheetFormat.Xlsx)
        .asserting(_ shouldEqual ExportResult.TooLarge(tooLarge))

      locally {
        val order = org.mockito.Mockito.inOrder(validator, workUnitPivotQueryService)
        val _     = order.verify(validator).validate(rawRequest)
        val _     = order.verify(workUnitPivotQueryService).queryValidated(authUser, organizationSlug, query, projectedFilters)
      }
      verifyNoInteractions(artifactStore)
    }

    "project and materialize a closed-range CSV from exactly one authoritative query snapshot" in new TestCase {
      val fileName  = SpreadsheetName("pivot_table_2026-08-01_2026-08-31.csv")
      val generated = artifact(fileName, SpreadsheetFormat.Csv)
      stubValid(Result.Ok(response))
      when(artifactStore.generate(fileName, SpreadsheetFormat.Csv, expectedTable, presentation.measureLabels)).thenReturn(IO.pure(generated))

      sut
        .`export`(authUser, organizationSlug, rawRequest, closedFilters, SpreadsheetFormat.Csv)
        .asserting(_ shouldEqual ExportResult.Ok(generated))

      verifyMaterialized(fileName, SpreadsheetFormat.Csv)
    }

    "use the literal open suffix and the XLSX encoder path when dateTo is absent" in new TestCase {
      val fileName  = SpreadsheetName("pivot_table_2026-08-01_open.xlsx")
      val generated = artifact(fileName, SpreadsheetFormat.Xlsx)
      stubValid(Result.Ok(response), openFilters)
      when(artifactStore.generate(fileName, SpreadsheetFormat.Xlsx, expectedTable, presentation.measureLabels)).thenReturn(IO.pure(generated))

      sut
        .`export`(authUser, organizationSlug, rawRequest, openFilters, SpreadsheetFormat.Xlsx)
        .asserting(_ shouldEqual ExportResult.Ok(generated))

      verifyMaterialized(fileName, SpreadsheetFormat.Xlsx, openFilters)
    }

    "propagate artifact generation failure without returning a partial successful result" in new TestCase {
      val fileName = SpreadsheetName("pivot_table_2026-08-01_2026-08-31.csv")
      val failure  = new IOException("encoder failed")
      stubValid(Result.Ok(response))
      when(artifactStore.generate(fileName, SpreadsheetFormat.Csv, expectedTable, presentation.measureLabels)).thenReturn(IO.raiseError(failure))

      sut
        .`export`(authUser, organizationSlug, rawRequest, closedFilters, SpreadsheetFormat.Csv)
        .attempt
        .asserting(_ shouldEqual Left(failure))

      verifyMaterialized(fileName, SpreadsheetFormat.Csv)
    }
  }
}
