package pl.iterators.epoca.workunit.services

import cats.effect.unsafe.IORuntime
import org.specs2.mutable.SpecificationLike
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.client.domain.Client
import pl.iterators.epoca.client.repositories.{ClientRepository, ClientRepositoryImpl}
import pl.iterators.epoca.export.domain.SpreadsheetDomain.SpreadsheetFormat
import pl.iterators.epoca.export.services.ExportWorkUnitService
import pl.iterators.epoca.export.services.ExportWorkUnitService.{ExportWorkUnitColumnNames, ExportWorkUnitRequest, ExportWorkUnitResult}
import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationId, OrganizationUserId, OrganizationUserRole}
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.{
  OrganizationRepository,
  OrganizationRepositoryImpl,
  OrganizationUserRepository,
  OrganizationUserRepositoryImpl
}
import pl.iterators.epoca.project.domain.ProjectDomain.{ProjectName, ProjectRole, ProjectUserArchivedAt, ProjectUserCreatedAt, ProjectUserId}
import pl.iterators.epoca.project.domain.{Project, ProjectUser}
import pl.iterators.epoca.project.repositories.ProjectUserRepository.ProjectUserFilters
import pl.iterators.epoca.project.repositories.{ProjectRepository, ProjectRepositoryImpl, ProjectUserRepository, ProjectUserRepositoryImpl}
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.domain.UserDomain.{UserAccountType, UserId, UserIsSuperAdmin}
import pl.iterators.epoca.user.repositories.{UserRepository, UserRepositoryImpl}
import pl.iterators.epoca.utils.Generators
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.workunit.domain.WorkUnitDomain._
import pl.iterators.epoca.workunit.domain.{WorkUnit, WorkUnitOverrideState}
import pl.iterators.epoca.workunit.repositories.WorkUnitRepository.{WorkUnitFilters, WorkUnitWithClientId}
import pl.iterators.epoca.workunit.repositories.{WorkUnitOrderRepository, WorkUnitOrderRepositoryImpl, WorkUnitRepository, WorkUnitRepositoryImpl}
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

class WorkUnitPmExportConcurrencySpec extends SpecificationLike with Generators {
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
  private val workUnitRepository: WorkUnitRepository                 = new WorkUnitRepositoryImpl()
  private val workUnitOrderRepository: WorkUnitOrderRepository       = new WorkUnitOrderRepositoryImpl()
  private val workUnitDtoService: WorkUnitDtoService =
    new WorkUnitDtoService(clientRepository, userRepository, projectRepository)

  "worklog export authorization" should {
    "restrict export to own rows when a management revocation wins before the authorization lock" in {
      var fixtureOpt   = Option.empty[Fixture]
      val accessLocked = Promise[Unit]()
      val release      = Promise[Unit]()
      val updaterPid   = Promise[Int]()
      val exportPid    = Promise[Int]()
      var futures      = Vector.empty[(String, Future[_])]

      runWithTeardown {
        val fixture = setupFixture(OrganizationUserRole.Manager, projectOwner = false)
        fixtureOpt = Some(fixture)

        val accessChange = db.run((for {
          pid <- sql"select pg_backend_pid()".as[Int].head
          _ = updaterPid.trySuccess(pid)
          _ <- organizationUserRepository.find(
                 OrganizationUserFilters(
                   organizationId = Some(fixture.organization.id),
                   userId = Some(fixture.actor.id),
                   includeArchived = true,
                   forUpdate = true
                 )
               )
          _ = accessLocked.trySuccess(())
          _      <- DBIO.from(release.future)
          result <- organizationUserRepository.upsert(fixture.membership.copy(roles = Seq(OrganizationUserRole.Employee)))
        } yield result).transactionally)
        futures :+= "access change" -> accessChange
        await(accessLocked.future, "access row lock")

        val observedOrganizationUsers =
          new ObservedOrganizationUserRepository(organizationUserRepository, fixture.organization.id, fixture.actor.id, exportPid)
        val exportFuture = newExportService(organizationUsers = observedOrganizationUsers)
          .export(fixture.auth, fixture.organization.slug, exportRequest, SpreadsheetFormat.Csv)
          .unsafeToFuture()
        futures :+= "worklog export" -> exportFuture

        awaitBlockingRelationship(await(updaterPid.future, "access updater backend pid"), await(exportPid.future, "export backend pid"))
        release.trySuccess(()) should beTrue

        val csv = csvBody(await(exportFuture, "worklog export after committed access change"))
        csv.contains("PM-TASK-OLD") should beTrue
        csv.contains("OTHER-CORRECTED") should beFalse
        await(accessChange, "committed access change").roles shouldEqual Seq(OrganizationUserRole.Employee)
      } {
        Seq[() => Unit] { () => release.trySuccess(()); () } ++ drain(futures) ++ Seq(() => fixtureOpt.foreach(cleanup))
      }
    }

    "keep a legal snapshot when the export locks win and block a later access change" in {
      var fixtureOpt    = Option.empty[Fixture]
      val exportLocked  = Promise[Unit]()
      val releaseExport = Promise[Unit]()
      val exportPid     = Promise[Int]()
      val updaterPid    = Promise[Int]()
      var futures       = Vector.empty[(String, Future[_])]

      runWithTeardown {
        val fixture = setupFixture(OrganizationUserRole.Manager, projectOwner = false)
        fixtureOpt = Some(fixture)
        val gatedProjectUsers =
          new GatedProjectUserLockRepository(projectUserRepository, fixture.actor.id, exportPid, exportLocked, releaseExport)
        val exportFuture = newExportService(projectUsers = gatedProjectUsers)
          .export(fixture.auth, fixture.organization.slug, exportRequest, SpreadsheetFormat.Csv)
          .unsafeToFuture()
        futures :+= "worklog export" -> exportFuture
        await(exportLocked.future, "complete authorization locks")

        val accessChange = db.run((for {
          pid <- sql"select pg_backend_pid()".as[Int].head
          _ = updaterPid.trySuccess(pid)
          result <- organizationUserRepository.upsert(fixture.membership.copy(roles = Seq(OrganizationUserRole.Employee)))
        } yield result).transactionally)
        futures :+= "later access change" -> accessChange

        awaitBlockingRelationship(await(exportPid.future, "export backend pid"), await(updaterPid.future, "access updater backend pid"))
        accessChange.isCompleted should beFalse
        releaseExport.trySuccess(()) should beTrue

        val csv = csvBody(await(exportFuture, "worklog export from legal snapshot"))
        csv.contains("PM-TASK-OLD") should beTrue
        await(accessChange, "access change after export commit").roles shouldEqual Seq(OrganizationUserRole.Employee)
      } {
        Seq[() => Unit] { () => releaseExport.trySuccess(()); () } ++ drain(futures) ++ Seq(() => fixtureOpt.foreach(cleanup))
      }
    }

    "preserve self-export without other users after Project Owner access is revoked" in {
      var fixtureOpt      = Option.empty[Fixture]
      val assignmentsHeld = Promise[Unit]()
      val releaseRevoke   = Promise[Unit]()
      val revokerPid      = Promise[Int]()
      val exportPid       = Promise[Int]()
      var futures         = Vector.empty[(String, Future[_])]

      runWithTeardown {
        val fixture = setupFixture(OrganizationUserRole.Employee, projectOwner = true)
        fixtureOpt = Some(fixture)

        val revoke = db.run((for {
          pid <- sql"select pg_backend_pid()".as[Int].head
          _ = revokerPid.trySuccess(pid)
          _ <- projectUserRepository.lockOrdered(ProjectUserFilters(userId = Some(fixture.actor.id), includeArchived = true))
          _ = assignmentsHeld.trySuccess(())
          _     <- DBIO.from(releaseRevoke.future)
          count <- projectUserRepository.bulkArchiveAt(fixture.projectUsers.map(_.id), ProjectUserArchivedAt(Instant.now()))
        } yield count).transactionally)
        futures :+= "Project Owner revoke" -> revoke
        await(assignmentsHeld.future, "Project Owner assignment locks")

        val observedProjectUsers = new ObservedProjectUserLockRepository(projectUserRepository, fixture.actor.id, exportPid)
        val exportFuture = newExportService(projectUsers = observedProjectUsers)
          .export(fixture.auth, fixture.organization.slug, exportRequest, SpreadsheetFormat.Csv)
          .unsafeToFuture()
        futures :+= "worklog export" -> exportFuture

        awaitBlockingRelationship(await(revokerPid.future, "assignment revoker backend pid"), await(exportPid.future, "export backend pid"))
        releaseRevoke.trySuccess(()) should beTrue

        await(revoke, "committed Project Owner revoke") shouldEqual fixture.projectUsers.size
        val csv = csvBody(await(exportFuture, "worklog export after Project Owner revoke"))
        csv.contains("PM-TASK-OLD") should beTrue
        csv.contains("OTHER-CORRECTED") should beFalse
      } {
        Seq[() => Unit] { () => releaseRevoke.trySuccess(()); () } ++ drain(futures) ++ Seq(() => fixtureOpt.foreach(cleanup))
      }
    }

    "return one effective value set from one snapshot during a concurrent source and override change" in {
      var fixtureOpt    = Option.empty[Fixture]
      val sourceRead    = Promise[Unit]()
      val releaseExport = Promise[Unit]()
      var futures       = Vector.empty[(String, Future[_])]

      runWithTeardown {
        val fixture = setupFixture(OrganizationUserRole.Manager, projectOwner = false)
        fixtureOpt = Some(fixture)
        val gatedWorkUnits =
          new GatedWorkUnitListRepository(workUnitRepository, fixture.organization.id, sourceRead, releaseExport)
        val exportFuture = newExportService(workUnits = gatedWorkUnits)
          .export(fixture.auth, fixture.organization.slug, exportRequest, SpreadsheetFormat.Csv)
          .unsafeToFuture()
        futures :+= "worklog export" -> exportFuture
        await(sourceRead.future, "source fields read")

        val workUnitId = fixture.workUnit.id.asInstanceOf[UUID]
        awaitDb(
          DBIO
            .seq(
              sqlu"update work_unit set task_id = 'SOURCE-TASK-NEW', override_task_id = 'PM-TASK-NEW', override_version = 2, override_last_changed_at = now() where id = $workUnitId"
            )
            .transactionally,
          "concurrent source and current PM change"
        )
        releaseExport.trySuccess(()) should beTrue

        val csv = csvBody(await(exportFuture, "worklog export from one snapshot"))
        csv.contains("SOURCE-TASK-OLD") should beFalse
        csv.contains("PM-TASK-OLD") should beTrue
        csv.contains("SOURCE-TASK-NEW") should beFalse
        csv.contains("PM-TASK-NEW") should beFalse
      } {
        Seq[() => Unit] { () => releaseExport.trySuccess(()); () } ++ drain(futures) ++ Seq(() => fixtureOpt.foreach(cleanup))
      }
    }
  }

  step(closeDatabases())

  private val exportRequest = ExportWorkUnitRequest(
    ExportWorkUnitColumnNames(None, None, None, None, None, None, None, None, None, None),
    ids = Seq.empty,
    dateFrom = None,
    dateTo = None,
    projectIds = Seq.empty,
    userIds = Seq.empty,
    clientIds = Seq.empty
  )

  private def newExportService(
    organizationUsers: OrganizationUserRepository = organizationUserRepository,
    projectUsers: ProjectUserRepository = projectUserRepository,
    workUnits: WorkUnitRepository = workUnitRepository
  ): ExportWorkUnitService = {
    val listService = new WorkUnitListService(
      new PostgresIOTransactor(db),
      organizationRepository,
      organizationUsers,
      projectRepository,
      projectUsers,
      userRepository,
      workUnits,
      workUnitOrderRepository,
      workUnitDtoService
    )
    new ExportWorkUnitService(listService)
  }

  private def setupFixture(role: OrganizationUserRole, projectOwner: Boolean): Fixture = {
    val actor =
      allGenerators[User].normal.generate.copy(isSuperAdmin = UserIsSuperAdmin(false), accountType = UserAccountType.Human, archivedAt = None)
    val colleague =
      allGenerators[User].normal.generate.copy(isSuperAdmin = UserIsSuperAdmin(false), accountType = UserAccountType.Human, archivedAt = None)
    val organization = allGenerators[Organization].normal.generate.copy(createdBy = actor.id, modifiedBy = actor.id, archivedAt = None)
    val client = allGenerators[Client].normal.generate.copy(
      organizationId = organization.id,
      createdBy = actor.id,
      modifiedBy = actor.id,
      archivedAt = None,
      archivedBy = None
    )
    val sourceProject = allGenerators[Project].normal.generate.copy(
      name = ProjectName("SOURCE-PROJECT-OLD"),
      organizationId = organization.id,
      clientId = client.id,
      createdBy = actor.id,
      modifiedBy = actor.id,
      archivedAt = None,
      archivedBy = None
    )
    val targetProject = allGenerators[Project].normal.generate.copy(
      name = ProjectName("PM-PROJECT-OLD"),
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
    val now = Instant.now()
    val workUnit = allGenerators[WorkUnit].normal.generate.copy(
      id = WorkUnitId(UUID.randomUUID()),
      userId = actor.id,
      organizationId = organization.id,
      projectId = sourceProject.id,
      taskId = Some(WorkUnitTaskId("SOURCE-TASK-OLD")),
      overrideState = WorkUnitOverrideState(
        projectId = Some(targetProject.id),
        taskIdOverrideSet = true,
        taskId = Some(WorkUnitTaskId("PM-TASK-OLD")),
        version = 1,
        lastChangedBy = Some(actor.id),
        lastChangedAt = Some(now)
      ),
      duration = WorkUnitDuration(Duration.ofHours(1)),
      entryType = WorkUnitEntryType.Worklog,
      state = WorkUnitState.Active,
      archivedAt = None
    )
    val colleagueWorkUnit = workUnit.copy(
      id = WorkUnitId(UUID.randomUUID()),
      userId = colleague.id,
      overrideState = workUnit.overrideState.copy(taskId = Some(WorkUnitTaskId("OTHER-CORRECTED")))
    )

    awaitDb(
      (for {
        storedActor             <- userRepository.insert(actor)
        storedColleague         <- userRepository.insert(colleague)
        storedOrganization      <- organizationRepository.insert(organization)
        storedMembership        <- organizationUserRepository.upsert(membership)
        storedClient            <- clientRepository.insert(client)
        storedSourceProject     <- projectRepository.insert(sourceProject)
        storedTargetProject     <- projectRepository.insert(targetProject)
        storedProjectUsers      <- projectUserRepository.insert(projectUsers)
        storedWorkUnit          <- workUnitRepository.insert(workUnit)
        storedColleagueWorkUnit <- workUnitRepository.insert(colleagueWorkUnit)
      } yield new Fixture(
        storedActor,
        AuthContext(storedActor.id, UserIsSuperAdmin(false), Nil, Set.empty, Nil, now, fresh = true),
        storedOrganization,
        storedMembership,
        storedClient,
        storedSourceProject,
        storedTargetProject,
        storedProjectUsers,
        storedWorkUnit,
        storedColleague,
        storedColleagueWorkUnit
      )).transactionally,
      "committed worklog export fixture"
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
    val workUnitId      = fixture.workUnit.id.asInstanceOf[UUID]
    val colleagueUnitId = fixture.colleagueWorkUnit.id.asInstanceOf[UUID]
    val colleagueId     = fixture.colleague.id.asInstanceOf[UUID]
    val organizationId  = fixture.organization.id.asInstanceOf[UUID]
    val actorId         = fixture.actor.id.asInstanceOf[UUID]
    val sourceId        = fixture.sourceProject.id.asInstanceOf[UUID]
    val targetId        = fixture.targetProject.id.asInstanceOf[UUID]
    val clientId        = fixture.client.id.asInstanceOf[UUID]
    awaitDb(
      DBIO
        .seq(
          sqlu"delete from work_unit where id in ($workUnitId, $colleagueUnitId)",
          sqlu"delete from project_user where project_id in ($sourceId, $targetId) and user_id = $actorId",
          sqlu"delete from organization_user where organization_id = $organizationId and user_id = $actorId",
          sqlu"delete from project where id in ($sourceId, $targetId)",
          sqlu"delete from client where id = $clientId",
          sqlu"delete from organization where id = $organizationId",
          sqlu"""delete from "user" where id in ($actorId, $colleagueId)"""
        )
        .transactionally,
      "committed worklog export cleanup"
    )
  }

  private def csvBody(result: ExportWorkUnitResult): String = result match {
    case ExportWorkUnitResult.Ok(_, stream, SpreadsheetFormat.Csv) =>
      new String(stream.compile.to(Array).unsafeRunSync(), "UTF-8")
    case other => throw new AssertionError(s"expected CSV export, got $other")
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
    val auth: AuthContext,
    val organization: Organization,
    val membership: OrganizationUser,
    val client: Client,
    val sourceProject: Project,
    val targetProject: Project,
    val projectUsers: Seq[ProjectUser],
    val workUnit: WorkUnit,
    val colleague: User,
    val colleagueWorkUnit: WorkUnit)

  private final class ObservedOrganizationUserRepository(
    delegate: OrganizationUserRepository,
    organizationId: OrganizationId,
    actorId: UserId,
    pid: Promise[Int])
      extends OrganizationUserRepository {
    override def upsert(value: OrganizationUser): DBIO[OrganizationUser]            = delegate.upsert(value)
    override def list(filter: OrganizationUserFilters): DBIO[Seq[OrganizationUser]] = delegate.list(filter)
    override def delete(id: OrganizationUserId, when: Instant): DBIO[Int]           = delegate.delete(id, when)
    override def find(filter: OrganizationUserFilters): DBIO[Option[OrganizationUser]] =
      if (filter.forUpdate && filter.organizationId.contains(organizationId) && filter.userId.contains(actorId))
        for {
          backendPid <- sql"select pg_backend_pid()".as[Int].head
          _ = pid.trySuccess(backendPid)
          result <- delegate.find(filter)
        } yield result
      else delegate.find(filter)
  }

  private final class GatedProjectUserLockRepository(
    delegate: ProjectUserRepository,
    actorId: UserId,
    pid: Promise[Int],
    locked: Promise[Unit],
    release: Promise[Unit])
      extends ProjectUserRepository {
    override def insert(value: ProjectUser): DBIO[ProjectUser]                                = delegate.insert(value)
    override def insert(values: Seq[ProjectUser]): DBIO[Seq[ProjectUser]]                     = delegate.insert(values)
    override def update(value: ProjectUser): DBIO[Option[ProjectUser]]                        = delegate.update(value)
    override def bulkArchiveAt(ids: Seq[ProjectUserId], at: ProjectUserArchivedAt): DBIO[Int] = delegate.bulkArchiveAt(ids, at)
    override def find(filter: ProjectUserFilters): DBIO[Option[ProjectUser]]                  = delegate.find(filter)
    override def list(filter: ProjectUserFilters): DBIO[Seq[ProjectUser]]                     = delegate.list(filter)
    override def lockOrdered(filter: ProjectUserFilters): DBIO[Seq[ProjectUser]] =
      if (filter.userId.contains(actorId) && filter.includeArchived)
        for {
          backendPid <- sql"select pg_backend_pid()".as[Int].head
          _ = pid.trySuccess(backendPid)
          result <- delegate.lockOrdered(filter)
          _ = locked.trySuccess(())
          _ <- DBIO.from(release.future)
        } yield result
      else delegate.lockOrdered(filter)
  }

  private final class ObservedProjectUserLockRepository(
    delegate: ProjectUserRepository,
    actorId: UserId,
    pid: Promise[Int])
      extends ProjectUserRepository {
    override def insert(value: ProjectUser): DBIO[ProjectUser]                                = delegate.insert(value)
    override def insert(values: Seq[ProjectUser]): DBIO[Seq[ProjectUser]]                     = delegate.insert(values)
    override def update(value: ProjectUser): DBIO[Option[ProjectUser]]                        = delegate.update(value)
    override def bulkArchiveAt(ids: Seq[ProjectUserId], at: ProjectUserArchivedAt): DBIO[Int] = delegate.bulkArchiveAt(ids, at)
    override def find(filter: ProjectUserFilters): DBIO[Option[ProjectUser]]                  = delegate.find(filter)
    override def list(filter: ProjectUserFilters): DBIO[Seq[ProjectUser]]                     = delegate.list(filter)
    override def lockOrdered(filter: ProjectUserFilters): DBIO[Seq[ProjectUser]] =
      if (filter.userId.contains(actorId) && filter.includeArchived)
        for {
          backendPid <- sql"select pg_backend_pid()".as[Int].head
          _ = pid.trySuccess(backendPid)
          result <- delegate.lockOrdered(filter)
        } yield result
      else delegate.lockOrdered(filter)
  }

  private final class GatedWorkUnitListRepository(
    delegate: WorkUnitRepository,
    organizationId: OrganizationId,
    listed: Promise[Unit],
    release: Promise[Unit])
      extends WorkUnitRepository {
    override def insert(value: WorkUnit): DBIO[WorkUnit]                                    = delegate.insert(value)
    override def update(value: WorkUnit): DBIO[Option[WorkUnit]]                            = delegate.update(value)
    override def find(filter: WorkUnitFilters): DBIO[Option[WorkUnit]]                      = delegate.find(filter)
    override def listWithClientId(filter: WorkUnitFilters): DBIO[Seq[WorkUnitWithClientId]] = delegate.listWithClientId(filter)
    override def count(filter: WorkUnitFilters): DBIO[Int]                                  = delegate.count(filter)
    override def list(filter: WorkUnitFilters): DBIO[Seq[WorkUnit]] =
      if (filter.organizationId.contains(organizationId))
        delegate.list(filter).flatMap { result =>
          listed.trySuccess(())
          DBIO.from(release.future).map(_ => result)
        }
      else delegate.list(filter)
  }
}
