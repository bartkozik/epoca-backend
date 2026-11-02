package pl.iterators.epoca.`export`.services

import cats.effect.IO
import io.circe.Json
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.export.domain.SpreadsheetDomain.{SpreadsheetFormat, SpreadsheetName}
import pl.iterators.epoca.export.domain.WorkUnitPivotExport.ValidationError
import pl.iterators.epoca.export.domain.{PivotExportArtifact, PivotExportArtifactStore, PivotExportProjection, WorkUnitPivotExport}
import pl.iterators.epoca.export.services.ExportWorkUnitPivotTableService.ExportResult
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.stats.domain.WorkUnitPivotQuery.{GlobalFilters, InvalidConfigurationError, Request, ResultTooLargeError}
import pl.iterators.epoca.stats.services.WorkUnitPivotQueryService
import pl.iterators.epoca.stats.services.WorkUnitPivotQueryService.Result

class ExportWorkUnitPivotTableService(
  validator: WorkUnitPivotExportRequestValidator,
  workUnitPivotQueryService: WorkUnitPivotQueryService,
  artifactStore: PivotExportArtifactStore) {

  def `export`(
    authContext: AuthContext,
    organizationSlug: OrganizationSlug,
    rawRequest: Json,
    filters: GlobalFilters,
    format: SpreadsheetFormat
  ): IO[ExportResult] =
    exportWithQuery(rawRequest, filters, format)(request => workUnitPivotQueryService.queryValidated(authContext, organizationSlug, request, filters))

  private[epoca] def exportWithQuery(
    rawRequest: Json,
    filters: GlobalFilters,
    format: SpreadsheetFormat
  )(
    runQuery: Request => IO[Result]
  ): IO[ExportResult] =
    validator.validate(rawRequest, filters) match {
      case Left(ValidationError.InvalidQuery(error))        => IO.pure(ExportResult.InvalidConfiguration(error))
      case Left(ValidationError.InvalidPresentation(error)) => IO.pure(ExportResult.InvalidExportRequest(error))
      case Left(ValidationError.MalformedEnvelope)          => IO.pure(ExportResult.MalformedRequest)
      case Right(request) =>
        runQuery(request.query).flatMap {
          case Result.Ok(response) if response.cells.isEmpty => IO.pure(ExportResult.NoContent)
          case Result.Ok(response) =>
            PivotExportProjection.build(response, request.query, request.projection, filters.dateFrom, request.presentation) match {
              case Left(error) => IO.pure(ExportResult.TooLarge(error))
              case Right(table) =>
                val fileName = ExportWorkUnitPivotTableService.spreadsheetName(filters, format)
                artifactStore
                  .generate(fileName, format, table, request.presentation.measureLabels)
                  .map(ExportResult.Ok)
            }
          case Result.InvalidConfiguration(error) => IO.pure(ExportResult.InvalidConfiguration(error))
          case Result.TooLarge(error)             => IO.pure(ExportResult.TooLarge(error))
          case Result.OrganizationNotFound        => IO.pure(ExportResult.OrganizationNotFound)
          case Result.NoAccessToOrganization      => IO.pure(ExportResult.NoAccessToOrganization)
          case Result.InsufficientRole            => IO.pure(ExportResult.InsufficientRole)
        }
    }
}

object ExportWorkUnitPivotTableService {
  sealed trait ExportResult
  object ExportResult {
    final case class Ok(artifact: PivotExportArtifact)                                     extends ExportResult
    case object NoContent                                                                  extends ExportResult
    final case class InvalidConfiguration(error: InvalidConfigurationError)                extends ExportResult
    final case class InvalidExportRequest(error: WorkUnitPivotExport.InvalidExportRequest) extends ExportResult
    final case class TooLarge(error: ResultTooLargeError)                                  extends ExportResult
    case object OrganizationNotFound                                                       extends ExportResult
    case object NoAccessToOrganization                                                     extends ExportResult
    case object InsufficientRole                                                           extends ExportResult
    case object MalformedRequest                                                           extends ExportResult
  }

  private def spreadsheetName(filters: GlobalFilters, format: SpreadsheetFormat): SpreadsheetName = {
    val dateTo = filters.dateTo.fold("open")(_.toString)
    SpreadsheetName(s"pivot_table_${filters.dateFrom}_$dateTo.${SpreadsheetFormat.extension(format)}")
  }
}
