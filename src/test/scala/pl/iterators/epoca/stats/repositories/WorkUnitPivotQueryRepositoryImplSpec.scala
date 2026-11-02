package pl.iterators.epoca.stats.repositories

import pl.iterators.epoca.client.repositories.fixtures.ClientFixture
import pl.iterators.epoca.oauth.repositories.fixtures.ServiceAccountFixture
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId
import pl.iterators.epoca.organization.repositories.fixtures.OrganizationFixture
import pl.iterators.epoca.project.domain.Project
import pl.iterators.epoca.project.domain.ProjectDomain.ProjectId
import pl.iterators.epoca.project.repositories.fixtures.ProjectFixture
import pl.iterators.epoca.project.repositories.{ProjectRepository, ProjectRepositoryImpl}
import pl.iterators.epoca.stats.domain.StatsDomain.{WorkUnitPivotTableKey, WorkUnitPivotTableValue}
import pl.iterators.epoca.stats.domain.WorkUnitPivotQuery.{
  FieldValue,
  Filter,
  FilterMember,
  FilterMembers,
  GlobalFilters,
  Options,
  PathSegment,
  Request
}
import pl.iterators.epoca.stats.repositories.WorkUnitPivotQueryRepository.{AggregateRow, CoordinateCounts}
import pl.iterators.epoca.stats.services.WorkUnitPivotQueryAssembler
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.user.repositories.fixtures.UserFixture
import pl.iterators.epoca.utils.PgRepositorySpec
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import pl.iterators.epoca.workunit.domain.WorkUnit
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.{
  WorkUnitArchivedAt,
  WorkUnitDate,
  WorkUnitDuration,
  WorkUnitEntryType,
  WorkUnitId,
  WorkUnitState,
  WorkUnitTaskId
}
import pl.iterators.epoca.workunit.repositories.fixtures.WorkUnitFixture
import pl.iterators.epoca.workunit.repositories.tables.WorkUnitTable
import pl.iterators.epoca.workunit.repositories.{WorkUnitRepository, WorkUnitRepositoryImpl}

import java.time.{Duration, Instant, LocalDate, YearMonth}
import java.util.UUID

class WorkUnitPivotQueryRepositoryImplSpec
    extends PgRepositorySpec
    with OrganizationFixture
    with UserFixture
    with ClientFixture
    with ProjectFixture
    with WorkUnitFixture
    with ServiceAccountFixture {
  private val repository                             = new WorkUnitPivotQueryRepositoryImpl()
  private val assembler                              = new WorkUnitPivotQueryAssembler()
  private val projectRepository: ProjectRepository   = new ProjectRepositoryImpl()
  private val workUnitRepository: WorkUnitRepository = new WorkUnitRepositoryImpl()
  private val workUnitGenerator                      = allGenerators[WorkUnit].normal

  "WorkUnitPivotQueryRepositoryImpl" should {
    "use effective values for filters, coordinates, aggregates and facets while requiring both project scopes" in withDatabase {
      val correctedRequest = request(
        rows = Seq(WorkUnitPivotTableKey.ProjectKey, WorkUnitPivotTableKey.ClientKey, WorkUnitPivotTableKey.TaskKey),
        columns = Seq.empty,
        grandTotals = false
      ).copy(options = Options(rowSubtotals = false, columnSubtotals = false, grandTotals = false))
      val facetRequest = correctedRequest.copy(filters = Seq(Filter(WorkUnitPivotTableKey.TaskKey, None)))
      for {
        organization  <- withOrganization()
        user          <- withUser()
        sourceClient  <- withClient(organization)
        targetClient  <- withClient(organization)
        sourceProject <- withProject(organization, sourceClient)
        targetProject <- withProject(organization, targetClient)
        workUnit <- withWorkUnit(
                      organization,
                      user,
                      sourceProject,
                      worklog(LocalDate.of(2026, 7, 8), Duration.ofHours(2)).copy(taskId = Some(WorkUnitTaskId("SOURCE-1")))
                    )
        _ <- TableQuery[WorkUnitTable]
               .filter(_.id === workUnit.id)
               .map(w => (w.overrideProjectId, w.taskIdOverrideSet, w.overrideVersion, w.overrideLastChangedBy, w.overrideLastChangedAt))
               .update((Some(targetProject.id), true, 1L, Some(user.id), Some(Instant.parse("2026-09-17T10:00:00Z"))))
        effectiveFilters = globalFilters.copy(projectIds = Seq(targetProject.id), clientIds = Seq(targetClient.id))
        counts       <- repository.coordinateCounts(organization.id, correctedRequest, effectiveFilters)
        rows         <- repository.aggregate(organization.id, correctedRequest, effectiveFilters)
        facets       <- repository.availableFilterMembers(organization.id, facetRequest, effectiveFilters)
        originalRows <- repository.aggregate(organization.id, correctedRequest, globalFilters.copy(projectIds = Seq(sourceProject.id)))
        sourceOnly <-
          repository.coordinateCounts(organization.id, correctedRequest, globalFilters.copy(reportAuthorizedProjectIds = Some(Seq(sourceProject.id))))
        targetOnly <-
          repository.coordinateCounts(organization.id, correctedRequest, globalFilters.copy(reportAuthorizedProjectIds = Some(Seq(targetProject.id))))
        both <- repository.coordinateCounts(
                  organization.id,
                  correctedRequest,
                  effectiveFilters.copy(reportAuthorizedProjectIds = Some(Seq(sourceProject.id, targetProject.id)))
                )
      } yield (counts shouldEqual CoordinateCounts(1L, 1L)) and
        (rows.map(_.rowPath) shouldEqual Seq(
          Seq(
            PathSegment(WorkUnitPivotTableKey.ProjectKey, FieldValue.Uuid(targetProject.id)),
            PathSegment(WorkUnitPivotTableKey.ClientKey, FieldValue.Uuid(targetClient.id)),
            PathSegment(WorkUnitPivotTableKey.TaskKey, FieldValue.Null)
          )
        )) and
        (rows.map(_.durationSumNanos) shouldEqual Seq(hours(2))) and
        (facets shouldEqual Map(WorkUnitPivotTableKey.TaskKey -> Seq(FieldValue.Null))) and
        (originalRows should beEmpty) and
        (sourceOnly shouldEqual CoordinateCounts(0L, 1L)) and
        (targetOnly shouldEqual CoordinateCounts(0L, 1L)) and
        (both shouldEqual CoordinateCounts(1L, 1L))
    }

    "exclude service-account work units from coordinates, aggregates, and user facets" in withDatabase {
      val humanOnlyRequest = request(rows = Seq(WorkUnitPivotTableKey.UserKey), columns = Seq.empty, grandTotals = false)
      val facetRequest     = humanOnlyRequest.copy(filters = Seq(Filter(WorkUnitPivotTableKey.UserKey, None)))

      for {
        human          <- withUser()
        organization   <- withOrganization()
        serviceAccount <- withLegalServiceAccount(organization, human)
        client         <- withClient(organization)
        project        <- withProject(organization, client)
        _              <- withWorkUnit(organization, human, project, worklog(LocalDate.of(2026, 7, 8), Duration.ofHours(2)))
        _              <- withWorkUnit(organization, serviceAccount.user, project, worklog(LocalDate.of(2026, 7, 8), Duration.ofHours(5)))
        counts         <- repository.coordinateCounts(organization.id, humanOnlyRequest, globalFilters)
        aggregate      <- repository.aggregate(organization.id, humanOnlyRequest, globalFilters)
        facets         <- repository.availableFilterMembers(organization.id, facetRequest, globalFilters)
      } yield {
        counts shouldEqual CoordinateCounts(row = 1L, column = 1L)
        aggregate shouldEqual Seq(
          AggregateRow(
            rowPath = Seq(PathSegment(WorkUnitPivotTableKey.UserKey, FieldValue.Uuid(human.id))),
            columnPath = Seq.empty,
            entriesCount = 1L,
            durationSumNanos = hours(2),
            durationAvgNanos = Some(hours(2))
          )
        )
        facets shouldEqual Map(WorkUnitPivotTableKey.UserKey -> Seq(FieldValue.Uuid(human.id)))
      }
    }

    "return no aggregates and preserve structural empty-axis counts when no source rows exist" in withDatabase {
      for {
        organization <- withOrganization()
        rowsRequest    = request(rows = Seq(WorkUnitPivotTableKey.UserKey), columns = Seq.empty)
        columnsRequest = request(rows = Seq.empty, columns = Seq(WorkUnitPivotTableKey.ProjectKey))
        rowsCounts       <- repository.coordinateCounts(organization.id, rowsRequest, globalFilters)
        columnsCounts    <- repository.coordinateCounts(organization.id, columnsRequest, globalFilters)
        rowsAggregate    <- repository.aggregate(organization.id, rowsRequest, globalFilters)
        columnsAggregate <- repository.aggregate(organization.id, columnsRequest, globalFilters)
      } yield {
        rowsCounts shouldEqual CoordinateCounts(row = 0L, column = 1L)
        columnsCounts shouldEqual CoordinateCounts(row = 1L, column = 0L)
        rowsAggregate should beEmpty
        columnsAggregate should beEmpty
      }
    }

    "keep both empty axes structural and aggregate a source row into their single coordinate" in withDatabase {
      val emptyRequest = request(rows = Seq.empty, columns = Seq.empty)

      for {
        emptyOrganization <- withOrganization()
        emptyCounts       <- repository.coordinateCounts(emptyOrganization.id, emptyRequest, globalFilters)
        emptyAggregate    <- repository.aggregate(emptyOrganization.id, emptyRequest, globalFilters)
        organization      <- withOrganization()
        user              <- withUser()
        client            <- withClient(organization)
        project           <- withProject(organization, client)
        worklog = workUnitGenerator.generate.copy(
                    date = WorkUnitDate(LocalDate.of(2026, 7, 8)),
                    duration = WorkUnitDuration(Duration.ofHours(2)),
                    entryType = WorkUnitEntryType.Worklog,
                    state = WorkUnitState.Active
                  )
        _         <- withWorkUnit(organization, user, project, worklog)
        counts    <- repository.coordinateCounts(organization.id, emptyRequest, globalFilters)
        aggregate <- repository.aggregate(organization.id, emptyRequest, globalFilters)
      } yield {
        emptyCounts shouldEqual CoordinateCounts(row = 1L, column = 1L)
        emptyAggregate should beEmpty
        counts shouldEqual CoordinateCounts(row = 1L, column = 1L)
        aggregate shouldEqual Seq(
          AggregateRow(
            rowPath = Seq.empty,
            columnPath = Seq.empty,
            entriesCount = 1L,
            durationSumNanos = BigInt("7200000000000"),
            durationAvgNanos = Some(BigInt("7200000000000"))
          )
        )
      }
    }

    "decode one-level rows-only and columns-only layouts with an empty opposite path" in withDatabase {
      val rowsRequest    = request(rows = Seq(WorkUnitPivotTableKey.UserKey), columns = Seq.empty, grandTotals = false)
      val columnsRequest = request(rows = Seq.empty, columns = Seq(WorkUnitPivotTableKey.ProjectKey), grandTotals = false)

      for {
        organization <- withOrganization()
        user         <- withUser()
        client       <- withClient(organization)
        project      <- withProject(organization, client)
        worklog = workUnitGenerator.generate.copy(
                    date = WorkUnitDate(LocalDate.of(2026, 7, 8)),
                    duration = WorkUnitDuration(Duration.ofHours(2)),
                    entryType = WorkUnitEntryType.Worklog,
                    state = WorkUnitState.Active
                  )
        _                <- withWorkUnit(organization, user, project, worklog)
        rowsCounts       <- repository.coordinateCounts(organization.id, rowsRequest, globalFilters)
        columnsCounts    <- repository.coordinateCounts(organization.id, columnsRequest, globalFilters)
        rowsAggregate    <- repository.aggregate(organization.id, rowsRequest, globalFilters)
        columnsAggregate <- repository.aggregate(organization.id, columnsRequest, globalFilters)
      } yield {
        rowsCounts shouldEqual CoordinateCounts(row = 1L, column = 1L)
        columnsCounts shouldEqual CoordinateCounts(row = 1L, column = 1L)
        rowsAggregate.map(row => row.rowPath -> row.columnPath) shouldEqual Seq(
          Seq(PathSegment(WorkUnitPivotTableKey.UserKey, FieldValue.Uuid(user.id))) -> Seq.empty
        )
        columnsAggregate.map(row => row.rowPath -> row.columnPath) shouldEqual Seq(
          Seq.empty -> Seq(PathSegment(WorkUnitPivotTableKey.ProjectKey, FieldValue.Uuid(project.id)))
        )
      }
    }

    "return canonical leaf, subtotal, and grand-total intersections without filling sparse cells" in withDatabase {
      val canonicalRequest = Request(
        rows = Seq(WorkUnitPivotTableKey.UserKey, WorkUnitPivotTableKey.ProjectKey),
        columns = Seq(WorkUnitPivotTableKey.MonthYearKey),
        values = Seq(WorkUnitPivotTableValue.EntriesCount, WorkUnitPivotTableValue.DurationSum, WorkUnitPivotTableValue.DurationAvg),
        filters = Seq.empty,
        options = Options(rowSubtotals = true, columnSubtotals = true, grandTotals = true)
      )

      for {
        organization <- withOrganization()
        user         <- withUser()
        client       <- withClient(organization)
        projectA     <- withProject(organization, client)
        projectB     <- withProject(organization, client)
        _            <- withWorkUnit(organization, user, projectA, worklog(LocalDate.of(2026, 7, 10), Duration.ofHours(2)))
        _            <- withWorkUnit(organization, user, projectA, worklog(LocalDate.of(2026, 8, 10), Duration.ofHours(3)))
        _            <- withWorkUnit(organization, user, projectB, worklog(LocalDate.of(2026, 7, 11), Duration.ofHours(1)))
        counts       <- repository.coordinateCounts(organization.id, canonicalRequest, globalFilters)
        rows         <- repository.aggregate(organization.id, canonicalRequest, globalFilters)
      } yield {
        val userPath     = Seq(PathSegment(WorkUnitPivotTableKey.UserKey, FieldValue.Uuid(user.id)))
        val projectAPath = userPath :+ PathSegment(WorkUnitPivotTableKey.ProjectKey, FieldValue.Uuid(projectA.id))
        val projectBPath = userPath :+ PathSegment(WorkUnitPivotTableKey.ProjectKey, FieldValue.Uuid(projectB.id))
        val julyPath     = Seq(PathSegment(WorkUnitPivotTableKey.MonthYearKey, FieldValue.Month(YearMonth.of(2026, 7))))
        val augustPath   = Seq(PathSegment(WorkUnitPivotTableKey.MonthYearKey, FieldValue.Month(YearMonth.of(2026, 8))))
        val emptyPath    = Seq.empty[PathSegment]

        counts shouldEqual CoordinateCounts(row = 4L, column = 3L)
        rows.map(row => (row.rowPath, row.columnPath) -> metrics(row)).toMap shouldEqual Map(
          (projectAPath, julyPath)   -> (1L, hours(2), Some(hours(2))),
          (projectAPath, augustPath) -> (1L, hours(3), Some(hours(3))),
          (projectAPath, emptyPath)  -> (2L, hours(5), Some(hours(2.5))),
          (projectBPath, julyPath)   -> (1L, hours(1), Some(hours(1))),
          (projectBPath, emptyPath)  -> (1L, hours(1), Some(hours(1))),
          (userPath, julyPath)       -> (2L, hours(3), Some(hours(1.5))),
          (userPath, augustPath)     -> (1L, hours(3), Some(hours(3))),
          (userPath, emptyPath)      -> (3L, hours(6), Some(hours(2))),
          (emptyPath, julyPath)      -> (2L, hours(3), Some(hours(1.5))),
          (emptyPath, augustPath)    -> (1L, hours(3), Some(hours(3))),
          (emptyPath, emptyPath)     -> (3L, hours(6), Some(hours(2)))
        )
        rows must haveSize(11)
        rows.map(row => row.rowPath -> row.columnPath) must not(contain(projectBPath -> augustPath))
      }
    }

    "apply row subtotals, one-level column subtotals, and grand totals independently" in withDatabase {
      val withoutRowSubtotals    = canonicalRequest(Options(rowSubtotals = false, columnSubtotals = true, grandTotals = true))
      val withoutColumnSubtotals = canonicalRequest(Options(rowSubtotals = true, columnSubtotals = false, grandTotals = true))
      val withoutGrandTotals     = canonicalRequest(Options(rowSubtotals = true, columnSubtotals = true, grandTotals = false))

      for {
        organization   <- withOrganization()
        user           <- withUser()
        client         <- withClient(organization)
        projectA       <- withProject(organization, client)
        projectB       <- withProject(organization, client)
        _              <- withWorkUnit(organization, user, projectA, worklog(LocalDate.of(2026, 7, 10), Duration.ofHours(2)))
        _              <- withWorkUnit(organization, user, projectA, worklog(LocalDate.of(2026, 8, 10), Duration.ofHours(3)))
        _              <- withWorkUnit(organization, user, projectB, worklog(LocalDate.of(2026, 7, 11), Duration.ofHours(1)))
        noRowCounts    <- repository.coordinateCounts(organization.id, withoutRowSubtotals, globalFilters)
        noRowRows      <- repository.aggregate(organization.id, withoutRowSubtotals, globalFilters)
        noColumnCounts <- repository.coordinateCounts(organization.id, withoutColumnSubtotals, globalFilters)
        noColumnRows   <- repository.aggregate(organization.id, withoutColumnSubtotals, globalFilters)
        noGrandCounts  <- repository.coordinateCounts(organization.id, withoutGrandTotals, globalFilters)
        noGrandRows    <- repository.aggregate(organization.id, withoutGrandTotals, globalFilters)
      } yield {
        val userPath  = Seq(PathSegment(WorkUnitPivotTableKey.UserKey, FieldValue.Uuid(user.id)))
        val leafPaths = Seq(projectA, projectB).map(project => userPath :+ PathSegment(WorkUnitPivotTableKey.ProjectKey, FieldValue.Uuid(project.id)))
        val julyPath  = Seq(PathSegment(WorkUnitPivotTableKey.MonthYearKey, FieldValue.Month(YearMonth.of(2026, 7))))
        val augustPath = Seq(PathSegment(WorkUnitPivotTableKey.MonthYearKey, FieldValue.Month(YearMonth.of(2026, 8))))
        val emptyPath  = Seq.empty[PathSegment]

        noRowCounts shouldEqual CoordinateCounts(row = 3L, column = 3L)
        noRowRows must haveSize(8)
        noRowRows.map(_.rowPath).distinct must containTheSameElementsAs(leafPaths :+ emptyPath)
        noRowRows.map(row => row.rowPath -> row.columnPath) must not(contain(userPath -> julyPath))

        noColumnCounts shouldEqual CoordinateCounts(row = 4L, column = 3L)
        noColumnRows.map(row => row.rowPath -> row.columnPath).toSet shouldEqual canonicalCoordinates(
          userPath,
          leafPaths.head,
          leafPaths(1),
          julyPath,
          augustPath
        )

        noGrandCounts shouldEqual CoordinateCounts(row = 3L, column = 2L)
        noGrandRows.map(row => row.rowPath -> row.columnPath).toSet shouldEqual Set(
          leafPaths.head -> julyPath,
          leafPaths.head -> augustPath,
          leafPaths(1)   -> julyPath,
          userPath       -> julyPath,
          userPath       -> augustPath
        )
        noGrandRows.map(row => row.rowPath -> row.columnPath) must not(contain(emptyPath -> emptyPath))
      }
    }

    "add and remove a real multi-level column prefix subtotal without changing crossed row or grand totals" in withDatabase {
      val withColumnSubtotals = Request(
        rows = Seq(WorkUnitPivotTableKey.UserKey, WorkUnitPivotTableKey.ProjectKey),
        columns = Seq(WorkUnitPivotTableKey.YearKey, WorkUnitPivotTableKey.MonthYearKey),
        values = Seq(WorkUnitPivotTableValue.EntriesCount, WorkUnitPivotTableValue.DurationSum, WorkUnitPivotTableValue.DurationAvg),
        filters = Seq.empty,
        options = Options(rowSubtotals = true, columnSubtotals = true, grandTotals = true)
      )
      val withoutColumnSubtotals = withColumnSubtotals.copy(options = withColumnSubtotals.options.copy(columnSubtotals = false))

      for {
        organization  <- withOrganization()
        user          <- withUser()
        client        <- withClient(organization)
        projectA      <- withProject(organization, client)
        projectB      <- withProject(organization, client)
        _             <- withWorkUnit(organization, user, projectA, worklog(LocalDate.of(2026, 7, 10), Duration.ofHours(2)))
        _             <- withWorkUnit(organization, user, projectA, worklog(LocalDate.of(2026, 8, 10), Duration.ofHours(3)))
        _             <- withWorkUnit(organization, user, projectB, worklog(LocalDate.of(2026, 7, 11), Duration.ofHours(1)))
        withCounts    <- repository.coordinateCounts(organization.id, withColumnSubtotals, globalFilters)
        withRows      <- repository.aggregate(organization.id, withColumnSubtotals, globalFilters)
        withoutCounts <- repository.coordinateCounts(organization.id, withoutColumnSubtotals, globalFilters)
        withoutRows   <- repository.aggregate(organization.id, withoutColumnSubtotals, globalFilters)
      } yield {
        val userPath     = Seq(PathSegment(WorkUnitPivotTableKey.UserKey, FieldValue.Uuid(user.id)))
        val projectAPath = userPath :+ PathSegment(WorkUnitPivotTableKey.ProjectKey, FieldValue.Uuid(projectA.id))
        val projectBPath = userPath :+ PathSegment(WorkUnitPivotTableKey.ProjectKey, FieldValue.Uuid(projectB.id))
        val yearPath     = Seq(PathSegment(WorkUnitPivotTableKey.YearKey, FieldValue.Year(2026)))
        val julyPath     = yearPath :+ PathSegment(WorkUnitPivotTableKey.MonthYearKey, FieldValue.Month(YearMonth.of(2026, 7)))
        val augustPath   = yearPath :+ PathSegment(WorkUnitPivotTableKey.MonthYearKey, FieldValue.Month(YearMonth.of(2026, 8)))
        val emptyPath    = Seq.empty[PathSegment]
        val expectedWithSubtotals = Map(
          (projectAPath, julyPath)   -> (1L, hours(2), Some(hours(2))),
          (projectAPath, augustPath) -> (1L, hours(3), Some(hours(3))),
          (projectAPath, yearPath)   -> (2L, hours(5), Some(hours(2.5))),
          (projectAPath, emptyPath)  -> (2L, hours(5), Some(hours(2.5))),
          (projectBPath, julyPath)   -> (1L, hours(1), Some(hours(1))),
          (projectBPath, yearPath)   -> (1L, hours(1), Some(hours(1))),
          (projectBPath, emptyPath)  -> (1L, hours(1), Some(hours(1))),
          (userPath, julyPath)       -> (2L, hours(3), Some(hours(1.5))),
          (userPath, augustPath)     -> (1L, hours(3), Some(hours(3))),
          (userPath, yearPath)       -> (3L, hours(6), Some(hours(2))),
          (userPath, emptyPath)      -> (3L, hours(6), Some(hours(2))),
          (emptyPath, julyPath)      -> (2L, hours(3), Some(hours(1.5))),
          (emptyPath, augustPath)    -> (1L, hours(3), Some(hours(3))),
          (emptyPath, yearPath)      -> (3L, hours(6), Some(hours(2))),
          (emptyPath, emptyPath)     -> (3L, hours(6), Some(hours(2)))
        )
        val expectedWithoutSubtotals = expectedWithSubtotals.filterNot { case ((_, columnPath), _) =>
          columnPath == yearPath
        }

        withCounts shouldEqual CoordinateCounts(row = 4L, column = 4L)
        withoutCounts shouldEqual CoordinateCounts(row = 4L, column = 3L)
        withRows.map(row => (row.rowPath, row.columnPath) -> metrics(row)).toMap shouldEqual expectedWithSubtotals
        withoutRows.map(row => (row.rowPath, row.columnPath) -> metrics(row)).toMap shouldEqual expectedWithoutSubtotals
        withRows must haveSize(15)
        withoutRows must haveSize(11)
        withRows.map(_.rowPath).toSet shouldEqual withoutRows.map(_.rowPath).toSet
        metrics(withRows.find(row => row.rowPath == emptyPath && row.columnPath == emptyPath).get) shouldEqual
          metrics(withoutRows.find(row => row.rowPath == emptyPath && row.columnPath == emptyPath).get)
      }
    }

    "count Notes but exclude them from duration sum and average at leaf, subtotal, and grand total" in withDatabase {
      val metricRequest = Request(
        rows = Seq(WorkUnitPivotTableKey.UserKey, WorkUnitPivotTableKey.ProjectKey),
        columns = Seq.empty,
        values = Seq(WorkUnitPivotTableValue.EntriesCount, WorkUnitPivotTableValue.DurationSum, WorkUnitPivotTableValue.DurationAvg),
        filters = Seq.empty,
        options = Options(rowSubtotals = true, columnSubtotals = false, grandTotals = true)
      )

      for {
        organization <- withOrganization()
        user         <- withUser()
        client       <- withClient(organization)
        mixedProject <- withProject(organization, client)
        noteProject  <- withProject(organization, client)
        _            <- withWorkUnit(organization, user, mixedProject, worklog(LocalDate.of(2026, 7, 10), Duration.ofHours(2)))
        _            <- withWorkUnit(organization, user, mixedProject, note(LocalDate.of(2026, 7, 10)))
        _            <- withWorkUnit(organization, user, noteProject, note(LocalDate.of(2026, 7, 11)))
        rows         <- repository.aggregate(organization.id, metricRequest, globalFilters)
      } yield {
        val userPath  = Seq(PathSegment(WorkUnitPivotTableKey.UserKey, FieldValue.Uuid(user.id)))
        val mixedPath = userPath :+ PathSegment(WorkUnitPivotTableKey.ProjectKey, FieldValue.Uuid(mixedProject.id))
        val notePath  = userPath :+ PathSegment(WorkUnitPivotTableKey.ProjectKey, FieldValue.Uuid(noteProject.id))
        val byPath    = rows.map(row => row.rowPath -> metrics(row)).toMap

        byPath(mixedPath) shouldEqual (2L, hours(2), Some(hours(2)))
        byPath(notePath) shouldEqual (1L, BigInt(0), None)
        byPath(userPath) shouldEqual (3L, hours(2), Some(hours(2)))
        byPath(Seq.empty) shouldEqual (3L, hours(2), Some(hours(2)))
      }
    }

    "group and filter ClientKey through the project's current client" in withDatabase {
      for {
        organization   <- withOrganization()
        user           <- withUser()
        formerClient   <- withClient(organization)
        currentClient  <- withClient(organization)
        project        <- withProject(organization, formerClient)
        _              <- withWorkUnit(organization, user, project, worklog(LocalDate.of(2026, 7, 10), Duration.ofHours(2)))
        updatedProject <- projectRepository.update(project.copy(clientId = currentClient.id))
        currentRequest = request(rows = Seq(WorkUnitPivotTableKey.ClientKey), columns = Seq.empty, grandTotals = false)
                           .copy(filters = Seq(Filter(WorkUnitPivotTableKey.ClientKey, Some(Seq(FieldValue.Uuid(currentClient.id))))))
        formerRequest = currentRequest.copy(filters = Seq(Filter(WorkUnitPivotTableKey.ClientKey, Some(Seq(FieldValue.Uuid(formerClient.id))))))
        currentCounts <- repository.coordinateCounts(organization.id, currentRequest, globalFilters)
        currentRows   <- repository.aggregate(organization.id, currentRequest, globalFilters)
        formerCounts  <- repository.coordinateCounts(organization.id, formerRequest, globalFilters)
        formerRows    <- repository.aggregate(organization.id, formerRequest, globalFilters)
      } yield {
        updatedProject shouldEqual Some(project.copy(clientId = currentClient.id))
        currentCounts shouldEqual CoordinateCounts(row = 1L, column = 1L)
        currentRows.map(_.rowPath) shouldEqual Seq(Seq(PathSegment(WorkUnitPivotTableKey.ClientKey, FieldValue.Uuid(currentClient.id))))
        formerCounts shouldEqual CoordinateCounts(row = 0L, column = 1L)
        formerRows should beEmpty
      }
    }

    "compose typed global arrays with every pivot filter in real PostgreSQL" in withDatabase {
      for {
        organization <- withOrganization()
        user         <- withUser()
        otherUser    <- withUser()
        client       <- withClient(organization)
        otherClient  <- withClient(organization)
        project      <- withProject(organization, client)
        otherProject <- withProject(organization, otherClient)
        _            <- withWorkUnit(organization, user, project, worklog(LocalDate.of(2026, 7, 10), Duration.ofHours(2)))
        _            <- withWorkUnit(organization, otherUser, otherProject, note(LocalDate.of(2026, 7, 11)))
        requestWithFilters = request(rows = Seq.empty, columns = Seq.empty).copy(filters =
                               Seq(
                                 Filter(WorkUnitPivotTableKey.UserKey, Some(Seq(FieldValue.Uuid(user.id)))),
                                 Filter(WorkUnitPivotTableKey.ProjectKey, Some(Seq(FieldValue.Uuid(project.id)))),
                                 Filter(WorkUnitPivotTableKey.ClientKey, Some(Seq(FieldValue.Uuid(client.id)))),
                                 Filter(WorkUnitPivotTableKey.YearKey, Some(Seq(FieldValue.Year(2026)))),
                                 Filter(WorkUnitPivotTableKey.MonthYearKey, Some(Seq(FieldValue.Month(YearMonth.of(2026, 7))))),
                                 Filter(WorkUnitPivotTableKey.DateKey, Some(Seq(FieldValue.Date(LocalDate.of(2026, 7, 10))))),
                                 Filter(WorkUnitPivotTableKey.EntryTypeKey, Some(Seq(FieldValue.EntryType(WorkUnitEntryType.Worklog))))
                               )
                             )
        typedGlobals = globalFilters.copy(projectIds = Seq(project.id), userIds = Seq(user.id), clientIds = Seq(client.id))
        rows <- repository.aggregate(organization.id, requestWithFilters, typedGlobals)
      } yield rows.map(metrics) shouldEqual Seq((1L, hours(2), Some(hours(2))))
    }

    "exclude archived work units" in withDatabase {
      for {
        organization   <- withOrganization()
        user           <- withUser()
        client         <- withClient(organization)
        project        <- withProject(organization, client)
        active         <- withWorkUnit(organization, user, project, worklog(LocalDate.of(2026, 7, 10), Duration.ofHours(2)))
        archived       <- withWorkUnit(organization, user, project, worklog(LocalDate.of(2026, 7, 11), Duration.ofHours(4)))
        archivedUpdate <- workUnitRepository.update(archived.copy(archivedAt = Some(WorkUnitArchivedAt(Instant.parse("2026-07-12T00:00:00Z")))))
        rows           <- repository.aggregate(organization.id, request(rows = Seq.empty, columns = Seq.empty), globalFilters)
      } yield {
        active.archivedAt should beNone
        archivedUpdate.flatMap(_.archivedAt) should beSome
        rows.map(metrics) shouldEqual Seq((1L, hours(2), Some(hours(2))))
      }
    }

    "return unique typed available filter members and skip SQL when no facets are requested" in withDatabase {
      for {
        organization <- withOrganization()
        firstUser    <- withUser()
        secondUser   <- withUser()
        client       <- withClient(organization)
        project      <- withProject(organization, client)
        _            <- withWorkUnit(organization, firstUser, project, worklog(LocalDate.of(2026, 7, 10), Duration.ofHours(1)))
        _            <- withWorkUnit(organization, firstUser, project, worklog(LocalDate.of(2026, 7, 11), Duration.ofHours(2)))
        _            <- withWorkUnit(organization, secondUser, project, note(LocalDate.of(2026, 7, 12)))
        noFacets     <- repository.availableFilterMembers(organization.id, request(rows = Seq.empty, columns = Seq.empty), globalFilters)
        facetRequest =
          request(rows = Seq.empty, columns = Seq.empty).copy(filters = Seq(Filter(WorkUnitPivotTableKey.UserKey, includedValues = None)))
        facets <- repository.availableFilterMembers(organization.id, facetRequest, globalFilters)
        emptyFacets <-
          repository.availableFilterMembers(
            organization.id,
            facetRequest,
            globalFilters.copy(dateFrom = WorkUnitDate(LocalDate.of(2027, 1, 1)), dateTo = Some(WorkUnitDate(LocalDate.of(2027, 12, 31))))
          )
        independentFacetRequest = request(rows = Seq.empty, columns = Seq.empty).copy(filters =
                                    Seq(
                                      Filter(WorkUnitPivotTableKey.UserKey, Some(Seq(FieldValue.Uuid(firstUser.id)))),
                                      Filter(WorkUnitPivotTableKey.EntryTypeKey, Some(Seq(FieldValue.EntryType(WorkUnitEntryType.Note))))
                                    )
                                  )
        independentFacets <- repository.availableFilterMembers(organization.id, independentFacetRequest, globalFilters)
      } yield {
        noFacets should beEmpty
        facets.keySet shouldEqual Set(WorkUnitPivotTableKey.UserKey)
        facets(WorkUnitPivotTableKey.UserKey) must containTheSameElementsAs(Seq(FieldValue.Uuid(firstUser.id), FieldValue.Uuid(secondUser.id)))
        facets(WorkUnitPivotTableKey.UserKey).distinct must haveSize(2)
        emptyFacets shouldEqual Map(WorkUnitPivotTableKey.UserKey -> Seq.empty)
        independentFacets shouldEqual Map(
          WorkUnitPivotTableKey.UserKey      -> Seq(FieldValue.Uuid(secondUser.id)),
          WorkUnitPivotTableKey.EntryTypeKey -> Seq(FieldValue.EntryType(WorkUnitEntryType.Worklog))
        )
      }
    }

    "facet every typed dimension in request order and assemble deterministic members across repeated calls" in withDatabase {
      for {
        organization  <- withOrganization()
        firstUser     <- withUser()
        secondUser    <- withUser()
        firstClient   <- withClient(organization)
        secondClient  <- withClient(organization)
        firstProject  <- withProject(organization, firstClient)
        secondProject <- withProject(organization, secondClient)
        _             <- withWorkUnit(organization, firstUser, firstProject, worklog(LocalDate.of(2025, 12, 31), Duration.ofHours(1)))
        _             <- withWorkUnit(organization, secondUser, secondProject, note(LocalDate.of(2026, 7, 1)))
        facetRequest = request(rows = Seq.empty, columns = Seq.empty).copy(filters =
                         Seq(
                           Filter(WorkUnitPivotTableKey.EntryTypeKey, None),
                           Filter(WorkUnitPivotTableKey.DateKey, None),
                           Filter(WorkUnitPivotTableKey.UserKey, None),
                           Filter(WorkUnitPivotTableKey.ClientKey, None),
                           Filter(WorkUnitPivotTableKey.MonthYearKey, None),
                           Filter(WorkUnitPivotTableKey.ProjectKey, None),
                           Filter(WorkUnitPivotTableKey.YearKey, None)
                         )
                       )
        facetGlobals = globalFilters.copy(dateFrom = WorkUnitDate(LocalDate.of(2025, 1, 1)))
        firstAvailable  <- repository.availableFilterMembers(organization.id, facetRequest, facetGlobals)
        secondAvailable <- repository.availableFilterMembers(organization.id, facetRequest, facetGlobals)
      } yield {
        firstAvailable shouldEqual secondAvailable
        firstAvailable.keySet shouldEqual facetRequest.filters.map(_.key).toSet
        firstAvailable(WorkUnitPivotTableKey.EntryTypeKey) must containTheSameElementsAs(
          Seq(FieldValue.EntryType(WorkUnitEntryType.Worklog), FieldValue.EntryType(WorkUnitEntryType.Note))
        )
        firstAvailable(WorkUnitPivotTableKey.DateKey) must containTheSameElementsAs(
          Seq(FieldValue.Date(LocalDate.of(2025, 12, 31)), FieldValue.Date(LocalDate.of(2026, 7, 1)))
        )
        firstAvailable(WorkUnitPivotTableKey.UserKey) must containTheSameElementsAs(
          Seq(FieldValue.Uuid(firstUser.id), FieldValue.Uuid(secondUser.id))
        )
        firstAvailable(WorkUnitPivotTableKey.ClientKey) must containTheSameElementsAs(
          Seq(FieldValue.Uuid(firstClient.id), FieldValue.Uuid(secondClient.id))
        )
        firstAvailable(WorkUnitPivotTableKey.MonthYearKey) must containTheSameElementsAs(
          Seq(FieldValue.Month(YearMonth.of(2025, 12)), FieldValue.Month(YearMonth.of(2026, 7)))
        )
        firstAvailable(WorkUnitPivotTableKey.ProjectKey) must containTheSameElementsAs(
          Seq(FieldValue.Uuid(firstProject.id), FieldValue.Uuid(secondProject.id))
        )
        firstAvailable(WorkUnitPivotTableKey.YearKey) must containTheSameElementsAs(Seq(FieldValue.Year(2025), FieldValue.Year(2026)))

        val firstResponse  = assembler.assemble(facetRequest, Seq.empty, firstAvailable, 0L, 10000L)
        val secondResponse = assembler.assemble(facetRequest, Seq.empty, secondAvailable, 0L, 10000L)
        firstResponse shouldEqual secondResponse
        firstResponse.filterMembers.map(_.key) shouldEqual facetRequest.filters.map(_.key)
        firstResponse.filterMembers.find(_.key == WorkUnitPivotTableKey.EntryTypeKey).map(_.members.map(_.value)) should beSome(
          Seq(FieldValue.EntryType(WorkUnitEntryType.Worklog), FieldValue.EntryType(WorkUnitEntryType.Note))
        )
        firstResponse.filterMembers.find(_.key == WorkUnitPivotTableKey.DateKey).map(_.members.map(_.value)) should beSome(
          Seq(FieldValue.Date(LocalDate.of(2025, 12, 31)), FieldValue.Date(LocalDate.of(2026, 7, 1)))
        )
        firstResponse.filterMembers.find(_.key == WorkUnitPivotTableKey.MonthYearKey).map(_.members.map(_.value)) should beSome(
          Seq(FieldValue.Month(YearMonth.of(2025, 12)), FieldValue.Month(YearMonth.of(2026, 7)))
        )
        firstResponse.filterMembers.find(_.key == WorkUnitPivotTableKey.YearKey).map(_.members.map(_.value)) should beSome(
          Seq(FieldValue.Year(2025), FieldValue.Year(2026))
        )
      }
    }

    "ignore a facet's own missing selection and retain it once as unavailable after assembly" in withDatabase {
      for {
        organization <- withOrganization()
        user         <- withUser()
        client       <- withClient(organization)
        project      <- withProject(organization, client)
        _            <- withWorkUnit(organization, user, project, worklog(LocalDate.of(2026, 7, 10), Duration.ofHours(1)))
        facetRequest =
          request(rows = Seq.empty, columns = Seq.empty).copy(filters = Seq(Filter(WorkUnitPivotTableKey.YearKey, Some(Seq(FieldValue.Year(2025))))))
        available <- repository.availableFilterMembers(organization.id, facetRequest, globalFilters)
      } yield {
        available shouldEqual Map(WorkUnitPivotTableKey.YearKey -> Seq(FieldValue.Year(2026)))
        assembler.assemble(facetRequest, Seq.empty, available, 0L, 10000L).filterMembers shouldEqual Seq(
          FilterMembers(
            WorkUnitPivotTableKey.YearKey,
            Seq(FilterMember(FieldValue.Year(2026), available = true), FilterMember(FieldValue.Year(2025), available = false))
          )
        )
      }
    }

    "aggregate a representative 1200-row sparse pivot without returning raw or Cartesian-filled records" in withDatabase {
      val representativeRequest = Request(
        rows = Seq(WorkUnitPivotTableKey.UserKey, WorkUnitPivotTableKey.ProjectKey),
        columns = Seq(WorkUnitPivotTableKey.YearKey, WorkUnitPivotTableKey.MonthYearKey),
        values = Seq(WorkUnitPivotTableValue.EntriesCount, WorkUnitPivotTableValue.DurationSum, WorkUnitPivotTableValue.DurationAvg),
        filters = Seq(Filter(WorkUnitPivotTableKey.EntryTypeKey, None), Filter(WorkUnitPivotTableKey.DateKey, None)),
        options = Options(rowSubtotals = true, columnSubtotals = true, grandTotals = true)
      )

      for {
        organization <- withOrganization()
        users <- DBIO.sequence((0 until 20).map { index =>
                   withUser(allGenerators[User].normal.generate.copy(id = UserId(new UUID(0x5000000000000000L, index.toLong + 1L))))
                 })
        client <- withClient(organization)
        projects <- DBIO.sequence((0 until 20).map { index =>
                      withProject(
                        organization,
                        client,
                        allGenerators[Project].normal.generate.copy(
                          id = ProjectId(new UUID(0x6000000000000000L, index.toLong + 1L)),
                          createdBy = users.head.id,
                          modifiedBy = users.head.id
                        )
                      )
                    })
        sourceRows = representativeWorkUnits(organization.id, users.map(_.id), projects.map(_.id), workUnitGenerator.generate)
        inserted <- TableQuery[WorkUnitTable] ++= sourceRows
        counts   <- repository.coordinateCounts(organization.id, representativeRequest, representativeGlobalFilters)
        rows     <- repository.aggregate(organization.id, representativeRequest, representativeGlobalFilters)
        facets   <- repository.availableFilterMembers(organization.id, representativeRequest, representativeGlobalFilters)
      } yield {
        val activeRows       = sourceRows.filter(_.archivedAt.isEmpty)
        val emptyPath        = Seq.empty[PathSegment]
        val firstUserPath    = Seq(PathSegment(WorkUnitPivotTableKey.UserKey, FieldValue.Uuid(users.head.id)))
        val firstProjectPath = firstUserPath :+ PathSegment(WorkUnitPivotTableKey.ProjectKey, FieldValue.Uuid(projects.head.id))
        val januaryPath = Seq(
          PathSegment(WorkUnitPivotTableKey.YearKey, FieldValue.Year(2025)),
          PathSegment(WorkUnitPivotTableKey.MonthYearKey, FieldValue.Month(YearMonth.of(2025, 1)))
        )
        val secondUserPath    = Seq(PathSegment(WorkUnitPivotTableKey.UserKey, FieldValue.Uuid(users(1).id)))
        val secondProjectPath = secondUserPath :+ PathSegment(WorkUnitPivotTableKey.ProjectKey, FieldValue.Uuid(projects.head.id))
        val februaryPath = Seq(
          PathSegment(WorkUnitPivotTableKey.YearKey, FieldValue.Year(2025)),
          PathSegment(WorkUnitPivotTableKey.MonthYearKey, FieldValue.Month(YearMonth.of(2025, 2)))
        )
        val byCoordinate = rows.map(row => (row.rowPath, row.columnPath) -> metrics(row)).toMap
        val response     = assembler.assemble(representativeRequest, rows, facets, counts.row * counts.column * 3L, 20000L)
        val expectedDates = activeRows
          .map(row => FieldValue.Date(row.date.asInstanceOf[LocalDate]))
          .distinct
          .sortBy { case FieldValue.Date(date) => date }

        inserted shouldEqual Some(sourceRows.size)
        activeRows must haveSize(1200)
        activeRows.map(_.userId).distinct must haveSize(20)
        activeRows.map(_.projectId).distinct must haveSize(20)
        activeRows.map(_.date.asInstanceOf[LocalDate].getMonthValue).distinct must haveSize(12)
        activeRows.groupMapReduce(_.entryType)(_ => 1)(_ + _) shouldEqual Map(WorkUnitEntryType.Worklog -> 600, WorkUnitEntryType.Note -> 600)
        sourceRows.count(_.archivedAt.nonEmpty) shouldEqual 20
        counts shouldEqual CoordinateCounts(row = 421L, column = 14L)
        rows must haveSize(1514)
        rows.count(row => row.rowPath.size == 2 && row.columnPath.size == 2) shouldEqual 600
        rows.count(row => row.rowPath.size == 2 && row.columnPath.size == 2) must beLessThan(activeRows.size)
        rows.size must beLessThan((counts.row * counts.column).toInt)
        rows.forall(row =>
          row.rowPath.size <= representativeRequest.rows.size && row.columnPath.size <= representativeRequest.columns.size
        ) should beTrue
        byCoordinate(firstProjectPath -> januaryPath) shouldEqual (2L, hours(0.5), Some(hours(0.25)))
        byCoordinate(secondProjectPath -> februaryPath) shouldEqual (2L, BigInt(0), None)
        byCoordinate(emptyPath -> emptyPath) shouldEqual (1200L, hours(300), Some(hours(0.5)))
        facets.keySet shouldEqual Set(WorkUnitPivotTableKey.EntryTypeKey, WorkUnitPivotTableKey.DateKey)
        facets(WorkUnitPivotTableKey.EntryTypeKey).distinct must haveSize(2)
        facets(WorkUnitPivotTableKey.DateKey).distinct must haveSize(84)
        response.filterMembers.map(_.key) shouldEqual representativeRequest.filters.map(_.key)
        response.filterMembers.head.members.map(_.value) shouldEqual Seq(
          FieldValue.EntryType(WorkUnitEntryType.Worklog),
          FieldValue.EntryType(WorkUnitEntryType.Note)
        )
        response.filterMembers(1).members.map(_.value) shouldEqual expectedDates
        response.result.cellCount shouldEqual 1514L
        response.result.displayValueCellCount shouldEqual 17682L
      }
    }
  }

  "strict raw decoding" should {
    "reconstruct every field kind in request-axis order rather than JSON object order" in {
      val userId    = UUID.fromString("10000000-0000-4000-8000-000000000001")
      val projectId = UUID.fromString("20000000-0000-4000-8000-000000000002")
      val clientId  = UUID.fromString("30000000-0000-4000-8000-000000000003")
      val keys = Seq(
        WorkUnitPivotTableKey.UserKey,
        WorkUnitPivotTableKey.ProjectKey,
        WorkUnitPivotTableKey.ClientKey,
        WorkUnitPivotTableKey.YearKey,
        WorkUnitPivotTableKey.MonthYearKey,
        WorkUnitPivotTableKey.DateKey,
        WorkUnitPivotTableKey.EntryTypeKey
      )
      val reversedJson = s"""{
                            |  "EntryTypeKey": "Worklog",
                            |  "DateKey": "2026-07-10",
                            |  "MonthYearKey": "2026-07",
                            |  "YearKey": 2026,
                            |  "ClientKey": "$clientId",
                            |  "ProjectKey": "$projectId",
                            |  "UserKey": "$userId"
                            |}""".stripMargin

      WorkUnitPivotQueryRepositoryImpl.decodePath("rowPath", keys, reversedJson) shouldEqual Seq(
        PathSegment(WorkUnitPivotTableKey.UserKey, FieldValue.Uuid(userId)),
        PathSegment(WorkUnitPivotTableKey.ProjectKey, FieldValue.Uuid(projectId)),
        PathSegment(WorkUnitPivotTableKey.ClientKey, FieldValue.Uuid(clientId)),
        PathSegment(WorkUnitPivotTableKey.YearKey, FieldValue.Year(2026)),
        PathSegment(WorkUnitPivotTableKey.MonthYearKey, FieldValue.Month(YearMonth.of(2026, 7))),
        PathSegment(WorkUnitPivotTableKey.DateKey, FieldValue.Date(LocalDate.of(2026, 7, 10))),
        PathSegment(WorkUnitPivotTableKey.EntryTypeKey, FieldValue.EntryType(WorkUnitEntryType.Worklog))
      )
    }

    "distinguish a grouped client null from an absent grouped-out key and reject impossible paths" in {
      val userId = UUID.fromString("10000000-0000-4000-8000-000000000001")
      val keys   = Seq(WorkUnitPivotTableKey.UserKey, WorkUnitPivotTableKey.ClientKey)

      WorkUnitPivotQueryRepositoryImpl.decodePath("rowPath", keys, s"""{"UserKey":"$userId","ClientKey":null}""") shouldEqual Seq(
        PathSegment(WorkUnitPivotTableKey.UserKey, FieldValue.Uuid(userId)),
        PathSegment(WorkUnitPivotTableKey.ClientKey, FieldValue.Null)
      )
      WorkUnitPivotQueryRepositoryImpl.decodePath("rowPath", keys, s"""{"UserKey":"$userId"}""") shouldEqual Seq(
        PathSegment(WorkUnitPivotTableKey.UserKey, FieldValue.Uuid(userId))
      )
      WorkUnitPivotQueryRepositoryImpl.decodePath("rowPath", keys, "{}") should beEmpty
      WorkUnitPivotQueryRepositoryImpl.decodePath("rowPath", keys, """{"ClientKey":null}""") must throwA[IllegalStateException]
      WorkUnitPivotQueryRepositoryImpl.decodePath(
        "rowPath",
        Seq(WorkUnitPivotTableKey.UserKey),
        s"""{"UserKey":"$userId","ProjectKey":"$userId"}"""
      ) must throwA[IllegalStateException]
      WorkUnitPivotQueryRepositoryImpl
        .decodePath("rowPath", Seq(WorkUnitPivotTableKey.UserKey), """{"UserKey":null}""") must throwA[IllegalStateException]
      WorkUnitPivotQueryRepositoryImpl.decodePath("rowPath", Seq.empty, "{") must throwA[IllegalStateException]
    }

    "distinguish a grouped task null from an absent grouped-out key" in {
      val keys   = Seq(WorkUnitPivotTableKey.UserKey, WorkUnitPivotTableKey.TaskKey)
      val userId = UUID.fromString("10000000-0000-4000-8000-000000000001")

      WorkUnitPivotQueryRepositoryImpl.decodePath("rowPath", keys, s"""{"UserKey":"$userId","TaskKey":null}""") shouldEqual Seq(
        PathSegment(WorkUnitPivotTableKey.UserKey, FieldValue.Uuid(userId)),
        PathSegment(WorkUnitPivotTableKey.TaskKey, FieldValue.Null)
      )
      WorkUnitPivotQueryRepositoryImpl.decodePath("rowPath", keys, s"""{"UserKey":"$userId","TaskKey":"PROJ-1"}""") shouldEqual Seq(
        PathSegment(WorkUnitPivotTableKey.UserKey, FieldValue.Uuid(userId)),
        PathSegment(WorkUnitPivotTableKey.TaskKey, FieldValue.TaskId("PROJ-1"))
      )
    }

    "reject malformed or wrongly typed UUID, year, month, date, client, and entry values" in {
      val malformed = Seq(
        WorkUnitPivotTableKey.UserKey      -> "\"not-a-uuid\"",
        WorkUnitPivotTableKey.ProjectKey   -> "1",
        WorkUnitPivotTableKey.ClientKey    -> "true",
        WorkUnitPivotTableKey.TaskKey      -> "1",
        WorkUnitPivotTableKey.YearKey      -> "\"2026\"",
        WorkUnitPivotTableKey.YearKey      -> "2026.5",
        WorkUnitPivotTableKey.MonthYearKey -> "\"2026-7\"",
        WorkUnitPivotTableKey.MonthYearKey -> "\"2026-13\"",
        WorkUnitPivotTableKey.DateKey      -> "\"2026-7-10\"",
        WorkUnitPivotTableKey.DateKey      -> "\"2026-02-30\"",
        WorkUnitPivotTableKey.EntryTypeKey -> "\"worklog\"",
        WorkUnitPivotTableKey.EntryTypeKey -> "1"
      )

      malformed.foreach { case (key, rawValue) =>
        WorkUnitPivotQueryRepositoryImpl.decodePath("rowPath", Seq(key), s"""{"${key.entryName}":$rawValue}""") must throwA[IllegalStateException]
      }
      success
    }

    "reject a parseable but noncanonical UUID in an aggregate path" in {
      WorkUnitPivotQueryRepositoryImpl
        .decodePath("rowPath", Seq(WorkUnitPivotTableKey.UserKey), """{"UserKey":"1-1-1-1-1"}""") must throwA[IllegalStateException]
    }

    "keep canonical uppercase UUIDs valid" in {
      val uppercaseUuid = "AAAAAAAA-BBBB-4CCC-8DDD-EEEEEEEEEEEE"

      WorkUnitPivotQueryRepositoryImpl.decodePath("rowPath", Seq(WorkUnitPivotTableKey.UserKey), s"""{"UserKey":"$uppercaseUuid"}""") shouldEqual Seq(
        PathSegment(WorkUnitPivotTableKey.UserKey, FieldValue.Uuid(UUID.fromString(uppercaseUuid)))
      )
    }

    "reject a parseable but noncanonical UUID in a facet value" in {
      import WorkUnitPivotQueryRepositoryImpl.RawFilterMember

      val facetRequest = request(rows = Seq.empty, columns = Seq.empty)
        .copy(filters = Seq(Filter(WorkUnitPivotTableKey.UserKey, None)))

      WorkUnitPivotQueryRepositoryImpl
        .decodeFilterMembers(facetRequest, Seq(RawFilterMember(0, Some("\"1-1-1-1-1\"")))) must throwA[IllegalStateException]
    }

    "reject missing, duplicate, and negative coordinate-count rows" in {
      import WorkUnitPivotQueryRepositoryImpl.RawCoordinateCounts

      WorkUnitPivotQueryRepositoryImpl.decodeCoordinateCounts(Seq(RawCoordinateCounts(2L, 3L))) shouldEqual CoordinateCounts(2L, 3L)
      WorkUnitPivotQueryRepositoryImpl.decodeCoordinateCounts(Seq.empty) must throwA[IllegalStateException]
      WorkUnitPivotQueryRepositoryImpl
        .decodeCoordinateCounts(Seq(RawCoordinateCounts(1L, 1L), RawCoordinateCounts(1L, 1L))) must throwA[IllegalStateException]
      WorkUnitPivotQueryRepositoryImpl.decodeCoordinateCounts(Seq(RawCoordinateCounts(-1L, 0L))) must throwA[IllegalStateException]
      WorkUnitPivotQueryRepositoryImpl.decodeCoordinateCounts(Seq(RawCoordinateCounts(0L, -1L))) must throwA[IllegalStateException]
    }

    "accept SQL-rounded integral nanos but reject fractional, negative, null, and negative counts" in {
      import WorkUnitPivotQueryRepositoryImpl.RawAggregateRow

      // PostgreSQL intervals cannot persist nanosecond-scale 1ns/2ns fixtures. Task 3 asserts ROUND in SQL;
      // this boundary accepts its integral 2 result and rejects an unrounded 1.5 decoder input.
      WorkUnitPivotQueryRepositoryImpl.decodeNanos("durationAvgNanos", new java.math.BigDecimal("2")) shouldEqual BigInt(2)
      WorkUnitPivotQueryRepositoryImpl.decodeNanos("durationAvgNanos", new java.math.BigDecimal("1.5")) must throwA[IllegalStateException]
      WorkUnitPivotQueryRepositoryImpl.decodeNanos("durationSumNanos", new java.math.BigDecimal("-1")) must throwA[IllegalStateException]
      WorkUnitPivotQueryRepositoryImpl.decodeNanos("durationSumNanos", null) must throwA[IllegalStateException]

      val emptyRequest = request(rows = Seq.empty, columns = Seq.empty)
      val negativeCount = RawAggregateRow(
        rowPathJson = "{}",
        columnPathJson = "{}",
        entriesCount = -1L,
        durationSumNanos = java.math.BigDecimal.ZERO,
        durationAvgNanos = None
      )
      WorkUnitPivotQueryRepositoryImpl.decodeAggregateRow(emptyRequest, negativeCount) must throwA[IllegalStateException]
    }

    "reject duplicate aggregate coordinates" in {
      import WorkUnitPivotQueryRepositoryImpl.RawAggregateRow

      val emptyRequest = request(rows = Seq.empty, columns = Seq.empty)
      val raw = RawAggregateRow(
        rowPathJson = "{}",
        columnPathJson = "{}",
        entriesCount = 1L,
        durationSumNanos = java.math.BigDecimal.ZERO,
        durationAvgNanos = None
      )
      WorkUnitPivotQueryRepositoryImpl.decodeAggregateRows(emptyRequest, Seq(raw, raw)) must throwA[IllegalStateException]
    }

    "validate facet indexes, SQL nullability, typed pairings, and deduplicate by typed equality" in {
      import WorkUnitPivotQueryRepositoryImpl.RawFilterMember

      val userId = UUID.fromString("10000000-0000-4000-8000-000000000001")
      val facetRequest = request(rows = Seq.empty, columns = Seq.empty)
        .copy(filters = Seq(Filter(WorkUnitPivotTableKey.UserKey, None), Filter(WorkUnitPivotTableKey.ClientKey, None)))
      WorkUnitPivotQueryRepositoryImpl.decodeFilterMembers(
        facetRequest,
        Seq(RawFilterMember(0, Some(s"\"$userId\"")), RawFilterMember(0, Some(s"\"$userId\"")), RawFilterMember(1, None))
      ) shouldEqual Map(WorkUnitPivotTableKey.UserKey -> Seq(FieldValue.Uuid(userId)), WorkUnitPivotTableKey.ClientKey -> Seq(FieldValue.Null))
      WorkUnitPivotQueryRepositoryImpl.decodeFilterMembers(facetRequest, Seq(RawFilterMember(-1, Some("null")))) must throwA[IllegalStateException]
      WorkUnitPivotQueryRepositoryImpl.decodeFilterMembers(facetRequest, Seq(RawFilterMember(2, Some("null")))) must throwA[IllegalStateException]
      WorkUnitPivotQueryRepositoryImpl.decodeFilterMembers(facetRequest, Seq(RawFilterMember(0, None))) must throwA[IllegalStateException]
      WorkUnitPivotQueryRepositoryImpl.decodeFilterMembers(facetRequest, Seq(RawFilterMember(0, Some("2026")))) must throwA[IllegalStateException]
      WorkUnitPivotQueryRepositoryImpl.decodeFilterMembers(facetRequest, Seq(RawFilterMember(0, Some("{")))) must throwA[IllegalStateException]
    }

    "deduplicate high-cardinality facet members in first-seen order through the linear helper" in {
      val unique = (0 until 20000).map(FieldValue.Year)
      val values = unique.reverse ++ unique ++ unique.take(1000) ++ unique.reverse.take(1000)

      WorkUnitPivotQueryRepositoryImpl.distinctPreservingFirst(values) shouldEqual unique.reverse
    }
  }

  private val globalFilters = GlobalFilters(
    dateFrom = WorkUnitDate(LocalDate.of(2026, 1, 1)),
    dateTo = Some(WorkUnitDate(LocalDate.of(2026, 12, 31))),
    projectIds = Seq.empty,
    userIds = Seq.empty,
    clientIds = Seq.empty
  )

  private val representativeGlobalFilters = GlobalFilters(
    dateFrom = WorkUnitDate(LocalDate.of(2025, 1, 1)),
    dateTo = Some(WorkUnitDate(LocalDate.of(2025, 12, 31))),
    projectIds = Seq.empty,
    userIds = Seq.empty,
    clientIds = Seq.empty
  )

  private def request(
    rows: Seq[WorkUnitPivotTableKey],
    columns: Seq[WorkUnitPivotTableKey],
    grandTotals: Boolean = true
  ): Request =
    Request(
      rows = rows,
      columns = columns,
      values = Seq(WorkUnitPivotTableValue.EntriesCount),
      filters = Seq.empty,
      options = Options(rowSubtotals = true, columnSubtotals = true, grandTotals = grandTotals)
    )

  private def worklog(date: LocalDate, duration: Duration): WorkUnit =
    workUnitGenerator.generate.copy(
      date = WorkUnitDate(date),
      duration = WorkUnitDuration(duration),
      entryType = WorkUnitEntryType.Worklog,
      state = WorkUnitState.Active
    )

  private def note(date: LocalDate): WorkUnit =
    workUnitGenerator.generate.copy(
      date = WorkUnitDate(date),
      duration = WorkUnitDuration(Duration.ZERO),
      entryType = WorkUnitEntryType.Note,
      state = WorkUnitState.Active
    )

  private def canonicalRequest(options: Options): Request =
    Request(
      rows = Seq(WorkUnitPivotTableKey.UserKey, WorkUnitPivotTableKey.ProjectKey),
      columns = Seq(WorkUnitPivotTableKey.MonthYearKey),
      values = Seq(WorkUnitPivotTableValue.EntriesCount, WorkUnitPivotTableValue.DurationSum, WorkUnitPivotTableValue.DurationAvg),
      filters = Seq.empty,
      options = options
    )

  private def canonicalCoordinates(
    userPath: Seq[PathSegment],
    projectAPath: Seq[PathSegment],
    projectBPath: Seq[PathSegment],
    julyPath: Seq[PathSegment],
    augustPath: Seq[PathSegment]
  ): Set[(Seq[PathSegment], Seq[PathSegment])] = {
    val emptyPath = Seq.empty[PathSegment]
    Set(
      projectAPath -> julyPath,
      projectAPath -> augustPath,
      projectAPath -> emptyPath,
      projectBPath -> julyPath,
      projectBPath -> emptyPath,
      userPath     -> julyPath,
      userPath     -> augustPath,
      userPath     -> emptyPath,
      emptyPath    -> julyPath,
      emptyPath    -> augustPath,
      emptyPath    -> emptyPath
    )
  }

  private def metrics(row: AggregateRow): (Long, BigInt, Option[BigInt]) =
    (row.entriesCount, row.durationSumNanos, row.durationAvgNanos)

  private def hours(value: Double): BigInt = BigInt((value * Duration.ofHours(1).toNanos).toLong)

  private def representativeWorkUnits(
    organizationId: OrganizationId,
    userIds: Seq[UserId],
    projectIds: Seq[ProjectId],
    template: WorkUnit
  ): Seq[WorkUnit] = {
    val active = (0 until 600).flatMap { index =>
      (0 until 2).map { duplicateIndex =>
        val entryType = if (index % 2 == 0) WorkUnitEntryType.Worklog else WorkUnitEntryType.Note
        template.copy(
          id = WorkUnitId(new UUID(0x7000000000000000L, index.toLong * 2L + duplicateIndex.toLong + 1L)),
          organizationId = organizationId,
          userId = userIds(index % userIds.size),
          projectId = projectIds((index / userIds.size) % projectIds.size),
          date = WorkUnitDate(LocalDate.of(2025, index % 12 + 1, index % 28 + 1)),
          duration = WorkUnitDuration(if (entryType == WorkUnitEntryType.Worklog) Duration.ofMinutes((index % 4 + 1) * 15L) else Duration.ZERO),
          entryType = entryType,
          state = WorkUnitState.Active,
          archivedAt = None
        )
      }
    }
    val archived = (0 until 20).map { index =>
      active(index).copy(
        id = WorkUnitId(new UUID(0x7100000000000000L, index.toLong + 1L)),
        archivedAt = Some(WorkUnitArchivedAt(Instant.parse("2025-12-31T23:59:59Z")))
      )
    }
    active ++ archived
  }
}
