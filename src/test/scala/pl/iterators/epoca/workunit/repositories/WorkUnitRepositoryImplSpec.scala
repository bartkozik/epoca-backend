package pl.iterators.epoca.workunit.repositories

import org.postgresql.util.PSQLException
import org.scalacheck.Arbitrary
import pl.iterators.epoca.client.repositories.fixtures.ClientFixture
import pl.iterators.epoca.oauth.repositories.fixtures.ServiceAccountFixture
import pl.iterators.epoca.organization.repositories.fixtures.OrganizationFixture
import pl.iterators.epoca.project.repositories.fixtures.ProjectFixture
import pl.iterators.epoca.project.repositories.{ProjectRepository, ProjectRepositoryImpl}
import pl.iterators.epoca.testsupport.generators.Generator
import pl.iterators.epoca.user.repositories.fixtures.UserFixture
import pl.iterators.epoca.utils.PgRepositorySpec
import pl.iterators.epoca.utils.db.PageSizeAndNumber
import pl.iterators.epoca.workunit.domain.WorkUnitDomain._
import pl.iterators.epoca.workunit.domain.{WorkUnit, WorkUnitOverrideState}
import pl.iterators.epoca.workunit.repositories.WorkUnitRepository.{WorkUnitFilters, WorkUnitWithClientId}
import pl.iterators.epoca.workunit.repositories.fixtures.WorkUnitFixture
import pl.iterators.epoca.workunit.services.WorkUnitUpdateService.WorkUnitUpdateRequest
import slick.dbio.{Effect, NoStream}
import slick.sql.SqlAction

import java.sql.DriverManager
import java.time.{Duration, Instant}
import java.util.UUID
import scala.util.{Failure, Success}

class WorkUnitRepositoryImplSpec
    extends PgRepositorySpec
    with OrganizationFixture
    with UserFixture
    with ProjectFixture
    with ClientFixture
    with WorkUnitFixture
    with ServiceAccountFixture {
  private val rep: WorkUnitRepository       = new WorkUnitRepositoryImpl()
  private val projectRep: ProjectRepository = new ProjectRepositoryImpl()

  private val rawWorkUnitGenerator: Generator[WorkUnit] = allGenerators[WorkUnit].normal
  val workUnitGenerator: Generator[WorkUnit] = new Generator[WorkUnit] {
    override val ArbT: Arbitrary[WorkUnit] = Arbitrary(
      rawWorkUnitGenerator.ArbT.arbitrary.map(_.copy(entryType = WorkUnitEntryType.Worklog, duration = WorkUnitDuration(Duration.ofMinutes(30))))
    )
  }

  "human reporting reads" should {
    "exclude service-account work units before direct reads, count, paging, and client enrichment" in withDatabase {
      for {
        human           <- withUser()
        organization    <- withOrganization()
        serviceAccount  <- withLegalServiceAccount(organization, human)
        client          <- withClient(organization)
        project         <- withProject(organization, client)
        humanWorkUnit   <- withWorkUnit(organization, human, project)
        serviceWorkUnit <- withWorkUnit(organization, serviceAccount.user, project)
        ids = Some(Seq(humanWorkUnit.id, serviceWorkUnit.id))
        direct     <- rep.find(WorkUnitFilters(id = Some(serviceWorkUnit.id), includeArchived = true))
        listed     <- rep.list(WorkUnitFilters(ids = ids, includeArchived = true))
        count      <- rep.count(WorkUnitFilters(ids = ids, includeArchived = true))
        firstPage  <- rep.list(WorkUnitFilters(ids = ids, includeArchived = true, pageSizeAndNumber = Some(PageSizeAndNumber(1, 1))))
        secondPage <- rep.list(WorkUnitFilters(ids = ids, includeArchived = true, pageSizeAndNumber = Some(PageSizeAndNumber(1, 2))))
        withClient <- rep.listWithClientId(WorkUnitFilters(ids = ids, includeArchived = true))
      } yield {
        direct should beNone
        listed shouldEqual Seq(humanWorkUnit)
        count shouldEqual 1
        firstPage shouldEqual Seq(humanWorkUnit)
        secondPage should beEmpty
        withClient shouldEqual Seq(WorkUnitWithClientId(humanWorkUnit, client.id))
      }
    }
  }

  "CRUD" should {
    "work as follows" in withDatabase {
      for {
        org    <- withOrganization()
        usr    <- withUser()
        client <- withClient(org)
        proj   <- withProject(org, client)

        r1 <- withWorkUnit(org, usr, proj)
        r2 <- withWorkUnit(org, usr, proj)
        r3 <- withWorkUnit(org, usr, proj)

        fixtureIds = Seq(r1.id, r2.id, r3.id)

        f1 <- rep.find(WorkUnitFilters(id = Some(r1.id)))
        f2 <- rep.find(WorkUnitFilters(id = Some(r2.id)))
        f3 <- rep.find(WorkUnitFilters(id = Some(r3.id)))

        l0 <- rep.list(WorkUnitFilters(ids = Some(fixtureIds)))
        l1 <- rep.list(WorkUnitFilters(id = Some(r1.id)))
        l2 <- rep.list(WorkUnitFilters(id = Some(r2.id)))
        l3 <- rep.list(WorkUnitFilters(id = Some(r3.id)))

        ru1 = r1.copy(date = WorkUnitDate(r1.date.plusDays(1)), revision = 1)
        ru2 = r2.copy(date = WorkUnitDate(r2.date.plusDays(1)), revision = 1)

        _ <- rep.update(ru1)
        _ <- rep.update(ru2)

        lu0 <- rep.list(WorkUnitFilters(ids = Some(fixtureIds)))
        lu1 <- rep.list(WorkUnitFilters(id = Some(r1.id)))
        lu2 <- rep.list(WorkUnitFilters(id = Some(r2.id)))
        lu3 <- rep.list(WorkUnitFilters(id = Some(r3.id)))

        now = Instant.ofEpochMilli(System.currentTimeMillis())
        rd2 = ru2.copy(archivedAt = Some(WorkUnitArchivedAt(now)))
        rd3 = r3.copy(archivedAt = Some(WorkUnitArchivedAt(now)))

        _ <- rep.update(rd2)
        _ <- rep.update(rd3)

        ld0 <- rep.list(WorkUnitFilters(ids = Some(fixtureIds)))
        ld1 <- rep.list(WorkUnitFilters(id = Some(r1.id)))
        ld2 <- rep.list(WorkUnitFilters(id = Some(r2.id)))
        ld3 <- rep.list(WorkUnitFilters(id = Some(r3.id)))
      } yield {
        f1 shouldEqual Some(r1)
        f2 shouldEqual Some(r2)
        f3 shouldEqual Some(r3)

        l0 should containTheSameElementsAs(Seq(r1, r2, r3))
        l1 should containTheSameElementsAs(Seq(r1))
        l2 should containTheSameElementsAs(Seq(r2))
        l3 should containTheSameElementsAs(Seq(r3))

        lu0 should containTheSameElementsAs(Seq(ru1, ru2, r3))
        lu1 should containTheSameElementsAs(Seq(ru1))
        lu2 should containTheSameElementsAs(Seq(ru2))
        lu3 should containTheSameElementsAs(Seq(r3))

        ld0 should containTheSameElementsAs(Seq(ru1))
        ld1 should containTheSameElementsAs(Seq(ru1))
        ld2 should containTheSameElementsAs(Nil)
        ld3 should containTheSameElementsAs(Nil)
      }
    }
  }

  "listWithClientId" should {
    "derive client IDs from current projects when legacy work unit clients differ" in withDatabase {
      for {
        org           <- withOrganization()
        usr           <- withUser()
        firstClient   <- withClient(org)
        secondClient  <- withClient(org)
        firstProject  <- withProject(org, firstClient)
        secondProject <- withProject(org, secondClient)

        first  <- withWorkUnit(org, usr, firstProject)
        second <- withWorkUnit(org, usr, secondProject)

        currentFirstClient <- withClient(org)
        currentFirstProject = firstProject.copy(clientId = currentFirstClient.id)
        updatedFirstProject <- projectRep.update(currentFirstProject)

        result <- rep.listWithClientId(WorkUnitFilters(ids = Some(Seq(first.id, second.id))))
      } yield {
        updatedFirstProject shouldEqual Some(currentFirstProject)
        result.find(_.workUnit.id == first.id).map(_.clientId) shouldEqual Some(currentFirstClient.id)
        result should containTheSameElementsAs(Seq(WorkUnitWithClientId(first, currentFirstClient.id), WorkUnitWithClientId(second, secondClient.id)))
      }
    }
  }

  "entry type persistence" should {
    "round-trip Worklogs and Notes through insert, get, update, count, and client listing" in withDatabase {
      for {
        org    <- withOrganization()
        usr    <- withUser()
        client <- withClient(org)
        proj   <- withProject(org, client)

        base = workUnitGenerator.generate.copy(
                 organizationId = org.id,
                 userId = usr.id,
                 projectId = proj.id,
                 state = WorkUnitState.Active,
                 archivedAt = None
               )
        worklog = base.copy(entryType = WorkUnitEntryType.Worklog, duration = WorkUnitDuration(Duration.ofMinutes(30)))
        note    = base.copy(id = WorkUnitId(UUID.randomUUID()), entryType = WorkUnitEntryType.Note, duration = WorkUnitDuration(Duration.ZERO))

        insertedWorklog  <- withWorkUnit(org, usr, proj, worklog)
        insertedNote     <- withWorkUnit(org, usr, proj, note)
        foundWorklog     <- rep.find(WorkUnitFilters(id = Some(worklog.id)))
        foundNote        <- rep.find(WorkUnitFilters(id = Some(note.id)))
        count            <- rep.count(WorkUnitFilters(ids = Some(Seq(worklog.id, note.id))))
        listed           <- rep.list(WorkUnitFilters(ids = Some(Seq(worklog.id, note.id))))
        listedWithClient <- rep.listWithClientId(WorkUnitFilters(ids = Some(Seq(worklog.id, note.id))))

        updatedNote = note.updated(
                        WorkUnitUpdateRequest(
                          projectId = None,
                          duration = None,
                          description = Some(WorkUnitDescription("updated note")),
                          taskId = None,
                          date = None,
                          shouldFinalize = false
                        ),
                        Instant.parse("2026-07-29T10:00:00Z")
                      )
        updateResult <- rep.update(updatedNote)
        foundUpdated <- rep.find(WorkUnitFilters(id = Some(note.id)))
      } yield {
        insertedWorklog shouldEqual worklog
        insertedNote shouldEqual note
        foundWorklog shouldEqual Some(worklog)
        foundNote shouldEqual Some(note)
        count shouldEqual 2
        listed.map(unit => unit.id -> unit.entryType).toMap shouldEqual Map(
          worklog.id -> WorkUnitEntryType.Worklog,
          note.id    -> WorkUnitEntryType.Note
        )
        listedWithClient.map(result => result.workUnit.id -> (result.workUnit.entryType, result.clientId)).toMap shouldEqual Map(
          worklog.id -> (WorkUnitEntryType.Worklog, client.id),
          note.id    -> (WorkUnitEntryType.Note, client.id)
        )
        updateResult shouldEqual Some(updatedNote)
        foundUpdated shouldEqual Some(updatedNote)
        foundUpdated.map(_.entryType) shouldEqual Some(WorkUnitEntryType.Note)
      }
    }

    "preserve the duration of an explicitly supplied Worklog fixture" in withDatabase {
      for {
        org    <- withOrganization()
        usr    <- withUser()
        client <- withClient(org)
        proj   <- withProject(org, client)

        worklog = workUnitGenerator.generate.copy(
                    organizationId = org.id,
                    userId = usr.id,
                    projectId = proj.id,
                    entryType = WorkUnitEntryType.Worklog,
                    duration = WorkUnitDuration(Duration.ofSeconds(1)),
                    state = WorkUnitState.Active,
                    archivedAt = None
                  )
        inserted <- withWorkUnit(org, usr, proj, worklog)
        found    <- rep.find(WorkUnitFilters(id = Some(worklog.id)))
      } yield {
        inserted shouldEqual worklog
        found shouldEqual Some(worklog)
      }
    }

    "send an explicitly supplied invalid Note duration to the database constraint" in {
      val result = withDatabase {
        for {
          org    <- withOrganization()
          usr    <- withUser()
          client <- withClient(org)
          proj   <- withProject(org, client)

          note = workUnitGenerator.generate.copy(
                   organizationId = org.id,
                   userId = usr.id,
                   projectId = proj.id,
                   entryType = WorkUnitEntryType.Note,
                   duration = WorkUnitDuration(Duration.ofSeconds(1)),
                   state = WorkUnitState.Active,
                   archivedAt = None
                 )
          inserted <- withWorkUnit(org, usr, proj, note).asTry
        } yield inserted
      }

      result match {
        case Failure(error: PSQLException) =>
          error.getSQLState shouldEqual "23514"
          Option(error.getServerErrorMessage).map(_.getConstraint) shouldEqual Some("work_unit_entry_type_duration_check")
        case Failure(error) => throw new AssertionError(s"Expected PostgreSQL check violation, got ${error.getClass.getName}", error)
        case Success(_)     => throw new AssertionError("Expected PostgreSQL to reject Note with PT1S")
      }
    }
  }

  "filter by provided params" should {
    "use the production effective-project expression index for organization-scoped effective filtering" in {
      val organization = pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId(UUID.randomUUID())
      val project      = pl.iterators.epoca.project.domain.ProjectDomain.ProjectId(UUID.randomUUID())
      val query =
        statements(rep.list(WorkUnitFilters(organizationId = Some(organization), projectIds = Some(Seq(project)), includeArchived = true))).head
      val (plan, index)   = explainWithRepresentativeData(query, organization, project)
      val indexConditions = plan.linesIterator.filter(_.trim.startsWith("Index Cond:")).mkString("\n")
      (plan must contain(index)) and
        (indexConditions must contain("COALESCE(override_project_id, project_id)")) and
        (indexConditions must contain(project.toString))
    }
    "id" in withDatabase {
      for {
        org    <- withOrganization()
        usr    <- withUser()
        client <- withClient(org)
        proj   <- withProject(org, client)

        r1 <- withWorkUnit(org, usr, proj)
        r2 <- withWorkUnit(org, usr, proj)
        r3 <- withWorkUnit(org, usr, proj)

        l1 <- rep.list(WorkUnitFilters(id = Some(r1.id)))
        l2 <- rep.list(WorkUnitFilters(id = Some(r2.id)))
        l3 <- rep.list(WorkUnitFilters(id = Some(r3.id)))
      } yield {
        l1 should containTheSameElementsAs(Seq(r1))
        l2 should containTheSameElementsAs(Seq(r2))
        l3 should containTheSameElementsAs(Seq(r3))
      }
    }
    "organizationId" in withDatabase {
      for {
        org1   <- withOrganization()
        org2   <- withOrganization()
        org3   <- withOrganization()
        usr    <- withUser()
        client <- withClient(org1)
        proj   <- withProject(org1, client)

        r1 <- withWorkUnit(org1, usr, proj)
        r2 <- withWorkUnit(org2, usr, proj)
        r3 <- withWorkUnit(org3, usr, proj)

        l1 <- rep.list(WorkUnitFilters(organizationId = Some(r1.organizationId)))
        l2 <- rep.list(WorkUnitFilters(organizationId = Some(r2.organizationId)))
        l3 <- rep.list(WorkUnitFilters(organizationId = Some(r3.organizationId)))
      } yield {
        l1 should containTheSameElementsAs(Seq(r1))
        l2 should containTheSameElementsAs(Seq(r2))
        l3 should containTheSameElementsAs(Seq(r3))
      }

    }
    "userIds" in withDatabase {
      for {
        org    <- withOrganization()
        usr1   <- withUser()
        usr2   <- withUser()
        usr3   <- withUser()
        client <- withClient(org)
        proj   <- withProject(org, client)

        r1 <- withWorkUnit(org, usr1, proj)
        r2 <- withWorkUnit(org, usr2, proj)
        r3 <- withWorkUnit(org, usr3, proj)

        l1 <- rep.list(WorkUnitFilters(userIds = Some(Seq(r1.userId))))
        l2 <- rep.list(WorkUnitFilters(userIds = Some(Seq(r2.userId))))
        l3 <- rep.list(WorkUnitFilters(userIds = Some(Seq(r3.userId))))
        l4 <- rep.list(WorkUnitFilters(userIds = Some(Seq(r1.userId, r3.userId))))
      } yield {
        l1 should containTheSameElementsAs(Seq(r1))
        l2 should containTheSameElementsAs(Seq(r2))
        l3 should containTheSameElementsAs(Seq(r3))
        l4 should containTheSameElementsAs(Seq(r1, r3))
      }
    }
    "projectIds" in withDatabase {
      for {
        org    <- withOrganization()
        usr    <- withUser()
        client <- withClient(org)
        proj1  <- withProject(org, client)
        proj2  <- withProject(org, client)
        proj3  <- withProject(org, client)

        r1 <- withWorkUnit(org, usr, proj1)
        r2 <- withWorkUnit(org, usr, proj2)
        r3 <- withWorkUnit(org, usr, proj3)

        l1 <- rep.list(WorkUnitFilters(projectIds = Some(Seq(proj1.id))))
        l2 <- rep.list(WorkUnitFilters(projectIds = Some(Seq(proj2.id))))
        l3 <- rep.list(WorkUnitFilters(projectIds = Some(Seq(proj3.id))))
        l4 <- rep.list(WorkUnitFilters(projectIds = Some(Seq(proj1.id, proj3.id))))
      } yield {
        l1 should containTheSameElementsAs(Seq(r1))
        l2 should containTheSameElementsAs(Seq(r2))
        l3 should containTheSameElementsAs(Seq(r3))
        l4 should containTheSameElementsAs(Seq(r1, r3))
      }
    }
    "clientIds" in withDatabase {
      for {
        org     <- withOrganization()
        usr     <- withUser()
        client1 <- withClient(org)
        client2 <- withClient(org)
        client3 <- withClient(org)
        proj1   <- withProject(org, client1)
        proj2   <- withProject(org, client2)
        proj3   <- withProject(org, client3)

        r1 <- withWorkUnit(org, usr, proj1)
        r2 <- withWorkUnit(org, usr, proj2)
        r3 <- withWorkUnit(org, usr, proj3)

        l1 <- rep.list(WorkUnitFilters(clientIds = Some(Seq(client1.id))))
        l2 <- rep.list(WorkUnitFilters(clientIds = Some(Seq(client2.id))))
        l3 <- rep.list(WorkUnitFilters(clientIds = Some(Seq(client3.id))))
        l4 <- rep.list(WorkUnitFilters(clientIds = Some(Seq(client1.id, client3.id))))
      } yield {
        l1 should containTheSameElementsAs(Seq(r1))
        l2 should containTheSameElementsAs(Seq(r2))
        l3 should containTheSameElementsAs(Seq(r3))
        l4 should containTheSameElementsAs(Seq(r1, r3))
      }
    }
    "filter project and client by effective values and keep list/count aligned" in withDatabase {
      for {
        org                <- withOrganization()
        usr                <- withUser()
        sourceClient       <- withClient(org)
        otherClient        <- withClient(org)
        pmClient           <- withClient(org)
        otherPmClient      <- withClient(org)
        sourceProject      <- withProject(org, sourceClient)
        otherSourceProject <- withProject(org, otherClient)
        pmProject          <- withProject(org, pmClient)
        otherPmProject     <- withProject(org, otherPmClient)

        first        <- withWorkUnit(org, usr, sourceProject)
        second       <- withWorkUnit(org, usr, otherSourceProject)
        third        <- withWorkUnit(org, usr, sourceProject)
        unclassified <- withWorkUnit(org, usr, sourceProject)

        _ <- rep.update(classification(first, pmProject.id, usr.id, 1L))
        _ <- rep.update(classification(second, pmProject.id, usr.id, 1L))
        _ <- rep.update(classification(third, otherPmProject.id, usr.id, 1L))

        ids = Some(Seq(first.id, second.id, third.id, unclassified.id))
        bySourceProject     <- rep.list(WorkUnitFilters(ids = ids, projectIds = Some(Seq(sourceProject.id))))
        bySourceClient      <- rep.list(WorkUnitFilters(ids = ids, clientIds = Some(Seq(sourceClient.id))))
        byPmProject         <- rep.list(WorkUnitFilters(ids = ids, projectIds = Some(Seq(pmProject.id))))
        byPmProjectCount    <- rep.count(WorkUnitFilters(ids = ids, projectIds = Some(Seq(pmProject.id))))
        byPmClient          <- rep.list(WorkUnitFilters(ids = ids, clientIds = Some(Seq(pmClient.id))))
        byPmClientCount     <- rep.count(WorkUnitFilters(ids = ids, clientIds = Some(Seq(pmClient.id))))
        byBoth              <- rep.list(WorkUnitFilters(ids = ids, projectIds = Some(Seq(pmProject.id)), clientIds = Some(Seq(pmClient.id))))
        byBothCount         <- rep.count(WorkUnitFilters(ids = ids, projectIds = Some(Seq(pmProject.id)), clientIds = Some(Seq(pmClient.id))))
        byContradictoryBoth <- rep.list(WorkUnitFilters(ids = ids, projectIds = Some(Seq(pmProject.id)), clientIds = Some(Seq(otherPmClient.id))))
        emptyPmFilter       <- rep.list(WorkUnitFilters(ids = ids, projectIds = Some(Seq.empty)))
        emptyPmFilterCount  <- rep.count(WorkUnitFilters(ids = ids, projectIds = Some(Seq.empty)))
      } yield {
        bySourceProject shouldEqual Seq(unclassified)
        bySourceClient shouldEqual Seq(unclassified)
        byPmProject.map(_.id) should containTheSameElementsAs(Seq(first.id, second.id))
        byPmProjectCount shouldEqual byPmProject.size
        byPmClient.map(_.id) should containTheSameElementsAs(Seq(first.id, second.id))
        byPmClientCount shouldEqual byPmClient.size
        byBoth.map(_.id) should containTheSameElementsAs(Seq(first.id, second.id))
        byBothCount shouldEqual byBoth.size
        byContradictoryBoth should beEmpty
        emptyPmFilter should beEmpty
        emptyPmFilterCount shouldEqual 0
      }
    }
    "apply OAuth report policy to both source and effective PM projects before paging and counting" in withDatabase {
      for {
        org           <- withOrganization()
        usr           <- withUser()
        client        <- withClient(org)
        allowedSource <- withProject(org, client)
        deniedSource  <- withProject(org, client)
        allowedPm     <- withProject(org, client)
        deniedPm      <- withProject(org, client)
        visible       <- withWorkUnit(org, usr, allowedSource)
        crossPm       <- withWorkUnit(org, usr, allowedSource)
        crossSource   <- withWorkUnit(org, usr, deniedSource)
        unclassified  <- withWorkUnit(org, usr, allowedSource)
        _             <- rep.update(classification(visible, allowedPm.id, usr.id, 1L))
        _             <- rep.update(classification(crossPm, deniedPm.id, usr.id, 1L))
        _             <- rep.update(classification(crossSource, allowedPm.id, usr.id, 1L))
        ids     = Some(Seq(visible.id, crossPm.id, crossSource.id, unclassified.id))
        filters = WorkUnitFilters(ids = ids, reportAuthorizedProjectIds = Some(Seq(allowedSource.id, allowedPm.id)))
        listed <- rep.list(filters)
        count  <- rep.count(filters)
        page   <- rep.list(filters.copy(pageSizeAndNumber = Some(PageSizeAndNumber(1, 2))))
      } yield {
        listed.map(_.id) should containTheSameElementsAs(Seq(visible.id, unclassified.id))
        count shouldEqual 2
        page must haveSize(1)
        page.head.id must beOneOf(visible.id, unclassified.id)
      }
    }
    "keep effective-filter list/count aligned for inactive and included archived worklogs" in withDatabase {
      for {
        org           <- withOrganization()
        usr           <- withUser()
        sourceClient  <- withClient(org)
        pmClient      <- withClient(org)
        sourceProject <- withProject(org, sourceClient)
        pmProject     <- withProject(org, pmClient)
        inactive      <- withWorkUnit(org, usr, sourceProject, workUnitGenerator.generate.copy(state = WorkUnitState.Inactive))
        active        <- withWorkUnit(org, usr, sourceProject)
        archivedAt = WorkUnitArchivedAt(Instant.parse("2026-08-05T13:00:00Z"))
        archived   = active.copy(state = WorkUnitState.Archived, archivedAt = Some(archivedAt))
        _ <- rep.update(archived)
        _ <- rep.update(classification(inactive, pmProject.id, usr.id, 1L))
        _ <- rep.update(classification(archived, pmProject.id, usr.id, 2L))
        filters = WorkUnitFilters(
                    ids = Some(Seq(inactive.id, archived.id)),
                    states = Some(Seq(WorkUnitState.Inactive, WorkUnitState.Archived)),
                    includeArchived = true,
                    projectIds = Some(Seq(pmProject.id)),
                    clientIds = Some(Seq(pmClient.id))
                  )
        filtered             <- rep.list(filters)
        filteredCount        <- rep.count(filters)
        withoutArchived      <- rep.list(filters.copy(includeArchived = false))
        withoutArchivedCount <- rep.count(filters.copy(includeArchived = false))
      } yield {
        filtered.map(_.id) should containTheSameElementsAs(Seq(inactive.id, archived.id))
        filteredCount shouldEqual 2
        withoutArchived.map(_.id) shouldEqual Seq(inactive.id)
        withoutArchivedCount shouldEqual 1
      }
    }
    "states" in withDatabase {
      val obj1 = workUnitGenerator.generate.copy(state = WorkUnitState.Active)
      val obj2 = workUnitGenerator.generate.copy(state = WorkUnitState.Draft)
      val obj3 = workUnitGenerator.generate.copy(state = WorkUnitState.Archived)

      for {
        org    <- withOrganization()
        usr    <- withUser()
        client <- withClient(org)
        proj   <- withProject(org, client)

        r1 <- withWorkUnit(org, usr, proj, obj1)
        r2 <- withWorkUnit(org, usr, proj, obj2)
        r3 <- withWorkUnit(org, usr, proj, obj3)

        fixtureIds = Seq(r1.id, r2.id, r3.id)

        l1 <- rep.list(WorkUnitFilters(ids = Some(fixtureIds), states = Some(Seq(WorkUnitState.Active))))
        l2 <- rep.list(WorkUnitFilters(ids = Some(fixtureIds), states = Some(Seq(WorkUnitState.Draft))))
        l3 <- rep.list(WorkUnitFilters(ids = Some(fixtureIds), states = Some(Seq(WorkUnitState.Archived))))
        l4 <- rep.list(WorkUnitFilters(ids = Some(fixtureIds), states = Some(Seq(WorkUnitState.Active, WorkUnitState.Draft))))
      } yield {
        l1 should containTheSameElementsAs(Seq(r1))
        l2 should containTheSameElementsAs(Seq(r2))
        l3 should containTheSameElementsAs(Seq(r3))
        l4 should containTheSameElementsAs(Seq(r1, r2))
      }
    }
    "dateFrom" in withDatabase {
      val obj1 = workUnitGenerator.generate
      val obj2 = workUnitGenerator.generate.copy(date = WorkUnitDate(obj1.date.plusDays(1)))
      val obj3 = workUnitGenerator.generate.copy(date = WorkUnitDate(obj1.date.plusDays(2)))
      for {
        org    <- withOrganization()
        usr    <- withUser()
        client <- withClient(org)
        proj   <- withProject(org, client)

        r1 <- withWorkUnit(org, usr, proj, obj1)
        r2 <- withWorkUnit(org, usr, proj, obj2)
        r3 <- withWorkUnit(org, usr, proj, obj3)

        fixtureIds = Seq(r1.id, r2.id, r3.id)

        l1 <- rep.list(WorkUnitFilters(ids = Some(fixtureIds), dateFrom = Some(r1.date)))
        l2 <- rep.list(WorkUnitFilters(ids = Some(fixtureIds), dateFrom = Some(r2.date)))
        l3 <- rep.list(WorkUnitFilters(ids = Some(fixtureIds), dateFrom = Some(r3.date)))
      } yield {
        l1 should containTheSameElementsAs(Seq(r1, r2, r3))
        l2 should containTheSameElementsAs(Seq(r3, r2))
        l3 should containTheSameElementsAs(Seq(r3))
      }
    }
    "dateTo" in withDatabase {
      val obj1 = workUnitGenerator.generate
      val obj2 = workUnitGenerator.generate.copy(date = WorkUnitDate(obj1.date.plusDays(1)))
      val obj3 = workUnitGenerator.generate.copy(date = WorkUnitDate(obj1.date.plusDays(2)))
      for {
        org    <- withOrganization()
        usr    <- withUser()
        client <- withClient(org)
        proj   <- withProject(org, client)

        r1 <- withWorkUnit(org, usr, proj, obj1)
        r2 <- withWorkUnit(org, usr, proj, obj2)
        r3 <- withWorkUnit(org, usr, proj, obj3)

        fixtureIds = Seq(r1.id, r2.id, r3.id)

        l1 <- rep.list(WorkUnitFilters(ids = Some(fixtureIds), dateTo = Some(r1.date)))
        l2 <- rep.list(WorkUnitFilters(ids = Some(fixtureIds), dateTo = Some(r2.date)))
        l3 <- rep.list(WorkUnitFilters(ids = Some(fixtureIds), dateTo = Some(r3.date)))
      } yield {
        l3 should containTheSameElementsAs(Seq(r1, r2, r3))
        l2 should containTheSameElementsAs(Seq(r1, r2))
        l1 should containTheSameElementsAs(Seq(r1))
      }
    }
    "includeArchived" in withDatabase {
      for {
        org    <- withOrganization()
        usr    <- withUser()
        client <- withClient(org)
        proj   <- withProject(org, client)

        r1 <- withWorkUnit(org, usr, proj)
        r2 <- withWorkUnit(org, usr, proj)
        r3 <- withWorkUnit(org, usr, proj)
        fixtureIds = Seq(r1.id, r2.id, r3.id)
        now        = Instant.ofEpochMilli(System.currentTimeMillis())
        rd2        = r2.copy(archivedAt = Some(WorkUnitArchivedAt(now)), revision = 1)
        _ <- rep.update(rd2)

        l1 <- rep.list(WorkUnitFilters(ids = Some(fixtureIds), includeArchived = true))
        l2 <- rep.list(WorkUnitFilters(ids = Some(fixtureIds), includeArchived = false))
      } yield {
        l1 should containTheSameElementsAs(Seq(r1, rd2, r3))
        l2 should containTheSameElementsAs(Seq(r1, r3))
      }
    }
  }

  private def classification(
    workUnit: WorkUnit,
    projectId: pl.iterators.epoca.project.domain.ProjectDomain.ProjectId,
    actorId: pl.iterators.epoca.user.domain.UserDomain.UserId,
    version: Long
  ): WorkUnit =
    workUnit.copy(overrideState =
      WorkUnitOverrideState(
        projectId = Some(projectId),
        taskIdOverrideSet = true,
        taskId = Some(WorkUnitTaskId(s"OVERRIDE-$version")),
        version = version,
        lastChangedBy = Some(actorId),
        lastChangedAt = Some(Instant.parse("2026-08-05T12:00:00Z"))
      )
    )

  private def statements(action: slick.dbio.DBIO[Seq[WorkUnit]]): Iterable[String] =
    action.asInstanceOf[SqlAction[Seq[WorkUnit], NoStream, Effect.Read]].statements

  private def explainWithRepresentativeData(
    query: String,
    organizationId: UUID,
    targetProjectId: UUID
  ): (String, String) = {
    val connection = DriverManager.getConnection(
      sys.env.getOrElse("TEST_DB_URL", "epoca"),
      sys.env.getOrElse("TEST_DB_USER", "postgres"),
      sys.env.getOrElse("TEST_DB_PASSWORD", "postgres")
    )
    connection.setAutoCommit(false)
    try {
      val statement = connection.createStatement()
      try {
        def execute(sql: String): Unit = { val _ = statement.execute(sql) }
        val actorId                    = UUID.randomUUID()
        val sourceProjectId            = UUID.randomUUID()
        val sourceClientId             = UUID.randomUUID()
        execute("CREATE TEMPORARY TABLE \"user\" (LIKE public.\"user\" INCLUDING ALL) ON COMMIT DROP")
        execute("CREATE TEMPORARY TABLE work_unit (LIKE public.work_unit INCLUDING ALL) ON COMMIT DROP")
        execute(s"""INSERT INTO pg_temp."user" (id,first_name,last_name,is_super_admin,account_type,created_at,modified_at)
             |VALUES ('$actorId','Index','Fixture',false,'Human',now(),now())""".stripMargin)
        execute(s"""INSERT INTO pg_temp.work_unit
             |(id,organization_id,user_id,project_id,client_id,date,duration,description,task_id,created_at,modified_at,state,entry_type,
             | override_project_id,revision,override_version,override_last_changed_by,override_last_changed_at)
             |SELECT md5(n::text)::uuid,'$organizationId','$actorId','$sourceProjectId','$sourceClientId',DATE '2026-09-17',
             | interval '1 hour','Index fixture','SOURCE',now(),now(),'Active','Worklog',
             | CASE WHEN n = 1 THEN '$targetProjectId'::uuid END,
             | CASE WHEN n = 1 THEN 1 ELSE 0 END,CASE WHEN n = 1 THEN 1 ELSE 0 END,
             | CASE WHEN n = 1 THEN '$actorId'::uuid END,CASE WHEN n = 1 THEN now() END
             |FROM generate_series(1,10000) AS fixture(n)""".stripMargin)
        execute("ANALYZE pg_temp.\"user\"")
        execute("ANALYZE pg_temp.work_unit")
        val indexResult = statement.executeQuery("""SELECT c.relname FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
            |WHERE i.indrelid = 'pg_temp.work_unit'::regclass
            |AND pg_get_expr(i.indexprs, i.indrelid) = 'COALESCE(override_project_id, project_id)'""".stripMargin)
        val index =
          try {
            if (!indexResult.next()) throw new AssertionError("Production effective-project expression index was not copied")
            indexResult.getString(1)
          } finally indexResult.close()
        val explainQuery = s"EXPLAIN (COSTS OFF) $query"
        val result =
          try statement.executeQuery(explainQuery)
          catch {
            case error: org.postgresql.util.PSQLException =>
              throw new AssertionError(s"Could not explain generated repository SQL:\n$explainQuery", error)
          }
        try {
          val lines = Seq.newBuilder[String]
          while (result.next()) lines += result.getString(1)
          lines.result().mkString("\n") -> index
        } finally result.close()
      } finally statement.close()
    } finally
      try connection.rollback()
      finally connection.close()
  }
}
