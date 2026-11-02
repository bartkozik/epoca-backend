package pl.iterators.epoca.oauth.adapters

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import org.mockito.Mockito.verifyNoInteractions
import pl.iterators.epoca.oauth.domain.OAuthDomain.OAuthProjectAccess
import pl.iterators.epoca.organization.domain.Organization
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.project.domain.Project
import pl.iterators.epoca.project.domain.ProjectDomain.ProjectId
import pl.iterators.epoca.project.repositories.ProjectRepository
import pl.iterators.epoca.project.repositories.ProjectRepository.ProjectFilters
import pl.iterators.epoca.stats.domain.StatsDomain.{WorkUnitPivotTableKey, WorkUnitPivotTableValue}
import pl.iterators.epoca.stats.domain.WorkUnitPivotQuery.{GlobalFilters, InvalidConfigurationError, Violation, ViolationCode}
import pl.iterators.epoca.stats.domain.WorkUnitPivotTableRow
import pl.iterators.epoca.stats.services.WorkUnitPivotTableService.{WorkUnitPivotTableRequest, WorkUnitPivotTableResult}
import pl.iterators.epoca.stats.services.{WorkUnitPivotQueryService, WorkUnitPivotTableService}
import pl.iterators.epoca.utils.ServiceSpec
import pl.iterators.epoca.utils.transactor.{FakeTransactor, IsolationLevel}
import pl.iterators.epoca.workunit.repositories.WorkUnitRepository
import pl.iterators.epoca.workunit.repositories.WorkUnitRepository.{WorkUnitFilters, WorkUnitWithClientId}
import slick.dbio.DBIO

import java.util.UUID

class OAuthReportAdapterSpec extends ServiceSpec {
  "OAuthReportPolicy" should {
    "require both the source and effective project to be authorized" in {
      val organizationId = OrganizationId(UUID.randomUUID())
      val allowedProject = ProjectId(UUID.randomUUID())
      val deniedProject  = ProjectId(UUID.randomUUID())
      val policy         = OAuthReportPolicy(organizationId, Set(allowedProject))

      (policy.allowsRow(allowedProject, None) should beTrue) and
        (policy.allowsRow(allowedProject, Some(allowedProject)) should beTrue) and
        (policy.allowsRow(deniedProject, None) should beFalse) and
        (policy.allowsRow(allowedProject, Some(deniedProject)) should beFalse)
    }
  }

  "OAuthReportAdapter" should {
    "resolve selected projects inside the bound organization before running the legacy pivot" in new BaseScope {
      val organizationRepository = mock[OrganizationRepository]
      val projectRepository      = mock[ProjectRepository]
      val pivotService           = mock[WorkUnitPivotTableService]
      val queryService           = mock[WorkUnitPivotQueryService]
      val sut                    = new OAuthReportAdapter(transactor, organizationRepository, projectRepository, pivotService, queryService)
      val organization           = allGenerators[Organization].normal.generate.copy(archivedAt = None)
      val project                = allGenerators[Project].normal.generate.copy(organizationId = organization.id, archivedAt = None)
      val policy                 = OAuthAccessPolicy(organization.id, OAuthProjectAccess.SelectedProjects(Set(project.id)))
      val request                = WorkUnitPivotTableRequest(Seq(WorkUnitPivotTableKey.ProjectKey), Seq(WorkUnitPivotTableValue.EntriesCount))
      val filters                = WorkUnitFilters()
      val expected               = WorkUnitPivotTableResult.Ok(Seq.empty[WorkUnitPivotTableRow])

      when(organizationRepository.find(OrganizationFilters(id = Some(organization.id), slug = Some(organization.slug), includeArchived = true)))
        .thenReturn(Some(organization).asDBIO)
      when(projectRepository.list(ProjectFilters(ids = Some(Seq(project.id)), organizationIds = Some(Seq(organization.id)), includeArchived = true)))
        .thenReturn(Seq(project).asDBIO)
      when(pivotService.buildOAuthPivotTableAction(organization.id, Seq(project.id), request, filters)).thenReturn(expected.asDBIO)

      sut.legacyPivot(policy, organization.slug, request, filters).asserting(_ shouldEqual expected)
    }

    "bind report policy and rows in one database snapshot" in new BaseScope {
      override val transactor        = new OAuthReportAdapterSpec.RecordingFakeTransactor()
      val organizationRepository     = mock[OrganizationRepository]
      val organizationUserRepository = mock[OrganizationUserRepository]
      val projectRepository          = mock[ProjectRepository]
      val workUnitRepository         = mock[WorkUnitRepository]
      val queryService               = mock[WorkUnitPivotQueryService]
      val pivotService = new WorkUnitPivotTableService(transactor, organizationRepository, organizationUserRepository, workUnitRepository)
      val sut          = new OAuthReportAdapter(transactor, organizationRepository, projectRepository, pivotService, queryService)
      val organization = allGenerators[Organization].normal.generate.copy(archivedAt = None)
      val project      = allGenerators[Project].normal.generate.copy(organizationId = organization.id, archivedAt = None)
      val policy       = OAuthAccessPolicy(organization.id, OAuthProjectAccess.SelectedProjects(Set(project.id)))
      val request      = WorkUnitPivotTableRequest(Seq(WorkUnitPivotTableKey.ProjectKey), Seq(WorkUnitPivotTableValue.EntriesCount))
      val filters      = WorkUnitFilters()

      when(organizationRepository.find(OrganizationFilters(id = Some(organization.id), slug = Some(organization.slug), includeArchived = true)))
        .thenReturn(Some(organization).asDBIO)
      when(projectRepository.list(ProjectFilters(ids = Some(Seq(project.id)), organizationIds = Some(Seq(organization.id)), includeArchived = true)))
        .thenReturn(Seq(project).asDBIO)
      when(
        workUnitRepository.listWithClientId(
          filters.copy(organizationId = Some(organization.id), reportAuthorizedProjectIds = Some(Seq(project.id)), pageSizeAndNumber = None)
        )
      ).thenReturn(Seq.empty[WorkUnitWithClientId].asDBIO)

      sut.legacyPivot(policy, organization.slug, request, filters).asserting { result =>
        (result shouldEqual WorkUnitPivotTableResult.Ok(Seq.empty)) and
          (transactor.executionCount shouldEqual 1) and
          (transactor.isolationLevels shouldEqual Vector(IsolationLevel.RepeatableRead))
      }
    }

    "bind full pivot to the resolved project policy" in new BaseScope {
      val organizationRepository = mock[OrganizationRepository]
      val projectRepository      = mock[ProjectRepository]
      val pivotService           = mock[WorkUnitPivotTableService]
      val queryService           = mock[WorkUnitPivotQueryService]
      val sut                    = new OAuthReportAdapter(transactor, organizationRepository, projectRepository, pivotService, queryService)
      val organization           = allGenerators[Organization].normal.generate.copy(archivedAt = None)
      val project                = allGenerators[Project].normal.generate.copy(organizationId = organization.id, archivedAt = None)
      val policy                 = OAuthAccessPolicy(organization.id, OAuthProjectAccess.SelectedProjects(Set(project.id)))
      val rawRequest             = io.circe.Json.obj("rows" -> io.circe.Json.arr())
      val globalFilters = GlobalFilters(
        pl.iterators.epoca.workunit.domain.WorkUnitDomain.WorkUnitDate(java.time.LocalDate.parse("2026-08-01")),
        None,
        Seq.empty,
        Seq.empty,
        Seq.empty
      )
      val invalid = WorkUnitPivotQueryService.Result.InvalidConfiguration(
        InvalidConfigurationError(violations = Seq(Violation("$.values", ViolationCode.EMPTY_VALUES)))
      )
      val organizationFilter = OrganizationFilters(id = Some(organization.id), slug = Some(organization.slug), includeArchived = true)
      val projectFilter      = ProjectFilters(ids = Some(Seq(project.id)), organizationIds = Some(Seq(organization.id)), includeArchived = true)

      when(organizationRepository.find(organizationFilter)).thenReturn(Some(organization).asDBIO)
      when(projectRepository.list(projectFilter)).thenReturn(Seq(project).asDBIO)
      when(queryService.queryOAuthAction(organization.id, Seq(project.id), rawRequest, globalFilters)).thenReturn(invalid.asDBIO)
      sut.fullPivot(policy, organization.slug, rawRequest, globalFilters).asserting(_ shouldEqual invalid)
    }

    "fail closed when a selected policy project no longer resolves in the bound organization" in new BaseScope {
      val organizationRepository = mock[OrganizationRepository]
      val projectRepository      = mock[ProjectRepository]
      val pivotService           = mock[WorkUnitPivotTableService]
      val queryService           = mock[WorkUnitPivotQueryService]
      val sut                    = new OAuthReportAdapter(transactor, organizationRepository, projectRepository, pivotService, queryService)
      val organization           = allGenerators[Organization].normal.generate.copy(archivedAt = None)
      val missingId              = ProjectId(UUID.randomUUID())
      val policy                 = OAuthAccessPolicy(organization.id, OAuthProjectAccess.SelectedProjects(Set(missingId)))
      val request                = WorkUnitPivotTableRequest(Seq(WorkUnitPivotTableKey.ProjectKey), Seq(WorkUnitPivotTableValue.EntriesCount))

      when(organizationRepository.find(OrganizationFilters(id = Some(organization.id), slug = Some(organization.slug), includeArchived = true)))
        .thenReturn(Some(organization).asDBIO)
      when(projectRepository.list(ProjectFilters(ids = Some(Seq(missingId)), organizationIds = Some(Seq(organization.id)), includeArchived = true)))
        .thenReturn(Seq.empty[Project].asDBIO)

      sut.legacyPivot(policy, organization.slug, request, WorkUnitFilters()).asserting { result =>
        verifyNoInteractions(pivotService, queryService)
        result shouldEqual WorkUnitPivotTableResult.OrganizationNotFound
      }
    }
  }
}

object OAuthReportAdapterSpec {
  final class RecordingFakeTransactor(implicit runtime: IORuntime) extends FakeTransactor {
    var executionCount: Int                     = 0
    var isolationLevels: Vector[IsolationLevel] = Vector.empty

    override def execute[T](action: => DBIO[T]): IO[T] = {
      executionCount += 1
      super.execute(action)
    }

    override def executeTransactionally[T](action: => DBIO[T]): IO[T] = {
      executionCount += 1
      super.executeTransactionally(action)
    }

    override def executeTransactionally[T](level: IsolationLevel)(action: => DBIO[T]): IO[T] = {
      executionCount += 1
      isolationLevels :+= level
      super.executeTransactionally(level)(action)
    }
  }
}
