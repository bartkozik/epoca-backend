package pl.iterators.epoca.project.services

import cats.effect.unsafe.IORuntime
import org.specs2.mutable.SpecificationLike
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.client.domain.Client
import pl.iterators.epoca.client.repositories.ClientRepositoryImpl
import pl.iterators.epoca.organization.domain.OrganizationDomain._
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.organization.repositories.{OrganizationRepositoryImpl, OrganizationUserRepositoryImpl}
import pl.iterators.epoca.project.domain.ProjectDomain._
import pl.iterators.epoca.project.domain.{Project, ProjectUser}
import pl.iterators.epoca.project.repositories.ProjectRepository.ProjectFilters
import pl.iterators.epoca.project.repositories.ProjectUserRepository.ProjectUserFilters
import pl.iterators.epoca.project.repositories.{ProjectRepositoryImpl, ProjectUserRepositoryImpl}
import pl.iterators.epoca.project.services.ProjectAssignService.ProjectAssignRequest
import pl.iterators.epoca.project.services.ProjectAssignService.ProjectAssignResult.Assigned
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.domain.UserDomain.{UserId, UserIsSuperAdmin}
import pl.iterators.epoca.user.repositories.UserRepositoryImpl
import pl.iterators.epoca.utils.Generators
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.workunit.domain.WorkUnit
import pl.iterators.epoca.workunit.domain.WorkUnitDomain._
import pl.iterators.epoca.workunit.repositories.WorkUnitRepository.WorkUnitFilters
import pl.iterators.epoca.workunit.repositories.WorkUnitRepositoryImpl
import pl.iterators.epoca.workunit.services.WorkUnitUpdateService.WorkUnitUpdateResult.Updated
import pl.iterators.epoca.workunit.services.WorkUnitUpdateService.{WorkUnitUpdateRequest, WorkUnitUpdateResult}
import pl.iterators.epoca.workunit.services.{WorkUnitBusinessClock, WorkUnitDtoService, WorkUnitUpdateService}
import slick.dbio.DBIO
import slick.jdbc.SetParameter
import slick.util.AsyncExecutor

import java.sql.{DriverManager, SQLException, Types}
import java.time.{Clock, Duration => JavaDuration, Instant, LocalDate, ZoneId, ZoneOffset}
import java.util.UUID
import java.util.concurrent.TimeoutException
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.util.control.NonFatal

class SeedApplicationLockOrderConcurrencySpec extends SpecificationLike with Generators {
  import SeedApplicationLockOrderConcurrencySpec._

  sequential

  private implicit val ec: ExecutionContext = ExecutionContext.global
  private implicit val runtime: IORuntime   = IORuntime.global
  private implicit val setUuid: SetParameter[UUID] =
    SetParameter((value, parameters) => parameters.setObject(value, Types.OTHER))

  private val requestTimeout         = 20.seconds
  private val setupCleanupTimeout    = 10.seconds
  private val lockObservationTimeout = 10.seconds
  private val lockPollingInterval    = 25.millis

  private val databaseUrl      = sys.env.getOrElse("TEST_DB_URL", "epoca")
  private val databaseUser     = sys.env.getOrElse("TEST_DB_USER", "postgres")
  private val databasePassword = sys.env.getOrElse("TEST_DB_PASSWORD", "postgres")
  private val executorSuffix   = UUID.randomUUID().toString

  private def namedUrl(name: String): String = {
    val separator = if (databaseUrl.contains("?")) "&" else "?"
    s"$databaseUrl${separator}ApplicationName=$name"
  }

  private lazy val applicationDb = Database.forURL(
    url = namedUrl("ct532-lock-order-application"),
    user = databaseUser,
    password = databasePassword,
    driver = "org.postgresql.Driver",
    executor = AsyncExecutor(s"ct532-lock-order-application-$executorSuffix", minThreads = 2, maxThreads = 2, queueSize = 20, maxConnections = 2)
  )

  private lazy val seedDb = Database.forURL(
    url = namedUrl("ct532-lock-order-seed"),
    user = databaseUser,
    password = databasePassword,
    driver = "org.postgresql.Driver",
    executor = AsyncExecutor(s"ct532-lock-order-seed-$executorSuffix", minThreads = 1, maxThreads = 1, queueSize = 20, maxConnections = 1)
  )

  private lazy val observerDb = Database.forURL(
    url = namedUrl("ct532-lock-order-observer"),
    user = databaseUser,
    password = databasePassword,
    driver = "org.postgresql.Driver",
    executor = AsyncExecutor(s"ct532-lock-order-observer-$executorSuffix", minThreads = 1, maxThreads = 1, queueSize = 20, maxConnections = 1)
  )

  private val transactor                 = new PostgresIOTransactor(applicationDb)
  private val userRepository             = new UserRepositoryImpl()
  private val organizationRepository     = new OrganizationRepositoryImpl()
  private val organizationUserRepository = new OrganizationUserRepositoryImpl()
  private val clientRepository           = new ClientRepositoryImpl(ClientRepositoryImpl.Config(30.seconds))
  private val projectRepository          = new ProjectRepositoryImpl()
  private val projectUserRepository      = new ProjectUserRepositoryImpl()
  private val workUnitRepository         = new WorkUnitRepositoryImpl()
  private val workUnitDtoService         = new WorkUnitDtoService(clientRepository, userRepository, projectRepository)
  private val workUnitBusinessClock =
    WorkUnitBusinessClock(Clock.fixed(Instant.parse("2026-08-12T08:00:00Z"), ZoneOffset.UTC), ZoneId.of("Europe/Warsaw"))

  private val requestedDescription = WorkUnitDescription("Demo delivery sync")
  private val requestedTaskId      = WorkUnitTaskId("CT-532-DEMO")

  "project assignment lock ordering" should {
    "let an application holding the project row finish before the seed locks project and updates project_user" in {
      val projectControl = LockControl.paused
      val beforeUserFind = Promise[Unit]()
      val releaseProject = projectControl.release.get
      val seedBackendPid = Promise[Int]()
      val seedAcquired   = Promise[Unit]()
      val releaseSeed    = Promise[Unit]()
      var fixtureOpt     = Option.empty[Fixture]
      var applicationOpt = Option.empty[Future[ProjectAssignService.ProjectAssignResult]]
      var seedOpt        = Option.empty[Future[Int]]

      runWithTeardown {
        val fixture = setupFixture()
        fixtureOpt = Some(fixture)
        val projectBefore = projectRowJson(fixture.project.id)
        val service       = assignService(projectControl, beforeUserFind)

        val application = assign(service, fixture)
        applicationOpt = Some(application)
        await(projectControl.acquired.future, "application-first project row lock")

        val seed = runSeedTransaction(fixture.projectUser.id, seedBackendPid, seedAcquired, releaseSeed)
        seedOpt = Some(seed)
        val applicationPid = await(projectControl.backendPid.future, "application-first application backend PID")
        val seedPid        = await(seedBackendPid.future, "application-first seed backend PID")
        awaitRelationBlock(applicationPid, seedPid, "ExclusiveLock") shouldEqual
          RelationLock(applicationPid, seedPid, "project", "ExclusiveLock", granted = false)
        seedAcquired.isCompleted should beFalse

        releaseProject.trySuccess(()) should beTrue
        awaitNoDeadlock(application, "application-first assignment result") shouldEqual Assigned
        await(seedAcquired.future, "application-first seed project lock")
        releaseSeed.trySuccess(()) should beTrue
        awaitNoDeadlock(seed, "application-first seed transaction") shouldEqual 1

        assertFinalState(fixture, projectBefore)
      } {
        Seq[() => Unit](
          () => { releaseProject.trySuccess(()); () },
          () => { releaseSeed.trySuccess(()); () },
          () => applicationOpt.foreach(value => drainNoDeadlock(value, "application-first assignment")),
          () => seedOpt.foreach(value => drainNoDeadlock(value, "application-first seed transaction")),
          () => fixtureOpt.foreach(cleanup)
        )
      }
    }

    "hold project_user work behind the project row when the seed owns the project relation lock" in {
      val projectControl = LockControl.unpaused
      val beforeUserFind = Promise[Unit]()
      val seedBackendPid = Promise[Int]()
      val seedAcquired   = Promise[Unit]()
      val releaseSeed    = Promise[Unit]()
      var fixtureOpt     = Option.empty[Fixture]
      var applicationOpt = Option.empty[Future[ProjectAssignService.ProjectAssignResult]]
      var seedOpt        = Option.empty[Future[Int]]

      runWithTeardown {
        val fixture = setupFixture()
        fixtureOpt = Some(fixture)
        val projectBefore = projectRowJson(fixture.project.id)
        val service       = assignService(projectControl, beforeUserFind)

        val seed = runSeedTransaction(fixture.projectUser.id, seedBackendPid, seedAcquired, releaseSeed)
        seedOpt = Some(seed)
        await(seedAcquired.future, "seed-first seed project and project_user work")

        val application = assign(service, fixture)
        applicationOpt = Some(application)
        val seedPid        = await(seedBackendPid.future, "seed-first seed backend PID")
        val applicationPid = await(projectControl.backendPid.future, "seed-first application backend PID")
        awaitRelationBlock(seedPid, applicationPid, "RowShareLock") shouldEqual
          RelationLock(seedPid, applicationPid, "project", "RowShareLock", granted = false)
        beforeUserFind.isCompleted should beFalse

        releaseSeed.trySuccess(()) should beTrue
        awaitNoDeadlock(seed, "seed-first seed transaction") shouldEqual 1
        awaitNoDeadlock(application, "seed-first assignment result") shouldEqual Assigned
        beforeUserFind.isCompleted should beTrue

        assertFinalState(fixture, projectBefore)
      } {
        Seq[() => Unit](
          () => { releaseSeed.trySuccess(()); () },
          () => applicationOpt.foreach(value => drainNoDeadlock(value, "seed-first assignment")),
          () => seedOpt.foreach(value => drainNoDeadlock(value, "seed-first seed transaction")),
          () => fixtureOpt.foreach(cleanup)
        )
      }
    }
  }

  "work-unit update lock ordering" should {
    "let an application holding the project relation finish before the seed updates the dependent work unit" in {
      val rowControl         = LockControl.unpaused
      val relationControl    = LockControl.paused
      val beforeWorkUnitFind = Promise[Unit]()
      val workUnitAcquired   = Promise[Unit]()
      val releaseRelation    = relationControl.release.get
      val seedBackendPid     = Promise[Int]()
      val seedAcquired       = Promise[Unit]()
      val releaseSeed        = Promise[Unit]()
      var fixtureOpt         = Option.empty[Fixture]
      var applicationOpt     = Option.empty[Future[WorkUnitUpdateResult]]
      var seedOpt            = Option.empty[Future[Int]]

      runWithTeardown {
        val fixture = setupFixture()
        fixtureOpt = Some(fixture)
        val sourceProjectBefore = projectRowJson(fixture.project.id)
        val targetProjectBefore = projectRowJson(fixture.targetProject.id)
        val service             = workUnitUpdateService(rowControl, relationControl, beforeWorkUnitFind, workUnitAcquired)
        val request             = workUnitUpdateRequest(fixture)

        val application = updateWorkUnit(service, fixture, request)
        applicationOpt = Some(application)
        await(relationControl.acquired.future, "application-first project relation lock")

        val seed = runWorkUnitSeedTransaction(fixture.workUnit.id, seedBackendPid, seedAcquired, releaseSeed)
        seedOpt = Some(seed)
        val applicationPid = await(relationControl.backendPid.future, "application-first application backend PID")
        val seedPid        = await(seedBackendPid.future, "application-first seed backend PID")
        awaitRelationBlock(applicationPid, seedPid, "ExclusiveLock") shouldEqual
          RelationLock(applicationPid, seedPid, "project", "ExclusiveLock", granted = false)
        seedAcquired.isCompleted should beFalse

        releaseRelation.trySuccess(()) should beTrue
        val result = awaitNoDeadlock(application, "application-first work-unit update result")
        result must beLike { case Updated(dto) => assertReturnedWorkUnit(dto, fixture, request) }
        await(seedAcquired.future, "application-first seed project lock and work-unit update")
        releaseSeed.trySuccess(()) should beTrue
        awaitNoDeadlock(seed, "application-first seed transaction") shouldEqual 1

        beforeWorkUnitFind.isCompleted should beTrue
        workUnitAcquired.isCompleted should beTrue
        assertWorkUnitFinalState(fixture, request, sourceProjectBefore, targetProjectBefore)
      } {
        Seq[() => Unit](
          () => { releaseRelation.trySuccess(()); () },
          () => { releaseSeed.trySuccess(()); () },
          () => applicationOpt.foreach(value => drainNoDeadlock(value, "application-first work-unit update")),
          () => seedOpt.foreach(value => drainNoDeadlock(value, "application-first seed transaction")),
          () => fixtureOpt.foreach(cleanup)
        )
      }
    }

    "keep the work-unit row untouched while the seed owns the project relation lock" in {
      val rowControl         = LockControl.unpaused
      val relationControl    = LockControl.unpaused
      val beforeWorkUnitFind = Promise[Unit]()
      val workUnitAcquired   = Promise[Unit]()
      val seedBackendPid     = Promise[Int]()
      val seedAcquired       = Promise[Unit]()
      val releaseSeed        = Promise[Unit]()
      var fixtureOpt         = Option.empty[Fixture]
      var applicationOpt     = Option.empty[Future[WorkUnitUpdateResult]]
      var seedOpt            = Option.empty[Future[Int]]

      runWithTeardown {
        val fixture = setupFixture()
        fixtureOpt = Some(fixture)
        val sourceProjectBefore = projectRowJson(fixture.project.id)
        val targetProjectBefore = projectRowJson(fixture.targetProject.id)
        val service             = workUnitUpdateService(rowControl, relationControl, beforeWorkUnitFind, workUnitAcquired)
        val request             = workUnitUpdateRequest(fixture)

        val seed = runWorkUnitSeedTransaction(fixture.workUnit.id, seedBackendPid, seedAcquired, releaseSeed)
        seedOpt = Some(seed)
        await(seedAcquired.future, "seed-first seed project lock and work-unit update")

        val application = updateWorkUnit(service, fixture, request)
        applicationOpt = Some(application)
        val seedPid        = await(seedBackendPid.future, "seed-first seed backend PID")
        val applicationPid = await(relationControl.backendPid.future, "seed-first application backend PID")
        awaitRelationBlock(seedPid, applicationPid, "RowShareLock") shouldEqual
          RelationLock(seedPid, applicationPid, "project", "RowShareLock", granted = false)
        beforeWorkUnitFind.isCompleted should beFalse

        releaseSeed.trySuccess(()) should beTrue
        awaitNoDeadlock(seed, "seed-first seed transaction") shouldEqual 1
        val result = awaitNoDeadlock(application, "seed-first work-unit update result")
        result must beLike { case Updated(dto) => assertReturnedWorkUnit(dto, fixture, request) }

        beforeWorkUnitFind.isCompleted should beTrue
        workUnitAcquired.isCompleted should beTrue
        assertWorkUnitFinalState(fixture, request, sourceProjectBefore, targetProjectBefore)
      } {
        Seq[() => Unit](
          () => { releaseSeed.trySuccess(()); () },
          () => applicationOpt.foreach(value => drainNoDeadlock(value, "seed-first work-unit update")),
          () => seedOpt.foreach(value => drainNoDeadlock(value, "seed-first seed transaction")),
          () => fixtureOpt.foreach(cleanup)
        )
      }
    }
  }

  step(closeDatabases())

  private def assignService(projectControl: LockControl, beforeUserFind: Promise[Unit]): ProjectAssignService =
    new ProjectAssignService(
      transactor,
      userRepository,
      new ObservedProjectRepository(projectControl, LockControl.unpaused),
      new ObservedProjectUserRepository(beforeUserFind),
      organizationRepository,
      organizationUserRepository
    )

  private def workUnitUpdateService(
    rowControl: LockControl,
    relationControl: LockControl,
    beforeWorkUnitFind: Promise[Unit],
    workUnitAcquired: Promise[Unit]
  ): WorkUnitUpdateService =
    new WorkUnitUpdateService(
      transactor,
      organizationRepository,
      organizationUserRepository,
      new ObservedWorkUnitRepository(beforeWorkUnitFind, workUnitAcquired),
      new ObservedProjectRepository(rowControl, relationControl),
      projectUserRepository,
      userRepository,
      workUnitDtoService,
      workUnitBusinessClock
    )

  private def assign(service: ProjectAssignService, fixture: Fixture): Future[ProjectAssignService.ProjectAssignResult] =
    service
      .assign(fixture.authAdmin, fixture.organization.slug, fixture.project.id, fixture.targetUser.id, ProjectAssignRequest(ProjectRole.Owner))
      .unsafeToFuture()

  private def updateWorkUnit(
    service: WorkUnitUpdateService,
    fixture: Fixture,
    request: WorkUnitUpdateRequest
  ): Future[WorkUnitUpdateResult] =
    service.update(fixture.authAdmin, fixture.organization.slug, fixture.workUnit.id, request).unsafeToFuture()

  private def workUnitUpdateRequest(fixture: Fixture): WorkUnitUpdateRequest =
    WorkUnitUpdateRequest(
      projectId = Some(fixture.targetProject.id),
      duration = Some(fixture.workUnit.duration),
      description = Some(requestedDescription),
      taskId = Some(requestedTaskId),
      date = Some(fixture.workUnit.date),
      shouldFinalize = false
    )

  private def runSeedTransaction(
    projectUserId: ProjectUserId,
    backendPid: Promise[Int],
    acquired: Promise[Unit],
    release: Promise[Unit]
  ): Future[Int] = {
    val rawProjectUserId = projectUserId.asInstanceOf[UUID]
    seedDb.run((for {
      pid     <- sql"SELECT pg_catalog.pg_backend_pid()".as[Int].head
      _       <- DBIO.successful { backendPid.trySuccess(pid); () }
      _       <- sqlu"LOCK TABLE public.project IN EXCLUSIVE MODE"
      updated <- sqlu"UPDATE public.project_user SET role = role WHERE id = $rawProjectUserId"
      _       <- DBIO.successful { acquired.trySuccess(()); () }
      _       <- DBIO.from(release.future)
    } yield updated).transactionally)
  }

  private def runWorkUnitSeedTransaction(
    workUnitId: WorkUnitId,
    backendPid: Promise[Int],
    acquired: Promise[Unit],
    release: Promise[Unit]
  ): Future[Int] = {
    val rawWorkUnitId = workUnitId.asInstanceOf[UUID]
    seedDb.run((for {
      pid     <- sql"SELECT pg_catalog.pg_backend_pid()".as[Int].head
      _       <- DBIO.successful { backendPid.trySuccess(pid); () }
      _       <- sqlu"LOCK TABLE public.project IN EXCLUSIVE MODE"
      updated <- sqlu"UPDATE public.work_unit SET description = description WHERE id = $rawWorkUnitId"
      _       <- DBIO.successful { acquired.trySuccess(()); () }
      _       <- DBIO.from(release.future)
    } yield updated).transactionally)
  }

  private def setupFixture(): Fixture = {
    val actor = allGenerators[User].normal.generate.copy(isSuperAdmin = UserIsSuperAdmin(true), archivedAt = None)
    val targetUser =
      allGenerators[User].normal.generate.copy(id = UserId(UUID.randomUUID()), isSuperAdmin = UserIsSuperAdmin(false), archivedAt = None)
    val organization = allGenerators[Organization].normal.generate.copy(
      slug = OrganizationSlug(s"ct532-lock-order-${UUID.randomUUID()}"),
      createdBy = actor.id,
      modifiedBy = actor.id,
      archivedAt = None
    )
    val organizationUser = OrganizationUser(OrganizationUserId.random, organization.id, targetUser.id, Seq(OrganizationUserRole.Employee), None)
    val client = allGenerators[Client].normal.generate.copy(
      organizationId = organization.id,
      createdBy = actor.id,
      modifiedBy = actor.id,
      archivedAt = None,
      archivedBy = None
    )
    val targetClient = allGenerators[Client].normal.generate.copy(
      organizationId = organization.id,
      createdBy = actor.id,
      modifiedBy = actor.id,
      archivedAt = None,
      archivedBy = None
    )
    val project = allGenerators[Project].normal.generate.copy(
      name = ProjectName(s"CT532 lock order ${UUID.randomUUID()}"),
      organizationId = organization.id,
      clientId = client.id,
      createdBy = actor.id,
      modifiedBy = actor.id,
      archivedAt = None,
      archivedBy = None
    )
    val targetProject = allGenerators[Project].normal.generate.copy(
      name = ProjectName(s"CT532 target lock order ${UUID.randomUUID()}"),
      organizationId = organization.id,
      clientId = targetClient.id,
      createdBy = actor.id,
      modifiedBy = actor.id,
      archivedAt = None,
      archivedBy = None
    )
    val projectUser =
      allGenerators[ProjectUser].normal.generate.copy(projectId = project.id, userId = targetUser.id, role = ProjectRole.Member, archivedAt = None)
    val workUnit = allGenerators[WorkUnit].normal.generate.copy(
      userId = actor.id,
      organizationId = organization.id,
      projectId = project.id,
      date = WorkUnitDate(LocalDate.parse("2026-08-12")),
      duration = WorkUnitDuration(JavaDuration.ofHours(1L)),
      entryType = WorkUnitEntryType.Worklog,
      state = WorkUnitState.Active,
      archivedAt = None
    )
    val authAdmin = allGenerators[AuthContext].normal.generate.copy(id = actor.id, isSuperAdmin = UserIsSuperAdmin(true))
    val fixture =
      Fixture(actor, targetUser, authAdmin, organization, organizationUser, client, targetClient, project, targetProject, projectUser, workUnit)

    awaitApplicationDb(
      DBIO
        .seq(
          userRepository.insert(actor),
          userRepository.insert(targetUser),
          organizationRepository.insert(organization),
          organizationUserRepository.insert(organizationUser),
          clientRepository.insert(client),
          clientRepository.insert(targetClient),
          projectRepository.insert(project),
          projectRepository.insert(targetProject),
          projectUserRepository.insert(projectUser),
          workUnitRepository.insert(workUnit)
        )
        .transactionally,
      "committed lock-order fixture setup"
    )
    fixture
  }

  private def assertFinalState(fixture: Fixture, projectBefore: String) = {
    val rows = awaitApplicationDb(
      projectUserRepository.list(
        ProjectUserFilters(projectIds = Some(Seq(fixture.project.id)), userId = Some(fixture.targetUser.id), includeArchived = true)
      ),
      "project-user final-state query"
    )
    val activeRows = rows.filter(_.archivedAt.isEmpty)

    rows.find(_.id == fixture.projectUser.id).flatMap(_.archivedAt) should beSome
    activeRows.count(_.role == ProjectRole.Owner) shouldEqual 1
    activeRows.size shouldEqual 1
    projectRowJson(fixture.project.id) shouldEqual projectBefore
  }

  private def assertReturnedWorkUnit(
    dto: WorkUnitDtoService.WorkUnitDto,
    fixture: Fixture,
    request: WorkUnitUpdateRequest
  ) = {
    dto.id shouldEqual fixture.workUnit.id
    dto.userId shouldEqual fixture.workUnit.userId
    dto.date shouldEqual request.date.get
    dto.duration shouldEqual request.duration.get
    dto.entryType shouldEqual fixture.workUnit.entryType
    dto.description shouldEqual requestedDescription
    dto.taskId shouldEqual Some(requestedTaskId)
    dto.state shouldEqual fixture.workUnit.state
    dto.createdAt shouldEqual fixture.workUnit.createdAt
    dto.projectClient.map(_.projectId) shouldEqual Some(fixture.targetProject.id)
    dto.projectClient.map(_.clientId) shouldEqual Some(fixture.targetClient.id)
  }

  private def assertWorkUnitFinalState(
    fixture: Fixture,
    request: WorkUnitUpdateRequest,
    sourceProjectBefore: String,
    targetProjectBefore: String
  ) = {
    val stored = awaitApplicationDb(
      workUnitRepository
        .find(WorkUnitFilters(id = Some(fixture.workUnit.id), organizationId = Some(fixture.organization.id), includeArchived = true))
        .map(_.getOrElse(throw new AssertionError(s"Missing work unit ${fixture.workUnit.id} after update"))),
      "work-unit final-state query"
    )
    val rawWorkUnitId = fixture.workUnit.id.asInstanceOf[UUID]
    val storedClientId = awaitApplicationDb(
      sql"SELECT client_id::TEXT FROM public.work_unit WHERE id = $rawWorkUnitId".as[String].head,
      "work-unit derived client query"
    )

    stored.projectId shouldEqual fixture.targetProject.id
    stored.description shouldEqual requestedDescription
    stored.taskId shouldEqual Some(requestedTaskId)
    stored.duration shouldEqual request.duration.get
    stored.date shouldEqual request.date.get
    stored.id shouldEqual fixture.workUnit.id
    stored.userId shouldEqual fixture.workUnit.userId
    stored.organizationId shouldEqual fixture.workUnit.organizationId
    stored.entryType shouldEqual fixture.workUnit.entryType
    stored.state shouldEqual fixture.workUnit.state
    stored.createdAt shouldEqual fixture.workUnit.createdAt
    stored.archivedAt shouldEqual fixture.workUnit.archivedAt
    storedClientId shouldEqual fixture.targetClient.id.toString
    projectRowJson(fixture.project.id) shouldEqual sourceProjectBefore
    projectRowJson(fixture.targetProject.id) shouldEqual targetProjectBefore
  }

  private def projectRowJson(projectId: ProjectId): String = {
    val rawProjectId = projectId.asInstanceOf[UUID]
    awaitApplicationDb(
      sql"""SELECT pg_catalog.row_to_json(project)::TEXT
             FROM project
             WHERE id = $rawProjectId""".as[String].head,
      "project row snapshot"
    )
  }

  private def cleanup(fixture: Fixture): Unit = {
    val workUnitId      = fixture.workUnit.id.asInstanceOf[UUID]
    val projectId       = fixture.project.id.asInstanceOf[UUID]
    val targetProjectId = fixture.targetProject.id.asInstanceOf[UUID]
    val clientId        = fixture.client.id.asInstanceOf[UUID]
    val targetClientId  = fixture.targetClient.id.asInstanceOf[UUID]
    val organizationId  = fixture.organization.id.asInstanceOf[UUID]
    val actorId         = fixture.actor.id.asInstanceOf[UUID]
    val targetUserId    = fixture.targetUser.id.asInstanceOf[UUID]

    val residue = awaitApplicationDb(
      (for {
        _ <- sqlu"DELETE FROM work_unit WHERE id = $workUnitId"
        _ <- sqlu"DELETE FROM project_user WHERE project_id IN ($projectId, $targetProjectId)"
        _ <- sqlu"DELETE FROM project WHERE id IN ($projectId, $targetProjectId)"
        _ <- sqlu"DELETE FROM client WHERE id IN ($clientId, $targetClientId)"
        _ <- sqlu"DELETE FROM organization_user WHERE organization_id = $organizationId"
        _ <- sqlu"DELETE FROM organization WHERE id = $organizationId"
        _ <- sqlu"""DELETE FROM "user" WHERE id IN ($actorId, $targetUserId)"""
        count <- sql"""SELECT
                         (SELECT pg_catalog.count(*) FROM work_unit WHERE id = $workUnitId) +
                         (SELECT pg_catalog.count(*) FROM project_user WHERE project_id IN ($projectId, $targetProjectId)) +
                         (SELECT pg_catalog.count(*) FROM project WHERE id IN ($projectId, $targetProjectId)) +
                         (SELECT pg_catalog.count(*) FROM client WHERE id IN ($clientId, $targetClientId)) +
                         (SELECT pg_catalog.count(*) FROM organization_user WHERE organization_id = $organizationId) +
                         (SELECT pg_catalog.count(*) FROM organization WHERE id = $organizationId) +
                         (SELECT pg_catalog.count(*) FROM "user" WHERE id IN ($actorId, $targetUserId))""".as[Long].head
      } yield count).transactionally,
      "FK-ordered lock-order fixture cleanup"
    )
    if (residue != 0L) throw new AssertionError(s"Lock-order fixture cleanup left $residue rows")
  }

  private def awaitRelationBlock(
    blockerPid: Int,
    waitingPid: Int,
    mode: String
  ): RelationLock = {
    val deadline        = lockObservationTimeout.fromNow
    var lastObservation = Option.empty[RelationLock]

    while (deadline.hasTimeLeft()) {
      lastObservation = awaitObserver(
        sql"""SELECT blocker.blocker_pid, waiting_lock.pid, relation.relname, waiting_lock.mode, waiting_lock.granted
               FROM pg_catalog.pg_locks AS waiting_lock
               JOIN pg_catalog.pg_class AS relation ON relation.oid = waiting_lock.relation
               JOIN pg_catalog.pg_namespace AS namespace ON namespace.oid = relation.relnamespace
               CROSS JOIN LATERAL pg_catalog.unnest(pg_catalog.pg_blocking_pids(waiting_lock.pid)) AS blocker(blocker_pid)
               WHERE waiting_lock.pid = $waitingPid
                 AND blocker.blocker_pid = $blockerPid
                 AND namespace.nspname = 'public'
                 AND relation.relname = 'project'
                 AND waiting_lock.locktype = 'relation'
                 AND waiting_lock.mode = $mode
                 AND NOT waiting_lock.granted"""
          .as[(Int, Int, String, String, Boolean)]
          .headOption
          .map(_.map(RelationLock.tupled)),
        "project relation-lock observation"
      )
      lastObservation.foreach(return _)
      Thread.sleep(lockPollingInterval.toMillis)
    }

    throw new AssertionError(
      s"Timed out after $lockObservationTimeout waiting for backend $waitingPid to wait on backend $blockerPid's $mode on public.project; " +
        s"lastObservation=$lastObservation"
    )
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

  private def await[A](future: Future[A], clue: String): A =
    try Await.result(future, requestTimeout)
    catch {
      case timeout: TimeoutException => throw new AssertionError(s"Timed out waiting for $clue after $requestTimeout", timeout)
    }

  private def awaitNoDeadlock[A](future: Future[A], clue: String): A =
    try await(future, clue)
    catch {
      case error if hasSqlState(error, "40P01") =>
        throw new AssertionError(s"PostgreSQL deadlock detected while waiting for $clue", error)
    }

  private def drainNoDeadlock(future: Future[_], clue: String): Unit =
    try {
      Await.result(future, requestTimeout)
      ()
    } catch {
      case error if hasSqlState(error, "40P01") =>
        throw new AssertionError(s"PostgreSQL deadlock detected while draining $clue", error)
      case timeout: TimeoutException => throw new AssertionError(s"Timed out draining $clue after $requestTimeout", timeout)
      case NonFatal(_)               => ()
    }

  private def hasSqlState(error: Throwable, expected: String): Boolean =
    error match {
      case sqlError: SQLException if sqlError.getSQLState == expected => true
      case other if other.getCause != null && (other.getCause ne other) =>
        hasSqlState(other.getCause, expected)
      case _ => false
    }

  private def awaitApplicationDb[A](action: DBIO[A], clue: String): A =
    try Await.result(applicationDb.run(action), setupCleanupTimeout)
    catch {
      case timeout: TimeoutException => throw new AssertionError(s"Timed out waiting for $clue after $setupCleanupTimeout", timeout)
    }

  private def awaitObserver[A](action: DBIO[A], clue: String): A =
    try Await.result(observerDb.run(action), setupCleanupTimeout)
    catch {
      case timeout: TimeoutException => throw new AssertionError(s"Timed out waiting for $clue after $setupCleanupTimeout", timeout)
    }

  private def closeDatabases(): Unit =
    runWithTeardown(applicationDb.close()) {
      Seq(() => seedDb.close(), () => observerDb.close(), () => assertNoNamedSessions())
    }

  private def assertNoNamedSessions(): Unit = {
    val deadline = setupCleanupTimeout.fromNow
    var count    = Long.MaxValue
    while (count != 0L && deadline.hasTimeLeft()) {
      val connection = DriverManager.getConnection(databaseUrl, databaseUser, databasePassword)
      try {
        val statement = connection.prepareStatement(
          "SELECT pg_catalog.count(*) FROM pg_catalog.pg_stat_activity " +
            "WHERE application_name IN ('ct532-lock-order-application', 'ct532-lock-order-seed', 'ct532-lock-order-observer')"
        )
        try {
          val result = statement.executeQuery()
          try {
            result.next()
            count = result.getLong(1)
          } finally result.close()
        } finally statement.close()
      } finally connection.close()
      if (count != 0L) Thread.sleep(lockPollingInterval.toMillis)
    }
    if (count != 0L) throw new AssertionError(s"Lock-order database pools left $count named PostgreSQL sessions")
  }

  final class ObservedProjectRepository(rowControl: LockControl, relationControl: LockControl)(implicit ec: ExecutionContext)
      extends ProjectRepositoryImpl {
    override def find(filter: ProjectFilters): DBIO[Option[Project]] =
      if (!filter.forUpdate) super.find(filter)
      else
        for {
          pid    <- sql"SELECT pg_catalog.pg_backend_pid()".as[Int].head
          _      <- DBIO.successful { rowControl.backendPid.trySuccess(pid); () }
          result <- super.find(filter)
          _      <- DBIO.successful { rowControl.acquired.trySuccess(()); () }
          _      <- rowControl.release.fold(DBIO.successful(()))(value => DBIO.from(value.future))
        } yield result

    override def lockTableForReference(): DBIO[Unit] =
      for {
        pid <- sql"SELECT pg_catalog.pg_backend_pid()".as[Int].head
        _   <- DBIO.successful { relationControl.backendPid.trySuccess(pid); () }
        _   <- super.lockTableForReference()
        _   <- DBIO.successful { relationControl.acquired.trySuccess(()); () }
        _   <- relationControl.release.fold(DBIO.successful(()))(value => DBIO.from(value.future))
      } yield ()
  }

  final class ObservedProjectUserRepository(beforeFind: Promise[Unit])(implicit ec: ExecutionContext) extends ProjectUserRepositoryImpl {
    override def find(filter: ProjectUserFilters): DBIO[Option[ProjectUser]] =
      if (!filter.forUpdate) super.find(filter)
      else
        DBIO.successful { beforeFind.trySuccess(()); () } >> super.find(filter)
  }

  final class ObservedWorkUnitRepository(beforeFind: Promise[Unit], acquired: Promise[Unit])(implicit ec: ExecutionContext)
      extends WorkUnitRepositoryImpl {
    override def find(filter: WorkUnitFilters): DBIO[Option[WorkUnit]] =
      if (!filter.forUpdate) super.find(filter)
      else
        for {
          _      <- DBIO.successful { beforeFind.trySuccess(()); () }
          result <- super.find(filter)
          _      <- DBIO.successful { acquired.trySuccess(()); () }
        } yield result
  }
}

object SeedApplicationLockOrderConcurrencySpec {
  private final case class Fixture(
    actor: User,
    targetUser: User,
    authAdmin: AuthContext,
    organization: Organization,
    organizationUser: OrganizationUser,
    client: Client,
    targetClient: Client,
    project: Project,
    targetProject: Project,
    projectUser: ProjectUser,
    workUnit: WorkUnit)

  private final case class RelationLock(
    blockerPid: Int,
    waitingPid: Int,
    relation: String,
    mode: String,
    granted: Boolean)

  final case class LockControl(
    backendPid: Promise[Int],
    acquired: Promise[Unit],
    release: Option[Promise[Unit]])

  private object LockControl {
    def paused: LockControl   = LockControl(Promise[Int](), Promise[Unit](), Some(Promise[Unit]()))
    def unpaused: LockControl = LockControl(Promise[Int](), Promise[Unit](), None)
  }
}
