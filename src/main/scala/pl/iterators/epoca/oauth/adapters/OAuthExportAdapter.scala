package pl.iterators.epoca.oauth.adapters

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import io.circe.Json
import pl.iterators.epoca.client.repositories.ClientRepository
import pl.iterators.epoca.export.domain.Spreadsheet
import pl.iterators.epoca.export.domain.SpreadsheetDomain.{SpreadsheetFormat, SpreadsheetName}
import pl.iterators.epoca.export.services.ExportWorkUnitPivotTableService
import pl.iterators.epoca.export.services.ExportWorkUnitPivotTableService.ExportResult
import pl.iterators.epoca.export.services.ExportWorkUnitService.{ExportWorkUnitRequest, ExportWorkUnitResult, WorkUnitDtoFlat}
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.organization.repositories.OrganizationRepository
import pl.iterators.epoca.project.repositories.ProjectRepository
import pl.iterators.epoca.stats.domain.WorkUnitPivotQuery.GlobalFilters
import pl.iterators.epoca.stats.services.WorkUnitPivotQueryService
import pl.iterators.epoca.user.repositories.UserRepository
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.transactor.IsolationLevel
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.WorkUnitState
import pl.iterators.epoca.workunit.repositories.WorkUnitRepository
import pl.iterators.epoca.workunit.services.WorkUnitDtoService
import slick.dbio.DBIO

import scala.concurrent.ExecutionContext

trait OAuthExporter {
  def exportWorkUnits(
    accessPolicy: OAuthAccessPolicy,
    organizationSlug: OrganizationSlug,
    request: ExportWorkUnitRequest,
    format: SpreadsheetFormat
  ): IO[ExportWorkUnitResult]
  def exportPivot(
    accessPolicy: OAuthAccessPolicy,
    organizationSlug: OrganizationSlug,
    rawRequest: Json,
    filters: GlobalFilters,
    format: SpreadsheetFormat
  ): IO[ExportResult]
}

final class OAuthExportAdapter(
  transactor: PostgresIOTransactor,
  organizationRepository: OrganizationRepository,
  projectRepository: ProjectRepository,
  clientRepository: ClientRepository,
  userRepository: UserRepository,
  workUnitRepository: WorkUnitRepository,
  pivotQueryService: WorkUnitPivotQueryService,
  pivotExportService: ExportWorkUnitPivotTableService
)(implicit runtime: IORuntime)
    extends OAuthExporter {
  import OAuthExportAdapter._

  private implicit val ec: ExecutionContext = runtime.compute
  private val workUnitDtoService            = new WorkUnitDtoService(clientRepository, userRepository, projectRepository)

  def exportWorkUnits(
    accessPolicy: OAuthAccessPolicy,
    organizationSlug: OrganizationSlug,
    request: ExportWorkUnitRequest,
    format: SpreadsheetFormat
  ): IO[ExportWorkUnitResult] =
    transactor.executeTransactionally(IsolationLevel.RepeatableRead) {
      OAuthReportAdapter.resolveAction(organizationRepository, projectRepository, accessPolicy, organizationSlug).flatMap {
        case None => DBIO.successful(ExportWorkUnitResult.OrganizationNotFound)
        case Some(policy) =>
          val filters = request.filter.copy(
            organizationId = Some(policy.organizationId),
            states = Some(WorkUnitState.liveStates),
            reportAuthorizedProjectIds = Some(policy.allowedProjectIds.toSeq),
            pageSizeAndNumber = None
          )
          for {
            workUnits <- workUnitRepository.list(filters)
            dtos      <- workUnitDtoService.prepareDtos(workUnits)
          } yield {
            val rows        = dtos.map(WorkUnitDtoFlat.from)
            val spreadsheet = Spreadsheet.from(WorkUnitSpreadsheetName, rows, request.columnNames.asMap)
            ExportWorkUnitResult.Ok(WorkUnitSpreadsheetName, spreadsheet.toStream(format), format)
          }
      }
    }

  def exportPivot(
    accessPolicy: OAuthAccessPolicy,
    organizationSlug: OrganizationSlug,
    rawRequest: Json,
    filters: GlobalFilters,
    format: SpreadsheetFormat
  ): IO[ExportResult] =
    pivotExportService.exportWithQuery(rawRequest, filters, format) { query =>
      transactor.executeTransactionally(IsolationLevel.RepeatableRead) {
        OAuthReportAdapter.resolveAction(organizationRepository, projectRepository, accessPolicy, organizationSlug).flatMap {
          case None => DBIO.successful(WorkUnitPivotQueryService.Result.OrganizationNotFound)
          case Some(policy) =>
            pivotQueryService.queryOAuthValidatedAction(policy.organizationId, policy.allowedProjectIds.toSeq, query, filters)
        }
      }
    }

}

object OAuthExportAdapter {
  private val WorkUnitSpreadsheetName = SpreadsheetName("epoca-work-units.xlsx")
}
