package pl.iterators.epoca.oauth.routers

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.model.headers.OAuth2BearerToken
import org.apache.pekko.http.scaladsl.server.Directives.{authenticateOrRejectWithChallenge, complete}
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.{RouteTestTimeout, Specs2RouteTest}
import org.mindrot.jbcrypt.BCrypt
import org.slf4j.LoggerFactory
import pl.iterators.epoca.oauth.domain.OAuthDomain._
import pl.iterators.epoca.oauth.repositories.{OAuthAuditRepositoryImpl, OAuthClientRepositoryImpl, OAuthRateLimitRepositoryImpl}
import pl.iterators.epoca.oauth.services.OAuthClientManagementService.OAuthClientManagementResult.Success
import pl.iterators.epoca.oauth.services.OAuthClientManagementService.{
  OAuthClientCreateRequest,
  OAuthClientSecretView,
  OAuthClientUpdateRequest,
  OAuthClientVersionRequest,
  OAuthExpirationPatch
}
import pl.iterators.epoca.oauth.services.OAuthConfig.{OAuthRateLimit, OAuthSigningKey, OAuthSigningKeyRing}
import pl.iterators.epoca.oauth.services.OAuthTokenService.OAuthTokenResult.Issued
import pl.iterators.epoca.oauth.services._
import pl.iterators.epoca.oauth.time.{OAuthAuthoritativeTime, PostgresOAuthAuthoritativeTime}
import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationUserId, OrganizationUserRole}
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.organization.repositories.{OrganizationRepositoryImpl, OrganizationUserRepositoryImpl}
import pl.iterators.epoca.project.repositories.ProjectRepositoryImpl
import pl.iterators.epoca.testsupport.generators.Generator
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.domain.UserDomain.{UserAccountType, UserIsSuperAdmin}
import pl.iterators.epoca.user.repositories.{ServiceAccountRepositoryImpl, UserRepositoryImpl}
import pl.iterators.epoca.utils.db.BaseRepository.driver.api._
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.{AuthContextProvider, PgRepositorySpec}
import slick.util.AsyncExecutor

import java.net.InetAddress
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.{Properties, UUID}
import scala.concurrent.duration.{Duration, DurationInt, FiniteDuration}
import scala.concurrent.{Await, Promise}
import scala.jdk.CollectionConverters._
import scala.util.Try

class OAuthAuthenticatorSpec extends PgRepositorySpec with Specs2RouteTest {
  private implicit val runtime: IORuntime                 = IORuntime.global
  private implicit val routeTestTimeout: RouteTestTimeout = RouteTestTimeout(10.seconds)
  sequential

  private val userRepository             = new UserRepositoryImpl()
  private val serviceAccountRepository   = new ServiceAccountRepositoryImpl()
  private val organizationRepository     = new OrganizationRepositoryImpl()
  private val organizationUserRepository = new OrganizationUserRepositoryImpl()
  private val projectRepository          = new ProjectRepositoryImpl()
  private val authoritativeTime          = new PostgresOAuthAuthoritativeTime()
  private val oauthClientRepository      = new OAuthClientRepositoryImpl(authoritativeTime)
  private val oauthAuditRepository       = new OAuthAuditRepositoryImpl()
  private val oauthRateLimitRepository   = new OAuthRateLimitRepositoryImpl(OAuthRateLimit(60, 60.seconds), OAuthRateLimit(10, 300.seconds))
  private val transactor                 = new PostgresIOTransactor(db)
  private val secretService = new OAuthSecretService(OAuthClientSecretHash(BCrypt.hashpw("oauth-authenticator-dummy", BCrypt.gensalt(12))))
  private val grantabilityService =
    new OAuthGrantabilityService(userRepository, organizationRepository, organizationUserRepository, projectRepository)
  private val managementService =
    new OAuthClientManagementService(
      transactor,
      authoritativeTime,
      grantabilityService,
      serviceAccountRepository,
      oauthClientRepository,
      oauthAuditRepository,
      userRepository,
      secretService
    )
  private val keyRing =
    OAuthSigningKeyRing(
      "oauth-authenticator",
      Vector(OAuthSigningKey("oauth-authenticator", "Y3Q1MjAtYXV0aGVudGljYXRvci1zaWduaW5nLWtleS0wMQ", retired = false))
    )
  private val jwtService = new OAuthJwtService("https://staging.epoca.example.test/oauth", "epoca-api", keyRing)
  private val tokenService = new OAuthTokenService(
    transactor,
    authoritativeTime,
    oauthClientRepository,
    oauthAuditRepository,
    oauthRateLimitRepository,
    secretService,
    jwtService,
    "https://staging.epoca.example.test/oauth",
    "epoca-api"
  )
  private val sut = new OAuthAuthenticator(transactor, authoritativeTime, jwtService, oauthClientRepository, serviceAccountRepository)

  private val userGenerator: Generator[User]                 = allGenerators[User].normal
  private val organizationGenerator: Generator[Organization] = allGenerators[Organization].normal

  "OAuth bearer authentication" should {
    "create an OAuth-only context from valid signed claims and current live policy" in {
      val owner        = newHuman()
      val organization = organizationGenerator.generate.copy(createdBy = owner.id, archivedAt = None)
      insertOwner(organization, owner)
      val created     = createClient(owner, organization, Set(OAuthScope.ProjectsRead))
      val token       = issue(created, requestedScope = None, "192.0.2.31")
      val before      = run(oauthClientRepository.findPublic(created.client.clientId)).get
      val issuedAt    = jwtService.verifyClaims(token).toOption.getOrElse(throw new AssertionError("expected valid token claims")).issuedAt
      val firstUseAt  = issuedAt.plusSeconds(1L)
      val secondUseAt = firstUseAt.plusSeconds(1L)
      def authenticatorAt(at: java.time.Instant) = {
        val time       = new MutableOAuthAuthoritativeTime(at)
        val repository = new OAuthClientRepositoryImpl(time)
        new OAuthAuthenticator(transactor, time, jwtService, repository, serviceAccountRepository)
      }

      val result   = Await.result(authenticatorAt(firstUseAt)(Some(OAuth2BearerToken(token))), 10.seconds)
      val repeated = Await.result(authenticatorAt(secondUseAt)(Some(OAuth2BearerToken(token))), 10.seconds)
      val context  = result.toOption.getOrElse(throw new AssertionError("expected OAuth authentication success"))
      val snapshot = run(oauthClientRepository.findPublic(created.client.clientId)).get

      (before.client.lastUsedAt.isEmpty should beTrue) and
        (repeated.isRight should beTrue) and
        (snapshot.client.lastUsedAt.contains(OAuthClientLastUsedAt(firstUseAt)) should beTrue) and
        (context.actorId shouldEqual snapshot.client.serviceAccountUserId) and
        (context.clientId shouldEqual snapshot.client.id) and
        (context.organizationId shouldEqual organization.id) and
        (context.scopes shouldEqual Set(OAuthScope.ProjectsRead)) and
        (context.projectAccess shouldEqual OAuthProjectAccess.AllProjects) and
        (context.policyVersion shouldEqual created.client.policyVersion)
    }

    "reject a stale policy version even when token scopes remain a subset of current grants" in {
      val owner        = newHuman()
      val organization = organizationGenerator.generate.copy(createdBy = owner.id, archivedAt = None)
      insertOwner(organization, owner)
      val created = createClient(owner, organization, Set(OAuthScope.ProjectsRead, OAuthScope.ReportsRead))
      val token   = issue(created, requestedScope = Some("projects:read"), "192.0.2.32")
      val update = OAuthClientUpdateRequest(
        expectedVersion = created.client.version,
        name = None,
        scopes = Some(Set(OAuthScope.ProjectsRead)),
        projectAccess = None
      )

      managementService
        .update(auth(owner), organization.slug, created.client.clientId, update)
        .unsafeRunSync() must beAnInstanceOf[Success[_]]
      val result = Await.result(sut(Some(OAuth2BearerToken(token))), 10.seconds)

      result.isLeft should beTrue
    }

    "reject exact-expiry and revoked clients with the same challenge without recording use" in {
      val expiryOwner        = newHuman()
      val expiryOrganization = organizationGenerator.generate.copy(createdBy = expiryOwner.id, archivedAt = None)
      insertOwner(expiryOrganization, expiryOwner)
      val expiresAt               = OAuthClientExpiresAt(run(authoritativeTime.now).plusSeconds(120L))
      val expiring                = createClient(expiryOwner, expiryOrganization, Set(OAuthScope.ProjectsRead), expiresAt = Some(expiresAt))
      val expiringToken           = issue(expiring, requestedScope = None, "192.0.2.33")
      val exactBoundaryTime       = new MutableOAuthAuthoritativeTime(expiresAt.asInstanceOf[java.time.Instant])
      val exactBoundaryRepository = new OAuthClientRepositoryImpl(exactBoundaryTime)
      val exactBoundaryAuthenticator =
        new OAuthAuthenticator(transactor, exactBoundaryTime, jwtService, exactBoundaryRepository, serviceAccountRepository)

      val revokedOwner        = newHuman()
      val revokedOrganization = organizationGenerator.generate.copy(createdBy = revokedOwner.id, archivedAt = None)
      insertOwner(revokedOrganization, revokedOwner)
      val revoked      = createClient(revokedOwner, revokedOrganization, Set(OAuthScope.ProjectsRead))
      val revokedToken = issue(revoked, requestedScope = None, "192.0.2.34")
      managementService
        .revoke(auth(revokedOwner), revokedOrganization.slug, revoked.client.clientId, OAuthClientVersionRequest(revoked.client.version))
        .unsafeRunSync() must beAnInstanceOf[Success[_]]

      val expiredResult = Await.result(exactBoundaryAuthenticator(Some(OAuth2BearerToken(expiringToken))), 10.seconds)
      val revokedResult = Await.result(sut(Some(OAuth2BearerToken(revokedToken))), 10.seconds)
      val expiredAfter  = run(oauthClientRepository.findPublic(expiring.client.clientId)).get
      val revokedAfter  = run(oauthClientRepository.findPublic(revoked.client.clientId)).get

      (expiredResult.isLeft should beTrue) and
        (revokedResult shouldEqual expiredResult) and
        (expiredAfter.client.lastUsedAt should beNone) and
        (revokedAfter.client.lastUsedAt should beNone)
    }

    "keep tokens invalid after clearing or extending expiry changes policy version" in {
      val owner        = newHuman()
      val organization = organizationGenerator.generate.copy(createdBy = owner.id, archivedAt = None)
      insertOwner(organization, owner)
      val firstExpiry      = OAuthClientExpiresAt(run(authoritativeTime.now).plusSeconds(120L))
      val created          = createClient(owner, organization, Set(OAuthScope.ProjectsRead), expiresAt = Some(firstExpiry))
      val beforeClearToken = issue(created, requestedScope = None, "192.0.2.35")
      val cleared = managementService
        .update(
          auth(owner),
          organization.slug,
          created.client.clientId,
          OAuthClientUpdateRequest(created.client.version, None, None, None, OAuthExpirationPatch.Clear)
        )
        .unsafeRunSync() match {
        case Success(value) => value
        case other          => throw new AssertionError(s"expected cleared expiry, got $other")
      }
      val afterClearToken = issue(created.copy(client = cleared), requestedScope = None, "192.0.2.36")
      val extendedExpiry  = OAuthClientExpiresAt(run(authoritativeTime.now).plusSeconds(240L))
      managementService
        .update(
          auth(owner),
          organization.slug,
          created.client.clientId,
          OAuthClientUpdateRequest(cleared.version, None, None, None, OAuthExpirationPatch.Set(extendedExpiry))
        )
        .unsafeRunSync() must beAnInstanceOf[Success[_]]

      val beforeClearResult = Await.result(sut(Some(OAuth2BearerToken(beforeClearToken))), 10.seconds)
      val afterClearResult  = Await.result(sut(Some(OAuth2BearerToken(afterClearToken))), 10.seconds)
      val after             = run(oauthClientRepository.findPublic(created.client.clientId)).get

      (beforeClearResult.isLeft should beTrue) and
        (afterClearResult.isLeft should beTrue) and
        (after.client.lastUsedAt.isEmpty should beTrue)
    }

    "record successful authentication before a downstream business rejection" in {
      val owner        = newHuman()
      val organization = organizationGenerator.generate.copy(createdBy = owner.id, archivedAt = None)
      insertOwner(organization, owner)
      val created = createClient(owner, organization, Set(OAuthScope.ProjectsRead))
      val token   = issue(created, requestedScope = None, "192.0.2.37")
      val route = Route.seal {
        authenticateOrRejectWithChallenge(sut)(_ => complete(StatusCodes.NotFound))
      }

      Get("/missing") ~> addCredentials(OAuth2BearerToken(token)) ~> route ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }
      val after = run(oauthClientRepository.findPublic(created.client.clientId)).get

      after.client.lastUsedAt should beSome
    }

    "keep successful authentication when the observational touch fails and log no credentials or identity" in {
      val owner        = newHuman()
      val organization = organizationGenerator.generate.copy(createdBy = owner.id, archivedAt = None)
      insertOwner(organization, owner)
      val created         = createClient(owner, organization, Set(OAuthScope.ProjectsRead))
      val token           = issue(created, requestedScope = None, "192.0.2.38")
      val failureSentinel = s"touch-failure-${created.client.clientId}"
      val failingTouchRepository = new OAuthClientRepositoryImpl(authoritativeTime) {
        override def touchLastUsedAt(
          clientId: OAuthClientId,
          now: java.time.Instant,
          minInterval: FiniteDuration
        ): DBIO[Boolean] =
          DBIO.failed(new RuntimeException(failureSentinel))
      }
      val authenticator =
        new OAuthAuthenticator(transactor, authoritativeTime, jwtService, failingTouchRepository, serviceAccountRepository)
      val logger   = LoggerFactory.getLogger(classOf[OAuthAuthenticator]).asInstanceOf[ch.qos.logback.classic.Logger]
      val oldLevel = logger.getLevel
      val appender = new ListAppender[ILoggingEvent]
      appender.start()
      logger.setLevel(Level.WARN)
      logger.addAppender(appender)

      try {
        val results  = Seq.fill(2)(Await.result(authenticator(Some(OAuth2BearerToken(token))), 10.seconds))
        val messages = appender.list.asScala.map(_.getFormattedMessage).toSeq
        val logs     = messages.mkString("\n")
        val forbidden = Seq(
          token,
          created.clientSecret,
          created.client.clientId.toString,
          created.client.serviceAccount.userId.toString,
          organization.id.toString,
          failureSentinel,
          "Authorization"
        )

        (results.forall(_.isRight) should beTrue) and
          (messages.count(_ == "OAuth bearer usage timestamp update failed") shouldEqual 1) and
          (forbidden.forall(value => !logs.contains(value)) should beTrue)
      } finally {
        logger.detachAppender(appender)
        logger.setLevel(oldLevel)
        appender.stop()
      }
    }

    "complete valid authentication without waiting for a locked usage row" in {
      val owner        = newHuman()
      val organization = organizationGenerator.generate.copy(createdBy = owner.id, archivedAt = None)
      insertOwner(organization, owner)
      val created          = createClient(owner, organization, Set(OAuthScope.ProjectsRead))
      val token            = issue(created, requestedScope = None, "192.0.2.41")
      val clientId         = run(oauthClientRepository.findPublic(created.client.clientId)).get.client.id
      val lockDb           = independentDatabase("oauth-auth-touch-lock-holder")
      val authDb           = independentDatabase("oauth-auth-touch-lock-skip")
      val acquired         = Promise[Unit]()
      val release          = Promise[Unit]()
      val holderRepository = new OAuthClientRepositoryImpl(authoritativeTime)
      val holder = lockDb.run((for {
        locked <- holderRepository.lock(clientId)
        _      <- if (locked.nonEmpty) DBIO.successful(()) else DBIO.failed(new AssertionError("expected OAuth client row"))
        _ = acquired.trySuccess(())
        _ <- DBIO.from(release.future)
      } yield ()).transactionally)
      val authenticator = new OAuthAuthenticator(
        new PostgresIOTransactor(authDb),
        authoritativeTime,
        jwtService,
        new OAuthClientRepositoryImpl(authoritativeTime),
        serviceAccountRepository
      )
      val logger   = LoggerFactory.getLogger(classOf[OAuthAuthenticator]).asInstanceOf[ch.qos.logback.classic.Logger]
      val oldLevel = logger.getLevel
      val appender = new ListAppender[ILoggingEvent]
      appender.start()
      logger.setLevel(Level.WARN)
      logger.addAppender(appender)

      try {
        Await.result(acquired.future, 10.seconds)
        val firstFuture       = authenticator(Some(OAuth2BearerToken(token)))
        val completedPromptly = Try(Await.result(firstFuture, 1.second)).toOption.exists(_.isRight)
        val whileLocked       = run(oauthClientRepository.findPublic(created.client.clientId)).get

        release.success(())
        Await.result(holder, 10.seconds)
        val firstResult = Await.result(firstFuture, 10.seconds)
        val beforeLater = run(oauthClientRepository.findPublic(created.client.clientId)).get
        val laterResult = Await.result(authenticator(Some(OAuth2BearerToken(token))), 10.seconds)
        val afterLater  = run(oauthClientRepository.findPublic(created.client.clientId)).get
        val messages    = appender.list.asScala.map(_.getFormattedMessage).toSeq

        (completedPromptly should beTrue) and
          (firstResult.isRight should beTrue) and
          (whileLocked.client.lastUsedAt.isEmpty should beTrue) and
          (beforeLater.client.lastUsedAt.isEmpty should beTrue) and
          (laterResult.isRight should beTrue) and
          (afterLater.client.lastUsedAt.nonEmpty should beTrue) and
          (messages.count(_ == "OAuth bearer usage timestamp update failed") shouldEqual 0)
      } finally {
        release.trySuccess(())
        val _ = Try(Await.result(holder, 10.seconds))
        logger.detachAppender(appender)
        logger.setLevel(oldLevel)
        appender.stop()
        lockDb.close()
        authDb.close()
      }
    }

    "start a separate touch transaction only when the snapshot is outside the usage window" in {
      val owner        = newHuman()
      val organization = organizationGenerator.generate.copy(createdBy = owner.id, archivedAt = None)
      insertOwner(organization, owner)
      val created  = createClient(owner, organization, Set(OAuthScope.ProjectsRead))
      val token    = issue(created, requestedScope = None, "192.0.2.39")
      val issuedAt = jwtService.verifyClaims(token).toOption.getOrElse(throw new AssertionError("expected valid token claims")).issuedAt
      val now      = issuedAt.plusSeconds(1L)
      val cases = Seq(
        "first use"      -> Option.empty[OAuthClientLastUsedAt],
        "inside window"  -> Some(OAuthClientLastUsedAt(now.minusSeconds(299L))),
        "exact boundary" -> Some(OAuthClientLastUsedAt(now.minusSeconds(300L)))
      )

      val observed = cases.map { case (label, lastUsedAt) =>
        val time               = new MutableOAuthAuthoritativeTime(now)
        val repository         = new RecordingTouchRepository(time, lastUsedAt)
        val countingTransactor = new CountingTransactor
        val authenticator      = new OAuthAuthenticator(countingTransactor, time, jwtService, repository, serviceAccountRepository)
        val result             = Await.result(authenticator(Some(OAuth2BearerToken(token))), 10.seconds)

        label -> (result.isRight, repository.touchCalls.get(), countingTransactor.transactionCalls.get())
      }

      observed shouldEqual Seq("first use" -> (true, 1, 2), "inside window" -> (true, 0, 1), "exact boundary" -> (true, 1, 2))
    }

    "fail closed on primary authentication database failures without attempting usage touch" in {
      val owner        = newHuman()
      val organization = organizationGenerator.generate.copy(createdBy = owner.id, archivedAt = None)
      insertOwner(organization, owner)
      val created            = createClient(owner, organization, Set(OAuthScope.ProjectsRead))
      val token              = issue(created, requestedScope = None, "192.0.2.40")
      val failure            = new RuntimeException("oauth-primary-auth-db-failure")
      val repository         = new FailingPrimaryRepository(failure)
      val countingTransactor = new CountingTransactor
      val authenticator =
        new OAuthAuthenticator(countingTransactor, authoritativeTime, jwtService, repository, serviceAccountRepository)

      val completed = Await.result(authenticator(Some(OAuth2BearerToken(token))), 10.seconds)

      (completed.isLeft should beTrue) and
        (repository.touchCalls.get() shouldEqual 0) and
        (countingTransactor.transactionCalls.get() shouldEqual 1)
    }

    "reject a token exactly at expiry using the injected authoritative time" in {
      val owner        = newHuman()
      val organization = organizationGenerator.generate.copy(createdBy = owner.id, archivedAt = None)
      insertOwner(organization, owner)
      val created  = createClient(owner, organization, Set(OAuthScope.ProjectsRead))
      val issuedAt = Instant.parse("2032-03-04T05:06:07Z")
      val testTime = new MutableOAuthAuthoritativeTime(issuedAt)
      val timedIssuer = new OAuthTokenService(
        transactor,
        testTime,
        oauthClientRepository,
        oauthAuditRepository,
        oauthRateLimitRepository,
        secretService,
        jwtService,
        "https://staging.epoca.example.test/oauth",
        "epoca-api"
      )
      val timedAuthenticator =
        new OAuthAuthenticator(transactor, testTime, jwtService, oauthClientRepository, serviceAccountRepository)
      val token = timedIssuer
        .issue(
          Some(OAuthClientCredentials(created.client.clientId, OAuthClientSecret(created.clientSecret))),
          requestedScope = None,
          InetAddress.getByName("192.0.2.120"),
          correlationId = Some(OAuthAuditCorrelationId("oauth-authoritative-expiry"))
        )
        .unsafeRunSync() match {
        case Issued(response) => response.access_token
        case other            => throw new AssertionError(s"expected issued token, got $other")
      }

      testTime.current = issuedAt.plusSeconds(300L)

      Await.result(timedAuthenticator(Some(OAuth2BearerToken(token))), 10.seconds).isLeft should beTrue
    }

    "return the same generic challenge for a human or malformed bearer token" in {
      val humanLike = Await.result(sut(Some(OAuth2BearerToken("oauth-human-jwt-sentinel"))), 10.seconds)
      val malformed = Await.result(sut(Some(OAuth2BearerToken("not-a-compact-token"))), 10.seconds)

      (humanLike.isLeft should beTrue) and
        (malformed shouldEqual humanLike)
    }
  }

  private def newHuman(): User =
    userGenerator.generate.copy(accountType = UserAccountType.Human, archivedAt = None, isSuperAdmin = UserIsSuperAdmin(false))

  private def insertOwner(organization: Organization, owner: User): Unit = {
    run(userRepository.insert(owner))
    run(organizationRepository.insert(organization))
    run(
      organizationUserRepository.upsert(OrganizationUser(OrganizationUserId.random, organization.id, owner.id, Seq(OrganizationUserRole.Owner), None))
    )
    ()
  }

  private def createClient(
    owner: User,
    organization: Organization,
    scopes: Set[OAuthScope],
    expiresAt: Option[OAuthClientExpiresAt] = None
  ): OAuthClientSecretView =
    managementService
      .create(
        auth(owner),
        organization.slug,
        OAuthClientCreateRequest("Authenticator integration", scopes, OAuthProjectAccess.AllProjects, expiresAt)
      )
      .unsafeRunSync() match {
      case Success(value) => value
      case other          => throw new AssertionError(s"expected created client, got $other")
    }

  private def issue(
    created: OAuthClientSecretView,
    requestedScope: Option[String],
    source: String
  ): String =
    tokenService
      .issue(
        Some(OAuthClientCredentials(created.client.clientId, OAuthClientSecret(created.clientSecret))),
        requestedScope,
        InetAddress.getByName(source),
        correlationId = None
      )
      .unsafeRunSync() match {
      case Issued(response) => response.access_token
      case other            => throw new AssertionError(s"expected issued token, got $other")
    }

  private def auth(user: User) =
    AuthContextProvider.user.copy(id = user.id, isSuperAdmin = user.isSuperAdmin, identities = Nil, roles = Nil)

  private def run[A](action: slick.dbio.DBIO[A]): A = Await.result(db.run(action), Duration.Inf)

  private def independentDatabase(name: String): Database =
    Database.forURL(
      url = sys.env.getOrElse("TEST_DB_URL", "epoca"),
      user = sys.env.getOrElse("TEST_DB_USER", "postgres"),
      password = sys.env.getOrElse("TEST_DB_PASSWORD", "postgres"),
      driver = "org.postgresql.Driver",
      executor = AsyncExecutor(s"$name-${UUID.randomUUID()}", minThreads = 1, maxThreads = 1, queueSize = 20, maxConnections = 1),
      prop = new Properties()
    )

  private final class CountingTransactor extends PostgresIOTransactor(db) {
    val transactionCalls = new AtomicInteger(0)

    override def executeTransactionally[T](action: => DBIO[T]): IO[T] = {
      transactionCalls.incrementAndGet()
      super.executeTransactionally(action)
    }
  }

  private final class RecordingTouchRepository(time: OAuthAuthoritativeTime, forcedLastUsedAt: Option[OAuthClientLastUsedAt])
      extends OAuthClientRepositoryImpl(time) {
    val touchCalls = new AtomicInteger(0)

    override def findPublic(clientId: OAuthClientPublicId)
      : DBIO[Option[pl.iterators.epoca.oauth.repositories.OAuthClientRepository.OAuthClientSnapshot]] =
      super.findPublic(clientId).map(_.map(snapshot => snapshot.copy(client = snapshot.client.copy(lastUsedAt = forcedLastUsedAt))))

    override def touchLastUsedAt(
      clientId: OAuthClientId,
      now: java.time.Instant,
      minInterval: FiniteDuration
    ): DBIO[Boolean] = {
      touchCalls.incrementAndGet()
      DBIO.successful(true)
    }
  }

  private final class FailingPrimaryRepository(failure: Throwable) extends OAuthClientRepositoryImpl(authoritativeTime) {
    val touchCalls = new AtomicInteger(0)

    override def findPublic(clientId: OAuthClientPublicId)
      : DBIO[Option[pl.iterators.epoca.oauth.repositories.OAuthClientRepository.OAuthClientSnapshot]] =
      DBIO.failed(failure)

    override def touchLastUsedAt(
      clientId: OAuthClientId,
      now: java.time.Instant,
      minInterval: FiniteDuration
    ): DBIO[Boolean] = {
      touchCalls.incrementAndGet()
      DBIO.successful(true)
    }
  }

  private final class MutableOAuthAuthoritativeTime(@volatile var current: Instant) extends OAuthAuthoritativeTime {
    override def now: slick.dbio.DBIO[Instant] = slick.dbio.DBIO.successful(current)
  }
}
