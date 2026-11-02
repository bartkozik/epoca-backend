package pl.iterators.epoca.workunit.services

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import org.mockito.Mockito.verifyNoInteractions
import org.specs2.matcher.MatchResult
import pl.iterators.epoca.oauth.adapters.OAuthAccessPolicy
import pl.iterators.epoca.oauth.domain.OAuthDomain.OAuthProjectAccess
import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationSlug, OrganizationUserId, OrganizationUserRole}
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.project.domain.{Project, ProjectUser}
import pl.iterators.epoca.project.repositories.ProjectRepository.ProjectFilters
import pl.iterators.epoca.project.repositories.ProjectUserRepository.ProjectUserFilters
import pl.iterators.epoca.project.repositories.{ProjectRepository, ProjectUserRepository}
import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.domain.UserDomain.UserAccountType
import pl.iterators.epoca.user.repositories.UserRepository
import pl.iterators.epoca.user.repositories.UserRepository.UserFilters
import pl.iterators.epoca.utils.ServiceSpec
import pl.iterators.epoca.utils.transactor.{FakeTransactor, IsolationLevel}
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.{
  WorkUnitCreatedAt,
  WorkUnitDate,
  WorkUnitDuration,
  WorkUnitEntryType,
  WorkUnitModifiedAt,
  WorkUnitState
}
import pl.iterators.epoca.workunit.domain.{WorkUnit, WorkUnitOverrideState}
import pl.iterators.epoca.workunit.repositories.WorkUnitRepository
import pl.iterators.epoca.workunit.repositories.WorkUnitRepository.WorkUnitFilters
import pl.iterators.epoca.workunit.services.WorkUnitDtoService.WorkUnitDto
import pl.iterators.epoca.workunit.services.WorkUnitUpdateService.{WorkUnitUpdateRequest, WorkUnitUpdateResult}
import slick.dbio.DBIO

import java.time.{Clock, Duration, Instant, LocalDate, ZoneId}

class WorkUnitUpdateServiceSpec extends ServiceSpec {
  lazy val organizationGenerator: AllGenerators[Organization]     = allGenerators[Organization]
  lazy val orgSlugGenerator: AllGenerators[OrganizationSlug]      = allGenerators[OrganizationSlug]
  lazy val workUnitGenerator: AllGenerators[WorkUnit]             = allGenerators[WorkUnit]
  lazy val workUnitDtoGenerator: AllGenerators[WorkUnitDto]       = allGenerators[WorkUnitDto]
  lazy val rolesGenerator: AllGenerators[OrganizationUserRole]    = allGenerators[OrganizationUserRole]
  lazy val projectGenerator: AllGenerators[Project]               = allGenerators[Project]
  lazy val projectUserGenerator: AllGenerators[ProjectUser]       = allGenerators[ProjectUser]
  lazy val userGenerator: AllGenerators[User]                     = allGenerators[User]
  lazy val requestGenerator: AllGenerators[WorkUnitUpdateRequest] = allGenerators[WorkUnitUpdateRequest]

  private val pastCreatedAt  = WorkUnitCreatedAt(Instant.EPOCH)
  private val pastModifiedAt = WorkUnitModifiedAt(Instant.EPOCH)
  private val fixedInstant   = Instant.parse("2026-07-29T10:00:00Z")
  private val businessZone   = ZoneId.of("Europe/Warsaw")
  private val businessDate   = LocalDate.parse("2026-07-29")
  private val today          = WorkUnitDate(businessDate)
  private val tomorrow       = WorkUnitDate(businessDate.plusDays(1L))
  private val neutral        = WorkUnitDuration(Duration.ZERO)
  private val positive       = WorkUnitDuration(Duration.ofHours(1L))
  private val exactlySecond  = WorkUnitDuration(Duration.ofSeconds(1L))
  private val subsecond      = WorkUnitDuration(Duration.ofMillis(500L))

  trait TestCase extends BaseScope {
    override val transactor: WorkUnitUpdateServiceSpec.RecordingTransactor = new WorkUnitUpdateServiceSpec.RecordingTransactor()
    val organizationRepository: OrganizationRepository                     = vmock[OrganizationRepository]
    val organizationUserRepository: OrganizationUserRepository             = vmock[OrganizationUserRepository]
    val workUnitRepository: WorkUnitRepository                             = vmock[WorkUnitRepository]
    val projectRepository: ProjectRepository                               = vmock[ProjectRepository]
    val projectUserRepository: ProjectUserRepository                       = vmock[ProjectUserRepository]
    val userRepository: UserRepository                                     = vmock[UserRepository]
    val workUnitDtoService: WorkUnitDtoService                             = vmock[WorkUnitDtoService]
    val workUnitBusinessClock: WorkUnitBusinessClock = WorkUnitBusinessClock(Clock.fixed(fixedInstant, businessZone), businessZone)

    when(userRepository.find(any[UserFilters])).thenReturn(Some(userGenerator.normal.generate.copy(accountType = UserAccountType.Human)).asDBIO)

    override def after: MatchResult[Any] = {
      val mocksIgnoringStubs = ignoreStubs(_allMocks.toList: _*).map(_.asInstanceOf[AnyRef])
      verifyNoMoreInteractions(mocksIgnoringStubs.toIndexedSeq: _*)
      1 shouldEqual 1
    }

    def stableWorkUnit(
      entryType: WorkUnitEntryType = WorkUnitEntryType.Worklog,
      duration: WorkUnitDuration = positive,
      date: WorkUnitDate = today,
      state: WorkUnitState = WorkUnitState.Active
    ): WorkUnit =
      workUnitGenerator.normal.generate.copy(
        userId = authUser.id,
        entryType = entryType,
        duration = duration,
        date = date,
        state = state,
        modifiedAt = pastModifiedAt
      )

    def stableRequest(
      duration: Option[WorkUnitDuration] = None,
      date: Option[WorkUnitDate] = None,
      shouldFinalize: Boolean = false
    ): WorkUnitUpdateRequest =
      requestGenerator.normal.generate.copy(
        projectId = None,
        duration = duration,
        description = None,
        taskId = None,
        date = date,
        shouldFinalize = shouldFinalize
      )

    def stubSuccessfulAdminUpdate(
      workUnit: WorkUnit,
      organization: Organization = organizationGenerator.normal.generate,
      dto: WorkUnitDto = workUnitDtoGenerator.normal.generate
    ): (Organization, WorkUnitDto) = {
      when(organizationRepository.find(OrganizationFilters(slug = Some(organization.slug)))).thenReturn(Some(organization).asDBIO)
      when(workUnitRepository.find(WorkUnitFilters(id = Some(workUnit.id), organizationId = Some(organization.id), forUpdate = true)))
        .thenReturn(Some(workUnit).asDBIO)
      when(workUnitRepository.update(any)).thenAnswer((value: WorkUnit) => Some(value).asDBIO)
      when(workUnitDtoService.prepareDto(any)).thenReturn(dto.asDBIO)
      organization -> dto
    }

    val sut: WorkUnitUpdateService = wire[WorkUnitUpdateService]
  }

  "updateOAuthAction" should {
    Seq("source only" -> true, "effective only" -> false).foreach { case (name, sourceOnly) =>
      s"deny $name project scope before mutation or preparing an override-bearing response" in new TestCase {
        val organization = organizationGenerator.normal.generate
        val target       = projectGenerator.normal.generate.id
        val workUnit     = stableWorkUnit().copy(organizationId = organization.id, overrideState = WorkUnitOverrideState(projectId = Some(target)))
        val projects     = if (sourceOnly) Set(workUnit.projectId) else Set(target)
        val policy       = OAuthAccessPolicy(organization.id, OAuthProjectAccess.SelectedProjects(projects))
        val filter       = WorkUnitFilters(id = Some(workUnit.id), organizationId = Some(organization.id), forUpdate = true)
        val dto          = workUnitDtoGenerator.normal.generate
        when(organizationRepository.find(OrganizationFilters(id = Some(organization.id), slug = Some(organization.slug))))
          .thenReturn(Some(organization).asDBIO)
        when(workUnitRepository.find(filter)).thenReturn(Some(workUnit).asDBIO)
        when(workUnitRepository.update(any)).thenAnswer((value: WorkUnit) => Some(value).asDBIO)
        when(workUnitDtoService.prepareDto(any)).thenReturn(dto.asDBIO)

        transactor
          .execute(sut.updateOAuthAction(policy, organization.slug, workUnit.id, stableRequest()))
          .asserting(_ shouldEqual WorkUnitUpdateResult.WorkUnitNotFound)

        verify(workUnitRepository).find(filter)
        verify(workUnitRepository, never).update(any)
        verifyNoInteractions(workUnitDtoService, projectRepository, projectUserRepository, organizationUserRepository)
      }
    }

    "allow both source and effective project scopes while preserving the override and advancing revision" in new TestCase {
      val organization = organizationGenerator.normal.generate
      val target       = projectGenerator.normal.generate.id
      val workUnit =
        stableWorkUnit().copy(organizationId = organization.id, revision = 7, overrideState = WorkUnitOverrideState(projectId = Some(target)))
      val policy = OAuthAccessPolicy(organization.id, OAuthProjectAccess.SelectedProjects(Set(workUnit.projectId, target)))
      val filter = WorkUnitFilters(id = Some(workUnit.id), organizationId = Some(organization.id), forUpdate = true)
      val dto    = workUnitDtoGenerator.normal.generate
      when(organizationRepository.find(OrganizationFilters(id = Some(organization.id), slug = Some(organization.slug))))
        .thenReturn(Some(organization).asDBIO)
      when(workUnitRepository.find(filter)).thenReturn(Some(workUnit).asDBIO)
      when(workUnitRepository.update(any)).thenAnswer((value: WorkUnit) => Some(value).asDBIO)
      when(workUnitDtoService.prepareDto(any)).thenReturn(dto.asDBIO)

      transactor
        .execute(sut.updateOAuthAction(policy, organization.slug, workUnit.id, stableRequest()))
        .asserting(_ shouldEqual WorkUnitUpdateResult.Updated(dto))

      verify(workUnitRepository).find(filter)
      verify(workUnitRepository).update(argThat { updated: WorkUnit =>
        updated.overrideState == workUnit.overrideState && updated.revision == 8 && updated.projectId == workUnit.projectId
      })
      verify(workUnitDtoService).prepareDto(argThat { updated: WorkUnit =>
        updated.overrideState == workUnit.overrideState && updated.revision == 8
      })
      verifyNoInteractions(projectRepository, projectUserRepository, organizationUserRepository)
    }
  }

  "update" should {
    "prepare its response from the persisted revision and preserved override, not a stale candidate" in new TestCase {
      val source              = stableWorkUnit().copy(revision = 7)
      val (organization, dto) = stubSuccessfulAdminUpdate(source)
      val persisted           = source.copy(revision = 42)
      when(workUnitRepository.update(any)).thenReturn(Some(persisted).asDBIO)
      sut.update(authAdmin, organization.slug, source.id, stableRequest()).asserting(_ shouldEqual WorkUnitUpdateResult.Updated(dto))
      verify(workUnitDtoService).prepareDto(persisted)
    }
    "return NonPositiveDuration when user tries to update work unit with no time" in new TestCase {
      val organization = organizationGenerator.normal.generate
      val request      = stableRequest(duration = Some(neutral))
      val workUnit     = stableWorkUnit()
      stubSuccessfulAdminUpdate(workUnit, organization)

      sut.update(authAdmin, organization.slug, workUnit.id, request).asserting(_ shouldEqual WorkUnitUpdateResult.NonPositiveDuration)

      transactor.executeCalls shouldEqual 0
      transactor.transactionalCalls shouldEqual 1
      verify(workUnitRepository).find(WorkUnitFilters(id = Some(workUnit.id), organizationId = Some(organization.id), forUpdate = true))
      verify(workUnitRepository, never).update(any)
    }
    "expose the same update action for an enclosing idempotency transaction with a referenced project" in new TestCase {
      val organization = organizationGenerator.normal.generate
      val workUnit     = stableWorkUnit()
      val request      = stableRequest(duration = Some(neutral)).copy(projectId = Some(workUnit.projectId))
      stubSuccessfulAdminUpdate(workUnit, organization)
      when(projectRepository.lockTableForReference()).thenReturn(().asDBIO)

      transactor
        .execute(sut.updateAction(authAdmin, organization.slug, workUnit.id, request))
        .asserting(_ shouldEqual WorkUnitUpdateResult.NonPositiveDuration)

      transactor.executeCalls shouldEqual 1
      transactor.transactionalCalls shouldEqual 0
      verify(projectRepository).lockTableForReference()
      verify(workUnitRepository).find(WorkUnitFilters(id = Some(workUnit.id), organizationId = Some(organization.id), forUpdate = true))
      verify(workUnitRepository, never).update(any)
    }
    "return OrganizationNotFound before validating an invalid duration" in new TestCase {
      val orgSlug  = orgSlugGenerator.normal.generate
      val request  = stableRequest(duration = Some(neutral)).copy(projectId = Some(projectGenerator.normal.generate.id))
      val workUnit = stableWorkUnit()

      val orgFilter = OrganizationFilters(slug = Some(orgSlug))

      when(organizationRepository.find(orgFilter)).thenReturn(None.asDBIO)

      sut.update(authUser, orgSlug, workUnit.id, request).asserting(_ shouldEqual WorkUnitUpdateResult.OrganizationNotFound)

      verify(organizationRepository).find(orgFilter)
      verifyNoInteractions(organizationUserRepository, workUnitRepository, projectRepository, projectUserRepository, workUnitDtoService)
    }
    "return NoAccessToOrganization before validating an invalid duration" in new TestCase {
      val orgSlug      = orgSlugGenerator.normal.generate
      val request      = stableRequest(duration = Some(neutral)).copy(projectId = Some(projectGenerator.normal.generate.id))
      val workUnit     = stableWorkUnit()
      val organization = organizationGenerator.normal.generate

      val orgFilter     = OrganizationFilters(slug = Some(orgSlug))
      val orgUserFilter = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id))

      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(orgUserFilter)).thenReturn(None.asDBIO)

      sut.update(authUser, orgSlug, workUnit.id, request).asserting(_ shouldEqual WorkUnitUpdateResult.NoAccessToOrganization)

      verify(organizationRepository).find(orgFilter)
      verify(organizationUserRepository).find(orgUserFilter)
      verifyNoInteractions(workUnitRepository, projectRepository, projectUserRepository, workUnitDtoService)
    }
    "return WorkUnitNotFound before validating an invalid duration and lock the lookup" in new TestCase {
      val orgSlug      = orgSlugGenerator.normal.generate
      val request      = stableRequest(duration = Some(neutral))
      val workUnit     = stableWorkUnit()
      val organization = organizationGenerator.normal.generate
      val roles        = Seq(rolesGenerator.normal.generate)
      val orgUser      = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, roles, None)

      val orgFilter      = OrganizationFilters(slug = Some(orgSlug))
      val orgUserFilter  = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id))
      val workUnitFilter = WorkUnitFilters(id = Some(workUnit.id), organizationId = Some(organization.id), forUpdate = true)

      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(orgUserFilter)).thenReturn(Some(orgUser).asDBIO)
      when(workUnitRepository.find(workUnitFilter)).thenReturn(None.asDBIO)

      sut.update(authUser, orgSlug, workUnit.id, request).asserting(_ shouldEqual WorkUnitUpdateResult.WorkUnitNotFound)

      verify(organizationRepository).find(orgFilter)
      verify(organizationUserRepository).find(orgUserFilter)
      verify(workUnitRepository).find(workUnitFilter)
      verifyNoInteractions(projectRepository, projectUserRepository, workUnitDtoService)
      verify(workUnitRepository, never).update(any)
    }
    "return the same WorkUnitNotFound without writing for a service-account-owned work unit" in new TestCase {
      val request        = stableRequest()
      val serviceAccount = userGenerator.normal.generate.copy(accountType = UserAccountType.ServiceAccount)
      val workUnit       = stableWorkUnit().copy(userId = serviceAccount.id)
      val organization   = organizationGenerator.normal.generate
      val workUnitFilter = WorkUnitFilters(id = Some(workUnit.id), organizationId = Some(organization.id), forUpdate = true)

      when(organizationRepository.find(OrganizationFilters(slug = Some(organization.slug)))).thenReturn(Some(organization).asDBIO)
      when(workUnitRepository.find(workUnitFilter)).thenReturn(Some(workUnit).asDBIO)
      when(userRepository.find(UserFilters(id = Some(serviceAccount.id)))).thenReturn(Some(serviceAccount).asDBIO)

      sut.update(authAdmin, organization.slug, workUnit.id, request).asserting(_ shouldEqual WorkUnitUpdateResult.WorkUnitNotFound)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(organization.slug)))
      verify(workUnitRepository).find(workUnitFilter)
      verify(userRepository).find(UserFilters(id = Some(serviceAccount.id)))
      verify(workUnitRepository, never).update(any)
      verifyNoInteractions(projectRepository, projectUserRepository, workUnitDtoService)
    }
    "return NoAccessToWorkUnit before validating an invalid duration" in new TestCase {
      val orgSlug      = orgSlugGenerator.normal.generate
      val request      = stableRequest(duration = Some(neutral))
      val workUnit     = stableWorkUnit().copy(userId = authAdmin.id)
      val organization = organizationGenerator.normal.generate
      val roles        = Seq(OrganizationUserRole.Employee)
      val orgUser      = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, roles, None)

      val orgFilter      = OrganizationFilters(slug = Some(orgSlug))
      val orgUserFilter  = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id))
      val workUnitFilter = WorkUnitFilters(id = Some(workUnit.id), organizationId = Some(organization.id), forUpdate = true)

      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(orgUserFilter)).thenReturn(Some(orgUser).asDBIO)
      when(workUnitRepository.find(workUnitFilter)).thenReturn(Some(workUnit).asDBIO)

      sut.update(authUser, orgSlug, workUnit.id, request).asserting(_ shouldEqual WorkUnitUpdateResult.NoAccessToWorkUnit)

      verify(organizationRepository).find(orgFilter)
      verify(organizationUserRepository).find(orgUserFilter)
      verify(workUnitRepository).find(workUnitFilter)
      verifyNoInteractions(projectRepository, projectUserRepository, workUnitDtoService)
      verify(workUnitRepository, never).update(any)
    }
    "return WorkUnitInactive before validating an invalid duration" in new TestCase {
      val orgSlug      = orgSlugGenerator.normal.generate
      val request      = stableRequest(duration = Some(neutral))
      val workUnit     = stableWorkUnit(state = WorkUnitState.Inactive)
      val organization = organizationGenerator.normal.generate
      val roles        = Seq(OrganizationUserRole.Employee)
      val orgUser      = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, roles, None)

      val orgFilter      = OrganizationFilters(slug = Some(orgSlug))
      val orgUserFilter  = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id))
      val workUnitFilter = WorkUnitFilters(id = Some(workUnit.id), organizationId = Some(organization.id), forUpdate = true)

      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(orgUserFilter)).thenReturn(Some(orgUser).asDBIO)
      when(workUnitRepository.find(workUnitFilter)).thenReturn(Some(workUnit).asDBIO)

      sut.update(authUser, orgSlug, workUnit.id, request).asserting(_ shouldEqual WorkUnitUpdateResult.WorkUnitInactive)

      verify(organizationRepository).find(orgFilter)
      verify(organizationUserRepository).find(orgUserFilter)
      verify(workUnitRepository).find(workUnitFilter)
      verifyNoInteractions(projectRepository, projectUserRepository, workUnitDtoService)
      verify(workUnitRepository, never).update(any)
    }
    "return ProjectNotFound when user tries to update work unit to a project that doesn't exist" in new TestCase {
      val orgSlug      = orgSlugGenerator.normal.generate
      val request      = stableRequest().copy(projectId = Some(projectGenerator.normal.generate.id))
      val workUnit     = stableWorkUnit()
      val organization = organizationGenerator.normal.generate
      val roles        = Seq(OrganizationUserRole.Employee)
      val orgUser      = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, roles, None)

      val orgFilter      = OrganizationFilters(slug = Some(orgSlug))
      val orgUserFilter  = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id))
      val workUnitFilter = WorkUnitFilters(id = Some(workUnit.id), organizationId = Some(organization.id), forUpdate = true)
      val projectFilter  = ProjectFilters(id = request.projectId, organizationIds = Some(Seq(organization.id)))

      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(orgUserFilter)).thenReturn(Some(orgUser).asDBIO)
      when(projectRepository.lockTableForReference()).thenReturn(().asDBIO)
      when(workUnitRepository.find(workUnitFilter)).thenReturn(Some(workUnit).asDBIO)
      when(projectRepository.find(projectFilter)).thenReturn(None.asDBIO)

      sut.update(authUser, orgSlug, workUnit.id, request).asserting(_ shouldEqual WorkUnitUpdateResult.ProjectNotFound)

      verify(organizationRepository).find(orgFilter)
      verify(organizationUserRepository).find(orgUserFilter)
      val order = org.mockito.Mockito.inOrder(projectRepository, workUnitRepository)
      order.verify(projectRepository).lockTableForReference()
      order.verify(workUnitRepository).find(workUnitFilter)
      verify(projectRepository).find(projectFilter)
    }
    "return NoAccessToProject when user tries to update work unit to a project that he doesn't have permission" in new TestCase {
      val orgSlug      = orgSlugGenerator.normal.generate
      val project      = projectGenerator.normal.generate
      val request      = stableRequest().copy(projectId = Some(project.id))
      val workUnit     = stableWorkUnit()
      val organization = organizationGenerator.normal.generate
      val roles        = Seq(OrganizationUserRole.Employee)
      val orgUser      = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, roles, None)

      val orgFilter      = OrganizationFilters(slug = Some(orgSlug))
      val orgUserFilter  = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id))
      val workUnitFilter = WorkUnitFilters(id = Some(workUnit.id), organizationId = Some(organization.id), forUpdate = true)
      val projectFilter  = ProjectFilters(id = request.projectId, organizationIds = Some(Seq(organization.id)))
      val projectUserFilter = ProjectUserFilters(
        projectIds = request.projectId.map(Seq(_)),
        userId = Some(authUser.id),
        includeArchived = true,
        notDeletedAt = Some(workUnit.createdAt)
      )

      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(orgUserFilter)).thenReturn(Some(orgUser).asDBIO)
      when(projectRepository.lockTableForReference()).thenReturn(().asDBIO)
      when(workUnitRepository.find(workUnitFilter)).thenReturn(Some(workUnit).asDBIO)
      when(projectRepository.find(projectFilter)).thenReturn(Some(project).asDBIO)
      when(projectUserRepository.find(projectUserFilter)).thenReturn(None.asDBIO)

      sut.update(authUser, orgSlug, workUnit.id, request).asserting(_ shouldEqual WorkUnitUpdateResult.NoAccessToProject)

      verify(organizationRepository).find(orgFilter)
      verify(organizationUserRepository).find(orgUserFilter)
      verify(workUnitRepository).find(workUnitFilter)
      verify(projectRepository).lockTableForReference()
      verify(projectRepository).find(projectFilter)
      verify(projectUserRepository).find(projectUserFilter)
    }
    "assignment period" should {
      def periodCase(
        name: String,
        date: Option[WorkUnitDate],
        memberships: ProjectUser => Seq[ProjectUser],
        expected: WorkUnitUpdateResult => Boolean
      ) =
        name in new TestCase {
          val orgSlug      = orgSlugGenerator.normal.generate
          val organization = organizationGenerator.normal.generate
          val workUnit     = stableWorkUnit(date = WorkUnitDate(LocalDate.parse("2026-07-10")))
          val request      = stableRequest(date = date)
          val orgUser =
            OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, Seq(OrganizationUserRole.Employee), None)
          val base = projectUserGenerator.normal.generate.copy(projectId = workUnit.projectId, userId = authUser.id)

          val dto = workUnitDtoGenerator.normal.generate
          when(organizationRepository.find(OrganizationFilters(slug = Some(orgSlug)))).thenReturn(Some(organization).asDBIO)
          when(organizationUserRepository.find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id))))
            .thenReturn(Some(orgUser).asDBIO)
          when(workUnitRepository.find(WorkUnitFilters(id = Some(workUnit.id), organizationId = Some(organization.id), forUpdate = true)))
            .thenReturn(Some(workUnit).asDBIO)
          when(
            projectUserRepository
              .list(ProjectUserFilters(projectIds = Some(Seq(workUnit.projectId)), userId = Some(authUser.id), includeArchived = true))
          ).thenReturn(memberships(base).asDBIO)
          when(workUnitRepository.update(any)).thenAnswer((value: WorkUnit) => Some(value).asDBIO)
          when(workUnitDtoService.prepareDto(any)).thenReturn(dto.asDBIO)

          sut.update(authUser, orgSlug, workUnit.id, request).asserting(r => expected(r) shouldEqual true)
        }

      def detached(from: String, to: String)(pu: ProjectUser): ProjectUser =
        pu.copy(
          createdAt = pl.iterators.epoca.project.domain.ProjectDomain.ProjectUserCreatedAt(Instant.parse(from)),
          archivedAt = Some(pl.iterators.epoca.project.domain.ProjectDomain.ProjectUserArchivedAt(Instant.parse(to)))
        )

      periodCase(
        "allow moving a worklog to a date inside the detached period",
        Some(WorkUnitDate(LocalDate.parse("2026-07-12"))),
        pu => Seq(detached("2026-07-01T00:00:00Z", "2026-07-15T12:00:00Z")(pu)),
        _.isInstanceOf[WorkUnitUpdateResult.Updated]
      )
      periodCase(
        "return NoAccessToProject when moving a worklog to a date after detaching",
        Some(WorkUnitDate(LocalDate.parse("2026-07-20"))),
        pu => Seq(detached("2026-07-01T00:00:00Z", "2026-07-15T12:00:00Z")(pu)),
        _ == WorkUnitUpdateResult.NoAccessToProject
      )
      periodCase(
        "return NoAccessToProject when moving a worklog to a date before the assignment",
        Some(WorkUnitDate(LocalDate.parse("2026-06-20"))),
        pu => Seq(detached("2026-07-01T00:00:00Z", "2026-07-15T12:00:00Z")(pu)),
        _ == WorkUnitUpdateResult.NoAccessToProject
      )
      periodCase(
        "return NoAccessToProject when the user has no assignment at all",
        Some(WorkUnitDate(LocalDate.parse("2026-07-12"))),
        _ => Seq.empty,
        _ == WorkUnitUpdateResult.NoAccessToProject
      )
    }
    "allow editing a worklog of a detached project when its project and date do not change" in new TestCase {
      val orgSlug      = orgSlugGenerator.normal.generate
      val organization = organizationGenerator.normal.generate
      val workUnit     = stableWorkUnit()
      val request      = stableRequest(duration = Some(exactlySecond)).copy(projectId = Some(workUnit.projectId))
      val dto          = workUnitDtoGenerator.normal.generate
      val orgUser =
        OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, Seq(OrganizationUserRole.Employee), None)

      when(organizationRepository.find(OrganizationFilters(slug = Some(orgSlug)))).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id))))
        .thenReturn(Some(orgUser).asDBIO)
      when(projectRepository.lockTableForReference()).thenReturn(().asDBIO)
      when(workUnitRepository.find(WorkUnitFilters(id = Some(workUnit.id), organizationId = Some(organization.id), forUpdate = true)))
        .thenReturn(Some(workUnit).asDBIO)
      when(workUnitRepository.update(any)).thenAnswer((value: WorkUnit) => Some(value).asDBIO)
      when(workUnitDtoService.prepareDto(any)).thenReturn(dto.asDBIO)

      sut.update(authUser, orgSlug, workUnit.id, request).asserting(_ shouldEqual WorkUnitUpdateResult.Updated(dto))

      verifyNoInteractions(projectUserRepository)
    }
    "return Updated with service data otherwise" in new TestCase {
      val orgSlug      = orgSlugGenerator.normal.generate
      val project      = projectGenerator.normal.generate
      val request      = requestGenerator.maximal.generate.copy(projectId = Some(project.id), duration = Some(positive), date = None)
      val workUnit     = stableWorkUnit()
      val workUnitDto  = workUnitDtoGenerator.normal.generate
      val organization = organizationGenerator.normal.generate
      val roles        = Seq(OrganizationUserRole.Employee)
      val orgUser      = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, roles, None)
      val projectUser  = projectUserGenerator.normal.generate

      val orgFilter      = OrganizationFilters(slug = Some(orgSlug))
      val orgUserFilter  = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id))
      val workUnitFilter = WorkUnitFilters(id = Some(workUnit.id), organizationId = Some(organization.id), forUpdate = true)
      val projectFilter  = ProjectFilters(id = request.projectId, organizationIds = Some(Seq(organization.id)))
      val projectUserFilter = ProjectUserFilters(
        projectIds = request.projectId.map(Seq(_)),
        userId = Some(authUser.id),
        includeArchived = true,
        notDeletedAt = Some(workUnit.createdAt)
      )

      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(orgUserFilter)).thenReturn(Some(orgUser).asDBIO)
      when(projectRepository.lockTableForReference()).thenReturn(().asDBIO)
      when(workUnitRepository.find(workUnitFilter)).thenReturn(Some(workUnit).asDBIO)
      when(projectRepository.find(projectFilter)).thenReturn(Some(project).asDBIO)
      when(projectUserRepository.find(projectUserFilter)).thenReturn(Some(projectUser).asDBIO)
      when(projectUserRepository.list(any[ProjectUserFilters])).thenReturn(Seq(projectUser.copy(archivedAt = None)).asDBIO)
      when(workUnitRepository.update(any)).thenAnswer((value: WorkUnit) => Some(value).asDBIO)
      when(workUnitDtoService.prepareDto(any)).thenReturn(workUnitDto.asDBIO)

      sut.update(authUser, orgSlug, workUnit.id, request).asserting(_ shouldEqual WorkUnitUpdateResult.Updated(workUnitDto))

      verify(organizationRepository).find(orgFilter)
      verify(organizationUserRepository).find(orgUserFilter)
      val order = org.mockito.Mockito.inOrder(projectRepository, workUnitRepository)
      order.verify(projectRepository).lockTableForReference()
      order.verify(workUnitRepository).find(workUnitFilter)
      verify(projectRepository).find(projectFilter)
      verify(projectUserRepository).find(projectUserFilter)
      verify(workUnitRepository).update(argThat { unit: WorkUnit =>
        unit.modifiedAt != workUnit.modifiedAt &&
        unit.createdAt == workUnit.createdAt &&
        unit.duration == request.duration.get &&
        unit.state == WorkUnitState.Active &&
        unit.description == request.description.get &&
        unit.taskId == request.taskId.orElse(unit.taskId)
      })
      verify(workUnitDtoService).prepareDto(argThat { unit: WorkUnit =>
        unit.modifiedAt != workUnit.modifiedAt &&
        unit.createdAt == workUnit.createdAt &&
        unit.duration == request.duration.get &&
        unit.state == WorkUnitState.Active &&
        unit.description == request.description.get &&
        unit.taskId == request.taskId.orElse(unit.taskId)
      })
    }
    "not providing project id skips projectRepository and projectUserRepository actions" in new TestCase {
      val orgSlug      = orgSlugGenerator.normal.generate
      val request      = requestGenerator.maximal.generate.copy(projectId = None, duration = Some(positive), date = None)
      val workUnit     = stableWorkUnit()
      val workUnitDto  = workUnitDtoGenerator.normal.generate
      val organization = organizationGenerator.normal.generate
      val roles        = Seq(OrganizationUserRole.Employee)
      val orgUser      = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, roles, None)

      val orgFilter      = OrganizationFilters(slug = Some(orgSlug))
      val orgUserFilter  = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id))
      val workUnitFilter = WorkUnitFilters(id = Some(workUnit.id), organizationId = Some(organization.id), forUpdate = true)

      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(orgUserFilter)).thenReturn(Some(orgUser).asDBIO)
      when(workUnitRepository.find(workUnitFilter)).thenReturn(Some(workUnit).asDBIO)
      when(workUnitRepository.update(any)).thenAnswer((value: WorkUnit) => Some(value).asDBIO)
      when(workUnitDtoService.prepareDto(any)).thenReturn(workUnitDto.asDBIO)

      sut.update(authUser, orgSlug, workUnit.id, request).asserting(_ shouldEqual WorkUnitUpdateResult.Updated(workUnitDto))

      verify(organizationRepository).find(orgFilter)
      verify(organizationUserRepository).find(orgUserFilter)
      verify(workUnitRepository).find(workUnitFilter)
      verify(workUnitRepository).update(argThat { unit: WorkUnit =>
        unit.modifiedAt != workUnit.modifiedAt &&
        unit.createdAt == workUnit.createdAt &&
        unit.duration == request.duration.get &&
        unit.state == WorkUnitState.Active &&
        unit.description == request.description.get &&
        unit.taskId == request.taskId.orElse(unit.taskId)
      })
      verify(workUnitDtoService).prepareDto(argThat { unit: WorkUnit =>
        unit.modifiedAt != workUnit.modifiedAt &&
        unit.createdAt == workUnit.createdAt &&
        unit.duration == request.duration.get &&
        unit.state == WorkUnitState.Active &&
        unit.description == request.description.get &&
        unit.taskId == request.taskId.orElse(unit.taskId)
      })
      verifyNoInteractions(projectRepository, projectUserRepository)
    }
    "providing same project id locks the relation before the work unit but skips project lookups" in new TestCase {
      val orgSlug  = orgSlugGenerator.normal.generate
      val workUnit = stableWorkUnit()
      val request =
        requestGenerator.maximal.generate.copy(projectId = Some(workUnit.projectId), duration = Some(positive), date = None)
      val workUnitDto  = workUnitDtoGenerator.normal.generate
      val organization = organizationGenerator.normal.generate
      val roles        = Seq(OrganizationUserRole.Employee)
      val orgUser      = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, roles, None)

      val orgFilter      = OrganizationFilters(slug = Some(orgSlug))
      val orgUserFilter  = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id))
      val workUnitFilter = WorkUnitFilters(id = Some(workUnit.id), organizationId = Some(organization.id), forUpdate = true)

      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(orgUserFilter)).thenReturn(Some(orgUser).asDBIO)
      when(projectRepository.lockTableForReference()).thenReturn(().asDBIO)
      when(workUnitRepository.find(workUnitFilter)).thenReturn(Some(workUnit).asDBIO)
      when(workUnitRepository.update(any)).thenAnswer((value: WorkUnit) => Some(value).asDBIO)
      when(workUnitDtoService.prepareDto(any)).thenReturn(workUnitDto.asDBIO)

      sut.update(authUser, orgSlug, workUnit.id, request).asserting(_ shouldEqual WorkUnitUpdateResult.Updated(workUnitDto))

      verify(organizationRepository).find(orgFilter)
      verify(organizationUserRepository).find(orgUserFilter)
      val order = org.mockito.Mockito.inOrder(projectRepository, workUnitRepository)
      order.verify(projectRepository).lockTableForReference()
      order.verify(workUnitRepository).find(workUnitFilter)
      verify(projectRepository, never).find(any)
      verifyNoInteractions(projectUserRepository)
      verify(workUnitRepository).update(argThat { unit: WorkUnit =>
        unit.modifiedAt != workUnit.modifiedAt &&
        unit.createdAt == workUnit.createdAt &&
        unit.duration == request.duration.get &&
        unit.state == WorkUnitState.Active &&
        unit.description == request.description.get &&
        unit.taskId == request.taskId.orElse(unit.taskId)
      })
      verify(workUnitDtoService).prepareDto(argThat { unit: WorkUnit =>
        unit.modifiedAt != workUnit.modifiedAt &&
        unit.createdAt == workUnit.createdAt &&
        unit.duration == request.duration.get &&
        unit.state == WorkUnitState.Active &&
        unit.description == request.description.get &&
        unit.taskId == request.taskId.orElse(unit.taskId)
      })
    }
    "admin skips unnecessary interactions" in new TestCase {
      val orgSlug      = orgSlugGenerator.normal.generate
      val request      = requestGenerator.maximal.generate.copy(projectId = None, duration = Some(positive), date = None)
      val workUnit     = stableWorkUnit()
      val workUnitDto  = workUnitDtoGenerator.normal.generate
      val organization = organizationGenerator.normal.generate

      val orgFilter      = OrganizationFilters(slug = Some(orgSlug))
      val workUnitFilter = WorkUnitFilters(id = Some(workUnit.id), organizationId = Some(organization.id), forUpdate = true)

      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(workUnitRepository.find(workUnitFilter)).thenReturn(Some(workUnit).asDBIO)
      when(workUnitRepository.update(any)).thenAnswer((value: WorkUnit) => Some(value).asDBIO)
      when(workUnitDtoService.prepareDto(any)).thenReturn(workUnitDto.asDBIO)

      sut.update(authAdmin, orgSlug, workUnit.id, request).asserting(_ shouldEqual WorkUnitUpdateResult.Updated(workUnitDto))

      verify(organizationRepository).find(orgFilter)
      verify(workUnitRepository).find(workUnitFilter)
      verify(workUnitRepository).update(argThat { unit: WorkUnit =>
        unit.modifiedAt != workUnit.modifiedAt &&
        unit.createdAt == workUnit.createdAt &&
        request.duration.contains(unit.duration) &&
        unit.state == WorkUnitState.Active &&
        request.description.contains(unit.description) &&
        unit.taskId == request.taskId.orElse(unit.taskId)
      })
      verify(workUnitDtoService).prepareDto(argThat { unit: WorkUnit =>
        unit.modifiedAt != workUnit.modifiedAt &&
        unit.createdAt == workUnit.createdAt &&
        request.duration.contains(unit.duration) &&
        unit.state == WorkUnitState.Active &&
        request.description.contains(unit.description) &&
        unit.taskId == request.taskId.orElse(unit.taskId)
      })
    }
    "updates draft" in new TestCase {
      val orgSlug = orgSlugGenerator.normal.generate
      val request =
        requestGenerator.maximal.generate.copy(projectId = None, duration = Some(positive), date = None, shouldFinalize = false)
      val workUnit     = stableWorkUnit(state = WorkUnitState.Draft)
      val workUnitDto  = workUnitDtoGenerator.normal.generate
      val organization = organizationGenerator.normal.generate

      val orgFilter      = OrganizationFilters(slug = Some(orgSlug))
      val workUnitFilter = WorkUnitFilters(id = Some(workUnit.id), organizationId = Some(organization.id), forUpdate = true)

      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(workUnitRepository.find(workUnitFilter)).thenReturn(Some(workUnit).asDBIO)
      when(workUnitRepository.update(any)).thenAnswer((value: WorkUnit) => Some(value).asDBIO)
      when(workUnitDtoService.prepareDto(any)).thenReturn(workUnitDto.asDBIO)

      sut.update(authAdmin, orgSlug, workUnit.id, request).asserting(_ shouldEqual WorkUnitUpdateResult.Updated(workUnitDto))

      verify(organizationRepository).find(orgFilter)
      verify(workUnitRepository).find(workUnitFilter)
      verify(workUnitRepository).update(argThat { unit: WorkUnit =>
        unit.modifiedAt != workUnit.modifiedAt &&
        unit.createdAt == workUnit.createdAt &&
        unit.duration == request.duration.get &&
        unit.state == WorkUnitState.Draft &&
        unit.description == request.description.get &&
        unit.taskId == request.taskId.orElse(unit.taskId)
      })
      verify(workUnitDtoService).prepareDto(argThat { unit: WorkUnit =>
        unit.modifiedAt != workUnit.modifiedAt &&
        unit.createdAt == workUnit.createdAt &&
        unit.duration == request.duration.get &&
        unit.state == WorkUnitState.Draft &&
        unit.description == request.description.get &&
        unit.taskId == request.taskId.orElse(unit.taskId)
      })
    }
    "finalize draft with created at" in new TestCase {
      val orgSlug = orgSlugGenerator.normal.generate
      val request =
        requestGenerator.maximal.generate.copy(projectId = None, shouldFinalize = true, duration = Some(positive), date = None)
      val workUnit     = stableWorkUnit(state = WorkUnitState.Draft).copy(createdAt = pastCreatedAt, modifiedAt = pastModifiedAt)
      val workUnitDto  = workUnitDtoGenerator.normal.generate
      val organization = organizationGenerator.normal.generate

      val orgFilter      = OrganizationFilters(slug = Some(orgSlug))
      val workUnitFilter = WorkUnitFilters(id = Some(workUnit.id), organizationId = Some(organization.id), forUpdate = true)

      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(workUnitRepository.find(workUnitFilter)).thenReturn(Some(workUnit).asDBIO)
      when(workUnitRepository.update(any)).thenAnswer((value: WorkUnit) => Some(value).asDBIO)
      when(workUnitDtoService.prepareDto(any)).thenReturn(workUnitDto.asDBIO)

      sut.update(authAdmin, orgSlug, workUnit.id, request).asserting(_ shouldEqual WorkUnitUpdateResult.Updated(workUnitDto))

      verify(organizationRepository).find(orgFilter)
      verify(workUnitRepository).find(workUnitFilter)
      verify(workUnitRepository).update(argThat { unit: WorkUnit =>
        unit.modifiedAt != workUnit.modifiedAt &&
        unit.createdAt != workUnit.createdAt &&
        unit.duration == request.duration.get &&
        unit.state == WorkUnitState.Active &&
        unit.description == request.description.get &&
        unit.taskId == request.taskId.orElse(unit.taskId)
      })
      verify(workUnitDtoService).prepareDto(argThat { unit: WorkUnit =>
        unit.modifiedAt != workUnit.modifiedAt &&
        unit.createdAt != workUnit.createdAt &&
        unit.duration == request.duration.get &&
        unit.state == WorkUnitState.Active &&
        unit.description == request.description.get &&
        unit.taskId == request.taskId.orElse(unit.taskId)
      })
    }
    "Owner can edit other members work units" in new TestCase {
      val orgSlug      = orgSlugGenerator.normal.generate
      val project      = projectGenerator.normal.generate
      val request      = requestGenerator.maximal.generate.copy(projectId = Some(project.id), duration = Some(positive), date = None)
      val workUnit     = stableWorkUnit().copy(userId = authAdmin.id)
      val workUnitDto  = workUnitDtoGenerator.normal.generate
      val organization = organizationGenerator.normal.generate
      val roles        = Seq(OrganizationUserRole.Owner)
      val orgUser      = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, roles, None)
      val projectUser  = projectUserGenerator.normal.generate

      val orgFilter      = OrganizationFilters(slug = Some(orgSlug))
      val orgUserFilter  = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id))
      val workUnitFilter = WorkUnitFilters(id = Some(workUnit.id), organizationId = Some(organization.id), forUpdate = true)
      val projectFilter  = ProjectFilters(id = request.projectId, organizationIds = Some(Seq(organization.id)))
      val projectUserFilter = ProjectUserFilters(
        projectIds = request.projectId.map(Seq(_)),
        userId = Some(authUser.id),
        includeArchived = true,
        notDeletedAt = Some(workUnit.createdAt)
      )

      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(orgUserFilter)).thenReturn(Some(orgUser).asDBIO)
      when(projectRepository.lockTableForReference()).thenReturn(().asDBIO)
      when(workUnitRepository.find(workUnitFilter)).thenReturn(Some(workUnit).asDBIO)
      when(projectRepository.find(projectFilter)).thenReturn(Some(project).asDBIO)
      when(projectUserRepository.find(projectUserFilter)).thenReturn(Some(projectUser).asDBIO)
      when(workUnitRepository.update(any)).thenAnswer((value: WorkUnit) => Some(value).asDBIO)
      when(workUnitDtoService.prepareDto(any)).thenReturn(workUnitDto.asDBIO)

      sut.update(authUser, orgSlug, workUnit.id, request).asserting(_ shouldEqual WorkUnitUpdateResult.Updated(workUnitDto))

      verify(organizationRepository).find(orgFilter)
      verify(organizationUserRepository).find(orgUserFilter)
      verify(workUnitRepository).find(workUnitFilter)
      verify(projectRepository).lockTableForReference()
      verify(projectRepository).find(projectFilter)
      verify(projectUserRepository).find(projectUserFilter)
      verify(workUnitRepository).update(argThat { unit: WorkUnit =>
        unit.modifiedAt != workUnit.modifiedAt &&
        unit.createdAt == workUnit.createdAt &&
        unit.duration == request.duration.get &&
        unit.state == WorkUnitState.Active &&
        unit.description == request.description.get &&
        unit.taskId == request.taskId.orElse(unit.taskId)
      })
      verify(workUnitDtoService).prepareDto(argThat { unit: WorkUnit =>
        unit.modifiedAt != workUnit.modifiedAt &&
        unit.createdAt == workUnit.createdAt &&
        unit.duration == request.duration.get &&
        unit.state == WorkUnitState.Active &&
        unit.description == request.description.get &&
        unit.taskId == request.taskId.orElse(unit.taskId)
      })
    }

    "preserve a neutral Note duration when a description-only patch omits duration" in new TestCase {
      val workUnit            = stableWorkUnit(entryType = WorkUnitEntryType.Note, duration = neutral)
      val request             = stableRequest().copy(description = Some(workUnit.description))
      val (organization, dto) = stubSuccessfulAdminUpdate(workUnit)

      sut.update(authAdmin, organization.slug, workUnit.id, request).asserting(_ shouldEqual WorkUnitUpdateResult.Updated(dto))

      verify(workUnitRepository).find(WorkUnitFilters(id = Some(workUnit.id), organizationId = Some(organization.id), forUpdate = true))
      verify(workUnitRepository).update(argThat { updated: WorkUnit =>
        updated.duration == neutral && updated.entryType == WorkUnitEntryType.Note && updated.userId == workUnit.userId
      })
    }

    "accept an explicit neutral Note duration" in new TestCase {
      val workUnit            = stableWorkUnit(entryType = WorkUnitEntryType.Note, duration = neutral)
      val request             = stableRequest(duration = Some(neutral))
      val (organization, dto) = stubSuccessfulAdminUpdate(workUnit)

      sut.update(authAdmin, organization.slug, workUnit.id, request).asserting(_ shouldEqual WorkUnitUpdateResult.Updated(dto))

      verify(workUnitRepository).update(argThat { updated: WorkUnit =>
        updated.duration == neutral && updated.entryType == workUnit.entryType && updated.userId == workUnit.userId
      })
    }

    Seq(
      "positive"           -> positive,
      "negative"           -> WorkUnitDuration(Duration.ofSeconds(-1L)),
      "positive subsecond" -> subsecond,
      "negative subsecond" -> WorkUnitDuration(Duration.ofMillis(-500L))
    ).foreach { case (name, invalidDuration) =>
      s"reject a $name Note duration as NonNeutralNoteDuration after locking the target" in new TestCase {
        val workUnit          = stableWorkUnit(entryType = WorkUnitEntryType.Note, duration = neutral)
        val request           = stableRequest(duration = Some(invalidDuration))
        val (organization, _) = stubSuccessfulAdminUpdate(workUnit)

        sut
          .update(authAdmin, organization.slug, workUnit.id, request)
          .asserting(_ shouldEqual WorkUnitUpdateResult.NonNeutralNoteDuration)

        verify(workUnitRepository).find(WorkUnitFilters(id = Some(workUnit.id), organizationId = Some(organization.id), forUpdate = true))
        verify(workUnitRepository, never).update(any)
        verifyNoInteractions(projectRepository, projectUserRepository)
      }
    }

    "preserve an existing positive Worklog duration when duration is omitted" in new TestCase {
      val workUnit            = stableWorkUnit(duration = positive)
      val request             = stableRequest().copy(description = Some(workUnit.description))
      val (organization, dto) = stubSuccessfulAdminUpdate(workUnit)

      sut.update(authAdmin, organization.slug, workUnit.id, request).asserting(_ shouldEqual WorkUnitUpdateResult.Updated(dto))

      verify(workUnitRepository).update(argThat { updated: WorkUnit =>
        updated.duration == positive && updated.entryType == WorkUnitEntryType.Worklog && updated.userId == workUnit.userId
      })
    }

    "accept an explicit one-second Worklog duration" in new TestCase {
      val workUnit            = stableWorkUnit()
      val request             = stableRequest(duration = Some(exactlySecond))
      val (organization, dto) = stubSuccessfulAdminUpdate(workUnit)

      sut.update(authAdmin, organization.slug, workUnit.id, request).asserting(_ shouldEqual WorkUnitUpdateResult.Updated(dto))

      verify(workUnitRepository).update(argThat { updated: WorkUnit =>
        updated.duration == exactlySecond && updated.entryType == workUnit.entryType && updated.userId == workUnit.userId
      })
    }

    Seq("zero" -> neutral, "negative" -> WorkUnitDuration(Duration.ofSeconds(-1L)), "positive subsecond" -> subsecond).foreach {
      case (name, invalidDuration) =>
        s"reject a $name Worklog duration as NonPositiveDuration after locking the target" in new TestCase {
          val workUnit          = stableWorkUnit()
          val request           = stableRequest(duration = Some(invalidDuration))
          val (organization, _) = stubSuccessfulAdminUpdate(workUnit)

          sut.update(authAdmin, organization.slug, workUnit.id, request).asserting(_ shouldEqual WorkUnitUpdateResult.NonPositiveDuration)

          verify(workUnitRepository).find(WorkUnitFilters(id = Some(workUnit.id), organizationId = Some(organization.id), forUpdate = true))
          verify(workUnitRepository, never).update(any)
          verifyNoInteractions(projectRepository, projectUserRepository)
        }
    }

    "finalize a future Note draft and refresh its state and createdAt" in new TestCase {
      val workUnit = stableWorkUnit(entryType = WorkUnitEntryType.Note, duration = neutral, date = tomorrow, state = WorkUnitState.Draft)
        .copy(createdAt = pastCreatedAt)
      val request             = stableRequest(shouldFinalize = true)
      val (organization, dto) = stubSuccessfulAdminUpdate(workUnit)

      sut.update(authAdmin, organization.slug, workUnit.id, request).asserting(_ shouldEqual WorkUnitUpdateResult.Updated(dto))

      verify(workUnitRepository).update(argThat { updated: WorkUnit =>
        updated.state == WorkUnitState.Active &&
        updated.createdAt != workUnit.createdAt &&
        updated.date == tomorrow &&
        updated.entryType == WorkUnitEntryType.Note &&
        updated.userId == workUnit.userId
      })
    }

    "reject finalizing a future Worklog draft" in new TestCase {
      val workUnit          = stableWorkUnit(date = tomorrow, state = WorkUnitState.Draft)
      val request           = stableRequest(shouldFinalize = true)
      val (organization, _) = stubSuccessfulAdminUpdate(workUnit)

      sut.update(authAdmin, organization.slug, workUnit.id, request).asserting(_ shouldEqual WorkUnitUpdateResult.FutureWorklogDate)

      verify(workUnitRepository, never).update(any)
    }

    "reject an explicit future Worklog date" in new TestCase {
      val workUnit          = stableWorkUnit()
      val request           = stableRequest(date = Some(tomorrow))
      val (organization, _) = stubSuccessfulAdminUpdate(workUnit)

      sut.update(authAdmin, organization.slug, workUnit.id, request).asserting(_ shouldEqual WorkUnitUpdateResult.FutureWorklogDate)

      verify(workUnitRepository, never).update(any)
    }

    "reject an explicitly submitted Worklog date even when it equals the existing future date" in new TestCase {
      val workUnit          = stableWorkUnit(date = tomorrow)
      val request           = stableRequest(date = Some(tomorrow))
      val (organization, _) = stubSuccessfulAdminUpdate(workUnit)

      sut.update(authAdmin, organization.slug, workUnit.id, request).asserting(_ shouldEqual WorkUnitUpdateResult.FutureWorklogDate)

      verify(workUnitRepository, never).update(any)
    }

    "allow a description-only patch of a historical future Active Worklog" in new TestCase {
      val workUnit            = stableWorkUnit(date = tomorrow)
      val request             = stableRequest().copy(description = Some(workUnit.description))
      val (organization, dto) = stubSuccessfulAdminUpdate(workUnit)

      sut.update(authAdmin, organization.slug, workUnit.id, request).asserting(_ shouldEqual WorkUnitUpdateResult.Updated(dto))

      verify(workUnitRepository).update(argThat { updated: WorkUnit =>
        updated.date == tomorrow && updated.state == WorkUnitState.Active
      })
    }

    "allow correcting a future Worklog date to business today" in new TestCase {
      val workUnit            = stableWorkUnit(date = tomorrow)
      val request             = stableRequest(date = Some(today))
      val (organization, dto) = stubSuccessfulAdminUpdate(workUnit)

      sut.update(authAdmin, organization.slug, workUnit.id, request).asserting(_ shouldEqual WorkUnitUpdateResult.Updated(dto))

      verify(workUnitRepository).update(argThat { updated: WorkUnit => updated.date == today })
    }

    "allow an explicit future Note date" in new TestCase {
      val workUnit            = stableWorkUnit(entryType = WorkUnitEntryType.Note, duration = neutral)
      val request             = stableRequest(duration = Some(neutral), date = Some(tomorrow))
      val (organization, dto) = stubSuccessfulAdminUpdate(workUnit)

      sut.update(authAdmin, organization.slug, workUnit.id, request).asserting(_ shouldEqual WorkUnitUpdateResult.Updated(dto))

      verify(workUnitRepository).update(argThat { updated: WorkUnit =>
        updated.date == tomorrow && updated.entryType == WorkUnitEntryType.Note && updated.userId == workUnit.userId
      })
    }

    "allow a description-only patch of a future Worklog draft without finalizing it" in new TestCase {
      val workUnit            = stableWorkUnit(date = tomorrow, state = WorkUnitState.Draft)
      val request             = stableRequest().copy(description = Some(workUnit.description))
      val (organization, dto) = stubSuccessfulAdminUpdate(workUnit)

      sut.update(authAdmin, organization.slug, workUnit.id, request).asserting(_ shouldEqual WorkUnitUpdateResult.Updated(dto))

      verify(workUnitRepository).update(argThat { updated: WorkUnit =>
        updated.date == tomorrow && updated.state == WorkUnitState.Draft
      })
    }

    "validate policy before checking or updating a changed project" in new TestCase {
      val workUnit          = stableWorkUnit()
      val changedProject    = projectGenerator.normal.generate
      val request           = stableRequest(duration = Some(neutral)).copy(projectId = Some(changedProject.id))
      val (organization, _) = stubSuccessfulAdminUpdate(workUnit)
      when(projectRepository.lockTableForReference()).thenReturn(().asDBIO)

      sut.update(authAdmin, organization.slug, workUnit.id, request).asserting(_ shouldEqual WorkUnitUpdateResult.NonPositiveDuration)

      val order = org.mockito.Mockito.inOrder(projectRepository, workUnitRepository)
      order.verify(projectRepository).lockTableForReference()
      order
        .verify(workUnitRepository)
        .find(WorkUnitFilters(id = Some(workUnit.id), organizationId = Some(organization.id), forUpdate = true))
      verify(projectRepository, never).find(any)
      verifyNoInteractions(projectUserRepository)
      verify(workUnitRepository, never).update(any)
    }

    Seq(OrganizationUserRole.Manager, OrganizationUserRole.Employee, OrganizationUserRole.External).foreach { role =>
      s"deny $role access to another user's WorkUnit before validating policy" in new TestCase {
        val organization = organizationGenerator.normal.generate
        val workUnit     = stableWorkUnit().copy(userId = authAdmin.id)
        val request      = stableRequest(duration = Some(neutral))
        val orgUser      = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, Seq(role), None)

        when(organizationRepository.find(OrganizationFilters(slug = Some(organization.slug)))).thenReturn(Some(organization).asDBIO)
        when(organizationUserRepository.find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id))))
          .thenReturn(Some(orgUser).asDBIO)
        when(workUnitRepository.find(WorkUnitFilters(id = Some(workUnit.id), organizationId = Some(organization.id), forUpdate = true)))
          .thenReturn(Some(workUnit).asDBIO)

        sut.update(authUser, organization.slug, workUnit.id, request).asserting(_ shouldEqual WorkUnitUpdateResult.NoAccessToWorkUnit)

        verifyNoInteractions(projectRepository, projectUserRepository, workUnitDtoService)
        verify(workUnitRepository, never).update(any)
      }
    }

    "allow External to update their own WorkUnit under the existing access matrix" in new TestCase {
      val organization = organizationGenerator.normal.generate
      val workUnit     = stableWorkUnit()
      val request      = stableRequest().copy(description = Some(workUnit.description))
      val orgUser      = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, Seq(OrganizationUserRole.External), None)
      val dto          = workUnitDtoGenerator.normal.generate

      val orgFilter      = OrganizationFilters(slug = Some(organization.slug))
      val orgUserFilter  = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id))
      val workUnitFilter = WorkUnitFilters(id = Some(workUnit.id), organizationId = Some(organization.id), forUpdate = true)

      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(orgUserFilter)).thenReturn(Some(orgUser).asDBIO)
      when(workUnitRepository.find(workUnitFilter)).thenReturn(Some(workUnit).asDBIO)
      when(workUnitRepository.update(any)).thenAnswer((value: WorkUnit) => Some(value).asDBIO)
      when(workUnitDtoService.prepareDto(any)).thenReturn(dto.asDBIO)

      sut.update(authUser, organization.slug, workUnit.id, request).asserting(_ shouldEqual WorkUnitUpdateResult.Updated(dto))

      verify(organizationUserRepository).find(orgUserFilter)
      verify(workUnitRepository).find(workUnitFilter)
      verify(workUnitRepository).update(argThat { updated: WorkUnit =>
        updated.userId == workUnit.userId && updated.entryType == workUnit.entryType && updated.duration == workUnit.duration
      })
      verifyNoInteractions(projectRepository, projectUserRepository)
    }

    "preserve immutable entryType and userId across a full allowed patch" in new TestCase {
      val workUnit = stableWorkUnit(entryType = WorkUnitEntryType.Note, duration = neutral)
      val request = requestGenerator.maximal.generate.copy(
        projectId = Some(workUnit.projectId),
        duration = Some(neutral),
        date = Some(tomorrow),
        shouldFinalize = false
      )
      val (organization, dto) = stubSuccessfulAdminUpdate(workUnit)
      when(projectRepository.lockTableForReference()).thenReturn(().asDBIO)

      sut.update(authAdmin, organization.slug, workUnit.id, request).asserting(_ shouldEqual WorkUnitUpdateResult.Updated(dto))

      verify(projectRepository).lockTableForReference()
      verify(projectRepository, never).find(any)
      verifyNoInteractions(projectUserRepository)
      verify(workUnitRepository).update(argThat { updated: WorkUnit =>
        updated.projectId == request.projectId.get &&
        updated.date == request.date.get &&
        updated.duration == request.duration.get &&
        updated.description == request.description.get &&
        updated.taskId == request.taskId &&
        updated.entryType == workUnit.entryType &&
        updated.userId == workUnit.userId &&
        updated.organizationId == workUnit.organizationId &&
        updated.createdAt == workUnit.createdAt &&
        updated.modifiedAt != workUnit.modifiedAt &&
        updated.state == workUnit.state &&
        updated.archivedAt == workUnit.archivedAt
      })
    }
  }
}

object WorkUnitUpdateServiceSpec {
  final class RecordingTransactor(implicit runtime: IORuntime) extends FakeTransactor {
    var executeCalls: Int       = 0
    var transactionalCalls: Int = 0

    override def execute[T](action: => DBIO[T]): IO[T] = {
      executeCalls += 1
      super.execute(action)
    }

    override def executeTransactionally[T](action: => DBIO[T]): IO[T] = {
      transactionalCalls += 1
      super.executeTransactionally(action)
    }

    override def executeTransactionally[T](level: IsolationLevel)(action: => DBIO[T]): IO[T] = {
      transactionalCalls += 1
      super.executeTransactionally(level)(action)
    }
  }
}
