package pl.iterators.epoca.oauth.adapters

import io.circe.Json
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import pl.iterators.epoca.client.domain.Client
import pl.iterators.epoca.client.repositories.ClientRepository
import pl.iterators.epoca.client.repositories.ClientRepository.ClientFilters
import pl.iterators.epoca.export.domain.PivotExportArtifactStore
import pl.iterators.epoca.export.domain.SpreadsheetDomain.{SpreadsheetColumnName, SpreadsheetFormat}
import pl.iterators.epoca.export.domain.WorkUnitPivotExport.{Presentation, ValidatedRequest}
import pl.iterators.epoca.export.services.ExportWorkUnitPivotTableService.ExportResult
import pl.iterators.epoca.export.services.ExportWorkUnitService.{ExportWorkUnitColumnNames, ExportWorkUnitRequest, ExportWorkUnitResult}
import pl.iterators.epoca.export.services.{ExportWorkUnitPivotTableService, WorkUnitPivotExportRequestValidator}
import pl.iterators.epoca.oauth.domain.OAuthDomain.OAuthProjectAccess
import pl.iterators.epoca.organization.domain.Organization
import pl.iterators.epoca.organization.repositories.OrganizationRepository
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.project.domain.Project
import pl.iterators.epoca.project.repositories.ProjectRepository
import pl.iterators.epoca.project.repositories.ProjectRepository.ProjectFilters
import pl.iterators.epoca.stats.domain.StatsDomain.{WorkUnitPivotTableKey, WorkUnitPivotTableValue}
import pl.iterators.epoca.stats.domain.WorkUnitPivotQuery.{
  Cell,
  Coordinate,
  CoordinateKind,
  FieldValue,
  GlobalFilters,
  MetricValues,
  Options,
  PathSegment,
  Request,
  Response,
  ResultMetadata
}
import pl.iterators.epoca.stats.services.WorkUnitPivotQueryService
import pl.iterators.epoca.stats.services.WorkUnitPivotQueryService.Result
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.domain.UserDomain.UserAccountType
import pl.iterators.epoca.user.repositories.UserRepository
import pl.iterators.epoca.user.repositories.UserRepository.UserFilters
import pl.iterators.epoca.utils.ServiceSpec
import pl.iterators.epoca.utils.transactor.IsolationLevel
import pl.iterators.epoca.workunit.domain.WorkUnitDomain._
import pl.iterators.epoca.workunit.domain.{WorkUnit, WorkUnitOverrideState}
import pl.iterators.epoca.workunit.repositories.WorkUnitRepository

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.{Duration, LocalDate}

class OAuthExportAdapterSpec extends ServiceSpec {
  "OAuthExportAdapter" should {
    "write only authorized effective values to CSV from one snapshot" in new BaseScope {
      override val transactor    = new OAuthReportAdapterSpec.RecordingFakeTransactor()
      val organizationRepository = mock[OrganizationRepository]
      val projectRepository      = mock[ProjectRepository]
      val clientRepository       = mock[ClientRepository]
      val userRepository         = mock[UserRepository]
      val workUnitRepository     = mock[WorkUnitRepository]
      val pivotExportService     = mock[ExportWorkUnitPivotTableService]
      val sut = new OAuthExportAdapter(
        transactor,
        organizationRepository,
        projectRepository,
        clientRepository,
        userRepository,
        workUnitRepository,
        mock[WorkUnitPivotQueryService],
        pivotExportService
      )
      val organization  = allGenerators[Organization].normal.generate.copy(archivedAt = None)
      val sourceClient  = allGenerators[Client].normal.generate.copy(organizationId = organization.id, archivedAt = None)
      val pmClient      = allGenerators[Client].normal.generate.copy(organizationId = organization.id, archivedAt = None)
      val sourceProject = allGenerators[Project].normal.generate.copy(organizationId = organization.id, clientId = sourceClient.id, archivedAt = None)
      val pmProject     = allGenerators[Project].normal.generate.copy(organizationId = organization.id, clientId = pmClient.id, archivedAt = None)
      val user          = allGenerators[User].normal.generate.copy(accountType = UserAccountType.Human, archivedAt = None)
      val workUnit = allGenerators[WorkUnit].normal.generate.copy(
        organizationId = organization.id,
        projectId = sourceProject.id,
        userId = user.id,
        duration = WorkUnitDuration(Duration.ofMinutes(75)),
        entryType = WorkUnitEntryType.Worklog,
        state = WorkUnitState.Active,
        overrideState =
          WorkUnitOverrideState(projectId = Some(pmProject.id), taskIdOverrideSet = true, taskId = Some(WorkUnitTaskId("CORRECTED-ALLOWED"))),
        archivedAt = None
      )
      val policy = OAuthAccessPolicy(organization.id, OAuthProjectAccess.SelectedProjects(Set(sourceProject.id, pmProject.id)))
      val columns = ExportWorkUnitColumnNames(
        None,
        Some(SpreadsheetColumnName("Source Client")),
        Some(SpreadsheetColumnName("Source Project")),
        None,
        None,
        None,
        None,
        None,
        None,
        None
      )
      val request = ExportWorkUnitRequest(columns, Seq.empty, None, None, Seq.empty, Seq.empty, Seq.empty)
      val boundedFilter = request.filter.copy(
        organizationId = Some(organization.id),
        states = Some(WorkUnitState.liveStates),
        reportAuthorizedProjectIds = Some(Seq(sourceProject.id, pmProject.id)),
        pageSizeAndNumber = None
      )

      when(organizationRepository.find(OrganizationFilters(id = Some(organization.id), slug = Some(organization.slug), includeArchived = true)))
        .thenReturn(Some(organization).asDBIO)
      when(
        projectRepository
          .list(ProjectFilters(ids = Some(Seq(sourceProject.id, pmProject.id)), organizationIds = Some(Seq(organization.id)), includeArchived = true))
      ).thenReturn(Seq(sourceProject, pmProject).asDBIO)
      when(workUnitRepository.list(boundedFilter)).thenReturn(Seq(workUnit).asDBIO)
      when(projectRepository.list(ProjectFilters(ids = Some(Seq(sourceProject.id, pmProject.id)), includeArchived = true)))
        .thenReturn(Seq(sourceProject, pmProject).asDBIO)
      when(clientRepository.list(ClientFilters(ids = Some(Seq(sourceClient.id, pmClient.id)), includeArchived = true)))
        .thenReturn(Seq(sourceClient, pmClient).asDBIO)
      when(userRepository.list(UserFilters(ids = Some(Seq(user.id)), includeArchived = true))).thenReturn(Seq(user).asDBIO)

      sut.exportWorkUnits(policy, organization.slug, request, SpreadsheetFormat.Csv).asserting {
        case ExportWorkUnitResult.Ok(_, stream, SpreadsheetFormat.Csv) =>
          val csv = new String(stream.compile.toVector.unsafeRunSync().toArray, StandardCharsets.UTF_8)
          (csv must not(contain(sourceProject.name.toString))) and
            (csv must contain(pmProject.name.toString)) and
            (csv must contain("CORRECTED-ALLOWED")) and
            (csv must contain((75d / 60d).toString)) and
            (transactor.executionCount shouldEqual 1) and
            (transactor.isolationLevels shouldEqual Vector(IsolationLevel.RepeatableRead))
        case other => ko(s"Unexpected export result: $other")
      }
    }

    "preserve authorized pivot totals and dimensions in XLSX" in new BaseScope {
      override val transactor    = new OAuthReportAdapterSpec.RecordingFakeTransactor()
      val organizationRepository = mock[OrganizationRepository]
      val projectRepository      = mock[ProjectRepository]
      val validator              = mock[WorkUnitPivotExportRequestValidator]
      val queryService           = mock[WorkUnitPivotQueryService]
      val artifactRoot           = Files.createTempDirectory("ct-520-oauth-pivot-")
      val artifactStore          = new PivotExportArtifactStore(PivotExportArtifactStore.Config(artifactRoot.resolve("artifacts")))
      val pivotExportService     = new ExportWorkUnitPivotTableService(validator, queryService, artifactStore)
      val sut = new OAuthExportAdapter(
        transactor,
        organizationRepository,
        projectRepository,
        mock[ClientRepository],
        mock[UserRepository],
        mock[WorkUnitRepository],
        queryService,
        pivotExportService
      )
      val organization = allGenerators[Organization].normal.generate.copy(archivedAt = None)
      val project      = allGenerators[Project].normal.generate.copy(organizationId = organization.id, archivedAt = None)
      val policy       = OAuthAccessPolicy(organization.id, OAuthProjectAccess.SelectedProjects(Set(project.id)))
      val query = Request(
        rows = Seq(WorkUnitPivotTableKey.ProjectKey),
        columns = Seq.empty,
        values = Seq(WorkUnitPivotTableValue.EntriesCount),
        filters = Seq.empty,
        options = Options(rowSubtotals = false, columnSubtotals = false, grandTotals = false)
      )
      val presentation = Presentation(
        fieldLabels = WorkUnitPivotTableKey.values
          .map(key => key -> Option.when(key == WorkUnitPivotTableKey.ProjectKey)("projectId").getOrElse(key.entryName))
          .toMap,
        measureLabels = WorkUnitPivotTableValue.values
          .map(value => value -> Option.when(value == WorkUnitPivotTableValue.EntriesCount)("entriesCount").getOrElse(value.entryName))
          .toMap,
        subtotalLabel = "Subtotal",
        grandTotalLabel = "Grand total",
        noClientLabel = "No client",
        noTaskLabel = "No task",
        memberLabels = Seq.empty
      )
      val rawRequest = Json.obj("contract" -> Json.fromString("ct-534"))
      val dateFrom   = WorkUnitDate(LocalDate.parse("2026-08-01"))
      val filters    = GlobalFilters(dateFrom, None, Seq.empty, Seq.empty, Seq.empty)
      val row        = Coordinate(CoordinateKind.Leaf, Seq(PathSegment(WorkUnitPivotTableKey.ProjectKey, FieldValue.Uuid(project.id))))
      val response = Response(
        cells = Seq(Cell(row, Coordinate(CoordinateKind.Leaf, Seq.empty), MetricValues(None, None, Some(3L)))),
        filterMembers = Seq.empty,
        result = ResultMetadata(cellCount = 1L, displayValueCellCount = 1L, maxDisplayValueCellCount = 10000L)
      )

      when(organizationRepository.find(OrganizationFilters(id = Some(organization.id), slug = Some(organization.slug), includeArchived = true)))
        .thenReturn(Some(organization).asDBIO)
      when(projectRepository.list(ProjectFilters(ids = Some(Seq(project.id)), organizationIds = Some(Seq(organization.id)), includeArchived = true)))
        .thenReturn(Seq(project).asDBIO)
      when(validator.validate(rawRequest)).thenReturn(Right(ValidatedRequest(query, presentation)))
      when(queryService.queryOAuthValidatedAction(organization.id, Seq(project.id), query, filters)).thenReturn(Result.Ok(response).asDBIO)

      sut.exportPivot(policy, organization.slug, rawRequest, filters, SpreadsheetFormat.Xlsx).asserting {
        case ExportResult.Ok(artifact) =>
          val workbook = new XSSFWorkbook(Files.newInputStream(artifact.path))
          try {
            val sheet = workbook.getSheetAt(0)
            (sheet.getRow(0).getCell(0).getStringCellValue shouldEqual "projectId") and
              (sheet.getRow(0).getCell(1).getStringCellValue shouldEqual "entriesCount") and
              (sheet.getRow(1).getCell(0).getStringCellValue shouldEqual project.id.toString) and
              (sheet.getRow(1).getCell(1).getNumericCellValue shouldEqual 3d) and
              (transactor.isolationLevels shouldEqual Vector(IsolationLevel.RepeatableRead))
          } finally {
            workbook.close()
            val _ = artifact.discard.unsafeRunSync()
            val _ = Files.deleteIfExists(artifactRoot.resolve("artifacts"))
            val _ = Files.deleteIfExists(artifactRoot)
          }
        case other => ko(s"Unexpected pivot export result: $other")
      }
    }
  }
}
