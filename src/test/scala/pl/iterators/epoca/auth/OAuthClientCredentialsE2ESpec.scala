package pl.iterators.epoca.auth

import cats.effect.unsafe.IORuntime
import io.circe.Json
import io.circe.syntax._
import org.apache.pekko.http.scaladsl.model.HttpMethods
import org.mindrot.jbcrypt.BCrypt
import org.specs2.matcher.MatchResult
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.client.domain.Client
import pl.iterators.epoca.client.repositories.fixtures.ClientFixture
import pl.iterators.epoca.client.repositories.{ClientRepository, ClientRepositoryImpl}
import pl.iterators.epoca.export.domain.PivotExportArtifactStore
import pl.iterators.epoca.export.domain.SpreadsheetDomain.{SpreadsheetColumnName, SpreadsheetFormat}
import pl.iterators.epoca.export.services.ExportWorkUnitService.{ExportWorkUnitColumnNames, ExportWorkUnitRequest, ExportWorkUnitResult}
import pl.iterators.epoca.export.services.{ExportWorkUnitPivotTableService, WorkUnitPivotExportRequestValidator}
import pl.iterators.epoca.oauth.adapters.OAuthReadResult.{NotFound, Ok}
import pl.iterators.epoca.oauth.adapters._
import pl.iterators.epoca.oauth.domain.OAuthAuthContext
import pl.iterators.epoca.oauth.domain.OAuthDomain._
import pl.iterators.epoca.oauth.policy.OAuthOperationRegistry.OAuthOperationClassification.{HumanOnly, OAuthEnabled, Unclassified}
import pl.iterators.epoca.oauth.policy.{OAuthHttpOperation, OAuthOperationRegistry}
import pl.iterators.epoca.oauth.repositories.OAuthAuditRepository.{NewOAuthAuditEvent, OAuthAuditEventFilters}
import pl.iterators.epoca.oauth.repositories.OAuthClientRepository.OAuthClientMutationResult
import pl.iterators.epoca.oauth.repositories._
import pl.iterators.epoca.oauth.repositories.fixtures.ServiceAccountFixture
import pl.iterators.epoca.oauth.repositories.tables.OAuthAuditEventRow
import pl.iterators.epoca.oauth.routers.OAuthAuthenticator
import pl.iterators.epoca.oauth.services.OAuthClientManagementService.OAuthClientManagementResult._
import pl.iterators.epoca.oauth.services.OAuthClientManagementService._
import pl.iterators.epoca.oauth.services.OAuthConfig.{OAuthRateLimit, OAuthSigningKey, OAuthSigningKeyRing}
import pl.iterators.epoca.oauth.services.OAuthTokenService.OAuthTokenResult._
import pl.iterators.epoca.oauth.services._
import pl.iterators.epoca.oauth.time.{OAuthAuthoritativeTime, PostgresOAuthAuthoritativeTime}
import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationSlug, OrganizationUserId, OrganizationUserRole}
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.organization.repositories.fixtures.{OrganizationFixture, OrganizationUserFixture}
import pl.iterators.epoca.organization.repositories.{OrganizationRepositoryImpl, OrganizationUserRepositoryImpl}
import pl.iterators.epoca.project.domain.Project
import pl.iterators.epoca.project.domain.ProjectDomain.ProjectName
import pl.iterators.epoca.project.repositories.fixtures.{ProjectFixture, ProjectUserFixture}
import pl.iterators.epoca.project.repositories.{ProjectRepository, ProjectRepositoryImpl, ProjectUserRepositoryImpl}
import pl.iterators.epoca.stats.domain.StatsDomain.{WorkUnitPivotTableKey, WorkUnitPivotTableValue}
import pl.iterators.epoca.stats.repositories.WorkUnitPivotQueryRepositoryImpl
import pl.iterators.epoca.stats.services.WorkUnitPivotTableService.{WorkUnitPivotTableRequest, WorkUnitPivotTableResult}
import pl.iterators.epoca.stats.services._
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.domain.UserDomain.{UserAccountType, UserIsSuperAdmin}
import pl.iterators.epoca.user.repositories.fixtures.UserFixture
import pl.iterators.epoca.user.repositories.{ServiceAccountRepositoryImpl, UserIdentityRepositoryImpl, UserRepositoryImpl}
import pl.iterators.epoca.utils.db.BaseRepository.driver.api._
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.json.JsonProtocol._
import pl.iterators.epoca.utils.{AuthContextProvider, PgRepositorySpec}
import pl.iterators.epoca.workunit.domain.WorkUnit
import pl.iterators.epoca.workunit.domain.WorkUnitDomain._
import pl.iterators.epoca.workunit.repositories.fixtures.WorkUnitFixture
import pl.iterators.epoca.workunit.repositories.tables.WorkUnitTable
import pl.iterators.epoca.workunit.repositories.{WorkUnitRepository, WorkUnitRepositoryImpl}
import pl.iterators.epoca.workunit.services.WorkUnitCreateService.{WorkUnitCreateRequest, WorkUnitCreateResult}
import pl.iterators.epoca.workunit.services._
import slick.util.AsyncExecutor

import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths, StandardOpenOption}
import java.time.temporal.ChronoUnit
import java.time.{Clock, Instant, LocalDate, ZoneId}
import java.util.{Properties, UUID}
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.{Duration, DurationInt}
import scala.concurrent.{Await, Promise, TimeoutException}
import scala.sys.process._
import scala.util.Try

class OAuthClientCredentialsE2ESpec
    extends PgRepositorySpec
    with UserFixture
    with OrganizationFixture
    with OrganizationUserFixture
    with ClientFixture
    with ProjectFixture
    with ProjectUserFixture
    with WorkUnitFixture
    with ServiceAccountFixture {
  sequential

  private implicit val runtime: IORuntime = IORuntime.global

  private val userRepository             = new UserRepositoryImpl()
  private val serviceAccountRepository   = new ServiceAccountRepositoryImpl()
  private val organizationRepository     = new OrganizationRepositoryImpl()
  private val organizationUserRepository = new OrganizationUserRepositoryImpl()
  private val clientRepository           = new ClientRepositoryImpl()
  private val projectRepository          = new ProjectRepositoryImpl()
  private val projectUserRepository      = new ProjectUserRepositoryImpl()
  private val userIdentityRepository     = new UserIdentityRepositoryImpl()
  private val workUnitRepository         = new WorkUnitRepositoryImpl()
  private val authoritativeTime          = new PostgresOAuthAuthoritativeTime()
  private val oauthClientRepository      = new OAuthClientRepositoryImpl(authoritativeTime)
  private val oauthAuditRepository       = new OAuthAuditRepositoryImpl()
  private val transactor                 = new PostgresIOTransactor(db)
  private val secretService              = new OAuthSecretService(OAuthClientSecretHash(BCrypt.hashpw("ct520-e2e-dummy", BCrypt.gensalt(12))))
  private val grantabilityService =
    new OAuthGrantabilityService(userRepository, organizationRepository, organizationUserRepository, projectRepository)
  private val managementService = managementServiceFor(transactor, oauthClientRepository, oauthAuditRepository)
  private val signingKeyA       = "ct520-e2e-signing-key-sentinel-a-32-bytes-minimum"
  private val signingKeyB       = "ct520-e2e-signing-key-sentinel-b-32-bytes-minimum"
  private val issuer            = "https://staging.epoca.example.test/oauth"
  private val audience          = "epoca-api"
  private val primaryRing       = OAuthSigningKeyRing("ct520-e2e-a", Vector(OAuthSigningKey("ct520-e2e-a", signingKeyA, retired = false)))
  private val jwtService        = new OAuthJwtService(issuer, audience, primaryRing)
  private val tokenService = tokenServiceFor(
    transactor,
    oauthClientRepository,
    oauthAuditRepository,
    new OAuthRateLimitRepositoryImpl(OAuthRateLimit(60, 60.seconds), OAuthRateLimit(10, 300.seconds)),
    jwtService
  )
  private val authenticator =
    new OAuthAuthenticator(transactor, authoritativeTime, jwtService, oauthClientRepository, serviceAccountRepository)
  private val startedAt    = Instant.now()
  private val passedIds    = ArrayBuffer.empty[String]
  private val evidencePath = Paths.get(sys.env.getOrElse("OAUTH_E2E_EVIDENCE_PATH", "target/oauth-e2e-evidence.json"))
  private lazy val pivotExportArtifactDirectory = {
    val directory = Files.createTempDirectory("ct520-oauth-pivot-artifacts-")
    directory.toFile.deleteOnExit()
    directory
  }

  private val legacyScenarioIds: Vector[String] =
    (1 to 22).map(value => f"OAUTH-E2E-$value%03d").toVector ++ (30 to 33).map(value => f"OAUTH-E2E-$value%03d")
  private val ct537LifecycleScenarioIds: Vector[String] =
    (1 to 8).map(value => f"CT-537-E2E-$value%03d").toVector
  private val combinedScenarioIds = legacyScenarioIds ++ ct537LifecycleScenarioIds

  step(Files.deleteIfExists(evidencePath))

  "OAuth client credentials executable matrix" should {
    "OAUTH-E2E-001 issue default scopes and read AllProjects reports" in record("OAUTH-E2E-001") {
      val fixture = boundary(withWorkUnits = true)
      val created = createOAuth(fixture, Set(OAuthScope.ProjectsRead, OAuthScope.ReportsRead), OAuthProjectAccess.AllProjects)
      val token   = issue(created)
      val claims  = verifiedClaims(token)
      val context = authenticate(token)
      val projects = projectAdapter
        .list(OAuthAccessPolicy.from(context), fixture.organization.slug, ProjectRepository.ProjectFilters())
        .unsafeRunSync()
        .asInstanceOf[Ok[pl.iterators.epoca.utils.db.PaginatedResult[pl.iterators.epoca.project.services.ProjectDtoService.ProjectListDto]]]
        .value
      val report = reportAdapter
        .legacyPivot(
          OAuthAccessPolicy.from(context),
          fixture.organization.slug,
          WorkUnitPivotTableRequest(Seq(WorkUnitPivotTableKey.ProjectKey), Seq(WorkUnitPivotTableValue.EntriesCount)),
          WorkUnitRepository.WorkUnitFilters()
        )
        .unsafeRunSync()
        .asInstanceOf[WorkUnitPivotTableResult.Ok]

      (claims.scopes shouldEqual Set(OAuthScope.ProjectsRead, OAuthScope.ReportsRead)) and
        (claims.expiresAt.getEpochSecond - claims.issuedAt.getEpochSecond shouldEqual 300L) and
        (projects.results.map(_.id).toSet shouldEqual Set(fixture.p1.id, fixture.p2.id)) and
        (report.data.flatMap(_.projectId).toSet shouldEqual Set(fixture.p1.id, fixture.p2.id))
    }

    "OAUTH-E2E-002 narrower token cannot use reports export" in record("OAUTH-E2E-002") {
      val fixture = boundary(withWorkUnits = true)
      val created = createOAuth(fixture, Set(OAuthScope.ReportsRead, OAuthScope.ReportsExport), OAuthProjectAccess.AllProjects)
      val claims  = verifiedClaims(issue(created, Some("reports:read")))
      val report = reportAdapter
        .legacyPivot(
          OAuthAccessPolicy(fixture.organization.id, OAuthProjectAccess.AllProjects),
          fixture.organization.slug,
          WorkUnitPivotTableRequest(Seq(WorkUnitPivotTableKey.ProjectKey), Seq(WorkUnitPivotTableValue.EntriesCount)),
          WorkUnitRepository.WorkUnitFilters()
        )
        .unsafeRunSync()
      val exportOperation = OAuthOperationRegistry.default
        .classify(HttpMethods.POST, s"/organizations/${fixture.organization.slug}/work-units/export")
        .asInstanceOf[OAuthEnabled]
        .operation

      (claims.scopes shouldEqual Set(OAuthScope.ReportsRead)) and
        (report must beAnInstanceOf[WorkUnitPivotTableResult.Ok]) and
        (claims.scopes.contains(exportOperation.requiredScope) should beFalse) and
        (exportOperation.requiredScope shouldEqual OAuthScope.ReportsExport)
    }

    "OAUTH-E2E-003 unknown and wrong-secret clients are indistinguishable" in record("OAUTH-E2E-003") {
      val fixture = boundary()
      val created = createOAuth(fixture, Set(OAuthScope.ProjectsRead), OAuthProjectAccess.AllProjects)
      val wrong = issueResult(
        Some(OAuthClientCredentials(created.client.clientId, OAuthClientSecret("epoca_oauth_secret_v1_wrong_redacted"))),
        None,
        nextSource()
      )
      val unknown = issueResult(
        Some(
          OAuthClientCredentials(
            OAuthClientPublicId("epoca_oauth_client_v1_AAAAAAAAAAAAAAAAAAAAAA"),
            OAuthClientSecret("epoca_oauth_secret_v1_unknown_redacted")
          )
        ),
        None,
        nextSource()
      )

      (wrong shouldEqual InvalidClient) and (unknown shouldEqual wrong)
    }

    "OAUTH-E2E-004 unknown and wider scopes fail closed" in record("OAUTH-E2E-004") {
      val fixture = boundary()
      val created = createOAuth(fixture, Set(OAuthScope.ProjectsRead), OAuthProjectAccess.AllProjects)

      (issueResult(credentials(created), Some("projects:read reports:read"), nextSource()) shouldEqual InvalidScope) and
        (issueResult(credentials(created), Some("admin:write"), nextSource()) shouldEqual InvalidScope)
    }

    "OAUTH-E2E-005 distributed IP rate limit is shared by two instances" in record("OAUTH-E2E-005") {
      val fixture = boundary()
      val created = createOAuth(fixture, Set(OAuthScope.ProjectsRead), OAuthProjectAccess.AllProjects)
      installFastEquivalentHashForRateLimitProbe(fixture, created)
      val fixedNow = run(authoritativeTime.now)
      val fixed    = fixedTime(fixedNow)
      val limits   = new OAuthRateLimitRepositoryImpl(OAuthRateLimit(60, 60.seconds), OAuthRateLimit(100, 300.seconds))
      val first    = tokenServiceFor(transactor, oauthClientRepository, oauthAuditRepository, limits, jwtService, fixed)
      val second   = tokenServiceFor(transactor, oauthClientRepository, oauthAuditRepository, limits, jwtService, fixed)
      val source   = nextSource()
      val results = (1 to 61).map { attempt =>
        val instance = if (attempt % 2 == 0) first else second
        instance.issue(credentials(created), None, source, None).unsafeRunSync()
      }
      val counts = results.count(_.isInstanceOf[Issued]) -> results.count(_.isInstanceOf[RateLimited])

      counts shouldEqual (60 -> 1)
    }

    "OAUTH-E2E-006 bad-secret client limit spans source IPs" in record("OAUTH-E2E-006") {
      val fixture = boundary()
      val created = createOAuth(fixture, Set(OAuthScope.ProjectsRead), OAuthProjectAccess.AllProjects)
      val limits  = new OAuthRateLimitRepositoryImpl(OAuthRateLimit(100, 60.seconds), OAuthRateLimit(10, 300.seconds))
      val first   = tokenServiceFor(transactor, oauthClientRepository, oauthAuditRepository, limits, jwtService)
      val second  = tokenServiceFor(transactor, oauthClientRepository, oauthAuditRepository, limits, jwtService)
      val bad     = Some(OAuthClientCredentials(created.client.clientId, OAuthClientSecret("epoca_oauth_secret_v1_bad_redacted")))
      val rejected = (1 to 10).forall { attempt =>
        val instance = if (attempt % 2 == 0) first else second
        instance.issue(bad, None, nextSource(), None).unsafeRunSync() == InvalidClient
      }

      (rejected && second.issue(bad, None, nextSource(), None).unsafeRunSync().isInstanceOf[RateLimited]) should beTrue
    }

    "OAUTH-E2E-007 expiry has no leeway at the authoritative boundary" in record("OAUTH-E2E-007") {
      val fixture = boundary()
      val created = createOAuth(fixture, Set(OAuthScope.ProjectsRead), OAuthProjectAccess.AllProjects)
      val token   = issue(created)
      val claims  = verifiedClaims(token)
      val fresh   = issue(created)

      (jwtService.verify(token, claims.expiresAt.minusSeconds(1)) must beRight) and
        (jwtService.verify(token, claims.expiresAt) shouldEqual Left(OAuthJwtError.Expired)) and
        (jwtService.verifyClaims(fresh) must beRight)
    }

    "OAUTH-E2E-008 rotation rejects old secret while preserving issued token" in record("OAUTH-E2E-008") {
      val fixture = boundary()
      val created = createOAuth(fixture, Set(OAuthScope.ProjectsRead), OAuthProjectAccess.AllProjects)
      val token   = issue(created)
      val claims  = verifiedClaims(token)
      val rotated = managementService
        .rotateSecret(auth(fixture.owner), fixture.organization.slug, created.client.clientId, OAuthClientVersionRequest(created.client.version))
        .unsafeRunSync()
        .asInstanceOf[Success[OAuthClientSecretView]]
        .value

      (issueResult(credentials(created), None, nextSource()) shouldEqual InvalidClient) and
        (issueResult(credentials(rotated), None, nextSource()) must beAnInstanceOf[Issued]) and
        (authenticateEither(token) must beRight) and
        (jwtService.verify(token, claims.expiresAt) shouldEqual Left(OAuthJwtError.Expired))
    }

    "OAUTH-E2E-009 revocation invalidates credentials, tokens, and mutations" in record("OAUTH-E2E-009") {
      val fixture = boundary()
      val created = createOAuth(fixture, Set(OAuthScope.ProjectsRead), OAuthProjectAccess.AllProjects)
      val token   = issue(created)
      val revoked = managementService
        .revoke(auth(fixture.owner), fixture.organization.slug, created.client.clientId, OAuthClientVersionRequest(created.client.version))
        .unsafeRunSync()
        .asInstanceOf[Success[OAuthClientView]]
        .value
      val update = managementService
        .update(
          auth(fixture.owner),
          fixture.organization.slug,
          created.client.clientId,
          OAuthClientUpdateRequest(revoked.version, Some("cannot-reactivate"), None, None)
        )
        .unsafeRunSync()

      (issueResult(credentials(created), None, nextSource()) shouldEqual InvalidClient) and
        (authenticateEither(token) must beLeft) and
        (update shouldEqual NotActive)
    }

    "OAUTH-E2E-010 project policy changes stale old tokens" in record("OAUTH-E2E-010") {
      val fixture = boundary()
      val created = createOAuth(fixture, Set(OAuthScope.ProjectsRead), OAuthProjectAccess.SelectedProjects(Set(fixture.p1.id)))
      val token   = issue(created)
      val updated = managementService
        .update(
          auth(fixture.owner),
          fixture.organization.slug,
          created.client.clientId,
          OAuthClientUpdateRequest(created.client.version, None, None, Some(OAuthProjectAccess.SelectedProjects(Set(fixture.p2.id))))
        )
        .unsafeRunSync()
        .asInstanceOf[Success[OAuthClientView]]
        .value
      val newContext = authenticate(issue(created.copy(client = updated)))
      val projects = projectAdapter
        .list(OAuthAccessPolicy.from(newContext), fixture.organization.slug, ProjectRepository.ProjectFilters())
        .unsafeRunSync()
        .asInstanceOf[Ok[pl.iterators.epoca.utils.db.PaginatedResult[pl.iterators.epoca.project.services.ProjectDtoService.ProjectListDto]]]
        .value

      (authenticateEither(token) must beLeft) and
        (newContext.projectAccess shouldEqual OAuthProjectAccess.SelectedProjects(Set(fixture.p2.id))) and
        (projects.results.map(_.id) shouldEqual Seq(fixture.p2.id))
    }

    "OAUTH-E2E-011 organization and direct-project IDOR is unavailable" in record("OAUTH-E2E-011") {
      val fixture = boundary()
      val foreign = boundary()
      val policy  = OAuthAccessPolicy(fixture.organization.id, OAuthProjectAccess.SelectedProjects(Set(fixture.p1.id)))

      (organizationAdapter.find(policy, foreign.organization.slug).unsafeRunSync() should beNone) and
        (projectAdapter.find(policy, fixture.organization.slug, fixture.p2.id).unsafeRunSync() shouldEqual NotFound) and
        (projectAdapter.find(policy, foreign.organization.slug, foreign.p1.id).unsafeRunSync() shouldEqual NotFound)
    }

    "OAUTH-E2E-012 nested project, client, and user views exclude P2" in record("OAUTH-E2E-012") {
      val fixture = boundary()
      val policy  = OAuthAccessPolicy(fixture.organization.id, OAuthProjectAccess.SelectedProjects(Set(fixture.p1.id)))
      val projects = projectAdapter
        .list(policy, fixture.organization.slug, ProjectRepository.ProjectFilters())
        .unsafeRunSync()
        .asInstanceOf[Ok[pl.iterators.epoca.utils.db.PaginatedResult[pl.iterators.epoca.project.services.ProjectDtoService.ProjectListDto]]]
        .value
      val clients = clientAdapter
        .list(policy, fixture.organization.slug, ClientRepository.ClientFilters())
        .unsafeRunSync()
        .asInstanceOf[Ok[pl.iterators.epoca.utils.db.PaginatedResult[pl.iterators.epoca.client.services.ClientDtoService.ClientDto]]]
        .value
      val users = userAdapter
        .list(policy, fixture.organization.slug)
        .unsafeRunSync()
        .asInstanceOf[Ok[Seq[pl.iterators.epoca.organizationdashboard.services.OrganizationUserListService.OrganizationUserDto]]]
        .value

      (projects.results.map(_.id) shouldEqual Seq(fixture.p1.id)) and
        (clients.results.map(_.id) shouldEqual Seq(fixture.client.id)) and
        (clients.results.head.projectCount shouldEqual pl.iterators.epoca.client.domain.ClientDomain.ClientProjectCount(1)) and
        (users.map(_.id).toSet shouldEqual Set(fixture.owner.id, fixture.member.id))
    }

    "OAUTH-E2E-013 work-unit reads require source and effective project scope" in record("OAUTH-E2E-013") {
      val fixture = boundary(withWorkUnits = true)
      val policy  = OAuthAccessPolicy(fixture.organization.id, OAuthProjectAccess.SelectedProjects(Set(fixture.p1.id)))
      val visible = workUnitReadAdapter
        .list(policy, fixture.organization.slug, WorkUnitRepository.WorkUnitFilters())
        .unsafeRunSync()
        .asInstanceOf[Ok[WorkUnitListService.WorkUnitListData]]
        .value
      val denied = workUnitReadAdapter
        .list(policy, fixture.organization.slug, WorkUnitRepository.WorkUnitFilters(projectIds = Some(Seq(fixture.p2.id))))
        .unsafeRunSync()
        .asInstanceOf[Ok[WorkUnitListService.WorkUnitListData]]
        .value
      run(DBIO.sequence(fixture.workUnits.map { unit =>
        val target = if (unit.projectId == fixture.p1.id) fixture.p2.id else fixture.p1.id
        TableQuery[WorkUnitTable]
          .filter(_.id === unit.id)
          .map(row => (row.overrideProjectId, row.overrideVersion, row.overrideLastChangedBy, row.overrideLastChangedAt))
          .update((Some(target), 1L, Some(fixture.owner.id), Some(Instant.now())))
      }))
      val corrected = workUnitReadAdapter
        .list(policy, fixture.organization.slug, WorkUnitRepository.WorkUnitFilters())
        .unsafeRunSync()
        .asInstanceOf[Ok[WorkUnitListService.WorkUnitListData]]
        .value

      (visible.workUnits.results.map(_.id).toSet shouldEqual Set(fixture.workUnits.head.id)) and
        (denied.workUnits.results should beEmpty) and
        (corrected.workUnits.count shouldEqual 0) and
        (corrected.workUnits.results should beEmpty)
    }

    "OAUTH-E2E-014 work-unit writes require explicit human P1 targets" in record("OAUTH-E2E-014") {
      val fixture = boundary()
      val policy  = OAuthAccessPolicy(fixture.organization.id, OAuthProjectAccess.SelectedProjects(Set(fixture.p1.id)))
      val request = WorkUnitCreateRequest(
        fixture.p1.id,
        WorkUnitDate(LocalDate.now().minusDays(1)),
        Some(WorkUnitDuration(java.time.Duration.ofMinutes(30))),
        WorkUnitDescription("oauth e2e"),
        None,
        isDraft = false,
        userId = Some(fixture.member.id)
      )
      val valid   = run(createService.createOAuthAction(policy, fixture.organization.slug, request))
      val missing = run(createService.createOAuthAction(policy, fixture.organization.slug, request.copy(userId = None)))
      val denied  = run(createService.createOAuthAction(policy, fixture.organization.slug, request.copy(projectId = fixture.p2.id)))

      (valid must beAnInstanceOf[WorkUnitCreateResult.Created]) and
        (missing shouldEqual WorkUnitCreateResult.TargetUserNotFound) and
        (denied shouldEqual WorkUnitCreateResult.ProjectNotFound)
    }

    "OAUTH-E2E-015 reports and files exclude source or PM project outside policy" in record("OAUTH-E2E-015") {
      val fixture = boundary(withWorkUnits = true)
      val policy  = OAuthAccessPolicy(fixture.organization.id, OAuthProjectAccess.SelectedProjects(Set(fixture.p1.id)))
      val request = WorkUnitPivotTableRequest(Seq(WorkUnitPivotTableKey.ProjectKey), Seq(WorkUnitPivotTableValue.EntriesCount))
      val result  = reportAdapter.legacyPivot(policy, fixture.organization.slug, request, WorkUnitRepository.WorkUnitFilters()).unsafeRunSync()
      val columns = ExportWorkUnitColumnNames(None, None, Some(SpreadsheetColumnName("Source Project")), None, None, None, None, None, None, None)
      val exportResult = exportAdapter
        .exportWorkUnits(
          policy,
          fixture.organization.slug,
          ExportWorkUnitRequest(columns, Seq.empty, None, None, Seq.empty, Seq.empty, Seq.empty),
          SpreadsheetFormat.Csv
        )
        .unsafeRunSync()

      val reportCheck = result match {
        case WorkUnitPivotTableResult.Ok(rows) => rows.flatMap(_.projectId).toSet shouldEqual Set(fixture.p1.id)
        case other                             => ko(s"unexpected report result: $other")
      }
      val exportCheck = exportResult match {
        case ExportWorkUnitResult.Ok(_, stream, SpreadsheetFormat.Csv) =>
          val csv = new String(stream.compile.toVector.unsafeRunSync().toArray, StandardCharsets.UTF_8)
          (csv.contains(fixture.p1.name.toString) && !csv.contains(fixture.p2.name.toString)) should beTrue
        case other => ko(s"unexpected export result: $other")
      }
      reportCheck and exportCheck
    }

    "OAUTH-E2E-016 missing scope and human-only operations stay distinct" in record("OAUTH-E2E-016") {
      val reports = OAuthOperationRegistry.default
        .classify(HttpMethods.POST, "/organizations/example/pivot-tables/work-units")
        .asInstanceOf[OAuthEnabled]
      val humanOnly = OAuthOperationRegistry.default.classify(HttpMethods.GET, "/organizations/example/oauth-clients")

      (reports.operation.requiredScope == OAuthScope.ReportsRead &&
        reports.operation.requiredScope != OAuthScope.ProjectsRead &&
        humanOnly == HumanOnly) should beTrue
    }

    "OAUTH-E2E-017 issuance and rotation linearize on the client row" in record("OAUTH-E2E-017") {
      val fixture  = boundary()
      val created  = createOAuth(fixture, Set(OAuthScope.ProjectsRead), OAuthProjectAccess.AllProjects)
      val issueDb  = independentDatabase("ct520-e2e-issue")
      val rotateDb = independentDatabase("ct520-e2e-rotate")
      val acquired = Promise[Unit]()
      val release  = Promise[Unit]()
      val started  = Promise[Unit]()
      try {
        val issueRepository  = new PauseAfterLockRepository(acquired, release)
        val rotateRepository = new SignallingRotateRepository(started)
        val issuing = tokenServiceFor(
          new PostgresIOTransactor(issueDb),
          issueRepository,
          oauthAuditRepository,
          new OAuthRateLimitRepositoryImpl(OAuthRateLimit(60, 60.seconds), OAuthRateLimit(10, 300.seconds)),
          jwtService
        )
        val rotating = managementServiceFor(new PostgresIOTransactor(rotateDb), rotateRepository, oauthAuditRepository)
        val issuance = issuing.issue(credentials(created), None, nextSource(), None).unsafeToFuture()
        Await.result(acquired.future, 10.seconds)
        val rotation = rotating
          .rotateSecret(auth(fixture.owner), fixture.organization.slug, created.client.clientId, OAuthClientVersionRequest(created.client.version))
          .unsafeToFuture()
        Await.result(started.future, 10.seconds)
        val blocked = Try(Await.result(rotation, 200.millis)).failed.toOption.exists(_.isInstanceOf[TimeoutException])
        release.success(())
        val issued  = Await.result(issuance, 20.seconds).isInstanceOf[Issued]
        val rotated = Await.result(rotation, 20.seconds).isInstanceOf[Success[_]]

        (blocked && issued && rotated) should beTrue
      } finally {
        release.trySuccess(())
        issueDb.close()
        rotateDb.close()
      }
    }

    "OAUTH-E2E-018 authentication snapshots linearize around policy commit" in record("OAUTH-E2E-018") {
      val fixture  = boundary()
      val created  = createOAuth(fixture, Set(OAuthScope.ProjectsRead), OAuthProjectAccess.SelectedProjects(Set(fixture.p1.id)))
      val token    = issue(created)
      val authDb   = independentDatabase("ct520-e2e-auth")
      val acquired = Promise[Unit]()
      val release  = Promise[Unit]()
      try {
        val pausingRepository = new PauseAfterSnapshotRepository(acquired, release)
        val racingAuthenticator =
          new OAuthAuthenticator(new PostgresIOTransactor(authDb), authoritativeTime, jwtService, pausingRepository, serviceAccountRepository)
        val inFlight = authenticateEitherWith(racingAuthenticator, token)
        Await.result(acquired.future, 10.seconds)
        val updated = managementService
          .update(
            auth(fixture.owner),
            fixture.organization.slug,
            created.client.clientId,
            OAuthClientUpdateRequest(created.client.version, None, None, Some(OAuthProjectAccess.SelectedProjects(Set(fixture.p2.id))))
          )
          .unsafeRunSync()
        release.success(())
        val beforeCommit       = Await.result(inFlight, 20.seconds)
        val beforeCommitUsesP1 = beforeCommit.toOption.exists(_.projectAccess == OAuthProjectAccess.SelectedProjects(Set(fixture.p1.id)))

        (beforeCommitUsesP1 && updated.isInstanceOf[Success[_]] && authenticateEither(token).isLeft) should beTrue
      } finally {
        release.trySuccess(())
        authDb.close()
      }
    }

    "OAUTH-E2E-019 two instances honor signing-key overlap and emergency removal" in record("OAUTH-E2E-019") {
      val fixture  = boundary()
      val created  = createOAuth(fixture, Set(OAuthScope.ProjectsRead), OAuthProjectAccess.AllProjects)
      val oldToken = issue(created)
      val overlap = OAuthSigningKeyRing(
        "ct520-e2e-b",
        Vector(OAuthSigningKey("ct520-e2e-a", signingKeyA, retired = false), OAuthSigningKey("ct520-e2e-b", signingKeyB, retired = false))
      )
      val newInstance = new OAuthJwtService(issuer, audience, overlap)
      val removedOld = new OAuthJwtService(
        issuer,
        audience,
        OAuthSigningKeyRing("ct520-e2e-b", Vector(OAuthSigningKey("ct520-e2e-b", signingKeyB, retired = false)))
      )
      val claims   = verifiedClaims(oldToken)
      val newToken = newInstance.sign(claims.copy(tokenId = UUID.randomUUID())).toOption.get

      (newInstance.verifyClaims(oldToken) must beRight) and
        (jwtService.verifyClaims(newToken) must beLeft) and
        (removedOld.verifyClaims(oldToken) shouldEqual Left(OAuthJwtError.UnknownKey)) and
        (removedOld.verifyClaims(newToken) must beRight)
    }

    "OAUTH-E2E-020 owner and contextual superadmin manage; manager and foreign actor cannot" in record("OAUTH-E2E-020") {
      val fixture = boundary()
      val manager = newHuman()
      run(userRepository.insert(manager))
      run(
        organizationUserRepository
          .upsert(OrganizationUser(OrganizationUserId.random, fixture.organization.id, manager.id, Seq(OrganizationUserRole.Manager), None))
      )
      val managerResult = managementService
        .create(
          auth(manager),
          fixture.organization.slug,
          OAuthClientCreateRequest(randomName(), Set(OAuthScope.ProjectsRead), OAuthProjectAccess.AllProjects)
        )
        .unsafeRunSync()
      val ownerResult = managementService
        .create(
          auth(fixture.owner),
          fixture.organization.slug,
          OAuthClientCreateRequest(randomName(), Set(OAuthScope.ProjectsRead), OAuthProjectAccess.AllProjects)
        )
        .unsafeRunSync()
      val ownerClient = ownerResult.asInstanceOf[Success[OAuthClientSecretView]].value.client
      val renamed = managementService
        .update(
          auth(fixture.owner),
          fixture.organization.slug,
          ownerClient.clientId,
          OAuthClientUpdateRequest(ownerClient.version, Some(randomName()), None, None)
        )
        .unsafeRunSync()
      val stale = managementService
        .update(
          auth(fixture.owner),
          fixture.organization.slug,
          ownerClient.clientId,
          OAuthClientUpdateRequest(ownerClient.version, Some(randomName()), None, None)
        )
        .unsafeRunSync()
      val superAdmin = newHuman().copy(isSuperAdmin = UserIsSuperAdmin(true))
      run(userRepository.insert(superAdmin))
      val superAdminResult = managementService
        .create(
          auth(superAdmin),
          fixture.organization.slug,
          OAuthClientCreateRequest(randomName(), Set(OAuthScope.ProjectsRead), OAuthProjectAccess.AllProjects)
        )
        .unsafeRunSync()
      val foreign       = boundary()
      val foreignResult = managementService.list(auth(fixture.owner), foreign.organization.slug, None, None).unsafeRunSync()
      val overGrant = managementService
        .create(
          auth(fixture.owner),
          fixture.organization.slug,
          OAuthClientCreateRequest(randomName(), Set(OAuthScope.ProjectsRead), OAuthProjectAccess.SelectedProjects(Set(foreign.p1.id)))
        )
        .unsafeRunSync()

      (managerResult == OperationNotPermitted &&
        ownerResult.isInstanceOf[Success[_]] &&
        renamed.isInstanceOf[Success[_]] &&
        stale.isInstanceOf[StaleVersion] &&
        superAdminResult.isInstanceOf[Success[_]] &&
        foreignResult == OrganizationNotFound &&
        overGrant == ProjectNotFound) should beTrue
    }

    "OAUTH-E2E-021 audit metadata is redacted and audit failure rolls back" in record("OAUTH-E2E-021") {
      val fixture     = boundary()
      val created     = createOAuth(fixture, Set(OAuthScope.ProjectsRead), OAuthProjectAccess.AllProjects)
      val snapshot    = run(oauthClientRepository.findPublic(created.client.clientId)).get
      val events      = run(oauthAuditRepository.list(OAuthAuditEventFilters(snapshot.client.id)))
      val lifecycleAt = Instant.parse("2026-08-13T00:00:00Z")
      val before      = run(oauthClientRepository.count(OAuthClientRepository.OAuthClientFilters(fixture.organization.id, None, None), lifecycleAt))
      val failing     = managementServiceFor(transactor, oauthClientRepository, new FailingAuditRepository(oauthAuditRepository))
      val failure = failing
        .create(
          auth(fixture.owner),
          fixture.organization.slug,
          OAuthClientCreateRequest(randomName(), Set(OAuthScope.ProjectsRead), OAuthProjectAccess.AllProjects)
        )
        .unsafeRunSync()
      val after    = run(oauthClientRepository.count(OAuthClientRepository.OAuthClientFilters(fixture.organization.id, None, None), lifecycleAt))
      val metadata = events.map(_.metadata.noSpaces).mkString

      (metadata must not(contain(created.clientSecret))) and
        (metadata must not(contain("$2a$"))) and
        (failure shouldEqual PersistenceUnavailable) and
        (after shouldEqual before)
    }

    "OAUTH-E2E-022 unclassified protected operation fails the inventory contract" in record("OAUTH-E2E-022") {
      val registry = OAuthOperationRegistry.default
      val unknown  = OAuthHttpOperation(HttpMethods.POST, "/organizations/{organizationSlug}/new-protected-route")

      (registry.classify(HttpMethods.POST, "/organizations/example/new-protected-route") shouldEqual Unclassified) and
        (registry.validateProtectedInventory(registry.classifiedProtectedOperations + unknown) must beLeft)
    }

    "OAUTH-E2E-030 service accounts cannot enter human credential or assignment surfaces" in record("OAUTH-E2E-030") {
      val fixture        = boundary()
      val created        = createOAuth(fixture, Set(OAuthScope.ProjectsRead), OAuthProjectAccess.AllProjects)
      val snapshot       = run(oauthClientRepository.findPublic(created.client.clientId)).get
      val serviceAccount = run(serviceAccountRepository.findById(snapshot.client.serviceAccountUserId)).get
      val assignment = allGenerators[pl.iterators.epoca.project.domain.ProjectUser].normal.generate
        .copy(projectId = fixture.p1.id, userId = serviceAccount.id, archivedAt = None)
      val rejectedAssignment = Try(run(projectUserRepository.insert(assignment))).isFailure
      val identities = run(
        userIdentityRepository
          .list(pl.iterators.epoca.user.repositories.UserIdentityRepository.UserIdentityFilters(userIds = Some(Seq(serviceAccount.id))))
      )

      (serviceAccount.accountType shouldEqual UserAccountType.ServiceAccount) and
        (rejectedAssignment should beTrue) and
        (identities should beEmpty)
    }

    "OAUTH-E2E-031 serialization, facets, dimensions, and aggregates contain no P2" in record("OAUTH-E2E-031") {
      val fixture = boundary(withWorkUnits = true)
      val policy  = OAuthAccessPolicy(fixture.organization.id, OAuthProjectAccess.SelectedProjects(Set(fixture.p1.id)))
      val workUnits = workUnitReadAdapter
        .list(policy, fixture.organization.slug, WorkUnitRepository.WorkUnitFilters())
        .unsafeRunSync()
        .asInstanceOf[Ok[WorkUnitListService.WorkUnitListData]]
        .value
      val report = reportAdapter
        .legacyPivot(
          policy,
          fixture.organization.slug,
          WorkUnitPivotTableRequest(Seq(WorkUnitPivotTableKey.ProjectKey), Seq(WorkUnitPivotTableValue.EntriesCount)),
          WorkUnitRepository.WorkUnitFilters()
        )
        .unsafeRunSync()
        .asInstanceOf[WorkUnitPivotTableResult.Ok]

      (workUnits.workUnits.results.map(_.id).toSet shouldEqual Set(fixture.workUnits.head.id)) and
        (report.data.flatMap(_.projectId).toSet shouldEqual Set(fixture.p1.id))
    }

    "OAUTH-E2E-032 removing a scope stales T1 without changing projects" in record("OAUTH-E2E-032") {
      val fixture = boundary()
      val created = createOAuth(fixture, Set(OAuthScope.ProjectsRead, OAuthScope.ReportsRead), OAuthProjectAccess.AllProjects)
      val token   = issue(created)
      val updated = managementService
        .update(
          auth(fixture.owner),
          fixture.organization.slug,
          created.client.clientId,
          OAuthClientUpdateRequest(created.client.version, None, Some(Set(OAuthScope.ProjectsRead)), None)
        )
        .unsafeRunSync()
        .asInstanceOf[Success[OAuthClientView]]
        .value

      (authenticateEither(token) must beLeft) and
        (issueResult(credentials(created.copy(client = updated)), Some("reports:read"), nextSource()) shouldEqual InvalidScope) and
        (verifiedClaims(issue(created.copy(client = updated))).scopes shouldEqual Set(OAuthScope.ProjectsRead))
    }

    "OAUTH-E2E-033 AllProjects token sees projects created after issuance" in record("OAUTH-E2E-033") {
      val fixture = boundary()
      val created = createOAuth(fixture, Set(OAuthScope.ProjectsRead), OAuthProjectAccess.AllProjects)
      val context = authenticate(issue(created))
      val futureProject = run(
        withProject(
          fixture.organization,
          fixture.client,
          allGenerators[Project].normal.generate.copy(createdBy = fixture.owner.id, modifiedBy = fixture.owner.id, archivedAt = None)
        )
      )

      projectAdapter
        .find(OAuthAccessPolicy.from(context), fixture.organization.slug, futureProject.id)
        .unsafeRunSync() must beAnInstanceOf[Ok[_]]
    }

    "CT-537-E2E-001 create without expiry exposes complete safe metadata" in record("CT-537-E2E-001") {
      val fixture = boundary()
      val created = createOAuth(fixture, Set(OAuthScope.ProjectsRead), OAuthProjectAccess.AllProjects)
      val fetched = managementService
        .get(auth(fixture.owner), fixture.organization.slug, created.client.clientId)
        .unsafeRunSync()
        .asInstanceOf[Success[OAuthClientView]]
        .value
      val json = fetched.asJson

      (fetched shouldEqual created.client) and
        (fetched.status shouldEqual OAuthClientEffectiveStatus.Active) and
        (fetched.expiresAt should beNone) and
        (fetched.lastUsedAt should beNone) and
        (json.hcursor.downField("expiresAt").focus shouldEqual Some(Json.Null)) and
        (json.hcursor.downField("lastUsedAt").focus shouldEqual Some(Json.Null)) and
        assertCompleteSafeClientMetadata(fetched)
    }

    "CT-537-E2E-002 future expiry records lastUsedAt only after successful bearer use" in record("CT-537-E2E-002") {
      val fixture = boundary()
      val expires = OAuthClientExpiresAt(run(authoritativeTime.now).plusSeconds(600L))
      val created = createOAuth(fixture, Set(OAuthScope.ProjectsRead), OAuthProjectAccess.AllProjects, Some(expires))
      val token   = issue(created)
      val afterIssuance = managementService
        .get(auth(fixture.owner), fixture.organization.slug, created.client.clientId)
        .unsafeRunSync()
        .asInstanceOf[Success[OAuthClientView]]
        .value
      val beforeUse = run(authoritativeTime.now)
      val context   = authenticate(token)
      val afterUse  = run(authoritativeTime.now)
      val observed = managementService
        .get(auth(fixture.owner), fixture.organization.slug, created.client.clientId)
        .unsafeRunSync()
        .asInstanceOf[Success[OAuthClientView]]
        .value

      (created.client.expiresAt shouldEqual Some(expires)) and
        (afterIssuance.lastUsedAt should beNone) and
        (context.organizationId shouldEqual fixture.organization.id) and
        (observed.lastUsedAt must beSome.which { value =>
          val instant = value.asInstanceOf[Instant]
          !instant.isBefore(beforeUse) && !instant.isAfter(afterUse)
        })
    }

    "CT-537-E2E-003 editing expiry invalidates the old token through policyVersion" in record("CT-537-E2E-003") {
      val fixture       = boundary()
      val initialExpiry = OAuthClientExpiresAt(run(authoritativeTime.now).plusSeconds(600L))
      val created       = createOAuth(fixture, Set(OAuthScope.ProjectsRead), OAuthProjectAccess.AllProjects, Some(initialExpiry))
      val oldToken      = issue(created)
      val oldClaims     = verifiedClaims(oldToken)
      val movedExpiry   = OAuthClientExpiresAt(run(authoritativeTime.now).plusSeconds(1200L))
      val updated = managementService
        .update(
          auth(fixture.owner),
          fixture.organization.slug,
          created.client.clientId,
          OAuthClientUpdateRequest(created.client.version, None, None, None, OAuthExpirationPatch.Set(movedExpiry))
        )
        .unsafeRunSync()
        .asInstanceOf[Success[OAuthClientView]]
        .value

      (jwtService.verifyClaims(oldToken) must beRight) and
        (updated.status shouldEqual OAuthClientEffectiveStatus.Active) and
        (updated.version shouldEqual created.client.version + 1L) and
        (updated.policyVersion shouldEqual created.client.policyVersion + 1L) and
        (updated.secretVersion shouldEqual created.client.secretVersion) and
        (oldClaims.policyVersion shouldEqual created.client.policyVersion) and
        ((oldClaims.policyVersion != updated.policyVersion) should beTrue) and
        (authenticateEither(oldToken) must beLeft)
    }

    "CT-537-E2E-004 exact database expiry boundary rejects issuance and bearer use generically" in record("CT-537-E2E-004") {
      val fixture             = boundary()
      val boundaryAt          = futureDatabaseBoundary()
      val expires             = OAuthClientExpiresAt(boundaryAt)
      val created             = createOAuth(fixture, Set(OAuthScope.ProjectsRead), OAuthProjectAccess.AllProjects, Some(expires))
      val token               = issue(created)
      val limits              = new OAuthRateLimitRepositoryImpl(OAuthRateLimit(100, 60.seconds), OAuthRateLimit(100, 300.seconds))
      val beforeBoundaryAt    = boundaryAt.minusNanos(1000L)
      val beforeRepository    = new FixedAuthoritativeNowRepository(beforeBoundaryAt)
      val beforeTokenService  = tokenServiceFor(transactor, beforeRepository, oauthAuditRepository, limits, jwtService, beforeRepository.time)
      val beforeAuthenticator = new OAuthAuthenticator(transactor, beforeRepository.time, jwtService, beforeRepository, serviceAccountRepository)
      val exactRepository     = new FixedAuthoritativeNowRepository(boundaryAt)
      val exactManagement     = managementServiceFor(transactor, exactRepository, oauthAuditRepository, exactRepository.time)
      val exactTokenService   = tokenServiceFor(transactor, exactRepository, oauthAuditRepository, limits, jwtService, exactRepository.time)
      val exactAuthenticator  = new OAuthAuthenticator(transactor, exactRepository.time, jwtService, exactRepository, serviceAccountRepository)
      val activeIssuance = beforeTokenService
        .issue(credentials(created), None, nextSource(), None)
        .unsafeRunSync()
      val activeBearer = Await.result(authenticateEitherWith(beforeAuthenticator, token), 10.seconds)
      val expired = exactManagement
        .get(auth(fixture.owner), fixture.organization.slug, created.client.clientId)
        .unsafeRunSync()
        .asInstanceOf[Success[OAuthClientView]]
        .value
      val expiredIssuance = exactTokenService
        .issue(credentials(created), None, nextSource(), None)
        .unsafeRunSync()
      val expiredBearer = Await.result(authenticateEitherWith(exactAuthenticator, token), 10.seconds)

      (boundaryAt.getNano % 1000 shouldEqual 0) and
        (beforeBoundaryAt shouldEqual boundaryAt.minusNanos(1000L)) and
        (beforeBoundaryAt.getNano % 1000 shouldEqual 0) and
        (created.client.expiresAt shouldEqual Some(expires)) and
        (activeIssuance must beAnInstanceOf[Issued]) and
        (activeBearer must beRight) and
        (beforeRepository.authoritativeNowCalls shouldEqual 3) and
        (expired.expiresAt shouldEqual Some(expires)) and
        (expired.status shouldEqual OAuthClientEffectiveStatus.Expired) and
        (expiredIssuance shouldEqual InvalidClient) and
        (expiredBearer must beLeft) and
        (exactRepository.authoritativeNowCalls shouldEqual 4)
    }

    "CT-537-E2E-005 renewal keeps old tokens stale and permits a newly issued token" in record("CT-537-E2E-005") {
      val fixture  = boundary()
      val expires  = OAuthClientExpiresAt(run(authoritativeTime.now).plusSeconds(600L))
      val created  = createOAuth(fixture, Set(OAuthScope.ProjectsRead), OAuthProjectAccess.AllProjects, Some(expires))
      val oldToken = issue(created)
      expireAtDatabaseBoundary(created.client.clientId)
      val expired = managementService
        .get(auth(fixture.owner), fixture.organization.slug, created.client.clientId)
        .unsafeRunSync()
        .asInstanceOf[Success[OAuthClientView]]
        .value
      val renewed = managementService
        .update(
          auth(fixture.owner),
          fixture.organization.slug,
          created.client.clientId,
          OAuthClientUpdateRequest(expired.version, None, None, None, OAuthExpirationPatch.Clear)
        )
        .unsafeRunSync()
        .asInstanceOf[Success[OAuthClientView]]
        .value
      val newToken = issue(created.copy(client = renewed))

      (expired.status shouldEqual OAuthClientEffectiveStatus.Expired) and
        (renewed.status shouldEqual OAuthClientEffectiveStatus.Active) and
        (renewed.expiresAt should beNone) and
        (renewed.policyVersion shouldEqual expired.policyVersion + 1L) and
        (authenticateEither(oldToken) must beLeft) and
        (authenticateEither(newToken) must beRight)
    }

    "CT-537-E2E-006 secret rotation is available only while the client is active" in record("CT-537-E2E-006") {
      val fixture = boundary()
      val expires = OAuthClientExpiresAt(run(authoritativeTime.now).plusSeconds(600L))
      val created = createOAuth(fixture, Set(OAuthScope.ProjectsRead), OAuthProjectAccess.AllProjects, Some(expires))
      val rotated = managementService
        .rotateSecret(auth(fixture.owner), fixture.organization.slug, created.client.clientId, OAuthClientVersionRequest(created.client.version))
        .unsafeRunSync()
        .asInstanceOf[Success[OAuthClientSecretView]]
        .value
      expireAtDatabaseBoundary(created.client.clientId)
      val expiredRotation = managementService
        .rotateSecret(auth(fixture.owner), fixture.organization.slug, created.client.clientId, OAuthClientVersionRequest(rotated.client.version))
        .unsafeRunSync()

      (rotated.client.secretVersion shouldEqual created.client.secretVersion + 1L) and
        (issueResult(credentials(created), None, nextSource()) shouldEqual InvalidClient) and
        (issueResult(credentials(rotated), None, nextSource()) shouldEqual InvalidClient) and
        (expiredRotation shouldEqual NotActive)
    }

    "CT-537-E2E-007 revoking an expired client is terminal" in record("CT-537-E2E-007") {
      val fixture = boundary()
      val expires = OAuthClientExpiresAt(run(authoritativeTime.now).plusSeconds(600L))
      val created = createOAuth(fixture, Set(OAuthScope.ProjectsRead), OAuthProjectAccess.AllProjects, Some(expires))
      val token   = issue(created)
      expireAtDatabaseBoundary(created.client.clientId)
      val expired = managementService
        .get(auth(fixture.owner), fixture.organization.slug, created.client.clientId)
        .unsafeRunSync()
        .asInstanceOf[Success[OAuthClientView]]
        .value
      val revoked = managementService
        .revoke(auth(fixture.owner), fixture.organization.slug, created.client.clientId, OAuthClientVersionRequest(expired.version))
        .unsafeRunSync()
        .asInstanceOf[Success[OAuthClientView]]
        .value
      val renew = managementService
        .update(
          auth(fixture.owner),
          fixture.organization.slug,
          created.client.clientId,
          OAuthClientUpdateRequest(revoked.version, None, None, None, OAuthExpirationPatch.Clear)
        )
        .unsafeRunSync()
      val rotate = managementService
        .rotateSecret(auth(fixture.owner), fixture.organization.slug, created.client.clientId, OAuthClientVersionRequest(revoked.version))
        .unsafeRunSync()
      val revokeAgain = managementService
        .revoke(auth(fixture.owner), fixture.organization.slug, created.client.clientId, OAuthClientVersionRequest(revoked.version))
        .unsafeRunSync()

      (expired.status shouldEqual OAuthClientEffectiveStatus.Expired) and
        (revoked.status shouldEqual OAuthClientEffectiveStatus.Revoked) and
        (renew shouldEqual NotActive) and
        (rotate shouldEqual NotActive) and
        (revokeAgain shouldEqual NotActive) and
        (issueResult(credentials(created.copy(client = revoked)), None, nextSource()) shouldEqual InvalidClient) and
        (authenticateEither(token) must beLeft)
    }

    "CT-537-E2E-008 ordinary management and audit JSON never exposes credential or human identity material" in record("CT-537-E2E-008") {
      val fixture = boundary()
      val created = createOAuth(fixture, Set(OAuthScope.ProjectsRead), OAuthProjectAccess.AllProjects)
      val rotated = managementService
        .rotateSecret(auth(fixture.owner), fixture.organization.slug, created.client.clientId, OAuthClientVersionRequest(created.client.version))
        .unsafeRunSync()
        .asInstanceOf[Success[OAuthClientSecretView]]
        .value
      val updated = managementService
        .update(
          auth(fixture.owner),
          fixture.organization.slug,
          created.client.clientId,
          OAuthClientUpdateRequest(rotated.client.version, Some(randomName()), None, None)
        )
        .unsafeRunSync()
        .asInstanceOf[Success[OAuthClientView]]
        .value
      val fetched = managementService
        .get(auth(fixture.owner), fixture.organization.slug, created.client.clientId)
        .unsafeRunSync()
        .asInstanceOf[Success[OAuthClientView]]
        .value
      val listed = managementService
        .list(auth(fixture.owner), fixture.organization.slug, None, None)
        .unsafeRunSync()
        .asInstanceOf[Success[pl.iterators.epoca.utils.db.PaginatedResult[OAuthClientView]]]
        .value
      val revoked = managementService
        .revoke(auth(fixture.owner), fixture.organization.slug, created.client.clientId, OAuthClientVersionRequest(updated.version))
        .unsafeRunSync()
        .asInstanceOf[Success[OAuthClientView]]
        .value
      val audit = managementService
        .listAuditEvents(auth(fixture.owner), fixture.organization.slug, created.client.clientId, None)
        .unsafeRunSync()
        .asInstanceOf[Success[pl.iterators.epoca.utils.db.PaginatedResult[OAuthAuditEventView]]]
        .value
      val ordinaryResponses = Seq(updated.asJson, fetched.asJson, listed.asJson, revoked.asJson, audit.asJson)
      val forbiddenKeys = Set("clientSecret", "secretHash", "accessToken", "access_token", "authorization", "email", "login", "roles", "isSuperAdmin")
      val wrappersAreNarrow = Seq(created, rotated).forall { wrapper =>
        wrapper.asJson.asObject.exists(_.keys.toSet == Set("client", "clientSecret")) &&
        wrapper.client.asJson.asObject.exists(!_.contains("clientSecret"))
      }
      val ordinaryAreSafe = ordinaryResponses.forall { json =>
        val text = json.noSpaces
        (jsonFieldNames(json) intersect forbiddenKeys).isEmpty &&
        !text.contains(created.clientSecret) &&
        !text.contains(rotated.clientSecret) &&
        !text.contains("$2a$") &&
        !text.contains("$2b$") &&
        !text.contains("$2y$") &&
        !text.contains("eyJ")
      }
      val auditActorsAreNarrow = audit.results.forall(_.actor.forall(actor => jsonFieldNames(actor.asJson) == Set("userId", "displayName")))

      (wrappersAreNarrow should beTrue) and
        (ordinaryAreSafe should beTrue) and
        (auditActorsAreNarrow should beTrue)
    }

    "write exact-SHA sanitized evidence only after the complete matrix passes" in {
      val inventoryResult = passedIds.toVector shouldEqual combinedScenarioIds
      if (!inventoryResult.isSuccess) inventoryResult
      else {
        val evidence = Json.obj(
          "schemaVersion"    -> Json.fromInt(1),
          "taskId"           -> Json.fromString("CT-520"),
          "commitSha"        -> Json.fromString(commitSha),
          "startedAt"        -> Json.fromString(startedAt.truncatedTo(ChronoUnit.MILLIS).toString),
          "finishedAt"       -> Json.fromString(Instant.now().truncatedTo(ChronoUnit.MILLIS).toString),
          "command"          -> Json.fromString("sbt \"testOnly pl.iterators.epoca.auth.OAuthClientCredentialsE2ESpec\""),
          "scenarioIds"      -> combinedScenarioIds.asJson,
          "postgresVersion"  -> Json.fromString(postgresVersion),
          "backendInstances" -> Json.fromInt(2),
          "outcome"          -> Json.fromString("passed")
        )
        val text = evidence.spaces2 + "\n"
        Option(evidencePath.getParent).foreach(parent => Files.createDirectories(parent))
        Files.writeString(
          evidencePath,
          text,
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING,
          StandardOpenOption.WRITE
        )
        (Files.isRegularFile(evidencePath) &&
          !text.contains("epoca_oauth_secret_v1_") &&
          !text.contains("eyJ") &&
          !text.contains("$2a$") &&
          !text.contains(signingKeyA) &&
          !text.contains(signingKeyB)) should beTrue
      }
    }
  }

  private final class Boundary(
    val owner: User,
    val member: User,
    val organization: Organization,
    val client: Client,
    val p1: Project,
    val p2: Project,
    val workUnits: Seq[WorkUnit])

  private def boundary(withWorkUnits: Boolean = false): Boundary = {
    val ownerTemplate  = newHuman()
    val memberTemplate = newHuman()
    run {
      for {
        owner  <- withUser(ownerTemplate)
        member <- withUser(memberTemplate)
        organization <-
          withOrganization(
            allGenerators[Organization].normal.generate
              .copy(slug = OrganizationSlug(s"ct520-e2e-${UUID.randomUUID()}"), createdBy = owner.id, modifiedBy = owner.id, archivedAt = None)
          )
        _      <- withCreatorOrganizationUser(organization, Seq(OrganizationUserRole.Owner))
        _      <- withOrganizationUser(OrganizationUser(OrganizationUserId.random, organization.id, member.id, roles = Nil, archivedAt = None))
        client <- withClient(organization, allGenerators[Client].normal.generate.copy(createdBy = owner.id, modifiedBy = owner.id, archivedAt = None))
        p1 <- withProject(
                organization,
                client,
                allGenerators[Project].normal.generate
                  .copy(name = ProjectName(s"ct520-p1-${UUID.randomUUID()}"), createdBy = owner.id, modifiedBy = owner.id, archivedAt = None)
              )
        p2 <- withProject(
                organization,
                client,
                allGenerators[Project].normal.generate
                  .copy(name = ProjectName(s"ct520-p2-${UUID.randomUUID()}"), createdBy = owner.id, modifiedBy = owner.id, archivedAt = None)
              )
        _ <- withProjectUser(owner, p1)
        _ <- withProjectUser(member, p1)
        _ <- withProjectUser(owner, p2)
        units <-
          if (withWorkUnits)
            for {
              first  <- withWorkUnit(organization, member, p1, activeWorkUnit())
              second <- withWorkUnit(organization, member, p2, activeWorkUnit())
            } yield Seq(first, second)
          else DBIO.successful(Seq.empty)
      } yield new Boundary(owner, member, organization, client, p1, p2, units)
    }
  }

  private def record[T](id: String)(result: => MatchResult[T]): MatchResult[T] = {
    val evaluated = result
    if (evaluated.isSuccess) passedIds += id
    evaluated
  }

  private def activeWorkUnit(): WorkUnit =
    allGenerators[WorkUnit].normal.generate.copy(
      date = WorkUnitDate(LocalDate.now().minusDays(1)),
      duration = WorkUnitDuration(java.time.Duration.ofMinutes(30)),
      entryType = WorkUnitEntryType.Worklog,
      state = WorkUnitState.Active,
      archivedAt = None
    )

  private def newHuman(): User =
    allGenerators[User].normal.generate.copy(accountType = UserAccountType.Human, archivedAt = None, isSuperAdmin = UserIsSuperAdmin(false))

  private def randomName(): String = s"OAuth E2E ${UUID.randomUUID()}"

  private def auth(user: User): AuthContext =
    AuthContextProvider.user.copy(id = user.id, isSuperAdmin = user.isSuperAdmin, identities = Nil, roles = Nil)

  private def createOAuth(
    fixture: Boundary,
    scopes: Set[OAuthScope],
    access: OAuthProjectAccess,
    expiresAt: Option[OAuthClientExpiresAt] = None
  ): OAuthClientSecretView =
    managementService
      .create(auth(fixture.owner), fixture.organization.slug, OAuthClientCreateRequest(randomName(), scopes, access, expiresAt))
      .unsafeRunSync() match {
      case Success(value) => value
      case other          => throw new AssertionError(s"OAuth E2E client creation failed: $other")
    }

  private def expireAtDatabaseBoundary(clientId: OAuthClientPublicId): OAuthClientExpiresAt = {
    val snapshot = run(oauthClientRepository.findPublic(clientId)).get
    val epochSeconds = run(sql"""UPDATE oauth_client
             SET expires_at = clock_timestamp()
             WHERE id = ${snapshot.client.id.toString}::uuid
             RETURNING extract(epoch from expires_at)""".as[BigDecimal].head)
    OAuthClientExpiresAt(databaseInstant(epochSeconds))
  }

  private def futureDatabaseBoundary(): Instant =
    databaseInstant(run(sql"select extract(epoch from clock_timestamp() + interval '2 minutes')".as[BigDecimal].head))

  private def databaseInstant(epochSeconds: BigDecimal): Instant = {
    val seconds = epochSeconds.longValue
    val nanos   = ((epochSeconds - BigDecimal(seconds)) * BigDecimal(1000000000L)).longValue
    Instant.ofEpochSecond(seconds, nanos)
  }

  private val requiredSafeClientMetadataFields = Set(
    "clientId",
    "name",
    "status",
    "scopes",
    "projectAccess",
    "version",
    "policyVersion",
    "secretVersion",
    "serviceAccount",
    "createdAt",
    "createdBy",
    "modifiedAt",
    "modifiedBy",
    "secretRotatedAt",
    "secretRotatedBy",
    "expiresAt",
    "revokedAt",
    "revokedBy",
    "lastUsedAt"
  )

  private def assertCompleteSafeClientMetadata(view: OAuthClientView) =
    (view.asJson.asObject.map(_.keys.toSet) shouldEqual Some(requiredSafeClientMetadataFields)) and
      (jsonFieldNames(view.serviceAccount.asJson) shouldEqual Set("userId", "displayName")) and
      (jsonFieldNames(view.createdBy.asJson) shouldEqual Set("userId", "displayName")) and
      (jsonFieldNames(view.modifiedBy.asJson) shouldEqual Set("userId", "displayName")) and
      (jsonFieldNames(view.secretRotatedBy.asJson) shouldEqual Set("userId", "displayName")) and
      (view.revokedBy.forall(actor => jsonFieldNames(actor.asJson) == Set("userId", "displayName")) should beTrue)

  private def jsonFieldNames(json: Json): Set[String] =
    json.arrayOrObject(Set.empty, _.flatMap(jsonFieldNames).toSet, fields => fields.keys.toSet ++ fields.values.flatMap(jsonFieldNames))

  private def installFastEquivalentHashForRateLimitProbe(fixture: Boundary, created: OAuthClientSecretView): Unit = {
    val snapshot = run(oauthClientRepository.findPublic(created.client.clientId)).get
    val now      = run(authoritativeTime.now)
    val hash     = BCrypt.hashpw(created.clientSecret, BCrypt.gensalt(4))
    run(
      oauthClientRepository.rotateSecretAtVersion(snapshot.client.id, snapshot.client.version.asInstanceOf[Long], hash, now, fixture.owner.id)
    ) match {
      case OAuthClientMutationResult.Applied(_) => ()
      case other                                => throw new AssertionError(s"OAuth E2E rate-limit setup failed: $other")
    }
  }

  private def credentials(created: OAuthClientSecretView): Option[OAuthClientCredentials] =
    Some(OAuthClientCredentials(created.client.clientId, OAuthClientSecret(created.clientSecret)))

  private def issue(created: OAuthClientSecretView, requestedScope: Option[String] = None): String =
    issueResult(credentials(created), requestedScope, nextSource()) match {
      case Issued(response) => response.access_token
      case other            => throw new AssertionError(s"OAuth E2E issuance failed: $other")
    }

  private def issueResult(
    credentials: Option[OAuthClientCredentials],
    requestedScope: Option[String],
    source: InetAddress
  ): OAuthTokenService.OAuthTokenResult =
    tokenService.issue(credentials, requestedScope, source, None).unsafeRunSync()

  private def verifiedClaims(token: String): OAuthTokenClaims =
    jwtService.verifyClaims(token).fold(error => throw new AssertionError(s"OAuth E2E token verification failed: $error"), identity)

  private def authenticate(token: String): OAuthAuthContext =
    authenticateEither(token).fold(_ => throw new AssertionError("OAuth E2E authentication failed"), identity)

  private def authenticateEither(token: String) =
    Await.result(authenticateEitherWith(authenticator, token), 10.seconds)

  private def authenticateEitherWith(authenticatorInstance: OAuthAuthenticator, token: String) =
    authenticatorInstance(Some(org.apache.pekko.http.scaladsl.model.headers.OAuth2BearerToken(token)))

  private def nextSource(): InetAddress = {
    val value = UUID.randomUUID()
    val bytes = ByteBuffer.allocate(16).putLong(value.getMostSignificantBits).putLong(value.getLeastSignificantBits).array()
    bytes(0) = 0xfd.toByte
    InetAddress.getByAddress(bytes)
  }

  private def run[A](action: DBIO[A]): A = Await.result(db.run(action), Duration.Inf)

  private def tokenServiceFor(
    tokenTransactor: PostgresIOTransactor,
    clientRepo: OAuthClientRepository,
    auditRepo: OAuthAuditRepository,
    rateRepo: OAuthRateLimitRepository,
    jwt: OAuthJwtService,
    time: OAuthAuthoritativeTime = authoritativeTime
  ): OAuthTokenService =
    new OAuthTokenService(tokenTransactor, time, clientRepo, auditRepo, rateRepo, secretService, jwt, issuer, audience)

  private def managementServiceFor(
    managementTransactor: PostgresIOTransactor,
    clientRepo: OAuthClientRepository,
    auditRepo: OAuthAuditRepository,
    time: OAuthAuthoritativeTime = authoritativeTime
  ): OAuthClientManagementService =
    new OAuthClientManagementService(
      managementTransactor,
      time,
      grantabilityService,
      serviceAccountRepository,
      clientRepo,
      auditRepo,
      userRepository,
      secretService
    )

  private def fixedTime(value: Instant): OAuthAuthoritativeTime = new OAuthAuthoritativeTime {
    override def now: DBIO[Instant] = DBIO.successful(value)
  }

  private lazy val projectAdapter =
    new OAuthProjectReadAdapter(transactor, organizationRepository, projectRepository, projectUserRepository, clientRepository, userRepository)
  private lazy val organizationAdapter = new OAuthOrganizationReadAdapter(transactor, organizationRepository)
  private lazy val clientAdapter       = new OAuthClientReadAdapter(transactor, organizationRepository, clientRepository, projectRepository)
  private lazy val userAdapter = new OAuthUserReadAdapter(
    transactor,
    organizationRepository,
    organizationUserRepository,
    projectRepository,
    projectUserRepository,
    clientRepository,
    userRepository,
    userIdentityRepository
  )
  private lazy val dtoService = new WorkUnitDtoService(clientRepository, userRepository, projectRepository)
  private lazy val workUnitReadAdapter =
    new OAuthWorkUnitReadAdapter(transactor, organizationRepository, workUnitRepository, userRepository, dtoService)
  private lazy val createService = WorkUnitCreateService(
    transactor,
    organizationRepository,
    organizationUserRepository,
    workUnitRepository,
    projectRepository,
    projectUserRepository,
    dtoService,
    userRepository,
    WorkUnitBusinessClock(Clock.systemUTC(), ZoneId.of("UTC"))
  )
  private lazy val pivotService =
    new WorkUnitPivotTableService(transactor, organizationRepository, organizationUserRepository, workUnitRepository)
  private lazy val pivotQueryService = new WorkUnitPivotQueryService(
    WorkUnitPivotQueryService.Config(10000L),
    transactor,
    organizationRepository,
    organizationUserRepository,
    new WorkUnitPivotQueryRepositoryImpl()(runtime.compute),
    new WorkUnitPivotQueryRequestValidator,
    new WorkUnitPivotQueryAssembler
  )
  private lazy val reportAdapter =
    new OAuthReportAdapter(transactor, organizationRepository, projectRepository, pivotService, pivotQueryService)
  private lazy val pivotExportService = new ExportWorkUnitPivotTableService(
    new WorkUnitPivotExportRequestValidator(new WorkUnitPivotQueryRequestValidator, WorkUnitPivotExportRequestValidator.Config(10000L)),
    pivotQueryService,
    new PivotExportArtifactStore(PivotExportArtifactStore.Config(pivotExportArtifactDirectory))
  )
  private lazy val exportAdapter = new OAuthExportAdapter(
    transactor,
    organizationRepository,
    projectRepository,
    clientRepository,
    userRepository,
    workUnitRepository,
    pivotQueryService,
    pivotExportService
  )

  private def commitSha: String =
    sys.env.getOrElse("CI_COMMIT_SHA", Process(Seq("git", "rev-parse", "HEAD")).!!.trim)

  private def postgresVersion: String = {
    val versionNumber = run(sql"select current_setting('server_version_num')".as[String].head)
    if (versionNumber != "130004") throw new AssertionError(s"OAuth E2E requires PostgreSQL 13.4, received server_version_num=$versionNumber")
    "13.4"
  }

  private def independentDatabase(name: String): Database =
    Database.forURL(
      url = sys.env.getOrElse("TEST_DB_URL", "epoca"),
      user = sys.env.getOrElse("TEST_DB_USER", "postgres"),
      password = sys.env.getOrElse("TEST_DB_PASSWORD", "postgres"),
      driver = "org.postgresql.Driver",
      executor = AsyncExecutor(s"$name-${UUID.randomUUID()}", minThreads = 1, maxThreads = 1, queueSize = 20, maxConnections = 1),
      prop = new Properties()
    )

  private final class PauseAfterLockRepository(acquired: Promise[Unit], release: Promise[Unit]) extends OAuthClientRepositoryImpl(authoritativeTime) {
    override def lock(clientId: OAuthClientId): DBIO[Option[OAuthClientRepository.OAuthClientSnapshot]] =
      super.lock(clientId).flatMap { snapshot =>
        acquired.trySuccess(())
        DBIO.from(release.future).map(_ => snapshot)
      }
  }

  private final class SignallingRotateRepository(started: Promise[Unit]) extends OAuthClientRepositoryImpl(authoritativeTime) {
    override def lock(id: OAuthClientId): DBIO[Option[OAuthClientRepository.OAuthClientSnapshot]] = {
      started.trySuccess(())
      super.lock(id)
    }
  }

  private final class PauseAfterSnapshotRepository(acquired: Promise[Unit], release: Promise[Unit])
      extends OAuthClientRepositoryImpl(authoritativeTime) {
    override def findPublic(clientId: OAuthClientPublicId): DBIO[Option[OAuthClientRepository.OAuthClientSnapshot]] =
      super.findPublic(clientId).flatMap { snapshot =>
        acquired.trySuccess(())
        DBIO.from(release.future).map(_ => snapshot)
      }
  }

  private final class CountingFixedTime(value: Instant) extends OAuthAuthoritativeTime {
    var calls: Int = 0

    override def now: DBIO[Instant] = {
      calls += 1
      DBIO.successful(value)
    }
  }

  private final class FixedAuthoritativeNowRepository private (val time: CountingFixedTime) extends OAuthClientRepositoryImpl(time) {
    def this(now: Instant) = this(new CountingFixedTime(now))
    def authoritativeNowCalls: Int = time.calls
  }

  private final class FailingAuditRepository(delegate: OAuthAuditRepository) extends OAuthAuditRepository {
    override def insert(event: NewOAuthAuditEvent): DBIO[OAuthAuditEventRow] = DBIO.failed(new RuntimeException("forced audit failure"))
    override def insertUnknownClientFailure(
      id: OAuthAuditEventId,
      occurredAt: OAuthAuditOccurredAt,
      correlationId: Option[OAuthAuditCorrelationId],
      sourceIp: Option[InetAddress],
      failureCategory: OAuthAuditFailureCategory
    ): DBIO[OAuthAuditEventRow] = delegate.insertUnknownClientFailure(id, occurredAt, correlationId, sourceIp, failureCategory)
    override def find(id: OAuthAuditEventId): DBIO[Option[OAuthAuditEventRow]]        = delegate.find(id)
    override def list(filters: OAuthAuditEventFilters): DBIO[Seq[OAuthAuditEventRow]] = delegate.list(filters)
    override def count(filters: OAuthAuditEventFilters): DBIO[Int]                    = delegate.count(filters)
    override def deleteExpiredAuthentication(cutoff: Instant, batchSize: Int): DBIO[Int] =
      delegate.deleteExpiredAuthentication(cutoff, batchSize)
  }
}
