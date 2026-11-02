package pl.iterators.epoca.project.repositories

import pl.iterators.epoca.client.domain.Client
import pl.iterators.epoca.client.repositories.fixtures.ClientFixture
import pl.iterators.epoca.organization.domain.Organization
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationUserRole
import pl.iterators.epoca.organization.repositories.fixtures.{OrganizationFixture, OrganizationUserFixture}
import pl.iterators.epoca.project.domain.ProjectDomain._
import pl.iterators.epoca.project.domain.{Project, ProjectUser}
import pl.iterators.epoca.project.repositories.ProjectRepository.ProjectFilters
import pl.iterators.epoca.project.repositories.fixtures.ProjectUserFixture
import pl.iterators.epoca.testsupport.generators.Generator
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.user.repositories.fixtures.UserFixture
import pl.iterators.epoca.utils.PgRepositorySpec
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import pl.iterators.epoca.utils.db.PageSizeAndNumber
import slick.util.AsyncExecutor

import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeoutException
import scala.concurrent.duration._
import scala.concurrent.{Await, Future, Promise}
import scala.util.control.NonFatal

class ProjectRepositoryImplSpec
    extends PgRepositorySpec
    with OrganizationFixture
    with OrganizationUserFixture
    with UserFixture
    with ClientFixture
    with ProjectUserFixture {

  sequential

  private val rep: ProjectRepository = new ProjectRepositoryImpl()

  val projectNameGenerator: Generator[ProjectName]         = allGenerators[ProjectName].maximal
  val organizationGenerator: Generator[Organization]       = allGenerators[Organization].maximal
  val clientGenerator: Generator[Client]                   = allGenerators[Client].maximal
  val projectUserGenerator: Generator[ProjectUser]         = allGenerators[ProjectUser].maximal
  val userGenerator: Generator[User]                       = allGenerators[User].maximal
  val rolesGenerator: Generator[Seq[OrganizationUserRole]] = allGenerators[Seq[OrganizationUserRole]].maximal

  "project relation lock" should {
    "hold a RowShareLock until the caller-owned transaction completes" in withRelationLockDatabases { (holderDb, observerDb) =>
      val acquired = Promise[Int]()
      val release  = Promise[Unit]()

      val holder = holderDb.run((for {
        pid <- sql"SELECT pg_catalog.pg_backend_pid()".as[Int].head
        _   <- rep.lockTableForReference()
        _   <- DBIO.successful { acquired.trySuccess(pid); () }
        _   <- DBIO.from(release.future)
      } yield ()).transactionally)

      runWithTeardown {
        val holderPid = await(acquired.future, "project relation lock holder PID")

        awaitDatabase(observerDb, projectRelationLocks(holderPid), "held project relation lock") shouldEqual Seq(("RowShareLock", true))
      } {
        Seq(
          () => { release.trySuccess(()); () },
          () => drain(holder, "project relation lock holder transaction"),
          () => {
            val holderPid     = await(acquired.future, "released project relation lock holder PID")
            val releasedLocks = awaitDatabase(observerDb, projectRelationLocks(holderPid), "released project relation lock")
            val _             = releasedLocks should beEmpty
            ()
          }
        )
      }
    }

    "propagate a holder transaction failure while draining" in {
      val expected = new IllegalStateException("relation lock holder failed")

      capture {
        drain(Future.failed[Unit](expected), "failed project relation lock holder transaction")
      } match {
        case Left(actual) => actual should beTheSameAs(expected)
        case Right(_)     => false should beTrue
      }
    }
  }

  "CRUD" should {
    "work as follows" in withDatabase {
      val org = organizationGenerator.generate
      val clt = clientGenerator.generate.copy(organizationId = org.id)
      for {
        _ <- withOrganization(org)
        _ <- withClient(org, clt)

        r1 <- rep.insert(generateProject(org, clt))
        r2 <- rep.insert(generateProject(org, clt))
        r3 <- rep.insert(generateProject(org, clt))
        f1 <- rep.find(ProjectFilters(id = Some(r1.id)))
        f2 <- rep.find(ProjectFilters(id = Some(r2.id)))
        f3 <- rep.find(ProjectFilters(id = Some(r3.id)))

        fixtureIds = Seq(r1.id, r2.id, r3.id)
        l0 <- rep.list(ProjectFilters(ids = Some(fixtureIds)))
        l1 <- rep.list(ProjectFilters(id = Some(r1.id)))
        l2 <- rep.list(ProjectFilters(id = Some(r2.id)))
        l3 <- rep.list(ProjectFilters(id = Some(r3.id)))

        ru1 = r1.copy(name = projectNameGenerator.generate)
        ru2 = r2.copy(name = projectNameGenerator.generate)

        _ <- rep.update(ru1)
        _ <- rep.update(ru2)

        lu0 <- rep.list(ProjectFilters(ids = Some(fixtureIds)))
        lu1 <- rep.list(ProjectFilters(id = Some(r1.id)))
        lu2 <- rep.list(ProjectFilters(id = Some(r2.id)))
        lu3 <- rep.list(ProjectFilters(id = Some(r3.id)))

        now = Instant.ofEpochMilli(System.currentTimeMillis())
        rd2 = r2.copy(archivedAt = Some(ProjectArchivedAt(now)))
        rd3 = r3.copy(archivedAt = Some(ProjectArchivedAt(now)))

        _ <- rep.update(rd2)
        _ <- rep.update(rd3)

        ld0 <- rep.list(ProjectFilters(ids = Some(fixtureIds)))
        ld1 <- rep.list(ProjectFilters(id = Some(r1.id)))
        ld2 <- rep.list(ProjectFilters(id = Some(r2.id)))
        ld3 <- rep.list(ProjectFilters(id = Some(r3.id)))

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

  "ordered row locking" should {
    "return projects by id regardless of insert order" in withDatabase {
      val org    = organizationGenerator.generate
      val clt    = clientGenerator.generate.copy(organizationId = org.id)
      val lowId  = ProjectId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
      val highId = ProjectId(UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"))
      val low    = generateProject(org, clt).copy(id = lowId)
      val high   = generateProject(org, clt).copy(id = highId)

      for {
        _      <- withOrganization(org)
        _      <- withClient(org, clt)
        _      <- rep.insert(high)
        _      <- rep.insert(low)
        locked <- rep.lockOrdered(ProjectFilters(ids = Some(Seq(highId, lowId))))
      } yield locked.map(_.id) shouldEqual Seq(lowId, highId)
    }
  }

  "filter by provided params" should {
    "id" in withDatabase {
      val org = organizationGenerator.generate
      val clt = clientGenerator.generate
      for {
        _  <- withOrganization(org)
        _  <- withClient(org, clt)
        r1 <- rep.insert(generateProject(org, clt))
        r2 <- rep.insert(generateProject(org, clt))
        r3 <- rep.insert(generateProject(org, clt))

        l1 <- rep.list(ProjectFilters(id = Some(r1.id)))
        l2 <- rep.list(ProjectFilters(id = Some(r2.id)))
        l3 <- rep.list(ProjectFilters(id = Some(r3.id)))
      } yield {

        l1 should containTheSameElementsAs(Seq(r1))
        l2 should containTheSameElementsAs(Seq(r2))
        l3 should containTheSameElementsAs(Seq(r3))
      }
    }

    "userId" in withDatabase {
      // 2 users, 2 organizations per user, 2 projects per organization
      val u1 = userGenerator.generate
      val u2 = userGenerator.generate

      val orgs1: Seq[Organization] = Seq(
        organizationGenerator.generate.copy(archivedAt = None, createdBy = u1.id),
        organizationGenerator.generate.copy(archivedAt = None, createdBy = u1.id)
      )
      val orgs2: Seq[Organization] = Seq(
        organizationGenerator.generate.copy(archivedAt = None, createdBy = u2.id),
        organizationGenerator.generate.copy(archivedAt = None, createdBy = u2.id)
      )

      val clts1: Seq[Client] = orgs1.map { org =>
        clientGenerator.generate.copy(archivedAt = None, organizationId = org.id)
      }
      val clts2: Seq[Client] = orgs2.map { org =>
        clientGenerator.generate.copy(archivedAt = None, organizationId = org.id)
      }

      val roles: Seq[Seq[OrganizationUserRole]] = Seq(rolesGenerator.generate, rolesGenerator.generate)

      val projects1: Seq[Project] = orgs1.zip(clts1).flatMap { case (org, clt) => Seq(generateProject(org, clt), generateProject(org, clt)) }
      val projects2: Seq[Project] = orgs2.zip(clts2).flatMap { case (org, clt) => Seq(generateProject(org, clt), generateProject(org, clt)) }

      for {
        _ <- withUser(u1)
        _ <- withUser(u2)
        _ <- DBIO.sequence((orgs1 ++ orgs2).map(withOrganization))
        _ <- DBIO.sequence((orgs1 ++ orgs2).zip(clts1 ++ clts2).map { case (org, clt) => withClient(org, clt) })
        _ <- DBIO.sequence((orgs1.zip(roles) ++ orgs2.zip(roles)).map { case (org, roles) => withCreatorOrganizationUser(org, roles) })
        _ <- DBIO.sequence((projects1 ++ projects2).map(rep.insert))
        _ <- DBIO.sequence(projects1.map(withProjectUser(u1, _)))
        _ <- DBIO.sequence(projects2.map(withProjectUser(u2, _)))
        _ <- DBIO.sequence(
               projects1
                 .map(withProjectUser(u2, _, projectUserGenerator.generate.copy(archivedAt = Some(ProjectUserArchivedAt(Instant.ofEpochMilli(0))))))
             )

        fixtureIds = (projects1 ++ projects2).map(_.id)
        l0 <- rep.list(ProjectFilters(ids = Some(fixtureIds)))
        l1 <- rep.list(ProjectFilters(ids = Some(fixtureIds), userId = Some(u1.id)))
        l2 <- rep.list(ProjectFilters(ids = Some(fixtureIds), userId = Some(u2.id)))
      } yield {

        l0 should containTheSameElementsAs(projects1 ++ projects2)
        l1 should containTheSameElementsAs(projects1)
        l2 should containTheSameElementsAs(projects2)
      }
    }

    "archivedConnectionsUserId" in withDatabase {
      // 2 users, 2 organizations per user, 2 projects per organization
      val u1 = userGenerator.generate
      val u2 = userGenerator.generate

      val orgs1: Seq[Organization] = Seq(
        organizationGenerator.generate.copy(archivedAt = None, createdBy = u1.id),
        organizationGenerator.generate.copy(archivedAt = None, createdBy = u1.id)
      )
      val orgs2: Seq[Organization] = Seq(
        organizationGenerator.generate.copy(archivedAt = None, createdBy = u2.id),
        organizationGenerator.generate.copy(archivedAt = None, createdBy = u2.id)
      )

      val clts1: Seq[Client] = orgs1.map { org =>
        clientGenerator.generate.copy(archivedAt = None, organizationId = org.id)
      }
      val clts2: Seq[Client] = orgs2.map { org =>
        clientGenerator.generate.copy(archivedAt = None, organizationId = org.id)
      }

      val roles: Seq[Seq[OrganizationUserRole]] = Seq(rolesGenerator.generate, rolesGenerator.generate)

      val projects1: Seq[Project] = orgs1.zip(clts1).flatMap { case (org, clt) => Seq(generateProject(org, clt), generateProject(org, clt)) }
      val projects2: Seq[Project] = orgs2.zip(clts2).flatMap { case (org, clt) => Seq(generateProject(org, clt), generateProject(org, clt)) }

      for {
        _ <- withUser(u1)
        _ <- withUser(u2)
        _ <- DBIO.sequence((orgs1 ++ orgs2).map(withOrganization))
        _ <- DBIO.sequence((orgs1 ++ orgs2).zip(clts1 ++ clts2).map { case (org, clt) => withClient(org, clt) })
        _ <- DBIO.sequence((orgs1.zip(roles) ++ orgs2.zip(roles)).map { case (org, roles) => withCreatorOrganizationUser(org, roles) })
        _ <- DBIO.sequence((projects1 ++ projects2).map(rep.insert))
        _ <- DBIO.sequence(projects1.map(withProjectUser(u1, _)))
        _ <- DBIO.sequence(projects2.map(withProjectUser(u2, _)))
        _ <- DBIO.sequence(
               projects1
                 .map(withProjectUser(u2, _, projectUserGenerator.generate))
             )

        fixtureIds = (projects1 ++ projects2).map(_.id)
        l0 <- rep.list(ProjectFilters(ids = Some(fixtureIds)))
        l1 <- rep.list(ProjectFilters(ids = Some(fixtureIds), archivedConnectionsUserId = Some(u1.id)))
        l2 <- rep.list(ProjectFilters(ids = Some(fixtureIds), archivedConnectionsUserId = Some(u2.id)))
      } yield {

        l0 should containTheSameElementsAs(projects1 ++ projects2)
        l1 should containTheSameElementsAs(projects1)
        l2 should containTheSameElementsAs(projects2 ++ projects1)
      }
    }

    "organizationIds" in withDatabase {
      // 2 users, 2 organizations per user, 2 projects per organization
      val u1 = userGenerator.generate
      val u2 = userGenerator.generate

      val orgs1: Seq[Organization] = Seq(
        organizationGenerator.generate.copy(archivedAt = None, createdBy = u1.id),
        organizationGenerator.generate.copy(archivedAt = None, createdBy = u1.id)
      )
      val orgs2: Seq[Organization] = Seq(
        organizationGenerator.generate.copy(archivedAt = None, createdBy = u2.id),
        organizationGenerator.generate.copy(archivedAt = None, createdBy = u2.id)
      )

      val clts1: Seq[Client] = orgs1.map { org =>
        clientGenerator.generate.copy(archivedAt = None, organizationId = org.id)
      }
      val clts2: Seq[Client] = orgs2.map { org =>
        clientGenerator.generate.copy(archivedAt = None, organizationId = org.id)
      }

      val roles: Seq[Seq[OrganizationUserRole]] = Seq(rolesGenerator.generate, rolesGenerator.generate)

      val projects1: Seq[Project] = orgs1.zip(clts1).flatMap { case (org, clt) => Seq(generateProject(org, clt), generateProject(org, clt)) }
      val projects2: Seq[Project] = orgs2.zip(clts2).flatMap { case (org, clt) => Seq(generateProject(org, clt), generateProject(org, clt)) }
      val projects                = projects1 ++ projects2

      val org1Ids   = orgs1.map(_.id)
      val org2Ids   = orgs2.map(_.id)
      val orgAllIds = org1Ids ++ org2Ids

      val org1HeadIds = Seq(orgs1.head.id)
      val org2HeadIds = Seq(orgs2.head.id)
      val orgHeadsIds = org1HeadIds ++ org2HeadIds

      for {
        _ <- withUser(u1)
        _ <- withUser(u2)

        _ <- DBIO.sequence((orgs1 ++ orgs2).map(withOrganization))
        _ <- DBIO.sequence((orgs1 ++ orgs2).zip(clts1 ++ clts2).map { case (org, clt) => withClient(org, clt) })
        _ <- DBIO.sequence((orgs1.zip(roles) ++ orgs2.zip(roles)).map { case (org, roles) => withCreatorOrganizationUser(org, roles) })
        _ <- DBIO.sequence(projects.map(rep.insert))

        fixtureIds = projects.map(_.id)
        l0 <- rep.list(ProjectFilters(ids = Some(fixtureIds)))
        l1 <- rep.list(ProjectFilters(ids = Some(fixtureIds), organizationIds = Some(org1HeadIds)))
        l2 <- rep.list(ProjectFilters(ids = Some(fixtureIds), organizationIds = Some(org2HeadIds)))
        l3 <- rep.list(ProjectFilters(ids = Some(fixtureIds), organizationIds = Some(org1Ids)))
        l4 <- rep.list(ProjectFilters(ids = Some(fixtureIds), organizationIds = Some(org2Ids)))
        l5 <- rep.list(ProjectFilters(ids = Some(fixtureIds), organizationIds = Some(orgHeadsIds)))
        l6 <- rep.list(ProjectFilters(ids = Some(fixtureIds), organizationIds = Some(orgAllIds)))

      } yield {
        l0 should containTheSameElementsAs(projects)
        l1 should containTheSameElementsAs(projects.filter(r => org1HeadIds.contains(r.organizationId)))
        l2 should containTheSameElementsAs(projects.filter(r => org2HeadIds.contains(r.organizationId)))
        l3 should containTheSameElementsAs(projects.filter(r => org1Ids.contains(r.organizationId)))
        l4 should containTheSameElementsAs(projects.filter(r => org2Ids.contains(r.organizationId)))
        l5 should containTheSameElementsAs(projects.filter(r => orgHeadsIds.contains(r.organizationId)))
        l6 should containTheSameElementsAs(projects.filter(r => orgAllIds.contains(r.organizationId)))
      }
    }

    "clientIds" in withDatabase {
      val org  = organizationGenerator.generate
      val clt1 = clientGenerator.generate.copy(organizationId = org.id)
      val clt2 = clientGenerator.generate.copy(organizationId = org.id)
      val clt3 = clientGenerator.generate.copy(organizationId = org.id)
      for {
        _ <- withOrganization(org)
        _ <- withClient(org, clt1)
        _ <- withClient(org, clt2)
        _ <- withClient(org, clt3)

        r1 <- rep.insert(generateProject(org, clt1))
        r2 <- rep.insert(generateProject(org, clt2))
        r3 <- rep.insert(generateProject(org, clt3))
        fixtureIds = Seq(r1.id, r2.id, r3.id)
        l1 <- rep.list(ProjectFilters(ids = Some(fixtureIds), clientIds = Some(Seq(clt1.id))))
        l2 <- rep.list(ProjectFilters(ids = Some(fixtureIds), clientIds = Some(Seq(clt2.id))))
        l3 <- rep.list(ProjectFilters(ids = Some(fixtureIds), clientIds = Some(Seq(clt3.id))))
        l4 <- rep.list(ProjectFilters(ids = Some(fixtureIds), clientIds = Some(Seq(clt1.id, clt3.id))))
      } yield {
        l1 should containTheSameElementsAs(Seq(r1))
        l2 should containTheSameElementsAs(Seq(r2))
        l3 should containTheSameElementsAs(Seq(r3))
        l4 should containTheSameElementsAs(Seq(r1, r3))
      }
    }

    "name" in withDatabase {
      val org  = organizationGenerator.generate
      val clt1 = clientGenerator.generate.copy(organizationId = org.id)
      val clt2 = clientGenerator.generate.copy(organizationId = org.id)
      val clt3 = clientGenerator.generate.copy(organizationId = org.id)
      for {
        _ <- withOrganization(org)
        _ <- withClient(org, clt1)
        _ <- withClient(org, clt2)
        _ <- withClient(org, clt3)

        r1 <- rep.insert(generateProject(org, clt1))
        r2 <- rep.insert(generateProject(org, clt2))
        r3 <- rep.insert(generateProject(org, clt3))
        fixtureIds = Seq(r1.id, r2.id, r3.id)
        l1 <- rep.list(ProjectFilters(ids = Some(fixtureIds), name = Some(r1.name)))
        l2 <- rep.list(ProjectFilters(ids = Some(fixtureIds), name = Some(r2.name)))
        l3 <- rep.list(ProjectFilters(ids = Some(fixtureIds), name = Some(r3.name)))
        l4 <- rep.list(ProjectFilters(ids = Some(fixtureIds), name = Some(generateProject(org, clt3).name)))
      } yield {
        l1 should containTheSameElementsAs(Seq(r1))
        l2 should containTheSameElementsAs(Seq(r2))
        l3 should containTheSameElementsAs(Seq(r3))
        l4 should containTheSameElementsAs(Seq())
      }
    }

    "page size and number" in withDatabase {
      val org = organizationGenerator.generate
      val clt = clientGenerator.generate
      for {
        _  <- withOrganization(org)
        _  <- withClient(org, clt)
        r1 <- rep.insert(generateProject(org, clt))
        r2 <- rep.insert(generateProject(org, clt))
        r3 <- rep.insert(generateProject(org, clt))
        r4 <- rep.insert(generateProject(org, clt))
        r5 <- rep.insert(generateProject(org, clt))
        fixtureIds = Seq(r1.id, r2.id, r3.id, r4.id, r5.id)
        l1 <- rep.list(ProjectFilters(ids = Some(fixtureIds), pageSizeAndNumber = Some(PageSizeAndNumber(size = 4, number = 1))))
        l2 <- rep.list(ProjectFilters(ids = Some(fixtureIds), pageSizeAndNumber = Some(PageSizeAndNumber(size = 2, number = 1))))
        l3 <- rep.list(ProjectFilters(ids = Some(fixtureIds), pageSizeAndNumber = Some(PageSizeAndNumber(size = 2, number = 2))))
      } yield {
        l1.size shouldEqual 4
        l2.size shouldEqual 2
        l3.size shouldEqual 2

        l1 shouldEqual (l2 ++ l3)
      }
    }

    "includeArchived" in withDatabase {
      val org = organizationGenerator.generate
      val clt = clientGenerator.generate
      for {
        _  <- withOrganization(org)
        _  <- withClient(org, clt)
        r1 <- rep.insert(generateProject(org, clt))
        r2 <- rep.insert(generateProject(org, clt))
        fixtureIds = Seq(r1.id, r2.id)
        l0 <- rep.list(ProjectFilters(ids = Some(fixtureIds)))
        l1 <- rep.list(ProjectFilters(id = Some(r1.id)))
        l2 <- rep.list(ProjectFilters(id = Some(r2.id)))

        li0 <- rep.list(ProjectFilters(ids = Some(fixtureIds), includeArchived = true))
        li1 <- rep.list(ProjectFilters(id = Some(r1.id), includeArchived = true))
        li2 <- rep.list(ProjectFilters(id = Some(r2.id), includeArchived = true))

        now = Instant.ofEpochMilli(System.currentTimeMillis())
        rd1 = r1.copy(archivedAt = Some(ProjectArchivedAt(now)))
        _ <- rep.update(rd1)

        ld0 <- rep.list(ProjectFilters(ids = Some(fixtureIds)))
        ld1 <- rep.list(ProjectFilters(id = Some(r1.id)))
        ld2 <- rep.list(ProjectFilters(id = Some(r2.id)))

        ldi0 <- rep.list(ProjectFilters(ids = Some(fixtureIds), includeArchived = true))
        ldi1 <- rep.list(ProjectFilters(id = Some(r1.id), includeArchived = true))
        ldi2 <- rep.list(ProjectFilters(id = Some(r2.id), includeArchived = true))
      } yield {
        l0 should containTheSameElementsAs(Seq(r1, r2))
        l1 should containTheSameElementsAs(Seq(r1))
        l2 should containTheSameElementsAs(Seq(r2))

        li0 should containTheSameElementsAs(l0)
        li1 should containTheSameElementsAs(l1)
        li2 should containTheSameElementsAs(l2)

        ld0 should containTheSameElementsAs(Seq(r2))
        ld1 should containTheSameElementsAs(Nil)
        ld2 should containTheSameElementsAs(Seq(r2))

        ldi0.map(_.id) should containTheSameElementsAs(l0.map(_.id))
        val rd1 = ldi0.find(_.id == r1.id).get
        rd1.archivedAt should beSome
        rd1.copy(archivedAt = None) shouldEqual r1
        ldi0.find(_.id == r2.id).get shouldEqual r2

        ldi1 should containTheSameElementsAs(Seq(rd1))
        ldi2 should containTheSameElementsAs(Seq(r2))
      }
    }
  }
  "count" should {
    "properly handle filters and pagesize" in withDatabase {
      val org1 = organizationGenerator.generate
      val org2 = organizationGenerator.generate
      val clt1 = clientGenerator.generate
      val clt2 = clientGenerator.generate
      for {
        _ <- withOrganization(org1)
        _ <- withOrganization(org2)
        _ <- withClient(org1, clt1)
        _ <- withClient(org2, clt2)

        r1 <- rep.insert(generateProject(org1, clt1))
        r2 <- rep.insert(generateProject(org1, clt1))
        r3 <- rep.insert(generateProject(org1, clt1))
        r4 <- rep.insert(generateProject(org2, clt2))
        r5 <- rep.insert(generateProject(org2, clt2))
        fixtureIds = Seq(r1.id, r2.id, r3.id, r4.id, r5.id)
        c1  <- rep.count(ProjectFilters(ids = Some(fixtureIds)))
        c11 <- rep.count(ProjectFilters(ids = Some(fixtureIds), pageSizeAndNumber = Some(PageSizeAndNumber(size = 4, number = 1))))
        c12 <- rep.count(ProjectFilters(ids = Some(fixtureIds), pageSizeAndNumber = Some(PageSizeAndNumber(size = 2, number = 1))))

        c2 <- rep.count(ProjectFilters(ids = Some(fixtureIds), organizationIds = Some(Seq(org1.id))))
        c21 <- rep.count(
                 ProjectFilters(
                   ids = Some(fixtureIds),
                   organizationIds = Some(Seq(org1.id)),
                   pageSizeAndNumber = Some(PageSizeAndNumber(size = 4, number = 10))
                 )
               )

        c3 <- rep.count(ProjectFilters(ids = Some(fixtureIds), organizationIds = Some(Seq(org2.id))))
      } yield {
        c1 shouldEqual 5
        c11 shouldEqual 5
        c12 shouldEqual 5

        c2 shouldEqual 3
        c21 shouldEqual 3

        c3 shouldEqual 2
      }
    }
  }

  "archive" should {
    "properly soft delete projects by filters and set correct fields" in withDatabase {
      val org1 = organizationGenerator.generate
      val org2 = organizationGenerator.generate
      val clt1 = clientGenerator.generate
      val clt2 = clientGenerator.generate
      val when = ProjectArchivedAt(clt1.createdAt)
      val who  = UserId(UUID.randomUUID())
      for {
        _ <- withOrganization(org1)
        _ <- withOrganization(org2)
        _ <- withClient(org1, clt1)
        _ <- withClient(org2, clt2)

        r1 <- rep.insert(generateProject(org1, clt1))
        r2 <- rep.insert(generateProject(org1, clt1))
        r3 <- rep.insert(generateProject(org1, clt1))
        r4 <- rep.insert(generateProject(org2, clt2))
        r5 <- rep.insert(generateProject(org2, clt2))
        fixtureIds = Seq(r1.id, r2.id, r3.id, r4.id, r5.id)
        filters    = ProjectFilters(ids = Some(fixtureIds), includeArchived = true, clientIds = Some(Seq(clt1.id)))
        c1 <- rep.archive(filters, when, who)
        l1 <- rep.list(filters)
      } yield {
        c1 shouldEqual 3

        val updatedList = Seq(r1, r2, r3).map(el => el.copy(archivedAt = Some(when), archivedBy = Some(who)))

        l1 should containTheSameElementsAs(updatedList)
      }
    }
  }

  private def generateProject(
    organization: Organization,
    client: Client,
    deleted: Boolean = false
  ): Project = {
    val projectGenerator: Generator[Project] = allGenerators[Project].maximal
    val project                              = projectGenerator.generate.copy(organizationId = organization.id, clientId = client.id)
    if (deleted) project
    else project.copy(archivedAt = None)
  }

  private val relationLockTimeout    = 10.seconds
  private val databaseUrl            = sys.env.getOrElse("TEST_DB_URL", "epoca")
  private val databaseUser           = sys.env.getOrElse("TEST_DB_USER", "postgres")
  private val databasePassword       = sys.env.getOrElse("TEST_DB_PASSWORD", "postgres")
  private val relationLockNamePrefix = "ct532-project-relation-lock"

  private def withRelationLockDatabases[A](body: (Database, Database) => A): A = {
    val suffix    = UUID.randomUUID()
    var databases = Vector.empty[Database]

    runWithTeardown {
      val holderDb = relationLockDatabase(s"$relationLockNamePrefix-holder-$suffix")
      databases :+= holderDb
      val observerDb = relationLockDatabase(s"$relationLockNamePrefix-observer-$suffix")
      databases :+= observerDb
      body(holderDb, observerDb)
    } {
      databases.map(database => () => database.close())
    }
  }

  private def relationLockDatabase(name: String): Database = {
    val separator = if (databaseUrl.contains("?")) "&" else "?"

    Database.forURL(
      url = s"$databaseUrl${separator}ApplicationName=$name",
      user = databaseUser,
      password = databasePassword,
      driver = "org.postgresql.Driver",
      executor = AsyncExecutor(name, minThreads = 1, maxThreads = 1, queueSize = 5, maxConnections = 1)
    )
  }

  private def projectRelationLocks(holderPid: Int): DBIO[Seq[(String, Boolean)]] =
    sql"""SELECT lock.mode, lock.granted
           FROM pg_catalog.pg_locks lock
           JOIN pg_catalog.pg_class relation ON relation.oid = lock.relation
           JOIN pg_catalog.pg_namespace namespace ON namespace.oid = relation.relnamespace
           WHERE lock.pid = $holderPid
             AND namespace.nspname = current_schema()
             AND relation.relname = 'project'
             AND lock.locktype = 'relation'
             AND lock.mode = 'RowShareLock'""".as[(String, Boolean)]

  private def awaitDatabase[A](
    database: Database,
    action: DBIO[A],
    clue: String
  ): A =
    try Await.result(database.run(action), relationLockTimeout)
    catch {
      case timeout: TimeoutException => throw new AssertionError(s"Timed out waiting for $clue after $relationLockTimeout", timeout)
    }

  private def await[A](future: Future[A], clue: String): A =
    try Await.result(future, relationLockTimeout)
    catch {
      case timeout: TimeoutException => throw new AssertionError(s"Timed out waiting for $clue after $relationLockTimeout", timeout)
    }

  private def drain(future: Future[_], clue: String): Unit =
    try {
      Await.result(future, relationLockTimeout)
      ()
    } catch {
      case timeout: TimeoutException => throw new AssertionError(s"Timed out draining $clue after $relationLockTimeout", timeout)
    }

  private def runWithTeardown[A](body: => A)(teardownSteps: => Seq[() => Unit]): A = {
    val bodyResult = capture(body)
    val teardownFailures = capture(teardownSteps) match {
      case Left(error)  => Seq(error)
      case Right(steps) => steps.flatMap(step => capture(step()).left.toOption)
    }

    bodyResult match {
      case Left(primaryFailure) =>
        teardownFailures.filterNot(_ eq primaryFailure).foreach(primaryFailure.addSuppressed)
        throw primaryFailure
      case Right(result) =>
        teardownFailures.headOption.foreach { firstFailure =>
          teardownFailures.tail.filterNot(_ eq firstFailure).foreach(firstFailure.addSuppressed)
          throw firstFailure
        }
        result
    }
  }

  private def capture[A](action: => A): Either[Throwable, A] =
    try Right(action)
    catch {
      case NonFatal(error) => Left(error)
    }
}
