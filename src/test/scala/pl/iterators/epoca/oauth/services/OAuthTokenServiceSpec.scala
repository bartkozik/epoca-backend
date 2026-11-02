package pl.iterators.epoca.oauth.services

import cats.effect.unsafe.IORuntime
import org.mindrot.jbcrypt.BCrypt
import pl.iterators.epoca.oauth.domain.OAuthDomain._
import pl.iterators.epoca.oauth.repositories.OAuthAuditRepository.{NewOAuthAuditEvent, OAuthAuditEventFilters}
import pl.iterators.epoca.oauth.repositories.OAuthRateLimitRepository.RateLimitDecision
import pl.iterators.epoca.oauth.repositories.tables.OAuthAuditEventRow
import pl.iterators.epoca.oauth.repositories.{
  OAuthAuditRepository,
  OAuthAuditRepositoryImpl,
  OAuthClientRepository,
  OAuthClientRepositoryImpl,
  OAuthRateLimitRepository,
  OAuthRateLimitRepositoryImpl
}
import pl.iterators.epoca.oauth.services.OAuthClientManagementService.OAuthClientManagementResult.Success
import pl.iterators.epoca.oauth.services.OAuthClientManagementService.{
  OAuthClientCreateRequest,
  OAuthClientSecretView,
  OAuthClientUpdateRequest,
  OAuthClientVersionRequest,
  OAuthExpirationPatch
}
import pl.iterators.epoca.oauth.services.OAuthConfig.{OAuthRateLimit, OAuthSigningKey, OAuthSigningKeyRing}
import pl.iterators.epoca.oauth.services.OAuthTokenService.OAuthTokenResult._
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
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.{Base64, Properties, UUID}
import scala.concurrent.duration.{Duration, DurationInt}
import scala.concurrent.{Await, Promise, TimeoutException}
import scala.util.Try

class OAuthTokenServiceSpec extends PgRepositorySpec {
  private implicit val ioRuntime: IORuntime = IORuntime.global

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
  private val secretService              = new OAuthSecretService(OAuthClientSecretHash(BCrypt.hashpw("oauth-token-dummy", BCrypt.gensalt(12))))
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
    OAuthSigningKeyRing("oauth-primary", Vector(OAuthSigningKey("oauth-primary", "Y3Q1MjAtb2F1dGgtc2lnbmluZy1rZXktMDEyMzQ1Njc4OQ", retired = false)))
  private val jwtService = new OAuthJwtService("https://staging.epoca.example.test/oauth", "epoca-api", keyRing)
  private val sut        = tokenService()

  private val userGenerator: Generator[User]                 = allGenerators[User].normal
  private val organizationGenerator: Generator[Organization] = allGenerators[Organization].normal

  "token issuance" should {
    "issue the granted scope for a valid client and persist a safe success audit" in {
      val owner        = newHuman()
      val organization = organizationGenerator.generate.copy(createdBy = owner.id, archivedAt = None)
      insertOwner(organization, owner)
      val created = createClient(owner, organization)
      val source  = InetAddress.getByName("192.0.2.20")

      val result = sut
        .issue(
          Some(OAuthClientCredentials(created.client.clientId, OAuthClientSecret(created.clientSecret))),
          requestedScope = None,
          source,
          correlationId = Some(OAuthAuditCorrelationId("oauth-token-issued"))
        )
        .unsafeRunSync()

      val response = result match {
        case Issued(value) => value
        case other         => throw new AssertionError(s"expected Issued, got $other")
      }
      val authoritativeNow = run(authoritativeTime.now)
      val claims = jwtService.verify(response.access_token, authoritativeNow) match {
        case Right(value) => value
        case Left(error)  => throw new AssertionError(s"expected verifiable token, got $error")
      }
      val clientSnapshot = run(oauthClientRepository.findPublic(created.client.clientId)).get
      val auditEvents    = run(oauthAuditRepository.list(OAuthAuditEventFilters(clientSnapshot.client.id)))

      (response.token_type shouldEqual "Bearer") and
        (response.expires_in shouldEqual 300) and
        (response.scope shouldEqual "projects:read") and
        (claims.scopes shouldEqual Set(OAuthScope.ProjectsRead)) and
        (claims.policyVersion shouldEqual created.client.policyVersion) and
        (auditEvents.count(_.eventType == OAuthAuditEventType.TokenIssued) shouldEqual 1) and
        (auditEvents.find(_.eventType == OAuthAuditEventType.TokenIssued).get.metadata.noSpaces must not(contain(created.clientSecret)))
    }

    "issue before expiration from the fresh post-lock database time" in {
      val owner        = newHuman()
      val organization = organizationGenerator.generate.copy(createdBy = owner.id, archivedAt = None)
      insertOwner(organization, owner)
      val expiresAt  = OAuthClientExpiresAt(Instant.parse("2036-08-24T10:00:00Z"))
      val created    = createClient(owner, organization, Some(expiresAt))
      val preLock    = expiresAt.asInstanceOf[Instant].minusSeconds(3L)
      val postLock   = expiresAt.asInstanceOf[Instant].minusSeconds(2L).plusNanos(123456000L)
      val repository = new SequencedNowOAuthClientRepository(Vector(preLock, postLock))
      val service    = tokenService(clientRepository = repository, time = repository.time)

      val result = service
        .issue(
          Some(OAuthClientCredentials(created.client.clientId, OAuthClientSecret(created.clientSecret))),
          requestedScope = None,
          InetAddress.getByName("192.0.2.30"),
          correlationId = Some(OAuthAuditCorrelationId("oauth-token-before-expiry"))
        )
        .unsafeRunSync()
      val response = result match {
        case Issued(value) => value
        case other         => throw new AssertionError(s"expected Issued, got $other")
      }
      val claims   = jwtService.verifyClaims(response.access_token).toOption.get
      val snapshot = run(oauthClientRepository.findPublic(created.client.clientId)).get
      val audit = run(oauthAuditRepository.list(OAuthAuditEventFilters(snapshot.client.id)))
        .find(_.eventType == OAuthAuditEventType.TokenIssued)
        .get
      val issuedAt = postLock.truncatedTo(java.time.temporal.ChronoUnit.SECONDS)

      (repository.authoritativeNowCalls shouldEqual 2) and
        (claims.issuedAt shouldEqual issuedAt) and
        (claims.expiresAt shouldEqual issuedAt.plusSeconds(OAuthJwtService.TokenLifetimeSeconds)) and
        (audit.occurredAt.asInstanceOf[Instant] shouldEqual postLock)
    }

    "reject the exact expiration boundary and audit expired-client internally" in {
      val owner        = newHuman()
      val organization = organizationGenerator.generate.copy(createdBy = owner.id, archivedAt = None)
      insertOwner(organization, owner)
      val expiresAt  = OAuthClientExpiresAt(Instant.parse("2036-08-24T11:00:00Z"))
      val created    = createClient(owner, organization, Some(expiresAt))
      val repository = new SequencedNowOAuthClientRepository(Vector(expiresAt.asInstanceOf[Instant]))
      val service    = tokenService(clientRepository = repository, time = repository.time)

      val result = service
        .issue(
          Some(OAuthClientCredentials(created.client.clientId, OAuthClientSecret(created.clientSecret))),
          requestedScope = None,
          InetAddress.getByName("192.0.2.31"),
          correlationId = Some(OAuthAuditCorrelationId("oauth-token-expiry-boundary"))
        )
        .unsafeRunSync()

      val snapshot = run(oauthClientRepository.findPublic(created.client.clientId)).get
      val events = run(oauthAuditRepository.list(OAuthAuditEventFilters(snapshot.client.id)))
        .filter(_.eventType == OAuthAuditEventType.InvalidClient)

      (result shouldEqual InvalidClient) and
        (repository.authoritativeNowCalls shouldEqual 2) and
        (events.map(_.metadata.hcursor.get[String]("failureCategory")) should contain(Right("expired-client"))) and
        (events.map(_.eventType).contains(OAuthAuditEventType.TokenIssued) should beFalse)
    }

    "give revoked precedence over expiration during token issuance" in {
      val owner        = newHuman()
      val organization = organizationGenerator.generate.copy(createdBy = owner.id, archivedAt = None)
      insertOwner(organization, owner)
      val expiresAt = OAuthClientExpiresAt(Instant.parse("2036-08-24T12:00:00Z"))
      val created   = createClient(owner, organization, Some(expiresAt))
      managementService
        .revoke(
          auth(owner),
          organization.slug,
          created.client.clientId,
          OAuthClientVersionRequest(created.client.version),
          correlationId = Some(OAuthAuditCorrelationId("oauth-revoke-expiring-client"))
        )
        .unsafeRunSync() must beAnInstanceOf[Success[_]]
      val repository = new SequencedNowOAuthClientRepository(Vector(expiresAt.asInstanceOf[Instant]))
      val service    = tokenService(clientRepository = repository, time = repository.time)

      val result = service
        .issue(
          Some(OAuthClientCredentials(created.client.clientId, OAuthClientSecret(created.clientSecret))),
          requestedScope = None,
          InetAddress.getByName("192.0.2.32"),
          correlationId = Some(OAuthAuditCorrelationId("oauth-revoked-expired-token"))
        )
        .unsafeRunSync()

      val snapshot = run(oauthClientRepository.findPublic(created.client.clientId)).get
      val events = run(oauthAuditRepository.list(OAuthAuditEventFilters(snapshot.client.id)))
        .filter(_.eventType == OAuthAuditEventType.InvalidClient)

      (result shouldEqual InvalidClient) and
        (events.map(_.metadata.hcursor.get[String]("failureCategory")) should contain(Right("revoked-client"))) and
        (events.map(_.metadata.hcursor.get[String]("failureCategory")) should not(contain(Right("expired-client"))))
    }

    "issue a renewed client token only with its new policy version" in {
      val owner        = newHuman()
      val organization = organizationGenerator.generate.copy(createdBy = owner.id, archivedAt = None)
      insertOwner(organization, owner)
      val expiresAt            = OAuthClientExpiresAt(Instant.parse("2036-08-24T13:00:00Z"))
      val created              = createClient(owner, organization, Some(expiresAt))
      val expiredAt            = expiresAt.asInstanceOf[Instant]
      val expirationRepository = new SequencedNowOAuthClientRepository(Vector(expiredAt))
      val expiredResult = tokenService(clientRepository = expirationRepository, time = expirationRepository.time)
        .issue(
          Some(OAuthClientCredentials(created.client.clientId, OAuthClientSecret(created.clientSecret))),
          None,
          InetAddress.getByName("192.0.2.33"),
          Some(OAuthAuditCorrelationId("oauth-token-before-renewal"))
        )
        .unsafeRunSync()
      val renewalRepository = new SequencedNowOAuthClientRepository(Vector(expiredAt))
      val renewed = managementServiceFor(new PostgresIOTransactor(db), renewalRepository, renewalRepository.time)
        .update(
          auth(owner),
          organization.slug,
          created.client.clientId,
          OAuthClientUpdateRequest(created.client.version, None, None, None, OAuthExpirationPatch.Clear),
          Some(OAuthAuditCorrelationId("oauth-renew-expired-client"))
        )
        .unsafeRunSync() match {
        case Success(value) => value
        case other          => throw new AssertionError(s"expected renewed client, got $other")
      }
      val issuedAt          = expiredAt.plusSeconds(1L)
      val renewedRepository = new SequencedNowOAuthClientRepository(Vector(issuedAt, issuedAt))
      val renewedResult = tokenService(clientRepository = renewedRepository, time = renewedRepository.time)
        .issue(
          Some(OAuthClientCredentials(created.client.clientId, OAuthClientSecret(created.clientSecret))),
          None,
          InetAddress.getByName("192.0.2.34"),
          Some(OAuthAuditCorrelationId("oauth-token-after-renewal"))
        )
        .unsafeRunSync()
      val claims = renewedResult match {
        case Issued(response) => jwtService.verifyClaims(response.access_token).toOption.get
        case other            => throw new AssertionError(s"expected Issued after renewal, got $other")
      }

      (expiredResult shouldEqual InvalidClient) and
        (renewed.policyVersion shouldEqual created.client.policyVersion + 1L) and
        (claims.policyVersion shouldEqual renewed.policyVersion)
    }

    "perform the same defensive password work and client-failure bucket consumption for every invalid client class" in {
      val owner        = newHuman()
      val organization = organizationGenerator.generate.copy(createdBy = owner.id, archivedAt = None)
      insertOwner(organization, owner)
      val expiresAt   = OAuthClientExpiresAt(Instant.parse("2036-08-24T14:00:00Z"))
      val invalid     = createClient(owner, organization)
      val expired     = createClient(owner, organization, Some(expiresAt), "Expired token integration")
      val revoked     = createClient(owner, organization, Some(expiresAt), "Revoked token integration")
      val unknownId   = OAuthClientPublicId("epoca_oauth_client_v1_ZZZZZZZZZZZZZZZZZZZZZZ")
      val bucketTimes = new RecordingRateLimitRepository
      val verifier    = new RecordingSecretVerifier(secretService)
      managementService
        .revoke(auth(owner), organization.slug, revoked.client.clientId, OAuthClientVersionRequest(revoked.client.version))
        .unsafeRunSync() must beAnInstanceOf[Success[_]]
      val timeRepository = new SequencedNowOAuthClientRepository(Vector.fill(4)(expiresAt.asInstanceOf[Instant]))
      val service =
        tokenService(clientRepository = timeRepository, rateLimitRepository = bucketTimes, time = timeRepository.time, tokenSecretService = verifier)
      val source = InetAddress.getByName("192.0.2.35")
      val results = Seq(
        OAuthClientCredentials(invalid.client.clientId, OAuthClientSecret("epoca_oauth_secret_v1_wrong-invalid")),
        OAuthClientCredentials(expired.client.clientId, OAuthClientSecret(expired.clientSecret)),
        OAuthClientCredentials(revoked.client.clientId, OAuthClientSecret(revoked.clientSecret)),
        OAuthClientCredentials(unknownId, OAuthClientSecret("epoca_oauth_secret_v1_unknown"))
      ).zipWithIndex.map { case (credentials, index) =>
        service
          .issue(Some(credentials), None, source, Some(OAuthAuditCorrelationId(s"oauth-invalid-class-$index")))
          .unsafeRunSync()
      }

      (results shouldEqual Seq.fill(4)(InvalidClient)) and
        (verifier.verifyOrDummyCalls shouldEqual 4) and
        (bucketTimes.clientFailureKeys should haveSize(4))
    }

    "recheck database time after the client lock and reject when waiting crosses expiration" in {
      val owner        = newHuman()
      val organization = organizationGenerator.generate.copy(createdBy = owner.id, archivedAt = None)
      insertOwner(organization, owner)
      val expiresAt  = OAuthClientExpiresAt(Instant.parse("2036-08-24T15:00:00Z"))
      val created    = createClient(owner, organization, Some(expiresAt))
      val acquired   = Promise[Unit]()
      val release    = Promise[Unit]()
      val repository = new ExpirationBarrierOAuthClientRepository(expiresAt.asInstanceOf[Instant].minusNanos(1L), acquired, release)
      val signer     = new RecordingTokenSigner(jwtService)
      val service    = tokenService(clientRepository = repository, time = repository.time, tokenJwtService = signer)
      val issuance = service
        .issue(
          Some(OAuthClientCredentials(created.client.clientId, OAuthClientSecret(created.clientSecret))),
          None,
          InetAddress.getByName("192.0.2.36"),
          Some(OAuthAuditCorrelationId("oauth-token-lock-expiry"))
        )
        .unsafeToFuture()

      try {
        Await.result(acquired.future, 10.seconds)
        repository.setNow(expiresAt.asInstanceOf[Instant])
        release.success(())
        val result   = Await.result(issuance, 10.seconds)
        val snapshot = run(oauthClientRepository.findPublic(created.client.clientId)).get
        val events   = run(oauthAuditRepository.list(OAuthAuditEventFilters(snapshot.client.id)))

        (result shouldEqual InvalidClient) and
          (repository.authoritativeNowCalls shouldEqual 2) and
          (signer.signCalls shouldEqual 0) and
          (events.map(_.metadata.hcursor.get[String]("failureCategory")) should contain(Right("expired-client"))) and
          (events.map(_.eventType).contains(OAuthAuditEventType.TokenIssued) should beFalse)
      } finally {
        val _ = release.trySuccess(())
      }
    }

    "read final database time after the second secret verification and reject an expiration crossed inside bcrypt" in {
      val owner        = newHuman()
      val organization = organizationGenerator.generate.copy(createdBy = owner.id, archivedAt = None)
      insertOwner(organization, owner)
      val expiresAt     = OAuthClientExpiresAt(Instant.parse("2036-08-24T15:30:00Z"))
      val created       = createClient(owner, organization, Some(expiresAt))
      val repository    = new MutableNowOAuthClientRepository(expiresAt.asInstanceOf[Instant].minusNanos(1L))
      val verifyStarted = Promise[Unit]()
      val releaseVerify = Promise[Unit]()
      val verifier      = new SecondVerifyBarrierVerifier(secretService, verifyStarted, releaseVerify)
      val signer        = new RecordingTokenSigner(jwtService)
      val service       = tokenService(clientRepository = repository, time = repository.time, tokenSecretService = verifier, tokenJwtService = signer)
      val issuance = service
        .issue(
          Some(OAuthClientCredentials(created.client.clientId, OAuthClientSecret(created.clientSecret))),
          None,
          InetAddress.getByName("192.0.2.37"),
          Some(OAuthAuditCorrelationId("oauth-token-bcrypt-expiry"))
        )
        .unsafeToFuture()

      try {
        Await.result(verifyStarted.future, 10.seconds)
        repository.setNow(expiresAt.asInstanceOf[Instant])
        releaseVerify.success(())
        val result   = Await.result(issuance, 10.seconds)
        val snapshot = run(oauthClientRepository.findPublic(created.client.clientId)).get
        val events   = run(oauthAuditRepository.list(OAuthAuditEventFilters(snapshot.client.id)))

        (result shouldEqual InvalidClient) and
          (repository.authoritativeNowCalls shouldEqual 2) and
          (verifier.verifyOrDummyCalls shouldEqual 1) and
          (verifier.verifyCalls shouldEqual 1) and
          (signer.signCalls shouldEqual 0) and
          (events.map(_.metadata.hcursor.get[String]("failureCategory")) should contain(Right("expired-client"))) and
          (events.map(_.eventType).contains(OAuthAuditEventType.TokenIssued) should beFalse)
      } finally {
        val _ = releaseVerify.trySuccess(())
      }
    }

    "keep one client-row-first lock order across concurrent precheck and post-lock failures" in {
      val owner        = newHuman()
      val organization = organizationGenerator.generate.copy(createdBy = owner.id, archivedAt = None)
      insertOwner(organization, owner)
      val created          = createClient(owner, organization)
      val precheckDb       = independentDatabase("oauth-precheck-failure-lock-order")
      val postLockDb       = independentDatabase("oauth-post-lock-failure-lock-order")
      val bucketAcquired   = Promise[Unit]()
      val releasePrecheck  = Promise[Unit]()
      val beforePostLock   = Promise[Unit]()
      val acquiredPostLock = Promise[Unit]()
      val limits           = OAuthRateLimit(10, 300.seconds)
      val precheckRates = new PauseAfterClientFailureBucketRepository(
        new OAuthRateLimitRepositoryImpl(OAuthRateLimit(60, 60.seconds), limits),
        bucketAcquired,
        releasePrecheck
      )
      val postLockRates      = new OAuthRateLimitRepositoryImpl(OAuthRateLimit(60, 60.seconds), limits)
      val postLockRepository = new SignallingSecretVersionDriftRepository(beforePostLock, acquiredPostLock)
      val precheckService = tokenService(
        tokenTransactor = new PostgresIOTransactor(precheckDb),
        clientRepository = new OAuthClientRepositoryImpl(authoritativeTime),
        rateLimitRepository = precheckRates
      )
      val postLockService = tokenService(
        tokenTransactor = new PostgresIOTransactor(postLockDb),
        clientRepository = postLockRepository,
        rateLimitRepository = postLockRates
      )
      val runId               = UUID.randomUUID()
      val precheckCorrelation = s"oauth-precheck-lock-order-$runId"
      val postLockCorrelation = s"oauth-post-lock-order-$runId"
      val precheckFailure = precheckService
        .issue(
          Some(OAuthClientCredentials(created.client.clientId, OAuthClientSecret("epoca_oauth_secret_v1_wrong-lock-order"))),
          None,
          InetAddress.getByName("198.51.100.41"),
          Some(OAuthAuditCorrelationId(precheckCorrelation))
        )
        .unsafeToFuture()

      try {
        Await.result(bucketAcquired.future, 10.seconds)
        val postLockFailure = postLockService
          .issue(
            Some(OAuthClientCredentials(created.client.clientId, OAuthClientSecret(created.clientSecret))),
            None,
            InetAddress.getByName("198.51.100.42"),
            Some(OAuthAuditCorrelationId(postLockCorrelation))
          )
          .unsafeToFuture()
        Await.result(beforePostLock.future, 10.seconds)
        val postLockOwnedClientBeforeRelease = Try(Await.result(acquiredPostLock.future, 500.millis)).isSuccess
        releasePrecheck.success(())
        val results = Seq(Await.result(precheckFailure, 15.seconds), Await.result(postLockFailure, 15.seconds))
        val auditCorrelations = run(sql"""select correlation_id from public.oauth_audit_event
                 where correlation_id in ($precheckCorrelation, $postLockCorrelation)""".as[String])
        val availableTokens = run(sql"""select available_tokens from public.oauth_rate_limit_bucket
                 where dimension = 'ClientFailure' and bucket_key = ${failureBucketKey(created.client.clientId)}""".as[Double].head)

        (postLockOwnedClientBeforeRelease should beFalse) and
          (results shouldEqual Seq(InvalidClient, InvalidClient)) and
          (auditCorrelations.toSet shouldEqual Set(precheckCorrelation, postLockCorrelation)) and
          (availableTokens should be_>=(8d)) and
          (availableTokens should be_<(9d))
      } finally {
        val _ = releasePrecheck.trySuccess(())
        precheckDb.close()
        postLockDb.close()
      }
    }

    "derive token timestamps and rate-limit decisions from the injected authoritative time" in {
      val owner        = newHuman()
      val organization = organizationGenerator.generate.copy(createdBy = owner.id, archivedAt = None)
      insertOwner(organization, owner)
      val created  = createClient(owner, organization)
      val fixedNow = Instant.parse("2031-02-03T04:05:06Z")
      val recordingRateRepository =
        new RecordingRateLimitRepository(Some(new OAuthRateLimitRepositoryImpl(OAuthRateLimit(60, 60.seconds), OAuthRateLimit(10, 300.seconds))))
      val fixedSut = tokenService(rateLimitRepository = recordingRateRepository, time = fixedTime(fixedNow))

      val result = fixedSut
        .issue(
          Some(OAuthClientCredentials(created.client.clientId, OAuthClientSecret(created.clientSecret))),
          requestedScope = None,
          InetAddress.getByName("192.0.2.119"),
          correlationId = Some(OAuthAuditCorrelationId("oauth-injected-token-time"))
        )
        .unsafeRunSync()
      val response = result match {
        case Issued(value) => value
        case other         => throw new AssertionError(s"expected Issued, got $other")
      }
      val claims = jwtService.verifyClaims(response.access_token) match {
        case Right(value) => value
        case Left(error)  => throw new AssertionError(s"expected decodable token, got $error")
      }
      val failed = fixedSut
        .issue(
          Some(OAuthClientCredentials(created.client.clientId, OAuthClientSecret("epoca_oauth_secret_v1_wrong-but-redacted"))),
          requestedScope = None,
          InetAddress.getByName("192.0.2.121"),
          correlationId = Some(OAuthAuditCorrelationId("oauth-injected-client-failure-time"))
        )
        .unsafeRunSync()

      (claims.issuedAt shouldEqual fixedNow) and
        (claims.expiresAt shouldEqual fixedNow.plusSeconds(300L)) and
        (response.expires_in shouldEqual 300L) and
        (failed shouldEqual InvalidClient) and
        (recordingRateRepository.ipTimes shouldEqual Vector(fixedNow, fixedNow)) and
        (recordingRateRepository.clientFailureTimes shouldEqual Vector(fixedNow))
    }

    "return indistinguishable invalid-client results and persist redacted known and unknown failure audits" in {
      val owner        = newHuman()
      val organization = organizationGenerator.generate.copy(createdBy = owner.id, archivedAt = None)
      insertOwner(organization, owner)
      val created            = createClient(owner, organization)
      val source             = InetAddress.getByName("192.0.2.21")
      val wrongSecret        = OAuthClientSecret("epoca_oauth_secret_v1_wrong-but-redacted")
      val unknownSecret      = OAuthClientSecret("epoca_oauth_secret_v1_unknown-but-redacted")
      val runId              = UUID.randomUUID()
      val unknownId          = OAuthClientPublicId(s"epoca_oauth_client_v1_${runId.toString.replace("-", "").take(22)}")
      val unknownCorrelation = s"oauth-unknown-invalid-$runId"

      val knownResult = sut
        .issue(
          Some(OAuthClientCredentials(created.client.clientId, wrongSecret)),
          requestedScope = None,
          source,
          correlationId = Some(OAuthAuditCorrelationId(s"oauth-known-invalid-$runId"))
        )
        .unsafeRunSync()
      val unknownResult = sut
        .issue(
          Some(OAuthClientCredentials(unknownId, unknownSecret)),
          requestedScope = None,
          source,
          correlationId = Some(OAuthAuditCorrelationId(unknownCorrelation))
        )
        .unsafeRunSync()

      val clientSnapshot = run(oauthClientRepository.findPublic(created.client.clientId)).get
      val knownEvents = run(oauthAuditRepository.list(OAuthAuditEventFilters(clientSnapshot.client.id)))
        .filter(_.eventType == OAuthAuditEventType.InvalidClient)
      val unknownMetadata =
        run(sql"""select metadata::text from public.oauth_audit_event where correlation_id = $unknownCorrelation""".as[String])

      (knownResult shouldEqual InvalidClient) and
        (unknownResult shouldEqual InvalidClient) and
        (knownEvents.map(_.metadata.hcursor.get[String]("failureCategory")) should contain(Right("invalid-secret"))) and
        (knownEvents.map(_.metadata.noSpaces).mkString must not(contain(wrongSecret.toString))) and
        (unknownMetadata should haveSize(1)) and
        (unknownMetadata.head must contain("unknown-client")) and
        (unknownMetadata.head must not(contain(unknownId.toString))) and
        (unknownMetadata.head must not(contain(unknownSecret.toString)))
    }

    "reject a wider requested scope and persist only its safe failure category" in {
      val owner        = newHuman()
      val organization = organizationGenerator.generate.copy(createdBy = owner.id, archivedAt = None)
      insertOwner(organization, owner)
      val created = createClient(owner, organization)

      val result = sut
        .issue(
          Some(OAuthClientCredentials(created.client.clientId, OAuthClientSecret(created.clientSecret))),
          requestedScope = Some("projects:read reports:read"),
          InetAddress.getByName("192.0.2.22"),
          correlationId = Some(OAuthAuditCorrelationId("oauth-invalid-scope"))
        )
        .unsafeRunSync()

      val clientSnapshot = run(oauthClientRepository.findPublic(created.client.clientId)).get
      val events = run(oauthAuditRepository.list(OAuthAuditEventFilters(clientSnapshot.client.id)))
        .filter(_.eventType == OAuthAuditEventType.InvalidScope)

      (result shouldEqual InvalidScope) and
        (events.map(_.metadata.hcursor.get[String]("failureCategory")) shouldEqual Seq(Right("ungranted-scope"))) and
        (events.map(_.metadata.noSpaces).mkString must not(contain(created.clientSecret)))
    }

    "reject a revoked client through the same invalid-client boundary and consume its failure bucket" in {
      val owner        = newHuman()
      val organization = organizationGenerator.generate.copy(createdBy = owner.id, archivedAt = None)
      insertOwner(organization, owner)
      val created = createClient(owner, organization)
      managementService
        .revoke(
          AuthContextProvider.user.copy(id = owner.id, isSuperAdmin = owner.isSuperAdmin, identities = Nil, roles = Nil),
          organization.slug,
          created.client.clientId,
          OAuthClientVersionRequest(created.client.version),
          correlationId = Some(OAuthAuditCorrelationId("oauth-revoke-before-token"))
        )
        .unsafeRunSync() must beAnInstanceOf[Success[_]]

      val result = sut
        .issue(
          Some(OAuthClientCredentials(created.client.clientId, OAuthClientSecret(created.clientSecret))),
          requestedScope = None,
          InetAddress.getByName("192.0.2.23"),
          correlationId = Some(OAuthAuditCorrelationId("oauth-revoked-token"))
        )
        .unsafeRunSync()

      val clientSnapshot = run(oauthClientRepository.findPublic(created.client.clientId)).get
      val events = run(oauthAuditRepository.list(OAuthAuditEventFilters(clientSnapshot.client.id)))
        .filter(_.eventType == OAuthAuditEventType.InvalidClient)

      (result shouldEqual InvalidClient) and
        (events.map(_.metadata.hcursor.get[String]("failureCategory")) should contain(Right("revoked-client")))
    }

    "reject issuance when the locked secret version changed after the public credential snapshot" in {
      val owner        = newHuman()
      val organization = organizationGenerator.generate.copy(createdBy = owner.id, archivedAt = None)
      insertOwner(organization, owner)
      val created    = createClient(owner, organization)
      val guardedSut = tokenService(clientRepository = new SecretVersionDriftRepository)

      val result = guardedSut
        .issue(
          Some(OAuthClientCredentials(created.client.clientId, OAuthClientSecret(created.clientSecret))),
          None,
          InetAddress.getByName("192.0.2.26"),
          Some(OAuthAuditCorrelationId("oauth-secret-version-drift"))
        )
        .unsafeRunSync()
      val snapshot = run(oauthClientRepository.findPublic(created.client.clientId)).get
      val events   = run(oauthAuditRepository.list(OAuthAuditEventFilters(snapshot.client.id)))

      (result shouldEqual InvalidClient) and
        (events.map(_.eventType).contains(OAuthAuditEventType.TokenIssued) should beFalse)
    }

    "linearize issuance under the client row lock before a concurrent secret rotation" in {
      val owner        = newHuman()
      val organization = organizationGenerator.generate.copy(createdBy = owner.id, archivedAt = None)
      insertOwner(organization, owner)
      val created            = createClient(owner, organization)
      val issueDb            = independentDatabase("oauth-token-issue-lock")
      val rotationDb         = independentDatabase("oauth-token-rotation-lock")
      val lockAcquired       = Promise[Unit]()
      val releaseIssuance    = Promise[Unit]()
      val rotationStarted    = Promise[Unit]()
      val issueRepository    = new PauseAfterLockRepository(lockAcquired, releaseIssuance)
      val rotationRepository = new SignallingRotateRepository(rotationStarted)
      val issueSut = tokenService(
        tokenTransactor = new PostgresIOTransactor(issueDb),
        clientRepository = issueRepository,
        rateLimitRepository = new OAuthRateLimitRepositoryImpl(OAuthRateLimit(60, 60.seconds), OAuthRateLimit(10, 300.seconds))
      )
      val rotationSut = managementServiceFor(new PostgresIOTransactor(rotationDb), rotationRepository)
      val runId       = UUID.randomUUID()
      val source =
        InetAddress.getByAddress(Array(198.toByte, 19.toByte, (runId.getLeastSignificantBits >> 8).toByte, runId.getLeastSignificantBits.toByte))
      val issuance = issueSut
        .issue(
          Some(OAuthClientCredentials(created.client.clientId, OAuthClientSecret(created.clientSecret))),
          None,
          source,
          Some(OAuthAuditCorrelationId(s"oauth-issue-before-rotate-$runId"))
        )
        .unsafeToFuture()

      try {
        Await.result(lockAcquired.future, 10.seconds)
        val rotation = rotationSut
          .rotateSecret(
            AuthContextProvider.user.copy(id = owner.id, isSuperAdmin = owner.isSuperAdmin, identities = Nil, roles = Nil),
            organization.slug,
            created.client.clientId,
            OAuthClientVersionRequest(created.client.version),
            Some(OAuthAuditCorrelationId(s"oauth-rotate-after-issue-$runId"))
          )
          .unsafeToFuture()
        Await.result(rotationStarted.future, 10.seconds)
        val blockedByIssuanceLock = Try(Await.result(rotation, 300.millis)).failed.toOption.exists(_.isInstanceOf[TimeoutException])

        releaseIssuance.success(())
        val issuedResult  = Await.result(issuance, 10.seconds)
        val rotatedResult = Await.result(rotation, 10.seconds)
        val issuedBeforeRotation = issuedResult match {
          case Issued(_) => true
          case _         => false
        }
        val rotationCompleted = rotatedResult match {
          case Success(_) => true
          case _          => false
        }

        (blockedByIssuanceLock && issuedBeforeRotation && rotationCompleted) should beTrue
      } finally {
        releaseIssuance.trySuccess(())
        issueDb.close()
        rotationDb.close()
      }
    }

    "reject the old secret when rotation commits before issuance acquires the client row lock" in {
      val owner        = newHuman()
      val organization = organizationGenerator.generate.copy(createdBy = owner.id, archivedAt = None)
      insertOwner(organization, owner)
      val created         = createClient(owner, organization)
      val issueDb         = independentDatabase("oauth-token-after-rotation")
      val rotationDb      = independentDatabase("oauth-rotation-before-token")
      val beforeLock      = Promise[Unit]()
      val releaseIssuance = Promise[Unit]()
      val issueSut = tokenService(
        tokenTransactor = new PostgresIOTransactor(issueDb),
        clientRepository = new PauseBeforeLockRepository(beforeLock, releaseIssuance),
        rateLimitRepository = new OAuthRateLimitRepositoryImpl(OAuthRateLimit(60, 60.seconds), OAuthRateLimit(10, 300.seconds))
      )
      val rotationSut = managementServiceFor(new PostgresIOTransactor(rotationDb), new OAuthClientRepositoryImpl(authoritativeTime))
      val runId       = UUID.randomUUID()
      val source =
        InetAddress.getByAddress(Array(198.toByte, 20.toByte, (runId.getLeastSignificantBits >> 8).toByte, runId.getLeastSignificantBits.toByte))
      val issuance = issueSut
        .issue(
          Some(OAuthClientCredentials(created.client.clientId, OAuthClientSecret(created.clientSecret))),
          None,
          source,
          Some(OAuthAuditCorrelationId(s"oauth-issue-after-rotate-$runId"))
        )
        .unsafeToFuture()

      try {
        Await.result(beforeLock.future, 10.seconds)
        val rotation = Await.result(
          rotationSut
            .rotateSecret(
              AuthContextProvider.user.copy(id = owner.id, isSuperAdmin = owner.isSuperAdmin, identities = Nil, roles = Nil),
              organization.slug,
              created.client.clientId,
              OAuthClientVersionRequest(created.client.version),
              Some(OAuthAuditCorrelationId(s"oauth-rotate-before-issue-$runId"))
            )
            .unsafeToFuture(),
          10.seconds
        )
        releaseIssuance.success(())
        val result = Await.result(issuance, 10.seconds)
        val rotationCompleted = rotation match {
          case Success(_) => true
          case _          => false
        }

        (rotationCompleted && result == InvalidClient) should beTrue
      } finally {
        releaseIssuance.trySuccess(())
        issueDb.close()
        rotationDb.close()
      }
    }

    "audit an exhausted source-IP bucket without authenticating or exposing client credentials" in {
      val owner        = newHuman()
      val organization = organizationGenerator.generate.copy(createdBy = owner.id, archivedAt = None)
      insertOwner(organization, owner)
      val created               = createClient(owner, organization)
      val limitedRateRepository = new OAuthRateLimitRepositoryImpl(OAuthRateLimit(1, 60.seconds), OAuthRateLimit(10, 300.seconds))
      val limitedSut            = tokenService(rateLimitRepository = limitedRateRepository)
      val runId                 = java.util.UUID.randomUUID()
      val source =
        InetAddress.getByAddress(Array(198.toByte, 18.toByte, (runId.getLeastSignificantBits >> 8).toByte, runId.getLeastSignificantBits.toByte))
      val credentials        = Some(OAuthClientCredentials(created.client.clientId, OAuthClientSecret(created.clientSecret)))
      val limitedCorrelation = s"oauth-ip-limited-$runId"

      limitedSut.issue(credentials, None, source, Some(OAuthAuditCorrelationId(s"oauth-ip-first-$runId"))).unsafeRunSync() must beAnInstanceOf[Issued]
      val result = limitedSut.issue(credentials, None, source, Some(OAuthAuditCorrelationId(limitedCorrelation))).unsafeRunSync()
      val audit =
        run(sql"""select event_type, metadata::text from public.oauth_audit_event where correlation_id = $limitedCorrelation""".as[(String, String)])

      (result must beLike { case RateLimited(seconds) => seconds must be_>(0L) }) and
        (audit shouldEqual Seq("RateLimit" -> "{\"failureCategory\": \"ip-rate-limit\"}")) and
        (audit.mkString must not(contain(created.clientSecret)))
    }

    "fail closed without returning a signed token when the required issuance audit cannot commit" in {
      val owner        = newHuman()
      val organization = organizationGenerator.generate.copy(createdBy = owner.id, archivedAt = None)
      insertOwner(organization, owner)
      val created    = createClient(owner, organization)
      val failingSut = tokenService(auditRepository = new FailingAuditRepository(oauthAuditRepository))

      val result = failingSut
        .issue(
          Some(OAuthClientCredentials(created.client.clientId, OAuthClientSecret(created.clientSecret))),
          None,
          InetAddress.getByName("192.0.2.25"),
          Some(OAuthAuditCorrelationId("oauth-audit-failure"))
        )
        .unsafeRunSync()
      val snapshot = run(oauthClientRepository.findPublic(created.client.clientId)).get
      val events   = run(oauthAuditRepository.list(OAuthAuditEventFilters(snapshot.client.id)))

      (result shouldEqual TemporarilyUnavailable) and
        (events.map(_.eventType).contains(OAuthAuditEventType.TokenIssued) should beFalse)
    }
  }

  private def tokenService(
    tokenTransactor: PostgresIOTransactor = transactor,
    clientRepository: OAuthClientRepository = oauthClientRepository,
    auditRepository: OAuthAuditRepository = oauthAuditRepository,
    rateLimitRepository: OAuthRateLimitRepository = oauthRateLimitRepository,
    time: OAuthAuthoritativeTime = authoritativeTime,
    tokenSecretService: OAuthSecretVerifier = secretService,
    tokenJwtService: OAuthTokenSigner = jwtService
  ): OAuthTokenService =
    new OAuthTokenService(
      tokenTransactor,
      time,
      clientRepository,
      auditRepository,
      rateLimitRepository,
      tokenSecretService,
      tokenJwtService,
      "https://staging.epoca.example.test/oauth",
      "epoca-api"
    )

  private def managementServiceFor(
    managementTransactor: PostgresIOTransactor,
    clientRepository: OAuthClientRepository,
    time: OAuthAuthoritativeTime = authoritativeTime
  ): OAuthClientManagementService =
    new OAuthClientManagementService(
      managementTransactor,
      time,
      grantabilityService,
      serviceAccountRepository,
      clientRepository,
      oauthAuditRepository,
      userRepository,
      secretService
    )

  private def fixedTime(value: Instant): OAuthAuthoritativeTime = new OAuthAuthoritativeTime {
    override def now: DBIO[Instant] = DBIO.successful(value)
  }

  private def newHuman(): User =
    userGenerator.generate.copy(accountType = UserAccountType.Human, archivedAt = None, isSuperAdmin = UserIsSuperAdmin(false))

  private def auth(owner: User) =
    AuthContextProvider.user.copy(id = owner.id, isSuperAdmin = owner.isSuperAdmin, identities = Nil, roles = Nil)

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
    expiresAt: Option[OAuthClientExpiresAt] = None,
    name: String = "Token integration"
  ): OAuthClientSecretView =
    managementService
      .create(auth(owner), organization.slug, OAuthClientCreateRequest(name, Set(OAuthScope.ProjectsRead), OAuthProjectAccess.AllProjects, expiresAt))
      .unsafeRunSync() match {
      case Success(value) => value
      case other          => throw new AssertionError(s"expected created client, got $other")
    }

  private def run[A](action: DBIO[A]): A = Await.result(db.run(action), Duration.Inf)

  private def failureBucketKey(clientId: OAuthClientPublicId): String = {
    val digest = MessageDigest.getInstance("SHA-256").digest(clientId.toString.getBytes(StandardCharsets.UTF_8))
    Base64.getUrlEncoder.withoutPadding().encodeToString(digest)
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

  private case object ForcedAuditFailure extends RuntimeException("forced token-audit failure")

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
    override def deleteExpiredAuthentication(cutoff: java.time.Instant, batchSize: Int): DBIO[Int] =
      delegate.deleteExpiredAuthentication(cutoff, batchSize)
  }

  private final class SecretVersionDriftRepository extends OAuthClientRepositoryImpl(authoritativeTime) {
    override def lock(clientId: OAuthClientId): DBIO[Option[OAuthClientRepository.OAuthClientSnapshot]] =
      super
        .lock(clientId)
        .map(
          _.map(snapshot =>
            snapshot
              .copy(client = snapshot.client.copy(secretVersion = OAuthClientSecretVersion(snapshot.client.secretVersion.asInstanceOf[Long] + 1L)))
          )
        )
  }

  private final class SequencedOAuthAuthoritativeTime(times: Vector[Instant]) extends OAuthAuthoritativeTime {
    require(times.nonEmpty, "at least one authoritative time is required")
    private var nextIndex: Int = 0
    var calls: Int             = 0

    override def now: DBIO[Instant] = {
      val index = math.min(nextIndex, times.size - 1)
      nextIndex += 1
      calls += 1
      DBIO.successful(times(index))
    }
  }

  private final class MutableOAuthAuthoritativeTime(initialNow: Instant) extends OAuthAuthoritativeTime {
    @volatile private var currentNow: Instant = initialNow
    var calls: Int                            = 0

    override def now: DBIO[Instant] = {
      calls += 1
      DBIO.successful(currentNow)
    }

    def setNow(value: Instant): Unit = currentNow = value
  }

  private final class SequencedNowOAuthClientRepository private (val time: SequencedOAuthAuthoritativeTime) extends OAuthClientRepositoryImpl(time) {
    def this(times: Vector[Instant]) = this(new SequencedOAuthAuthoritativeTime(times))
    def authoritativeNowCalls: Int = time.calls
  }

  private final class ExpirationBarrierOAuthClientRepository private (
    val time: MutableOAuthAuthoritativeTime,
    acquired: Promise[Unit],
    release: Promise[Unit])
      extends OAuthClientRepositoryImpl(time) {
    def this(
      initialNow: Instant,
      acquired: Promise[Unit],
      release: Promise[Unit]
    ) =
      this(new MutableOAuthAuthoritativeTime(initialNow), acquired, release)

    def authoritativeNowCalls: Int = time.calls

    override def lock(clientId: OAuthClientId): DBIO[Option[OAuthClientRepository.OAuthClientSnapshot]] =
      super.lock(clientId).flatMap { snapshot =>
        val _ = acquired.trySuccess(())
        DBIO.from(release.future).map(_ => snapshot)
      }

    def setNow(value: Instant): Unit = time.setNow(value)
  }

  private final class MutableNowOAuthClientRepository private (val time: MutableOAuthAuthoritativeTime) extends OAuthClientRepositoryImpl(time) {
    def this(initialNow: Instant) = this(new MutableOAuthAuthoritativeTime(initialNow))

    def authoritativeNowCalls: Int = time.calls

    def setNow(value: Instant): Unit = time.setNow(value)
  }

  private final class RecordingRateLimitRepository(delegate: Option[OAuthRateLimitRepository] = None) extends OAuthRateLimitRepository {
    var clientFailureKeys: Vector[OAuthRateLimitBucketKey] = Vector.empty
    var ipTimes: Vector[Instant]                           = Vector.empty
    var clientFailureTimes: Vector[Instant]                = Vector.empty

    override def consumeIp(source: InetAddress, now: Instant): DBIO[RateLimitDecision] = {
      ipTimes :+= now
      delegate.fold[DBIO[RateLimitDecision]](DBIO.successful(RateLimitDecision.Allowed(remainingTokens = 100d)))(_.consumeIp(source, now))
    }

    override def consumeClientFailure(bucketKey: OAuthRateLimitBucketKey, now: Instant): DBIO[RateLimitDecision] = {
      clientFailureKeys :+= bucketKey
      clientFailureTimes :+= now
      delegate.fold[DBIO[RateLimitDecision]](DBIO.successful(RateLimitDecision.Allowed(remainingTokens = 100d)))(
        _.consumeClientFailure(bucketKey, now)
      )
    }

    override def deleteExpired(now: Instant, batchSize: Int): DBIO[Int] =
      delegate.fold[DBIO[Int]](DBIO.successful(0))(_.deleteExpired(now, batchSize))
  }

  private final class RecordingSecretVerifier(delegate: OAuthSecretVerifier) extends OAuthSecretVerifier {
    var verifyCalls: Int        = 0
    var verifyOrDummyCalls: Int = 0

    override def verify(secret: OAuthClientSecret, hash: OAuthClientSecretHash): Boolean = {
      verifyCalls += 1
      delegate.verify(secret, hash)
    }

    override def verifyOrDummy(secret: OAuthClientSecret, eligibleHash: Option[OAuthClientSecretHash]): Boolean = {
      verifyOrDummyCalls += 1
      delegate.verifyOrDummy(secret, eligibleHash)
    }
  }

  private final class SecondVerifyBarrierVerifier(
    delegate: OAuthSecretVerifier,
    verifyStarted: Promise[Unit],
    releaseVerify: Promise[Unit])
      extends OAuthSecretVerifier {
    var verifyCalls: Int        = 0
    var verifyOrDummyCalls: Int = 0

    override def verify(secret: OAuthClientSecret, hash: OAuthClientSecretHash): Boolean = {
      verifyCalls += 1
      val _ = verifyStarted.trySuccess(())
      Await.result(releaseVerify.future, 10.seconds)
      delegate.verify(secret, hash)
    }

    override def verifyOrDummy(secret: OAuthClientSecret, eligibleHash: Option[OAuthClientSecretHash]): Boolean = {
      verifyOrDummyCalls += 1
      delegate.verifyOrDummy(secret, eligibleHash)
    }
  }

  private final class RecordingTokenSigner(delegate: OAuthTokenSigner) extends OAuthTokenSigner {
    var signCalls: Int = 0

    override def sign(claims: OAuthTokenClaims): Either[OAuthJwtError, String] = {
      signCalls += 1
      delegate.sign(claims)
    }
  }

  private final class PauseAfterClientFailureBucketRepository(
    delegate: OAuthRateLimitRepository,
    bucketAcquired: Promise[Unit],
    release: Promise[Unit])
      extends OAuthRateLimitRepository {
    override def consumeIp(source: InetAddress, now: Instant): DBIO[RateLimitDecision] = delegate.consumeIp(source, now)

    override def consumeClientFailure(bucketKey: OAuthRateLimitBucketKey, now: Instant): DBIO[RateLimitDecision] =
      delegate.consumeClientFailure(bucketKey, now).flatMap { decision =>
        val _ = bucketAcquired.trySuccess(())
        DBIO.from(release.future).map(_ => decision)
      }

    override def deleteExpired(now: Instant, batchSize: Int): DBIO[Int] = delegate.deleteExpired(now, batchSize)
  }

  private final class SignallingSecretVersionDriftRepository(beforeLock: Promise[Unit], acquired: Promise[Unit])
      extends OAuthClientRepositoryImpl(authoritativeTime) {
    override def lock(clientId: OAuthClientId): DBIO[Option[OAuthClientRepository.OAuthClientSnapshot]] = {
      val _ = beforeLock.trySuccess(())
      super.lock(clientId).map { maybeSnapshot =>
        val _ = acquired.trySuccess(())
        maybeSnapshot.map(snapshot =>
          snapshot
            .copy(client = snapshot.client.copy(secretVersion = OAuthClientSecretVersion(snapshot.client.secretVersion.asInstanceOf[Long] + 1L)))
        )
      }
    }
  }

  private final class PauseAfterLockRepository(acquired: Promise[Unit], release: Promise[Unit]) extends OAuthClientRepositoryImpl(authoritativeTime) {
    override def lock(clientId: OAuthClientId): DBIO[Option[OAuthClientRepository.OAuthClientSnapshot]] =
      super.lock(clientId).flatMap { snapshot =>
        val _ = acquired.trySuccess(())
        DBIO.from(release.future).map(_ => snapshot)
      }
  }

  private final class PauseBeforeLockRepository(beforeLock: Promise[Unit], release: Promise[Unit])
      extends OAuthClientRepositoryImpl(authoritativeTime) {
    private val pauseOnce = new AtomicBoolean(false)

    override def lock(clientId: OAuthClientId): DBIO[Option[OAuthClientRepository.OAuthClientSnapshot]] =
      if (pauseOnce.compareAndSet(false, true)) {
        val _ = beforeLock.trySuccess(())
        DBIO.from(release.future).flatMap(_ => super.lock(clientId))
      } else super.lock(clientId)
  }

  private final class SignallingRotateRepository(started: Promise[Unit]) extends OAuthClientRepositoryImpl(authoritativeTime) {
    override def lock(clientId: OAuthClientId): DBIO[Option[OAuthClientRepository.OAuthClientSnapshot]] = {
      val _ = started.trySuccess(())
      super.lock(clientId)
    }

    override def rotateSecretAtVersion(
      id: OAuthClientId,
      expectedVersion: Long,
      hash: String,
      at: java.time.Instant,
      by: pl.iterators.epoca.user.domain.UserDomain.UserId
    ): DBIO[OAuthClientRepository.OAuthClientMutationResult] =
      super.rotateSecretAtVersion(id, expectedVersion, hash, at, by)
  }
}
