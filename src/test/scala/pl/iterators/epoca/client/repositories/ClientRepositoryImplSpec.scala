package pl.iterators.epoca.client.repositories

import pl.iterators.epoca.client.domain.Client
import pl.iterators.epoca.client.domain.ClientDomain._
import pl.iterators.epoca.client.repositories.ClientRepository.ClientFilters
import pl.iterators.epoca.organization.domain.Organization
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationUserRole
import pl.iterators.epoca.organization.repositories.fixtures.{OrganizationFixture, OrganizationUserFixture}
import pl.iterators.epoca.project.domain.Project
import pl.iterators.epoca.project.repositories.fixtures.ProjectFixture
import pl.iterators.epoca.testsupport.generators.Generator
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.repositories.fixtures.UserFixture
import pl.iterators.epoca.utils.PgRepositorySpec
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import pl.iterators.epoca.utils.db.PageSizeAndNumber
import slick.util.AsyncExecutor

import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeoutException
import scala.concurrent.duration._
import scala.concurrent.{Await, Future, Promise}
import scala.util.control.NonFatal

class ClientRepositoryImplSpec extends PgRepositorySpec with OrganizationFixture with OrganizationUserFixture with UserFixture with ProjectFixture {

  sequential

  private val rep: ClientRepository = new ClientRepositoryImpl(ClientRepositoryImpl.Config(30.seconds))

  def generateClient(organization: Organization, deleted: Boolean = false): Client = {
    val clientGenerator: Generator[Client] = allGenerators[Client].maximal
    val client                             = clientGenerator.generate.copy(organizationId = organization.id)
    if (deleted) client
    else client.copy(archivedAt = None)
  }
  val clientNameGenerator: Generator[ClientName]           = allGenerators[ClientName].maximal
  val organizationGenerator: Generator[Organization]       = allGenerators[Organization].maximal
  val projectGenerator: Generator[Project]                 = allGenerators[Project].normal
  val userGenerator: Generator[User]                       = allGenerators[User].maximal
  val rolesGenerator: Generator[Seq[OrganizationUserRole]] = allGenerators[Seq[OrganizationUserRole]].maximal

  "client mutation lock" should {
    "block a second transaction for the same client until the first transaction commits" in withMutationLockDatabases {
      (
        holderDb,
        waiterDb,
        observerDb
      ) =>
        val clientId       = ClientId(UUID.randomUUID())
        val holderPid      = Promise[Int]()
        val holderAcquired = Promise[Unit]()
        val releaseHolder  = Promise[Unit]()
        val waiterPid      = Promise[Int]()
        val holder         = holdMutationLock(holderDb, clientId, holderPid, holderAcquired, releaseHolder, rollback = false)
        var waiter         = Option.empty[Future[Unit]]

        try {
          await(holderAcquired.future, "first client mutation lock")
          val waiting = acquireMutationLock(waiterDb, clientId, waiterPid)
          waiter = Some(waiting)
          val firstBackendPid  = await(holderPid.future, "first mutation-lock backend PID")
          val secondBackendPid = await(waiterPid.future, "second mutation-lock backend PID")

          firstBackendPid shouldNotEqual secondBackendPid
          awaitAdvisoryBlock(observerDb, firstBackendPid, secondBackendPid) shouldEqual
            ((firstBackendPid, Some("Lock"), "advisory"))
          waiting.isCompleted should beFalse

          releaseHolder.trySuccess(()) should beTrue
          await(holder, "first mutation-lock transaction commit")
          await(waiting, "second mutation-lock transaction after commit")
        } finally {
          releaseHolder.trySuccess(())
          drain(holder, "first mutation-lock transaction")
          waiter.foreach(drain(_, "second mutation-lock transaction"))
        }
        1 shouldEqual 1
    }

    "allow a transaction for a different client while the first lock is held" in withMutationLockDatabases {
      (
        holderDb,
        waiterDb,
        observerDb
      ) =>
        val firstClientId  = ClientId(UUID.randomUUID())
        val secondClientId = ClientId(UUID.randomUUID())
        val holderPid      = Promise[Int]()
        val holderAcquired = Promise[Unit]()
        val releaseHolder  = Promise[Unit]()
        val waiterPid      = Promise[Int]()
        val holder         = holdMutationLock(holderDb, firstClientId, holderPid, holderAcquired, releaseHolder, rollback = false)
        var waiter         = Option.empty[Future[Unit]]

        try {
          await(holderAcquired.future, "first client mutation lock")
          val waiting = acquireMutationLock(waiterDb, secondClientId, waiterPid)
          waiter = Some(waiting)
          val secondBackendPid = await(waiterPid.future, "different-client backend PID")

          await(waiting, "different-client mutation lock")
          awaitObserver(
            observerDb,
            sql"SELECT pg_catalog.cardinality(pg_catalog.pg_blocking_pids($secondBackendPid))".as[Int].head,
            "different-client blocker count"
          ) shouldEqual 0
        } finally {
          releaseHolder.trySuccess(())
          drain(holder, "first different-client transaction")
          waiter.foreach(drain(_, "second different-client transaction"))
        }
        1 shouldEqual 1
    }

    "release the client lock when the first transaction rolls back" in withMutationLockDatabases {
      (
        holderDb,
        waiterDb,
        observerDb
      ) =>
        val clientId       = ClientId(UUID.randomUUID())
        val holderPid      = Promise[Int]()
        val holderAcquired = Promise[Unit]()
        val releaseHolder  = Promise[Unit]()
        val waiterPid      = Promise[Int]()
        val holder         = holdMutationLock(holderDb, clientId, holderPid, holderAcquired, releaseHolder, rollback = true)
        var waiter         = Option.empty[Future[Unit]]

        try {
          await(holderAcquired.future, "rolled-back client mutation lock")
          val waiting = acquireMutationLock(waiterDb, clientId, waiterPid)
          waiter = Some(waiting)
          val firstBackendPid  = await(holderPid.future, "rolled-back mutation-lock backend PID")
          val secondBackendPid = await(waiterPid.future, "post-rollback mutation-lock backend PID")

          awaitAdvisoryBlock(observerDb, firstBackendPid, secondBackendPid) shouldEqual
            ((firstBackendPid, Some("Lock"), "advisory"))
          waiting.isCompleted should beFalse

          releaseHolder.trySuccess(()) should beTrue
          awaitFailed(holder, "first mutation-lock transaction rollback")
          await(waiting, "second mutation-lock transaction after rollback")
        } finally {
          releaseHolder.trySuccess(())
          drain(holder, "rolled-back mutation-lock transaction")
          waiter.foreach(drain(_, "post-rollback mutation-lock transaction"))
        }
        1 shouldEqual 1
    }

    "time out a blocked lock request without leaking the pool connection or transaction setting" in withBoundedMutationLockDatabase { sharedDb =>
      val clientId        = ClientId(UUID.randomUUID())
      val holderAcquired  = Promise[Unit]()
      val releaseHolder   = Promise[Unit]()
      val shortTimeoutRep = new ClientRepositoryImpl(ClientRepositoryImpl.Config(150.millis))
      val holder = sharedDb.run((for {
        _ <- rep.lockForMutation(clientId)
        _ <- DBIO.successful { holderAcquired.trySuccess(()); () }
        _ <- DBIO.from(releaseHolder.future)
      } yield ()).transactionally)
      var waiter = Option.empty[Future[Unit]]

      try {
        await(holderAcquired.future, "bounded-wait holder advisory lock")
        val waiting = sharedDb.run(shortTimeoutRep.lockForMutation(clientId).transactionally)
        waiter = Some(waiting)

        val failure = awaitFailure(waiting, 2.seconds, "bounded client mutation lock")
        sqlState(failure) shouldEqual "55P03"
        awaitDatabase(sharedDb, sql"SELECT 1".as[Int].head, 2.seconds, "shared pool recovery query") shouldEqual 1
        awaitDatabase(
          sharedDb,
          sql"SELECT pg_catalog.current_setting('lock_timeout')".as[String].head,
          2.seconds,
          "transaction-local lock timeout reset"
        ) shouldEqual "0"
      } finally {
        releaseHolder.trySuccess(())
        drain(holder, "bounded-wait holder transaction")
        waiter.foreach(drain(_, "bounded client mutation lock transaction"))
      }
      1 shouldEqual 1
    }
  }

  "CRUD" should {
    "work as follows" in withDatabase {
      val org = organizationGenerator.generate
      for {
        _ <- withOrganization(org)

        r1 <- rep.insert(generateClient(org))
        r2 <- rep.insert(generateClient(org))
        r3 <- rep.insert(generateClient(org))
        f1 <- rep.find(ClientFilters(id = Some(r1.id)))
        f2 <- rep.find(ClientFilters(id = Some(r2.id)))
        f3 <- rep.find(ClientFilters(id = Some(r3.id)))

        fixtureIds = Seq(r1.id, r2.id, r3.id)
        l0 <- rep.list(ClientFilters(ids = Some(fixtureIds)))
        l1 <- rep.list(ClientFilters(id = Some(r1.id)))
        l2 <- rep.list(ClientFilters(id = Some(r2.id)))
        l3 <- rep.list(ClientFilters(id = Some(r3.id)))

        ru1 = r1.copy(name = clientNameGenerator.generate)
        ru2 = r2.copy(name = clientNameGenerator.generate)

        _ <- rep.update(ru1)
        _ <- rep.update(ru2)

        lu0 <- rep.list(ClientFilters(ids = Some(fixtureIds)))
        lu1 <- rep.list(ClientFilters(id = Some(r1.id)))
        lu2 <- rep.list(ClientFilters(id = Some(r2.id)))
        lu3 <- rep.list(ClientFilters(id = Some(r3.id)))

        now = Instant.ofEpochMilli(System.currentTimeMillis())
        rd2 = r2.copy(archivedAt = Some(ClientArchivedAt(now)))
        rd3 = r3.copy(archivedAt = Some(ClientArchivedAt(now)))

        _ <- rep.update(rd2)
        _ <- rep.update(rd3)

        ld0 <- rep.list(ClientFilters(ids = Some(fixtureIds)))
        ld1 <- rep.list(ClientFilters(id = Some(r1.id)))
        ld2 <- rep.list(ClientFilters(id = Some(r2.id)))
        ld3 <- rep.list(ClientFilters(id = Some(r3.id)))

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

  "filter by provided params" should {
    "id" in withDatabase {
      val org = organizationGenerator.generate
      for {
        _  <- withOrganization(org)
        r1 <- rep.insert(generateClient(org))
        r2 <- rep.insert(generateClient(org))
        r3 <- rep.insert(generateClient(org))

        l1 <- rep.list(ClientFilters(id = Some(r1.id)))
        l2 <- rep.list(ClientFilters(id = Some(r2.id)))
        l3 <- rep.list(ClientFilters(id = Some(r3.id)))
      } yield {

        l1 should containTheSameElementsAs(Seq(r1))
        l2 should containTheSameElementsAs(Seq(r2))
        l3 should containTheSameElementsAs(Seq(r3))
      }
    }

    "userId" in withDatabase {
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

      val roles: Seq[Seq[OrganizationUserRole]] = Seq(rolesGenerator.generate, rolesGenerator.generate)

      val clients1: Seq[Client] = orgs1.flatMap(org => Seq(generateClient(org), generateClient(org)))
      val clients2: Seq[Client] = orgs2.flatMap(org => Seq(generateClient(org), generateClient(org)))

      for {
        _ <- withUser(u1)
        _ <- withUser(u2)

        _ <- DBIO.sequence((orgs1 ++ orgs2).map(withOrganization))
        _ <- DBIO.sequence((orgs1.zip(roles) ++ orgs2.zip(roles)).map { case (org, roles) => withCreatorOrganizationUser(org, roles) })
        _ <- DBIO.sequence((clients1 ++ clients2).map(rep.insert))

        fixtureIds = (clients1 ++ clients2).map(_.id)
        l0 <- rep.list(ClientFilters(ids = Some(fixtureIds)))
        l1 <- rep.list(ClientFilters(ids = Some(fixtureIds), userId = Some(u1.id)))
        l2 <- rep.list(ClientFilters(ids = Some(fixtureIds), userId = Some(u2.id)))

      } yield {
        l0 should containTheSameElementsAs(clients1 ++ clients2)
        l1 should containTheSameElementsAs(clients1)
        l2 should containTheSameElementsAs(clients2)
      }
    }

    "organizationIds" in withDatabase {
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
      val roles: Seq[Seq[OrganizationUserRole]] = Seq(rolesGenerator.generate, rolesGenerator.generate)

      val clients1: Seq[Client] = orgs1.flatMap(org => Seq(generateClient(org), generateClient(org)))
      val clients2: Seq[Client] = orgs2.flatMap(org => Seq(generateClient(org), generateClient(org)))
      val clients               = clients1 ++ clients2

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
        _ <- DBIO.sequence((orgs1.zip(roles) ++ orgs2.zip(roles)).map { case (org, roles) => withCreatorOrganizationUser(org, roles) })
        _ <- DBIO.sequence(clients.map(rep.insert))

        l0 <- rep.list(ClientFilters(ids = Some(clients.map(_.id))))
        l1 <- rep.list(ClientFilters(organizationIds = Some(org1HeadIds)))
        l2 <- rep.list(ClientFilters(organizationIds = Some(org2HeadIds)))
        l3 <- rep.list(ClientFilters(organizationIds = Some(org1Ids)))
        l4 <- rep.list(ClientFilters(organizationIds = Some(org2Ids)))
        l5 <- rep.list(ClientFilters(organizationIds = Some(orgHeadsIds)))
        l6 <- rep.list(ClientFilters(organizationIds = Some(orgAllIds)))

      } yield {
        l0 should containTheSameElementsAs(clients)
        l1 should containTheSameElementsAs(clients.filter(r => org1HeadIds.contains(r.organizationId)))
        l2 should containTheSameElementsAs(clients.filter(r => org2HeadIds.contains(r.organizationId)))
        l3 should containTheSameElementsAs(clients.filter(r => org1Ids.contains(r.organizationId)))
        l4 should containTheSameElementsAs(clients.filter(r => org2Ids.contains(r.organizationId)))
        l5 should containTheSameElementsAs(clients.filter(r => orgHeadsIds.contains(r.organizationId)))
        l6 should containTheSameElementsAs(clients.filter(r => orgAllIds.contains(r.organizationId)))
      }
    }

    "page size and number" in withDatabase {
      val org = organizationGenerator.generate
      for {
        _  <- withOrganization(org)
        r1 <- rep.insert(generateClient(org))
        r2 <- rep.insert(generateClient(org))
        r3 <- rep.insert(generateClient(org))
        r4 <- rep.insert(generateClient(org))
        r5 <- rep.insert(generateClient(org))

        fixtureIds = Seq(r1.id, r2.id, r3.id, r4.id, r5.id)
        l1 <- rep.list(ClientFilters(ids = Some(fixtureIds), pageSizeAndNumber = Some(PageSizeAndNumber(size = 4, number = 1))))
        l2 <- rep.list(ClientFilters(ids = Some(fixtureIds), pageSizeAndNumber = Some(PageSizeAndNumber(size = 2, number = 1))))
        l3 <- rep.list(ClientFilters(ids = Some(fixtureIds), pageSizeAndNumber = Some(PageSizeAndNumber(size = 2, number = 2))))
      } yield {
        l1.size shouldEqual 4
        l2.size shouldEqual 2
        l3.size shouldEqual 2

        l1 shouldEqual (l2 ++ l3)
      }
    }

    "includeArchived" in withDatabase {
      val org = organizationGenerator.generate
      for {
        _  <- withOrganization(org)
        r1 <- rep.insert(generateClient(org))
        r2 <- rep.insert(generateClient(org))
        fixtureIds = Seq(r1.id, r2.id)
        l0 <- rep.list(ClientFilters(ids = Some(fixtureIds)))
        l1 <- rep.list(ClientFilters(id = Some(r1.id)))
        l2 <- rep.list(ClientFilters(id = Some(r2.id)))

        li0 <- rep.list(ClientFilters(ids = Some(fixtureIds), includeArchived = true))
        li1 <- rep.list(ClientFilters(id = Some(r1.id), includeArchived = true))
        li2 <- rep.list(ClientFilters(id = Some(r2.id), includeArchived = true))

        now = Instant.ofEpochMilli(System.currentTimeMillis())
        rd1 = r1.copy(archivedAt = Some(ClientArchivedAt(now)))
        _ <- rep.update(rd1)

        ld0 <- rep.list(ClientFilters(ids = Some(fixtureIds)))
        ld1 <- rep.list(ClientFilters(id = Some(r1.id)))
        ld2 <- rep.list(ClientFilters(id = Some(r2.id)))

        ldi0 <- rep.list(ClientFilters(ids = Some(fixtureIds), includeArchived = true))
        ldi1 <- rep.list(ClientFilters(id = Some(r1.id), includeArchived = true))
        ldi2 <- rep.list(ClientFilters(id = Some(r2.id), includeArchived = true))
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
  "project visibility filters" should {
    "filter list and count before pagination" in withDatabase {
      val org = organizationGenerator.generate
      for {
        _  <- withOrganization(org)
        c1 <- rep.insert(generateClient(org))
        c2 <- rep.insert(generateClient(org))
        c3 <- rep.insert(generateClient(org))
        _  <- withProject(org, c1, projectGenerator.generate.copy(archivedAt = None, archivedBy = None))
        p2 <- withProject(org, c2, projectGenerator.generate.copy(archivedAt = None, archivedBy = None))

        visibleFilter = ClientFilters(
                          organizationIds = Some(Seq(org.id)),
                          requireActiveProject = true,
                          pageSizeAndNumber = Some(PageSizeAndNumber(size = 1, number = 1))
                        )
        firstPage    <- rep.list(visibleFilter)
        allVisible   <- rep.list(visibleFilter.copy(pageSizeAndNumber = None))
        visibleCount <- rep.count(visibleFilter)
        selected     <- rep.list(visibleFilter.copy(projectIds = Some(Seq(p2.id)), pageSizeAndNumber = None))
        outside      <- rep.list(visibleFilter.copy(projectIds = Some(Seq.empty), pageSizeAndNumber = None))
      } yield {
        firstPage should have size 1
        Seq(c1.id, c2.id) should contain(firstPage.head.id)
        allVisible should containTheSameElementsAs(Seq(c1, c2))
        allVisible.map(_.id) should not contain c3.id
        visibleCount shouldEqual 2
        selected shouldEqual Seq(c2)
        outside should beEmpty
      }
    }
  }

  "count" should {
    "properly handle filters and pagesize" in withDatabase {
      val org1 = organizationGenerator.generate
      val org2 = organizationGenerator.generate
      for {
        _ <- withOrganization(org1)
        _ <- withOrganization(org2)

        r1 <- rep.insert(generateClient(org1))
        r2 <- rep.insert(generateClient(org1))
        r3 <- rep.insert(generateClient(org1))
        r4 <- rep.insert(generateClient(org2))
        r5 <- rep.insert(generateClient(org2))

        fixtureIds = Seq(r1.id, r2.id, r3.id, r4.id, r5.id)
        c1  <- rep.count(ClientFilters(ids = Some(fixtureIds)))
        c11 <- rep.count(ClientFilters(ids = Some(fixtureIds), pageSizeAndNumber = Some(PageSizeAndNumber(size = 4, number = 1))))
        c12 <- rep.count(ClientFilters(ids = Some(fixtureIds), pageSizeAndNumber = Some(PageSizeAndNumber(size = 2, number = 1))))

        c2 <- rep.count(ClientFilters(ids = Some(fixtureIds), organizationIds = Some(Seq(org1.id))))
        c21 <- rep.count(
                 ClientFilters(
                   ids = Some(fixtureIds),
                   organizationIds = Some(Seq(org1.id)),
                   pageSizeAndNumber = Some(PageSizeAndNumber(size = 4, number = 10))
                 )
               )

        c3 <- rep.count(ClientFilters(ids = Some(fixtureIds), organizationIds = Some(Seq(org2.id))))
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

  private val mutationLockTimeout      = 10.seconds
  private val lockObservationTimeout   = 5.seconds
  private val lockObservationPollDelay = 25.millis
  private val databaseUrl              = sys.env.getOrElse("TEST_DB_URL", "epoca")
  private val databaseUser             = sys.env.getOrElse("TEST_DB_USER", "postgres")
  private val databasePassword         = sys.env.getOrElse("TEST_DB_PASSWORD", "postgres")

  private def withMutationLockDatabases[A](body: (Database, Database, Database) => A): A = {
    val suffix     = UUID.randomUUID()
    val holderDb   = mutationLockDatabase(s"client-mutation-holder-$suffix")
    val waiterDb   = mutationLockDatabase(s"client-mutation-waiter-$suffix")
    val observerDb = mutationLockDatabase(s"client-mutation-observer-$suffix")
    val bodyResult = capture(body(holderDb, waiterDb, observerDb))
    val closeFailures = Seq(holderDb, waiterDb, observerDb).flatMap { database =>
      capture(database.close()).left.toOption
    }

    bodyResult match {
      case Left(primaryFailure) =>
        closeFailures.filterNot(_ eq primaryFailure).foreach(primaryFailure.addSuppressed)
        throw primaryFailure
      case Right(result) =>
        closeFailures.headOption.foreach { firstFailure =>
          closeFailures.tail.filterNot(_ eq firstFailure).foreach(firstFailure.addSuppressed)
          throw firstFailure
        }
        result
    }
  }

  private def mutationLockDatabase(executorName: String): Database =
    Database.forURL(
      url = databaseUrl,
      user = databaseUser,
      password = databasePassword,
      driver = "org.postgresql.Driver",
      executor = AsyncExecutor(executorName, minThreads = 1, maxThreads = 1, queueSize = 5, maxConnections = 1)
    )

  private def withBoundedMutationLockDatabase[A](body: Database => A): A = {
    val sharedDb = Database.forURL(
      url = databaseUrl,
      user = databaseUser,
      password = databasePassword,
      driver = "org.postgresql.Driver",
      executor = AsyncExecutor(s"client-mutation-bounded-${UUID.randomUUID()}", minThreads = 2, maxThreads = 2, queueSize = 5, maxConnections = 2)
    )
    val bodyResult   = capture(body(sharedDb))
    val closeFailure = capture(sharedDb.close()).left.toOption

    bodyResult match {
      case Left(primaryFailure) =>
        closeFailure.filterNot(_ eq primaryFailure).foreach(primaryFailure.addSuppressed)
        throw primaryFailure
      case Right(result) =>
        closeFailure.foreach(throw _)
        result
    }
  }

  private def holdMutationLock(
    database: Database,
    clientId: ClientId,
    backendPid: Promise[Int],
    acquired: Promise[Unit],
    release: Promise[Unit],
    rollback: Boolean
  ): Future[Unit] =
    database.run((for {
      pid <- sql"SELECT pg_catalog.pg_backend_pid()".as[Int].head
      _   <- DBIO.successful { backendPid.trySuccess(pid); () }
      _   <- rep.lockForMutation(clientId)
      _   <- DBIO.successful { acquired.trySuccess(()); () }
      _   <- DBIO.from(release.future)
      _   <- if (rollback) DBIO.failed(new RuntimeException("expected mutation-lock rollback")) else DBIO.successful(())
    } yield ()).transactionally)

  private def acquireMutationLock(
    database: Database,
    clientId: ClientId,
    backendPid: Promise[Int]
  ): Future[Unit] =
    database.run((for {
      pid <- sql"SELECT pg_catalog.pg_backend_pid()".as[Int].head
      _   <- DBIO.successful { backendPid.trySuccess(pid); () }
      _   <- rep.lockForMutation(clientId)
    } yield ()).transactionally)

  private def awaitAdvisoryBlock(
    observerDb: Database,
    firstBackendPid: Int,
    secondBackendPid: Int
  ): (Int, Option[String], String) = {
    val deadline        = lockObservationTimeout.fromNow
    var lastObservation = Option.empty[(Int, Option[String], String)]

    while (deadline.hasTimeLeft()) {
      lastObservation = awaitObserver(
        observerDb,
        sql"""SELECT blocker.blocker_pid, activity.wait_event_type, waiting_lock.locktype
               FROM pg_catalog.pg_stat_activity AS activity
               CROSS JOIN LATERAL pg_catalog.unnest(pg_catalog.pg_blocking_pids(activity.pid)) AS blocker(blocker_pid)
               JOIN pg_catalog.pg_locks AS waiting_lock
                 ON waiting_lock.pid = activity.pid
                AND waiting_lock.locktype = 'advisory'
                AND NOT waiting_lock.granted
               WHERE activity.pid = $secondBackendPid
                 AND blocker.blocker_pid = $firstBackendPid"""
          .as[(Int, Option[String], String)]
          .headOption,
        "client advisory-lock observation"
      )
      lastObservation.foreach(return _)
      Thread.sleep(lockObservationPollDelay.toMillis)
    }

    throw new AssertionError(
      s"Timed out after $lockObservationTimeout waiting for backend $secondBackendPid to wait on backend $firstBackendPid's advisory lock; " +
        s"lastObservation=$lastObservation"
    )
  }

  private def awaitObserver[A](
    observerDb: Database,
    action: DBIO[A],
    clue: String
  ): A =
    try Await.result(observerDb.run(action), mutationLockTimeout)
    catch {
      case timeout: TimeoutException => throw new AssertionError(s"Timed out waiting for $clue after $mutationLockTimeout", timeout)
    }

  private def await[A](future: Future[A], clue: String): A =
    try Await.result(future, mutationLockTimeout)
    catch {
      case timeout: TimeoutException => throw new AssertionError(s"Timed out waiting for $clue after $mutationLockTimeout", timeout)
    }

  private def awaitFailure(
    future: Future[_],
    timeout: FiniteDuration,
    clue: String
  ): Throwable =
    try {
      Await.result(future, timeout)
      throw new AssertionError(s"Expected $clue to fail")
    } catch {
      case harnessTimeout: TimeoutException =>
        throw new AssertionError(s"Harness timed out waiting for $clue after $timeout", harnessTimeout)
      case NonFatal(error) => error
    }

  private def awaitDatabase[A](
    database: Database,
    action: DBIO[A],
    timeout: FiniteDuration,
    clue: String
  ): A =
    try Await.result(database.run(action), timeout)
    catch {
      case harnessTimeout: TimeoutException =>
        throw new AssertionError(s"Harness timed out waiting for $clue after $timeout", harnessTimeout)
    }

  private def sqlState(error: Throwable): String =
    error match {
      case sqlError: SQLException if sqlError.getSQLState != null       => sqlError.getSQLState
      case other if other.getCause != null && (other.getCause ne other) => sqlState(other.getCause)
      case other => throw new AssertionError(s"Expected SQLException with SQLSTATE, got ${other.getClass.getName}", other)
    }

  private def awaitFailed(future: Future[_], clue: String): Unit =
    try {
      Await.result(future, mutationLockTimeout)
      throw new AssertionError(s"Expected $clue to fail")
    } catch {
      case _: RuntimeException       => ()
      case timeout: TimeoutException => throw new AssertionError(s"Timed out waiting for $clue after $mutationLockTimeout", timeout)
    }

  private def drain(future: Future[_], clue: String): Unit =
    try {
      Await.ready(future, mutationLockTimeout)
      ()
    } catch {
      case timeout: TimeoutException => throw new AssertionError(s"Timed out draining $clue after $mutationLockTimeout", timeout)
    }

  private def capture[A](action: => A): Either[Throwable, A] =
    try Right(action)
    catch {
      case NonFatal(error) => Left(error)
    }

}
