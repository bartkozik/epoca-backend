package pl.iterators.epoca.archiver.services

import cats.effect.unsafe.IORuntime
import org.specs2.mutable.SpecificationLike
import pl.iterators.epoca.archiver.services.ClientDeleteService.ClientDeleteResult
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.client.domain.Client
import pl.iterators.epoca.client.domain.ClientDomain._
import pl.iterators.epoca.client.repositories.ClientRepository.ClientFilters
import pl.iterators.epoca.client.repositories.{ClientRepository, ClientRepositoryImpl}
import pl.iterators.epoca.organization.domain.Organization
import pl.iterators.epoca.organization.domain.OrganizationDomain._
import pl.iterators.epoca.organization.repositories.{OrganizationRepositoryImpl, OrganizationUserRepositoryImpl}
import pl.iterators.epoca.project.domain.ProjectDomain._
import pl.iterators.epoca.project.domain.{Project, ProjectUser}
import pl.iterators.epoca.project.repositories.ProjectRepository.ProjectFilters
import pl.iterators.epoca.project.repositories.ProjectUserRepository.ProjectUserFilters
import pl.iterators.epoca.project.repositories.{ProjectRepositoryImpl, ProjectUserRepositoryImpl}
import pl.iterators.epoca.project.services.ProjectCreateService.{ProjectCreateRequest, ProjectCreateResult}
import pl.iterators.epoca.project.services.{ProjectCreateService, ProjectDtoService}
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.domain.UserDomain.UserIsSuperAdmin
import pl.iterators.epoca.user.repositories.UserRepositoryImpl
import pl.iterators.epoca.utils.Generators
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import slick.dbio.DBIO
import slick.jdbc.SetParameter
import slick.util.AsyncExecutor

import java.sql.{SQLException, Types}
import java.util.UUID
import java.util.concurrent.TimeoutException
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.util.control.NonFatal

class ClientMutationConcurrencySpec extends SpecificationLike with Generators {
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
  private val executorName     = s"${getClass.getCanonicalName}-${UUID.randomUUID()}"

  private lazy val db = Database.forURL(
    url = databaseUrl,
    user = databaseUser,
    password = databasePassword,
    driver = "org.postgresql.Driver",
    executor = AsyncExecutor(executorName, minThreads = 2, maxThreads = 2, queueSize = 20, maxConnections = 2)
  )

  private lazy val observerDb = Database.forURL(
    url = databaseUrl,
    user = databaseUser,
    password = databasePassword,
    driver = "org.postgresql.Driver",
    executor = AsyncExecutor(s"$executorName-observer", minThreads = 1, maxThreads = 1, queueSize = 20, maxConnections = 1)
  )

  private val transactor                 = new PostgresIOTransactor(db)
  private val userRepository             = new UserRepositoryImpl()
  private val organizationRepository     = new OrganizationRepositoryImpl()
  private val organizationUserRepository = new OrganizationUserRepositoryImpl()
  private val clientRepository           = new ClientRepositoryImpl(ClientRepositoryImpl.Config(30.seconds))
  private val projectRepository          = new ProjectRepositoryImpl()
  private val projectUserRepository      = new ProjectUserRepositoryImpl()

  "client mutation serialization" should {
    "finish create before delete and archive every project created for the deleting tenant" in {
      val createControl = LockControl.paused
      val deleteControl = LockControl.unpaused
      val releaseCreate = createControl.release.get
      var fixtureOpt    = Option.empty[Fixture]
      var requests      = Vector.empty[(String, Future[_])]

      runWithTeardown {
        val fixture = setupFixture()
        fixtureOpt = Some(fixture)
        val (createService, deleteService) = services(createControl, deleteControl)
        val request                        = createRequest(fixture)

        val create = createService.create(fixture.authContext, fixture.organizationA.slug, request).unsafeToFuture()
        requests :+= "create-first project request" -> create
        await(createControl.acquired.future, "create-first advisory lock")

        val delete = deleteService.delete(fixture.authContext, fixture.organizationA.slug, fixture.client.id).unsafeToFuture()
        requests :+= "create-first delete request" -> delete
        val createPid = await(createControl.backendPid.future, "create-first backend PID")
        val deletePid = await(deleteControl.backendPid.future, "blocked delete backend PID")
        awaitAdvisoryBlock(createPid, deletePid) shouldEqual ((createPid, Some("Lock"), "advisory"))
        delete.isCompleted should beFalse

        releaseCreate.trySuccess(()) should beTrue
        await(create, "create-first project result") must beLike { case ProjectCreateResult.Created(_) => ok }
        await(delete, "delete-after-create result") shouldEqual ClientDeleteResult.Deleted

        val archivedClient = awaitDb(
          clientRepository.find(
            ClientFilters(id = Some(fixture.client.id), organizationIds = Some(Seq(fixture.organizationA.id)), includeArchived = true)
          ),
          "archived client query"
        )
        val projects = projectsForDeletingTenant(fixture, includeArchived = true)
        val projectUsers = awaitDb(
          projectUserRepository.list(ProjectUserFilters(projectIds = Some(projects.map(_.id)), includeArchived = true)),
          "archived project-user query"
        )

        archivedClient.flatMap(_.archivedAt) should beSome
        projects.size shouldEqual 2
        projects.forall(_.archivedAt.isDefined) should beTrue
        projectUsers.size shouldEqual 2
        projectUsers.forall(_.archivedAt.isDefined) should beTrue
      } {
        Seq[() => Unit] { () => releaseCreate.trySuccess(()); () } ++
          drainSteps(requests) :+
          (() => fixtureOpt.foreach(cleanup))
      }
    }

    "finish delete before create and reject creation beneath the archived client" in {
      val createControl = LockControl.unpaused
      val deleteControl = LockControl.paused
      val releaseDelete = deleteControl.release.get
      var fixtureOpt    = Option.empty[Fixture]
      var requests      = Vector.empty[(String, Future[_])]

      runWithTeardown {
        val fixture = setupFixture()
        fixtureOpt = Some(fixture)
        val (createService, deleteService) = services(createControl, deleteControl)
        val request                        = createRequest(fixture)

        val delete = deleteService.delete(fixture.authContext, fixture.organizationA.slug, fixture.client.id).unsafeToFuture()
        requests :+= "delete-first delete request" -> delete
        await(deleteControl.acquired.future, "delete-first advisory lock")

        val create = createService.create(fixture.authContext, fixture.organizationA.slug, request).unsafeToFuture()
        requests :+= "delete-first project request" -> create
        val deletePid = await(deleteControl.backendPid.future, "delete-first backend PID")
        val createPid = await(createControl.backendPid.future, "blocked create backend PID")
        awaitAdvisoryBlock(deletePid, createPid) shouldEqual ((deletePid, Some("Lock"), "advisory"))
        create.isCompleted should beFalse

        releaseDelete.trySuccess(()) should beTrue
        await(delete, "delete-first result") shouldEqual ClientDeleteResult.Deleted
        await(create, "create-after-delete result") shouldEqual ProjectCreateResult.ClientNotFound

        projectsForDeletingTenant(fixture, includeArchived = false) should beEmpty
        awaitDb(
          projectUserRepository.list(ProjectUserFilters(projectIds = Some(Seq(fixture.projectA.id)))),
          "active project-user query after delete-first"
        ) should beEmpty
      } {
        Seq[() => Unit] { () => releaseDelete.trySuccess(()); () } ++
          drainSteps(requests) :+
          (() => fixtureOpt.foreach(cleanup))
      }
    }

    "time out create on a downstream project relation lock without partial rows or exhausting the pool" in {
      var requests = Vector.empty[(String, Future[_])]

      withHeldProjectExclusiveLock(requests) { fixture =>
        val repository                    = new ClientRepositoryImpl(ClientRepositoryImpl.Config(150.millis))
        val (projectsBefore, usersBefore) = tenantProjectAndUserIds(fixture)
        val (createService, _)            = services(LockControl.unpaused, LockControl.unpaused, repository)
        val create = createService
          .create(fixture.authContext, fixture.organizationA.slug, createRequest(fixture))
          .unsafeToFuture()
        requests :+= "create blocked by project relation lock" -> create

        val failure = awaitFailure(create, 2.seconds, "create blocked by project relation lock")
        sqlState(failure) shouldEqual "55P03"
        awaitDbWithin(sql"SELECT 1".as[Int].head, 2.seconds, "create pool recovery query") shouldEqual 1
        tenantProjectAndUserIds(fixture) shouldEqual ((projectsBefore, usersBefore))
      }
    }

    "time out delete on the client advisory lock without mutating rows or exhausting the pool" in {
      var requests = Vector.empty[(String, Future[_])]

      withHeldClientMutationLock(requests) { fixture =>
        val repository         = new ClientRepositoryImpl(ClientRepositoryImpl.Config(150.millis))
        val clientBefore       = rowJson("client", fixture.client.id.asInstanceOf[UUID])
        val projectBefore      = rowJson("project", fixture.projectA.id.asInstanceOf[UUID])
        val projectUserBefore  = rowJson("project_user", fixture.projectUserA.id.asInstanceOf[UUID])
        val (_, deleteService) = services(LockControl.unpaused, LockControl.unpaused, repository)
        val delete = deleteService
          .delete(fixture.authContext, fixture.organizationA.slug, fixture.client.id)
          .unsafeToFuture()
        requests :+= "delete blocked by client advisory lock" -> delete

        val failure = awaitFailure(delete, 2.seconds, "delete blocked by client advisory lock")
        sqlState(failure) shouldEqual "55P03"
        awaitDbWithin(sql"SELECT 1".as[Int].head, 2.seconds, "delete pool recovery query") shouldEqual 1
        rowJson("client", fixture.client.id.asInstanceOf[UUID]) shouldEqual clientBefore
        rowJson("project", fixture.projectA.id.asInstanceOf[UUID]) shouldEqual projectBefore
        rowJson("project_user", fixture.projectUserA.id.asInstanceOf[UUID]) shouldEqual projectUserBefore
      }
    }

    "leave an inconsistent cross-tenant project and membership byte-identical" in {
      val createControl = LockControl.unpaused
      val deleteControl = LockControl.unpaused
      var fixtureOpt    = Option.empty[Fixture]

      runWithTeardown {
        val fixture = setupFixture()
        fixtureOpt = Some(fixture)
        val (_, deleteService) = services(createControl, deleteControl)
        val projectBefore      = rowJson("project", fixture.projectB.id.asInstanceOf[UUID])
        val projectUserBefore  = rowJson("project_user", fixture.projectUserB.id.asInstanceOf[UUID])

        deleteService.delete(fixture.authContext, fixture.organizationA.slug, fixture.client.id).unsafeRunSync() shouldEqual
          ClientDeleteResult.Deleted

        rowJson("project", fixture.projectB.id.asInstanceOf[UUID]) shouldEqual projectBefore
        rowJson("project_user", fixture.projectUserB.id.asInstanceOf[UUID]) shouldEqual projectUserBefore
        projectsForDeletingTenant(fixture, includeArchived = true).map(_.archivedAt.isDefined) shouldEqual Seq(true)
        awaitDb(
          projectUserRepository.list(ProjectUserFilters(projectIds = Some(Seq(fixture.projectA.id)), includeArchived = true)),
          "tenant-A archived project-user query"
        ).map(_.archivedAt.isDefined) shouldEqual Seq(true)
        awaitDb(
          projectRepository.find(ProjectFilters(id = Some(fixture.projectB.id), organizationIds = Some(Seq(fixture.organizationB.id)))),
          "tenant-B active project query"
        ) should beSome(fixture.projectB)
        awaitDb(
          projectUserRepository.find(ProjectUserFilters(projectIds = Some(Seq(fixture.projectB.id)))),
          "tenant-B active project-user query"
        ) should beSome(fixture.projectUserB)
      } {
        Seq(() => fixtureOpt.foreach(cleanup))
      }
    }
  }

  step(closeDatabases())

  private def services(
    createControl: LockControl,
    deleteControl: LockControl,
    repository: ClientRepository = clientRepository
  ): (ProjectCreateService, ClientDeleteService) = {
    val createClientRepository = new ObservedClientRepository(repository, createControl)
    val deleteClientRepository = new ObservedClientRepository(repository, deleteControl)
    val projectDtoService      = new ProjectDtoService(repository, projectUserRepository, userRepository)
    val createService = new ProjectCreateService(
      transactor,
      organizationRepository,
      organizationUserRepository,
      projectUserRepository,
      createClientRepository,
      projectRepository,
      projectDtoService
    )
    val deleteService = new ClientDeleteService(
      transactor,
      projectRepository,
      projectUserRepository,
      organizationRepository,
      organizationUserRepository,
      deleteClientRepository
    )
    createService -> deleteService
  }

  private def setupFixture(): Fixture = {
    val user = allGenerators[User].normal.generate.copy(isSuperAdmin = UserIsSuperAdmin(true), archivedAt = None)
    val organizationA = allGenerators[Organization].normal.generate.copy(
      slug = OrganizationSlug(s"ct532-a-${UUID.randomUUID()}"),
      createdBy = user.id,
      modifiedBy = user.id,
      archivedAt = None
    )
    val organizationB = allGenerators[Organization].normal.generate.copy(
      slug = OrganizationSlug(s"ct532-b-${UUID.randomUUID()}"),
      createdBy = user.id,
      modifiedBy = user.id,
      archivedAt = None
    )
    val client = allGenerators[Client].normal.generate.copy(
      organizationId = organizationA.id,
      createdBy = user.id,
      modifiedBy = user.id,
      archivedAt = None,
      archivedBy = None
    )
    val projectA = allGenerators[Project].normal.generate.copy(
      name = ProjectName(s"CT532 fixture A ${UUID.randomUUID()}"),
      organizationId = organizationA.id,
      clientId = client.id,
      createdBy = user.id,
      modifiedBy = user.id,
      archivedAt = None,
      archivedBy = None
    )
    val projectB = allGenerators[Project].normal.generate.copy(
      name = ProjectName(s"CT532 fixture B ${UUID.randomUUID()}"),
      organizationId = organizationB.id,
      clientId = client.id,
      createdBy = user.id,
      modifiedBy = user.id,
      archivedAt = None,
      archivedBy = None
    )
    val projectUserA = allGenerators[ProjectUser].normal.generate.copy(projectId = projectA.id, userId = user.id, archivedAt = None)
    val projectUserB = allGenerators[ProjectUser].normal.generate.copy(projectId = projectB.id, userId = user.id, archivedAt = None)
    val authContext  = allGenerators[AuthContext].normal.generate.copy(id = user.id, isSuperAdmin = UserIsSuperAdmin(true))
    val fixture =
      new Fixture(user, authContext, organizationA, organizationB, client, projectA, projectUserA, projectB, projectUserB)

    awaitDb(
      DBIO
        .seq(
          userRepository.insert(user),
          organizationRepository.insert(organizationA),
          organizationRepository.insert(organizationB),
          clientRepository.insert(client),
          projectRepository.insert(projectA),
          projectRepository.insert(projectB),
          projectUserRepository.insert(projectUserA),
          projectUserRepository.insert(projectUserB)
        )
        .transactionally,
      "committed client-mutation fixture setup"
    )
    fixture
  }

  private def createRequest(fixture: Fixture): ProjectCreateRequest =
    ProjectCreateRequest(
      name = ProjectName(s"CT532 concurrent ${UUID.randomUUID()}"),
      description = None,
      clientId = fixture.client.id,
      ownerIds = Set.empty,
      memberIds = Set.empty
    )

  private def projectsForDeletingTenant(fixture: Fixture, includeArchived: Boolean): Seq[Project] =
    awaitDb(
      projectRepository.list(
        ProjectFilters(
          organizationIds = Some(Seq(fixture.organizationA.id)),
          clientIds = Some(Seq(fixture.client.id)),
          includeArchived = includeArchived
        )
      ),
      "deleting-tenant project query"
    )

  private def tenantProjectAndUserIds(fixture: Fixture): (Set[ProjectId], Set[ProjectUserId]) = {
    val projects = projectsForDeletingTenant(fixture, includeArchived = true)
    val projectUsers = awaitDb(
      projectUserRepository.list(ProjectUserFilters(projectIds = Some(projects.map(_.id)), includeArchived = true)),
      "deleting-tenant project-user IDs"
    )
    projects.map(_.id).toSet -> projectUsers.map(_.id).toSet
  }

  private def rowJson(tableName: String, id: UUID): String = {
    val trustedTableName = tableName match {
      case "client"       => "client"
      case "project"      => "project"
      case "project_user" => "project_user"
      case other          => throw new IllegalArgumentException(s"Unsupported fixture table $other")
    }
    awaitDb(
      sql"""SELECT pg_catalog.row_to_json(row_value)::TEXT
             FROM #$trustedTableName AS row_value
             WHERE row_value.id = $id""".as[String].head,
      s"$trustedTableName fixture row snapshot"
    )
  }

  private def cleanup(fixture: Fixture): Unit = {
    val userId          = fixture.user.id.asInstanceOf[UUID]
    val organizationAId = fixture.organizationA.id.asInstanceOf[UUID]
    val organizationBId = fixture.organizationB.id.asInstanceOf[UUID]
    val clientId        = fixture.client.id.asInstanceOf[UUID]

    val residue = awaitDb(
      (for {
        _ <- sqlu"""DELETE FROM project_user
                     WHERE project_id IN (
                       SELECT id FROM project WHERE organization_id IN ($organizationAId, $organizationBId)
                     )"""
        _ <- sqlu"DELETE FROM project WHERE organization_id IN ($organizationAId, $organizationBId)"
        _ <- sqlu"DELETE FROM client WHERE id = $clientId"
        _ <- sqlu"DELETE FROM organization WHERE id IN ($organizationAId, $organizationBId)"
        _ <- sqlu"""DELETE FROM "user" WHERE id = $userId"""
        count <- sql"""SELECT
                         (SELECT pg_catalog.count(*) FROM project WHERE organization_id IN ($organizationAId, $organizationBId)) +
                         (SELECT pg_catalog.count(*) FROM client WHERE id = $clientId) +
                         (SELECT pg_catalog.count(*) FROM organization WHERE id IN ($organizationAId, $organizationBId)) +
                         (SELECT pg_catalog.count(*) FROM "user" WHERE id = $userId)""".as[Long].head
      } yield count).transactionally,
      "FK-ordered client-mutation fixture cleanup"
    )
    if (residue != 0L) throw new AssertionError(s"Client-mutation fixture cleanup left $residue rows")
  }

  private def withHeldProjectExclusiveLock[A](requests: => Seq[(String, Future[_])])(body: Fixture => A): A =
    withHeldFixtureLock("project exclusive relation lock", _ => sqlu"LOCK TABLE public.project IN EXCLUSIVE MODE".map(_ => ()), requests)(body)

  private def withHeldClientMutationLock[A](requests: => Seq[(String, Future[_])])(body: Fixture => A): A =
    withHeldFixtureLock("client mutation advisory lock", fixture => clientRepository.lockForMutation(fixture.client.id), requests)(body)

  private def withHeldFixtureLock[A](
    clue: String,
    acquire: Fixture => DBIO[Unit],
    requests: => Seq[(String, Future[_])]
  )(
    body: Fixture => A
  ): A = {
    val holderAcquired = Promise[Unit]()
    val releaseHolder  = Promise[Unit]()
    var fixtureOpt     = Option.empty[Fixture]
    var holderOpt      = Option.empty[Future[Unit]]

    runWithTeardown {
      val fixture = setupFixture()
      fixtureOpt = Some(fixture)
      val holder = db.run((for {
        _ <- acquire(fixture)
        _ <- DBIO.successful { holderAcquired.trySuccess(()); () }
        _ <- DBIO.from(releaseHolder.future)
      } yield ()).transactionally)
      holderOpt = Some(holder)
      await(holderAcquired.future, clue)
      body(fixture)
    } {
      Seq[() => Unit] { () =>
        releaseHolder.trySuccess(())
        holderOpt.foreach(holder => drain(holder, s"$clue holder transaction"))
        drainSteps(requests).foreach(_.apply())
        fixtureOpt.foreach(cleanup)
      }
    }
  }

  private def awaitAdvisoryBlock(firstBackendPid: Int, secondBackendPid: Int): (Int, Option[String], String) = {
    val deadline        = lockObservationTimeout.fromNow
    var lastObservation = Option.empty[(Int, Option[String], String)]

    while (deadline.hasTimeLeft()) {
      lastObservation = awaitObserver(
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
        "service-level client advisory-lock observation"
      )
      lastObservation.foreach(return _)
      Thread.sleep(lockPollingInterval.toMillis)
    }

    throw new AssertionError(
      s"Timed out after $lockObservationTimeout waiting for backend $secondBackendPid to wait on backend $firstBackendPid's advisory lock; " +
        s"lastObservation=$lastObservation"
    )
  }

  private def drainSteps(futures: Seq[(String, Future[_])]): Seq[() => Unit] =
    futures.map { case (clue, future) => () => drain(future, clue) }

  private def drain(future: Future[_], clue: String): Unit =
    try {
      Await.ready(future, requestTimeout)
      ()
    } catch {
      case timeout: TimeoutException => throw new AssertionError(s"Timed out draining $clue after $requestTimeout", timeout)
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

  private def sqlState(error: Throwable): String =
    error match {
      case sqlError: SQLException if sqlError.getSQLState != null       => sqlError.getSQLState
      case other if other.getCause != null && (other.getCause ne other) => sqlState(other.getCause)
      case other => throw new AssertionError(s"Expected SQLException with SQLSTATE, got ${other.getClass.getName}", other)
    }

  private def awaitDb[A](action: DBIO[A], clue: String): A =
    try Await.result(db.run(action), setupCleanupTimeout)
    catch {
      case timeout: TimeoutException => throw new AssertionError(s"Timed out waiting for $clue after $setupCleanupTimeout", timeout)
    }

  private def awaitDbWithin[A](
    action: DBIO[A],
    timeout: FiniteDuration,
    clue: String
  ): A =
    try Await.result(db.run(action), timeout)
    catch {
      case harnessTimeout: TimeoutException =>
        throw new AssertionError(s"Harness timed out waiting for $clue after $timeout", harnessTimeout)
    }

  private def awaitObserver[A](action: DBIO[A], clue: String): A =
    try Await.result(observerDb.run(action), setupCleanupTimeout)
    catch {
      case timeout: TimeoutException => throw new AssertionError(s"Timed out waiting for $clue after $setupCleanupTimeout", timeout)
    }

  private def closeDatabases(): Unit =
    runWithTeardown(db.close()) {
      Seq(() => observerDb.close())
    }

  private final class Fixture(
    val user: User,
    val authContext: AuthContext,
    val organizationA: Organization,
    val organizationB: Organization,
    val client: Client,
    val projectA: Project,
    val projectUserA: ProjectUser,
    val projectB: Project,
    val projectUserB: ProjectUser)

  private final class LockControl(
    val backendPid: Promise[Int],
    val acquired: Promise[Unit],
    val release: Option[Promise[Unit]])

  private object LockControl {
    def paused: LockControl   = new LockControl(Promise[Int](), Promise[Unit](), Some(Promise[Unit]()))
    def unpaused: LockControl = new LockControl(Promise[Int](), Promise[Unit](), None)
  }

  private final class ObservedClientRepository(delegate: ClientRepository, control: LockControl) extends ClientRepository {
    override def insert(client: Client): DBIO[Client]              = delegate.insert(client)
    override def update(client: Client): DBIO[Option[Client]]      = delegate.update(client)
    override def find(filter: ClientFilters): DBIO[Option[Client]] = delegate.find(filter)
    override def list(filter: ClientFilters): DBIO[Seq[Client]]    = delegate.list(filter)
    override def count(filter: ClientFilters): DBIO[Int]           = delegate.count(filter)

    override def lockForMutation(clientId: ClientId): DBIO[Unit] =
      for {
        pid <- sql"SELECT pg_catalog.pg_backend_pid()".as[Int].head
        _   <- DBIO.successful { control.backendPid.trySuccess(pid); () }
        _   <- delegate.lockForMutation(clientId)
        _   <- DBIO.successful { control.acquired.trySuccess(()); () }
        _   <- control.release.fold(DBIO.successful(()))(release => DBIO.from(release.future))
      } yield ()
  }
}
