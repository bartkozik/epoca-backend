package pl.iterators.epoca.stats.services

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import com.typesafe.config.{ConfigFactory, ConfigResolveOptions}
import io.circe.Json
import org.mockito.Mockito.verifyNoInteractions
import org.specs2.matcher.MatchResult
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationSlug, OrganizationUserId, OrganizationUserRole}
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.stats.domain.StatsDomain.{WorkUnitPivotTableKey, WorkUnitPivotTableValue}
import pl.iterators.epoca.stats.domain.WorkUnitPivotQuery._
import pl.iterators.epoca.stats.repositories.WorkUnitPivotQueryRepository
import pl.iterators.epoca.stats.repositories.WorkUnitPivotQueryRepository.{AggregateRow, CoordinateCounts}
import pl.iterators.epoca.stats.services.WorkUnitPivotQueryService.Result
import pl.iterators.epoca.stats.services.WorkUnitPivotQueryServiceSpec.RecordingFakeTransactor
import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.user.domain.UserDomain.UserIsSuperAdmin
import pl.iterators.epoca.utils.ServiceSpec
import pl.iterators.epoca.utils.transactor.{FakeTransactor, IsolationLevel}
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.{WorkUnitDate, WorkUnitEntryType}
import slick.dbio.DBIO

import java.time.LocalDate

class WorkUnitPivotQueryServiceSpec extends ServiceSpec {
  private lazy val organizationGenerator: AllGenerators[Organization] = allGenerators[Organization]

  trait TestCase extends BaseScope {
    val config: WorkUnitPivotQueryService.Config               = WorkUnitPivotQueryService.Config(maxDisplayValueCellCount = 24L)
    override val transactor: RecordingFakeTransactor           = new RecordingFakeTransactor()
    val organizationRepository: OrganizationRepository         = vmock[OrganizationRepository]
    val organizationUserRepository: OrganizationUserRepository = vmock[OrganizationUserRepository]
    val repository: WorkUnitPivotQueryRepository               = vmock[WorkUnitPivotQueryRepository]
    val validator: WorkUnitPivotQueryRequestValidator          = vmock[WorkUnitPivotQueryRequestValidator]
    val assembler: WorkUnitPivotQueryAssembler                 = vmock[WorkUnitPivotQueryAssembler]

    val sut: WorkUnitPivotQueryService = wire[WorkUnitPivotQueryService]

    val organization: Organization = organizationGenerator.normal.generate
    val slug: OrganizationSlug     = organization.slug
    val rawRequest: Json           = Json.obj("request" -> Json.fromString("valid"))
    val request: Request = Request(
      rows = Seq(WorkUnitPivotTableKey.UserKey),
      columns = Seq(WorkUnitPivotTableKey.MonthYearKey),
      values = Seq(WorkUnitPivotTableValue.DurationSum, WorkUnitPivotTableValue.EntriesCount),
      filters = Seq(Filter(WorkUnitPivotTableKey.EntryTypeKey, None), Filter(WorkUnitPivotTableKey.DateKey, None)),
      options = Options(rowSubtotals = true, columnSubtotals = true, grandTotals = true)
    )
    val globalFilters: GlobalFilters = GlobalFilters(
      dateFrom = WorkUnitDate(LocalDate.of(2026, 8, 1)),
      dateTo = Some(WorkUnitDate(LocalDate.of(2026, 8, 31))),
      projectIds = Seq.empty,
      userIds = Seq.empty,
      clientIds = Seq.empty
    )
    val counts: CoordinateCounts = CoordinateCounts(row = 3L, column = 4L)
    val aggregates: Seq[AggregateRow] = Seq(
      AggregateRow(rowPath = Seq.empty, columnPath = Seq.empty, entriesCount = 1L, durationSumNanos = BigInt(1), durationAvgNanos = Some(BigInt(1)))
    )
    val members: Map[WorkUnitPivotTableKey, Seq[FieldValue]] = Map(
      WorkUnitPivotTableKey.EntryTypeKey -> Seq(FieldValue.EntryType(WorkUnitEntryType.Worklog)),
      WorkUnitPivotTableKey.DateKey      -> Seq(FieldValue.Date(LocalDate.of(2026, 8, 1)))
    )
    val response: Response = Response(
      cells = Seq.empty,
      filterMembers = Seq.empty,
      result = ResultMetadata(cellCount = 0L, displayValueCellCount = 24L, maxDisplayValueCellCount = 24L)
    )

    val orgFilter: OrganizationFilters = OrganizationFilters(slug = Some(slug))
    def orgUserFilter(auth: AuthContext): OrganizationUserFilters =
      OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(auth.id))

    when(validator.validate(rawRequest)).thenReturn(Right(request))

    def member(auth: AuthContext, roles: Seq[OrganizationUserRole]): OrganizationUser =
      OrganizationUser(OrganizationUserId.random, organization.id, auth.id, roles, None)

    def stubAuthorized(
      auth: AuthContext,
      role: OrganizationUserRole,
      coordinateCounts: CoordinateCounts = counts
    ): Unit = {
      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(orgUserFilter(auth))).thenReturn(Some(member(auth, Seq(role))).asDBIO)
      stubQueryData(coordinateCounts)
    }

    def stubSuperAdmin(coordinateCounts: CoordinateCounts = counts): Unit = {
      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      stubQueryData(coordinateCounts)
    }

    def stubQueryData(coordinateCounts: CoordinateCounts): Unit = {
      when(repository.coordinateCounts(organization.id, request, globalFilters)).thenReturn(coordinateCounts.asDBIO)
      val estimated = WorkUnitPivotQueryService.saturatingDisplayValueCellCount(coordinateCounts, request.values.size)
      if (estimated <= config.maxDisplayValueCellCount) {
        when(repository.aggregate(organization.id, request, globalFilters)).thenReturn(aggregates.asDBIO)
        when(repository.availableFilterMembers(organization.id, request, globalFilters)).thenReturn(members.asDBIO)
        val _ = when(assembler.assemble(request, aggregates, members, estimated, config.maxDisplayValueCellCount)).thenReturn(response)
      }
    }

    def verifySuccessful(auth: AuthContext, expectsMembership: Boolean): MatchResult[Any] = {
      request.filters.map(_.key) shouldEqual Seq(WorkUnitPivotTableKey.EntryTypeKey, WorkUnitPivotTableKey.DateKey)
      verify(validator).validate(rawRequest)
      verify(organizationRepository).find(orgFilter)
      if (expectsMembership) verify(organizationUserRepository).find(orgUserFilter(auth))
      else verifyNoInteractions(organizationUserRepository)
      verify(repository, org.mockito.Mockito.times(1)).coordinateCounts(organization.id, request, globalFilters)
      verify(repository, org.mockito.Mockito.times(1)).aggregate(organization.id, request, globalFilters)
      verify(repository, org.mockito.Mockito.times(1)).availableFilterMembers(organization.id, request, globalFilters)
      verify(assembler).assemble(request, aggregates, members, 24L, 24L)
      transactor.executionCount shouldEqual 1
      transactor.isolationLevels shouldEqual Vector(IsolationLevel.RepeatableRead)
    }
  }

  "query" should {
    "return the exact validation error before opening a transaction or touching any repository" in new TestCase {
      val error = InvalidConfigurationError(violations = Seq(Violation("values", ViolationCode.EMPTY_VALUES)))
      when(validator.validate(rawRequest)).thenReturn(Left(error))

      sut.query(authUser, slug, rawRequest, globalFilters).asserting(_ shouldEqual Result.InvalidConfiguration(error))

      verify(validator).validate(rawRequest)
      verifyNoInteractions(organizationRepository, organizationUserRepository, repository, assembler)
      transactor.executionCount shouldEqual 0
      transactor.isolationLevels should beEmpty
    }

    "return OrganizationNotFound from the single repeatable-read transaction" in new TestCase {
      when(organizationRepository.find(orgFilter)).thenReturn(None.asDBIO)

      sut.query(authUser, slug, rawRequest, globalFilters).asserting(_ shouldEqual Result.OrganizationNotFound)

      verify(validator).validate(rawRequest)
      verify(organizationRepository).find(orgFilter)
      verifyNoInteractions(organizationUserRepository, repository, assembler)
      transactor.executionCount shouldEqual 1
      transactor.isolationLevels shouldEqual Vector(IsolationLevel.RepeatableRead)
    }

    "return NoAccessToOrganization when a non-super-admin has no membership" in new TestCase {
      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(orgUserFilter(authUser))).thenReturn(None.asDBIO)

      sut.query(authUser, slug, rawRequest, globalFilters).asserting(_ shouldEqual Result.NoAccessToOrganization)

      verify(validator).validate(rawRequest)
      verify(organizationRepository).find(orgFilter)
      verify(organizationUserRepository).find(orgUserFilter(authUser))
      verifyNoInteractions(repository, assembler)
      transactor.executionCount shouldEqual 1
      transactor.isolationLevels shouldEqual Vector(IsolationLevel.RepeatableRead)
    }

    "allow an Owner and pass the same validated request, counts, aggregates, and facets to the assembler" in new TestCase {
      stubAuthorized(authUser, OrganizationUserRole.Owner)

      sut.query(authUser, slug, rawRequest, globalFilters).asserting(_ shouldEqual Result.Ok(response))

      verifySuccessful(authUser, expectsMembership = true)
    }

    "produce identical results and repository calls for raw and already validated requests" in new TestCase {
      stubAuthorized(authUser, OrganizationUserRole.Owner)

      sut
        .query(authUser, slug, rawRequest, globalFilters)
        .flatMap(rawResult => sut.queryValidated(authUser, slug, request, globalFilters).map(validatedResult => rawResult -> validatedResult))
        .asserting(_ shouldEqual Result.Ok(response) -> Result.Ok(response))

      verify(validator).validate(rawRequest)
      verify(organizationRepository, org.mockito.Mockito.times(2)).find(orgFilter)
      verify(organizationUserRepository, org.mockito.Mockito.times(2)).find(orgUserFilter(authUser))
      verify(repository, org.mockito.Mockito.times(2)).coordinateCounts(organization.id, request, globalFilters)
      verify(repository, org.mockito.Mockito.times(2)).aggregate(organization.id, request, globalFilters)
      verify(repository, org.mockito.Mockito.times(2)).availableFilterMembers(organization.id, request, globalFilters)
      verify(assembler, org.mockito.Mockito.times(2)).assemble(request, aggregates, members, 24L, 24L)
      transactor.executionCount shouldEqual 2
      transactor.isolationLevels shouldEqual Vector.fill(2)(IsolationLevel.RepeatableRead)
    }

    "allow a Manager using the shared managementAccessRoles contract" in new TestCase {
      OrganizationUserRole.managementAccessRoles shouldEqual Seq(OrganizationUserRole.Owner, OrganizationUserRole.Manager)
      stubAuthorized(authUser, OrganizationUserRole.Manager)

      sut.query(authUser, slug, rawRequest, globalFilters).asserting(_ shouldEqual Result.Ok(response))

      verifySuccessful(authUser, expectsMembership = true)
    }

    "reject an Employee with InsufficientRole before pivot repository work" in new TestCase {
      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(orgUserFilter(authUser))).thenReturn(Some(member(authUser, Seq(OrganizationUserRole.Employee))).asDBIO)

      sut.query(authUser, slug, rawRequest, globalFilters).asserting(_ shouldEqual Result.InsufficientRole)

      verify(validator).validate(rawRequest)
      verify(organizationRepository).find(orgFilter)
      verify(organizationUserRepository).find(orgUserFilter(authUser))
      verifyNoInteractions(repository, assembler)
      transactor.executionCount shouldEqual 1
      transactor.isolationLevels shouldEqual Vector(IsolationLevel.RepeatableRead)
    }

    "reject an External with InsufficientRole before pivot repository work" in new TestCase {
      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(orgUserFilter(authUser))).thenReturn(Some(member(authUser, Seq(OrganizationUserRole.External))).asDBIO)

      sut.query(authUser, slug, rawRequest, globalFilters).asserting(_ shouldEqual Result.InsufficientRole)

      verify(validator).validate(rawRequest)
      verify(organizationRepository).find(orgFilter)
      verify(organizationUserRepository).find(orgUserFilter(authUser))
      verifyNoInteractions(repository, assembler)
      transactor.executionCount shouldEqual 1
      transactor.isolationLevels shouldEqual Vector(IsolationLevel.RepeatableRead)
    }

    "allow a super-admin without looking up membership" in new TestCase {
      val superAdmin = authAdmin.copy(isSuperAdmin = UserIsSuperAdmin(true))
      stubSuperAdmin()

      sut.query(superAdmin, slug, rawRequest, globalFilters).asserting(_ shouldEqual Result.Ok(response))

      verifySuccessful(superAdmin, expectsMembership = false)
    }

    "allow a display-size estimate exactly equal to the configured limit" in new TestCase {
      stubAuthorized(authUser, OrganizationUserRole.Owner, CoordinateCounts(row = 3L, column = 4L))

      sut.query(authUser, slug, rawRequest, globalFilters).asserting(_ shouldEqual Result.Ok(response))

      verifySuccessful(authUser, expectsMembership = true)
    }

    "reject an estimate above the limit without aggregate, facet, or assembler work" in new TestCase {
      val oversizedCounts = CoordinateCounts(row = 3L, column = 5L)
      stubAuthorized(authUser, OrganizationUserRole.Owner, oversizedCounts)
      val error = ResultTooLargeError(estimatedDisplayValueCellCount = 30L, maxDisplayValueCellCount = 24L)

      sut.query(authUser, slug, rawRequest, globalFilters).asserting(_ shouldEqual Result.TooLarge(error))

      verify(validator).validate(rawRequest)
      verify(organizationRepository).find(orgFilter)
      verify(organizationUserRepository).find(orgUserFilter(authUser))
      verify(repository, org.mockito.Mockito.times(1)).coordinateCounts(organization.id, request, globalFilters)
      verify(repository, never).aggregate(organization.id, request, globalFilters)
      verify(repository, never).availableFilterMembers(organization.id, request, globalFilters)
      verifyNoInteractions(assembler)
      transactor.executionCount shouldEqual 1
      transactor.isolationLevels shouldEqual Vector(IsolationLevel.RepeatableRead)
    }

    "saturate an overflowing sparse-matrix estimate instead of wrapping it" in new TestCase {
      val overflowingCounts = CoordinateCounts(row = Long.MaxValue, column = Long.MaxValue)
      stubAuthorized(authUser, OrganizationUserRole.Owner, overflowingCounts)
      val error = ResultTooLargeError(estimatedDisplayValueCellCount = Long.MaxValue, maxDisplayValueCellCount = 24L)

      sut.query(authUser, slug, rawRequest, globalFilters).asserting(_ shouldEqual Result.TooLarge(error))

      WorkUnitPivotQueryService.saturatingDisplayValueCellCount(overflowingCounts, Int.MaxValue) shouldEqual Long.MaxValue
      WorkUnitPivotQueryService.saturatingDisplayValueCellCount(CoordinateCounts(-1L, 5L), 3) shouldEqual 0L
      verify(validator).validate(rawRequest)
      verify(organizationRepository).find(orgFilter)
      verify(organizationUserRepository).find(orgUserFilter(authUser))
      verify(repository, org.mockito.Mockito.times(1)).coordinateCounts(organization.id, request, globalFilters)
      verify(repository, never).aggregate(organization.id, request, globalFilters)
      verify(repository, never).availableFilterMembers(organization.id, request, globalFilters)
      verifyNoInteractions(assembler)
      transactor.executionCount shouldEqual 1
      transactor.isolationLevels shouldEqual Vector(IsolationLevel.RepeatableRead)
    }
  }

  "Config" should {
    "load the application default and a supplied override" in {
      val applicationConfig = ConfigFactory
        .parseResources("application.conf")
        .resolve(ConfigResolveOptions.defaults().setAllowUnresolved(true))
      WorkUnitPivotQueryService.Config(applicationConfig) shouldEqual WorkUnitPivotQueryService.Config(9600L)
      WorkUnitPivotQueryService.Config(ConfigFactory.parseString("stats.pivot.maxDisplayValueCellCount = 321")) shouldEqual WorkUnitPivotQueryService
        .Config(321L)
    }

    "reject zero and negative limits at startup" in {
      WorkUnitPivotQueryService.Config(0L) must throwA[IllegalArgumentException]
      WorkUnitPivotQueryService.Config(-1L) must throwA[IllegalArgumentException]
    }
  }
}

object WorkUnitPivotQueryServiceSpec {
  final class RecordingFakeTransactor(implicit runtime: IORuntime) extends FakeTransactor {
    var isolationLevels: Vector[IsolationLevel] = Vector.empty
    var executionCount: Int                     = 0

    override def executeTransactionally[T](level: IsolationLevel)(action: => DBIO[T]): IO[T] = {
      executionCount += 1
      isolationLevels :+= level
      super.executeTransactionally(level)(action)
    }
  }
}
