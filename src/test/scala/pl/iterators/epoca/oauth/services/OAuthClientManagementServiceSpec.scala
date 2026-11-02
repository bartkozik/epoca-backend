package pl.iterators.epoca.oauth.services

import cats.effect.unsafe.IORuntime
import io.circe.Json
import io.circe.syntax._
import org.mindrot.jbcrypt.BCrypt
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.client.domain.Client
import pl.iterators.epoca.client.repositories.ClientRepositoryImpl
import pl.iterators.epoca.oauth.domain.OAuthDomain._
import pl.iterators.epoca.oauth.repositories.OAuthAuditRepository.{NewOAuthAuditEvent, OAuthAuditEventFilters}
import pl.iterators.epoca.oauth.repositories.tables.OAuthAuditEventRow
import pl.iterators.epoca.oauth.repositories.{OAuthAuditRepository, OAuthAuditRepositoryImpl, OAuthClientRepository, OAuthClientRepositoryImpl}
import pl.iterators.epoca.oauth.services.OAuthClientManagementService.OAuthClientManagementResult._
import pl.iterators.epoca.oauth.services.OAuthClientManagementService._
import pl.iterators.epoca.oauth.time.{OAuthAuthoritativeTime, PostgresOAuthAuthoritativeTime}
import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationUserId, OrganizationUserRole}
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.organization.repositories.{OrganizationRepositoryImpl, OrganizationUserRepositoryImpl}
import pl.iterators.epoca.project.domain.Project
import pl.iterators.epoca.project.domain.ProjectDomain.ProjectArchivedAt
import pl.iterators.epoca.project.repositories.ProjectRepository.ProjectFilters
import pl.iterators.epoca.project.repositories.ProjectRepositoryImpl
import pl.iterators.epoca.testsupport.generators.Generator
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.domain.UserDomain.{UserAccountType, UserArchivedAt, UserCreatedAt, UserId, UserIsSuperAdmin, UserModifiedAt}
import pl.iterators.epoca.user.repositories.UserRepository.UserFilters
import pl.iterators.epoca.user.repositories.{ServiceAccountRepositoryImpl, UserRepository, UserRepositoryImpl}
import pl.iterators.epoca.utils.db.BaseRepository.driver.api._
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.json.JsonProtocol._
import pl.iterators.epoca.utils.transactor.{IsolationLevel, RecordingTransactor, TransactorCall}
import pl.iterators.epoca.utils.{AuthContextProvider, PgRepositorySpec}
import slick.jdbc.SetParameter

import java.net.InetAddress
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Await
import scala.concurrent.duration.Duration

class OAuthClientManagementServiceSpec extends PgRepositorySpec {
  private implicit val ioRuntime: IORuntime = IORuntime.global
  private implicit val setInstant: SetParameter[Instant] =
    SetParameter((value, parameters) => parameters.setTimestamp(Timestamp.from(value)))

  private val userRepository             = new UserRepositoryImpl()
  private val serviceAccountRepository   = new ServiceAccountRepositoryImpl()
  private val organizationRepository     = new OrganizationRepositoryImpl()
  private val organizationUserRepository = new OrganizationUserRepositoryImpl()
  private val authoritativeTime          = new PostgresOAuthAuthoritativeTime()
  private val oauthClientRepository      = new OAuthClientRepositoryImpl(authoritativeTime)
  private val oauthAuditRepository       = new OAuthAuditRepositoryImpl()
  private val clientRepository           = new ClientRepositoryImpl()
  private val projectRepository          = new ProjectRepositoryImpl()
  private val transactor                 = new PostgresIOTransactor(db)
  private val secretService              = new OAuthSecretService(OAuthClientSecretHash(BCrypt.hashpw("oauth-dummy-secret", BCrypt.gensalt(12))))
  private val grantabilityService =
    new OAuthGrantabilityService(userRepository, organizationRepository, organizationUserRepository, projectRepository)
  private val sut = managementService(oauthAuditRepository)

  private val userGenerator: Generator[User]                 = allGenerators[User].normal
  private val organizationGenerator: Generator[Organization] = allGenerators[Organization].normal
  private val clientGenerator: Generator[Client]             = allGenerators[Client].normal
  private val projectGenerator: Generator[Project]           = allGenerators[Project].normal

  "grantability" should {
    "allow an Owner and a contextual superadmin but deny Manager and a regular member" in {
      val owner        = newHuman()
      val manager      = newHuman()
      val member       = newHuman()
      val superadmin   = newHuman().copy(isSuperAdmin = UserIsSuperAdmin(true))
      val organization = newOrganization(owner)
      insertContext(organization, owner, OrganizationUserRole.Owner)
      run(userRepository.insert(manager))
      insertMembership(organization, manager, Seq(OrganizationUserRole.Manager))
      run(userRepository.insert(member))
      insertMembership(organization, member, Seq(OrganizationUserRole.Employee))
      run(userRepository.insert(superadmin))

      val request          = OAuthClientCreateRequest("Warehouse", Set(OAuthScope.ProjectsRead), OAuthProjectAccess.AllProjects)
      val ownerResult      = sut.create(auth(owner), organization.slug, request).unsafeRunSync()
      val superadminResult = sut.create(auth(superadmin), organization.slug, request.copy(name = "Admin warehouse")).unsafeRunSync()
      val managerResult    = sut.create(auth(manager), organization.slug, request.copy(name = "Manager warehouse")).unsafeRunSync()
      val memberResult     = sut.create(auth(member), organization.slug, request.copy(name = "Member warehouse")).unsafeRunSync()

      (ownerResult should beAnInstanceOf[Success[_]]) and
        (superadminResult should beAnInstanceOf[Success[_]]) and
        (managerResult shouldEqual OperationNotPermitted) and
        (memberResult shouldEqual OperationNotPermitted)
    }

    "reject a delegated scope or project outside an explicitly restricted actor grant" in {
      val organization   = newOrganization(newHuman())
      val allowedProject = pl.iterators.epoca.project.domain.ProjectDomain.ProjectId(java.util.UUID.randomUUID())
      val foreignProject = pl.iterators.epoca.project.domain.ProjectDomain.ProjectId(java.util.UUID.randomUUID())
      val grant =
        OAuthGrantabilityService.OAuthGrantability(organization, Set(OAuthScope.ProjectsRead), Set(allowedProject), mayGrantAllProjects = false)

      (grantabilityService.validateDelegation(
        grant,
        Set(OAuthScope.ProjectsRead, OAuthScope.ReportsRead),
        OAuthProjectAccess.AllProjects
      ) shouldEqual Left(OAuthGrantabilityService.OAuthGrantabilityError.OperationNotPermitted)) and
        (grantabilityService.validateDelegation(
          grant,
          Set(OAuthScope.ProjectsRead),
          OAuthProjectAccess.SelectedProjects(Set(foreignProject))
        ) shouldEqual Left(OAuthGrantabilityService.OAuthGrantabilityError.ProjectNotFound))
    }
  }

  "management lifecycle" should {
    "create only absent or strictly future expiration and audit the explicit nullable value" in {
      val owner        = newHuman()
      val organization = newOrganization(owner)
      insertContext(organization, owner, OrganizationUserRole.Owner)
      val now        = Instant.parse("2026-08-13T10:15:30.123456Z")
      val repository = new RecordingNowOAuthClientRepository(now)
      val service    = managementService(oauthAuditRepository, repository, time = repository.time)

      val noExpiry = success(
        service
          .create(auth(owner), organization.slug, OAuthClientCreateRequest("No expiry", Set(OAuthScope.ProjectsRead), OAuthProjectAccess.AllProjects))
          .unsafeRunSync()
      ).asInstanceOf[OAuthClientSecretView]
      val futureExpiry = OAuthClientExpiresAt(now.plusSeconds(3600L))
      val expiring = success(
        service
          .create(
            auth(owner),
            organization.slug,
            OAuthClientCreateRequest("Future expiry", Set(OAuthScope.ProjectsRead), OAuthProjectAccess.AllProjects, Some(futureExpiry))
          )
          .unsafeRunSync()
      ).asInstanceOf[OAuthClientSecretView]
      val equal = service
        .create(
          auth(owner),
          organization.slug,
          OAuthClientCreateRequest("Equal expiry", Set(OAuthScope.ProjectsRead), OAuthProjectAccess.AllProjects, Some(OAuthClientExpiresAt(now)))
        )
        .unsafeRunSync()
      val past = service
        .create(
          auth(owner),
          organization.slug,
          OAuthClientCreateRequest(
            "Past expiry",
            Set(OAuthScope.ProjectsRead),
            OAuthProjectAccess.AllProjects,
            Some(OAuthClientExpiresAt(now.minusNanos(1L)))
          )
        )
        .unsafeRunSync()
      val noExpirySnapshot = run(repository.findPublic(noExpiry.client.clientId)).get
      val expiringSnapshot = run(repository.findPublic(expiring.client.clientId)).get
      val noExpiryAudit    = run(oauthAuditRepository.list(OAuthAuditEventFilters(noExpirySnapshot.client.id))).head
      val expiringAudit    = run(oauthAuditRepository.list(OAuthAuditEventFilters(expiringSnapshot.client.id))).head

      (noExpiry.client.expiresAt should beNone) and
        (expiring.client.expiresAt shouldEqual Some(futureExpiry)) and
        (equal shouldEqual InvalidExpiration) and
        (past shouldEqual InvalidExpiration) and
        (serviceAccountCount("Equal expiry") shouldEqual 0) and
        (serviceAccountCount("Past expiry") shouldEqual 0) and
        (noExpiryAudit.metadata.hcursor.downField("expiresAt").focus shouldEqual Some(Json.Null)) and
        (expiringAudit.metadata.hcursor.get[String]("expiresAt") shouldEqual Right(futureExpiry.toString))
    }

    "canonicalize expiration before validation, comparison, persistence, response, and audit" in {
      val owner        = newHuman()
      val organization = newOrganization(owner)
      insertContext(organization, owner, OrganizationUserRole.Owner)
      val now                 = Instant.parse("2026-08-13T10:15:30.123456Z")
      val repository          = new RecordingNowOAuthClientRepository(now)
      val service             = managementService(oauthAuditRepository, repository, time = repository.time)
      val rawExpiration       = OAuthClientExpiresAt(Instant.parse("2027-01-02T03:04:05.123456789Z"))
      val canonicalExpiration = OAuthClientExpiresAt(Instant.parse("2027-01-02T03:04:05.123456Z"))
      val created = success(
        service
          .create(
            auth(owner),
            organization.slug,
            OAuthClientCreateRequest("Canonical expiry", Set(OAuthScope.ProjectsRead), OAuthProjectAccess.AllProjects, Some(rawExpiration))
          )
          .unsafeRunSync()
      ).asInstanceOf[OAuthClientSecretView]
      val stored          = run(repository.findPublic(created.client.clientId)).get
      val createAudit     = run(oauthAuditRepository.list(OAuthAuditEventFilters(stored.client.id))).head
      val auditCount      = run(oauthAuditRepository.count(OAuthAuditEventFilters(stored.client.id)))
      val equivalentInput = OAuthClientExpiresAt(Instant.parse("2027-01-02T03:04:05.123456999Z"))
      val equivalent = success(
        service
          .update(
            auth(owner),
            organization.slug,
            created.client.clientId,
            OAuthClientUpdateRequest(1L, None, None, None, OAuthExpirationPatch.Set(equivalentInput))
          )
          .unsafeRunSync()
      ).asInstanceOf[OAuthClientView]
      val afterEquivalent            = run(repository.findPublic(created.client.clientId)).get
      val rawUpdatedExpiration       = OAuthClientExpiresAt(Instant.parse("2028-02-03T04:05:06.987654321Z"))
      val canonicalUpdatedExpiration = OAuthClientExpiresAt(Instant.parse("2028-02-03T04:05:06.987654Z"))
      val updated = success(
        service
          .update(
            auth(owner),
            organization.slug,
            created.client.clientId,
            OAuthClientUpdateRequest(1L, None, None, None, OAuthExpirationPatch.Set(rawUpdatedExpiration))
          )
          .unsafeRunSync()
      ).asInstanceOf[OAuthClientView]
      val storedUpdated = run(repository.findPublic(created.client.clientId)).get
      val updateAudit = run(oauthAuditRepository.list(OAuthAuditEventFilters(stored.client.id)))
        .find(_.eventType == OAuthAuditEventType.Update)
        .get
      val nearNow = service
        .create(
          auth(owner),
          organization.slug,
          OAuthClientCreateRequest(
            "Sub-microsecond expiry",
            Set(OAuthScope.ProjectsRead),
            OAuthProjectAccess.AllProjects,
            Some(OAuthClientExpiresAt(now.plusNanos(1L)))
          )
        )
        .unsafeRunSync()
      val outOfRange = service
        .create(
          auth(owner),
          organization.slug,
          OAuthClientCreateRequest(
            "Out-of-range expiry",
            Set(OAuthScope.ProjectsRead),
            OAuthProjectAccess.AllProjects,
            Some(OAuthClientExpiresAt(Instant.parse("+10000-01-01T00:00:00Z")))
          )
        )
        .unsafeRunSync()

      (created.client.expiresAt shouldEqual Some(canonicalExpiration)) and
        (stored.client.expiresAt shouldEqual Some(canonicalExpiration)) and
        (createAudit.metadata.hcursor.get[String]("expiresAt") shouldEqual Right(canonicalExpiration.toString)) and
        (equivalent.expiresAt shouldEqual Some(canonicalExpiration)) and
        (equivalent.version shouldEqual 1L) and
        (equivalent.policyVersion shouldEqual 1L) and
        (afterEquivalent shouldEqual stored) and
        (updated.expiresAt shouldEqual Some(canonicalUpdatedExpiration)) and
        (storedUpdated.client.expiresAt shouldEqual Some(canonicalUpdatedExpiration)) and
        (updateAudit.metadata.hcursor.get[String]("beforeExpiresAt") shouldEqual Right(canonicalExpiration.toString)) and
        (updateAudit.metadata.hcursor.get[String]("afterExpiresAt") shouldEqual Right(canonicalUpdatedExpiration.toString)) and
        (run(oauthAuditRepository.count(OAuthAuditEventFilters(stored.client.id))) shouldEqual auditCount + 1L) and
        (nearNow shouldEqual InvalidExpiration) and
        (outOfRange shouldEqual InvalidExpiration) and
        (serviceAccountCount("Sub-microsecond expiry") shouldEqual 0) and
        (serviceAccountCount("Out-of-range expiry") shouldEqual 0)
    }

    "apply tri-state expiration updates with policy versioning, no-ops, and stale protection" in {
      val owner        = newHuman()
      val organization = newOrganization(owner)
      insertContext(organization, owner, OrganizationUserRole.Owner)
      val now           = Instant.parse("2026-08-13T10:15:30.123456Z")
      val repository    = new RecordingNowOAuthClientRepository(now)
      val service       = managementService(oauthAuditRepository, repository, time = repository.time)
      val initialExpiry = OAuthClientExpiresAt(now.plusSeconds(3600L))
      val created = success(
        service
          .create(
            auth(owner),
            organization.slug,
            OAuthClientCreateRequest("Expiration updates", Set(OAuthScope.ProjectsRead), OAuthProjectAccess.AllProjects, Some(initialExpiry))
          )
          .unsafeRunSync()
      ).asInstanceOf[OAuthClientSecretView]
      val snapshot         = run(repository.findPublic(created.client.clientId)).get
      val createAuditCount = run(oauthAuditRepository.count(OAuthAuditEventFilters(snapshot.client.id)))

      val preserved = success(
        service
          .update(
            auth(owner),
            organization.slug,
            created.client.clientId,
            OAuthClientUpdateRequest(1L, Some("Expiration renamed"), None, None, OAuthExpirationPatch.Unchanged)
          )
          .unsafeRunSync()
      ).asInstanceOf[OAuthClientView]
      val equalNoOp = success(
        service
          .update(
            auth(owner),
            organization.slug,
            created.client.clientId,
            OAuthClientUpdateRequest(2L, None, None, None, OAuthExpirationPatch.Set(initialExpiry))
          )
          .unsafeRunSync()
      ).asInstanceOf[OAuthClientView]
      val afterEqualAuditCount = run(oauthAuditRepository.count(OAuthAuditEventFilters(snapshot.client.id)))
      val cleared = success(
        service
          .update(auth(owner), organization.slug, created.client.clientId, OAuthClientUpdateRequest(2L, None, None, None, OAuthExpirationPatch.Clear))
          .unsafeRunSync()
      ).asInstanceOf[OAuthClientView]
      val clearNoOp = success(
        service
          .update(auth(owner), organization.slug, created.client.clientId, OAuthClientUpdateRequest(3L, None, None, None, OAuthExpirationPatch.Clear))
          .unsafeRunSync()
      ).asInstanceOf[OAuthClientView]
      val futureExpiry = OAuthClientExpiresAt(now.plusSeconds(7200L))
      val combined = success(
        service
          .update(
            auth(owner),
            organization.slug,
            created.client.clientId,
            OAuthClientUpdateRequest(
              3L,
              Some("Expiration combined"),
              Some(Set(OAuthScope.ProjectsRead, OAuthScope.ReportsRead)),
              Some(OAuthProjectAccess.AllProjects),
              OAuthExpirationPatch.Set(futureExpiry)
            )
          )
          .unsafeRunSync()
      ).asInstanceOf[OAuthClientView]
      val beforeStale       = run(repository.findPublic(created.client.clientId)).get
      val beforeStaleAudits = run(oauthAuditRepository.count(OAuthAuditEventFilters(snapshot.client.id)))
      val stale = service
        .update(auth(owner), organization.slug, created.client.clientId, OAuthClientUpdateRequest(3L, None, None, None, OAuthExpirationPatch.Clear))
        .unsafeRunSync()
      val outOfRangeExpiry = OAuthClientExpiresAt(Instant.parse("+10000-01-01T00:00:00Z"))
      val staleOutOfRange = service
        .update(
          auth(owner),
          organization.slug,
          created.client.clientId,
          OAuthClientUpdateRequest(3L, None, None, None, OAuthExpirationPatch.Set(outOfRangeExpiry))
        )
        .unsafeRunSync()
      val currentOutOfRange = service
        .update(
          auth(owner),
          organization.slug,
          created.client.clientId,
          OAuthClientUpdateRequest(4L, None, None, None, OAuthExpirationPatch.Set(outOfRangeExpiry))
        )
        .unsafeRunSync()
      val subMicrosecondFuture = service
        .update(
          auth(owner),
          organization.slug,
          created.client.clientId,
          OAuthClientUpdateRequest(4L, None, None, None, OAuthExpirationPatch.Set(OAuthClientExpiresAt(now.plusNanos(1L))))
        )
        .unsafeRunSync()
      val equalNow = service
        .update(
          auth(owner),
          organization.slug,
          created.client.clientId,
          OAuthClientUpdateRequest(4L, None, None, None, OAuthExpirationPatch.Set(OAuthClientExpiresAt(now)))
        )
        .unsafeRunSync()
      val past = service
        .update(
          auth(owner),
          organization.slug,
          created.client.clientId,
          OAuthClientUpdateRequest(4L, None, None, None, OAuthExpirationPatch.Set(OAuthClientExpiresAt(now.minusNanos(1L))))
        )
        .unsafeRunSync()
      val afterStale       = run(repository.findPublic(created.client.clientId)).get
      val afterStaleAudits = run(oauthAuditRepository.count(OAuthAuditEventFilters(snapshot.client.id)))
      val updateAudits = run(oauthAuditRepository.list(OAuthAuditEventFilters(snapshot.client.id))).filter(_.eventType == OAuthAuditEventType.Update)

      (preserved.expiresAt shouldEqual Some(initialExpiry)) and
        (preserved.version shouldEqual 2L) and
        (preserved.policyVersion shouldEqual 1L) and
        (equalNoOp.version shouldEqual 2L) and
        (afterEqualAuditCount shouldEqual createAuditCount + 1) and
        (cleared.expiresAt should beNone) and
        (cleared.version shouldEqual 3L) and
        (cleared.policyVersion shouldEqual 2L) and
        (cleared.secretVersion shouldEqual 1L) and
        (clearNoOp.version shouldEqual 3L) and
        (combined.expiresAt shouldEqual Some(futureExpiry)) and
        (combined.version shouldEqual 4L) and
        (combined.policyVersion shouldEqual 3L) and
        (combined.secretVersion shouldEqual 1L) and
        (stale shouldEqual StaleVersion(4L)) and
        (staleOutOfRange shouldEqual StaleVersion(4L)) and
        (currentOutOfRange shouldEqual InvalidExpiration) and
        (subMicrosecondFuture shouldEqual InvalidExpiration) and
        (equalNow shouldEqual InvalidExpiration) and
        (past shouldEqual InvalidExpiration) and
        (afterStale shouldEqual beforeStale) and
        (afterStaleAudits shouldEqual beforeStaleAudits) and
        (updateAudits.size shouldEqual 3) and
        (updateAudits.exists(event =>
          event.metadata.hcursor.downField("beforeExpiresAt").focus.contains(Json.fromString(initialExpiry.toString)) &&
            event.metadata.hcursor.downField("afterExpiresAt").focus.contains(Json.Null)
        ) should beTrue) and
        (updateAudits.forall(event =>
          event.metadata.hcursor.downField("beforeExpiresAt").succeeded && event.metadata.hcursor.downField("afterExpiresAt").succeeded
        ) should beTrue)
    }

    "reject a patch with no mutable fields while accepting a recognized expiration no-op" in {
      val owner        = newHuman()
      val organization = newOrganization(owner)
      insertContext(organization, owner, OrganizationUserRole.Owner)
      val created = createClient(owner, organization, "Patch shape")

      val empty = sut
        .update(
          auth(owner),
          organization.slug,
          created.client.clientId,
          OAuthClientUpdateRequest(1L, None, None, None, OAuthExpirationPatch.Unchanged)
        )
        .unsafeRunSync()
      val clearNoOp = success(
        sut
          .update(auth(owner), organization.slug, created.client.clientId, OAuthClientUpdateRequest(1L, None, None, None, OAuthExpirationPatch.Clear))
          .unsafeRunSync()
      ).asInstanceOf[OAuthClientView]

      (empty shouldEqual InvalidPolicy) and (clearNoOp.version shouldEqual 1L)
    }

    "reject an invalid create name before consulting authoritative time" in {
      val owner        = newHuman()
      val organization = newOrganization(owner)
      val timeCalls    = new AtomicInteger(0)
      val failingSut   = managementService(oauthAuditRepository, time = failingTime(timeCalls))
      insertContext(organization, owner, OrganizationUserRole.Owner)

      val result = failingSut
        .create(auth(owner), organization.slug, OAuthClientCreateRequest("   ", Set(OAuthScope.ProjectsRead), OAuthProjectAccess.AllProjects))
        .unsafeRunSync()

      (result shouldEqual InvalidPolicy) and (timeCalls.get() shouldEqual 0)
    }

    "reject an invalid create delegation before consulting authoritative time" in {
      val owner          = newHuman()
      val organization   = newOrganization(owner)
      val foreignProject = pl.iterators.epoca.project.domain.ProjectDomain.ProjectId(UUID.randomUUID())
      val timeCalls      = new AtomicInteger(0)
      val failingSut     = managementService(oauthAuditRepository, time = failingTime(timeCalls))
      insertContext(organization, owner, OrganizationUserRole.Owner)

      val result = failingSut
        .create(
          auth(owner),
          organization.slug,
          OAuthClientCreateRequest(
            "Invalid delegated project",
            Set(OAuthScope.ProjectsRead),
            OAuthProjectAccess.SelectedProjects(Set(foreignProject))
          )
        )
        .unsafeRunSync()

      (result shouldEqual ProjectNotFound) and (timeCalls.get() shouldEqual 0)
    }

    "compose an explicitly prepared create inside its caller transaction without changing the normal audit contract" in {
      val owner        = newHuman()
      val organization = newOrganization(owner)
      insertContext(organization, owner, OrganizationUserRole.Owner)
      val project     = insertProject(organization, owner)
      val credentials = secretService.generateCredentials()
      val prepared = OAuthClientPreparedCreate(
        credentials = credentials,
        secretHash = secretService.hash(credentials.clientSecret),
        clientId = OAuthClientId(UUID.randomUUID()),
        serviceAccountUserId = pl.iterators.epoca.user.domain.UserDomain.UserId(UUID.randomUUID()),
        membershipId = OrganizationUserId(UUID.randomUUID()),
        auditEventId = OAuthAuditEventId(UUID.randomUUID())
      )
      val request =
        OAuthClientCreateRequest("Prepared reporting warehouse", Set(OAuthScope.ProjectsRead), OAuthProjectAccess.SelectedProjects(Set(project.id)))

      val created = success(
        run(
          sut
            .createPreparedDBIO(auth(owner), organization.slug, request, prepared, Some(OAuthAuditCorrelationId("oauth-prepared-create")))
            .transactionally
        )
      ).asInstanceOf[OAuthClientSecretView]
      val snapshot   = run(oauthClientRepository.findPublic(created.client.clientId)).get
      val technical  = run(serviceAccountRepository.findById(snapshot.client.serviceAccountUserId)).get
      val membership = run(serviceAccountRepository.findMembership(snapshot.client.serviceAccountUserId, organization.id)).get
      val audit      = run(oauthAuditRepository.find(prepared.auditEventId)).get

      (created.clientSecret shouldEqual credentials.clientSecret.toString) and
        (created.client.clientId shouldEqual credentials.clientId) and
        (snapshot.client.id shouldEqual prepared.clientId) and
        (technical.id shouldEqual prepared.serviceAccountUserId) and
        (membership.id shouldEqual prepared.membershipId) and
        (audit.id shouldEqual prepared.auditEventId) and
        (audit.correlationId shouldEqual Some(OAuthAuditCorrelationId("oauth-prepared-create"))) and
        (audit.eventType shouldEqual OAuthAuditEventType.Create)
    }

    "create the technical identity, empty membership, client policy, and audit atomically" in {
      val owner        = newHuman()
      val organization = newOrganization(owner)
      insertContext(organization, owner, OrganizationUserRole.Owner)

      val created = success(
        sut
          .create(
            auth(owner),
            organization.slug,
            OAuthClientCreateRequest("Reporting warehouse", Set(OAuthScope.ProjectsRead, OAuthScope.ReportsRead), OAuthProjectAccess.AllProjects)
          )
          .unsafeRunSync()
      )
      val secretView = created.asInstanceOf[OAuthClientSecretView]
      val snapshot   = run(oauthClientRepository.findPublic(secretView.client.clientId)).get
      val technical  = run(serviceAccountRepository.findById(snapshot.client.serviceAccountUserId)).get
      val membership = run(serviceAccountRepository.findMembership(snapshot.client.serviceAccountUserId, organization.id)).get
      val audits     = run(oauthAuditRepository.list(OAuthAuditEventFilters(snapshot.client.id)))

      (secretView.clientSecret.startsWith(OAuthSecretService.ClientSecretPrefix) should beTrue) and
        (secretView.client.version shouldEqual 1L) and
        (secretView.client.policyVersion shouldEqual 1L) and
        (technical.accountType shouldEqual UserAccountType.ServiceAccount) and
        (technical.firstName.toString shouldEqual "Integration") and
        (technical.lastName.toString shouldEqual "Reporting warehouse") and
        (membership.roles should beEmpty) and
        (audits.map(_.eventType) shouldEqual Seq(OAuthAuditEventType.Create))
    }

    "use the injected authoritative time for identity, client, and audit timestamps" in {
      val fixedNow     = Instant.parse("2033-04-05T06:07:08Z")
      val timeCalls    = new AtomicInteger(0)
      val fixed        = countingTime(timeCalls, fixedNow)
      val fixedClient  = new OAuthClientRepositoryImpl(fixed)
      val fixedSut     = managementService(oauthAuditRepository, fixedClient, time = fixed)
      val owner        = newHuman()
      val organization = newOrganization(owner)
      insertContext(organization, owner, OrganizationUserRole.Owner)

      val created = success(
        fixedSut
          .create(
            auth(owner),
            organization.slug,
            OAuthClientCreateRequest("Fixed management time", Set(OAuthScope.ProjectsRead), OAuthProjectAccess.AllProjects),
            Some(OAuthAuditCorrelationId("oauth-management-time"))
          )
          .unsafeRunSync()
      ).asInstanceOf[OAuthClientSecretView]
      val snapshot  = run(fixedClient.findPublic(created.client.clientId)).get
      val technical = run(serviceAccountRepository.findById(snapshot.client.serviceAccountUserId)).get
      val audit     = run(oauthAuditRepository.list(OAuthAuditEventFilters(snapshot.client.id))).head

      (snapshot.client.createdAt shouldEqual OAuthClientCreatedAt(fixedNow)) and
        (snapshot.client.modifiedAt shouldEqual OAuthClientModifiedAt(fixedNow)) and
        (snapshot.client.secretRotatedAt shouldEqual OAuthClientSecretRotatedAt(fixedNow)) and
        (technical.createdAt shouldEqual UserCreatedAt(fixedNow)) and
        (technical.modifiedAt shouldEqual UserModifiedAt(fixedNow)) and
        (audit.occurredAt shouldEqual OAuthAuditOccurredAt(fixedNow)) and
        (timeCalls.get() shouldEqual 1)
    }

    "roll back every create row when the required audit insert fails" in {
      val owner        = newHuman()
      val organization = newOrganization(owner)
      insertContext(organization, owner, OrganizationUserRole.Owner)
      val failingSut = managementService(new FailingAuditRepository(oauthAuditRepository))

      val beforeTechnical = serviceAccountCount("Rollback sentinel")
      val result = failingSut
        .create(
          auth(owner),
          organization.slug,
          OAuthClientCreateRequest("Rollback sentinel", Set(OAuthScope.ProjectsRead), OAuthProjectAccess.AllProjects)
        )
        .unsafeRunSync()
      val clients = run(
        oauthClientRepository.list(
          pl.iterators.epoca.oauth.repositories.OAuthClientRepository.OAuthClientFilters(organization.id),
          Instant.parse("2026-08-13T00:00:00Z")
        )
      )

      (result shouldEqual PersistenceUnavailable) and
        (clients.map(_.client.name.toString) should not(contain("Rollback sentinel"))) and
        (serviceAccountCount("Rollback sentinel") shouldEqual beforeTechnical)
    }

    "roll back update, rotation, and revocation when their required audit insert fails" in {
      val owner        = newHuman()
      val organization = newOrganization(owner)
      insertContext(organization, owner, OrganizationUserRole.Owner)
      val updateClient = createClient(owner, organization, "Update audit rollback")
      val rotateClient = createClient(owner, organization, "Rotate audit rollback")
      val revokeClient = createClient(owner, organization, "Revoke audit rollback")
      val beforeUpdate = run(oauthClientRepository.findPublic(updateClient.client.clientId)).get
      val beforeRotate = run(oauthClientRepository.findPublic(rotateClient.client.clientId)).get
      val beforeRevoke = run(oauthClientRepository.findPublic(revokeClient.client.clientId)).get
      val failingSut   = managementService(new FailingAuditRepository(oauthAuditRepository))

      val updateResult = failingSut
        .update(auth(owner), organization.slug, updateClient.client.clientId, OAuthClientUpdateRequest(1L, Some("Must roll back"), None, None))
        .unsafeRunSync()
      val rotateResult = failingSut
        .rotateSecret(auth(owner), organization.slug, rotateClient.client.clientId, OAuthClientVersionRequest(1L))
        .unsafeRunSync()
      val revokeResult = failingSut
        .revoke(auth(owner), organization.slug, revokeClient.client.clientId, OAuthClientVersionRequest(1L))
        .unsafeRunSync()

      val afterUpdate           = run(oauthClientRepository.findPublic(updateClient.client.clientId)).get
      val afterRotate           = run(oauthClientRepository.findPublic(rotateClient.client.clientId)).get
      val afterRevoke           = run(oauthClientRepository.findPublic(revokeClient.client.clientId)).get
      val updateTechnical       = run(serviceAccountRepository.findById(beforeUpdate.client.serviceAccountUserId)).get
      val revokeTechnical       = run(serviceAccountRepository.findById(beforeRevoke.client.serviceAccountUserId)).get
      val revokeMembership      = run(serviceAccountRepository.findMembership(beforeRevoke.client.serviceAccountUserId, organization.id)).get
      val updateAuditEventCount = run(oauthAuditRepository.count(OAuthAuditEventFilters(beforeUpdate.client.id)))
      val rotateAuditEventCount = run(oauthAuditRepository.count(OAuthAuditEventFilters(beforeRotate.client.id)))
      val revokeAuditEventCount = run(oauthAuditRepository.count(OAuthAuditEventFilters(beforeRevoke.client.id)))

      (updateResult shouldEqual PersistenceUnavailable) and
        (rotateResult shouldEqual PersistenceUnavailable) and
        (revokeResult shouldEqual PersistenceUnavailable) and
        (afterUpdate shouldEqual beforeUpdate) and
        (updateTechnical.lastName.toString shouldEqual "Update audit rollback") and
        (afterRotate shouldEqual beforeRotate) and
        (afterRevoke shouldEqual beforeRevoke) and
        (revokeTechnical.archivedAt should beNone) and
        (revokeMembership.archivedAt should beNone) and
        (updateAuditEventCount shouldEqual 1) and
        (rotateAuditEventCount shouldEqual 1) and
        (revokeAuditEventCount shouldEqual 1)
    }

    "map invalid policy, duplicate name, and stale writes to stable results" in {
      val owner        = newHuman()
      val organization = newOrganization(owner)
      insertContext(organization, owner, OrganizationUserRole.Owner)
      val request = OAuthClientCreateRequest("Unique integration", Set(OAuthScope.ProjectsRead), OAuthProjectAccess.AllProjects)
      val created = success(sut.create(auth(owner), organization.slug, request).unsafeRunSync()).asInstanceOf[OAuthClientSecretView]

      val emptyScopes = sut.create(auth(owner), organization.slug, request.copy(name = "Empty", scopes = Set.empty)).unsafeRunSync()
      val emptyProjects = sut
        .create(auth(owner), organization.slug, request.copy(name = "Empty projects", projectAccess = OAuthProjectAccess.SelectedProjects(Set.empty)))
        .unsafeRunSync()
      val duplicate = sut.create(auth(owner), organization.slug, request).unsafeRunSync()
      val stale =
        sut.update(auth(owner), organization.slug, created.client.clientId, OAuthClientUpdateRequest(0L, Some("Changed"), None, None)).unsafeRunSync()

      (emptyScopes shouldEqual InvalidPolicy) and
        (emptyProjects shouldEqual InvalidPolicy) and
        (duplicate shouldEqual NameConflict) and
        (stale shouldEqual StaleVersion(1L))
    }

    "treat semantic updates as no-ops and synchronize a real rename in the same transaction" in {
      val owner        = newHuman()
      val organization = newOrganization(owner)
      insertContext(organization, owner, OrganizationUserRole.Owner)
      val created = success(
        sut
          .create(
            auth(owner),
            organization.slug,
            OAuthClientCreateRequest("Before rename", Set(OAuthScope.ProjectsRead), OAuthProjectAccess.AllProjects)
          )
          .unsafeRunSync()
      ).asInstanceOf[OAuthClientSecretView]
      val stored           = run(oauthClientRepository.findPublic(created.client.clientId)).get
      val beforeAuditCount = run(oauthAuditRepository.count(OAuthAuditEventFilters(stored.client.id)))

      val noOp = success(
        sut
          .update(auth(owner), organization.slug, created.client.clientId, OAuthClientUpdateRequest(1L, Some("Before rename"), None, None))
          .unsafeRunSync()
      ).asInstanceOf[OAuthClientView]
      val afterNoOpAuditCount = run(oauthAuditRepository.count(OAuthAuditEventFilters(stored.client.id)))
      val renamed = success(
        sut
          .update(auth(owner), organization.slug, created.client.clientId, OAuthClientUpdateRequest(1L, Some("After rename"), None, None))
          .unsafeRunSync()
      ).asInstanceOf[OAuthClientView]
      val technical = run(serviceAccountRepository.findById(stored.client.serviceAccountUserId)).get

      (noOp.version shouldEqual 1L) and
        (afterNoOpAuditCount shouldEqual beforeAuditCount) and
        (renamed.version shouldEqual 2L) and
        (renamed.policyVersion shouldEqual 1L) and
        (technical.lastName.toString shouldEqual "After rename")
    }

    "increment policyVersion only when an update changes delegated policy" in {
      val owner        = newHuman()
      val organization = newOrganization(owner)
      insertContext(organization, owner, OrganizationUserRole.Owner)
      val created = createClient(owner, organization, "Policy version")

      val updated = success(
        sut
          .update(
            auth(owner),
            organization.slug,
            created.client.clientId,
            OAuthClientUpdateRequest(1L, None, Some(Set(OAuthScope.ProjectsRead, OAuthScope.ReportsRead)), None)
          )
          .unsafeRunSync()
      ).asInstanceOf[OAuthClientView]

      (updated.version shouldEqual 2L) and
        (updated.policyVersion shouldEqual 2L) and
        (updated.secretVersion shouldEqual 1L) and
        (updated.scopes shouldEqual Set(OAuthScope.ProjectsRead, OAuthScope.ReportsRead))
    }

    "retain selected archived projects in the grantability boundary without accepting a foreign project" in {
      val owner        = newHuman()
      val organization = newOrganization(owner)
      insertContext(organization, owner, OrganizationUserRole.Owner)
      val project = insertProject(organization, owner)
      val created = success(
        sut
          .create(
            auth(owner),
            organization.slug,
            OAuthClientCreateRequest("Archived project policy", Set(OAuthScope.ProjectsRead), OAuthProjectAccess.SelectedProjects(Set(project.id)))
          )
          .unsafeRunSync()
      ).asInstanceOf[OAuthClientSecretView]
      run(projectRepository.archive(ProjectFilters(id = Some(project.id)), ProjectArchivedAt(Instant.now()), owner.id))

      val renamed = sut
        .update(
          auth(owner),
          organization.slug,
          created.client.clientId,
          OAuthClientUpdateRequest(1L, Some("Archived project policy renamed"), None, None)
        )
        .unsafeRunSync()
      val foreignProject = pl.iterators.epoca.project.domain.ProjectDomain.ProjectId(java.util.UUID.randomUUID())
      val foreignGrant = sut
        .update(
          auth(owner),
          organization.slug,
          created.client.clientId,
          OAuthClientUpdateRequest(2L, None, None, Some(OAuthProjectAccess.SelectedProjects(Set(foreignProject))))
        )
        .unsafeRunSync()

      (success(renamed).asInstanceOf[OAuthClientView].projectAccess shouldEqual OAuthProjectAccess.SelectedProjects(Set(project.id))) and
        (foreignGrant shouldEqual ProjectNotFound)
    }

    "rotate with independent version semantics and revoke terminally with immediate policy invalidation" in {
      val owner        = newHuman()
      val organization = newOrganization(owner)
      insertContext(organization, owner, OrganizationUserRole.Owner)
      val created = success(
        sut
          .create(auth(owner), organization.slug, OAuthClientCreateRequest("Lifecycle", Set(OAuthScope.ProjectsRead), OAuthProjectAccess.AllProjects))
          .unsafeRunSync()
      ).asInstanceOf[OAuthClientSecretView]
      val snapshot = run(oauthClientRepository.findPublic(created.client.clientId)).get

      val rotated = success(sut.rotateSecret(auth(owner), organization.slug, created.client.clientId, OAuthClientVersionRequest(1L)).unsafeRunSync())
        .asInstanceOf[OAuthClientSecretView]
      val revoked = success(sut.revoke(auth(owner), organization.slug, created.client.clientId, OAuthClientVersionRequest(2L)).unsafeRunSync())
        .asInstanceOf[OAuthClientView]
      val technical  = run(serviceAccountRepository.findById(snapshot.client.serviceAccountUserId)).get
      val membership = run(serviceAccountRepository.findMembership(snapshot.client.serviceAccountUserId, organization.id)).get
      val updateAfterRevoke =
        sut.update(auth(owner), organization.slug, created.client.clientId, OAuthClientUpdateRequest(3L, Some("Never"), None, None)).unsafeRunSync()
      val rotateAfterRevoke = sut.rotateSecret(auth(owner), organization.slug, created.client.clientId, OAuthClientVersionRequest(3L)).unsafeRunSync()

      (rotated.clientSecret != created.clientSecret should beTrue) and
        (rotated.client.version shouldEqual 2L) and
        (rotated.client.secretVersion shouldEqual 2L) and
        (rotated.client.policyVersion shouldEqual 1L) and
        (revoked.status shouldEqual OAuthClientEffectiveStatus.Revoked) and
        (revoked.version shouldEqual 3L) and
        (revoked.policyVersion shouldEqual 2L) and
        (technical.archivedAt should beSome) and
        (membership.archivedAt should beSome) and
        (updateAfterRevoke shouldEqual NotActive) and
        (rotateAfterRevoke shouldEqual NotActive)
    }

    "block secret rotation exactly at expiration using one authoritative database time" in {
      val owner        = newHuman()
      val organization = newOrganization(owner)
      insertContext(organization, owner, OrganizationUserRole.Owner)
      val createdAt  = Instant.parse("2026-08-13T10:15:30.123456Z")
      val expiresAt  = OAuthClientExpiresAt(createdAt.plusSeconds(60L))
      val repository = new RecordingNowOAuthClientRepository(createdAt)
      val service    = managementService(oauthAuditRepository, repository, time = repository.time)
      val created = success(
        service
          .create(
            auth(owner),
            organization.slug,
            OAuthClientCreateRequest("Expiry rotation guard", Set(OAuthScope.ProjectsRead), OAuthProjectAccess.AllProjects, Some(expiresAt))
          )
          .unsafeRunSync()
      ).asInstanceOf[OAuthClientSecretView]
      val snapshot         = run(repository.findPublic(created.client.clientId)).get
      val auditCountBefore = run(oauthAuditRepository.count(OAuthAuditEventFilters(snapshot.client.id)))

      repository.setNow(expiresAt.asInstanceOf[Instant])
      repository.reset()
      val result = service
        .rotateSecret(auth(owner), organization.slug, created.client.clientId, OAuthClientVersionRequest(created.client.version))
        .unsafeRunSync()

      (result shouldEqual NotActive) and
        (repository.authoritativeNowCalls shouldEqual 1) and
        (run(repository.findPublic(created.client.clientId)).get shouldEqual snapshot) and
        (run(oauthAuditRepository.count(OAuthAuditEventFilters(snapshot.client.id))) shouldEqual auditCountBefore)
    }

    "filter all effective lifecycle states with one captured database time for list and count" in {
      val owner        = newHuman()
      val organization = newOrganization(owner)
      insertContext(organization, owner, OrganizationUserRole.Owner)
      val createdAt  = Instant.parse("2026-08-13T10:15:30.123456Z")
      val expiresAt  = OAuthClientExpiresAt(createdAt.plusSeconds(60L))
      val repository = new RecordingNowOAuthClientRepository(createdAt)
      val service    = managementService(oauthAuditRepository, repository, time = repository.time)
      val active     = createClient(service, owner, organization, "Effective active")
      val expired = success(
        service
          .create(
            auth(owner),
            organization.slug,
            OAuthClientCreateRequest("Effective expired", Set(OAuthScope.ProjectsRead), OAuthProjectAccess.AllProjects, Some(expiresAt))
          )
          .unsafeRunSync()
      ).asInstanceOf[OAuthClientSecretView]
      val revokedCandidate = createClient(service, owner, organization, "Effective revoked")
      val revoked = success(
        service
          .revoke(auth(owner), organization.slug, revokedCandidate.client.clientId, OAuthClientVersionRequest(revokedCandidate.client.version))
          .unsafeRunSync()
      ).asInstanceOf[OAuthClientView]

      repository.setNow(expiresAt.asInstanceOf[Instant])
      val expectedByStatus = Seq(
        OAuthClientEffectiveStatus.Active  -> active.client.clientId,
        OAuthClientEffectiveStatus.Expired -> expired.client.clientId,
        OAuthClientEffectiveStatus.Revoked -> revoked.clientId
      )

      expectedByStatus
        .map { case (status, expectedClientId) =>
          repository.reset()
          val page = success(service.list(auth(owner), organization.slug, Some(status), None).unsafeRunSync())
            .asInstanceOf[pl.iterators.epoca.utils.db.PaginatedResult[OAuthClientView]]

          (page.count shouldEqual 1) and
            (page.results.map(_.clientId) shouldEqual Seq(expectedClientId)) and
            (page.results.map(_.status) shouldEqual Seq(status)) and
            (repository.authoritativeNowCalls shouldEqual 1) and
            (repository.listTimes shouldEqual Seq(expiresAt.asInstanceOf[Instant])) and
            (repository.countTimes shouldEqual Seq(expiresAt.asInstanceOf[Instant]))
        }
        .reduce(_ and _)
    }

    "keep list and count on the captured time when the database clock crosses expiration mid-request" in {
      val owner        = newHuman()
      val organization = newOrganization(owner)
      insertContext(organization, owner, OrganizationUserRole.Owner)
      val createdAt    = Instant.parse("2026-08-13T10:15:30.123456Z")
      val expiresAt    = OAuthClientExpiresAt(createdAt.plusSeconds(60L))
      val capturedNow  = expiresAt.asInstanceOf[Instant].minusNanos(1000L)
      val repository   = new RecordingNowOAuthClientRepository(createdAt)
      val service      = managementService(oauthAuditRepository, repository, time = repository.time)
      val alwaysActive = createClient(service, owner, organization, "Always active in page")
      val boundaryClient = success(
        service
          .create(
            auth(owner),
            organization.slug,
            OAuthClientCreateRequest("Boundary active in page", Set(OAuthScope.ProjectsRead), OAuthProjectAccess.AllProjects, Some(expiresAt))
          )
          .unsafeRunSync()
      ).asInstanceOf[OAuthClientSecretView]

      repository.setNow(capturedNow)
      repository.advanceNowAfterListTo(expiresAt.asInstanceOf[Instant])
      repository.reset()
      val page = success(service.list(auth(owner), organization.slug, Some(OAuthClientEffectiveStatus.Active), None).unsafeRunSync())
        .asInstanceOf[pl.iterators.epoca.utils.db.PaginatedResult[OAuthClientView]]

      (page.count shouldEqual 2) and
        (page.results.map(_.clientId).toSet shouldEqual Set(alwaysActive.client.clientId, boundaryClient.client.clientId)) and
        (page.results.map(_.status).toSet shouldEqual Set(OAuthClientEffectiveStatus.Active)) and
        (repository.authoritativeNowCalls shouldEqual 1) and
        (repository.listTimes shouldEqual Seq(capturedNow)) and
        (repository.countTimes shouldEqual Seq(capturedNow))
    }

    "read client list and count in a single RepeatableRead transaction" in {
      val owner        = newHuman()
      val organization = newOrganization(owner)
      insertContext(organization, owner, OrganizationUserRole.Owner)
      createClient(owner, organization, "Snapshot list")
      val recording = new PostgresIOTransactor(db) with RecordingTransactor
      val service   = managementService(oauthAuditRepository, transactor = recording)

      val page = success(service.list(auth(owner), organization.slug, None, None).unsafeRunSync())
        .asInstanceOf[pl.iterators.epoca.utils.db.PaginatedResult[OAuthClientView]]

      (page.count shouldEqual 1) and
        (recording.calls shouldEqual Seq(TransactorCall.ExecuteTransactionallyAt(IsolationLevel.RepeatableRead)))
    }

    "read audit events and their count in a single RepeatableRead transaction" in {
      val owner        = newHuman()
      val organization = newOrganization(owner)
      insertContext(organization, owner, OrganizationUserRole.Owner)
      val created   = createClient(owner, organization, "Snapshot audit")
      val recording = new PostgresIOTransactor(db) with RecordingTransactor
      val service   = managementService(oauthAuditRepository, transactor = recording)

      val audit = success(service.listAuditEvents(auth(owner), organization.slug, created.client.clientId, None).unsafeRunSync())
        .asInstanceOf[pl.iterators.epoca.utils.db.PaginatedResult[OAuthAuditEventView]]

      (audit.count should be_>=(1)) and
        (recording.calls shouldEqual Seq(TransactorCall.ExecuteTransactionallyAt(IsolationLevel.RepeatableRead)))
    }

    "allow expired reads, audit, renewal, post-renewal rotation, and revocation" in {
      val owner        = newHuman()
      val organization = newOrganization(owner)
      insertContext(organization, owner, OrganizationUserRole.Owner)
      val createdAt  = Instant.parse("2026-08-13T10:15:30.123456Z")
      val expiresAt  = OAuthClientExpiresAt(createdAt.plusSeconds(60L))
      val repository = new RecordingNowOAuthClientRepository(createdAt)
      val service    = managementService(oauthAuditRepository, repository, time = repository.time)
      val renewable = success(
        service
          .create(
            auth(owner),
            organization.slug,
            OAuthClientCreateRequest("Renewable expired", Set(OAuthScope.ProjectsRead), OAuthProjectAccess.AllProjects, Some(expiresAt))
          )
          .unsafeRunSync()
      ).asInstanceOf[OAuthClientSecretView]
      val revocable = success(
        service
          .create(
            auth(owner),
            organization.slug,
            OAuthClientCreateRequest("Revocable expired", Set(OAuthScope.ProjectsRead), OAuthProjectAccess.AllProjects, Some(expiresAt))
          )
          .unsafeRunSync()
      ).asInstanceOf[OAuthClientSecretView]
      repository.setNow(expiresAt.asInstanceOf[Instant])

      val fetched = success(service.get(auth(owner), organization.slug, renewable.client.clientId).unsafeRunSync())
        .asInstanceOf[OAuthClientView]
      val audit = success(service.listAuditEvents(auth(owner), organization.slug, renewable.client.clientId, None).unsafeRunSync())
        .asInstanceOf[pl.iterators.epoca.utils.db.PaginatedResult[OAuthAuditEventView]]
      val rotateBeforeRenewal = service
        .rotateSecret(auth(owner), organization.slug, renewable.client.clientId, OAuthClientVersionRequest(renewable.client.version))
        .unsafeRunSync()
      val renewed = success(
        service
          .update(
            auth(owner),
            organization.slug,
            renewable.client.clientId,
            OAuthClientUpdateRequest(renewable.client.version, None, None, None, OAuthExpirationPatch.Clear)
          )
          .unsafeRunSync()
      ).asInstanceOf[OAuthClientView]
      val rotated = success(
        service
          .rotateSecret(auth(owner), organization.slug, renewable.client.clientId, OAuthClientVersionRequest(renewed.version))
          .unsafeRunSync()
      ).asInstanceOf[OAuthClientSecretView]
      val revoked = success(
        service
          .revoke(auth(owner), organization.slug, revocable.client.clientId, OAuthClientVersionRequest(revocable.client.version))
          .unsafeRunSync()
      ).asInstanceOf[OAuthClientView]

      (fetched.status shouldEqual OAuthClientEffectiveStatus.Expired) and
        (audit.results.map(_.eventType) shouldEqual Seq(OAuthAuditEventType.Create)) and
        (rotateBeforeRenewal shouldEqual NotActive) and
        (renewed.status shouldEqual OAuthClientEffectiveStatus.Active) and
        (renewed.expiresAt should beNone) and
        (renewed.version shouldEqual 2L) and
        (renewed.policyVersion shouldEqual 2L) and
        (rotated.client.status shouldEqual OAuthClientEffectiveStatus.Active) and
        (rotated.client.version shouldEqual 3L) and
        (rotated.client.secretVersion shouldEqual 2L) and
        (rotated.client.policyVersion shouldEqual 2L) and
        (revoked.status shouldEqual OAuthClientEffectiveStatus.Revoked)
    }

    "keep revoked clients readable but use one fresh database time before rejecting every mutation" in {
      val owner        = newHuman()
      val organization = newOrganization(owner)
      insertContext(organization, owner, OrganizationUserRole.Owner)
      val now        = Instant.parse("2026-08-13T10:15:30.123456Z")
      val repository = new RecordingNowOAuthClientRepository(now)
      val service    = managementService(oauthAuditRepository, repository, time = repository.time)
      val created    = createClient(service, owner, organization, "Terminal revoked")
      val revoked = success(
        service
          .revoke(auth(owner), organization.slug, created.client.clientId, OAuthClientVersionRequest(created.client.version))
          .unsafeRunSync()
      ).asInstanceOf[OAuthClientView]

      val fetched = success(service.get(auth(owner), organization.slug, created.client.clientId).unsafeRunSync())
        .asInstanceOf[OAuthClientView]
      val audit = success(service.listAuditEvents(auth(owner), organization.slug, created.client.clientId, None).unsafeRunSync())
        .asInstanceOf[pl.iterators.epoca.utils.db.PaginatedResult[OAuthAuditEventView]]

      repository.reset()
      val updateResult = service
        .update(auth(owner), organization.slug, created.client.clientId, OAuthClientUpdateRequest(0L, None, None, None, OAuthExpirationPatch.Clear))
        .unsafeRunSync()
      val updateNowCalls = repository.authoritativeNowCalls
      repository.reset()
      val rotateResult = service
        .rotateSecret(auth(owner), organization.slug, created.client.clientId, OAuthClientVersionRequest(0L))
        .unsafeRunSync()
      val rotateNowCalls = repository.authoritativeNowCalls
      repository.reset()
      val revokeResult = service
        .revoke(auth(owner), organization.slug, created.client.clientId, OAuthClientVersionRequest(0L))
        .unsafeRunSync()
      val revokeNowCalls = repository.authoritativeNowCalls

      (fetched.status shouldEqual OAuthClientEffectiveStatus.Revoked) and
        (audit.results.map(_.eventType) should containTheSameElementsAs(Seq(OAuthAuditEventType.Revoke, OAuthAuditEventType.Create))) and
        (updateResult shouldEqual NotActive) and
        (rotateResult shouldEqual NotActive) and
        (revokeResult shouldEqual NotActive) and
        (updateNowCalls shouldEqual 1) and
        (rotateNowCalls shouldEqual 1) and
        (revokeNowCalls shouldEqual 1) and
        (revoked.version shouldEqual 2L)
    }

    "preserve public revoke behavior through the prepared transactional program" in {
      val owner        = newHuman()
      val organization = newOrganization(owner)
      insertContext(organization, owner, OrganizationUserRole.Owner)
      val publicClient   = createClient(owner, organization, "Public revoke parity")
      val preparedClient = createClient(owner, organization, "Prepared revoke parity")

      val publicResult = sut
        .revoke(auth(owner), organization.slug, publicClient.client.clientId, OAuthClientVersionRequest(1L))
        .unsafeRunSync()
      val preparedResult = transactor
        .executeTransactionally(sut.revokePreparedDBIO(auth(owner), organization.slug, preparedClient.client.clientId, OAuthClientVersionRequest(1L)))
        .unsafeRunSync()

      val publicSnapshot   = run(oauthClientRepository.findPublic(publicClient.client.clientId)).get
      val preparedSnapshot = run(oauthClientRepository.findPublic(preparedClient.client.clientId)).get
      val publicAccount    = run(serviceAccountRepository.findById(publicSnapshot.client.serviceAccountUserId)).get
      val preparedAccount  = run(serviceAccountRepository.findById(preparedSnapshot.client.serviceAccountUserId)).get
      val publicAudit      = run(oauthAuditRepository.list(OAuthAuditEventFilters(publicSnapshot.client.id))).head
      val preparedAudit    = run(oauthAuditRepository.list(OAuthAuditEventFilters(preparedSnapshot.client.id))).head

      (publicResult must beAnInstanceOf[Success[_]]) and
        (preparedResult must beAnInstanceOf[Success[_]]) and
        (publicSnapshot.client.status shouldEqual OAuthClientStatus.Revoked) and
        (preparedSnapshot.client.status shouldEqual OAuthClientStatus.Revoked) and
        (publicSnapshot.client.version shouldEqual preparedSnapshot.client.version) and
        (publicSnapshot.client.policyVersion shouldEqual preparedSnapshot.client.policyVersion) and
        (publicAccount.archivedAt should beSome) and
        (preparedAccount.archivedAt should beSome) and
        (publicAudit.eventType shouldEqual OAuthAuditEventType.Revoke) and
        (preparedAudit.eventType shouldEqual OAuthAuditEventType.Revoke) and
        (publicAudit.metadata shouldEqual preparedAudit.metadata)
    }

    "audit the locked current state and leave no mutation or audit on stale rotate and revoke" in {
      val owner        = newHuman()
      val organization = newOrganization(owner)
      insertContext(organization, owner, OrganizationUserRole.Owner)
      val rotateClient = createClient(owner, organization, "Concurrent rotate")
      val revokeClient = createClient(owner, organization, "Concurrent revoke")
      val staleRotate  = createClient(owner, organization, "Stale rotate")
      val staleRevoke  = createClient(owner, organization, "Stale revoke")

      success(sut.rotateSecret(auth(owner), organization.slug, rotateClient.client.clientId, OAuthClientVersionRequest(1L)).unsafeRunSync())
      Seq(revokeClient, staleRotate, staleRevoke).foreach { client =>
        success(
          sut
            .update(
              auth(owner),
              organization.slug,
              client.client.clientId,
              OAuthClientUpdateRequest(1L, None, Some(Set(OAuthScope.ProjectsRead, OAuthScope.ReportsRead)), None)
            )
            .unsafeRunSync()
        )
      }

      val stalePublicRepository = new StalePublicSnapshotRepository
      val concurrentSut         = managementService(oauthAuditRepository, stalePublicRepository)
      val rotated = success(
        concurrentSut.rotateSecret(auth(owner), organization.slug, rotateClient.client.clientId, OAuthClientVersionRequest(2L)).unsafeRunSync()
      )
      val revoked =
        success(concurrentSut.revoke(auth(owner), organization.slug, revokeClient.client.clientId, OAuthClientVersionRequest(2L)).unsafeRunSync())
      val rotateAudit =
        run(oauthAuditRepository.list(OAuthAuditEventFilters(run(oauthClientRepository.findPublic(rotated.client.clientId)).get.client.id))).head
      val revokeAudit =
        run(oauthAuditRepository.list(OAuthAuditEventFilters(run(oauthClientRepository.findPublic(revoked.clientId)).get.client.id))).head

      val staleRotateBefore      = run(oauthClientRepository.findPublic(staleRotate.client.clientId)).get
      val staleRevokeBefore      = run(oauthClientRepository.findPublic(staleRevoke.client.clientId)).get
      val staleRotateAuditBefore = run(oauthAuditRepository.count(OAuthAuditEventFilters(staleRotateBefore.client.id)))
      val staleRevokeAuditBefore = run(oauthAuditRepository.count(OAuthAuditEventFilters(staleRevokeBefore.client.id)))
      val staleRotateResult =
        concurrentSut.rotateSecret(auth(owner), organization.slug, staleRotate.client.clientId, OAuthClientVersionRequest(1L)).unsafeRunSync()
      val staleRevokeResult =
        concurrentSut.revoke(auth(owner), organization.slug, staleRevoke.client.clientId, OAuthClientVersionRequest(1L)).unsafeRunSync()

      (rotateAudit.metadata.hcursor.get[Long]("beforeVersion") shouldEqual Right(2L)) and
        (rotateAudit.metadata.hcursor.get[Long]("beforeSecretVersion") shouldEqual Right(2L)) and
        (revokeAudit.metadata.hcursor.get[Long]("beforeVersion") shouldEqual Right(2L)) and
        (revokeAudit.metadata.hcursor.get[Long]("beforePolicyVersion") shouldEqual Right(2L)) and
        (staleRotateResult shouldEqual StaleVersion(2L)) and
        (staleRevokeResult shouldEqual StaleVersion(2L)) and
        (run(oauthClientRepository.findPublic(staleRotate.client.clientId)).get shouldEqual staleRotateBefore) and
        (run(oauthClientRepository.findPublic(staleRevoke.client.clientId)).get shouldEqual staleRevokeBefore) and
        (run(oauthAuditRepository.count(OAuthAuditEventFilters(staleRotateBefore.client.id))) shouldEqual staleRotateAuditBefore) and
        (run(oauthAuditRepository.count(OAuthAuditEventFilters(staleRevokeBefore.client.id))) shouldEqual staleRevokeAuditBefore)
    }

    "hide clients across organizations and list active and revoked clients with stable audit paging" in {
      val ownerA = newHuman()
      val ownerB = newHuman()
      val orgA   = newOrganization(ownerA)
      val orgB   = newOrganization(ownerB)
      insertContext(orgA, ownerA, OrganizationUserRole.Owner)
      insertContext(orgB, ownerB, OrganizationUserRole.Owner)
      val created = success(
        sut
          .create(auth(ownerA), orgA.slug, OAuthClientCreateRequest("Hidden", Set(OAuthScope.ProjectsRead), OAuthProjectAccess.AllProjects))
          .unsafeRunSync()
      ).asInstanceOf[OAuthClientSecretView]

      val foreignGet = sut.get(auth(ownerB), orgB.slug, created.client.clientId).unsafeRunSync()
      val listed = success(sut.list(auth(ownerA), orgA.slug, None, None).unsafeRunSync())
        .asInstanceOf[pl.iterators.epoca.utils.db.PaginatedResult[OAuthClientView]]
      val audit = success(sut.listAuditEvents(auth(ownerA), orgA.slug, created.client.clientId, None).unsafeRunSync())
        .asInstanceOf[pl.iterators.epoca.utils.db.PaginatedResult[OAuthAuditEventView]]

      (foreignGet shouldEqual ClientNotFound) and
        (listed.results.map(_.clientId) should contain(created.client.clientId)) and
        (audit.results.map(_.eventType) shouldEqual Seq(OAuthAuditEventType.Create))
    }
  }

  "public response assembly" should {
    "return every safe client field with one authoritative time for create, list, get, update, rotate, and revoke" in {
      val owner        = newHuman()
      val organization = newOrganization(owner)
      insertContext(organization, owner, OrganizationUserRole.Owner)
      val createdAt        = Instant.parse("2026-08-13T10:15:30.123456Z")
      val responseTime     = createdAt.plusSeconds(60L)
      val recordingClients = new RecordingNowOAuthClientRepository(createdAt)
      val recordingUsers   = new RecordingUserRepository
      val service          = managementService(oauthAuditRepository, recordingClients, recordingUsers, recordingClients.time)
      val ownerSummary     = OAuthUserSummary(owner.id, owner.fullName.toString)

      val created = success(
        service
          .create(
            auth(owner),
            organization.slug,
            OAuthClientCreateRequest("Metadata client", Set(OAuthScope.ProjectsRead), OAuthProjectAccess.AllProjects)
          )
          .unsafeRunSync()
      ).asInstanceOf[OAuthClientSecretView]
      val createdView = created.client
      val createdJson = createdView.asJson.hcursor
      recordingClients.authoritativeNowCalls shouldEqual 1
      assertCompleteClientView(createdView)
      (createdView.status shouldEqual OAuthClientEffectiveStatus.Active) and
        (createdView.serviceAccount shouldEqual OAuthUserSummary(createdView.serviceAccount.userId, "Integration Metadata client")) and
        (createdView.createdAt shouldEqual OAuthClientCreatedAt(createdAt)) and
        (createdView.createdBy shouldEqual ownerSummary) and
        (createdView.modifiedAt shouldEqual OAuthClientModifiedAt(createdAt)) and
        (createdView.modifiedBy shouldEqual ownerSummary) and
        (createdView.secretRotatedAt shouldEqual OAuthClientSecretRotatedAt(createdAt)) and
        (createdView.secretRotatedBy shouldEqual ownerSummary) and
        (createdView.expiresAt should beNone) and
        (createdView.revokedAt should beNone) and
        (createdView.revokedBy should beNone) and
        (createdView.lastUsedAt should beNone) and
        (createdJson.downField("expiresAt").focus shouldEqual Some(Json.Null)) and
        (createdJson.downField("revokedAt").focus shouldEqual Some(Json.Null)) and
        (createdJson.downField("revokedBy").focus shouldEqual Some(Json.Null)) and
        (createdJson.downField("lastUsedAt").focus shouldEqual Some(Json.Null)) and
        (createdJson.get[String]("createdAt") shouldEqual Right(createdAt.toString))

      recordingClients.reset()
      val updated = success(
        service
          .update(
            auth(owner),
            organization.slug,
            createdView.clientId,
            OAuthClientUpdateRequest(createdView.version, Some("Renamed metadata client"), None, None)
          )
          .unsafeRunSync()
      ).asInstanceOf[OAuthClientView]
      recordingClients.authoritativeNowCalls shouldEqual 1
      assertCompleteClientView(updated)
      (updated.status shouldEqual OAuthClientEffectiveStatus.Active) and
        (updated.serviceAccount.displayName shouldEqual "Integration Renamed metadata client") and
        (updated.modifiedBy shouldEqual ownerSummary)

      recordingClients.reset()
      val rotated = success(
        service
          .rotateSecret(auth(owner), organization.slug, createdView.clientId, OAuthClientVersionRequest(updated.version))
          .unsafeRunSync()
      ).asInstanceOf[OAuthClientSecretView]
      recordingClients.authoritativeNowCalls shouldEqual 1
      assertCompleteClientView(rotated.client)
      (rotated.client.status shouldEqual OAuthClientEffectiveStatus.Active) and
        (rotated.client.secretRotatedBy shouldEqual ownerSummary)

      val expiresAt  = OAuthClientExpiresAt(createdAt.plusSeconds(30L))
      val lastUsedAt = OAuthClientLastUsedAt(createdAt.plusSeconds(15L))
      val snapshot   = run(recordingClients.findPublic(createdView.clientId)).get
      run(sqlu"""UPDATE oauth_client SET expires_at = ${expiresAt.asInstanceOf[Instant]}, last_used_at = ${lastUsedAt.asInstanceOf[Instant]}
                   WHERE id = ${snapshot.client.id.toString}::uuid""")

      recordingClients.setNow(responseTime)
      recordingClients.reset()
      val fetched = success(service.get(auth(owner), organization.slug, createdView.clientId).unsafeRunSync()).asInstanceOf[OAuthClientView]
      (recordingClients.authoritativeNowCalls shouldEqual 1) and
        (fetched.status shouldEqual OAuthClientEffectiveStatus.Expired) and
        (fetched.expiresAt shouldEqual Some(expiresAt)) and
        (fetched.lastUsedAt shouldEqual Some(lastUsedAt)) and
        (fetched.asJson.hcursor.get[String]("expiresAt") shouldEqual Right(expiresAt.asInstanceOf[Instant].toString)) and
        (fetched.asJson.hcursor.get[String]("lastUsedAt") shouldEqual Right(lastUsedAt.asInstanceOf[Instant].toString))
      assertCompleteClientView(fetched)

      recordingClients.reset()
      val listed = success(service.list(auth(owner), organization.slug, None, None).unsafeRunSync())
        .asInstanceOf[pl.iterators.epoca.utils.db.PaginatedResult[OAuthClientView]]
      val listedView = listed.results.find(_.clientId == createdView.clientId).get
      (recordingClients.authoritativeNowCalls shouldEqual 1) and
        (recordingClients.listTimes shouldEqual Seq(responseTime)) and
        (recordingClients.countTimes shouldEqual Seq(responseTime)) and
        (listedView.status shouldEqual OAuthClientEffectiveStatus.Expired)
      assertCompleteClientView(listedView)

      recordingClients.reset()
      val revoked = success(
        service
          .revoke(auth(owner), organization.slug, createdView.clientId, OAuthClientVersionRequest(rotated.client.version))
          .unsafeRunSync()
      ).asInstanceOf[OAuthClientView]
      (recordingClients.authoritativeNowCalls shouldEqual 1) and
        (revoked.status shouldEqual OAuthClientEffectiveStatus.Revoked) and
        (revoked.revokedAt shouldEqual Some(OAuthClientRevokedAt(responseTime))) and
        (revoked.revokedBy shouldEqual Some(ownerSummary)) and
        (revoked.expiresAt shouldEqual Some(expiresAt)) and
        (revoked.lastUsedAt shouldEqual Some(lastUsedAt)) and
        (revoked.asJson.hcursor.get[String]("revokedAt") shouldEqual Right(responseTime.toString))
      assertCompleteClientView(revoked)
    }

    "batch repeated client actors once and include archived humans in historical summaries" in {
      val owner        = newHuman()
      val supervisor   = newHuman().copy(isSuperAdmin = UserIsSuperAdmin(true))
      val organization = newOrganization(owner)
      insertContext(organization, owner, OrganizationUserRole.Owner)
      run(userRepository.insert(supervisor))
      val recordingUsers = new RecordingUserRepository
      val service        = managementService(oauthAuditRepository, oauthClientRepository, recordingUsers)
      val first = success(
        service
          .create(
            auth(owner),
            organization.slug,
            OAuthClientCreateRequest("First batch", Set(OAuthScope.ProjectsRead), OAuthProjectAccess.AllProjects)
          )
          .unsafeRunSync()
      ).asInstanceOf[OAuthClientSecretView]
      success(
        service
          .create(
            auth(owner),
            organization.slug,
            OAuthClientCreateRequest("Second batch", Set(OAuthScope.ProjectsRead), OAuthProjectAccess.AllProjects)
          )
          .unsafeRunSync()
      )
      run(userRepository.update(owner.copy(archivedAt = Some(UserArchivedAt(Instant.parse("2026-08-13T12:00:00Z"))))))
      recordingUsers.reset()

      val listed = success(service.list(auth(supervisor), organization.slug, None, None).unsafeRunSync())
        .asInstanceOf[pl.iterators.epoca.utils.db.PaginatedResult[OAuthClientView]]

      (recordingUsers.listFilters shouldEqual Seq(UserFilters(ids = Some(Seq(owner.id)), includeArchived = true))) and
        (listed.results.filter(view => Set("First batch", "Second batch").contains(view.name)).map(_.createdBy).toSet shouldEqual
          Set(OAuthUserSummary(owner.id, owner.fullName.toString))) and
        (success(service.get(auth(supervisor), organization.slug, first.client.clientId).unsafeRunSync())
          .asInstanceOf[OAuthClientView]
          .createdBy shouldEqual
          OAuthUserSummary(owner.id, owner.fullName.toString))
    }

    "use a deterministic identifier-only fallback when a historical human is missing" in {
      val owner        = newHuman()
      val organization = newOrganization(owner)
      insertContext(organization, owner, OrganizationUserRole.Owner)
      val droppingUsers = new RecordingUserRepository(Set(owner.id))
      val service       = managementService(oauthAuditRepository, oauthClientRepository, droppingUsers)
      val created = success(
        sut
          .create(
            auth(owner),
            organization.slug,
            OAuthClientCreateRequest("Missing actor", Set(OAuthScope.ProjectsRead), OAuthProjectAccess.AllProjects)
          )
          .unsafeRunSync()
      ).asInstanceOf[OAuthClientSecretView]

      val view = success(service.get(auth(owner), organization.slug, created.client.clientId).unsafeRunSync()).asInstanceOf[OAuthClientView]

      (view.createdBy shouldEqual OAuthUserSummary(owner.id, s"Unknown user (${owner.id})")) and
        (view.modifiedBy shouldEqual view.createdBy) and
        (view.secretRotatedBy shouldEqual view.createdBy)
    }

    "batch unique audit humans while resolving service-account and non-human actors safely" in {
      val owner = newHuman()
      val historicalActor =
        newHuman().copy(archivedAt = Some(UserArchivedAt(Instant.parse("2026-08-13T09:00:00Z"))), isSuperAdmin = UserIsSuperAdmin(true))
      val organization = newOrganization(owner)
      insertContext(organization, owner, OrganizationUserRole.Owner)
      run(userRepository.insert(historicalActor))
      val created  = createClient(owner, organization, "Audit summaries")
      val snapshot = run(oauthClientRepository.findPublic(created.client.clientId)).get
      insertAudit(snapshot, Some(historicalActor.id), OAuthAuditActorType.Human, OAuthAuditEventType.Update, 1L)
      insertAudit(snapshot, Some(historicalActor.id), OAuthAuditActorType.Human, OAuthAuditEventType.Rotate, 2L)
      insertAudit(snapshot, Some(snapshot.client.serviceAccountUserId), OAuthAuditActorType.ServiceAccount, OAuthAuditEventType.TokenIssued, 3L)
      insertAudit(snapshot, None, OAuthAuditActorType.Anonymous, OAuthAuditEventType.InvalidClient, 4L)
      insertAudit(snapshot, None, OAuthAuditActorType.System, OAuthAuditEventType.RateLimit, 5L)
      val recordingUsers = new RecordingUserRepository
      val service        = managementService(oauthAuditRepository, oauthClientRepository, recordingUsers)

      val page = success(service.listAuditEvents(auth(owner), organization.slug, created.client.clientId, None).unsafeRunSync())
        .asInstanceOf[pl.iterators.epoca.utils.db.PaginatedResult[OAuthAuditEventView]]
      val byType        = page.results.map(event => event.eventType -> event).toMap
      val encodedByType = page.results.map(event => event.eventType -> event.asJson.hcursor).toMap

      (recordingUsers.listFilters.size shouldEqual 1) and
        (recordingUsers.listFilters.head.includeArchived should beTrue) and
        (recordingUsers.listFilters.head.ids.map(_.toSet) shouldEqual Some(Set(owner.id, historicalActor.id))) and
        (byType(OAuthAuditEventType.Update).actor shouldEqual Some(OAuthUserSummary(historicalActor.id, historicalActor.fullName.toString))) and
        (byType(OAuthAuditEventType.Rotate).actor shouldEqual Some(OAuthUserSummary(historicalActor.id, historicalActor.fullName.toString))) and
        (byType(OAuthAuditEventType.TokenIssued).actor shouldEqual
          Some(OAuthUserSummary(snapshot.client.serviceAccountUserId, "Integration Audit summaries"))) and
        (byType(OAuthAuditEventType.InvalidClient).actor should beNone) and
        (byType(OAuthAuditEventType.RateLimit).actor should beNone) and
        (encodedByType(OAuthAuditEventType.Update).downField("actor").focus.flatMap(_.asObject).map(_.keys.toSet) shouldEqual
          Some(Set("userId", "displayName"))) and
        (encodedByType(OAuthAuditEventType.TokenIssued).downField("actor").focus.flatMap(_.asObject).map(_.keys.toSet) shouldEqual
          Some(Set("userId", "displayName"))) and
        (encodedByType(OAuthAuditEventType.InvalidClient).downField("actor").focus shouldEqual Some(Json.Null)) and
        (encodedByType(OAuthAuditEventType.RateLimit).downField("actor").focus shouldEqual Some(Json.Null))
    }

    "keep secrets and identity attributes out of ordinary client and audit encoders" in {
      val owner        = newHuman()
      val organization = newOrganization(owner)
      insertContext(organization, owner, OrganizationUserRole.Owner)
      val created = createClient(owner, organization, "Encoder safety")
      val audit = success(sut.listAuditEvents(auth(owner), organization.slug, created.client.clientId, None).unsafeRunSync())
        .asInstanceOf[pl.iterators.epoca.utils.db.PaginatedResult[OAuthAuditEventView]]
        .results
        .head
      val forbidden = Set("clientSecret", "secretHash", "accessToken", "jwt", "Authorization", "email", "login", "role", "isSuperAdmin")

      (jsonFieldNames(created.client.asJson) intersect forbidden should beEmpty) and
        (jsonFieldNames(audit.asJson) intersect forbidden should beEmpty) and
        (created.asJson.asObject.map(_.keys.toSet) shouldEqual Some(Set("client", "clientSecret"))) and
        (created.client.asJson.asObject.flatMap(_("clientSecret")) should beNone) and
        (audit.asJson.asObject.flatMap(_("clientSecret")) should beNone) and
        (created.toString.contains(created.clientSecret) should beFalse) and
        (created.toString.contains("clientSecret=<redacted>") should beTrue)
    }
  }

  private def managementService(
    auditRepository: OAuthAuditRepository,
    clientRepository: OAuthClientRepository = oauthClientRepository,
    actors: UserRepository = userRepository,
    time: OAuthAuthoritativeTime = authoritativeTime,
    transactor: PostgresIOTransactor = transactor
  ): OAuthClientManagementService =
    new OAuthClientManagementService(
      transactor,
      time,
      grantabilityService,
      serviceAccountRepository,
      clientRepository,
      auditRepository,
      actors,
      secretService
    )

  private def countingTime(calls: AtomicInteger, value: Instant): OAuthAuthoritativeTime = new OAuthAuthoritativeTime {
    override def now: DBIO[Instant] = {
      calls.incrementAndGet()
      DBIO.successful(value)
    }
  }

  private def failingTime(calls: AtomicInteger): OAuthAuthoritativeTime = new OAuthAuthoritativeTime {
    override def now: DBIO[Instant] = {
      calls.incrementAndGet()
      DBIO.failed(UnexpectedAuthoritativeTimeRead)
    }
  }

  private def newHuman(): User =
    userGenerator.generate.copy(accountType = UserAccountType.Human, archivedAt = None, isSuperAdmin = UserIsSuperAdmin(false))

  private def newOrganization(owner: User): Organization =
    organizationGenerator.generate.copy(createdBy = owner.id, archivedAt = None)

  private def createClient(
    owner: User,
    organization: Organization,
    name: String
  ): OAuthClientSecretView =
    createClient(sut, owner, organization, name)

  private def createClient(
    service: OAuthClientManagementService,
    owner: User,
    organization: Organization,
    name: String
  ): OAuthClientSecretView =
    success(
      service
        .create(auth(owner), organization.slug, OAuthClientCreateRequest(name, Set(OAuthScope.ProjectsRead), OAuthProjectAccess.AllProjects))
        .unsafeRunSync()
    ).asInstanceOf[OAuthClientSecretView]

  private def insertProject(organization: Organization, owner: User): Project = {
    val client = clientGenerator.generate.copy(
      organizationId = organization.id,
      createdBy = owner.id,
      modifiedBy = owner.id,
      archivedAt = None,
      archivedBy = None
    )
    run(clientRepository.insert(client))
    val project = projectGenerator.generate.copy(
      organizationId = organization.id,
      clientId = client.id,
      createdBy = owner.id,
      modifiedBy = owner.id,
      archivedAt = None,
      archivedBy = None
    )
    run(projectRepository.insert(project))
  }

  private def insertContext(
    organization: Organization,
    user: User,
    role: OrganizationUserRole
  ): Unit = {
    run(userRepository.insert(user))
    run(organizationRepository.insert(organization))
    insertMembership(organization, user, Seq(role))
  }

  private def insertMembership(
    organization: Organization,
    user: User,
    roles: Seq[OrganizationUserRole]
  ): Unit = {
    val membership = OrganizationUser(OrganizationUserId.random, organization.id, user.id, roles, None)
    run(organizationUserRepository.upsert(membership))
    ()
  }

  private def auth(user: User): AuthContext =
    AuthContextProvider.user.copy(id = user.id, isSuperAdmin = user.isSuperAdmin, identities = Nil, roles = Nil)

  private def success[A](result: OAuthClientManagementResult[A]): A = result match {
    case Success(value) => value
    case other          => throw new AssertionError(s"expected Success, got $other")
  }

  private def serviceAccountCount(lastName: String): Int =
    run(sql"""select count(*) from public."user" where account_type = 'ServiceAccount' and last_name = $lastName""".as[Int].head)

  private def run[A](action: DBIO[A]): A = Await.result(db.run(action), Duration.Inf)

  private val requiredClientViewFields = Set(
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

  private def assertCompleteClientView(view: OAuthClientView) =
    (view.asJson.asObject.map(_.keys.toSet) shouldEqual Some(requiredClientViewFields)) and
      (jsonFieldNames(view.serviceAccount.asJson) shouldEqual Set("userId", "displayName")) and
      (jsonFieldNames(view.createdBy.asJson) shouldEqual Set("userId", "displayName")) and
      (jsonFieldNames(view.modifiedBy.asJson) shouldEqual Set("userId", "displayName")) and
      (jsonFieldNames(view.secretRotatedBy.asJson) shouldEqual Set("userId", "displayName")) and
      (view.revokedBy.forall(summary => jsonFieldNames(summary.asJson) == Set("userId", "displayName")) should beTrue)

  private def jsonFieldNames(json: Json): Set[String] =
    json.arrayOrObject(Set.empty, _.flatMap(jsonFieldNames).toSet, fields => fields.keys.toSet ++ fields.values.flatMap(jsonFieldNames))

  private def insertAudit(
    snapshot: OAuthClientRepository.OAuthClientSnapshot,
    actorUserId: Option[UserId],
    actorType: OAuthAuditActorType,
    eventType: OAuthAuditEventType,
    offsetSeconds: Long
  ): Unit = {
    val (category, outcome, metadata) = eventType match {
      case OAuthAuditEventType.Update =>
        (
          OAuthAuditCategory.Management,
          OAuthAuditOutcome.Success,
          Json.obj(
            "beforeScopes"        -> Json.arr(Json.fromString(OAuthScope.ProjectsRead.value)),
            "afterScopes"         -> Json.arr(Json.fromString(OAuthScope.ProjectsRead.value)),
            "beforeProjectMode"   -> Json.fromString(OAuthProjectAccessMode.AllProjects.entryName),
            "afterProjectMode"    -> Json.fromString(OAuthProjectAccessMode.AllProjects.entryName),
            "beforeProjectIds"    -> Json.arr(),
            "afterProjectIds"     -> Json.arr(),
            "beforeVersion"       -> Json.fromLong(1L),
            "afterVersion"        -> Json.fromLong(2L),
            "beforePolicyVersion" -> Json.fromLong(1L),
            "afterPolicyVersion"  -> Json.fromLong(1L)
          )
        )
      case OAuthAuditEventType.Rotate =>
        (
          OAuthAuditCategory.Management,
          OAuthAuditOutcome.Success,
          Json.obj(
            "beforeVersion"       -> Json.fromLong(2L),
            "afterVersion"        -> Json.fromLong(3L),
            "beforeSecretVersion" -> Json.fromLong(1L),
            "afterSecretVersion"  -> Json.fromLong(2L)
          )
        )
      case OAuthAuditEventType.TokenIssued =>
        (
          OAuthAuditCategory.Authentication,
          OAuthAuditOutcome.Success,
          Json.obj(
            "scopes"        -> Json.arr(Json.fromString(OAuthScope.ProjectsRead.value)),
            "projectMode"   -> Json.fromString(OAuthProjectAccessMode.AllProjects.entryName),
            "projectIds"    -> Json.arr(),
            "policyVersion" -> Json.fromLong(1L),
            "secretVersion" -> Json.fromLong(1L)
          )
        )
      case OAuthAuditEventType.InvalidClient =>
        (
          OAuthAuditCategory.Authentication,
          OAuthAuditOutcome.Failure,
          Json.obj("failureCategory" -> Json.fromString(OAuthAuditFailureCategory.InvalidSecret.entryName))
        )
      case OAuthAuditEventType.RateLimit =>
        (
          OAuthAuditCategory.Authentication,
          OAuthAuditOutcome.Failure,
          Json.obj("failureCategory" -> Json.fromString(OAuthAuditFailureCategory.IpRateLimit.entryName))
        )
      case other => throw new AssertionError(s"unsupported audit fixture type: $other")
    }
    run(
      oauthAuditRepository.insert(
        NewOAuthAuditEvent(
          OAuthAuditEventId(UUID.randomUUID()),
          OAuthAuditOccurredAt(Instant.parse("2026-08-13T10:00:00Z").plusSeconds(offsetSeconds)),
          category,
          Some(snapshot.client.organizationId),
          Some(snapshot.client.id),
          actorType,
          actorUserId,
          eventType,
          outcome,
          None,
          None,
          None,
          metadata
        )
      )
    )
    ()
  }

  private case object ForcedAuditFailure              extends RuntimeException("forced audit failure")
  private case object UnexpectedAuthoritativeTimeRead extends RuntimeException("unexpected authoritative time read")

  private final class FailingAuditRepository(delegate: OAuthAuditRepository) extends OAuthAuditRepository {
    override def insert(event: NewOAuthAuditEvent): DBIO[OAuthAuditEventRow] = DBIO.failed(ForcedAuditFailure)
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

  private final class StalePublicSnapshotRepository extends OAuthClientRepositoryImpl(authoritativeTime) {
    override def findPublic(clientId: OAuthClientPublicId): DBIO[Option[OAuthClientRepository.OAuthClientSnapshot]] =
      super
        .findPublic(clientId)
        .map(_.map { snapshot =>
          snapshot.copy(client =
            snapshot.client.copy(
              version = OAuthClientVersion(snapshot.client.version.asInstanceOf[Long] - 1L),
              policyVersion = OAuthClientPolicyVersion(math.max(1L, snapshot.client.policyVersion.asInstanceOf[Long] - 1L)),
              secretVersion = OAuthClientSecretVersion(math.max(1L, snapshot.client.secretVersion.asInstanceOf[Long] - 1L))
            )
          )
        })
  }

  private final class RecordingOAuthAuthoritativeTime(initialNow: Instant) extends OAuthAuthoritativeTime {
    private var currentNow: Instant = initialNow
    var calls: Int                  = 0

    override def now: DBIO[Instant] = {
      calls += 1
      DBIO.successful(currentNow)
    }

    def reset(): Unit                = calls = 0
    def setNow(value: Instant): Unit = currentNow = value
  }

  private final class RecordingNowOAuthClientRepository private (val time: RecordingOAuthAuthoritativeTime) extends OAuthClientRepositoryImpl(time) {
    def this(initialNow: Instant) = this(new RecordingOAuthAuthoritativeTime(initialNow))

    private var afterListNow: Option[Instant] = None
    var listTimes: Vector[Instant]            = Vector.empty
    var countTimes: Vector[Instant]           = Vector.empty

    def authoritativeNowCalls: Int = time.calls

    override def list(filters: OAuthClientRepository.OAuthClientFilters, at: Instant): DBIO[Seq[OAuthClientRepository.OAuthClientSnapshot]] = {
      listTimes :+= at
      super.list(filters, at).map { snapshots =>
        afterListNow.foreach(time.setNow)
        snapshots
      }
    }

    override def count(filters: OAuthClientRepository.OAuthClientFilters, at: Instant): DBIO[Int] = {
      countTimes :+= at
      super.count(filters, at)
    }

    def reset(): Unit = {
      time.reset()
      listTimes = Vector.empty
      countTimes = Vector.empty
    }

    def setNow(value: Instant): Unit = time.setNow(value)

    def advanceNowAfterListTo(value: Instant): Unit = afterListNow = Some(value)
  }

  private final class RecordingUserRepository(omitted: Set[UserId] = Set.empty) extends UserRepositoryImpl {
    var listFilters: Vector[UserFilters] = Vector.empty

    override def list(filter: UserFilters): DBIO[Seq[User]] = {
      listFilters :+= filter
      super.list(filter).map(_.filterNot(user => omitted.contains(user.id)))(scala.concurrent.ExecutionContext.Implicits.global)
    }

    def reset(): Unit = listFilters = Vector.empty
  }
}
