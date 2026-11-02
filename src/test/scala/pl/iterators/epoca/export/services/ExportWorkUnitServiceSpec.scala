package pl.iterators.epoca.`export`.services

import cats.effect.IO
import fs2.Stream
import io.circe.parser.decode
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import pl.iterators.epoca.client.domain.Client
import pl.iterators.epoca.client.domain.ClientDomain.ClientName
import pl.iterators.epoca.export.domain.SpreadsheetDomain.{SpreadsheetFormat, SpreadsheetName}
import pl.iterators.epoca.export.services.ExportWorkUnitService.ExportWorkUnitRequest.decoder
import pl.iterators.epoca.export.services.ExportWorkUnitService.{
  ExportWorkUnitColumnNames,
  ExportWorkUnitRequest,
  ExportWorkUnitResult,
  WorkUnitDtoFlat
}
import pl.iterators.epoca.organization.domain.Organization
import pl.iterators.epoca.project.domain.Project
import pl.iterators.epoca.project.domain.ProjectDomain.ProjectName
import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.utils.ServiceSpec
import pl.iterators.epoca.utils.db.PaginatedResult
import pl.iterators.epoca.utils.json.JsonProtocol
import pl.iterators.epoca.workunit.domain.WorkUnitDomain._
import pl.iterators.epoca.workunit.services.WorkUnitDtoService.{WorkUnitDto, WorkUnitProjectClientDto}
import pl.iterators.epoca.workunit.services.WorkUnitListService
import pl.iterators.epoca.workunit.services.WorkUnitListService.{WorkUnitListData, WorkUnitListResult}

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.time.Duration

class ExportWorkUnitServiceSpec extends ServiceSpec with JsonProtocol {
  lazy val organizationGenerator: AllGenerators[Organization]     = allGenerators[Organization]
  lazy val clientGenerator: AllGenerators[Client]                 = allGenerators[Client]
  lazy val projectGenerator: AllGenerators[Project]               = allGenerators[Project]
  lazy val workUnitDtoGenerator: AllGenerators[WorkUnitDto]       = allGenerators[WorkUnitDto]
  lazy val requestGenerator: AllGenerators[ExportWorkUnitRequest] = allGenerators[ExportWorkUnitRequest]
  lazy val filename: SpreadsheetName                              = SpreadsheetName("epoca-work-units.xlsx")
  lazy val format: SpreadsheetFormat                              = SpreadsheetFormat.Csv

  private val legacyRequestJson =
    """{"columnNames":{},"ids":[],"dateFrom":null,"dateTo":null,"projectIds":[],"userIds":[],"clientIds":[]}"""
  private val customRequestJson =
    """{"columnNames":{"entryType":"Typ wpisu"},"ids":[],"dateFrom":null,"dateTo":null,"projectIds":[],"userIds":[],"clientIds":[]}"""

  private val allCustomColumnNames =
    Seq("Date", "Client", "Project", "Person", "Task", "Description", "Hours", "Created", "Modified", "Type")
  private lazy val allCustomRequestJson = {
    val keys        = ordinaryColumnNames :+ "entryType"
    val columnNames = keys.zip(allCustomColumnNames).map { case (key, name) => s""""$key":"$name"""" }.mkString(",")
    s"""{"columnNames":{$columnNames},"ids":[],"dateFrom":null,"dateTo":null,"projectIds":[],"userIds":[],"clientIds":[]}"""
  }

  private def exportRequest(json: String): ExportWorkUnitRequest =
    decode[ExportWorkUnitRequest](json).fold(throw _, identity)

  private def streamBytes(stream: Stream[IO, Byte]): Array[Byte] =
    stream.compile.toVector.unsafeRunSync().toArray

  private def csvRows(stream: Stream[IO, Byte]): Seq[Seq[String]] =
    new String(streamBytes(stream), StandardCharsets.UTF_8).linesIterator
      .map(_.split(",", -1).toSeq)
      .toSeq

  private def exportedWorkUnits: Seq[WorkUnitDto] = {
    val worklog = workUnitDtoGenerator.normal.generate.copy(
      userFullName = None,
      duration = WorkUnitDuration(Duration.ofMinutes(80)),
      entryType = WorkUnitEntryType.Worklog,
      description = WorkUnitDescription("ordinary worklog"),
      taskId = None,
      projectClient = None,
      effectiveProjectClient = None,
      effectiveTaskId = None
    )
    Seq(
      worklog,
      worklog.copy(duration = WorkUnitDuration(Duration.ZERO), entryType = WorkUnitEntryType.Note, description = WorkUnitDescription("ordinary note"))
    )
  }

  private val ordinaryColumnNames =
    Seq("date", "clientName", "projectName", "userFullName", "taskId", "description", "duration", "createdAt", "modifiedAt")
  private def sourceDto(
    project: Project,
    client: Client,
    description: String
  ): WorkUnitDto =
    workUnitDtoGenerator.normal.generate.copy(
      entryType = WorkUnitEntryType.Worklog,
      description = WorkUnitDescription(description),
      taskId = Some(WorkUnitTaskId(s"SRC-$description")),
      projectClient = Some(WorkUnitProjectClientDto.from(client, project)),
      effectiveProjectClient = Some(WorkUnitProjectClientDto.from(client, project)),
      effectiveTaskId = Some(WorkUnitTaskId(s"SRC-$description"))
    )

  trait TestCase extends BaseScope {
    val workUnitListService: WorkUnitListService = vmock[WorkUnitListService]

    val sut: ExportWorkUnitService = wire[ExportWorkUnitService]
  }

  "export" should {
    "flatten effective project and explicit Task ID clear without exporting the original values" in {
      val sourceClient  = clientGenerator.normal.generate.copy(name = ClientName("Original Client"))
      val targetClient  = clientGenerator.normal.generate.copy(name = ClientName("Effective Client"))
      val sourceProject = projectGenerator.normal.generate.copy(clientId = sourceClient.id, name = ProjectName("Original Project"))
      val targetProject = projectGenerator.normal.generate.copy(clientId = targetClient.id, name = ProjectName("Effective Project"))
      val dto = sourceDto(sourceProject, sourceClient, "override").copy(
        effectiveProjectClient = Some(WorkUnitProjectClientDto.from(targetClient, targetProject)),
        effectiveTaskId = None
      )
      val row = WorkUnitDtoFlat.from(dto)

      (row.clientName should beSome(targetClient.name)) and
        (row.projectName should beSome(targetProject.name)) and
        (row.taskId should beNone) and
        (dto.taskId should beSome(WorkUnitTaskId("SRC-override")))
    }

    "flatten every source DTO value without changing its meaning" in {
      val client  = clientGenerator.normal.generate
      val project = projectGenerator.normal.generate.copy(clientId = client.id)
      val source  = sourceDto(project, client, "chimney-parity")

      WorkUnitDtoFlat.from(source) shouldEqual WorkUnitDtoFlat(
        date = source.date,
        clientName = source.projectClient.map(_.clientName),
        projectName = source.projectClient.map(_.projectName),
        userFullName = source.userFullName,
        taskId = source.taskId,
        description = source.description,
        duration = source.duration,
        createdAt = source.createdAt,
        modifiedAt = source.modifiedAt,
        entryType = source.entryType
      )
    }

    "keep the legacy ten-argument column source API and omit PM map keys when labels are absent" in {
      val columnNames = ExportWorkUnitColumnNames(None, None, None, None, None, None, None, None, None, None)

      (columnNames.asMap.keySet should not(contain("pmClientName"))) and
        (columnNames.asMap.keySet should not(contain("pmProjectName"))) and
        (columnNames.asMap.keySet should not(contain("pmTaskId")))
    }

    "return NoAccessToOrganization if list service return NoAccessToOrganization" in new TestCase {
      val organization = organizationGenerator.normal.generate
      val request      = requestGenerator.normal.generate

      when(workUnitListService.listForExport(any, any, any)).thenReturn(WorkUnitListResult.NoAccessToOrganization.asIO)

      sut.`export`(authUser, organization.slug, request, format).asserting(_ shouldEqual ExportWorkUnitResult.NoAccessToOrganization)

      verify(workUnitListService).listForExport(authUser, organization.slug, request.filter)
    }
    "return OrganizationNotFound if list service return OrganizationNotFound" in new TestCase {
      val organization = organizationGenerator.normal.generate
      val request      = requestGenerator.normal.generate

      when(workUnitListService.listForExport(any, any, any)).thenReturn(WorkUnitListResult.OrganizationNotFound.asIO)

      sut.`export`(authUser, organization.slug, request, format).asserting(_ shouldEqual ExportWorkUnitResult.OrganizationNotFound)

      verify(workUnitListService).listForExport(authUser, organization.slug, request.filter)
    }
    "propagate OverrideForbidden without building an export" in new TestCase {
      val organization = organizationGenerator.normal.generate
      val request      = requestGenerator.normal.generate

      when(workUnitListService.listForExport(any, any, any)).thenReturn(WorkUnitListResult.OverrideForbidden.asIO)

      sut.`export`(authUser, organization.slug, request, format).asserting(_ shouldEqual ExportWorkUnitResult.OverrideForbidden)

      verify(workUnitListService).listForExport(authUser, organization.slug, request.filter)
    }
    "return OK with filename and stream" in new TestCase {
      val organization = organizationGenerator.normal.generate
      val workUnits    = for (_ <- Seq(1 to 4)) yield workUnitDtoGenerator.normal.generate
      val request      = requestGenerator.normal.generate

      val resultData: WorkUnitListData = WorkUnitListData(PaginatedResult(4, workUnits), Seq())
      when(workUnitListService.listForExport(any, any, any)).thenReturn(WorkUnitListResult.Ok(resultData).asIO)

      sut
        .`export`(authUser, organization.slug, request, format)
        .asserting {
          case ExportWorkUnitResult.Ok(fName, _, formatRet) =>
            (fName, format) shouldEqual (filename, formatRet)
          case _ => false shouldEqual true
        }

      verify(workUnitListService).listForExport(authUser, organization.slug, request.filter)
    }

    "append entry type without shifting existing CSV columns and respect fallback and custom headers" in new TestCase {
      val organization = organizationGenerator.normal.generate
      val requests     = Seq(exportRequest(legacyRequestJson) -> "entryType", exportRequest(customRequestJson) -> "Typ wpisu")
      val resultData   = WorkUnitListData(PaginatedResult(2, exportedWorkUnits), Seq())
      when(workUnitListService.listForExport(any, any, any)).thenReturn(WorkUnitListResult.Ok(resultData).asIO)

      requests.foreach { case (request, entryTypeHeader) =>
        sut.`export`(authUser, organization.slug, request, SpreadsheetFormat.Csv).asserting {
          case ExportWorkUnitResult.Ok(_, stream, _) =>
            val rows = csvRows(stream)
            (rows.head shouldEqual ordinaryColumnNames :+ entryTypeHeader) and
              (rows(1).length shouldEqual 10) and
              (rows(1)(6).toDouble should beCloseTo(80d / 60d, 1e-15)) and
              (rows(1)(9) shouldEqual WorkUnitEntryType.Worklog.entryName) and
              (rows(2).length shouldEqual 10) and
              (rows(2)(6).toDouble shouldEqual 0d) and
              (rows(2)(9) shouldEqual WorkUnitEntryType.Note.entryName)
          case _ => false shouldEqual true
        }
      }

      verify(workUnitListService, times(requests.size)).listForExport(authUser, organization.slug, requests.head._1.filter)
    }

    "use every provided column name, including modifiedAt, as the CSV header" in new TestCase {
      val organization = organizationGenerator.normal.generate
      val request      = exportRequest(allCustomRequestJson)
      val resultData   = WorkUnitListData(PaginatedResult(2, exportedWorkUnits), Seq())
      when(workUnitListService.listForExport(any, any, any)).thenReturn(WorkUnitListResult.Ok(resultData).asIO)

      sut.`export`(authUser, organization.slug, request, SpreadsheetFormat.Csv).asserting {
        case ExportWorkUnitResult.Ok(_, stream, _) => csvRows(stream).head shouldEqual allCustomColumnNames
        case _                                     => false shouldEqual true
      }

      verify(workUnitListService).listForExport(authUser, organization.slug, request.filter)
    }

    "append entry type without shifting existing XLSX columns and keep Note duration numeric zero" in new TestCase {
      val organization = organizationGenerator.normal.generate
      val requests     = Seq(exportRequest(legacyRequestJson) -> "entryType", exportRequest(customRequestJson) -> "Typ wpisu")
      val resultData   = WorkUnitListData(PaginatedResult(2, exportedWorkUnits), Seq())
      when(workUnitListService.listForExport(any, any, any)).thenReturn(WorkUnitListResult.Ok(resultData).asIO)

      requests.foreach { case (request, entryTypeHeader) =>
        sut.`export`(authUser, organization.slug, request, SpreadsheetFormat.Xlsx).asserting {
          case ExportWorkUnitResult.Ok(_, stream, _) =>
            val workbook = new XSSFWorkbook(new ByteArrayInputStream(streamBytes(stream)))
            try {
              val sheet   = workbook.getSheetAt(0)
              val headers = (0 until sheet.getRow(0).getLastCellNum.toInt).map(sheet.getRow(0).getCell(_).getStringCellValue)
              (headers shouldEqual ordinaryColumnNames :+ entryTypeHeader) and
                (sheet.getRow(1).getLastCellNum shouldEqual 10) and
                (sheet.getRow(1).getCell(6).getNumericCellValue should beCloseTo(80d / 60d, 1e-15)) and
                (sheet.getRow(1).getCell(9).getStringCellValue shouldEqual WorkUnitEntryType.Worklog.entryName) and
                (sheet.getRow(2).getLastCellNum shouldEqual 10) and
                (sheet.getRow(2).getCell(6).getNumericCellValue shouldEqual 0d) and
                (sheet.getRow(2).getCell(9).getStringCellValue shouldEqual WorkUnitEntryType.Note.entryName)
            } finally workbook.close()
          case _ => false shouldEqual true
        }
      }

      verify(workUnitListService, times(requests.size)).listForExport(authUser, organization.slug, requests.head._1.filter)
    }

    "export a single effective set of client, project and task values in CSV and XLSX" in new TestCase {
      val organization  = organizationGenerator.normal.generate
      val sourceClient  = clientGenerator.normal.generate.copy(name = ClientName("Original Client"))
      val targetClient  = clientGenerator.normal.generate.copy(name = ClientName("Corrected Client"))
      val sourceProject = projectGenerator.normal.generate.copy(clientId = sourceClient.id, name = ProjectName("Original Project"))
      val targetProject = projectGenerator.normal.generate.copy(clientId = targetClient.id, name = ProjectName("Corrected Project"))
      val corrected = sourceDto(sourceProject, sourceClient, "corrected").copy(
        effectiveProjectClient = Some(WorkUnitProjectClientDto.from(targetClient, targetProject)),
        effectiveTaskId = Some(WorkUnitTaskId("CORRECTED-42"))
      )
      val cleared = corrected.copy(effectiveTaskId = None)
      val request = exportRequest(legacyRequestJson)
      when(workUnitListService.listForExport(authUser, organization.slug, request.filter))
        .thenReturn(WorkUnitListResult.Ok(WorkUnitListData(PaginatedResult(2, Seq(corrected, cleared)), Seq.empty)).asIO)

      val csv = sut.`export`(authUser, organization.slug, request, SpreadsheetFormat.Csv).unsafeRunSync() match {
        case ExportWorkUnitResult.Ok(_, stream, _) => csvRows(stream)
        case other                                 => throw new IllegalStateException(s"Unexpected export: $other")
      }
      val xlsx = sut.`export`(authUser, organization.slug, request, SpreadsheetFormat.Xlsx).unsafeRunSync() match {
        case ExportWorkUnitResult.Ok(_, stream, _) =>
          val workbook = new XSSFWorkbook(new ByteArrayInputStream(streamBytes(stream)))
          try {
            val sheet = workbook.getSheetAt(0)
            (0 to 2).map(row => (0 until 10).map(index => Option(sheet.getRow(row).getCell(index)).fold("")(_.toString)))
          } finally workbook.close()
        case other => throw new IllegalStateException(s"Unexpected export: $other")
      }

      (csv.head shouldEqual ordinaryColumnNames :+ "entryType") and
        (xlsx.head shouldEqual ordinaryColumnNames :+ "entryType") and
        (Seq(csv, xlsx).flatMap(_.tail.map(row => (row(1), row(2), row(4)))) shouldEqual
          Seq.fill(2)(Seq(("Corrected Client", "Corrected Project", "CORRECTED-42"), ("Corrected Client", "Corrected Project", ""))).flatten) and
        (corrected.projectClient.map(_.projectName) should beSome(sourceProject.name)) and
        (corrected.taskId should beSome(WorkUnitTaskId("SRC-corrected")))

      verify(workUnitListService, times(2)).listForExport(authUser, organization.slug, request.filter)
    }
  }
}
