package pl.iterators.epoca.oauth.fixture

import cats.effect.kernel.Outcome
import cats.effect.unsafe.IORuntime
import cats.effect.{Deferred, IO}
import com.typesafe.config.{Config, ConfigFactory, ConfigResolveOptions}
import org.mindrot.jbcrypt.BCrypt
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.client.repositories.ClientRepositoryImpl
import pl.iterators.epoca.oauth.domain.OAuthDomain._
import pl.iterators.epoca.oauth.fixture.OAuthE2EFixtureControlProtocol.{ControlDeadline, ControlRequestContext}
import pl.iterators.epoca.oauth.fixture.OAuthE2EFixtureDomain.AdvanceResult.{
  Applied => AdvanceApplied,
  Conflict => AdvanceConflict,
  Idempotent => AdvanceIdempotent
}
import pl.iterators.epoca.oauth.fixture.OAuthE2EFixtureDomain.FixtureAuditCommandCategory.{RevokeOAuth029, Setup}
import pl.iterators.epoca.oauth.fixture.OAuthE2EFixtureDomain.FixtureInitializationResult.ControllerClaimed
import pl.iterators.epoca.oauth.fixture.OAuthE2EFixtureDomain.{
  CleanupStep,
  CleanupStepState,
  ControllerInstanceId,
  FixtureAlias,
  FixtureOwnership,
  FixtureRunState
}
import pl.iterators.epoca.oauth.fixture.OAuthE2EFixtureRevokeResult.{
  Applied => RevokeApplied,
  Conflict => RevokeConflict,
  Idempotent => RevokeIdempotent
}
import pl.iterators.epoca.oauth.repositories.OAuthAuditRepository.OAuthAuditEventFilters
import pl.iterators.epoca.oauth.repositories.{OAuthAuditRepositoryImpl, OAuthClientRepositoryImpl}
import pl.iterators.epoca.oauth.services.OAuthClientManagementService.{
  OAuthClientManagementResult,
  OAuthClientPreparedCreate,
  OAuthClientVersionRequest
}
import pl.iterators.epoca.oauth.services.{OAuthClientManagementService, OAuthGrantabilityService, OAuthSecretService}
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationUserRole
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepositoryImpl, OrganizationUserRepositoryImpl}
import pl.iterators.epoca.project.domain.ProjectDomain.{ProjectId, ProjectName}
import pl.iterators.epoca.project.repositories.ProjectRepository.ProjectFilters
import pl.iterators.epoca.project.repositories.ProjectRepositoryImpl
import pl.iterators.epoca.user.repositories.{ServiceAccountRepositoryImpl, UserRepositoryImpl}
import pl.iterators.epoca.utils.PgRepositorySpec
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.workunit.domain.WorkUnit
import pl.iterators.epoca.workunit.domain.WorkUnitDomain._
import pl.iterators.epoca.workunit.repositories.WorkUnitRepository.WorkUnitFilters
import pl.iterators.epoca.workunit.repositories.WorkUnitRepositoryImpl
import slick.util.AsyncExecutor

import java.time.{Duration, Instant, LocalDate}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicLong, AtomicReference}
import java.util.{Base64, Properties, UUID}
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Promise}
import scala.util.control.NoStackTrace

class OAuthE2EFixtureServiceSpec extends PgRepositorySpec {
  sequential

  private implicit val ioRuntime: IORuntime = IORuntime.global

  private val ExpectedSetupStatementCount = 84

  private val fixtureRepository          = new OAuthE2EFixtureRepositoryImpl()
  private val authoritativeTime          = new FixtureOAuthAuthoritativeTime(fixtureRepository)
  private val userRepository             = new UserRepositoryImpl()
  private val organizationRepository     = new OrganizationRepositoryImpl()
  private val organizationUserRepository = new OrganizationUserRepositoryImpl()
  private val clientRepository           = new ClientRepositoryImpl()
  private val projectRepository          = new ProjectRepositoryImpl()
  private val workUnitRepository         = new WorkUnitRepositoryImpl()
  private val serviceAccountRepository   = new ServiceAccountRepositoryImpl()
  private val oauthClientRepository      = new OAuthClientRepositoryImpl(authoritativeTime)
  private val oauthAuditRepository       = new OAuthAuditRepositoryImpl()
  private val dummySecretHash            = OAuthClientSecretHash(BCrypt.hashpw("oauth-e2e-task5-dummy-secret", BCrypt.gensalt(12)))
  private val secretService              = new OAuthSecretService(dummySecretHash)
  private val transactor                 = new PostgresIOTransactor(db)
  private val managementService = new OAuthClientManagementService(
    transactor,
    authoritativeTime,
    new OAuthGrantabilityService(userRepository, organizationRepository, organizationUserRepository, projectRepository),
    serviceAccountRepository,
    oauthClientRepository,
    oauthAuditRepository,
    userRepository,
    secretService
  )
  private val successfulStatementCount = new AtomicInteger(0)
  private val commandExecutor = new OAuthE2EFixtureCommandExecutor(
    transactor,
    new PostgresOAuthE2EFixtureBackendCanceller(db),
    OAuthE2EFixtureControllerSupervisor.noop,
    statementCompleted = successfulStatementCount.set
  )

  "The OAuth E2E fixture service" should {
    "provision the exact four isolated aliases and their closed OAuth policies in one setup" in isolatedFixture {
      val runId          = UUID.randomUUID()
      val idempotencyKey = UUID.randomUUID()
      initializeController()

      val result = service
        .setup(runId, idempotencyKey, requestContext())
        .unsafeRunSync()
      val credentials = result match {
        case OAuthE2EFixtureSetupResult.Applied(value) => value
        case other                                     => throw new AssertionError(s"Expected applied setup, got $other")
      }
      val ownership = awaitDb(fixtureRepository.ownershipRows)
      val ledger    = awaitDb(fixtureRepository.ledger).getOrElse(throw new AssertionError("fixture ledger missing"))

      ownership.map(_.alias) shouldEqual FixtureAlias.values
      ownership.map(_.organizationId).distinct must haveSize(4)
      ownership.map(_.businessClientId).distinct must haveSize(4)
      ownership.map(_.projectP1Id).distinct must haveSize(4)
      ownership.map(_.projectP2Id).distinct must haveSize(4)
      ownership.map(_.foreignOrganizationId).distinct must haveSize(4)
      ownership.foreach { row =>
        if (row.organizationId == row.foreignOrganizationId)
          throw new AssertionError("Fixture foreign organization must stay isolated")
      }
      credentials.aliases shouldEqual FixtureAlias.values
      credentials.toString must not(contain("epoca_oauth_secret_v1_"))
      ledger.runId shouldEqual Some(runId)
      ledger.runState shouldEqual FixtureRunState.SetupComplete

      val snapshots = ownership.map(row => row.alias -> awaitDb(oauthClientRepository.lock(row.oauthClientId)).get).toMap
      snapshots(FixtureAlias.OAuth027).scopes shouldEqual Set(OAuthScope.ProjectsRead)
      snapshots(FixtureAlias.OAuth027).projectAccess shouldEqual
        OAuthProjectAccess.SelectedProjects(Set(ownershipFor(ownership, FixtureAlias.OAuth027).projectP1Id))
      snapshots(FixtureAlias.OAuth028).scopes shouldEqual Set(OAuthScope.OrganizationRead, OAuthScope.ProjectsRead)
      snapshots(FixtureAlias.OAuth028).projectAccess shouldEqual
        OAuthProjectAccess.SelectedProjects(Set(ownershipFor(ownership, FixtureAlias.OAuth028).projectP1Id))
      snapshots(FixtureAlias.OAuth029).scopes shouldEqual Set(OAuthScope.ProjectsRead)
      snapshots(FixtureAlias.OAuth029).projectAccess shouldEqual
        OAuthProjectAccess.SelectedProjects(Set(ownershipFor(ownership, FixtureAlias.OAuth029).projectP1Id))
      snapshots(FixtureAlias.General).scopes shouldEqual
        Set(OAuthScope.OrganizationRead, OAuthScope.ProjectsRead, OAuthScope.WorkUnitsWrite)
      snapshots(FixtureAlias.General).projectAccess shouldEqual OAuthProjectAccess.SelectedProjects(
        Set(ownershipFor(ownership, FixtureAlias.General).projectP1Id, ownershipFor(ownership, FixtureAlias.General).projectP2Id)
      )

      ownership.foreach { row =>
        awaitDb(oauthAuditRepository.list(OAuthAuditEventFilters(row.oauthClientId))).map(_.eventType) shouldEqual Seq(OAuthAuditEventType.Create)
      }
      successfulStatementCount.get() shouldEqual ExpectedSetupStatementCount
      success
    }

    "revoke only the run-owned oauth029 client and make the command single-use" in isolatedFixture {
      val runId = UUID.randomUUID()
      initializeController()
      service.setup(runId, UUID.randomUUID(), requestContext()).unsafeRunSync() must beLike { case OAuthE2EFixtureSetupResult.Applied(_) =>
        ok
      }
      val ownership = ownershipFor(awaitDb(fixtureRepository.ownershipRows), FixtureAlias.OAuth029)
      val before    = awaitDb(oauthClientRepository.lock(ownership.oauthClientId)).get
      val revokeKey = UUID.randomUUID()

      service.revokeOAuth029(runId, revokeKey, requestContext()).unsafeRunSync() shouldEqual RevokeApplied
      service.revokeOAuth029(runId, revokeKey, requestContext()).unsafeRunSync() shouldEqual RevokeIdempotent
      service.revokeOAuth029(runId, UUID.randomUUID(), requestContext()).unsafeRunSync() shouldEqual RevokeConflict

      val after         = awaitDb(oauthClientRepository.lock(ownership.oauthClientId)).get
      val account       = awaitDb(serviceAccountRepository.findById(after.client.serviceAccountUserId)).get
      val membership    = awaitDb(serviceAccountRepository.findMembership(after.client.serviceAccountUserId, after.client.organizationId)).get
      val audits        = awaitDb(oauthAuditRepository.list(OAuthAuditEventFilters(after.client.id)))
      val fixtureAudits = awaitDb(fixtureRepository.auditRows)

      (before.client.status shouldEqual OAuthClientStatus.Active) and
        (after.client.status shouldEqual OAuthClientStatus.Revoked) and
        (account.archivedAt should beSome) and
        (membership.archivedAt should beSome) and
        (audits.map(_.eventType) shouldEqual Seq(OAuthAuditEventType.Revoke, OAuthAuditEventType.Create)) and
        (fixtureAudits.map(_.commandCategory) shouldEqual Seq(Setup, RevokeOAuth029)) and
        (awaitDb(fixtureRepository.ledger).get.acceptedCommandCount shouldEqual 2)
    }

    "advance exactly 300 seconds only for the owned run and make the command single-use" in isolatedFixture {
      val runId = UUID.randomUUID()
      initializeController()
      service.setup(runId, UUID.randomUUID(), requestContext()).unsafeRunSync() must beLike { case OAuthE2EFixtureSetupResult.Applied(_) =>
        ok
      }
      val key = UUID.randomUUID()

      service.advance300(runId, key, requestContext()).unsafeRunSync() should beLike { case AdvanceApplied(0, 300, _, 2) =>
        ok
      }
      service.advance300(runId, key, requestContext()).unsafeRunSync() should beLike { case AdvanceIdempotent(300, _, 2) =>
        ok
      }
      service.advance300(UUID.randomUUID(), UUID.randomUUID(), requestContext()).unsafeRunSync() should beLike { case AdvanceConflict(300, _, 2) =>
        ok
      }
    }

    "reconcile a committed oauth029 revoke when the separate command marker response is lost" in isolatedFixture {
      val markerLost   = new AtomicBoolean(false)
      val lossyService = serviceWith(commandExecutor, afterRevoke = IO.raiseWhen(markerLost.compareAndSet(false, true))(SyntheticRevokeMarkerLoss))
      val runId        = UUID.randomUUID()
      val revokeKey    = UUID.randomUUID()
      initializeController()
      lossyService.setup(runId, UUID.randomUUID(), requestContext()).unsafeRunSync() must beLike { case OAuthE2EFixtureSetupResult.Applied(_) =>
        ok
      }
      val ownership = ownershipFor(awaitDb(fixtureRepository.ownershipRows), FixtureAlias.OAuth029)

      thrownBy(lossyService.revokeOAuth029(runId, revokeKey, requestContext()).unsafeRunSync()) shouldEqual SyntheticRevokeMarkerLoss
      awaitDb(oauthClientRepository.lock(ownership.oauthClientId)).get.client.status shouldEqual OAuthClientStatus.Revoked
      awaitDb(fixtureRepository.auditRows).map(_.commandCategory) shouldEqual Seq(Setup)
      val revokeDigest = OAuthE2EFixtureDomain.IdempotencyKeyDigest.sha256(revokeKey.toString)
      awaitDb(
        fixtureRepository.admitNonCleanupCommand(OAuthE2EFixtureDomain.FixtureAuditCommandCategory.Advance300Seconds, revokeDigest)((_, _) =>
          DBIO.successful(())
        )
      ) must beLike { case OAuthE2EFixtureDomain.NonCleanupCommandAdmissionResult.Conflict(_, _, _) => ok }

      lossyService.revokeOAuth029(runId, revokeKey, requestContext()).unsafeRunSync() shouldEqual RevokeIdempotent
      lossyService.revokeOAuth029(runId, UUID.randomUUID(), requestContext()).unsafeRunSync() shouldEqual RevokeConflict
      awaitDb(oauthAuditRepository.list(OAuthAuditEventFilters(ownership.oauthClientId)))
        .count(_.eventType == OAuthAuditEventType.Revoke) shouldEqual 1
      val fixtureAudits = awaitDb(fixtureRepository.auditRows)
      fixtureAudits.map(_.commandCategory) shouldEqual Seq(Setup, RevokeOAuth029)
      fixtureAudits.filter(_.idempotencyKeyDigest == revokeDigest).map(_.commandCategory) shouldEqual Seq(RevokeOAuth029)
    }

    "clean an owned run through control auth after every OAuth client is already revoked" in isolatedFixture {
      val runId      = UUID.randomUUID()
      val cleanupKey = UUID.randomUUID()
      initializeController()
      val setupResult = serviceWith(commandExecutor, preparedCreate = Some(() => fastPreparedCreate()))
        .setup(runId, UUID.randomUUID(), requestContext())
        .unsafeRunSync()
      setupResult must beLike { case OAuthE2EFixtureSetupResult.Applied(credentials) =>
        credentials.discardRemaining().unsafeRunSync()
        ok
      }
      val ownership = awaitDb(fixtureRepository.ownershipRows)
      ownership.foreach(revokeForCleanup)

      service.cleanup(runId, cleanupKey, cleanupRequestContext()).unsafeRunSync() shouldEqual OAuthE2EFixtureCleanupResult.Cleaned
      service.cleanup(runId, cleanupKey, cleanupRequestContext()).unsafeRunSync() shouldEqual OAuthE2EFixtureCleanupResult.AlreadyCleaned
      service.cleanup(runId, UUID.randomUUID(), cleanupRequestContext()).unsafeRunSync() shouldEqual OAuthE2EFixtureCleanupResult.Conflict
      awaitDb(fixtureRepository.ledger).map(_.runState) shouldEqual Some(FixtureRunState.Cleaned)
      ownership.foreach { owned =>
        awaitDb(oauthAuditRepository.list(OAuthAuditEventFilters(owned.oauthClientId))).count(_.eventType == OAuthAuditEventType.Revoke) shouldEqual 1
      }
      success
    }

    "accept one legitimate preexisting revoke without rewriting its correlation or duplicating its audit" in isolatedFixture {
      val runId      = UUID.randomUUID()
      val cleanupKey = UUID.randomUUID()
      initializeController()
      serviceWith(commandExecutor, preparedCreate = Some(() => fastPreparedCreate()))
        .setup(runId, UUID.randomUUID(), requestContext())
        .unsafeRunSync() must beLike { case OAuthE2EFixtureSetupResult.Applied(credentials) =>
        credentials.discardRemaining().unsafeRunSync()
        ok
      }
      val ownership    = ownershipFor(awaitDb(fixtureRepository.ownershipRows), FixtureAlias.General)
      val organization = awaitDb(organizationRepository.find(OrganizationFilters(id = Some(ownership.organizationId), includeArchived = true))).get
      val client       = awaitDb(oauthClientRepository.lock(ownership.oauthClientId)).get
      managementService
        .revoke(
          ownerAuth(ownership),
          organization.slug,
          client.client.clientId,
          OAuthClientVersionRequest(client.client.version.asInstanceOf[Long]),
          correlationId = None
        )
        .unsafeRunSync() must beLike { case OAuthClientManagementResult.Success(value) if value.status == OAuthClientEffectiveStatus.Revoked => ok }
      val terminalClient = awaitDb(oauthClientRepository.lock(ownership.oauthClientId)).get
      val account        = awaitDb(serviceAccountRepository.findById(terminalClient.client.serviceAccountUserId)).get
      val membership =
        awaitDb(serviceAccountRepository.findMembership(terminalClient.client.serviceAccountUserId, terminalClient.client.organizationId)).get
      val priorAudits = awaitDb(oauthAuditRepository.list(OAuthAuditEventFilters(ownership.oauthClientId)))
      terminalClient.client.organizationId shouldEqual ownership.organizationId
      terminalClient.client.status shouldEqual OAuthClientStatus.Revoked
      account.archivedAt should beSome
      membership.archivedAt should beSome
      priorAudits.filter(_.eventType == OAuthAuditEventType.Revoke) must haveSize(1)
      priorAudits.find(_.eventType == OAuthAuditEventType.Revoke).flatMap(_.correlationId) should beNone

      service.cleanup(runId, cleanupKey, cleanupRequestContext()).unsafeRunSync() shouldEqual OAuthE2EFixtureCleanupResult.Cleaned
      val afterAudits = awaitDb(oauthAuditRepository.list(OAuthAuditEventFilters(ownership.oauthClientId)))
      afterAudits.filter(row => row.eventType == OAuthAuditEventType.Revoke && row.outcome == OAuthAuditOutcome.Success) must haveSize(1)
      afterAudits.find(_.eventType == OAuthAuditEventType.Revoke).flatMap(_.correlationId) should beNone
      success
    }

    "archive only active work units on fixed owned projects" in isolatedFixture {
      val runId      = UUID.randomUUID()
      val cleanupKey = UUID.randomUUID()
      initializeController()
      serviceWith(commandExecutor, preparedCreate = Some(() => fastPreparedCreate()))
        .setup(runId, UUID.randomUUID(), requestContext())
        .unsafeRunSync() must beLike { case OAuthE2EFixtureSetupResult.Applied(credentials) =>
        credentials.discardRemaining().unsafeRunSync()
        ok
      }
      val ownership    = ownershipFor(awaitDb(fixtureRepository.ownershipRows), FixtureAlias.General)
      val fixedProject = awaitDb(projectRepository.find(ProjectFilters(id = Some(ownership.projectP1Id), includeArchived = true))).get
      val unrelatedProject = awaitDb(
        projectRepository
          .insert(fixedProject.copy(id = ProjectId(UUID.randomUUID()), name = ProjectName(s"oauth-e2e-unrelated-${UUID.randomUUID()}")))
      )
      val createdAt        = Instant.parse("2026-08-21T08:00:00Z")
      val stableArchivedAt = Instant.parse("2026-08-21T08:01:00Z")
      def workUnit(
        projectId: ProjectId,
        state: WorkUnitState,
        archivedAt: Option[Instant]
      ) = WorkUnit(
        id = WorkUnitId(UUID.randomUUID()),
        userId = ownership.ownerUserId,
        organizationId = ownership.organizationId,
        projectId = projectId,
        date = WorkUnitDate(LocalDate.of(2026, 8, 21)),
        duration = WorkUnitDuration(Duration.ofHours(1)),
        entryType = WorkUnitEntryType.Worklog,
        description = WorkUnitDescription("oauth-e2e cleanup ownership control"),
        taskId = None,
        state = state,
        createdAt = WorkUnitCreatedAt(createdAt),
        modifiedAt = WorkUnitModifiedAt(createdAt),
        archivedAt = archivedAt.map(WorkUnitArchivedAt.apply)
      )
      val activeOwned     = awaitDb(workUnitRepository.insert(workUnit(ownership.projectP1Id, WorkUnitState.Active, None)))
      val archivedOwned   = awaitDb(workUnitRepository.insert(workUnit(ownership.projectP1Id, WorkUnitState.Archived, Some(stableArchivedAt))))
      val unrelatedActive = awaitDb(workUnitRepository.insert(workUnit(unrelatedProject.id, WorkUnitState.Active, None)))

      service.cleanup(runId, cleanupKey, cleanupRequestContext()).unsafeRunSync() shouldEqual OAuthE2EFixtureCleanupResult.Cleaned
      def stored(id: WorkUnitId) =
        awaitDb(workUnitRepository.find(WorkUnitFilters(id = Some(id), states = Some(WorkUnitState.values), includeArchived = true)))
          .getOrElse(throw new AssertionError(s"work unit $id missing"))
      val activeAfter    = stored(activeOwned.id)
      val archivedAfter  = stored(archivedOwned.id)
      val unrelatedAfter = stored(unrelatedActive.id)

      activeAfter.state shouldEqual WorkUnitState.Archived
      activeAfter.archivedAt should beSome
      activeAfter.projectId shouldEqual ownership.projectP1Id
      activeAfter.organizationId shouldEqual ownership.organizationId
      archivedAfter shouldEqual archivedOwned
      archivedAfter.archivedAt shouldEqual Some(WorkUnitArchivedAt(stableArchivedAt))
      unrelatedAfter shouldEqual unrelatedActive
      unrelatedAfter.state shouldEqual WorkUnitState.Active
      unrelatedAfter.archivedAt should beNone
      unrelatedAfter.projectId shouldEqual unrelatedProject.id
      success
    }

    "fail the resource marker when a committed work unit is reactivated and reconcile it on retry" in isolatedFixture {
      val runId          = UUID.randomUUID()
      val cleanupKey     = UUID.randomUUID()
      val tampered       = new AtomicBoolean(false)
      val testWorkUnitId = WorkUnitId(UUID.randomUUID())
      initializeController()
      val faulting = serviceWith(
        commandExecutor,
        preparedCreate = Some(() => fastPreparedCreate()),
        afterCleanupResourceCommit = alias =>
          IO.whenA(alias == FixtureAlias.General && tampered.compareAndSet(false, true)) {
            IO {
              val current = awaitDb(
                workUnitRepository.find(WorkUnitFilters(id = Some(testWorkUnitId), states = Some(WorkUnitState.values), includeArchived = true))
              ).get
              val _ = awaitDb(workUnitRepository.update(current.copy(state = WorkUnitState.Active, archivedAt = None)))
                .getOrElse(throw new AssertionError("work unit tamper update failed"))
              ()
            }
          }
      )
      faulting.setup(runId, UUID.randomUUID(), requestContext()).unsafeRunSync() must beLike { case OAuthE2EFixtureSetupResult.Applied(credentials) =>
        credentials.discardRemaining().unsafeRunSync()
        ok
      }
      val ownership = ownershipFor(awaitDb(fixtureRepository.ownershipRows), FixtureAlias.General)
      val inserted =
        awaitDb(workUnitRepository.insert(workUnitForCleanup(testWorkUnitId, ownership, ownership.projectP1Id, WorkUnitState.Active, None)))

      thrownBy(faulting.cleanup(runId, cleanupKey, cleanupRequestContext()).unsafeRunSync()) must beAnInstanceOf[IllegalStateException]
      val afterTamper =
        awaitDb(workUnitRepository.find(WorkUnitFilters(id = Some(inserted.id), states = Some(WorkUnitState.values), includeArchived = true))).get
      afterTamper.state shouldEqual WorkUnitState.Active
      afterTamper.archivedAt should beNone
      awaitDb(fixtureRepository.ledger).get.cleanupSteps(CleanupStep.GeneralResources) shouldEqual CleanupStepState.InProgress

      faulting.cleanup(runId, cleanupKey, cleanupRequestContext()).unsafeRunSync() shouldEqual OAuthE2EFixtureCleanupResult.Cleaned
      val reconciled =
        awaitDb(workUnitRepository.find(WorkUnitFilters(id = Some(inserted.id), states = Some(WorkUnitState.values), includeArchived = true))).get
      reconciled.state shouldEqual WorkUnitState.Archived
      reconciled.archivedAt should beSome
      success
    }

    "fail the resource marker when a committed project relationship changes and complete only after restoration" in isolatedFixture {
      val runId      = UUID.randomUUID()
      val cleanupKey = UUID.randomUUID()
      val tampered   = new AtomicBoolean(false)
      initializeController()
      val ownershipRef = new AtomicReference[Option[FixtureOwnership]](None)
      val faulting = serviceWith(
        commandExecutor,
        preparedCreate = Some(() => fastPreparedCreate()),
        afterCleanupResourceCommit = alias =>
          IO.whenA(alias == FixtureAlias.General && tampered.compareAndSet(false, true)) {
            IO {
              val ownership = ownershipRef.get().getOrElse(throw new AssertionError("fixture ownership missing"))
              val current   = awaitDb(projectRepository.find(ProjectFilters(id = Some(ownership.projectP1Id), includeArchived = true))).get
              val _ = awaitDb(projectRepository.update(current.copy(clientId = ownership.foreignBusinessClientId)))
                .getOrElse(throw new AssertionError("project relationship tamper failed"))
              ()
            }
          }
      )
      faulting.setup(runId, UUID.randomUUID(), requestContext()).unsafeRunSync() must beLike { case OAuthE2EFixtureSetupResult.Applied(credentials) =>
        credentials.discardRemaining().unsafeRunSync()
        ok
      }
      val ownership = ownershipFor(awaitDb(fixtureRepository.ownershipRows), FixtureAlias.General)
      ownershipRef.set(Some(ownership))

      thrownBy(faulting.cleanup(runId, cleanupKey, cleanupRequestContext()).unsafeRunSync()) must beAnInstanceOf[IllegalStateException]
      val afterTamper = awaitDb(projectRepository.find(ProjectFilters(id = Some(ownership.projectP1Id), includeArchived = true))).get
      afterTamper.clientId shouldEqual ownership.foreignBusinessClientId
      awaitDb(fixtureRepository.ledger).get.cleanupSteps(CleanupStep.GeneralResources) shouldEqual CleanupStepState.InProgress
      val _ = awaitDb(projectRepository.update(afterTamper.copy(clientId = ownership.businessClientId)))
        .getOrElse(throw new AssertionError("project relationship restore failed"))

      faulting.cleanup(runId, cleanupKey, cleanupRequestContext()).unsafeRunSync() shouldEqual OAuthE2EFixtureCleanupResult.Cleaned
      val restored = awaitDb(projectRepository.find(ProjectFilters(id = Some(ownership.projectP1Id), includeArchived = true))).get
      restored.clientId shouldEqual ownership.businessClientId
      restored.archivedAt should beSome
      success
    }

    "fail closed before archiving a fixed project whose stored ownership relationships were replaced" in isolatedFixture {
      val runId = UUID.randomUUID()
      initializeController()
      serviceWith(commandExecutor, preparedCreate = Some(() => fastPreparedCreate()))
        .setup(runId, UUID.randomUUID(), requestContext())
        .unsafeRunSync() must beLike { case OAuthE2EFixtureSetupResult.Applied(credentials) =>
        credentials.discardRemaining().unsafeRunSync()
        ok
      }
      val ownership = ownershipFor(awaitDb(fixtureRepository.ownershipRows), FixtureAlias.OAuth027)
      val foreignProject = awaitDb(
        projectRepository.find(
          pl.iterators.epoca.project.repositories.ProjectRepository.ProjectFilters(id = Some(ownership.foreignProjectId), includeArchived = true)
        )
      ).get
      awaitDb(
        projectRepository.update(foreignProject.copy(organizationId = ownership.organizationId, clientId = ownership.businessClientId))
      ) must beSome

      thrownBy(service.cleanup(runId, UUID.randomUUID(), cleanupRequestContext()).unsafeRunSync()) must beLike { case error: IllegalStateException =>
        error.getMessage shouldEqual "Fixture cleanup project ownership failed closed"
      }
      val after = awaitDb(
        projectRepository.find(
          pl.iterators.epoca.project.repositories.ProjectRepository.ProjectFilters(id = Some(ownership.foreignProjectId), includeArchived = true)
        )
      ).get
      after.archivedAt should beNone
      awaitDb(fixtureRepository.ledger).get.cleanupSteps(CleanupStep.OAuth027Resources) shouldEqual CleanupStepState.InProgress
    }

    "resume every client cleanup crash window without a duplicate management audit" in {
      val phases = Vector[CleanupFaultPhase](
        CleanupFaultPhase.PreRevokeCommit,
        CleanupFaultPhase.PostRevokeCommit,
        CleanupFaultPhase.PostRevocationMarker,
        CleanupFaultPhase.PostResourceCommit,
        CleanupFaultPhase.PostResourceMarker
      )

      phases.foreach { phase =>
        FixtureAlias.values.foreach { targetAlias =>
          isolatedFixture {
            val failed = new AtomicBoolean(false)
            val runId  = UUID.randomUUID()
            val key    = UUID.randomUUID()
            initializeController()
            val faulting = serviceWith(
              commandExecutor,
              preparedCreate = Some(() => fastPreparedCreate()),
              beforeCleanupRevokeCommit = alias => faultDbioOnce(phase == CleanupFaultPhase.PreRevokeCommit && alias == targetAlias, failed),
              afterCleanupRevokeCommit = alias => faultIoOnce(phase == CleanupFaultPhase.PostRevokeCommit && alias == targetAlias, failed),
              afterCleanupRevocationMarker = alias => faultIoOnce(phase == CleanupFaultPhase.PostRevocationMarker && alias == targetAlias, failed),
              afterCleanupResourceCommit = alias => faultIoOnce(phase == CleanupFaultPhase.PostResourceCommit && alias == targetAlias, failed),
              afterCleanupResourceMarker = alias => faultIoOnce(phase == CleanupFaultPhase.PostResourceMarker && alias == targetAlias, failed)
            )
            faulting.setup(runId, UUID.randomUUID(), requestContext()).unsafeRunSync() must beLike {
              case OAuthE2EFixtureSetupResult.Applied(credentials) =>
                credentials.discardRemaining().unsafeRunSync()
                ok
            }

            thrownBy(faulting.cleanup(runId, key, cleanupRequestContext()).unsafeRunSync()) shouldEqual SyntheticCleanupFault
            val afterFault = awaitDb(fixtureRepository.ledger).get
            afterFault.runState shouldEqual FixtureRunState.CleanupRequired
            Set(CleanupStepState.InProgress, CleanupStepState.Complete) must contain(afterFault.cleanupSteps(revocationStep(targetAlias)))

            faulting.cleanup(runId, key, cleanupRequestContext()).unsafeRunSync() shouldEqual OAuthE2EFixtureCleanupResult.Cleaned
            val ownership = awaitDb(fixtureRepository.ownershipRows)
            ownership.foreach { owned =>
              awaitDb(oauthAuditRepository.list(OAuthAuditEventFilters(owned.oauthClientId)))
                .count(_.eventType == OAuthAuditEventType.Revoke) shouldEqual 1
            }
            val completed = awaitDb(fixtureRepository.ledger).get
            completed.runState shouldEqual FixtureRunState.Cleaned
            completed.cleanupSteps.values.toSet shouldEqual Set(CleanupStepState.Complete)
          }
        }
      }
      success
    }

    "serialize same-key and conflicting cleanup callers on the real ledger lock" in {
      verifyConcurrentCleanup(sameKey = true)
      verifyConcurrentCleanup(sameKey = false)
      success
    }

    "reconcile cleanup racing the real oauth029 revoke in both commit orders" in {
      verifyCleanupRevokeRace(cleanupStartsDuringPublicRevoke = true)
      verifyCleanupRevokeRace(cleanupStartsDuringPublicRevoke = false)
      success
    }

    "share one cumulative cleanup deadline across every saga transaction" in isolatedFixture {
      val clock      = new AtomicLong(0L)
      val stopCount  = new AtomicInteger(0)
      val runId      = UUID.randomUUID()
      val cleanupKey = UUID.randomUUID()
      initializeController()
      serviceWith(commandExecutor, preparedCreate = Some(() => fastPreparedCreate()))
        .setup(runId, UUID.randomUUID(), requestContext())
        .unsafeRunSync() must beLike { case OAuthE2EFixtureSetupResult.Applied(credentials) =>
        credentials.discardRemaining().unsafeRunSync()
        ok
      }
      val boundedExecutor = new OAuthE2EFixtureCommandExecutor(
        transactor,
        new PostgresOAuthE2EFixtureBackendCanceller(db),
        new OAuthE2EFixtureControllerSupervisor {
          override def stopControllerAndDestroyRun: IO[Unit] = IO(stopCount.incrementAndGet()).void
        },
        nanoTime = clock.get,
        statementCompleted = _ => { clock.addAndGet(1000000000L); () }
      )
      val boundedService = serviceWith(boundedExecutor, preparedCreate = Some(() => fastPreparedCreate()))
      val context        = ControlRequestContext(ControlDeadline.testing(admittedAtNanos = 0L, activeNanos = 15000000000L, totalNanos = 20000000000L))

      boundedService.cleanup(runId, cleanupKey, context).unsafeRunSync() shouldEqual OAuthE2EFixtureCleanupResult.TimedOut
      stopCount.get() shouldEqual 0
      val ledgerAfter = awaitDb(fixtureRepository.ledger).get
      ledgerAfter.runState shouldEqual FixtureRunState.CleanupRequired
      ledgerAfter.cleanupSteps.values.count(_ == CleanupStepState.Complete) must be_<(CleanupStep.values.size)
      val auditAfter = awaitDb(fixtureRepository.auditRows)
      awaitDb(fixtureRepository.auditRows) shouldEqual auditAfter
      success
    }

    "settle a final held resource lock before returning timed out and never mutate after release" in isolatedFixture {
      val runId      = UUID.randomUUID()
      val cleanupKey = UUID.randomUUID()
      val lockDb     = independentDatabase("cleanup-final-resource-lock", maximumConnections = 1)
      val cleanupDb  = independentDatabase("cleanup-final-resource-cleanup", maximumConnections = 1)
      val cancelDb   = independentDatabase("cleanup-final-resource-cancel", maximumConnections = 1)
      val observerDb = independentDatabase("cleanup-final-resource-observer", maximumConnections = 1)
      val lockReady  = Promise[Int]()
      val release    = Promise[Unit]()
      initializeController()
      serviceWith(commandExecutor, preparedCreate = Some(() => fastPreparedCreate()))
        .setup(runId, UUID.randomUUID(), requestContext())
        .unsafeRunSync() must beLike { case OAuthE2EFixtureSetupResult.Applied(credentials) =>
        credentials.discardRemaining().unsafeRunSync()
        ok
      }
      val general = ownershipFor(awaitDb(fixtureRepository.ownershipRows), FixtureAlias.General)
      val ids     = Vector(general.projectP1Id, general.projectP2Id, general.foreignProjectId).sortBy(_.toString)
      val holder = lockDb.run(
        sql"select pg_backend_pid()"
          .as[Int]
          .head
          .flatMap { pid =>
            projectRepository
              .lockOrdered(pl.iterators.epoca.project.repositories.ProjectRepository.ProjectFilters(ids = Some(ids), includeArchived = true))
              .flatMap(_ => DBIO.successful { lockReady.trySuccess(pid); () } >> DBIO.from(release.future))
          }
          .transactionally
      )
      val blockerPid = Await.result(lockReady.future, 10.seconds)
      val cleanupExecutor = new OAuthE2EFixtureCommandExecutor(
        new PostgresIOTransactor(cleanupDb),
        new PostgresOAuthE2EFixtureBackendCanceller(cancelDb),
        OAuthE2EFixtureControllerSupervisor.noop
      )
      val cleanupService = serviceWith(cleanupExecutor, preparedCreate = Some(() => fastPreparedCreate()))
      val context        = ControlRequestContext(ControlDeadline.testing(System.nanoTime(), activeNanos = 3000000000L, totalNanos = 5000000000L))

      try {
        val cleanupFiber = cleanupService.cleanup(runId, cleanupKey, context).start.unsafeRunSync()
        awaitAnyBackendBlockedBy(observerDb, blockerPid) should beTrue
        cleanupFiber.joinWithNever.unsafeRunSync() shouldEqual OAuthE2EFixtureCleanupResult.TimedOut
        val beforeRelease = awaitDb(fixtureRepository.ledger).get
        beforeRelease.runState shouldEqual FixtureRunState.CleanupRequired
        beforeRelease.cleanupSteps(CleanupStep.GeneralResources) shouldEqual CleanupStepState.InProgress
        release.trySuccess(()) should beTrue
        Await.result(holder, 10.seconds)
        val generalProject = awaitDb(
          projectRepository
            .find(pl.iterators.epoca.project.repositories.ProjectRepository.ProjectFilters(id = Some(general.projectP1Id), includeArchived = true))
        ).get
        generalProject.archivedAt should beNone
        awaitDb(fixtureRepository.ledger).get.cleanupSteps(CleanupStep.GeneralResources) shouldEqual CleanupStepState.InProgress
      } finally {
        release.trySuccess(())
        lockDb.close()
        cleanupDb.close()
        cancelDb.close()
        observerDb.close()
      }
      success
    }

    "cancel and settle a prepared oauth029 revoke blocked on its owned client without a delayed mutation" in isolatedFixture {
      val runId = UUID.randomUUID()
      initializeController()
      service.setup(runId, UUID.randomUUID(), requestContext()).unsafeRunSync() must beLike { case OAuthE2EFixtureSetupResult.Applied(_) =>
        ok
      }
      val ownership        = ownershipFor(awaitDb(fixtureRepository.ownershipRows), FixtureAlias.OAuth029)
      val lockReady        = Promise[Unit]()
      val releaseLock      = Promise[Unit]()
      val observedPid      = Promise[Int]()
      val lockDatabase     = independentDatabase("integration-revoke-lock", maximumConnections = 1)
      val cancelDatabase   = independentDatabase("integration-revoke-cancel", maximumConnections = 1)
      val observerDatabase = independentDatabase("integration-revoke-observer", maximumConnections = 1)
      val lockHolder = lockDatabase.run(
        oauthClientRepository
          .lock(ownership.oauthClientId)
          .flatMap(_ => DBIO.successful { lockReady.trySuccess(()); () } >> DBIO.from(releaseLock.future))
          .transactionally
      )
      Await.result(lockReady.future, 5.seconds)
      val executor = new OAuthE2EFixtureCommandExecutor(
        transactor,
        new PostgresOAuthE2EFixtureBackendCanceller(cancelDatabase),
        OAuthE2EFixtureControllerSupervisor.noop,
        backendPidObserved = pid => { observedPid.trySuccess(pid); () }
      )
      val blockedService = serviceWith(executor)
      val context        = ControlRequestContext(ControlDeadline.testing(System.nanoTime(), activeNanos = 300000000L, totalNanos = 2000000000L))

      try {
        val result = blockedService.revokeOAuth029(runId, UUID.randomUUID(), context).unsafeToFuture()
        val pid    = Await.result(observedPid.future, 5.seconds)
        awaitBackendWaitingOnLock(observerDatabase, pid) should beTrue
        Await.result(result, 3.seconds) shouldEqual OAuthE2EFixtureRevokeResult.TimedOut
      } finally {
        val _ = releaseLock.trySuccess(())
        Await.ready(lockHolder, 5.seconds)
        val _ = lockDatabase.close()
        val _ = cancelDatabase.close()
        val _ = observerDatabase.close()
      }

      assertRevokeRolledBack(ownership)
    }

    "roll back a prepared oauth029 revoke when the active deadline expires after the real client mutation" in isolatedFixture {
      val runId = UUID.randomUUID()
      initializeController()
      service.setup(runId, UUID.randomUUID(), requestContext()).unsafeRunSync() must beLike { case OAuthE2EFixtureSetupResult.Applied(_) =>
        ok
      }
      val ownership       = ownershipFor(awaitDb(fixtureRepository.ownershipRows), FixtureAlias.OAuth029)
      val expiringService = serviceWith(commandExecutor, afterOwnedClientRevoke = sql"select pg_sleep(1)".as[Unit].head)
      val context         = ControlRequestContext(ControlDeadline.testing(System.nanoTime(), activeNanos = 300000000L, totalNanos = 2000000000L))

      expiringService.revokeOAuth029(runId, UUID.randomUUID(), context).unsafeRunSync() shouldEqual OAuthE2EFixtureRevokeResult.TimedOut
      assertRevokeRolledBack(ownership)
    }

    "roll back ledger and partially inserted business rows when the deadline expires between real SQL boundaries" in isolatedFixture {
      val runId          = UUID.randomUUID()
      val idempotencyKey = UUID.randomUUID()
      val clock          = new AtomicLong(0L)
      initializeController()
      val before = businessRowCounts
      val expiringExecutor = new OAuthE2EFixtureCommandExecutor(
        transactor,
        new PostgresOAuthE2EFixtureBackendCanceller(db),
        OAuthE2EFixtureControllerSupervisor.noop,
        nanoTime = () => clock.get(),
        statementCompleted = count => if (count == 8) clock.set(8000000000L)
      )
      val expiringService = serviceWith(expiringExecutor, () => clock.get())
      val context         = ControlRequestContext(ControlDeadline.command(0L))

      expiringService.setup(runId, idempotencyKey, context).unsafeRunSync() shouldEqual OAuthE2EFixtureSetupResult.TimedOut
      val ledger = awaitDb(fixtureRepository.ledger).getOrElse(throw new AssertionError("fixture ledger missing"))
      ledger.runId shouldEqual None
      ledger.runState shouldEqual FixtureRunState.AwaitingSetup
      ledger.acceptedCommandCount shouldEqual 0
      awaitDb(fixtureRepository.ownershipRows) should beEmpty
      awaitDb(fixtureRepository.auditRows) should beEmpty
      businessRowCounts shouldEqual before
    }

    "roll back after every setup SQL boundary and permit only the same failed attempt to retry" in isolatedFixture {
      val before = businessRowCounts

      (0 to ExpectedSetupStatementCount).foreach { faultBoundary =>
        dropFixtureTables()
        initializeController()
        val runId          = UUID.randomUUID()
        val idempotencyKey = UUID.randomUUID()
        val executor = new OAuthE2EFixtureCommandExecutor(
          transactor,
          new PostgresOAuthE2EFixtureBackendCanceller(db),
          OAuthE2EFixtureControllerSupervisor.noop,
          beforePersistenceOpen = _ => if (faultBoundary == 0) IO.raiseError(SyntheticSetupFault) else IO.unit,
          statementCompleted = count => if (count == faultBoundary) throw SyntheticSetupFault
        )
        val failed = serviceWith(executor, preparedCreate = Some(() => fastPreparedCreate()))

        thrownBy(failed.setup(runId, idempotencyKey, requestContext()).unsafeRunSync()) shouldEqual SyntheticSetupFault
        val ledger = awaitDb(fixtureRepository.ledger).getOrElse(throw new AssertionError("fixture ledger missing"))
        ledger.runId shouldEqual None
        ledger.runState shouldEqual FixtureRunState.AwaitingSetup
        ledger.acceptedCommandCount shouldEqual 0
        awaitDb(fixtureRepository.ownershipRows) should beEmpty
        awaitDb(fixtureRepository.auditRows) should beEmpty
      }
      businessRowCounts shouldEqual before

      dropFixtureTables()
      initializeController()
      val failedOnce = new AtomicBoolean(false)
      val retryExecutor = new OAuthE2EFixtureCommandExecutor(
        transactor,
        new PostgresOAuthE2EFixtureBackendCanceller(db),
        OAuthE2EFixtureControllerSupervisor.noop,
        statementCompleted = count => if (count == 8 && failedOnce.compareAndSet(false, true)) throw SyntheticSetupFault
      )
      val retryService   = serviceWith(retryExecutor, preparedCreate = Some(() => fastPreparedCreate()))
      val originalRun    = UUID.randomUUID()
      val originalKey    = UUID.randomUUID()
      val conflictingRun = UUID.randomUUID()

      thrownBy(retryService.setup(originalRun, originalKey, requestContext()).unsafeRunSync()) shouldEqual SyntheticSetupFault
      retryService.setup(conflictingRun, UUID.randomUUID(), requestContext()).unsafeRunSync() shouldEqual OAuthE2EFixtureSetupResult.Conflict
      retryService.setup(originalRun, originalKey, requestContext()).unsafeRunSync() must beLike { case OAuthE2EFixtureSetupResult.Applied(_) =>
        ok
      }
    }

    "make a committed setup response unrecoverable after delivery loss" in isolatedFixture {
      val preparedClients = ArrayBuffer.empty[OAuthClientPreparedCreate]
      val lostResponse    = new AtomicBoolean(false)
      val executor =
        new OAuthE2EFixtureCommandExecutor(transactor, new PostgresOAuthE2EFixtureBackendCanceller(db), OAuthE2EFixtureControllerSupervisor.noop)
      val lossyService = serviceWith(
        executor,
        afterCommit = IO.raiseWhen(lostResponse.compareAndSet(false, true))(SyntheticResponseLoss),
        preparedCreate = Some { () =>
          val prepared = securePreparedCreate()
          preparedClients += prepared
          prepared
        }
      )
      val runId = UUID.randomUUID()
      val key   = UUID.randomUUID()
      initializeController()

      thrownBy(lossyService.setup(runId, key, requestContext()).unsafeRunSync()) shouldEqual SyntheticResponseLoss
      val ledger = awaitDb(fixtureRepository.ledger).getOrElse(throw new AssertionError("fixture ledger missing"))
      ledger.runState shouldEqual FixtureRunState.SetupComplete
      lossyService.setup(runId, key, requestContext()).unsafeRunSync() shouldEqual OAuthE2EFixtureSetupResult.CredentialsUnavailable
      lossyService.setup(runId, UUID.randomUUID(), requestContext()).unsafeRunSync() shouldEqual
        OAuthE2EFixtureSetupResult.CredentialsUnavailable
      lossyService.setup(UUID.randomUUID(), UUID.randomUUID(), requestContext()).unsafeRunSync() shouldEqual
        OAuthE2EFixtureSetupResult.CredentialsUnavailable
      ledger.toString must not(contain("epoca_oauth_secret_v1_"))
      awaitDb(fixtureRepository.auditRows).mkString must not(contain("epoca_oauth_secret_v1_"))
      val persistedSurface = Seq(
        ledger.toString,
        awaitDb(fixtureRepository.auditRows).mkString,
        awaitDb(fixtureRepository.ownershipRows).mkString,
        preparedClients.map(_.toString).mkString
      ).mkString
      val secretSafetyChecks = preparedClients.toVector.map { prepared =>
        val snapshot = awaitDb(oauthClientRepository.lock(prepared.clientId))
        (
          !persistedSurface.contains(prepared.credentials.clientSecret),
          snapshot.isDefined,
          snapshot.exists(_.client.secretHash != prepared.credentials.clientSecret),
          snapshot.exists(value => secretService.verify(prepared.credentials.clientSecret, value.client.secretHash))
        )
      }
      secretSafetyChecks shouldEqual Vector.fill(FixtureAlias.values.size)((true, true, true, true))
    }

    "record one sanitized result for every alias before the exact evidence handoff" in {
      val credentials   = oneTimeCredentials()
      val brokerStarted = Deferred[IO, Unit].unsafeRunSync()
      val brokerExit    = Deferred[IO, Unit].unsafeRunSync()
      val oauth027Fiber = credentials
        .projectToBroker(FixtureAlias.OAuth027)(_ => brokerStarted.complete(()).void *> brokerExit.get.as(OAuthE2EFixtureSanitizedCaseOutcome.Passed))
        .start
        .unsafeRunSync()

      brokerStarted.get.unsafeRunSync()
      thrownBy(
        credentials
          .projectToBroker(FixtureAlias.OAuth027)(_ => IO.pure(OAuthE2EFixtureSanitizedCaseOutcome.Passed))
          .unsafeRunSync()
      ).getMessage shouldEqual "Fixture credential alias is unavailable"
      thrownBy(credentials.sealForEvidence().unsafeRunSync()).getMessage shouldEqual
        "Fixture credential map cannot be sealed for evidence"
      thrownBy(credentials.discardRemaining().unsafeRunSync()).getMessage shouldEqual
        "Fixture credential map cannot be discarded"
      brokerExit.complete(()).unsafeRunSync() should beTrue
      val oauth027 = oauth027Fiber.joinWithNever.unsafeRunSync()

      oauth027.alias shouldEqual FixtureAlias.OAuth027
      oauth027.outcome shouldEqual OAuthE2EFixtureSanitizedCaseOutcome.Passed
      thrownBy(credentials.sealForEvidence().unsafeRunSync()).getMessage shouldEqual
        "Fixture credential map cannot be sealed for evidence"
      thrownBy(
        credentials
          .projectToBroker(FixtureAlias.OAuth027)(_ => IO.pure(OAuthE2EFixtureSanitizedCaseOutcome.Passed))
          .unsafeRunSync()
      ).getMessage shouldEqual "Fixture credential alias is unavailable"

      val oauth028 = credentials
        .projectToBroker(FixtureAlias.OAuth028)(credential => IO.raiseError(new RuntimeException(credential.clientSecret.toString)))
        .unsafeRunSync()
      oauth028 shouldEqual OAuthE2EFixtureSanitizedCaseResult(FixtureAlias.OAuth028, OAuthE2EFixtureSanitizedCaseOutcome.FailedSafe)
      thrownBy(
        credentials
          .projectToBroker(FixtureAlias.OAuth028)(_ => IO.pure(OAuthE2EFixtureSanitizedCaseOutcome.Passed))
          .unsafeRunSync()
      ).getMessage shouldEqual "Fixture credential alias is unavailable"

      val oauth029Fiber = credentials
        .projectToBroker(FixtureAlias.OAuth029)(_ => IO.canceled *> IO.pure(OAuthE2EFixtureSanitizedCaseOutcome.Passed))
        .start
        .unsafeRunSync()
      oauth029Fiber.join.unsafeRunSync() must beLike { case Outcome.Canceled() => ok }
      val general = credentials
        .projectToBroker(FixtureAlias.General)(_ => IO.pure(OAuthE2EFixtureSanitizedCaseOutcome.Passed))
        .unsafeRunSync()

      val evidenceInput = credentials.sealForEvidence().unsafeRunSync()
      evidenceInput.caseResults shouldEqual Vector(
        oauth027,
        oauth028,
        OAuthE2EFixtureSanitizedCaseResult(FixtureAlias.OAuth029, OAuthE2EFixtureSanitizedCaseOutcome.FailedSafe),
        general
      )
      evidenceInput.toString must not(contain("epoca_oauth_secret_v1_"))
      credentials.sealedForEvidence must beTrue
      credentials.retainedCredentialCount shouldEqual 0
      FixtureAlias.values.foreach { alias =>
        thrownBy(
          credentials
            .projectToBroker(alias)(_ => IO.pure(OAuthE2EFixtureSanitizedCaseOutcome.Passed))
            .unsafeRunSync()
        ).getMessage shouldEqual "Fixture credential alias is unavailable"
      }
      success
    }

    "reject empty, partial, missing, duplicate, and extra sanitized evidence result sets" in {
      val exact   = FixtureAlias.values.map(alias => OAuthE2EFixtureSanitizedCaseResult(alias, OAuthE2EFixtureSanitizedCaseOutcome.Passed))
      val invalid = Vector(Vector.empty, exact.dropRight(1), exact.updated(1, exact.head), exact :+ exact.head)

      invalid.foreach { results =>
        thrownBy(OAuthE2EFixtureSanitizedEvidenceInput.fromExactCaseResults(results)).getMessage shouldEqual
          "Fixture evidence requires the exact closed alias results"
      }
      OAuthE2EFixtureSanitizedEvidenceInput.fromExactCaseResults(exact).caseResults shouldEqual exact
    }

    "redact a synchronous secret-bearing broker throw and still complete the exact evidence set" in {
      val credentials = oneTimeCredentials()
      val oauth027 = credentials
        .projectToBroker(FixtureAlias.OAuth027)(credential => throw new RuntimeException(credential.clientSecret.toString))
        .unsafeRunSync()

      oauth027 shouldEqual OAuthE2EFixtureSanitizedCaseResult(FixtureAlias.OAuth027, OAuthE2EFixtureSanitizedCaseOutcome.FailedSafe)
      oauth027.toString must not(contain("epoca_oauth_secret_v1_"))
      credentials.retainedCredentialCount shouldEqual 3
      thrownBy(
        credentials
          .projectToBroker(FixtureAlias.OAuth027)(_ => IO.pure(OAuthE2EFixtureSanitizedCaseOutcome.Passed))
          .unsafeRunSync()
      ).getMessage shouldEqual "Fixture credential alias is unavailable"

      FixtureAlias.values.tail.foreach { alias =>
        credentials.projectToBroker(alias)(_ => IO.pure(OAuthE2EFixtureSanitizedCaseOutcome.Passed)).unsafeRunSync()
      }
      val evidence = credentials.sealForEvidence().unsafeRunSync()
      evidence.caseResults.map(_.alias) shouldEqual FixtureAlias.values
      evidence.caseResults.head shouldEqual oauth027
      evidence.toString must not(contain("epoca_oauth_secret_v1_"))
      credentials.retainedCredentialCount shouldEqual 0
    }

    "serialize concurrent setup attempts and expose credentials to exactly one same-key caller" in isolatedFixture {
      val firstPersistenceOpen = Deferred[IO, Unit].unsafeRunSync()
      val releaseFirst         = Deferred[IO, Unit].unsafeRunSync()
      val persistenceOpens     = new AtomicInteger(0)
      val executor = new OAuthE2EFixtureCommandExecutor(
        transactor,
        new PostgresOAuthE2EFixtureBackendCanceller(db),
        OAuthE2EFixtureControllerSupervisor.noop,
        beforePersistenceOpen = _ =>
          if (persistenceOpens.incrementAndGet() == 1) firstPersistenceOpen.complete(()).void *> releaseFirst.get
          else IO.unit
      )
      val concurrentService = serviceWith(executor, preparedCreate = Some(() => fastPreparedCreate()))
      val runId             = UUID.randomUUID()
      val key               = UUID.randomUUID()
      initializeController()

      val first = concurrentService.setup(runId, key, requestContext()).start.unsafeRunSync()
      firstPersistenceOpen.get.unsafeRunSync()
      concurrentService.setup(UUID.randomUUID(), UUID.randomUUID(), requestContext()).unsafeRunSync() shouldEqual
        OAuthE2EFixtureSetupResult.Conflict
      val racingResult = concurrentService.setup(runId, key, requestContext()).unsafeRunSync()
      releaseFirst.complete(()).unsafeRunSync() should beTrue
      val firstResult = first.joinWithNever.unsafeRunSync()

      Vector(firstResult, racingResult).count(_.isInstanceOf[OAuthE2EFixtureSetupResult.Applied]) shouldEqual 1
      Vector(firstResult, racingResult).count(_ == OAuthE2EFixtureSetupResult.CredentialsUnavailable) shouldEqual 1
      val abandonedCredentials = Vector(firstResult, racingResult).collectFirst { case OAuthE2EFixtureSetupResult.Applied(credentials) =>
        credentials
      }.get
      abandonedCredentials.discardRemaining().unsafeRunSync()
      abandonedCredentials.retainedCredentialCount shouldEqual 0
      abandonedCredentials.sealedForEvidence must beFalse
      thrownBy(abandonedCredentials.sealForEvidence().unsafeRunSync()).getMessage shouldEqual
        "Fixture credential map cannot be sealed for evidence"
      concurrentService.setup(runId, key, requestContext()).unsafeRunSync() shouldEqual OAuthE2EFixtureSetupResult.CredentialsUnavailable
      concurrentService.setup(runId, UUID.randomUUID(), requestContext()).unsafeRunSync() shouldEqual
        OAuthE2EFixtureSetupResult.CredentialsUnavailable
      concurrentService.setup(UUID.randomUUID(), UUID.randomUUID(), requestContext()).unsafeRunSync() shouldEqual
        OAuthE2EFixtureSetupResult.CredentialsUnavailable
    }

    "expose only the supervisor run ID and idempotency context as setup input" in {
      val setupParameterTypes = classOf[OAuthE2EFixtureService].getDeclaredMethods
        .filter(_.getName == "setup")
        .map(_.getParameterTypes.toVector)
        .toVector

      setupParameterTypes shouldEqual Vector(Vector(classOf[UUID], classOf[UUID], classOf[ControlRequestContext]))
      classOf[OAuthE2EFixtureService].getDeclaredMethods
        .filter(_.getName == "revokeOAuth029")
        .map(_.getParameterTypes.toVector)
        .toVector shouldEqual Vector(Vector(classOf[UUID], classOf[UUID], classOf[ControlRequestContext]))
      thrownBy(FixtureAlias.fromDatabase("attacker-selected-topology")).getMessage shouldEqual
        "Fixture alias failed closed decoding"
    }
  }

  private def service: OAuthE2EFixtureService =
    serviceWith(commandExecutor)

  private def serviceWith(
    executor: OAuthE2EFixtureCommandExecutor,
    clock: () => Long = () => System.nanoTime(),
    afterCommit: IO[Unit] = IO.unit,
    afterRevoke: IO[Unit] = IO.unit,
    preparedCreate: Option[() => OAuthClientPreparedCreate] = None,
    afterOwnedClientRevoke: DBIO[Unit] = DBIO.successful(()),
    beforeCleanupRevokeCommit: FixtureAlias => DBIO[Unit] = _ => DBIO.successful(()),
    afterCleanupRevokeCommit: FixtureAlias => IO[Unit] = _ => IO.unit,
    afterCleanupRevocationMarker: FixtureAlias => IO[Unit] = _ => IO.unit,
    afterCleanupResourceCommit: FixtureAlias => IO[Unit] = _ => IO.unit,
    afterCleanupResourceMarker: FixtureAlias => IO[Unit] = _ => IO.unit
  ): OAuthE2EFixtureService =
    new OAuthE2EFixtureService(
      executor,
      fixtureRepository,
      managementService,
      secretService,
      userRepository,
      organizationRepository,
      organizationUserRepository,
      clientRepository,
      projectRepository,
      workUnitRepository,
      nanoTime = clock,
      afterSetupCommit = afterCommit,
      afterRevokeCommit = afterRevoke,
      preparedCreateOverride = preparedCreate,
      afterOwnedClientRevoke = afterOwnedClientRevoke,
      beforeCleanupRevokeCommit = beforeCleanupRevokeCommit,
      afterCleanupRevokeCommit = afterCleanupRevokeCommit,
      afterCleanupRevocationMarker = afterCleanupRevocationMarker,
      afterCleanupResourceCommit = afterCleanupResourceCommit,
      afterCleanupResourceMarker = afterCleanupResourceMarker
    )

  private def awaitBackendWaitingOnLock(observer: Database, pid: Int): Boolean = {
    val deadline = System.nanoTime() + 5.seconds.toNanos
    var waiting  = false
    while (!waiting && System.nanoTime() < deadline) {
      waiting = Await.result(
        observer.run(sql"""select count(*) from pg_stat_activity
                where pid = $pid and wait_event_type = 'Lock'""".as[Int].head),
        1.second
      ) == 1
      if (!waiting) Thread.`yield`()
    }
    waiting
  }

  private def verifyCleanupRevokeRace(cleanupStartsDuringPublicRevoke: Boolean): Unit = isolatedFixture {
    val publicDb         = independentDatabase(s"cleanup-public-revoke-race-$cleanupStartsDuringPublicRevoke", maximumConnections = 1)
    val cleanupDb        = independentDatabase(s"cleanup-cleanup-revoke-race-$cleanupStartsDuringPublicRevoke", maximumConnections = 1)
    val cancelDb         = independentDatabase(s"cleanup-cancel-revoke-race-$cleanupStartsDuringPublicRevoke", maximumConnections = 2)
    val publicCommitted  = Deferred[IO, Unit].unsafeRunSync()
    val cleanupCommitted = Deferred[IO, Unit].unsafeRunSync()
    val release          = Deferred[IO, Unit].unsafeRunSync()
    val publicExecutor = new OAuthE2EFixtureCommandExecutor(
      new PostgresIOTransactor(publicDb),
      new PostgresOAuthE2EFixtureBackendCanceller(cancelDb),
      OAuthE2EFixtureControllerSupervisor.noop
    )
    val cleanupExecutor = new OAuthE2EFixtureCommandExecutor(
      new PostgresIOTransactor(cleanupDb),
      new PostgresOAuthE2EFixtureBackendCanceller(cancelDb),
      OAuthE2EFixtureControllerSupervisor.noop
    )
    val publicService = serviceWith(
      publicExecutor,
      preparedCreate = Some(() => fastPreparedCreate()),
      afterRevoke = if (cleanupStartsDuringPublicRevoke) publicCommitted.complete(()).void *> release.get else IO.unit
    )
    val cleanupService = serviceWith(
      cleanupExecutor,
      preparedCreate = Some(() => fastPreparedCreate()),
      afterCleanupRevokeCommit = alias =>
        if (!cleanupStartsDuringPublicRevoke && alias == FixtureAlias.OAuth029)
          cleanupCommitted.complete(()).void *> release.get
        else IO.unit
    )
    val runId      = UUID.randomUUID()
    val revokeKey  = UUID.randomUUID()
    val cleanupKey = UUID.randomUUID()

    try {
      initializeController()
      cleanupService.setup(runId, UUID.randomUUID(), requestContext()).unsafeRunSync() must beLike {
        case OAuthE2EFixtureSetupResult.Applied(credentials) =>
          credentials.discardRemaining().unsafeRunSync()
          ok
      }

      val (revokeResult, cleanupResult) =
        if (cleanupStartsDuringPublicRevoke) {
          val revokeFiber = publicService.revokeOAuth029(runId, revokeKey, requestContext()).start.unsafeRunSync()
          publicCommitted.get.unsafeRunSync()
          val stateInWindow = awaitDb(
            sql"""select run_state, revoke_oauth029_started_digest is not null,
                  revoke_oauth029_completed_digest is not null from oauth_e2e_fixture_run where singleton_key = 1"""
              .as[(String, Boolean, Boolean)]
              .head
          )
          stateInWindow shouldEqual (("setup-complete", true, false))
          val cleaned = cleanupService.cleanup(runId, cleanupKey, cleanupRequestContext()).unsafeRunSync()
          release.complete(()).unsafeRunSync() should beTrue
          revokeFiber.joinWithNever.unsafeRunSync() -> cleaned
        } else {
          val cleanupFiber = cleanupService.cleanup(runId, cleanupKey, cleanupRequestContext()).start.unsafeRunSync()
          cleanupCommitted.get.unsafeRunSync()
          val ledgerInWindow = awaitDb(fixtureRepository.ledger).get
          ledgerInWindow.runState shouldEqual FixtureRunState.CleanupRequired
          ledgerInWindow.cleanupSteps(CleanupStep.OAuth029Revocation) shouldEqual CleanupStepState.InProgress
          val revoked = publicService.revokeOAuth029(runId, revokeKey, requestContext()).unsafeRunSync()
          release.complete(()).unsafeRunSync() should beTrue
          revoked -> cleanupFiber.joinWithNever.unsafeRunSync()
        }

      revokeResult shouldEqual (if (cleanupStartsDuringPublicRevoke) RevokeIdempotent else RevokeConflict)
      cleanupResult shouldEqual OAuthE2EFixtureCleanupResult.Cleaned
      val ledger = awaitDb(fixtureRepository.ledger).get
      ledger.runState shouldEqual FixtureRunState.Cleaned
      ledger.cleanupSteps.keySet shouldEqual CleanupStep.values.toSet
      ledger.cleanupSteps.values.toSet shouldEqual Set(CleanupStepState.Complete)
      val markerDigests = awaitDb(sql"""select revoke_oauth029_started_digest, revoke_oauth029_completed_digest
              from oauth_e2e_fixture_run where singleton_key = 1""".as[(Option[String], Option[String])].head)
      if (cleanupStartsDuringPublicRevoke) {
        markerDigests._1 must beSome
        markerDigests._2 shouldEqual markerDigests._1
      } else markerDigests shouldEqual ((None, None))

      val ownership = awaitDb(fixtureRepository.ownershipRows)
      val auditCounts = ownership.map(owned =>
        owned.oauthClientId -> awaitDb(oauthAuditRepository.list(OAuthAuditEventFilters(owned.oauthClientId)))
          .count(_.eventType == OAuthAuditEventType.Revoke)
      )
      auditCounts.map(_._2).toSet shouldEqual Set(1)
      val statusesBeforeReplay = ownership.map(owned => awaitDb(oauthClientRepository.lock(owned.oauthClientId)).get.client.status)
      val ledgerBeforeReplay   = awaitDb(fixtureRepository.ledger)
      val auditsBeforeReplay   = awaitDb(fixtureRepository.auditRows)
      cleanupService.cleanup(runId, cleanupKey, cleanupRequestContext()).unsafeRunSync() shouldEqual OAuthE2EFixtureCleanupResult.AlreadyCleaned
      ownership.map(owned => awaitDb(oauthClientRepository.lock(owned.oauthClientId)).get.client.status) shouldEqual statusesBeforeReplay
      ownership.map(owned =>
        awaitDb(oauthAuditRepository.list(OAuthAuditEventFilters(owned.oauthClientId))).count(_.eventType == OAuthAuditEventType.Revoke)
      ) shouldEqual auditCounts.map(_._2)
      awaitDb(fixtureRepository.ledger) shouldEqual ledgerBeforeReplay
      awaitDb(fixtureRepository.auditRows) shouldEqual auditsBeforeReplay
    } finally {
      release.complete(()).attempt.unsafeRunSync()
      publicDb.close()
      cleanupDb.close()
      cancelDb.close()
    }
    ()
  }

  private def verifyConcurrentCleanup(sameKey: Boolean): Unit = isolatedFixture {
    val firstPid    = new AtomicInteger(0)
    val secondPid   = Promise[Int]()
    val revokeReady = Promise[Unit]()
    val release     = Promise[Unit]()
    val firstDb     = independentDatabase(s"cleanup-cleanup-first-$sameKey", maximumConnections = 1)
    val secondDb    = independentDatabase(s"cleanup-cleanup-second-$sameKey", maximumConnections = 1)
    val cancelDb    = independentDatabase(s"cleanup-cleanup-cancel-$sameKey", maximumConnections = 2)
    val observer    = independentDatabase(s"cleanup-cleanup-observer-$sameKey", maximumConnections = 1)
    val firstExecutor = new OAuthE2EFixtureCommandExecutor(
      new PostgresIOTransactor(firstDb),
      new PostgresOAuthE2EFixtureBackendCanceller(cancelDb),
      OAuthE2EFixtureControllerSupervisor.noop,
      backendPidObserved = firstPid.set
    )
    val secondExecutor = new OAuthE2EFixtureCommandExecutor(
      new PostgresIOTransactor(secondDb),
      new PostgresOAuthE2EFixtureBackendCanceller(cancelDb),
      OAuthE2EFixtureControllerSupervisor.noop,
      backendPidObserved = pid => { secondPid.trySuccess(pid); () }
    )
    val firstService = serviceWith(
      firstExecutor,
      preparedCreate = Some(() => fastPreparedCreate()),
      beforeCleanupRevokeCommit = alias =>
        if (alias == FixtureAlias.OAuth027)
          DBIO.successful { revokeReady.trySuccess(()); () } >> DBIO.from(release.future)
        else DBIO.successful(())
    )
    val secondService = serviceWith(secondExecutor, preparedCreate = Some(() => fastPreparedCreate()))
    val runId         = UUID.randomUUID()
    val firstKey      = UUID.randomUUID()
    val secondKey     = if (sameKey) firstKey else UUID.randomUUID()
    try {
      initializeController()
      firstService.setup(runId, UUID.randomUUID(), requestContext()).unsafeRunSync() must beLike {
        case OAuthE2EFixtureSetupResult.Applied(credentials) =>
          credentials.discardRemaining().unsafeRunSync()
          ok
      }

      val firstFiber = firstService.cleanup(runId, firstKey, cleanupRequestContext()).start.unsafeRunSync()
      Await.result(revokeReady.future, 10.seconds)
      val blockingPid = firstPid.get()
      blockingPid must be_>(0)
      val secondFiber = secondService.cleanup(runId, secondKey, cleanupRequestContext()).start.unsafeRunSync()
      val waitingPid  = Await.result(secondPid.future, 10.seconds)
      awaitBackendBlockedBy(observer, waitingPid, blockingPid) should beTrue
      release.trySuccess(()) should beTrue

      val results = Vector(firstFiber.joinWithNever.unsafeRunSync(), secondFiber.joinWithNever.unsafeRunSync())
      if (sameKey) results.toSet shouldEqual Set(OAuthE2EFixtureCleanupResult.Cleaned, OAuthE2EFixtureCleanupResult.AlreadyCleaned)
      else results.toSet shouldEqual Set(OAuthE2EFixtureCleanupResult.Cleaned, OAuthE2EFixtureCleanupResult.Conflict)
      val ownership = awaitDb(fixtureRepository.ownershipRows)
      ownership.foreach { owned =>
        awaitDb(oauthAuditRepository.list(OAuthAuditEventFilters(owned.oauthClientId))).count(_.eventType == OAuthAuditEventType.Revoke) shouldEqual 1
      }
    } finally {
      release.trySuccess(())
      firstDb.close()
      secondDb.close()
      cancelDb.close()
      observer.close()
    }
    ()
  }

  private def awaitBackendBlockedBy(
    observer: Database,
    waitingPid: Int,
    blockerPid: Int
  ): Boolean = {
    val deadline = System.nanoTime() + 10.seconds.toNanos
    var blocked  = false
    while (!blocked && System.nanoTime() < deadline) {
      blocked = Await
        .result(
          observer.run(sql"""select wait_event_type = 'Lock' and $blockerPid = any(pg_catalog.pg_blocking_pids(pid))
                 from pg_catalog.pg_stat_activity where pid = $waitingPid""".as[Boolean].headOption),
          1.second
        )
        .contains(true)
      if (!blocked) Thread.`yield`()
    }
    blocked
  }

  private def awaitAnyBackendBlockedBy(observer: Database, blockerPid: Int): Boolean = {
    val deadline = System.nanoTime() + 5.seconds.toNanos
    var blocked  = false
    while (!blocked && System.nanoTime() < deadline) {
      blocked = Await.result(
        observer.run(sql"""select exists (
                   select 1 from pg_catalog.pg_stat_activity
                   where wait_event_type = 'Lock' and $blockerPid = any(pg_catalog.pg_blocking_pids(pid))
                 )""".as[Boolean].head),
        1.second
      )
      if (!blocked) Thread.`yield`()
    }
    blocked
  }

  private def assertRevokeRolledBack(ownership: OAuthE2EFixtureDomain.FixtureOwnership) = {
    val snapshot = awaitDb(oauthClientRepository.lock(ownership.oauthClientId)).get
    val account  = awaitDb(serviceAccountRepository.findById(snapshot.client.serviceAccountUserId)).get
    val audits   = awaitDb(oauthAuditRepository.list(OAuthAuditEventFilters(ownership.oauthClientId)))
    val ledger   = awaitDb(fixtureRepository.ledger).get
    val revokeDigests = awaitDb(sql"""select revoke_oauth029_started_digest, revoke_oauth029_completed_digest
            from oauth_e2e_fixture_run where singleton_key = 1""".as[(Option[String], Option[String])].head)

    (snapshot.client.status shouldEqual OAuthClientStatus.Active) and
      (account.archivedAt should beNone) and
      (audits.map(_.eventType) shouldEqual Seq(OAuthAuditEventType.Create)) and
      (ledger.acceptedCommandCount shouldEqual 1) and
      (revokeDigests shouldEqual ((None, None))) and
      (awaitDb(fixtureRepository.auditRows).map(_.commandCategory) shouldEqual Seq(Setup))
  }

  private def independentDatabase(name: String, maximumConnections: Int): Database =
    Database.forURL(
      url = sys.env.getOrElse("TEST_DB_URL", "epoca"),
      user = sys.env.getOrElse("TEST_DB_USER", "postgres"),
      password = sys.env.getOrElse("TEST_DB_PASSWORD", "postgres"),
      driver = "org.postgresql.Driver",
      executor = AsyncExecutor(
        s"$name-${UUID.randomUUID()}",
        minThreads = maximumConnections,
        maxThreads = maximumConnections,
        queueSize = 20,
        maxConnections = maximumConnections
      ),
      prop = new Properties()
    )

  private def workUnitForCleanup(
    id: WorkUnitId,
    ownership: FixtureOwnership,
    projectId: ProjectId,
    state: WorkUnitState,
    archivedAt: Option[Instant]
  ): WorkUnit = {
    val createdAt = Instant.parse("2026-08-21T08:00:00Z")
    WorkUnit(
      id = id,
      userId = ownership.ownerUserId,
      organizationId = ownership.organizationId,
      projectId = projectId,
      date = WorkUnitDate(LocalDate.of(2026, 8, 21)),
      duration = WorkUnitDuration(Duration.ofHours(1)),
      entryType = WorkUnitEntryType.Worklog,
      description = WorkUnitDescription("oauth-e2e cleanup marker control"),
      taskId = None,
      state = state,
      createdAt = WorkUnitCreatedAt(createdAt),
      modifiedAt = WorkUnitModifiedAt(createdAt),
      archivedAt = archivedAt.map(WorkUnitArchivedAt.apply)
    )
  }

  private def fastPreparedCreate(): OAuthClientPreparedCreate =
    OAuthClientPreparedCreate(
      credentials = secretService.generateCredentials(),
      secretHash = dummySecretHash,
      clientId = OAuthClientId(UUID.randomUUID()),
      serviceAccountUserId = pl.iterators.epoca.user.domain.UserDomain.UserId(UUID.randomUUID()),
      membershipId = pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationUserId.random,
      auditEventId = OAuthAuditEventId(UUID.randomUUID())
    )

  private def securePreparedCreate(): OAuthClientPreparedCreate = {
    val credentials = secretService.generateCredentials()
    fastPreparedCreate().copy(credentials = credentials, secretHash = secretService.hash(credentials.clientSecret))
  }

  private def oneTimeCredentials(): OAuthE2EFixtureOneTimeCredentials =
    new OAuthE2EFixtureOneTimeCredentials(FixtureAlias.values.map { alias =>
      val generated = secretService.generateCredentials()
      alias -> OAuthE2EFixtureCredential(generated.clientId, generated.clientSecret)
    }.toMap)

  private def revokeForCleanup(ownership: FixtureOwnership): Unit = {
    val organization = awaitDb(organizationRepository.find(OrganizationFilters(id = Some(ownership.organizationId), includeArchived = true))).get
    val result = awaitDb(
      managementService
        .revokeOwnedPreparedDBIO(
          ownerAuth(ownership),
          organization.slug,
          ownership.oauthClientId,
          cleanupCorrelationId(ownership),
          DBIO.successful(())
        )
        .transactionally
    )
    result must beLike { case OAuthClientManagementResult.Success(value) if value.status == OAuthClientEffectiveStatus.Revoked => ok }
    ()
  }

  private def ownerAuth(ownership: FixtureOwnership): AuthContext =
    AuthContext(
      id = ownership.ownerUserId,
      isSuperAdmin = pl.iterators.epoca.user.domain.UserDomain.UserIsSuperAdmin(false),
      identities = Nil,
      authedBy = Set.empty,
      roles = Seq(AuthContext.OrganizationRoles(ownership.organizationId, Seq(OrganizationUserRole.Owner))),
      lastAuthedAt = Instant.EPOCH,
      fresh = true
    )

  private def cleanupCorrelationId(ownership: FixtureOwnership): OAuthAuditCorrelationId =
    ownership.alias match {
      case FixtureAlias.OAuth029 => OAuthAuditCorrelationId(s"oauth-e2e-fixture-revoke-oauth029-${ownership.runId}")
      case alias                 => OAuthAuditCorrelationId(s"oauth-e2e-fixture-cleanup-${alias.databaseValue}-${ownership.runId}")
    }

  private def faultDbioOnce(enabled: Boolean, failed: AtomicBoolean): DBIO[Unit] =
    if (enabled && failed.compareAndSet(false, true)) DBIO.failed(SyntheticCleanupFault) else DBIO.successful(())

  private def faultIoOnce(enabled: Boolean, failed: AtomicBoolean): IO[Unit] =
    IO.raiseWhen(enabled && failed.compareAndSet(false, true))(SyntheticCleanupFault)

  private def revocationStep(alias: FixtureAlias): CleanupStep = alias match {
    case FixtureAlias.OAuth027 => CleanupStep.OAuth027Revocation
    case FixtureAlias.OAuth028 => CleanupStep.OAuth028Revocation
    case FixtureAlias.OAuth029 => CleanupStep.OAuth029Revocation
    case FixtureAlias.General  => CleanupStep.GeneralRevocation
  }

  private def thrownBy[A](value: => A): Throwable =
    try {
      val _ = value
      throw new AssertionError("Expected setup failure")
    } catch {
      case error: Throwable => error
    }

  private def ownershipFor(rows: Seq[OAuthE2EFixtureDomain.FixtureOwnership], alias: FixtureAlias): OAuthE2EFixtureDomain.FixtureOwnership =
    rows.find(_.alias == alias).get

  private def requestContext(): ControlRequestContext = ControlRequestContext(ControlDeadline.command(System.nanoTime()))

  private def cleanupRequestContext(): ControlRequestContext = ControlRequestContext(ControlDeadline.cleanup(System.nanoTime()))

  private def awaitDb[A](action: DBIO[A]): A = Await.result(db.run(action), 20.seconds)

  private def businessRowCounts: Seq[Long] =
    awaitDb(
      DBIO.sequence(
        Seq(
          sql"""select count(*) from "user"""".as[Long].head,
          sql"select count(*) from organization_user".as[Long].head,
          sql"select count(*) from organization".as[Long].head,
          sql"select count(*) from client".as[Long].head,
          sql"select count(*) from project".as[Long].head,
          sql"select count(*) from oauth_client".as[Long].head,
          sql"select count(*) from oauth_client_scope".as[Long].head,
          sql"select count(*) from oauth_client_project".as[Long].head,
          sql"select count(*) from oauth_audit_event".as[Long].head
        )
      )
    )

  private def initializeController(): Unit = {
    val result = awaitDb(fixtureRepository.initialize(controllerConfig, Some(ControllerInstanceId(UUID.randomUUID()))))
    result shouldEqual ControllerClaimed
    ()
  }

  private def isolatedFixture[T](body: => T): T = {
    dropFixtureTables()
    try body
    finally dropFixtureTables()
  }

  private def dropFixtureTables(): Unit =
    awaitDb(
      DBIO
        .seq(
          sqlu"drop table if exists oauth_e2e_fixture_ownership",
          sqlu"drop table if exists oauth_e2e_fixture_audit",
          sqlu"drop table if exists oauth_e2e_fixture_run"
        )
        .transactionally
    )

  private val applicationConfig = ConfigFactory
    .parseResources("oauth-e2e.conf")
    .withFallback(ConfigFactory.parseResources("application.conf"))
    .resolve(ConfigResolveOptions.defaults().setAllowUnresolved(true))

  private lazy val controllerConfig: OAuthE2EFixtureConfig = requireConfig(
    ConfigFactory
      .parseString(s"""
        app.environment = "oauth-e2e"
        db.url = "jdbc:postgresql://127.0.0.1:55422/oauth_e2e_repository"
        db.password = "oauth-e2e-task5-database-secret"
        login.jwtSecret = "oauth-e2e-task5-login-secret"
        oauth.enabled = true
        oauth.signing.activeKid = "oauth-a"
        oauth.signing.keys = [
          { kid = "oauth-a", material = "${encodedBytes(1)}", retired = false },
          { kid = "oauth-b", material = "${encodedBytes(33)}", retired = false }
        ]
        oauth.e2eFixture.timeEnabled = true
        oauth.e2eFixture.controlListenerEnabled = true
        oauth.e2eFixture.disposableAck = "oauth-e2e-disposable-v1"
        oauth.e2eFixture.deploymentTier = "disposable"
        oauth.e2eFixture.listenerInterface = "127.0.0.1"
        oauth.e2eFixture.listenerPort = 19000
        oauth.e2eFixture.controlToken = "oauth-e2e-v1.${encodedBytes(65)}"
      """)
      .withFallback(applicationConfig)
      .resolve(ConfigResolveOptions.defaults().setAllowUnresolved(true))
  )

  private def requireConfig(config: Config): OAuthE2EFixtureConfig =
    OAuthE2EFixtureConfig.load(config) match {
      case Right(value) => value
      case Left(error)  => throw new AssertionError(s"Expected valid fixture test configuration, got $error")
    }

  private def encodedBytes(start: Int): String =
    Base64.getUrlEncoder.withoutPadding().encodeToString(Array.tabulate[Byte](32)(index => (start + index).toByte))

  private case object SyntheticSetupFault       extends RuntimeException("synthetic setup fault") with NoStackTrace
  private case object SyntheticResponseLoss     extends RuntimeException("synthetic setup response loss") with NoStackTrace
  private case object SyntheticRevokeMarkerLoss extends RuntimeException("synthetic revoke marker loss") with NoStackTrace
  private case object SyntheticCleanupFault     extends RuntimeException("synthetic cleanup fault") with NoStackTrace

  private sealed trait CleanupFaultPhase
  private object CleanupFaultPhase {
    case object PreRevokeCommit      extends CleanupFaultPhase
    case object PostRevokeCommit     extends CleanupFaultPhase
    case object PostRevocationMarker extends CleanupFaultPhase
    case object PostResourceCommit   extends CleanupFaultPhase
    case object PostResourceMarker   extends CleanupFaultPhase
  }
}
