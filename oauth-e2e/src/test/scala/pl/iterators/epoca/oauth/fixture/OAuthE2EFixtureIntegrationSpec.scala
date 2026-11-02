package pl.iterators.epoca.oauth.fixture

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import com.typesafe.config.{Config, ConfigFactory, ConfigResolveOptions}
import io.circe.parser.parse
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.model.headers.{Authorization, HttpChallenge, OAuth2BearerToken}
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.server.directives.AuthenticationResult
import org.apache.pekko.http.scaladsl.testkit.Specs2RouteTest
import org.mindrot.jbcrypt.BCrypt
import org.mockito.MockitoSugar
import pl.iterators.epoca.auth.routers.UserAuthenticator
import pl.iterators.epoca.client.repositories.ClientRepository.ClientFilters
import pl.iterators.epoca.client.repositories.ClientRepositoryImpl
import pl.iterators.epoca.main.ApplicationLoader
import pl.iterators.epoca.oauth.adapters.OAuthReadResult.{NotFound, Ok}
import pl.iterators.epoca.oauth.adapters.{
  OAuthAccessPolicy,
  OAuthClientReader,
  OAuthExporter,
  OAuthOrganizationReader,
  OAuthProjectReadAdapter,
  OAuthReportReader,
  OAuthUserReader,
  OAuthWorkUnitReader,
  OAuthWorkUnitWriter
}
import pl.iterators.epoca.oauth.domain.OAuthAuthContext
import pl.iterators.epoca.oauth.domain.OAuthDomain._
import pl.iterators.epoca.oauth.fixture.OAuthE2EFixtureControlProtocol.ControlCommand.{Advance300Seconds, Cleanup, RevokeOAuth029, Setup, Status}
import pl.iterators.epoca.oauth.fixture.OAuthE2EFixtureControlProtocol.{ControlDeadline, ControlRequestContext, ControlResult}
import pl.iterators.epoca.oauth.fixture.OAuthE2EFixtureDomain.FixtureInitializationResult.ControllerClaimed
import pl.iterators.epoca.oauth.fixture.OAuthE2EFixtureDomain.{
  CleanupStep,
  CleanupStepState,
  ControllerInstanceId,
  FixtureAlias,
  FixtureOwnership,
  FixtureRunState
}
import pl.iterators.epoca.oauth.repositories.OAuthAuditRepository.OAuthAuditEventFilters
import pl.iterators.epoca.oauth.repositories.{OAuthAuditRepositoryImpl, OAuthClientRepositoryImpl, OAuthRateLimitRepositoryImpl}
import pl.iterators.epoca.oauth.routers.{OAuthApiRouter, OAuthAuthenticator, OAuthPrincipalAuthenticator}
import pl.iterators.epoca.oauth.services.OAuthConfig.{OAuthRateLimit, OAuthSigningKey, OAuthSigningKeyRing}
import pl.iterators.epoca.oauth.services.OAuthTokenService.OAuthTokenResult.{InvalidClient, Issued}
import pl.iterators.epoca.oauth.services.{
  OAuthClientManagementService,
  OAuthGrantabilityService,
  OAuthJwtService,
  OAuthSecretService,
  OAuthTokenService
}
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepositoryImpl, OrganizationUserRepositoryImpl}
import pl.iterators.epoca.project.repositories.ProjectRepository.ProjectFilters
import pl.iterators.epoca.project.repositories.{ProjectRepositoryImpl, ProjectUserRepositoryImpl}
import pl.iterators.epoca.user.repositories.UserRepository.UserFilters
import pl.iterators.epoca.user.repositories.{ServiceAccountRepositoryImpl, UserRepositoryImpl}
import pl.iterators.epoca.utils.PgRepositorySpec
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.job.TriggeredJobScheduler
import pl.iterators.epoca.utils.log.TracingContext
import pl.iterators.epoca.workunit.repositories.WorkUnitRepositoryImpl
import slick.util.AsyncExecutor
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.WebSocketStreamBackend

import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import java.util.{Base64, Properties, UUID}
import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}

class OAuthE2EFixtureIntegrationSpec extends PgRepositorySpec with Specs2RouteTest with MockitoSugar {
  sequential

  private implicit val ioRuntime: IORuntime = IORuntime.global

  private val fixtureRepository          = new OAuthE2EFixtureRepositoryImpl()
  private val authoritativeTime          = new FixtureOAuthAuthoritativeTime(fixtureRepository)
  private val userRepository             = new UserRepositoryImpl()
  private val organizationRepository     = new OrganizationRepositoryImpl()
  private val organizationUserRepository = new OrganizationUserRepositoryImpl()
  private val clientRepository           = new ClientRepositoryImpl()
  private val projectRepository          = new ProjectRepositoryImpl()
  private val workUnitRepository         = new WorkUnitRepositoryImpl()
  private val projectUserRepository      = new ProjectUserRepositoryImpl()
  private val serviceAccountRepository   = new ServiceAccountRepositoryImpl()
  private val oauthClientRepository      = new OAuthClientRepositoryImpl(authoritativeTime)
  private val oauthAuditRepository       = new OAuthAuditRepositoryImpl()
  private val dummySecretHash            = OAuthClientSecretHash(BCrypt.hashpw("oauth-e2e-integration-dummy-secret", BCrypt.gensalt(12)))
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
  private val commandExecutor =
    new OAuthE2EFixtureCommandExecutor(transactor, new PostgresOAuthE2EFixtureBackendCanceller(db), OAuthE2EFixtureControllerSupervisor.noop)
  private val fixtureService = new OAuthE2EFixtureService(
    commandExecutor,
    fixtureRepository,
    managementService,
    secretService,
    userRepository,
    organizationRepository,
    organizationUserRepository,
    clientRepository,
    projectRepository,
    workUnitRepository
  )
  private val controlHandler = new OAuthE2EFixtureServiceControlHandler(fixtureService)

  private val issuer   = "https://oauth-e2e.disposable.example.test/oauth"
  private val audience = "epoca-api"
  private val jwtService = new OAuthJwtService(
    issuer,
    audience,
    OAuthSigningKeyRing(
      "oauth-e2e-integration-a",
      Vector(OAuthSigningKey("oauth-e2e-integration-a", "oauth-e2e-integration-signing-key-material-at-least-32-bytes", retired = false))
    )
  )
  private lazy val businessDbA = independentDatabase("oauth-e2e-integration-business-a")
  private lazy val businessDbB = independentDatabase("oauth-e2e-integration-business-b")
  private lazy val instanceA   = businessInstance(businessDbA)
  private lazy val instanceB   = businessInstance(businessDbB)
  private lazy val provisioned = provision()

  step(dropFixtureTables())

  "The disposable OAuth integration" should {
    "OAUTH-E2E-027 reuse one cached token at exact expiry and renew exactly once" in {
      val issueCount   = new AtomicInteger(0)
      val renewalCount = new AtomicInteger(0)

      val result = provisioned.credentials
        .projectToBroker(FixtureAlias.OAuth027) { credential =>
          for {
            firstToken <- issue(instanceA, credential, Some("projects:read"), issueCount)
            firstClaims = verifiedClaims(firstToken)
            firstAuth        <- authenticate(instanceA, firstToken)
            firstIds         <- visibleProjectIds(instanceA, firstAuth, provisioned.ownership(FixtureAlias.OAuth027))
            advancedResponse <- controlHandler.handle(Advance300Seconds(provisioned.runId), UUID.randomUUID(), requestContext())
            advanced = requireResult(advancedResponse, "applied")
            authoritativeNow <- IO(awaitDb(authoritativeTime.now))
            expired          <- authenticateOption(instanceB, firstToken)
            renewedToken     <- issue(instanceB, credential, Some("projects:read"), renewalCount)
            renewedAuth      <- authenticate(instanceB, renewedToken)
            renewedIds       <- visibleProjectIds(instanceB, renewedAuth, provisioned.ownership(FixtureAlias.OAuth027))
          } yield {
            require(firstClaims.expiresAt.getEpochSecond - firstClaims.issuedAt.getEpochSecond == 300L)
            require(advancedResponse.status == StatusCodes.OK)
            require(
              advanced.hcursor.get[Int]("offsetAfterSeconds").toOption.get - advanced.hcursor.get[Int]("offsetBeforeSeconds").toOption.get == 300
            )
            require(firstIds == Set(provisioned.ownership(FixtureAlias.OAuth027).projectP1Id))
            require(!authoritativeNow.isBefore(firstClaims.expiresAt))
            require(jwtService.validateAt(firstClaims, authoritativeNow) == Left(OAuthJwtError.Expired))
            require(expired.isEmpty)
            require(renewedToken != firstToken)
            require(renewedIds == firstIds)
            require(issueCount.get() == 1 && renewalCount.get() == 1)
            OAuthE2EFixtureSanitizedCaseOutcome.Passed
          }
        }
        .unsafeRunSync()

      result shouldEqual OAuthE2EFixtureSanitizedCaseResult(FixtureAlias.OAuth027, OAuthE2EFixtureSanitizedCaseOutcome.Passed)
    }

    "OAUTH-E2E-028 return the real projects:read insufficient-scope contract without escalation" in {
      val issueCount = new AtomicInteger(0)
      val result = provisioned.credentials
        .projectToBroker(FixtureAlias.OAuth028) { credential =>
          for {
            token <- issue(instanceA, credential, Some("organization:read"), issueCount)
            auth  <- authenticate(instanceA, token)
          } yield {
            val response = projectListThroughNormalDispatch(token, organization(provisioned.ownership(FixtureAlias.OAuth028)).slug.toString)
            require(auth.scopes == Set(OAuthScope.OrganizationRead))
            require(response._1 == StatusCodes.Forbidden)
            require(response._2 == "{\"error\":\"insufficient_scope\"}")
            require(response._3 == "Bearer error=\"insufficient_scope\", scope=\"projects:read\"")
            require(issueCount.get() == 1)
            require(!ApplicationLoader.getClass.getDeclaredMethods.exists(_.getName.contains("oauthInsufficientScopeResponse")))
            OAuthE2EFixtureSanitizedCaseOutcome.Passed
          }
        }
        .unsafeRunSync()

      result shouldEqual OAuthE2EFixtureSanitizedCaseResult(FixtureAlias.OAuth028, OAuthE2EFixtureSanitizedCaseOutcome.Passed)
    }

    "OAUTH-E2E-029 enforce P1 and foreign boundaries, revoke once, then fail one reauthentication" in {
      val reauthCount = new AtomicInteger(0)
      val result = provisioned.credentials
        .projectToBroker(FixtureAlias.OAuth029) { credential =>
          val owned = provisioned.ownership(FixtureAlias.OAuth029)
          for {
            token       <- issue(instanceA, credential, Some("projects:read"), new AtomicInteger(0))
            auth        <- authenticate(instanceA, token)
            visible     <- visibleProjectIds(instanceA, auth, owned)
            p2          <- instanceA.projects.find(OAuthAccessPolicy.from(auth), organization(owned).slug, owned.projectP2Id)
            foreign     <- instanceA.projects.find(OAuthAccessPolicy.from(auth), foreignOrganization(owned).slug, owned.foreignProjectId)
            revoke      <- controlHandler.handle(RevokeOAuth029(provisioned.runId), UUID.randomUUID(), requestContext())
            oldRejected <- authenticateOption(instanceB, token)
            reauth      <- instanceB.tokens.issue(Some(credentials(credential)), Some("projects:read"), nextSource(), None)
          } yield {
            reauthCount.incrementAndGet()
            val snapshot = awaitDb(oauthClientRepository.lock(owned.oauthClientId)).get
            val audits   = awaitDb(oauthAuditRepository.list(OAuthAuditEventFilters(owned.oauthClientId)))
            require(visible == Set(owned.projectP1Id))
            require(p2 == NotFound && foreign == NotFound)
            require(revoke.status == StatusCodes.OK && requireResult(revoke, "applied").isObject)
            require(oldRejected.isEmpty)
            require(reauth == InvalidClient && reauthCount.get() == 1)
            require(snapshot.client.status == OAuthClientStatus.Revoked)
            require(audits.count(_.eventType == OAuthAuditEventType.Revoke) == 1)
            OAuthE2EFixtureSanitizedCaseOutcome.Passed
          }
        }
        .unsafeRunSync()

      result shouldEqual OAuthE2EFixtureSanitizedCaseResult(FixtureAlias.OAuth029, OAuthE2EFixtureSanitizedCaseOutcome.Passed)
    }

    "run cleanup through control auth after oauth029 revocation" in {
      val cleanupKey = UUID.randomUUID()
      val cleanup    = controlHandler.handle(Cleanup(provisioned.runId), cleanupKey, cleanupRequestContext()).unsafeRunSync()
      val replay     = controlHandler.handle(Cleanup(provisioned.runId), cleanupKey, cleanupRequestContext()).unsafeRunSync()
      val conflict   = controlHandler.handle(Cleanup(provisioned.runId), UUID.randomUUID(), cleanupRequestContext()).unsafeRunSync()
      val status     = controlHandler.handle(Status(provisioned.runId), UUID.randomUUID(), requestContext()).unsafeRunSync()

      cleanup.status shouldEqual StatusCodes.OK
      requireResult(cleanup, "cleaned").isObject must beTrue
      replay.status shouldEqual StatusCodes.OK
      requireResult(replay, "already-cleaned").isObject must beTrue
      conflict.status shouldEqual StatusCodes.Conflict
      requireResult(conflict, "conflict").isObject must beTrue
      status.status shouldEqual StatusCodes.OK
      val statusJson = requireResult(status, "status")
      statusJson.hcursor.get[String]("runState").toOption shouldEqual Some("cleaned")
      statusJson.hcursor.get[Int]("completedCleanupSteps").toOption shouldEqual Some(CleanupStep.values.size)

      val ledger = awaitDb(fixtureRepository.ledger).get
      ledger.runState shouldEqual FixtureRunState.Cleaned
      ledger.cleanupSteps shouldEqual CleanupStep.values.map(_ -> CleanupStepState.Complete).toMap
      provisioned.ownership.values.foreach { owned =>
        val oauth = awaitDb(oauthClientRepository.lock(owned.oauthClientId)).get
        awaitDb(oauthAuditRepository.list(OAuthAuditEventFilters(owned.oauthClientId))).count(_.eventType == OAuthAuditEventType.Revoke) shouldEqual 1
        oauth.client.status shouldEqual OAuthClientStatus.Revoked
        val projectP1      = awaitDb(projectRepository.find(ProjectFilters(id = Some(owned.projectP1Id), includeArchived = true))).get
        val projectP2      = awaitDb(projectRepository.find(ProjectFilters(id = Some(owned.projectP2Id), includeArchived = true))).get
        val foreignProject = awaitDb(projectRepository.find(ProjectFilters(id = Some(owned.foreignProjectId), includeArchived = true))).get
        val businessClient = awaitDb(clientRepository.find(ClientFilters(id = Some(owned.businessClientId), includeArchived = true))).get
        val foreignBusinessClient =
          awaitDb(clientRepository.find(ClientFilters(id = Some(owned.foreignBusinessClientId), includeArchived = true))).get
        val organization =
          awaitDb(organizationRepository.find(OrganizationFilters(id = Some(owned.organizationId), includeArchived = true))).get
        val foreignOrganization =
          awaitDb(organizationRepository.find(OrganizationFilters(id = Some(owned.foreignOrganizationId), includeArchived = true))).get

        projectP1.archivedAt must beSome
        projectP1.archivedBy shouldEqual Some(owned.ownerUserId)
        projectP2.archivedAt must beSome
        projectP2.archivedBy shouldEqual Some(owned.ownerUserId)
        foreignProject.archivedAt must beSome
        foreignProject.archivedBy shouldEqual Some(owned.foreignOwnerUserId)
        businessClient.archivedAt must beSome
        businessClient.archivedBy shouldEqual Some(owned.ownerUserId)
        foreignBusinessClient.archivedAt must beSome
        foreignBusinessClient.archivedBy shouldEqual Some(owned.foreignOwnerUserId)
        organization.archivedAt must beSome
        organization.modifiedBy shouldEqual owned.ownerUserId
        foreignOrganization.archivedAt must beSome
        foreignOrganization.modifiedBy shouldEqual owned.foreignOwnerUserId
        awaitDb(
          organizationUserRepository
            .find(OrganizationUserFilters(organizationId = Some(owned.organizationId), userId = Some(owned.ownerUserId), includeArchived = true))
        ).flatMap(_.archivedAt) must beSome
        awaitDb(userRepository.find(UserFilters(id = Some(owned.ownerUserId), includeArchived = true))).flatMap(_.archivedAt) must beSome
      }
      success
    }
  }

  step {
    provisioned.credentials.discardRemaining().unsafeRunSync()
    dropFixtureTables()
    if (businessDbA != null) businessDbA.close()
    if (businessDbB != null) businessDbB.close()
  }

  private def provision(): Provisioned = {
    val runId = UUID.randomUUID()
    awaitDb(fixtureRepository.initialize(controllerConfig, Some(ControllerInstanceId(UUID.randomUUID())))) shouldEqual ControllerClaimed
    val response    = controlHandler.handle(Setup(runId), UUID.randomUUID(), requestContext()).unsafeRunSync()
    val credentials = credentialsFromSetup(response)
    new Provisioned(runId, credentials, awaitDb(fixtureRepository.ownershipRows).map(value => value.alias -> value).toMap)
  }

  private def credentialsFromSetup(response: ControlResult): OAuthE2EFixtureOneTimeCredentials = {
    require(response.status == StatusCodes.OK)
    val json    = requireResult(response, "applied")
    val entries = json.hcursor.downField("credentials").focus.flatMap(_.asArray).getOrElse(throw new AssertionError("Fixture credentials missing"))
    val values = entries.map { entry =>
      val cursor       = entry.hcursor
      val aliasValue   = cursor.get[String]("alias").toOption.get
      val clientId     = cursor.get[String]("clientId").toOption.get
      val clientSecret = cursor.get[String]("clientSecret").toOption.get
      val alias = FixtureAlias.values
        .find(_.databaseValue == aliasValue)
        .getOrElse(throw new AssertionError("Unknown fixture alias"))
      alias -> OAuthE2EFixtureCredential(OAuthClientPublicId(clientId), OAuthClientSecret(clientSecret))
    }.toMap
    require(values.keySet == FixtureAlias.values.toSet)
    new OAuthE2EFixtureOneTimeCredentials(values)
  }

  private def requireResult(response: ControlResult, expected: String): io.circe.Json = {
    val json = parse(response.json).fold(error => throw new AssertionError(error), identity)
    require(json.hcursor.get[String]("result").toOption.contains(expected))
    json
  }

  private def issue(
    instance: BusinessInstance,
    credential: OAuthE2EFixtureCredential,
    scope: Option[String],
    counter: AtomicInteger
  ): IO[String] = {
    counter.incrementAndGet()
    instance.tokens.issue(Some(credentials(credential)), scope, nextSource(), None).flatMap {
      case Issued(response) => IO.pure(response.access_token)
      case other            => IO.raiseError(new AssertionError(s"Token issuance failed: $other"))
    }
  }

  private def authenticate(instance: BusinessInstance, token: String): IO[OAuthAuthContext] =
    authenticateOption(instance, token).flatMap(value => IO.fromOption(value)(new AssertionError("Bearer authentication failed")))

  private def authenticateOption(instance: BusinessInstance, token: String): IO[Option[OAuthAuthContext]] =
    IO.fromFuture(IO(instance.authenticator(Some(OAuth2BearerToken(token))))).map(_.toOption)

  private def visibleProjectIds(
    instance: BusinessInstance,
    auth: OAuthAuthContext,
    owned: FixtureOwnership
  ): IO[Set[pl.iterators.epoca.project.domain.ProjectDomain.ProjectId]] =
    instance.projects.list(OAuthAccessPolicy.from(auth), organization(owned).slug, ProjectFilters()).flatMap {
      case Ok(value) => IO.pure(value.results.map(_.id).toSet)
      case other     => IO.raiseError(new AssertionError(s"Project list failed: $other"))
    }

  private def verifiedClaims(token: String): OAuthTokenClaims =
    jwtService.verifyClaims(token).fold(error => throw new AssertionError(s"Token verification failed: $error"), identity)

  private def organization(ownership: FixtureOwnership) =
    awaitDb(organizationRepository.find(OrganizationFilters(id = Some(ownership.organizationId), includeArchived = true))).get

  private def foreignOrganization(ownership: FixtureOwnership) =
    awaitDb(organizationRepository.find(OrganizationFilters(id = Some(ownership.foreignOrganizationId), includeArchived = true))).get

  private def projectListThroughNormalDispatch(token: String, organizationSlug: String)
    : (org.apache.pekko.http.scaladsl.model.StatusCode, String, String) =
    Get(s"/api/v1/organizations/$organizationSlug/projects") ~>
      Authorization(OAuth2BearerToken(token)) ~>
      Route.seal(dispatchLoader.routes) ~>
      check {
        (response.status, responseAs[String], response.headers.find(_.is("www-authenticate")).map(_.value()).getOrElse(""))
      }

  private lazy val dispatchRouter = new OAuthApiRouter(
    mock[OAuthOrganizationReader],
    mock[OAuthUserReader],
    mock[OAuthClientReader],
    instanceA.projects,
    mock[OAuthWorkUnitReader],
    mock[OAuthWorkUnitWriter],
    mock[OAuthReportReader],
    mock[OAuthExporter]
  )

  private lazy val dispatchLoader = new ApplicationLoader(
    ConfigFactory.load(),
    ec,
    ioRuntime,
    transactor,
    mock[WebSocketStreamBackend[IO, Fs2Streams[IO]]],
    mock[TriggeredJobScheduler],
    mock[cats.effect.std.Supervisor[IO]]
  ) {
    override lazy val userAuthenticator: UserAuthenticator = _ =>
      scala.concurrent.Future.successful(AuthenticationResult.failWithChallenge(HttpChallenge("Bearer", "epoca-backend")))
    override lazy val oauthPrincipalAuthenticator: OAuthPrincipalAuthenticator                      = instanceA.authenticator
    override def oauthRoute(auth: OAuthAuthContext)(implicit tracingContext: TracingContext): Route = dispatchRouter.routes(auth)
  }

  private def credentials(credential: OAuthE2EFixtureCredential): OAuthClientCredentials =
    OAuthClientCredentials(credential.clientId, credential.clientSecret)

  private def businessInstance(database: Database): BusinessInstance = {
    val instanceTransactor = new PostgresIOTransactor(database)
    val clients            = new OAuthClientRepositoryImpl(authoritativeTime)
    val audits             = new OAuthAuditRepositoryImpl()
    val tokens = new OAuthTokenService(
      instanceTransactor,
      authoritativeTime,
      clients,
      audits,
      new OAuthRateLimitRepositoryImpl(OAuthRateLimit(100, 60.seconds), OAuthRateLimit(20, 300.seconds)),
      secretService,
      jwtService,
      issuer,
      audience
    )
    val authenticator = new OAuthAuthenticator(instanceTransactor, authoritativeTime, jwtService, clients, serviceAccountRepository)
    val projects = new OAuthProjectReadAdapter(
      instanceTransactor,
      organizationRepository,
      projectRepository,
      projectUserRepository,
      clientRepository,
      userRepository
    )
    new BusinessInstance(tokens, authenticator, projects)
  }

  private def requestContext(): ControlRequestContext = ControlRequestContext(ControlDeadline.command(System.nanoTime()))

  private def cleanupRequestContext(): ControlRequestContext = ControlRequestContext(ControlDeadline.cleanup(System.nanoTime()))

  private def awaitDb[A](action: DBIO[A]): A = Await.result(db.run(action), Duration.Inf)

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

  private def independentDatabase(name: String): Database =
    Database.forURL(
      url = sys.env.getOrElse("TEST_DB_URL", "epoca"),
      user = sys.env.getOrElse("TEST_DB_USER", "postgres"),
      password = sys.env.getOrElse("TEST_DB_PASSWORD", "postgres"),
      driver = "org.postgresql.Driver",
      executor = AsyncExecutor(s"$name-${UUID.randomUUID()}", minThreads = 1, maxThreads = 1, queueSize = 20, maxConnections = 1),
      prop = new Properties()
    )

  private def nextSource(): InetAddress = {
    val value = UUID.randomUUID()
    val bytes = ByteBuffer.allocate(16).putLong(value.getMostSignificantBits).putLong(value.getLeastSignificantBits).array()
    bytes(0) = 0xfd.toByte
    InetAddress.getByAddress(bytes)
  }

  private val applicationConfig = ConfigFactory
    .parseResources("oauth-e2e.conf")
    .withFallback(ConfigFactory.parseResources("application.conf"))
    .resolve(ConfigResolveOptions.defaults().setAllowUnresolved(true))

  private lazy val controllerConfig: OAuthE2EFixtureConfig = requireConfig(
    ConfigFactory
      .parseString(s"""
        app.environment = "oauth-e2e"
        db.url = "jdbc:postgresql://127.0.0.1:55422/oauth_e2e_repository"
        db.password = "oauth-e2e-integration-database-secret"
        login.jwtSecret = "oauth-e2e-integration-login-secret"
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

  private final class Provisioned(
    val runId: UUID,
    val credentials: OAuthE2EFixtureOneTimeCredentials,
    val ownership: Map[FixtureAlias, FixtureOwnership])

  private final class BusinessInstance(
    val tokens: OAuthTokenService,
    val authenticator: OAuthAuthenticator,
    val projects: OAuthProjectReadAdapter)

}
