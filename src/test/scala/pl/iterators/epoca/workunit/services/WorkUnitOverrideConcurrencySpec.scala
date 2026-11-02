package pl.iterators.epoca.workunit.services

import cats.effect.unsafe.IORuntime
import org.specs2.mutable.SpecificationLike
import pl.iterators.epoca.archiver.services.ClientDeleteService
import pl.iterators.epoca.archiver.services.ClientDeleteService.ClientDeleteResult
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.client.domain.Client
import pl.iterators.epoca.client.repositories.ClientRepository.ClientFilters
import pl.iterators.epoca.client.repositories.{ClientRepository, ClientRepositoryImpl}
import pl.iterators.epoca.organization.domain.OrganizationDomain._
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.organization.repositories.{
  OrganizationRepository,
  OrganizationRepositoryImpl,
  OrganizationUserRepository,
  OrganizationUserRepositoryImpl
}
import pl.iterators.epoca.project.domain.ProjectDomain._
import pl.iterators.epoca.project.domain.{Project, ProjectUser}
import pl.iterators.epoca.project.repositories.ProjectRepository.ProjectFilters
import pl.iterators.epoca.project.repositories.ProjectUserRepository.ProjectUserFilters
import pl.iterators.epoca.project.repositories.{ProjectRepository, ProjectRepositoryImpl, ProjectUserRepository, ProjectUserRepositoryImpl}
import pl.iterators.epoca.project.services.ProjectBulkDeassignService
import pl.iterators.epoca.project.services.ProjectBulkDeassignService.{ProjectBulkDeassignRequest, ProjectBulkDeassignResult}
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.domain.UserDomain.{UserId, UserIsSuperAdmin}
import pl.iterators.epoca.user.repositories.{UserRepository, UserRepositoryImpl}
import pl.iterators.epoca.utils.Generators
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import pl.iterators.epoca.utils.db.DBIOInstances._
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.workunit.domain.WorkUnitDomain._
import pl.iterators.epoca.workunit.domain.{WorkUnit, WorkUnitOverrideHistory, WorkUnitOverrideState, WorkUnitTaskOverride}
import pl.iterators.epoca.workunit.repositories.WorkUnitRepository.WorkUnitFilters
import pl.iterators.epoca.workunit.repositories._
import pl.iterators.epoca.workunit.services.WorkUnitDtoService.WorkUnitDto
import pl.iterators.epoca.workunit.services.WorkUnitOverrideResult.{Ok, ProjectUnavailable, StaleRevision}
import slick.dbio.DBIO
import slick.jdbc.SetParameter
import slick.util.AsyncExecutor

import java.sql.Types
import java.time.{Duration, Instant}
import java.util.UUID
import java.util.concurrent.TimeoutException
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.util.control.NonFatal

class WorkUnitOverrideConcurrencySpec extends SpecificationLike with Generators {
  sequential

  private implicit val ec: ExecutionContext = ExecutionContext.global
  private implicit val runtime: IORuntime   = IORuntime.global
  private implicit val setUuid: SetParameter[UUID] =
    SetParameter((value, parameters) => parameters.setObject(value, Types.OTHER))

  private val requestTimeout     = 20.seconds
  private val databaseTimeout    = 10.seconds
  private val observationTimeout = 10.seconds
  private val databaseUrl        = sys.env.getOrElse("TEST_DB_URL", "epoca")
  private val databaseUser       = sys.env.getOrElse("TEST_DB_USER", "postgres")
  private val databasePassword   = sys.env.getOrElse("TEST_DB_PASSWORD", "postgres")
  private val executorName       = s"${getClass.getCanonicalName}-${UUID.randomUUID()}"

  private lazy val db = Database.forURL(
    url = databaseUrl,
    user = databaseUser,
    password = databasePassword,
    driver = "org.postgresql.Driver",
    executor = AsyncExecutor(executorName, minThreads = 6, maxThreads = 6, queueSize = 40, maxConnections = 6)
  )
  private lazy val observerDb = Database.forURL(
    url = databaseUrl,
    user = databaseUser,
    password = databasePassword,
    driver = "org.postgresql.Driver",
    executor = AsyncExecutor(s"$executorName-observer", minThreads = 1, maxThreads = 1, queueSize = 20, maxConnections = 1)
  )

  private val organizationRepository: OrganizationRepository         = new OrganizationRepositoryImpl()
  private val organizationUserRepository: OrganizationUserRepository = new OrganizationUserRepositoryImpl()
  private val clientRepository: ClientRepository                     = new ClientRepositoryImpl()
  private val projectRepository: ProjectRepository                   = new ProjectRepositoryImpl()
  private val projectUserRepository: ProjectUserRepository           = new ProjectUserRepositoryImpl()
  private val userRepository: UserRepository                         = new UserRepositoryImpl()
  private val baseWorkUnitRepository: WorkUnitRepository             = new WorkUnitRepositoryImpl()
  private val historyRepository: WorkUnitOverrideHistoryRepository   = new WorkUnitOverrideHistoryRepositoryImpl()

  "Work-unit override transaction" should {
    "read current revision, version and Undo head from one repeatable snapshot" in {
      var fixtureOpt = Option.empty[Fixture]
      val read       = Promise[Unit]()
      val release    = Promise[Unit]()
      var futures    = Vector.empty[(String, Future[_])]
      runWithTeardown {
        val fixture = setupFixture(OrganizationUserRole.Manager, projectOwner = false)
        fixtureOpt = Some(fixture)
        val writer = newService(baseWorkUnitRepository)
        val initial = okDto(
          await(
            writer
              .replace(
                fixture.auth,
                fixture.organization.slug,
                fixture.workUnit.id,
                ReplaceWorkUnitOverrideRequest(0, Some(fixture.targetProject.id), WorkUnitTaskOverride("Clear"))
              )
              .unsafeToFuture(),
            "initial correction"
          )
        )
        val repository = new ObservedWorkUnitRepository(baseWorkUnitRepository, fixture.workUnit.id, Promise[Int](), None) {
          override def find(filter: WorkUnitFilters): DBIO[Option[WorkUnit]] = super.find(filter).flatMap { unit =>
            read.trySuccess(())
            DBIO.from(release.future).map(_ => unit)
          }
        }
        val reader = newService(repository).get(fixture.auth, fixture.organization.slug, fixture.workUnit.id).unsafeToFuture()
        futures :+= "current snapshot reader" -> reader
        await(read.future, "worklog read before concurrent write")
        val updated = okDto(
          await(
            writer
              .replace(
                fixture.auth,
                fixture.organization.slug,
                fixture.workUnit.id,
                ReplaceWorkUnitOverrideRequest(1, None, WorkUnitTaskOverride("Set", Some("B")))
              )
              .unsafeToFuture(),
            "concurrent correction"
          )
        )
        release.trySuccess(())
        val result = okDto(await(reader, "coherent current snapshot"))
        result.revision shouldEqual initial.revision
        result.`override`.version shouldEqual initial.`override`.version
        result.`override`.undo shouldEqual initial.`override`.undo
        updated.`override`.undo.get.candidateEventId should not(beEqualTo(initial.`override`.undo.get.candidateEventId))
      } {
        Seq[() => Unit] { () => release.trySuccess(()); () } ++ drain(futures) ++ Seq(() => fixtureOpt.foreach(cleanup))
      }
    }
    Seq("Undo", "Replace", "source PATCH").foreach { competitor =>
      s"serialize Undo against $competitor without duplicate history or lost source data" in {
        var fixtureOpt = Option.empty[Fixture]
        val release    = Promise[Unit]()
        var futures    = Vector.empty[(String, Future[_])]
        runWithTeardown {
          val fixture = setupFixture(OrganizationUserRole.Manager, projectOwner = false)
          fixtureOpt = Some(fixture)
          val initial = okDto(
            await(
              newService(baseWorkUnitRepository)
                .replace(
                  fixture.auth,
                  fixture.organization.slug,
                  fixture.workUnit.id,
                  ReplaceWorkUnitOverrideRequest(0, Some(fixture.targetProject.id), WorkUnitTaskOverride("Set", Some("A")))
                )
                .unsafeToFuture(),
              "initial correction"
            )
          )
          val request   = UndoWorkUnitOverrideRequest(1, initial.`override`.undo.get.candidateEventId.get)
          val firstPid  = Promise[Int]()
          val locked    = Promise[Unit]()
          val secondPid = Promise[Int]()
          val firstService =
            newService(new ObservedWorkUnitRepository(baseWorkUnitRepository, fixture.workUnit.id, firstPid, Some(locked -> release)))
          val secondRepository = new ObservedWorkUnitRepository(baseWorkUnitRepository, fixture.workUnit.id, secondPid, None)
          val secondService    = newService(secondRepository)
          val first            = firstService.undo(fixture.auth, fixture.organization.slug, fixture.workUnit.id, request).unsafeToFuture()
          futures :+= "Undo holding worklog" -> first
          await(locked.future, "Undo worklog lock")
          val second: Future[Any] = competitor match {
            case "Undo" => secondService.undo(fixture.auth, fixture.organization.slug, fixture.workUnit.id, request).unsafeToFuture()
            case "Replace" =>
              secondService
                .replace(
                  fixture.auth,
                  fixture.organization.slug,
                  fixture.workUnit.id,
                  ReplaceWorkUnitOverrideRequest(1, None, WorkUnitTaskOverride("Set", Some("B")))
                )
                .unsafeToFuture()
            case _ =>
              db.run((for {
                unit <- secondRepository.find(WorkUnitFilters(id = Some(fixture.workUnit.id), forUpdate = true)).map(_.get)
                _    <- secondRepository.update(unit.copy(description = WorkUnitDescription("survives Undo"), taskId = Some(WorkUnitTaskId("LATER"))))
              } yield ()).transactionally)
          }
          futures :+= competitor -> second
          awaitBlockingRelationship(await(firstPid.future, "Undo pid"), await(secondPid.future, "competitor pid"))
          second.isCompleted should beFalse
          release.trySuccess(())
          okDto(await(first, "Undo commit")).revision shouldEqual 2L
          val competingResult = await(second, "competitor completes")
          val saved           = awaitDb(baseWorkUnitRepository.find(WorkUnitFilters(id = Some(fixture.workUnit.id))).map(_.get), "saved worklog")
          val events          = awaitDb(historyRepository.list(fixture.workUnit.id, Long.MaxValue, None), "atomic history")
          events.map(_.operation) shouldEqual Seq("Undo", "Replace")
          events.head.revertsEventId shouldEqual Some(request.targetEventId)
          saved.overrideState.active should beFalse
          if (competitor == "source PATCH") {
            saved.revision shouldEqual 3L
            saved.description shouldEqual WorkUnitDescription("survives Undo")
            saved.effectiveTaskId shouldEqual Some(WorkUnitTaskId("LATER"))
          } else {
            competingResult shouldEqual StaleRevision(2)
            saved.revision shouldEqual 2L
            saved.copy(revision = 0, overrideState = WorkUnitOverrideState()) shouldEqual fixture.workUnit
          }
        } {
          Seq[() => Unit] { () => release.trySuccess(()); () } ++ drain(futures) ++ Seq(() => fixtureOpt.foreach(cleanup))
        }
      }
    }
    "revalidate the restored project after waiting for its archival" in {
      var fixtureOpt     = Option.empty[Fixture]
      val release        = Promise[Unit]()
      val archivedLocked = Promise[Unit]()
      val archivePid     = Promise[Int]()
      val undoPid        = Promise[Int]()
      var futures        = Vector.empty[(String, Future[_])]
      runWithTeardown {
        val fixture = setupFixture(OrganizationUserRole.Manager, projectOwner = false)
        fixtureOpt = Some(fixture)
        val service = newService(baseWorkUnitRepository)
        await(
          service
            .replace(
              fixture.auth,
              fixture.organization.slug,
              fixture.workUnit.id,
              ReplaceWorkUnitOverrideRequest(0, Some(fixture.targetProject.id), WorkUnitTaskOverride("Inherit"))
            )
            .unsafeToFuture(),
          "target correction"
        )
        val cleared =
          okDto(await(service.delete(fixture.auth, fixture.organization.slug, fixture.workUnit.id, 1).unsafeToFuture(), "clear correction"))
        val archive = db.run((for {
          pid <- sql"select pg_backend_pid()".as[Int].head
          _ = archivePid.trySuccess(pid)
          project <- projectRepository.find(ProjectFilters(id = Some(fixture.targetProject.id), forUpdate = true)).map(_.get)
          _ = archivedLocked.trySuccess(())
          _ <- DBIO.from(release.future)
          _ <- projectRepository.update(project.copy(archivedAt = Some(ProjectArchivedAt(Instant.now())), archivedBy = Some(fixture.actor.id)))
        } yield ()).transactionally)
        futures :+= "archive restored project" -> archive
        await(archivedLocked.future, "restored project locked")
        val undo = newService(new ObservedWorkUnitRepository(baseWorkUnitRepository, fixture.workUnit.id, undoPid, None))
          .undo(
            fixture.auth,
            fixture.organization.slug,
            fixture.workUnit.id,
            UndoWorkUnitOverrideRequest(2, cleared.`override`.undo.get.candidateEventId.get)
          )
          .unsafeToFuture()
        futures :+= "Undo after project archive" -> undo
        awaitBlockingRelationship(await(archivePid.future, "archive pid"), await(undoPid.future, "Undo pid"))
        undo.isCompleted should beFalse
        release.trySuccess(())
        await(archive, "archive commits")
        await(undo, "Undo rejects archived target") shouldEqual ProjectUnavailable
        awaitDb(historyRepository.count(fixture.workUnit.id, Long.MaxValue), "unchanged history") shouldEqual 2
        awaitDb(baseWorkUnitRepository.find(WorkUnitFilters(id = Some(fixture.workUnit.id))).map(_.get.revision), "unchanged revision") shouldEqual 2L
      } {
        Seq[() => Unit] { () => release.trySuccess(()); () } ++ drain(futures) ++ Seq(() => fixtureOpt.foreach(cleanup))
      }
    }
    "roll back an Undo event if the current-state update fails" in {
      var fixtureOpt = Option.empty[Fixture]
      runWithTeardown {
        val fixture = setupFixture(OrganizationUserRole.Manager, projectOwner = false)
        fixtureOpt = Some(fixture)
        val initial = okDto(
          await(
            newService(baseWorkUnitRepository)
              .replace(
                fixture.auth,
                fixture.organization.slug,
                fixture.workUnit.id,
                ReplaceWorkUnitOverrideRequest(0, Some(fixture.targetProject.id), WorkUnitTaskOverride("Clear"))
              )
              .unsafeToFuture(),
            "initial correction"
          )
        )
        val failure = new IllegalStateException("injected Undo update failure")
        val repository = new ObservedWorkUnitRepository(baseWorkUnitRepository, fixture.workUnit.id, Promise[Int](), None) {
          override def update(value: WorkUnit): DBIO[Option[WorkUnit]] = DBIO.failed(failure)
        }
        await(
          newService(repository)
            .undo(
              fixture.auth,
              fixture.organization.slug,
              fixture.workUnit.id,
              UndoWorkUnitOverrideRequest(1, initial.`override`.undo.get.candidateEventId.get)
            )
            .attempt
            .unsafeToFuture(),
          "failed Undo"
        ) shouldEqual Left(failure)
        awaitDb(historyRepository.count(fixture.workUnit.id, Long.MaxValue), "rolled back Undo") shouldEqual 1
        awaitDb(baseWorkUnitRepository.find(WorkUnitFilters(id = Some(fixture.workUnit.id))).map(_.get.revision), "unchanged revision") shouldEqual 1L
      }(Seq(() => fixtureOpt.foreach(cleanup)))
    }
    "roll back the appended event when the current-state write fails" in {
      var fixtureOpt = Option.empty[Fixture]
      runWithTeardown {
        val fixture = setupFixture(OrganizationUserRole.Manager, projectOwner = false)
        fixtureOpt = Some(fixture)
        val failure = new IllegalStateException("injected current-state failure")
        val failingRepository = new ObservedWorkUnitRepository(baseWorkUnitRepository, fixture.workUnit.id, Promise[Int](), None) {
          override def update(value: WorkUnit): DBIO[Option[WorkUnit]] = DBIO.failed(failure)
        }
        val result = await(
          newService(failingRepository)
            .replace(
              fixture.auth,
              fixture.organization.slug,
              fixture.workUnit.id,
              ReplaceWorkUnitOverrideRequest(0, Some(fixture.targetProject.id), WorkUnitTaskOverride("Clear"))
            )
            .attempt
            .unsafeToFuture(),
          "failed override write"
        )
        result shouldEqual Left(failure)
        awaitDb(baseWorkUnitRepository.find(WorkUnitFilters(id = Some(fixture.workUnit.id))), "unchanged source") shouldEqual Some(fixture.workUnit)
        awaitDb(historyRepository.count(fixture.workUnit.id, Long.MaxValue), "rolled-back history") shouldEqual 0
      } {
        Seq(() => fixtureOpt.foreach(cleanup))
      }
    }
    "return history count and rows from the same committed snapshot" in {
      var fixtureOpt    = Option.empty[Fixture]
      val counted       = Promise[Int]()
      val releaseReader = Promise[Unit]()
      var futures       = Vector.empty[(String, Future[_])]

      runWithTeardown {
        val fixture = setupFixture(OrganizationUserRole.Manager, projectOwner = false)
        fixtureOpt = Some(fixture)
        val writerService = newService(baseWorkUnitRepository)

        okDto(
          await(
            writerService
              .replace(
                fixture.auth,
                fixture.organization.slug,
                fixture.workUnit.id,
                ReplaceWorkUnitOverrideRequest(0L, None, WorkUnitTaskOverride("Set", Some("V1")))
              )
              .unsafeToFuture(),
            "first committed history version"
          )
        ).`override`.version shouldEqual 1L
        okDto(
          await(
            writerService
              .replace(
                fixture.auth,
                fixture.organization.slug,
                fixture.workUnit.id,
                ReplaceWorkUnitOverrideRequest(1L, None, WorkUnitTaskOverride("Set", Some("V2")))
              )
              .unsafeToFuture(),
            "second committed history version"
          )
        ).`override`.version shouldEqual 2L

        val gatedHistoryRepository = new GatedHistoryRepository(historyRepository, counted, releaseReader)
        val historyService =
          newService(baseWorkUnitRepository, gatedHistoryRepository)
        val historyFuture = historyService
          .history(fixture.auth, fixture.organization.slug, fixture.workUnit.id, 2L, pageSize = Some(25), pageNumber = Some(1))
          .unsafeToFuture()
        futures :+= "gated history read" -> historyFuture
        await(counted.future, "history count before concurrent commit") shouldEqual 2

        okDto(
          await(
            writerService
              .replace(
                fixture.auth,
                fixture.organization.slug,
                fixture.workUnit.id,
                ReplaceWorkUnitOverrideRequest(2L, None, WorkUnitTaskOverride("Set", Some("V3")))
              )
              .unsafeToFuture(),
            "third history version committed between count and list"
          )
        ).`override`.version shouldEqual 3L
        releaseReader.trySuccess(()) should beTrue

        val page = await(historyFuture, "history page from one snapshot") match {
          case Right(value) => value
          case Left(error)  => throw new AssertionError(s"expected history page, got $error")
        }
        page.count shouldEqual 2
        page.pageNumber shouldEqual 1
        page.pageSize shouldEqual 25
        page.upToVersion shouldEqual 2L
        page.results.map(_.version) shouldEqual Seq(2L, 1L)
      } {
        Seq[() => Unit] { () => releaseReader.trySuccess(()); () } ++ drain(futures) ++ Seq(() => fixtureOpt.foreach(cleanup))
      }
    }

    "archive a client without deadlocking a low-to-high project writer" in {
      var fixtureOpt = Option.empty[Fixture]
      val lowLocked  = Promise[Unit]()
      val releaseLow = Promise[Unit]()
      val writerPid  = Promise[Int]()
      val archivePid = Promise[Int]()
      var futures    = Vector.empty[(String, Future[_])]

      runWithTeardown {
        val fixture = setupFixture(OrganizationUserRole.Manager, projectOwner = false)
        fixtureOpt = Some(fixture)
        val Seq(lowProject, highProject) = Seq(fixture.sourceProject, fixture.targetProject).sortBy(_.id.toString)
        val writer = db.run((for {
          pid <- sql"select pg_backend_pid()".as[Int].head
          _ = writerPid.trySuccess(pid)
          _ <- projectRepository.find(ProjectFilters(id = Some(lowProject.id), includeArchived = true, forUpdate = true))
          _ = lowLocked.trySuccess(())
          _ <- DBIO.from(releaseLow.future)
          _ <- projectRepository.find(ProjectFilters(id = Some(highProject.id), includeArchived = true, forUpdate = true))
        } yield ()).transactionally)
        futures :+= "low-to-high project writer" -> writer
        await(lowLocked.future, "low project lock")

        val reverseProjectRepository = new ReverseProjectLockRepository(projectRepository, lowProject.id, highProject.id)
        val observedClientRepository = new ObservedClientRepository(clientRepository, fixture.client.id, archivePid)
        val archiveService = new ClientDeleteService(
          new PostgresIOTransactor(db),
          reverseProjectRepository,
          projectUserRepository,
          organizationRepository,
          organizationUserRepository,
          observedClientRepository
        )
        val archive = archiveService.delete(fixture.auth, fixture.organization.slug, fixture.client.id).unsafeToFuture()
        futures :+= "same-client archive" -> archive

        val blocker = await(writerPid.future, "project writer backend pid")
        val blocked = await(archivePid.future, "client archive backend pid")
        awaitBlockingRelationship(blocker, blocked)
        archive.isCompleted should beFalse
        releaseLow.trySuccess(()) should beTrue

        val (_, archiveResult) = await(writer.zip(archive), "project writer and same-client archive")
        archiveResult shouldEqual ClientDeleteResult.Deleted
      } {
        Seq[() => Unit] { () => releaseLow.trySuccess(()); () } ++ drain(futures) ++ Seq(() => fixtureOpt.foreach(cleanup))
      }
    }

    "bulk deassign two project-user rows without deadlocking a low-to-high writer" in {
      var fixtureOpt = Option.empty[Fixture]
      val lowLocked  = Promise[Unit]()
      val releaseLow = Promise[Unit]()
      val writerPid  = Promise[Int]()
      val bulkPid    = Promise[Int]()
      var futures    = Vector.empty[(String, Future[_])]

      runWithTeardown {
        val fixture = setupFixture(OrganizationUserRole.Employee, projectOwner = true)
        fixtureOpt = Some(fixture)
        awaitDb(
          DBIO
            .sequence(
              Seq(fixture.sourceProject, fixture.targetProject).map(project => projectRepository.update(project.copy(createdBy = fixture.author.id)))
            )
            .transactionally,
          "projects made eligible for bulk deassign"
        )
        val Seq(lowAssignment, highAssignment) =
          fixture.projectUsers.sortBy(value => (value.projectId.toString, value.userId.toString, value.id.toString))
        val writer = db.run((for {
          pid <- sql"select pg_backend_pid()".as[Int].head
          _ = writerPid.trySuccess(pid)
          _ <- projectUserRepository.find(
                 ProjectUserFilters(
                   projectIds = Some(Seq(lowAssignment.projectId)),
                   userId = Some(lowAssignment.userId),
                   includeArchived = true,
                   forUpdate = true
                 )
               )
          _ = lowLocked.trySuccess(())
          _ <- DBIO.from(releaseLow.future)
          _ <- projectUserRepository.find(
                 ProjectUserFilters(
                   projectIds = Some(Seq(highAssignment.projectId)),
                   userId = Some(highAssignment.userId),
                   includeArchived = true,
                   forUpdate = true
                 )
               )
        } yield ()).transactionally)
        futures :+= "low-to-high project-user writer" -> writer
        await(lowLocked.future, "low project-user lock")

        val observedProjectRepository    = new ObservedProjectListRepository(projectRepository, fixture.organization.id, bulkPid)
        val reverseProjectUserRepository = new ReverseProjectUserLockRepository(projectUserRepository, lowAssignment, highAssignment)
        val bulkService = new ProjectBulkDeassignService(
          new PostgresIOTransactor(db),
          clientRepository,
          observedProjectRepository,
          reverseProjectUserRepository,
          organizationRepository,
          organizationUserRepository
        )
        val bulk = bulkService
          .deassign(fixture.auth, fixture.organization.slug, fixture.actor.id, ProjectBulkDeassignRequest(clientType = None, clientId = None))
          .unsafeToFuture()
        futures :+= "two-row bulk deassign" -> bulk

        val blocker = await(writerPid.future, "project-user writer backend pid")
        val blocked = await(bulkPid.future, "bulk deassign backend pid")
        awaitBlockingRelationship(blocker, blocked)
        bulk.isCompleted should beFalse
        releaseLow.trySuccess(()) should beTrue

        val (_, bulkResult) = await(writer.zip(bulk), "project-user writer and bulk deassign")
        bulkResult match {
          case ProjectBulkDeassignResult.Deassigned(response) =>
            response.changedProjectIds.toSet shouldEqual Set(lowAssignment.projectId, highAssignment.projectId)
          case other => throw new AssertionError(s"expected Deassigned, got $other")
        }
      } {
        Seq[() => Unit] { () => releaseLow.trySuccess(()); () } ++ drain(futures) ++ Seq(() => fixtureOpt.foreach(cleanup))
      }
    }

    "serialize two revision-zero writes on the existing work-unit row into one Ok and one stale result" in {
      var fixtureOpt   = Option.empty[Fixture]
      val releaseFirst = Promise[Unit]()
      var requests     = Vector.empty[(String, Future[WorkUnitOverrideResult])]

      runWithTeardown {
        val fixture = setupFixture(OrganizationUserRole.Manager, projectOwner = false)
        fixtureOpt = Some(fixture)
        val firstPid        = Promise[Int]()
        val firstLocked     = Promise[Unit]()
        val secondPid       = Promise[Int]()
        val firstRepository = new ObservedWorkUnitRepository(baseWorkUnitRepository, fixture.workUnit.id, firstPid, Some(firstLocked -> releaseFirst))
        val secondRepository = new ObservedWorkUnitRepository(baseWorkUnitRepository, fixture.workUnit.id, secondPid, None)
        val firstService     = newService(firstRepository)
        val secondService    = newService(secondRepository)
        val firstRequest     = ReplaceWorkUnitOverrideRequest(0L, Some(fixture.targetProject.id), WorkUnitTaskOverride("Set", Some("FIRST")))
        val secondRequest    = ReplaceWorkUnitOverrideRequest(0L, None, WorkUnitTaskOverride("Set", Some("SECOND")))

        val first = firstService.replace(fixture.auth, fixture.organization.slug, fixture.workUnit.id, firstRequest).unsafeToFuture()
        requests :+= "first revision-zero write" -> first
        await(firstLocked.future, "first work-unit row lock")
        val second = secondService.replace(fixture.auth, fixture.organization.slug, fixture.workUnit.id, secondRequest).unsafeToFuture()
        requests :+= "second revision-zero write" -> second

        val blocker = await(firstPid.future, "first PostgreSQL backend pid")
        val blocked = await(secondPid.future, "second PostgreSQL backend pid")
        awaitBlockingRelationship(blocker, blocked)
        second.isCompleted should beFalse
        releaseFirst.trySuccess(()) should beTrue

        val results = await(first.zip(second), "both revision-zero writes")
        val winner  = okDto(results._1)
        winner.effectiveProjectClient.map(_.projectId) shouldEqual Some(fixture.targetProject.id)
        winner.effectiveTaskId.map(_.toString) shouldEqual Some("FIRST")
        winner.`override`.version shouldEqual 1L
        results._2 shouldEqual StaleRevision(1L)
        val current =
          awaitDb(baseWorkUnitRepository.find(WorkUnitFilters(id = Some(fixture.workUnit.id))).map(_.map(_.overrideState)), "current PM state")
        val history = awaitDb(historyRepository.list(fixture.workUnit.id, Long.MaxValue, None), "PM history")
        current.map(value => (value.projectId, value.taskId, value.version)) shouldEqual
          Some((Some(fixture.targetProject.id), Some(WorkUnitTaskId("FIRST")), 1L))
        history.map(_.version) shouldEqual Seq(1L)
        history.map(_.operation) shouldEqual Seq("Replace")
        history.head.before.get.hcursor.downField("source").get[String]("taskId").toOption shouldEqual fixture.workUnit.taskId.map(_.toString)
        history.head.before.get.hcursor.get[Boolean]("taskIdOverrideSet") shouldEqual Right(false)
        history.head.after.get.hcursor.downField("effective").downField("projectClient").get[String]("projectId") shouldEqual Right(
          fixture.targetProject.id.toString
        )
        history.head.after.get.hcursor.downField("effective").get[String]("taskId") shouldEqual Right("FIRST")
        history.map(_.version.toString.toLong) shouldEqual (1L to history.size.toLong).reverse
      } {
        Seq[() => Unit] { () => releaseFirst.trySuccess(()); () } ++
          drain(requests) ++
          Seq(() => fixtureOpt.foreach(cleanup))
      }
    }

    "preserve a source update that waits behind a PM classification write" in {
      var fixtureOpt = Option.empty[Fixture]
      val releasePm  = Promise[Unit]()
      var futures    = Vector.empty[(String, Future[_])]

      runWithTeardown {
        val fixture = setupFixture(OrganizationUserRole.Manager, projectOwner = false)
        fixtureOpt = Some(fixture)
        val pmPid        = Promise[Int]()
        val pmLocked     = Promise[Unit]()
        val updatePid    = Promise[Int]()
        val pmRepository = new ObservedWorkUnitRepository(baseWorkUnitRepository, fixture.workUnit.id, pmPid, Some(pmLocked -> releasePm))
        val service      = newService(pmRepository)
        val pmFuture = service
          .replace(
            fixture.auth,
            fixture.organization.slug,
            fixture.workUnit.id,
            ReplaceWorkUnitOverrideRequest(0L, None, WorkUnitTaskOverride("Set", Some("PM")))
          )
          .unsafeToFuture()
        futures :+= "PM write" -> pmFuture
        await(pmLocked.future, "PM work-unit row lock")

        val updatedDescription = WorkUnitDescription("source update survives")
        val updatedAt          = WorkUnitModifiedAt(Instant.parse("2026-08-06T08:00:00Z"))
        val updateFuture = db.run((for {
          pid <- sql"select pg_backend_pid()".as[Int].head
          _ = updatePid.trySuccess(pid)
          locked <- baseWorkUnitRepository
                      .find(WorkUnitFilters(id = Some(fixture.workUnit.id), organizationId = Some(fixture.organization.id), forUpdate = true))
                      .map(_.getOrElse(throw new AssertionError("work unit disappeared")))
          updated = locked.copy(description = updatedDescription, modifiedAt = updatedAt)
          _ <- baseWorkUnitRepository.update(updated)
        } yield ()).transactionally)
        futures :+= "source update" -> updateFuture

        val blocker = await(pmPid.future, "PM backend pid")
        val blocked = await(updatePid.future, "source-update backend pid")
        awaitBlockingRelationship(blocker, blocked)
        updateFuture.isCompleted should beFalse
        releasePm.trySuccess(()) should beTrue

        val pmResult = await(pmFuture.zip(updateFuture), "PM and source writes")._1
        okDto(pmResult).projectClient.map(_.projectId) shouldEqual Some(fixture.sourceProject.id)
        okDto(pmResult).effectiveTaskId.map(_.toString) shouldEqual Some("PM")
        okDto(pmResult).`override`.version shouldEqual 1L
        val storedSource = awaitDb(
          baseWorkUnitRepository.find(
            WorkUnitFilters(id = Some(fixture.workUnit.id), organizationId = Some(fixture.organization.id), includeArchived = true)
          ),
          "stored source work unit"
        )
        val current = awaitDb(
          baseWorkUnitRepository.find(WorkUnitFilters(id = Some(fixture.workUnit.id))).map(_.map(_.overrideState)),
          "current PM state after source update"
        )
        val history = awaitDb(historyRepository.list(fixture.workUnit.id, Long.MaxValue, None), "history after source update")
        storedSource.map(_.description) shouldEqual Some(updatedDescription)
        storedSource.map(_.modifiedAt) shouldEqual Some(updatedAt)
        storedSource.map(
          _.copy(
            description = fixture.workUnit.description,
            modifiedAt = fixture.workUnit.modifiedAt,
            revision = 0,
            overrideState = WorkUnitOverrideState()
          )
        ) shouldEqual Some(fixture.workUnit)
        storedSource.map(_.revision) shouldEqual Some(2L)
        current.map(value => (value.projectId, value.taskId, value.version)) shouldEqual
          Some((None, Some(WorkUnitTaskId("PM")), 1L))
        history.map(_.version) shouldEqual Seq(1L)
        history.map(_.operation) shouldEqual Seq("Replace")
        history.head.before.get.hcursor.downField("source").get[String]("taskId").toOption shouldEqual fixture.workUnit.taskId.map(_.toString)
        history.head.before.get.hcursor.get[Boolean]("taskIdOverrideSet") shouldEqual Right(false)
        history.head.after.get.hcursor.downField("effective").get[String]("taskId") shouldEqual Right("PM")
      } {
        Seq[() => Unit] { () => releasePm.trySuccess(()); () } ++ drain(futures) ++ Seq(() => fixtureOpt.foreach(cleanup))
      }
    }

    "wait for target project archival and then reject the write after locked revalidation" in {
      var fixtureOpt     = Option.empty[Fixture]
      val releaseArchive = Promise[Unit]()
      var futures        = Vector.empty[(String, Future[_])]

      runWithTeardown {
        val fixture = setupFixture(OrganizationUserRole.Manager, projectOwner = false)
        fixtureOpt = Some(fixture)
        val archivePid    = Promise[Int]()
        val archiveLocked = Promise[Unit]()
        val archiveFuture = db.run((for {
          pid <- sql"select pg_backend_pid()".as[Int].head
          _ = archivePid.trySuccess(pid)
          project <- projectRepository
                       .find(
                         ProjectFilters(
                           id = Some(fixture.targetProject.id),
                           organizationIds = Some(Seq(fixture.organization.id)),
                           includeArchived = true,
                           forUpdate = true
                         )
                       )
                       .map(_.getOrElse(throw new AssertionError("target project disappeared")))
          _ = archiveLocked.trySuccess(())
          _ <- DBIO.from(releaseArchive.future)
          _ <- projectRepository.update(project.copy(archivedAt = Some(ProjectArchivedAt(Instant.now())), archivedBy = Some(fixture.actor.id)))
        } yield ()).transactionally)
        futures :+= "target archive" -> archiveFuture
        await(archiveLocked.future, "target project lock")

        val mutationPid = Promise[Int]()
        val service     = newService(new ObservedWorkUnitRepository(baseWorkUnitRepository, fixture.workUnit.id, mutationPid, None))
        val mutation = service
          .replace(
            fixture.auth,
            fixture.organization.slug,
            fixture.workUnit.id,
            ReplaceWorkUnitOverrideRequest(0L, Some(fixture.targetProject.id), WorkUnitTaskOverride("Inherit"))
          )
          .unsafeToFuture()
        futures :+= "PM write behind project archive" -> mutation

        val blocker = await(archivePid.future, "archive backend pid")
        val blocked = await(mutationPid.future, "PM mutation backend pid")
        awaitBlockingRelationship(blocker, blocked)
        mutation.isCompleted should beFalse
        releaseArchive.trySuccess(()) should beTrue

        await(archiveFuture, "target archive commit")
        await(mutation, "PM write revalidation") shouldEqual ProjectUnavailable
        awaitDb(
          baseWorkUnitRepository.find(WorkUnitFilters(id = Some(fixture.workUnit.id))).map(_.map(_.overrideState)),
          "current state after rejected target"
        ) shouldEqual Some(WorkUnitOverrideState())
        awaitDb(historyRepository.count(fixture.workUnit.id, Long.MaxValue), "history after rejected target") shouldEqual 0
      } {
        Seq[() => Unit] { () => releaseArchive.trySuccess(()); () } ++ drain(futures) ++ Seq(() => fixtureOpt.foreach(cleanup))
      }
    }

    "wait for target-owner deassignment and then reject the write after fresh role revalidation" in {
      var fixtureOpt      = Option.empty[Fixture]
      val releaseDeassign = Promise[Unit]()
      var futures         = Vector.empty[(String, Future[_])]

      runWithTeardown {
        val fixture = setupFixture(OrganizationUserRole.Employee, projectOwner = true)
        fixtureOpt = Some(fixture)
        val targetAssignment = fixture.projectUsers.find(_.projectId == fixture.targetProject.id).get
        val deassignPid      = Promise[Int]()
        val deassignLocked   = Promise[Unit]()
        val deassignFuture = db.run((for {
          pid <- sql"select pg_backend_pid()".as[Int].head
          _ = deassignPid.trySuccess(pid)
          assignment <- projectUserRepository
                          .find(
                            ProjectUserRepository.ProjectUserFilters(
                              projectIds = Some(Seq(targetAssignment.projectId)),
                              userId = Some(fixture.actor.id),
                              includeArchived = true,
                              forUpdate = true
                            )
                          )
                          .map(_.getOrElse(throw new AssertionError("target assignment disappeared")))
          _ = deassignLocked.trySuccess(())
          _ <- DBIO.from(releaseDeassign.future)
          _ <- projectUserRepository.update(assignment.copy(archivedAt = Some(ProjectUserArchivedAt(Instant.now()))))
        } yield ()).transactionally)
        futures :+= "target owner deassignment" -> deassignFuture
        await(deassignLocked.future, "target assignment lock")

        val mutationPid = Promise[Int]()
        val service     = newService(new ObservedWorkUnitRepository(baseWorkUnitRepository, fixture.workUnit.id, mutationPid, None))
        val mutation = service
          .replace(
            fixture.auth,
            fixture.organization.slug,
            fixture.workUnit.id,
            ReplaceWorkUnitOverrideRequest(0L, Some(fixture.targetProject.id), WorkUnitTaskOverride("Inherit"))
          )
          .unsafeToFuture()
        futures :+= "PM write behind target deassignment" -> mutation

        val blocker = await(deassignPid.future, "deassignment backend pid")
        val blocked = await(mutationPid.future, "PM mutation backend pid")
        awaitBlockingRelationship(blocker, blocked)
        mutation.isCompleted should beFalse
        releaseDeassign.trySuccess(()) should beTrue

        await(deassignFuture, "target deassignment commit")
        await(mutation, "PM write after target deassignment") shouldEqual ProjectUnavailable
        awaitDb(
          baseWorkUnitRepository.find(WorkUnitFilters(id = Some(fixture.workUnit.id))).map(_.map(_.overrideState)),
          "current after revoked target"
        ) shouldEqual Some(WorkUnitOverrideState())
        awaitDb(historyRepository.count(fixture.workUnit.id, Long.MaxValue), "history after revoked target") shouldEqual 0
      } {
        Seq[() => Unit] { () => releaseDeassign.trySuccess(()); () } ++ drain(futures) ++ Seq(() => fixtureOpt.foreach(cleanup))
      }
    }
  }

  step(closeDatabases())

  private def newService(workUnitRepository: WorkUnitRepository, history: WorkUnitOverrideHistoryRepository = historyRepository)
    : WorkUnitOverrideService = {
    val dtoService = new WorkUnitDtoService(clientRepository, userRepository, projectRepository)
    new WorkUnitOverrideService(
      new PostgresIOTransactor(db),
      organizationRepository,
      organizationUserRepository,
      projectRepository,
      projectUserRepository,
      clientRepository,
      userRepository,
      workUnitRepository,
      history,
      dtoService
    )
  }

  private def setupFixture(role: OrganizationUserRole, projectOwner: Boolean): Fixture = {
    val actor  = allGenerators[User].normal.generate.copy(isSuperAdmin = UserIsSuperAdmin(false), archivedAt = None)
    val author = allGenerators[User].normal.generate.copy(id = UserId(UUID.randomUUID()), isSuperAdmin = UserIsSuperAdmin(false), archivedAt = None)
    val organization = allGenerators[Organization].normal.generate.copy(createdBy = actor.id, modifiedBy = actor.id, archivedAt = None)
    val client = allGenerators[Client].normal.generate.copy(
      organizationId = organization.id,
      createdBy = actor.id,
      modifiedBy = actor.id,
      archivedAt = None,
      archivedBy = None
    )
    val sourceProject = allGenerators[Project].normal.generate.copy(
      organizationId = organization.id,
      clientId = client.id,
      createdBy = actor.id,
      modifiedBy = actor.id,
      archivedAt = None,
      archivedBy = None
    )
    val targetProject = allGenerators[Project].normal.generate.copy(
      organizationId = organization.id,
      clientId = client.id,
      createdBy = actor.id,
      modifiedBy = actor.id,
      archivedAt = None,
      archivedBy = None
    )
    val membership = OrganizationUser(OrganizationUserId(UUID.randomUUID()), organization.id, actor.id, Seq(role), None)
    val projectUsers = Option
      .when(projectOwner)(
        Seq(sourceProject, targetProject).map(project =>
          ProjectUser(ProjectUserId(UUID.randomUUID()), project.id, actor.id, ProjectRole.Owner, ProjectUserCreatedAt(Instant.now()), None)
        )
      )
      .getOrElse(Seq.empty)
    val workUnit = allGenerators[WorkUnit].normal.generate.copy(
      id = WorkUnitId(UUID.randomUUID()),
      userId = author.id,
      organizationId = organization.id,
      projectId = sourceProject.id,
      duration = WorkUnitDuration(Duration.ofHours(1)),
      entryType = WorkUnitEntryType.Worklog,
      state = WorkUnitState.Active,
      archivedAt = None
    )

    awaitDb(
      (for {
        storedActor        <- userRepository.insert(actor)
        storedAuthor       <- userRepository.insert(author)
        storedOrganization <- organizationRepository.insert(organization)
        storedMembership   <- organizationUserRepository.upsert(membership)
        storedClient       <- clientRepository.insert(client)
        storedSource       <- projectRepository.insert(sourceProject)
        storedTarget       <- projectRepository.insert(targetProject)
        storedProjectUsers <- projectUserRepository.insert(projectUsers)
        storedWorkUnit     <- baseWorkUnitRepository.insert(workUnit)
      } yield new Fixture(
        storedActor,
        storedAuthor,
        AuthContext(storedActor.id, UserIsSuperAdmin(false), Nil, Set.empty, Nil, Instant.now(), fresh = true),
        storedOrganization,
        storedMembership,
        storedClient,
        storedSource,
        storedTarget,
        storedProjectUsers,
        storedWorkUnit
      )).transactionally,
      "committed PM concurrency fixture"
    )
  }

  private def awaitBlockingRelationship(blockerPid: Int, blockedPid: Int): Unit = {
    val deadline = observationTimeout.fromNow
    def observe(): Future[Boolean] =
      observerDb
        .run(sql"select $blockerPid = any(pg_blocking_pids($blockedPid))".as[Boolean].head)
        .flatMap {
          case true                            => Future.successful(true)
          case false if deadline.hasTimeLeft() => observe()
          case false                           => Future.successful(false)
        }

    if (!await(observe(), s"PostgreSQL backend $blockedPid blocked by $blockerPid"))
      throw new AssertionError(s"PostgreSQL backend $blockedPid was not blocked by $blockerPid")
  }

  private def cleanup(fixture: Fixture): Unit = {
    val workUnitId     = fixture.workUnit.id.asInstanceOf[UUID]
    val organizationId = fixture.organization.id.asInstanceOf[UUID]
    val actorId        = fixture.actor.id.asInstanceOf[UUID]
    val authorId       = fixture.author.id.asInstanceOf[UUID]
    val sourceId       = fixture.sourceProject.id.asInstanceOf[UUID]
    val targetId       = fixture.targetProject.id.asInstanceOf[UUID]
    val clientId       = fixture.client.id.asInstanceOf[UUID]
    awaitDb(
      DBIO
        .seq(
          sqlu"delete from work_unit_override_history where work_unit_id = $workUnitId",
          sqlu"delete from work_unit where id = $workUnitId",
          sqlu"delete from project_user where project_id in ($sourceId, $targetId) and user_id = $actorId",
          sqlu"delete from organization_user where organization_id = $organizationId and user_id = $actorId",
          sqlu"delete from project where id in ($sourceId, $targetId)",
          sqlu"delete from client where id = $clientId",
          sqlu"delete from organization where id = $organizationId",
          sqlu"""delete from "user" where id in ($actorId, $authorId)"""
        )
        .transactionally,
      "committed PM concurrency cleanup"
    )
  }

  private def drain(futures: Seq[(String, Future[_])]): Seq[() => Unit] =
    futures.map { case (label, future) => () => { await(future, s"teardown of $label"); () } }

  private def runWithTeardown[A](body: => A)(teardown: => Seq[() => Unit]): A = {
    val bodyResult = capture(body)
    val teardownFailures = capture(teardown) match {
      case Left(error)  => Seq(error)
      case Right(steps) => steps.flatMap(step => capture(step()).left.toOption)
    }
    bodyResult match {
      case Left(error) =>
        teardownFailures.filterNot(_ eq error).foreach(error.addSuppressed)
        throw error
      case Right(value) =>
        teardownFailures.headOption.foreach(throw _)
        value
    }
  }

  private def capture[A](action: => A): Either[Throwable, A] =
    try Right(action)
    catch { case NonFatal(error) => Left(error) }

  private def await[A](future: Future[A], clue: String): A =
    try Await.result(future, requestTimeout)
    catch {
      case timeout: TimeoutException => throw new AssertionError(s"Timed out waiting for $clue after $requestTimeout", timeout)
    }

  private def awaitDb[A](action: DBIO[A], clue: String): A =
    try Await.result(db.run(action), databaseTimeout)
    catch {
      case timeout: TimeoutException => throw new AssertionError(s"Timed out waiting for $clue after $databaseTimeout", timeout)
    }

  private def closeDatabases(): Unit = {
    db.close()
    observerDb.close()
  }

  private final class Fixture(
    val actor: User,
    val author: User,
    val auth: AuthContext,
    val organization: Organization,
    val membership: OrganizationUser,
    val client: Client,
    val sourceProject: Project,
    val targetProject: Project,
    val projectUsers: Seq[ProjectUser],
    val workUnit: WorkUnit)

  private def okDto(result: WorkUnitOverrideResult): WorkUnitDto = result match {
    case Ok(dto) => dto
    case other   => throw new AssertionError(s"expected Ok, got $other")
  }

  private class ObservedWorkUnitRepository(
    delegate: WorkUnitRepository,
    observedWorkUnitId: WorkUnitId,
    pid: Promise[Int],
    gateAfterLock: Option[(Promise[Unit], Promise[Unit])])
      extends WorkUnitRepository {
    override def insert(workUnit: WorkUnit): DBIO[WorkUnit]         = delegate.insert(workUnit)
    override def update(workUnit: WorkUnit): DBIO[Option[WorkUnit]] = delegate.update(workUnit)
    override def find(filter: WorkUnitFilters): DBIO[Option[WorkUnit]] =
      if (filter.forUpdate && filter.id.contains(observedWorkUnitId))
        for {
          backendPid <- sql"select pg_backend_pid()".as[Int].head
          _ = pid.trySuccess(backendPid)
          result <- delegate.find(filter)
          _ <- gateAfterLock match {
                 case Some((locked, release)) =>
                   locked.trySuccess(())
                   DBIO.from(release.future)
                 case None => DBIO.successful(())
               }
        } yield result
      else delegate.find(filter)
    override def list(filter: WorkUnitFilters): DBIO[Seq[WorkUnit]] = delegate.list(filter)
    override def listWithClientId(filter: WorkUnitFilters): DBIO[Seq[WorkUnitRepository.WorkUnitWithClientId]] =
      delegate.listWithClientId(filter)
    override def count(filter: WorkUnitFilters): DBIO[Int] = delegate.count(filter)
  }

  private final class GatedHistoryRepository(
    delegate: WorkUnitOverrideHistoryRepository,
    counted: Promise[Int],
    releaseReader: Promise[Unit])
      extends WorkUnitOverrideHistoryRepository {
    override def insert(value: WorkUnitOverrideHistory): DBIO[WorkUnitOverrideHistory] = delegate.insert(value)
    override def find(
      workUnitId: WorkUnitId,
      id: UUID,
      upToVersion: Long
    ): DBIO[Option[WorkUnitOverrideHistory]] =
      delegate.find(workUnitId, id, upToVersion)
    override def reversals(
      workUnitId: WorkUnitId,
      eventIds: Seq[UUID],
      upToVersion: Long
    ): DBIO[Seq[WorkUnitOverrideHistory]] =
      delegate.reversals(workUnitId, eventIds, upToVersion)
    override def list(
      id: WorkUnitId,
      upToVersion: Long,
      page: Option[pl.iterators.epoca.utils.db.PageSizeAndNumber]
    ): DBIO[Seq[WorkUnitOverrideHistory]] =
      delegate.list(id, upToVersion, page)
    override def count(id: WorkUnitId, upToVersion: Long): DBIO[Int] =
      delegate.count(id, upToVersion).flatMap { value =>
        counted.trySuccess(value)
        DBIO.from(releaseReader.future).map(_ => value)
      }
  }

  private final class ObservedClientRepository(
    delegate: ClientRepository,
    clientId: pl.iterators.epoca.client.domain.ClientDomain.ClientId,
    pid: Promise[Int])
      extends ClientRepository {
    override def insert(client: Client): DBIO[Client]           = delegate.insert(client)
    override def update(client: Client): DBIO[Option[Client]]   = delegate.update(client)
    override def list(filter: ClientFilters): DBIO[Seq[Client]] = delegate.list(filter)
    override def count(filter: ClientFilters): DBIO[Int]        = delegate.count(filter)
    override def lockForMutation(requestedClientId: pl.iterators.epoca.client.domain.ClientDomain.ClientId): DBIO[Unit] =
      if (requestedClientId == clientId)
        for {
          backendPid <- sql"select pg_backend_pid()".as[Int].head
          _ = pid.trySuccess(backendPid)
          _ <- delegate.lockForMutation(requestedClientId)
        } yield ()
      else delegate.lockForMutation(requestedClientId)
    override def find(filter: ClientFilters): DBIO[Option[Client]] = delegate.find(filter)
  }

  private final class ReverseProjectLockRepository(
    delegate: ProjectRepository,
    low: ProjectId,
    high: ProjectId)
      extends ProjectRepository {
    override def insert(project: Project): DBIO[Project]                 = delegate.insert(project)
    override def update(project: Project): DBIO[Option[Project]]         = delegate.update(project)
    override def find(filter: ProjectFilters): DBIO[Option[Project]]     = delegate.find(filter)
    override def count(filter: ProjectFilters): DBIO[Int]                = delegate.count(filter)
    override def lockOrdered(filter: ProjectFilters): DBIO[Seq[Project]] = delegate.lockOrdered(filter)
    override def lockTableForReference(): DBIO[Unit]                     = delegate.lockTableForReference()
    override def archive(
      filter: ProjectFilters,
      when: ProjectArchivedAt,
      who: UserId
    ): DBIO[Int] = delegate.archive(filter, when, who)
    override def list(filter: ProjectFilters): DBIO[Seq[Project]] =
      if (filter.forUpdate && filter.clientIds.nonEmpty)
        for {
          highProject <- delegate
                           .find(ProjectFilters(id = Some(high), includeArchived = true, forUpdate = true))
                           .map(_.getOrElse(throw new AssertionError("high project disappeared")))
          lowProject <- delegate
                          .find(ProjectFilters(id = Some(low), includeArchived = true, forUpdate = true))
                          .map(_.getOrElse(throw new AssertionError("low project disappeared")))
        } yield Seq(highProject, lowProject)
      else delegate.list(filter)
  }

  private final class ObservedProjectListRepository(
    delegate: ProjectRepository,
    organizationId: OrganizationId,
    pid: Promise[Int])
      extends ProjectRepository {
    override def insert(project: Project): DBIO[Project]                 = delegate.insert(project)
    override def update(project: Project): DBIO[Option[Project]]         = delegate.update(project)
    override def find(filter: ProjectFilters): DBIO[Option[Project]]     = delegate.find(filter)
    override def count(filter: ProjectFilters): DBIO[Int]                = delegate.count(filter)
    override def lockOrdered(filter: ProjectFilters): DBIO[Seq[Project]] = delegate.lockOrdered(filter)
    override def lockTableForReference(): DBIO[Unit]                     = delegate.lockTableForReference()
    override def archive(
      filter: ProjectFilters,
      when: ProjectArchivedAt,
      who: UserId
    ): DBIO[Int] = delegate.archive(filter, when, who)
    override def list(filter: ProjectFilters): DBIO[Seq[Project]] =
      if (filter.organizationIds.exists(_.contains(organizationId)) && filter.notCreatedBy.nonEmpty)
        for {
          backendPid <- sql"select pg_backend_pid()".as[Int].head
          _ = pid.trySuccess(backendPid)
          result <- delegate.list(filter)
        } yield result
      else delegate.list(filter)
  }

  private final class ReverseProjectUserLockRepository(
    delegate: ProjectUserRepository,
    low: ProjectUser,
    high: ProjectUser)
      extends ProjectUserRepository {
    override def insert(projectUser: ProjectUser): DBIO[ProjectUser]             = delegate.insert(projectUser)
    override def insert(projectUsers: Seq[ProjectUser]): DBIO[Seq[ProjectUser]]  = delegate.insert(projectUsers)
    override def update(projectUser: ProjectUser): DBIO[Option[ProjectUser]]     = delegate.update(projectUser)
    override def find(filter: ProjectUserFilters): DBIO[Option[ProjectUser]]     = delegate.find(filter)
    override def list(filter: ProjectUserFilters): DBIO[Seq[ProjectUser]]        = delegate.list(filter)
    override def lockOrdered(filter: ProjectUserFilters): DBIO[Seq[ProjectUser]] = delegate.lockOrdered(filter)
    override def bulkArchiveAt(ids: Seq[ProjectUserId], at: ProjectUserArchivedAt): DBIO[Int] =
      if (ids.toSet == Set(low.id, high.id))
        for {
          _ <- delegate.find(
                 ProjectUserFilters(projectIds = Some(Seq(high.projectId)), userId = Some(high.userId), includeArchived = true, forUpdate = true)
               )
          _ <- delegate.find(
                 ProjectUserFilters(projectIds = Some(Seq(low.projectId)), userId = Some(low.userId), includeArchived = true, forUpdate = true)
               )
          count <- delegate.bulkArchiveAt(ids, at)
        } yield count
      else delegate.bulkArchiveAt(ids, at)
  }
}
