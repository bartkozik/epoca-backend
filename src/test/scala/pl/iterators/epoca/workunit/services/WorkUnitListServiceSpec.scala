package pl.iterators.epoca.workunit.services

import cats.effect.IO
import org.postgresql.util.{PSQLException, PSQLState}
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
import pl.iterators.epoca.user.domain.UserDomain.UserIsSuperAdmin
import pl.iterators.epoca.user.repositories.UserRepository
import pl.iterators.epoca.user.repositories.UserRepository.UserFilters
import pl.iterators.epoca.utils.ServiceSpec
import pl.iterators.epoca.utils.db.PaginatedResult
import pl.iterators.epoca.utils.transactor.{FakeTransactor, IsolationLevel, TransactorCall}
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.{WorkUnitDuration, WorkUnitEntryType, WorkUnitId, WorkUnitState}
import pl.iterators.epoca.workunit.domain.{WorkUnit, WorkUnitOrder}
import pl.iterators.epoca.workunit.repositories.WorkUnitOrderRepository.WorkUnitOrderFilters
import pl.iterators.epoca.workunit.repositories.WorkUnitRepository.WorkUnitFilters
import pl.iterators.epoca.workunit.repositories.{WorkUnitOrderRepository, WorkUnitRepository}
import pl.iterators.epoca.workunit.services.WorkUnitDtoService.WorkUnitDto
import pl.iterators.epoca.workunit.services.WorkUnitListService.{WorkUnitListData, WorkUnitListOrderEntry, WorkUnitListResult}
import slick.dbio.DBIO

import java.sql.SQLException
import java.time.Duration
import java.util.UUID

class WorkUnitListServiceSpec extends ServiceSpec {
  lazy val organizationGenerator: AllGenerators[Organization]         = allGenerators[Organization]
  lazy val orgSlugGenerator: AllGenerators[OrganizationSlug]          = allGenerators[OrganizationSlug]
  lazy val workUnitGenerator: AllGenerators[WorkUnit]                 = allGenerators[WorkUnit]
  lazy val workUnitsGenerator: AllGenerators[Seq[WorkUnit]]           = allGenerators[Seq[WorkUnit]]
  lazy val workUnitOrdersGenerator: AllGenerators[Seq[WorkUnitOrder]] = allGenerators[Seq[WorkUnitOrder]]
  lazy val workUnitDtosGenerator: AllGenerators[Seq[WorkUnitDto]]     = allGenerators[Seq[WorkUnitDto]]
  lazy val workUnitDtoGenerator: AllGenerators[WorkUnitDto]           = allGenerators[WorkUnitDto]
  lazy val workUnitFilterGenerator: AllGenerators[WorkUnitFilters]    = allGenerators[WorkUnitFilters]
  lazy val projectGenerator: AllGenerators[Project]                   = allGenerators[Project]
  lazy val projectUserGenerator: AllGenerators[ProjectUser]           = allGenerators[ProjectUser]
  lazy val userGenerator: AllGenerators[User]                         = allGenerators[User]

  trait TestCase extends BaseScope {
    val workUnitRepository: WorkUnitRepository                 = vmock[WorkUnitRepository]
    val workUnitOrderRepository: WorkUnitOrderRepository       = vmock[WorkUnitOrderRepository]
    val organizationRepository: OrganizationRepository         = vmock[OrganizationRepository]
    val organizationUserRepository: OrganizationUserRepository = vmock[OrganizationUserRepository]
    val projectRepository: ProjectRepository                   = mock[ProjectRepository]
    val projectUserRepository: ProjectUserRepository           = mock[ProjectUserRepository]
    val userRepository: UserRepository                         = mock[UserRepository]
    val workUnitDtoService: WorkUnitDtoService                 = vmock[WorkUnitDtoService]

    when(userRepository.find(any[UserFilters])).thenAnswer { filter: UserFilters =>
      Some(
        userGenerator.normal.generate.copy(
          id = filter.id.get,
          isSuperAdmin = pl.iterators.epoca.user.domain.UserDomain.UserIsSuperAdmin(filter.id.contains(authAdmin.id)),
          archivedAt = None
        )
      ).asDBIO
    }
    when(projectRepository.list(any[ProjectFilters])).thenReturn(Seq.empty[Project].asDBIO)
    when(projectUserRepository.list(any[ProjectUserFilters])).thenReturn(Seq.empty[ProjectUser].asDBIO)

    def conservative(dto: WorkUnitDto): WorkUnitDto =
      dto.copy(`override` = dto.`override`.copy(permissions = WorkUnitOverridePermissionsDto()))

    lazy val sut: WorkUnitListService = wire[WorkUnitListService]

    def ordinary(filter: WorkUnitFilters): WorkUnitFilters =
      filter
  }

  "list" should {
    "return OrganizationNotFound when user tries to list work units in non existing organization" in new TestCase {
      val orgSlug = orgSlugGenerator.normal.generate

      val workUnitFilters = ordinary(workUnitFilterGenerator.normal.generate)
      val orgFilter       = OrganizationFilters(slug = Some(orgSlug))

      when(organizationRepository.find(orgFilter)).thenReturn(None.asDBIO)

      sut.list(authUser, orgSlug, workUnitFilters).asserting(_ shouldEqual WorkUnitListResult.OrganizationNotFound)

      verify(organizationRepository).find(orgFilter)
    }
    "return NoAccessToOrganization when user tries to list work units in non accessible organization" in new TestCase {
      val orgSlug      = orgSlugGenerator.normal.generate
      val organization = organizationGenerator.normal.generate

      val workUnitFilters = ordinary(workUnitFilterGenerator.normal.generate)
      val orgFilter       = OrganizationFilters(slug = Some(orgSlug))
      val orgUserFilter   = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id))

      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(orgUserFilter)).thenReturn(None.asDBIO)

      sut.list(authUser, orgSlug, workUnitFilters).asserting(_ shouldEqual WorkUnitListResult.NoAccessToOrganization)

      verify(organizationRepository).find(orgFilter)
      verify(organizationUserRepository).find(orgUserFilter)
    }
    "return Ok with service data when manager tries to list work units [no restrict] without order - skip interactions" in new TestCase {
      val orgSlug      = orgSlugGenerator.normal.generate
      val organization = organizationGenerator.normal.generate
      val orgUser      = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, Seq(OrganizationUserRole.Manager), None)
      val workUnits    = workUnitsGenerator.normal.generate
      val workUnitDtos = workUnitDtosGenerator.normal.generate.map(conservative)

      val workUnitFilters           = ordinary(workUnitFilterGenerator.normal.generate).copy(includeArchived = false, userIds = None, states = None)
      val workUnitFiltersRestricted = workUnitFilters.copy(organizationId = Some(organization.id), states = Some(WorkUnitState.liveStates))
      val orgFilter                 = OrganizationFilters(slug = Some(orgSlug))
      val orgUserFilter             = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id))

      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(orgUserFilter)).thenReturn(Some(orgUser).asDBIO)
      when(workUnitRepository.list(workUnitFiltersRestricted)).thenReturn(workUnits.asDBIO)
      when(workUnitRepository.count(workUnitFiltersRestricted)).thenReturn(workUnits.size.asDBIO)
      when(workUnitDtoService.prepareDtos(workUnits)).thenReturn(workUnitDtos.asDBIO)

      val result = WorkUnitListData(PaginatedResult(workUnits.size, workUnitDtos), Nil)
      sut.list(authUser, orgSlug, workUnitFilters).asserting(_ shouldEqual WorkUnitListResult.Ok(result))

      verify(organizationRepository).find(orgFilter)
      verify(organizationUserRepository).find(orgUserFilter)
      verify(workUnitRepository).list(workUnitFiltersRestricted)
      verify(workUnitRepository).count(workUnitFiltersRestricted)
      verify(workUnitDtoService).prepareDtos(workUnits)
    }
    "read the page and the count in a single RepeatableRead transaction" in new TestCase {
      val orgSlug      = orgSlugGenerator.normal.generate
      val organization = organizationGenerator.normal.generate
      val orgUser      = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, Seq(OrganizationUserRole.Manager), None)
      val workUnits    = workUnitsGenerator.normal.generate
      val workUnitDtos = workUnitDtosGenerator.normal.generate.map(conservative)

      val workUnitFilters           = ordinary(workUnitFilterGenerator.normal.generate).copy(includeArchived = false, userIds = None, states = None)
      val workUnitFiltersRestricted = workUnitFilters.copy(organizationId = Some(organization.id), states = Some(WorkUnitState.liveStates))
      val orgFilter                 = OrganizationFilters(slug = Some(orgSlug))
      val orgUserFilter             = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id))

      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(orgUserFilter)).thenReturn(Some(orgUser).asDBIO)
      when(workUnitRepository.list(workUnitFiltersRestricted)).thenReturn(workUnits.asDBIO)
      when(workUnitRepository.count(workUnitFiltersRestricted)).thenReturn(workUnits.size.asDBIO)
      when(workUnitDtoService.prepareDtos(workUnits)).thenReturn(workUnitDtos.asDBIO)

      sut
        .list(authUser, orgSlug, workUnitFilters)
        .asserting(_ => transactorCalls shouldEqual Seq(TransactorCall.ExecuteTransactionallyAt(IsolationLevel.RepeatableRead)))

      verify(organizationRepository).find(orgFilter)
      verify(organizationUserRepository).find(orgUserFilter)
      verify(workUnitRepository).list(workUnitFiltersRestricted)
      verify(workUnitRepository).count(workUnitFiltersRestricted)
      verify(workUnitDtoService).prepareDtos(workUnits)
    }
    "return Ok with service data when manager tries to list work units [no restrict] with order" in new TestCase {
      val orgSlug      = orgSlugGenerator.normal.generate
      val organization = organizationGenerator.normal.generate
      val orgUser      = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, Seq(OrganizationUserRole.Manager), None)
      val workUnits    = workUnitsGenerator.normal.generate
      val workUnitDtos = workUnitDtosGenerator.normal.generate.map(conservative)

      val workUnitFilters           = ordinary(workUnitFilterGenerator.normal.generate).copy(includeArchived = false, userIds = None, states = None)
      val workUnitFiltersRestricted = workUnitFilters.copy(organizationId = Some(organization.id), states = Some(WorkUnitState.liveStates))
      val orgFilter                 = OrganizationFilters(slug = Some(orgSlug))
      val orgUserFilter             = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id))
      val orderFilters = WorkUnitOrderFilters(
        organizationId = Some(organization.id),
        userId = Some(authUser.id),
        dateFrom = workUnitFilters.dateFrom,
        dateTo = workUnitFilters.dateTo
      )

      val orders = workUnitOrdersGenerator.normal.generate
      val orderMap = workUnits.groupBy(_.date).map { case (date, units) =>
        date -> units.map(_.id)
      }

      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(orgUserFilter)).thenReturn(Some(orgUser).asDBIO)
      when(workUnitRepository.list(workUnitFiltersRestricted)).thenReturn(workUnits.asDBIO)
      when(workUnitRepository.count(workUnitFiltersRestricted)).thenReturn(workUnits.size.asDBIO)
      when(workUnitDtoService.prepareDtos(workUnits)).thenReturn(workUnitDtos.asDBIO)
      when(workUnitDtoService.prepareOrder(workUnitDtos, orders)).thenReturn(orderMap)
      when(workUnitOrderRepository.list(orderFilters)).thenReturn(orders.asDBIO)

      val result = WorkUnitListData(PaginatedResult(workUnits.size, workUnitDtos), WorkUnitListOrderEntry.from(orderMap))
      sut.listWithOrder(authUser, orgSlug, false, workUnitFilters).asserting(_ shouldEqual WorkUnitListResult.Ok(result))

      verify(organizationRepository).find(orgFilter)
      verify(organizationUserRepository).find(orgUserFilter)
      verify(workUnitRepository).list(workUnitFiltersRestricted)
      verify(workUnitRepository).count(workUnitFiltersRestricted)
      verify(workUnitDtoService).prepareDtos(workUnits)
      verify(workUnitDtoService).prepareOrder(workUnitDtos, orders)
      verify(workUnitOrderRepository).list(orderFilters)
    }
    "read the page, the count and the order in a single RepeatableRead transaction" in new TestCase {
      val orgSlug      = orgSlugGenerator.normal.generate
      val organization = organizationGenerator.normal.generate
      val orgUser      = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, Seq(OrganizationUserRole.Manager), None)
      val workUnits    = workUnitsGenerator.normal.generate
      val workUnitDtos = workUnitDtosGenerator.normal.generate.map(conservative)

      val workUnitFilters           = ordinary(workUnitFilterGenerator.normal.generate).copy(includeArchived = false, userIds = None, states = None)
      val workUnitFiltersRestricted = workUnitFilters.copy(organizationId = Some(organization.id), states = Some(WorkUnitState.liveStates))
      val orgFilter                 = OrganizationFilters(slug = Some(orgSlug))
      val orgUserFilter             = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id))
      val orderFilters = WorkUnitOrderFilters(
        organizationId = Some(organization.id),
        userId = Some(authUser.id),
        dateFrom = workUnitFilters.dateFrom,
        dateTo = workUnitFilters.dateTo
      )

      val orders = workUnitOrdersGenerator.normal.generate
      val orderMap = workUnits.groupBy(_.date).map { case (date, units) =>
        date -> units.map(_.id)
      }

      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(orgUserFilter)).thenReturn(Some(orgUser).asDBIO)
      when(workUnitRepository.list(workUnitFiltersRestricted)).thenReturn(workUnits.asDBIO)
      when(workUnitRepository.count(workUnitFiltersRestricted)).thenReturn(workUnits.size.asDBIO)
      when(workUnitDtoService.prepareDtos(workUnits)).thenReturn(workUnitDtos.asDBIO)
      when(workUnitDtoService.prepareOrder(workUnitDtos, orders)).thenReturn(orderMap)
      when(workUnitOrderRepository.list(orderFilters)).thenReturn(orders.asDBIO)

      sut
        .listWithOrder(authUser, orgSlug, false, workUnitFilters)
        .asserting(_ => transactorCalls shouldEqual Seq(TransactorCall.ExecuteTransactionallyAt(IsolationLevel.RepeatableRead)))

      verify(organizationRepository).find(orgFilter)
      verify(organizationUserRepository).find(orgUserFilter)
      verify(workUnitRepository).list(workUnitFiltersRestricted)
      verify(workUnitRepository).count(workUnitFiltersRestricted)
      verify(workUnitDtoService).prepareDtos(workUnits)
      verify(workUnitDtoService).prepareOrder(workUnitDtos, orders)
      verify(workUnitOrderRepository).list(orderFilters)
    }
    "return Ok with service data when admin tries to list work units [no restrict] with order" in new TestCase {
      val orgSlug      = orgSlugGenerator.normal.generate
      val organization = organizationGenerator.normal.generate
      val workUnits    = workUnitsGenerator.normal.generate
      val workUnitDtos = workUnitDtosGenerator.normal.generate.map(conservative)

      val workUnitFilters           = ordinary(workUnitFilterGenerator.normal.generate).copy(includeArchived = false, userIds = None, states = None)
      val workUnitFiltersRestricted = workUnitFilters.copy(organizationId = Some(organization.id), states = Some(WorkUnitState.liveStates))
      val orgFilter                 = OrganizationFilters(slug = Some(orgSlug))
      val orderFilters = WorkUnitOrderFilters(
        organizationId = Some(organization.id),
        userId = Some(authAdmin.id),
        dateFrom = workUnitFilters.dateFrom,
        dateTo = workUnitFilters.dateTo
      )
      val orders = workUnitOrdersGenerator.normal.generate
      val orderMap = workUnits.groupBy(_.date).map { case (date, units) =>
        date -> units.map(_.id)
      }
      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(workUnitRepository.list(workUnitFiltersRestricted)).thenReturn(workUnits.asDBIO)
      when(workUnitRepository.count(workUnitFiltersRestricted)).thenReturn(workUnits.size.asDBIO)
      when(workUnitDtoService.prepareDtos(workUnits)).thenReturn(workUnitDtos.asDBIO)
      when(workUnitDtoService.prepareOrder(workUnitDtos, orders)).thenReturn(orderMap)
      when(workUnitOrderRepository.list(orderFilters)).thenReturn(orders.asDBIO)

      val result = WorkUnitListData(PaginatedResult(workUnits.size, workUnitDtos), WorkUnitListOrderEntry.from(orderMap))
      sut.listWithOrder(authAdmin, orgSlug, false, workUnitFilters).asserting(_ shouldEqual WorkUnitListResult.Ok(result))

      verify(organizationRepository).find(orgFilter)
      verify(workUnitRepository).list(workUnitFiltersRestricted)
      verify(workUnitRepository).count(workUnitFiltersRestricted)
      verify(workUnitDtoService).prepareDtos(workUnits)
      verify(workUnitDtoService).prepareOrder(workUnitDtos, orders)
      verify(workUnitOrderRepository).list(orderFilters)
    }
    "keep mixed Worklog and Note entries in count, state filtering and ordering" in new TestCase {
      val orgSlug      = orgSlugGenerator.normal.generate
      val organization = organizationGenerator.normal.generate
      val base = workUnitGenerator.normal.generate.copy(
        organizationId = organization.id,
        state = WorkUnitState.Active,
        entryType = WorkUnitEntryType.Worklog,
        duration = WorkUnitDuration(Duration.ofMinutes(30))
      )
      val worklog   = base
      val note      = base.copy(id = WorkUnitId(UUID.randomUUID()), entryType = WorkUnitEntryType.Note, duration = WorkUnitDuration(Duration.ZERO))
      val workUnits = Seq(worklog, note)
      val workUnitDtos = Seq(
        conservative(workUnitDtoGenerator.normal.generate).copy(
          id = worklog.id,
          userId = worklog.userId,
          date = worklog.date,
          duration = worklog.duration,
          entryType = worklog.entryType,
          state = worklog.state
        ),
        conservative(workUnitDtoGenerator.normal.generate)
          .copy(id = note.id, userId = note.userId, date = note.date, duration = note.duration, entryType = note.entryType, state = note.state)
      )
      val requestedFilters =
        ordinary(workUnitFilterGenerator.normal.generate).copy(includeArchived = false, userIds = None, states = Some(Seq(WorkUnitState.Active)))
      val restrictedFilters = requestedFilters.copy(organizationId = Some(organization.id))
      val orgFilter         = OrganizationFilters(slug = Some(orgSlug))
      val orderFilters = WorkUnitOrderFilters(
        organizationId = Some(organization.id),
        userId = Some(authAdmin.id),
        dateFrom = requestedFilters.dateFrom,
        dateTo = requestedFilters.dateTo
      )
      val orderedIds = Seq(note.id, worklog.id)

      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(workUnitRepository.list(restrictedFilters)).thenReturn(workUnits.asDBIO)
      when(workUnitRepository.count(restrictedFilters)).thenReturn(workUnits.size.asDBIO)
      when(workUnitDtoService.prepareDtos(workUnits)).thenReturn(workUnitDtos.asDBIO)
      when(workUnitOrderRepository.list(orderFilters)).thenReturn(Seq.empty[WorkUnitOrder].asDBIO)
      when(workUnitDtoService.prepareOrder(workUnitDtos, Seq.empty)).thenReturn(Map(base.date -> orderedIds))

      sut.listWithOrder(authAdmin, orgSlug, false, requestedFilters).asserting {
        case WorkUnitListResult.Ok(data) =>
          data.workUnits.count shouldEqual 2
          data.workUnits.results.map(_.entryType) should containTheSameElementsAs(Seq(WorkUnitEntryType.Worklog, WorkUnitEntryType.Note))
          data.orders shouldEqual Seq(WorkUnitListOrderEntry(base.date, orderedIds))
        case result => result should beAnInstanceOf[WorkUnitListResult.Ok]
      }

      verify(organizationRepository).find(orgFilter)
      verify(workUnitRepository).list(restrictedFilters)
      verify(workUnitRepository).count(restrictedFilters)
      verify(workUnitDtoService).prepareDtos(workUnits)
      verify(workUnitDtoService).prepareOrder(workUnitDtos, Seq.empty)
      verify(workUnitOrderRepository).list(orderFilters)
    }
    "return Ok with service data and draft work units when user tries to list self work units with order" in new TestCase {
      val orgSlug      = orgSlugGenerator.normal.generate
      val organization = organizationGenerator.normal.generate
      val orgUser      = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, Seq(OrganizationUserRole.Employee), None)
      val workUnits    = workUnitsGenerator.normal.generate
      val workUnitDtos = workUnitDtosGenerator.normal.generate.map(conservative)

      val workUnitFilters =
        ordinary(workUnitFilterGenerator.normal.generate).copy(includeArchived = false, userIds = Some(Seq(authUser.id)), states = None)
      val workUnitFiltersRestricted =
        workUnitFilters.copy(
          organizationId = Some(organization.id),
          userIds = Some(Seq(authUser.id)),
          states = Some(WorkUnitState.liveStates ++ Seq(WorkUnitState.Draft))
        )
      val orgFilter     = OrganizationFilters(slug = Some(orgSlug))
      val orgUserFilter = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id))
      val orderFilters = WorkUnitOrderFilters(
        organizationId = Some(organization.id),
        userId = Some(authUser.id),
        dateFrom = workUnitFilters.dateFrom,
        dateTo = workUnitFilters.dateTo
      )
      val orders = workUnitOrdersGenerator.normal.generate
      val orderMap = workUnits.groupBy(_.date).map { case (date, units) =>
        date -> units.map(_.id)
      }
      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(orgUserFilter)).thenReturn(Some(orgUser).asDBIO)
      when(workUnitRepository.list(workUnitFiltersRestricted)).thenReturn(workUnits.asDBIO)
      when(workUnitRepository.count(workUnitFiltersRestricted)).thenReturn(workUnits.size.asDBIO)
      when(workUnitDtoService.prepareDtos(workUnits)).thenReturn(workUnitDtos.asDBIO)
      when(workUnitDtoService.prepareOrder(workUnitDtos, orders)).thenReturn(orderMap)
      when(workUnitOrderRepository.list(orderFilters)).thenReturn(orders.asDBIO)

      val result = WorkUnitListData(PaginatedResult(workUnits.size, workUnitDtos), WorkUnitListOrderEntry.from(orderMap))
      sut.listWithOrder(authUser, orgSlug, false, workUnitFilters).asserting(_ shouldEqual WorkUnitListResult.Ok(result))

      verify(organizationRepository).find(orgFilter)
      verify(organizationUserRepository).find(orgUserFilter)
      verify(workUnitRepository).list(workUnitFiltersRestricted)
      verify(workUnitRepository).count(workUnitFiltersRestricted)
      verify(workUnitDtoService).prepareDtos(workUnits)
      verify(workUnitDtoService).prepareOrder(workUnitDtos, orders)
      verify(workUnitOrderRepository).list(orderFilters)
    }

    "retry the complete PM export transaction twice on SQLSTATE 40001 and evaluate a fresh scope" in new TestCase {
      override val transactor: ControlledPmExportTransactor = new ControlledPmExportTransactor(serializationFailures = 2)

      val actor                 = userGenerator.normal.generate.copy(id = authUser.id, isSuperAdmin = UserIsSuperAdmin(false), archivedAt = None)
      val orgSlug               = orgSlugGenerator.normal.generate
      val organization          = organizationGenerator.normal.generate
      val orgUser               = OrganizationUser(OrganizationUserId.random, organization.id, actor.id, Seq(OrganizationUserRole.Employee), None)
      val sourceProject         = projectGenerator.normal.generate.copy(organizationId = organization.id, archivedAt = None)
      val requested             = WorkUnitFilters()
      val userFilter            = UserFilters(id = Some(actor.id), includeArchived = true, forUpdate = true)
      val orgFilter             = OrganizationFilters(slug = Some(orgSlug), forUpdate = true)
      val orgUserPrecheckFilter = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(actor.id))
      val orgUserFilter =
        OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(actor.id), includeArchived = true, forUpdate = true)
      val projectFilter     = ProjectFilters(organizationIds = Some(Seq(organization.id)), includeArchived = true)
      val projectUserFilter = ProjectUserFilters(userId = Some(actor.id), includeArchived = true)

      when(userRepository.find(userFilter)).thenReturn(Some(actor).asDBIO)
      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(orgUserPrecheckFilter)).thenReturn(Some(orgUser).asDBIO)
      when(organizationUserRepository.find(orgUserFilter)).thenReturn(Some(orgUser).asDBIO)
      when(projectRepository.lockOrdered(projectFilter)).thenReturn(Seq(sourceProject).asDBIO)
      when(projectUserRepository.lockOrdered(projectUserFilter)).thenReturn(Seq.empty[ProjectUser].asDBIO)

      val restricted = WorkUnitFilters.from(requested, organization, Some(orgUser), authUser)
      when(workUnitRepository.list(restricted)).thenReturn(Seq.empty[WorkUnit].asDBIO)
      when(workUnitRepository.count(restricted)).thenReturn(0.asDBIO)
      when(workUnitDtoService.prepareDtos(Seq.empty[WorkUnit])).thenReturn(Seq.empty[WorkUnitDto].asDBIO)
      sut.listForExport(authUser, orgSlug, requested).asserting {
        case WorkUnitListResult.Ok(data) => data.workUnits shouldEqual PaginatedResult(0, Seq.empty[WorkUnitDto])
        case other                       => throw new AssertionError(s"Expected restricted self-export, got $other")
      }
      transactor.attempts shouldEqual 3

      verify(userRepository).find(userFilter)
      verify(organizationRepository).find(orgFilter)
      verify(organizationUserRepository).find(orgUserPrecheckFilter)
      verify(organizationUserRepository).find(orgUserFilter)
      verify(projectRepository).lockOrdered(projectFilter)
      verify(projectUserRepository).lockOrdered(projectUserFilter)
      verify(workUnitRepository).list(restricted)
      verify(workUnitRepository).count(restricted)
      verify(workUnitDtoService).prepareDtos(Seq.empty[WorkUnit])
    }

    "propagate SQLSTATE 40001 after the bounded PM export retry limit" in new TestCase {
      override val transactor: ControlledPmExportTransactor = new ControlledPmExportTransactor(serializationFailures = 3)

      sut.listForExport(authUser, orgSlugGenerator.normal.generate, WorkUnitFilters()).attempt.asserting {
        case Left(error: PSQLException) => error.getSQLState shouldEqual "40001"
        case other                      => other should beLeft
      }
      transactor.attempts shouldEqual 3
    }
    "propagate a non-40001 database failure without retrying the PM export transaction" in new TestCase {
      val failure                                        = new SQLException("deadlock", "40P01")
      override val transactor: FailingPmExportTransactor = new FailingPmExportTransactor(failure)

      sut.listForExport(authUser, orgSlugGenerator.normal.generate, WorkUnitFilters()).attempt.asserting {
        case Left(error) => error should beTheSameAs(failure)
        case other       => other should beLeft
      }
      transactor.attempts shouldEqual 1
    }
    "return Ok with service data when admin tries to list archived work units with order" in new TestCase {
      val orgSlug      = orgSlugGenerator.normal.generate
      val organization = organizationGenerator.normal.generate
      val workUnits    = workUnitsGenerator.normal.generate
      val workUnitDtos = workUnitDtosGenerator.normal.generate.map(conservative)

      val workUnitFilters = ordinary(workUnitFilterGenerator.normal.generate).copy(includeArchived = true, states = None)
      val workUnitFiltersRestricted =
        workUnitFilters.copy(organizationId = Some(organization.id), states = Some(WorkUnitState.liveStates ++ Seq(WorkUnitState.Archived)))
      val orgFilter = OrganizationFilters(slug = Some(orgSlug))
      val orderFilters = WorkUnitOrderFilters(
        organizationId = Some(organization.id),
        userId = Some(authAdmin.id),
        dateFrom = workUnitFilters.dateFrom,
        dateTo = workUnitFilters.dateTo
      )
      val orders = workUnitOrdersGenerator.normal.generate
      val orderMap = workUnits.groupBy(_.date).map { case (date, units) =>
        date -> units.map(_.id)
      }
      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(workUnitRepository.list(workUnitFiltersRestricted)).thenReturn(workUnits.asDBIO)
      when(workUnitRepository.count(workUnitFiltersRestricted)).thenReturn(workUnits.size.asDBIO)
      when(workUnitDtoService.prepareDtos(workUnits)).thenReturn(workUnitDtos.asDBIO)
      when(workUnitDtoService.prepareOrder(workUnitDtos, orders)).thenReturn(orderMap)
      when(workUnitOrderRepository.list(any)).thenReturn(orders.asDBIO)

      val result = WorkUnitListData(PaginatedResult(workUnits.size, workUnitDtos), WorkUnitListOrderEntry.from(orderMap))
      sut.listWithOrder(authAdmin, orgSlug, false, workUnitFilters).asserting(_ shouldEqual WorkUnitListResult.Ok(result))

      verify(organizationRepository).find(orgFilter)
      verify(workUnitRepository).list(workUnitFiltersRestricted)
      verify(workUnitRepository).count(workUnitFiltersRestricted)
      verify(workUnitDtoService).prepareDtos(workUnits)
      verify(workUnitDtoService).prepareOrder(workUnitDtos, orders)
      verify(workUnitOrderRepository).list(orderFilters)
    }
    "return Ok with drafts ids if drafts are requested" in new TestCase {
      val orgSlug      = orgSlugGenerator.normal.generate
      val organization = organizationGenerator.normal.generate
      val orgUser      = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, Seq(OrganizationUserRole.Employee), None)
      val workUnits    = workUnitsGenerator.normal.generate
      val workUnitDtos = workUnitDtosGenerator.normal.generate.map(conservative)

      val workUnitFilters =
        ordinary(workUnitFilterGenerator.normal.generate).copy(includeArchived = false, userIds = Some(Seq(authUser.id)), states = None)
      val workUnitFiltersRestricted =
        workUnitFilters.copy(
          organizationId = Some(organization.id),
          userIds = Some(Seq(authUser.id)),
          states = Some(WorkUnitState.liveStates ++ Seq(WorkUnitState.Draft))
        )
      val workUnitFiltersDrafts =
        WorkUnitFilters(organizationId = Some(organization.id), states = Some(Seq(WorkUnitState.Draft)), userIds = Some(Seq(authUser.id)))

      val orgFilter     = OrganizationFilters(slug = Some(orgSlug))
      val orgUserFilter = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id))
      val orderFilters = WorkUnitOrderFilters(
        organizationId = Some(organization.id),
        userId = Some(authUser.id),
        dateFrom = workUnitFilters.dateFrom,
        dateTo = workUnitFilters.dateTo
      )
      val orders = workUnitOrdersGenerator.normal.generate
      val orderMap = workUnits.groupBy(_.date).map { case (date, units) =>
        date -> units.map(_.id)
      }
      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(orgUserFilter)).thenReturn(Some(orgUser).asDBIO)
      when(workUnitRepository.list(workUnitFiltersRestricted)).thenReturn(workUnits.asDBIO)
      when(workUnitRepository.list(workUnitFiltersDrafts)).thenReturn(workUnits.asDBIO)
      when(workUnitRepository.count(workUnitFiltersRestricted)).thenReturn(workUnits.size.asDBIO)
      when(workUnitDtoService.prepareDtos(workUnits)).thenReturn(workUnitDtos.asDBIO)
      when(workUnitDtoService.prepareOrder(workUnitDtos, orders)).thenReturn(orderMap)
      when(workUnitOrderRepository.list(orderFilters)).thenReturn(orders.asDBIO)

      val result = WorkUnitListData(PaginatedResult(workUnits.size, workUnitDtos), WorkUnitListOrderEntry.from(orderMap), Some(workUnits.map(_.id)))
      sut.listWithOrder(authUser, orgSlug, true, workUnitFilters).asserting(_ shouldEqual WorkUnitListResult.Ok(result))

      verify(organizationRepository).find(orgFilter)
      verify(organizationUserRepository).find(orgUserFilter)
      verify(workUnitRepository).list(workUnitFiltersRestricted)
      verify(workUnitRepository).count(workUnitFiltersRestricted)
      verify(workUnitRepository).list(workUnitFiltersDrafts)
      verify(workUnitDtoService).prepareDtos(workUnits)
      verify(workUnitDtoService).prepareOrder(workUnitDtos, orders)
      verify(workUnitOrderRepository).list(orderFilters)
    }

  }

  private final class ControlledPmExportTransactor(serializationFailures: Int) extends FakeTransactor {
    var attempts: Int = 0

    override def executeTransactionally[T](level: IsolationLevel)(action: => DBIO[T]): IO[T] = {
      attempts += 1
      if (attempts <= serializationFailures) IO.raiseError(new PSQLException("serialization failure", PSQLState.SERIALIZATION_FAILURE))
      else super.executeTransactionally(level)(action)
    }
  }

  private final class FailingPmExportTransactor(error: Throwable) extends FakeTransactor {
    var attempts: Int = 0

    override def executeTransactionally[T](level: IsolationLevel)(action: => DBIO[T]): IO[T] = {
      attempts += 1
      IO.raiseError(error)
    }
  }
}
