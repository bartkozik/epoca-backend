package pl.iterators.epoca.`export`.services

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import fs2.Stream
import io.circe.Decoder
import io.scalaland.chimney.dsl._
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.client.domain.ClientDomain.{ClientId, ClientName}
import pl.iterators.epoca.export.domain.Spreadsheet
import pl.iterators.epoca.export.domain.SpreadsheetDomain.{SpreadsheetColumnName, SpreadsheetFormat, SpreadsheetName}
import pl.iterators.epoca.export.services.ExportWorkUnitService.ExportWorkUnitResult._
import pl.iterators.epoca.export.services.ExportWorkUnitService.{ExportWorkUnitRequest, ExportWorkUnitResult, WorkUnitDtoFlat, spreadsheetName}
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.project.domain.ProjectDomain.{ProjectId, ProjectName}
import pl.iterators.epoca.user.domain.UserDomain.{UserFullName, UserId}
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.service.SealedMonadServiceIODBIO
import pl.iterators.epoca.workunit.domain.WorkUnitDomain._
import pl.iterators.epoca.workunit.repositories.WorkUnitRepository.WorkUnitFilters
import pl.iterators.epoca.workunit.services.WorkUnitDtoService.WorkUnitDto
import pl.iterators.epoca.workunit.services.WorkUnitListService
import pl.iterators.epoca.workunit.services.WorkUnitListService.WorkUnitListResult
import pl.iterators.sealedmonad.syntax._

class ExportWorkUnitService(workUnitListService: WorkUnitListService)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIODBIO[ExportWorkUnitResult](ioRuntime)
    with LoggingSupport {

  def `export`(
    authContext: AuthContext,
    organizationSlug: OrganizationSlug,
    request: ExportWorkUnitRequest,
    format: SpreadsheetFormat
  ): IO[ExportWorkUnitResult] =
    (for {
      workUnits   <- listWorkUnitsFlat(authContext, organizationSlug, request.filter)
      spreadsheet <- buildSpreadsheet(workUnits, request)
      stream      <- toStream(spreadsheet, format)
    } yield ExportWorkUnitResult.Ok(spreadsheetName, stream, format)).run

  private def listWorkUnitsFlat(
    authContext: AuthContext,
    organizationSlug: OrganizationSlug,
    filter: WorkUnitFilters
  ): StepIO[Seq[WorkUnitDtoFlat]] =
    workUnitListService.listForExport(authContext, organizationSlug, filter).attemptF {
      case WorkUnitListResult.Ok(data)               => IO(Right(data.workUnits.results.map(WorkUnitDtoFlat.from)))
      case WorkUnitListResult.OrganizationNotFound   => IO(Left(OrganizationNotFound))
      case WorkUnitListResult.NoAccessToOrganization => IO(Left(NoAccessToOrganization))
      case WorkUnitListResult.OverrideForbidden      => IO(Left(OverrideForbidden))
    }

  private def buildSpreadsheet(workUnits: Seq[WorkUnitDtoFlat], request: ExportWorkUnitRequest): StepIO[Spreadsheet] =
    IO(Spreadsheet.from(spreadsheetName, workUnits, request.columnNames.asMap)).seal

  private def toStream(spreadsheet: Spreadsheet, format: SpreadsheetFormat): StepIO[Stream[IO, Byte]] =
    IO(spreadsheet.toStream(format)).seal
}

object ExportWorkUnitService {
  case class ExportWorkUnitRequest(
    columnNames: ExportWorkUnitColumnNames,
    ids: Seq[WorkUnitId],
    dateFrom: Option[WorkUnitDate],
    dateTo: Option[WorkUnitDate],
    projectIds: Seq[ProjectId],
    userIds: Seq[UserId],
    clientIds: Seq[ClientId]) {
    lazy val filter: WorkUnitFilters = WorkUnitFilters(
      ids = Option.when(ids.nonEmpty)(ids),
      dateFrom = dateFrom,
      dateTo = dateTo,
      projectIds = Option.when(projectIds.nonEmpty)(projectIds),
      userIds = Option.when(userIds.nonEmpty)(userIds),
      clientIds = Option.when(clientIds.nonEmpty)(clientIds)
    )
  }

  object ExportWorkUnitRequest {
    import pl.iterators.epoca.utils.json.JsonProtocol._

    implicit val decoder: Decoder[ExportWorkUnitRequest] = Decoder.instance { cursor =>
      for {
        columnNames <- cursor.get[ExportWorkUnitColumnNames]("columnNames")
        ids         <- cursor.get[Seq[WorkUnitId]]("ids")
        dateFrom    <- cursor.get[Option[WorkUnitDate]]("dateFrom")
        dateTo      <- cursor.get[Option[WorkUnitDate]]("dateTo")
        projectIds  <- cursor.get[Seq[ProjectId]]("projectIds")
        userIds     <- cursor.get[Seq[UserId]]("userIds")
        clientIds   <- cursor.get[Seq[ClientId]]("clientIds")
      } yield ExportWorkUnitRequest(columnNames, ids, dateFrom, dateTo, projectIds, userIds, clientIds)
    }
  }

  case class ExportWorkUnitColumnNames(
    date: Option[SpreadsheetColumnName],
    clientName: Option[SpreadsheetColumnName],
    projectName: Option[SpreadsheetColumnName],
    userFullName: Option[SpreadsheetColumnName],
    taskId: Option[SpreadsheetColumnName],
    description: Option[SpreadsheetColumnName],
    duration: Option[SpreadsheetColumnName],
    createdAt: Option[SpreadsheetColumnName],
    modifiedAt: Option[SpreadsheetColumnName],
    entryType: Option[SpreadsheetColumnName]) {
    lazy val asMap: Map[String, SpreadsheetColumnName] =
      Map(
        "date"         -> this.date.getOrElse(SpreadsheetColumnName("date")),
        "clientName"   -> this.clientName.getOrElse(SpreadsheetColumnName("clientName")),
        "projectName"  -> this.projectName.getOrElse(SpreadsheetColumnName("projectName")),
        "userFullName" -> this.userFullName.getOrElse(SpreadsheetColumnName("userFullName")),
        "taskId"       -> this.taskId.getOrElse(SpreadsheetColumnName("taskId")),
        "description"  -> this.description.getOrElse(SpreadsheetColumnName("description")),
        "duration"     -> this.duration.getOrElse(SpreadsheetColumnName("duration")),
        "createdAt"    -> this.createdAt.getOrElse(SpreadsheetColumnName("createdAt")),
        "modifiedAt"   -> this.modifiedAt.getOrElse(SpreadsheetColumnName("modifiedAt")),
        "entryType"    -> this.entryType.getOrElse(SpreadsheetColumnName("entryType"))
      )
  }

  sealed trait ExportWorkUnitResult
  object ExportWorkUnitResult {
    case class Ok(
      filename: SpreadsheetName,
      stream: Stream[IO, Byte],
      format: SpreadsheetFormat)
        extends ExportWorkUnitResult
    case object OrganizationNotFound   extends ExportWorkUnitResult
    case object NoAccessToOrganization extends ExportWorkUnitResult
    case object OverrideForbidden      extends ExportWorkUnitResult
  }

  private final val spreadsheetName = SpreadsheetName("epoca-work-units.xlsx")
  case class WorkUnitDtoFlat(
    date: WorkUnitDate,
    clientName: Option[ClientName],
    projectName: Option[ProjectName],
    userFullName: Option[UserFullName],
    taskId: Option[WorkUnitTaskId],
    description: WorkUnitDescription,
    duration: WorkUnitDuration,
    createdAt: WorkUnitCreatedAt,
    modifiedAt: WorkUnitModifiedAt,
    entryType: WorkUnitEntryType)
  object WorkUnitDtoFlat {
    def from(workUnitDto: WorkUnitDto): WorkUnitDtoFlat =
      workUnitDto
        .into[WorkUnitDtoFlat]
        .withFieldComputed(_.clientName, _.effectiveProjectClient.map(_.clientName))
        .withFieldComputed(_.projectName, _.effectiveProjectClient.map(_.projectName))
        .withFieldComputed(_.taskId, _.effectiveTaskId)
        .transform

  }

}
