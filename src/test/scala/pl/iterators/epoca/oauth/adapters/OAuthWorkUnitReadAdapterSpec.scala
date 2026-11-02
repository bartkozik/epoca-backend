package pl.iterators.epoca.oauth.adapters

import io.circe.syntax._
import pl.iterators.epoca.client.domain.Client
import pl.iterators.epoca.client.repositories.ClientRepository
import pl.iterators.epoca.oauth.adapters.OAuthReadResult.Ok
import pl.iterators.epoca.oauth.domain.OAuthDomain.OAuthProjectAccess
import pl.iterators.epoca.organization.domain.Organization
import pl.iterators.epoca.organization.repositories.OrganizationRepository
import pl.iterators.epoca.project.domain.Project
import pl.iterators.epoca.project.repositories.ProjectRepository
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.domain.UserDomain.UserAccountType
import pl.iterators.epoca.user.repositories.UserRepository
import pl.iterators.epoca.utils.ServiceSpec
import pl.iterators.epoca.utils.db.PageSizeAndNumber
import pl.iterators.epoca.utils.json.JsonProtocol._
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.WorkUnitState
import pl.iterators.epoca.workunit.domain.{WorkUnit, WorkUnitOverrideState}
import pl.iterators.epoca.workunit.repositories.WorkUnitRepository
import pl.iterators.epoca.workunit.services.WorkUnitDtoService
import pl.iterators.epoca.workunit.services.WorkUnitListService.WorkUnitListData

class OAuthWorkUnitReadAdapterSpec extends ServiceSpec {
  private lazy val organizationGenerator = allGenerators[Organization].normal
  private lazy val projectGenerator      = allGenerators[Project].normal
  private lazy val clientGenerator       = allGenerators[Client].normal
  private lazy val userGenerator         = allGenerators[User].normal
  private lazy val workUnitGenerator     = allGenerators[WorkUnit].normal

  "OAuthWorkUnitReadAdapter" should {
    "serialize only human base work units in visible projects" in new BaseScope {
      val organizationRepository = mock[OrganizationRepository]
      val projectRepository      = mock[ProjectRepository]
      val clientRepository       = mock[ClientRepository]
      val userRepository         = mock[UserRepository]
      val workUnitRepository     = mock[WorkUnitRepository]
      val dtoService             = new WorkUnitDtoService(clientRepository, userRepository, projectRepository)
      val sut                    = new OAuthWorkUnitReadAdapter(transactor, organizationRepository, workUnitRepository, userRepository, dtoService)

      val organization   = organizationGenerator.generate.copy(archivedAt = None)
      val c1             = clientGenerator.generate.copy(organizationId = organization.id, archivedAt = None)
      val p1             = projectGenerator.generate.copy(organizationId = organization.id, clientId = c1.id, archivedAt = None)
      val p2             = projectGenerator.generate.copy(organizationId = organization.id, clientId = c1.id, archivedAt = None)
      val humanP1        = userGenerator.generate.copy(accountType = UserAccountType.Human, archivedAt = None)
      val humanP2        = userGenerator.generate.copy(accountType = UserAccountType.Human, archivedAt = None)
      val serviceAccount = userGenerator.generate.copy(accountType = UserAccountType.ServiceAccount, archivedAt = None)
      val w1 = workUnitGenerator.generate.copy(
        organizationId = organization.id,
        projectId = p1.id,
        userId = humanP1.id,
        state = WorkUnitState.Active,
        archivedAt = None
      )
      val w2 = workUnitGenerator.generate.copy(
        organizationId = organization.id,
        projectId = p1.id,
        overrideState = WorkUnitOverrideState(projectId = Some(p2.id)),
        userId = humanP2.id,
        state = WorkUnitState.Active,
        archivedAt = None
      )
      val serviceWorkUnit = workUnitGenerator.generate.copy(
        organizationId = organization.id,
        projectId = p1.id,
        userId = serviceAccount.id,
        state = WorkUnitState.Active,
        archivedAt = None
      )
      val policy        = OAuthAccessPolicy(organization.id, OAuthProjectAccess.SelectedProjects(Set(p1.id)))
      val requestedPage = PageSizeAndNumber(size = 1, number = 1)
      val boundedFilter = WorkUnitRepository.WorkUnitFilters(
        organizationId = Some(organization.id),
        reportAuthorizedProjectIds = Some(Seq(p1.id)),
        states = Some(WorkUnitState.liveStates),
        pageSizeAndNumber = Some(requestedPage)
      )

      when(organizationRepository.find(any[OrganizationRepository.OrganizationFilters])).thenReturn(Some(organization).asDBIO)
      when(workUnitRepository.list(any[WorkUnitRepository.WorkUnitFilters])).thenReturn(Seq(w1, w2, serviceWorkUnit).asDBIO)
      when(workUnitRepository.count(any[WorkUnitRepository.WorkUnitFilters])).thenReturn(1.asDBIO)
      when(userRepository.list(any[UserRepository.UserFilters])).thenReturn(Seq(humanP1, humanP2, serviceAccount).asDBIO)
      when(projectRepository.list(any[ProjectRepository.ProjectFilters])).thenReturn(Seq(p1).asDBIO)
      when(clientRepository.list(any[ClientRepository.ClientFilters])).thenReturn(Seq(c1).asDBIO)

      sut.list(policy, organization.slug, WorkUnitRepository.WorkUnitFilters(pageSizeAndNumber = Some(requestedPage))).asserting { result =>
        val data = result.asInstanceOf[Ok[WorkUnitListData]].value
        val json = data.workUnits.results.asJson.noSpaces

        verify(workUnitRepository).list(boundedFilter)
        verify(workUnitRepository).count(boundedFilter)

        (data.workUnits.count shouldEqual 1) and
          (data.workUnits.results.map(_.id) shouldEqual Seq(w1.id)) and
          (json must contain(w1.id.toString)) and
          (json must contain(p1.id.toString)) and
          (json must not(contain(w2.id.toString))) and
          (json must not(contain(p2.id.toString))) and
          (json must not(contain(serviceAccount.id.toString))) and
          (data.workUnits.results.forall(dto => !dto.`override`.permissions.canEdit && !dto.`override`.permissions.canViewHistory) should beTrue)
      }
    }

    "return an empty successful collection for a project filter outside the policy" in new BaseScope {
      val organizationRepository = mock[OrganizationRepository]
      val sut =
        new OAuthWorkUnitReadAdapter(transactor, organizationRepository, mock[WorkUnitRepository], mock[UserRepository], mock[WorkUnitDtoService])
      val organization = organizationGenerator.generate.copy(archivedAt = None)
      val p1           = projectGenerator.generate.copy(organizationId = organization.id, archivedAt = None)
      val p2           = projectGenerator.generate.copy(organizationId = organization.id, archivedAt = None)
      val policy       = OAuthAccessPolicy(organization.id, OAuthProjectAccess.SelectedProjects(Set(p1.id)))

      when(organizationRepository.find(any[OrganizationRepository.OrganizationFilters])).thenReturn(Some(organization).asDBIO)

      val expected = Ok(WorkUnitListData(pl.iterators.epoca.utils.db.PaginatedResult(0, Seq.empty), Seq.empty))
      sut
        .list(policy, organization.slug, WorkUnitRepository.WorkUnitFilters(projectIds = Some(Seq(p2.id))))
        .asserting(_ shouldEqual expected)
    }
  }
}
