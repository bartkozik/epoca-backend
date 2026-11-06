package pl.iterators.epoca.invitation.services

import pl.iterators.epoca.auth.domain.AccountAuthentication.{BoundSaml, Credential, CredentialMethod}
import pl.iterators.epoca.auth.domain.AuthDomain.PasswordHash
import pl.iterators.epoca.auth.domain.{AccountAuthentication, AuthContext, UserPassword}
import pl.iterators.epoca.auth.repositories.UserPasswordRepositoryImpl
import pl.iterators.epoca.invitation.domain.Invitation
import pl.iterators.epoca.invitation.domain.InvitationDomain._
import pl.iterators.epoca.invitation.domain.InvitationOnboarding._
import pl.iterators.epoca.invitation.repositories._
import pl.iterators.epoca.organization.domain.OrganizationDomain._
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.organization.repositories.fixtures.OrganizationFixture
import pl.iterators.epoca.saml.domain.SamlDomain._
import pl.iterators.epoca.saml.domain.{SamlAuthContext, SamlBindingKey, SamlConfig, SamlIdentityBinding, SamlRequest}
import pl.iterators.epoca.saml.repositories.{SamlConfigRepositoryImpl, SamlIdentityBindingRepositoryImpl, SamlRequestRepositoryImpl}
import pl.iterators.epoca.saml.services.SamlInvitationService
import pl.iterators.epoca.user.domain.UserDomain._
import pl.iterators.epoca.user.domain.{User, UserIdentity}
import pl.iterators.epoca.user.repositories.EmailIdentityRepository.Resolution
import pl.iterators.epoca.user.repositories._
import pl.iterators.epoca.user.repositories.fixtures.UserFixture
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import pl.iterators.epoca.utils.{AuthContextProvider, PgRepositorySpec}

import java.time.{Clock, Instant, ZoneOffset}
import java.util.UUID

class InvitationActivationServiceSpec extends PgRepositorySpec with OrganizationFixture with UserFixture {
  private val invitations = new InvitationOnboardingRepositoryImpl
  private val accesses    = new InvitationAccessRepositoryImpl
  private val identities  = new UserIdentityRepositoryImpl
  private val users       = new UserRepositoryImpl
  private val passwords   = new UserPasswordRepositoryImpl
  private val secrets     = new InvitationSecretService
  private val now         = Instant.parse("2026-10-02T10:00:00Z")
  private val clock       = Clock.fixed(now, ZoneOffset.UTC)
  private val origin      = Some("https://app.example.test")
  private val browser     = new InvitationBrowserSecurity(origin.toSet, "/api", false, false, secrets)
  private val access      = new InvitationAccessService(null, invitations, accesses, new EmailIdentityRepositoryImpl, secrets, browser, null, clock)
  private val memberships = new pl.iterators.epoca.organization.repositories.OrganizationUserRepositoryImpl
  private val creation    = new pl.iterators.epoca.user.services.UserCreationActions(users, identities, new EmailIdentityRepositoryImpl, passwords)
  private val authentication = new pl.iterators.epoca.auth.services.AccountAuthenticationValidator(
    new pl.iterators.epoca.auth.repositories.AccountAuthenticationRepositoryImpl,
    identities,
    passwords
  )
  private case object MembershipWriteFailure extends RuntimeException("membership write failure")
  private val failingMemberships = new pl.iterators.epoca.organization.repositories.OrganizationUserRepositoryImpl {
    override private[epoca] def insertPrepared(organizationUser: pl.iterators.epoca.organization.domain.OrganizationUser)
      : DBIO[pl.iterators.epoca.organization.domain.OrganizationUser] =
      DBIO.failed(MembershipWriteFailure)
  }
  private val actions = new InvitationActivationActions(
    invitations,
    accesses,
    users,
    identities,
    memberships,
    creation,
    authentication,
    clock,
    new pl.iterators.epoca.saml.repositories.SamlRequestRepositoryImpl,
    new pl.iterators.epoca.saml.repositories.SamlIdentityBindingRepositoryImpl
  )
  private val service = new InvitationActivationService(null, access, actions, null, null, null, null, browser, clock)
  private val failingService = new InvitationActivationService(
    null,
    access,
    new InvitationActivationActions(
      invitations,
      accesses,
      users,
      identities,
      failingMemberships,
      creation,
      authentication,
      clock,
      new pl.iterators.epoca.saml.repositories.SamlRequestRepositoryImpl,
      new pl.iterators.epoca.saml.repositories.SamlIdentityBindingRepositoryImpl
    ),
    null,
    null,
    null,
    null,
    browser,
    clock
  )
  private case class Fixture(
    record: Record,
    cookie: String,
    csrf: String,
    user: Option[User],
    identity: Option[UserIdentity]) {
    def auth: Option[AuthContext] = user.map(u =>
      AuthContextProvider.user.copy(
        id = u.id,
        accountAuthentication = Some(AccountAuthentication(now.minusSeconds(30), Credential(CredentialMethod.Password, identity.get.id)))
      )
    )
    def activate(authenticated: Option[AuthContext] = auth, hash: Option[PasswordHash] = None): DBIO[InvitationActivationService.Result] =
      service.activateAction(cookie, record.revision.toString, authenticated, hash, origin, Some(csrf))
    def counts: DBIO[(Int, Int)] = for {
      members <-
        sql"SELECT count(*) FROM organization_user WHERE organization_id=${record.invitation.organizationId.toString}::uuid AND archived_at IS NULL"
          .as[Int]
          .head
      audits <-
        sql"SELECT count(*) FROM invitation_audit WHERE invitation_id=${record.invitation.id.toString}::uuid AND action='Accepted'".as[Int].head
    } yield (members, audits)
  }
  private def fixture(existing: Boolean): DBIO[Fixture] = for {
    actor <- withUser()
    org   <- withOrganization(allGenerators[Organization].normal.generate.copy(archivedAt = None))
    email = UserIdentityValue(s"invite-${UUID.randomUUID()}@example.test")
    user <- if (existing) withUser(allGenerators[User].normal.generate.copy(archivedAt = None, accountType = UserAccountType.Human)).map(Some(_))
            else DBIO.successful(None)
    identity <- user match {
                  case Some(u) =>
                    identities
                      .insert(
                        allGenerators[UserIdentity].normal.generate
                          .copy(userId = u.id, `type` = IdentityType.MailAddress, value = email, confirmed = UserIdentityConfirmed(true))
                      )
                      .map(Some(_))
                  case None => DBIO.successful(None)
                }
    _ <- identity.fold[DBIO[Unit]](DBIO.successful(()))(i => passwords.upsert(UserPassword(i.id, PasswordHash("original-hash"))).map(_ => ()))
    token = secrets.generate()
    invitation = allGenerators[Invitation].normal.generate.copy(
                   organizationId = org.id,
                   createdBy = actor.id,
                   modifiedBy = actor.id,
                   identityType = IdentityType.MailAddress,
                   identityValue = email,
                   roles = Seq(OrganizationUserRole.Manager),
                   expireAt = Some(InvitationExpireAt(now.plusSeconds(604800))),
                   acceptAt = None,
                   rejectAt = None,
                   archivedAt = None
                 )
    _      <- invitations.insert(Record(invitation, 2, Some("New"), Some("Person"), 0, 1, Some(Digest(secrets.hash(token))), None, Some("en"), None))
    record <- invitations.find(invitation.id)
    grant  <- access.exchangeAction(token, None).map(_.asInstanceOf[InvitationAccessService.Granted])
  } yield Fixture(record.get, grant.cookie, grant.dto.csrfToken, user, identity)

  private val samlRequests = new SamlRequestRepositoryImpl
  private val samlConfigs  = new SamlConfigRepositoryImpl
  private val samlBindings = new SamlIdentityBindingRepositoryImpl
  private def boundAuthentication(f: Fixture, organizationId: OrganizationId): DBIO[AuthContext] = for {
    _ <- if (organizationId == f.record.invitation.organizationId) DBIO.successful(())
         else
           memberships
             .upsert(OrganizationUser(OrganizationUserId(UUID.randomUUID()), organizationId, f.user.get.id, Seq(OrganizationUserRole.Employee), None))
             .map(_ => ())
    _ <- samlConfigs.upsert(
           allGenerators[SamlConfig].normal.generate
             .copy(organizationId = organizationId, idpEntityId = SamlIdpEntityId("bound-issuer"), isEnabled = SamlIsEnabled(true))
         )
    _ <- samlBindings.insert(
           SamlIdentityBinding(
             SamlBindingKey(organizationId, 1L, "bound-issuer", "bound-subject"),
             f.user.get.id,
             f.record.canonicalEmail.get,
             now.minusSeconds(30),
             io.circe.Json.obj()
           )
         )
  } yield f.auth.get
    .copy(accountAuthentication = Some(AccountAuthentication(now.minusSeconds(30), BoundSaml(organizationId, 1L, "bound-issuer", "bound-subject"))))
  private val saml = new SamlInvitationService(
    samlRequests,
    samlConfigs,
    samlBindings,
    invitations,
    accesses,
    new EmailIdentityRepositoryImpl,
    new pl.iterators.epoca.organization.repositories.OrganizationRepositoryImpl,
    users,
    identities,
    memberships,
    authentication,
    secrets,
    clock,
    Some(access)
  )

  private def throughSaml(
    f: Fixture,
    reuseEmail: Boolean = false,
    capturePrior: Boolean = false,
    bound: Boolean = false
  ): DBIO[Fixture] = for {
    config <- samlConfigs.upsert(
                allGenerators[SamlConfig].normal.generate.copy(
                  organizationId = f.record.invitation.organizationId,
                  idpEntityId = SamlIdpEntityId("issuer"),
                  isEnabled = SamlIsEnabled(true),
                  allowAccountInit = SamlAllowAccountInitialization(false),
                  validFor = SamlValidityDuration(java.time.Duration.ofMinutes(5))
                )
              )
    _ <- if (bound)
           samlBindings.insert(
             SamlIdentityBinding(
               SamlBindingKey(f.record.invitation.organizationId, 1L, "issuer", "subject"),
               f.user.get.id,
               f.record.canonicalEmail.get,
               now.minusSeconds(60),
               io.circe.Json.obj()
             )
           )
         else DBIO.successful(())
    previous <- if (reuseEmail) accesses.findByCookieHash(Digest(secrets.hash(f.cookie))) else DBIO.successful(None)
    request <- samlRequests.insert(
                 SamlRequest(
                   SamlRequestId("_" + UUID.randomUUID()),
                   None,
                   if (capturePrior) f.user.map(_.id) else None,
                   SamlRequestUsed(false),
                   SamlRequestCreatedAt(now),
                   accountAuthentication = if (capturePrior) f.auth.flatMap(_.accountAuthentication) else None,
                   organizationId = Some(f.record.invitation.organizationId),
                   trustEpoch = Some(1L),
                   purpose = Some(if (reuseEmail) "Invitation" else "Login"),
                   browserNonceHash = Some(secrets.hash("browser-nonce").toVector),
                   invitationAccessId = previous.map(_.id)
                 )
               )
    _ <- previous.fold[DBIO[Unit]](DBIO.successful(()))(a => accesses.update(a.copy(samlRequestId = Some(request.id)), a.generation).map(_ => ()))
    issued <- saml.authorizeAction(request, config, SamlAuthContext("subject", Map("email" -> List(f.record.canonicalEmail.get)), "issuer"))
    org    <- sql"SELECT slug FROM organization WHERE id=${f.record.invitation.organizationId.toString}::uuid".as[String].head
    result <- saml.exchangeAction(issued.get.token.get, OrganizationSlug(org), Some("browser-nonce"))
    next <- result match {
              case SamlInvitationService.Continuation(_, grant) => DBIO.successful(f.copy(cookie = grant.cookie, csrf = grant.dto.csrfToken))
              case _ => DBIO.failed(new IllegalStateException("Synthetic invitation did not produce a SAML continuation"))
            }
  } yield next

  private def legacy(f: Fixture): DBIO[Fixture] = for {
    user <- withUser(allGenerators[User].normal.generate.copy(archivedAt = None, accountType = UserAccountType.Human))
    identity <-
      identities.insert(
        allGenerators[UserIdentity].normal.generate
          .copy(userId = user.id, `type` = IdentityType.Saml, value = f.record.invitation.identityValue, confirmed = UserIdentityConfirmed(true))
      )
  } yield f.copy(user = Some(user), identity = Some(identity))

  private def run[A](action: DBIO[A]): A =
    scala.concurrent.Await.result(db.run(action.transactionally), scala.concurrent.duration.Duration(30, "seconds"))
  private def withCommitted[A](existing: Boolean)(test: Fixture => A): A = {
    val f = run(fixture(existing))
    try test(f)
    finally
      run(for {
        recipient <- new EmailIdentityRepositoryImpl().resolve(f.record.invitation.identityValue)
        ids =
          (recipient match { case Resolution.Single(user, _) => Vector(user.id); case _ => Vector.empty }) ++ Vector(f.record.invitation.createdBy)
        _ <- sqlu"DELETE FROM organization_user WHERE organization_id=${f.record.invitation.organizationId.toString}::uuid"
        _ <- sqlu"UPDATE invitation_access SET saml_request_id=NULL WHERE invitation_id=${f.record.invitation.id.toString}::uuid"
        _ <- sqlu"DELETE FROM saml_request WHERE organization_id=${f.record.invitation.organizationId.toString}::uuid"
        _ <- sqlu"DELETE FROM saml_identity_binding WHERE organization_id=${f.record.invitation.organizationId.toString}::uuid"
        _ <- sqlu"DELETE FROM saml_config WHERE organization_id=${f.record.invitation.organizationId.toString}::uuid"
        _ <- sqlu"DELETE FROM invitation_access WHERE invitation_id=${f.record.invitation.id.toString}::uuid"
        _ <- sqlu"DELETE FROM invitation_audit WHERE invitation_id=${f.record.invitation.id.toString}::uuid"
        _ <- sqlu"DELETE FROM invitation WHERE id=${f.record.invitation.id.toString}::uuid"
        _ <- sqlu"DELETE FROM organization WHERE id=${f.record.invitation.organizationId.toString}::uuid"
        _ <- DBIO.sequence(
               ids.distinct.map(id =>
                 DBIO.seq(
                   sqlu"DELETE FROM refresh_token WHERE user_id=${id.toString}::uuid",
                   sqlu"DELETE FROM user_login_token WHERE user_identity_id IN (SELECT id FROM user_identity WHERE user_id=${id.toString}::uuid)",
                   sqlu"DELETE FROM user_password WHERE user_identity_id IN (SELECT id FROM user_identity WHERE user_id=${id.toString}::uuid)",
                   sqlu"DELETE FROM user_identity WHERE user_id=${id.toString}::uuid",
                   sqlu"""DELETE FROM "user" WHERE id=${id.toString}::uuid"""
                 )
               )
             )
      } yield ())
  }
  Seq("recovery code", "unverified Firebase").foreach { method =>
    s"keep a signup email unconfirmed after $method and reject its password and invitation activation" in withCommitted(existing = false) { f =>
      import cats.effect.unsafe.implicits.global
      import pl.iterators.epoca.auth.domain.AuthDomain._
      import pl.iterators.epoca.auth.repositories._
      import pl.iterators.epoca.auth.routers.AuthRouter.LoginHttpRequestContext
      import pl.iterators.epoca.auth.services._
      import pl.iterators.epoca.mail.domain.MailDomain.MailAddress
      import pl.iterators.epoca.user.services._
      import scala.concurrent.duration._

      val tx              = new pl.iterators.epoca.utils.db.PostgresIOTransactor(db)
      val loginTokens     = new UserLoginTokenRepositoryImpl
      val passwordService = new PasswordService
      val tokenHash       = new LoginTokenHashService(LoginTokenHashService.Config(org.mindrot.jbcrypt.BCrypt.gensalt(4)))
      val tokenGenerator  = new LoginTokenGenerateService(LoginTokenGenerateService.Config(10.minutes), tokenHash)
      val userFinder      = new UserWithIdentitiesFindService(tx, users, identities)
      val mail            = org.mockito.Mockito.mock(classOf[pl.iterators.epoca.mail.emails.UserMagicLoginLink])
      val sms             = org.mockito.Mockito.mock(classOf[pl.iterators.epoca.sms.smses.UserTokenSms])
      val tokenGet = new LoginTokenGetService(
        LoginTokenGetService.Config("https://app.example.test", "/login/{token}/{email}"),
        tx,
        tokenGenerator,
        userFinder,
        users,
        loginTokens,
        mail,
        sms
      )
      val signup = new UserCreateService(
        UserCreateService.Config(None, 5),
        tx,
        creation,
        loginTokens,
        tokenGet,
        passwordService,
        new PasswordValidateService(PasswordValidateService.Config(8, None, false, false, false, false, false)),
        tokenGenerator
      )
      val resolver = new LoginResolveService(
        tx,
        LoginResolveService.Config(10.minutes, 1.day, RefreshTokenValidForStrategy.FromFirstUsage),
        new JwtService(JwtService.Config("synthetic-proof-chain-jwt-secret-32-characters")),
        new RefreshTokenRepositoryImpl,
        new RefreshTokenValueGeneratorService
      )
      val email    = MailAddress(f.record.invitation.identityValue)
      val password = PasswordPlain("synthetic-signup-password")
      val created = signup
        .create(
          UserCreateService
            .UserCreateRequest(UserFirstName("New"), UserLastName("Recipient"), None, None, Some(email), None, Some(password), None, Some(true))
        )
        .unsafeRunSync()
        .asInstanceOf[UserCreateService.UserCreateResult.Ok]
        .user
      val identity         = run(identities.list(UserIdentityRepository.UserIdentityFilters(userId = Some(created.user.id)))).head
      val originalPassword = run(passwords.find(identity.id))
      val requestContext   = allGenerators[LoginHttpRequestContext].normal.generate
      val before = (
        identity.confirmed,
        created.recoveryCodes.size,
        run(sql"SELECT count(*) FROM refresh_token WHERE user_id=${created.user.id.toString}::uuid".as[Int].head)
      )

      val rejected = if (method == "recovery code") {
        val login = new LoginByTokenService(tx, tokenHash, userFinder, loginTokens, memberships, identities, resolver)
        login
          .login(None, LoginByTokenService.LoginByTokenRequest(Some(email), None, created.recoveryCodes.head), requestContext)
          .unsafeRunSync() == LoginByTokenService.LoginByTokenResult.InvalidCredentials
      } else {
        val firebase = org.mockito.Mockito
          .mock(classOf[com.google.firebase.auth.FirebaseAuth], org.mockito.Mockito.withSettings().mockMaker(org.mockito.MockMakers.INLINE))
        val token = org.mockito.Mockito
          .mock(classOf[com.google.firebase.auth.FirebaseToken], org.mockito.Mockito.withSettings().mockMaker(org.mockito.MockMakers.INLINE))
        val provider = new FirebaseAuthProvider(FirebaseAuthProvider.Config(None)) {
          override lazy val firebaseAuth = Some(firebase)
        }
        org.mockito.Mockito.when(firebase.verifyIdToken("synthetic-unverified-token")).thenReturn(token)
        org.mockito.Mockito.when(token.getEmail).thenReturn(email)
        org.mockito.Mockito.when(token.isEmailVerified).thenReturn(false)
        val login = new LoginByFirebaseService(
          tx,
          provider,
          resolver,
          userFinder,
          memberships,
          identities,
          users,
          signup,
          org.mockito.Mockito.mock(classOf[pl.iterators.epoca.user.services.AvatarSyncService])
        )
        login
          .login(None, LoginByFirebaseService.LoginByFirebaseRequest("synthetic-unverified-token"), requestContext)
          .unsafeRunSync() == LoginByFirebaseService.LoginByFirebaseResult.InvalidCredentials
      }
      val passwordLogin = new LoginByPasswordService(tx, userFinder, memberships, users, passwords, passwordService, resolver)
        .login(None, LoginByPasswordService.LoginRequest(Some(email), None, password), requestContext)
        .unsafeRunSync()
      val storedIdentity = run(identities.find(UserIdentityRepository.UserIdentityFilters(id = Some(identity.id)))).get
      val sessionCounts = run(sql"""SELECT count(*), count(account_authentication) FROM refresh_token
          WHERE user_id=${created.user.id.toString}::uuid""".as[(Int, Int)].head)
      val activation = run(f.activate(None).asTry)

      val after = (rejected, passwordLogin == LoginByPasswordService.LoginResult.IdentityNotConfirmed, storedIdentity.confirmed, sessionCounts)
      before shouldEqual ((UserIdentityConfirmed(false), 5, 0))
      after shouldEqual ((true, true, UserIdentityConfirmed(false), (0, 0)))
      originalPassword.isDefined shouldEqual true
      run(passwords.find(identity.id)) shouldEqual originalPassword
      activation shouldEqual scala.util.Failure(InvitationActivationService.Rejected(InvitationActivationService.SignInRequired))
      run(f.counts) shouldEqual ((0, 0))
      run(invitations.find(f.record.invitation.id)).get shouldEqual f.record
      org.mockito.Mockito.verifyNoInteractions(mail, sms)
      success
    }
  }

  private def orderedRace[A, B](email: String)(first: DBIO[A], second: DBIO[B]): (A, B) = {
    import java.util.concurrent.{CountDownLatch, TimeUnit}
    import java.util.concurrent.atomic.AtomicInteger
    import scala.concurrent.Await
    import scala.concurrent.duration._
    val parallel = Database.forURL(
      sys.env("TEST_DB_URL"),
      sys.env.getOrElse("TEST_DB_USER", "postgres"),
      sys.env.getOrElse("TEST_DB_PASSWORD", "postgres"),
      driver = "org.postgresql.Driver",
      executor = AsyncExecutor("ct208-activation-races", 3, 3, 20, maxConnections = 3)
    )
    val locked    = new CountDownLatch(1)
    val release   = new CountDownLatch(1)
    val started   = new CountDownLatch(1)
    val firstPid  = new AtomicInteger()
    val secondPid = new AtomicInteger()
    try {
      val left = parallel.run((for {
        pid    <- sql"SELECT pg_backend_pid()".as[Int].head
        _      <- new EmailIdentityRepositoryImpl().lock(email)
        _      <- SimpleDBIO { _ => firstPid.set(pid); locked.countDown(); require(release.await(20, TimeUnit.SECONDS), "release first writer") }
        result <- first
      } yield result).transactionally)
      require(locked.await(20, TimeUnit.SECONDS), "first writer owns email reservation")
      val right = parallel.run((for {
        pid    <- sql"SELECT pg_backend_pid()".as[Int].head
        _      <- SimpleDBIO { _ => secondPid.set(pid); started.countDown() }
        result <- second
      } yield result).transactionally)
      require(started.await(20, TimeUnit.SECONDS), "second writer started")
      require(firstPid.get() != secondPid.get(), "independent PostgreSQL connections")
      val deadline = 10.seconds.fromNow
      require(
        Iterator
          .continually(run(sql"SELECT ${firstPid.get()} = ANY(pg_blocking_pids(${secondPid.get()}))".as[Boolean].head))
          .takeWhile(_ => deadline.hasTimeLeft())
          .exists(identity),
        "competing writer waits for email reservation"
      )
      release.countDown()
      (Await.result(left, 30.seconds), Await.result(right, 30.seconds))
    } finally { release.countDown(); parallel.close() }
  }

  for {
    operation   <- Seq("cancel", "role")
    acceptFirst <- Seq(true, false)
  }
    s"serialize acceptance and $operation with acceptance first=$acceptFirst" in withCommitted(existing = true) { initial =>
      import io.circe.Json
      import pl.iterators.epoca.organization.domain.OrganizationUser
      val f     = initial
      val actor = f.record.invitation.createdBy
      run(sqlu"""UPDATE "user" SET archived_at=NULL, account_type='Human' WHERE id=${actor.toString}::uuid""")
      run(
        memberships.insertPrepared(
          OrganizationUser(OrganizationUserId.random, f.record.invitation.organizationId, actor, Seq(OrganizationUserRole.Owner), None)
        )
      )
      val slug =
        OrganizationSlug(run(sql"SELECT slug FROM organization WHERE id=${f.record.invitation.organizationId.toString}::uuid".as[String].head))
      val management = new InvitationManagementService(
        null,
        invitations,
        new InvitationRepositoryImpl,
        new EmailIdentityRepositoryImpl,
        new InvitationAccessGuard,
        secrets,
        clock
      )
      val body = Json
        .obj("expectedRevision" -> Json.fromString(f.record.revision.toString))
        .deepMerge(operation match {
          case "role" => Json.obj("role" -> Json.fromString("Employee"))
          case _      => Json.obj()
        })
      val edit =
        if (operation == "cancel") management.cancelAction(actor, slug, f.record.invitation.id, Some(f.record.revision))
        else management.patchAction(actor, slug, f.record.invitation.id, body)
      val (accepted, changed) =
        if (acceptFirst) orderedRace(f.record.canonicalEmail.get)(f.activate(), edit)
        else { val result = orderedRace(f.record.canonicalEmail.get)(edit, f.activate()); (result._2, result._1) }
      if (acceptFirst) {
        accepted must beAnInstanceOf[InvitationActivationService.Activated]
        changed mustEqual InvitationManagementService.InvitationNotPending
      } else {
        changed must beAnInstanceOf[InvitationManagementService.Ok]
        accepted mustEqual (if (operation == "cancel") InvitationActivationService.InvalidInvitation
                            else InvitationActivationService.RevisionConflict)
        run(f.counts) mustEqual (1, 0)
        if (operation != "cancel") {
          val current = run(invitations.find(f.record.invitation.id)).get
          run(f.copy(record = current).activate()) must beAnInstanceOf[InvitationActivationService.Activated]
        }
      }
      val active = acceptFirst || operation != "cancel"
      run(f.counts) mustEqual (if (active) (2, 1) else (1, 0))
      if (active) {
        val member = run(
          memberships.find(
            pl.iterators.epoca.organization.repositories.OrganizationUserRepository
              .OrganizationUserFilters(organizationId = Some(f.record.invitation.organizationId), userId = Some(f.user.get.id))
          )
        ).get
        member.roles mustEqual Seq(if (!acceptFirst && operation == "role") OrganizationUserRole.Employee else OrganizationUserRole.Manager)
      }
      run(passwords.find(f.identity.get.id)).get.password mustEqual PasswordHash("original-hash")
      run(sql"SELECT count(*) FROM user_identity WHERE user_id=${f.user.get.id.toString}::uuid".as[Int].head) mustEqual 1
    }
  "serialize two acceptances with exactly one account, membership and audit" in withCommitted(existing = false) { initial =>
    val f = initial
    val (left, right) = orderedRace(f.record.canonicalEmail.get)(
      f.activate(hash = Some(PasswordHash("first-password"))),
      f.activate(hash = Some(PasswordHash("second-password")))
    )
    left must beAnInstanceOf[InvitationActivationService.Activated]
    right mustEqual InvitationActivationService.SignInRequired
    run(f.counts) mustEqual (1, 1)
    val Resolution.Single(user, matching) = run(new EmailIdentityRepositoryImpl().resolve(f.record.canonicalEmail.get)): @unchecked
    left.asInstanceOf[InvitationActivationService.Activated].userId mustEqual user.id
    matching.size mustEqual 1
    run(passwords.find(matching.head.id)).get.password mustEqual PasswordHash("first-password")
    run(sql"SELECT count(*) FROM user_identity WHERE user_id=${user.id.toString}::uuid".as[Int].head) mustEqual 1
  }
  for {
    kind        <- Seq(IdentityType.MailAddress, IdentityType.Saml)
    acceptFirst <- Seq(true, false)
  }
    s"serialize $kind account creation and acceptance with acceptance first=$acceptFirst" in withCommitted(existing = false) { initial =>
      val f        = initial
      val newcomer = allGenerators[User].normal.generate.copy(archivedAt = None, accountType = UserAccountType.Human)
      val identity = allGenerators[UserIdentity].normal.generate.copy(
        userId = newcomer.id,
        `type` = kind,
        value = UserIdentityValue(f.record.canonicalEmail.get.toUpperCase(java.util.Locale.ROOT)),
        main = UserIdentityMain(true),
        confirmed = UserIdentityConfirmed(true)
      )
      val create = creation.create(newcomer, identity, if (kind == IdentityType.MailAddress) Some(PasswordHash("signup-password")) else None)
      val accept = f.activate(hash = Some(PasswordHash("invitation-password"))).asTry
      val (accepted, created) =
        if (acceptFirst) orderedRace(f.record.canonicalEmail.get)(accept, create)
        else { val result = orderedRace(f.record.canonicalEmail.get)(create, accept); (result._2, result._1) }
      if (acceptFirst) {
        accepted.get must beAnInstanceOf[InvitationActivationService.Activated]
        created must beNone
        run(f.counts) mustEqual (1, 1)
      } else {
        created must beSome
        accepted mustEqual scala.util.Failure(InvitationActivationService.Rejected(InvitationActivationService.SignInRequired))
        run(f.counts) mustEqual (0, 0)
      }
      val Resolution.Single(user, matching) = run(new EmailIdentityRepositoryImpl().resolve(f.record.canonicalEmail.get)): @unchecked
      matching.size mustEqual 1
      run(sql"SELECT count(*) FROM user_identity WHERE user_id=${user.id.toString}::uuid".as[Int].head) mustEqual 1
      val password = run(passwords.find(matching.head.id)).map(_.password)
      password mustEqual (if (acceptFirst) Some(PasswordHash("invitation-password"))
                          else if (kind == IdentityType.MailAddress) Some(PasswordHash("signup-password"))
                          else None)
      run(sql"""SELECT count(*) FROM "user" WHERE id=${newcomer.id.toString}::uuid""".as[Int].head) mustEqual (if (acceptFirst) 0 else 1)
    }

  "serialize acceptance against the actual add-alias service" in withCommitted(existing = false) { initial =>
    import cats.effect.IO
    import cats.effect.unsafe.implicits.global
    import org.mockito.Mockito.{mock, when}
    import org.mockito.ArgumentMatchers.any
    import pl.iterators.epoca.auth.repositories.AccountAuthenticationRepositoryImpl
    import pl.iterators.epoca.auth.services.{AccountAuthenticationValidator, TimeBasedOneTimePasswordService}
    import pl.iterators.epoca.mail.emails.UserIdentityConfirmationCodeMail
    import pl.iterators.epoca.sms.services.SmsService
    import pl.iterators.epoca.user.services.UserIdentityCreateService
    import pl.iterators.epoca.user.services.UserIdentityCreateService.{UserIdentityCreateRequest, UserIdentityCreateResult}
    import pl.iterators.epoca.utils.db.PostgresIOTransactor
    import java.util.concurrent.{CountDownLatch, TimeUnit}
    import scala.concurrent.Await
    import scala.concurrent.duration._
    val f     = initial
    val owner = run(withUser(allGenerators[User].normal.generate.copy(archivedAt = None, accountType = UserAccountType.Human)))
    val credential = run(
      identities.insert(
        allGenerators[UserIdentity].normal.generate.copy(
          userId = owner.id,
          `type` = IdentityType.TimeBasedOneTimePassword,
          value = UserIdentityValue("BASE32"),
          main = UserIdentityMain(false),
          confirmed = UserIdentityConfirmed(true)
        )
      )
    )
    val parallel = Database.forURL(
      sys.env("TEST_DB_URL"),
      sys.env.getOrElse("TEST_DB_USER", "postgres"),
      sys.env.getOrElse("TEST_DB_PASSWORD", "postgres"),
      driver = "org.postgresql.Driver",
      executor = AsyncExecutor("ct208-accept-alias", 3, 3, 20, maxConnections = 3)
    )
    val barrier = new CountDownLatch(2)
    val pids    = java.util.concurrent.ConcurrentHashMap.newKeySet[Int]()
    def gate: DBIO[Unit] = sql"SELECT pg_backend_pid()".as[Int].head.flatMap { pid =>
      SimpleDBIO { _ =>
        pids.add(pid); barrier.countDown(); require(barrier.await(20, TimeUnit.SECONDS), "both invitation and alias transactions started")
      }
    }
    val tx = new PostgresIOTransactor(parallel) {
      override def executeTransactionally[A](action: => DBIO[A]): IO[A] = super.executeTransactionally(gate.flatMap(_ => action))
    }
    val mail = mock(classOf[UserIdentityConfirmationCodeMail])
    when(mail.send(any[UserIdentityConfirmationCodeMail.Event])).thenReturn(IO.unit)
    val aliases = new UserIdentityCreateService(
      tx,
      mail,
      mock(classOf[SmsService]),
      users,
      identities,
      new EmailIdentityRepositoryImpl,
      mock(classOf[TimeBasedOneTimePasswordService]),
      new AccountAuthenticationValidator(new AccountAuthenticationRepositoryImpl, identities, passwords)
    )
    val at = Instant.now()
    val auth = AuthContext(
      owner.id,
      owner.isSuperAdmin,
      Nil,
      Set.empty,
      Nil,
      at,
      true,
      Some(AccountAuthentication(at, Credential(CredentialMethod.TimeBasedOneTimePassword, credential.id)))
    )
    try {
      val activation = parallel.run(gate.flatMap(_ => f.activate(hash = Some(PasswordHash("invitation-password")))).transactionally.asTry)
      val alias = aliases
        .create(
          auth,
          UserIdentityCreateRequest(
            IdentityType.MailAddress,
            UserIdentityValue(f.record.canonicalEmail.get.toUpperCase(java.util.Locale.ROOT)),
            None,
            None,
            UserIdentityMain(true)
          )
        )
        .unsafeToFuture()
      val accepted = Await.result(activation, 30.seconds)
      val added    = Await.result(alias, 30.seconds)
      pids.size() mustEqual 2
      val joined = accepted.toOption.exists(_.isInstanceOf[InvitationActivationService.Activated])
      joined mustEqual !added.isInstanceOf[UserIdentityCreateResult.Ok]
      if (joined) added mustEqual UserIdentityCreateResult.IdentityValueAlreadyExist
      else accepted mustEqual scala.util.Failure(InvitationActivationService.Rejected(InvitationActivationService.SignInRequired))
      run(f.counts) mustEqual (if (joined) (1, 1) else (0, 0))
      val Resolution.Single(_, matching) = run(new EmailIdentityRepositoryImpl().resolve(f.record.canonicalEmail.get)): @unchecked
      matching.size mustEqual 1
      run(passwords.find(matching.head.id)).map(_.password) mustEqual (if (joined) Some(PasswordHash("invitation-password")) else None)
    } finally {
      parallel.close()
      run(
        DBIO.seq(
          sqlu"DELETE FROM user_identity WHERE user_id=${owner.id.toString}::uuid",
          sqlu"""DELETE FROM "user" WHERE id=${owner.id.toString}::uuid"""
        )
      )
    }
  }

  "security regression" should {
    "deny anonymous completed activation replay instead of authenticating from the invitation cookie" in withDatabase {
      for {
        f      <- fixture(existing = true)
        _      <- f.activate()
        replay <- f.activate(authenticated = None)
      } yield replay mustEqual InvitationActivationService.SignInRequired
    }
    "deny stale account authentication on completed activation replay" in withDatabase {
      for {
        f <- fixture(existing = true)
        _ <- f.activate()
        stale = f.auth.map(a => a.copy(accountAuthentication = a.accountAuthentication.map(_.copy(authenticatedAt = now.minusSeconds(301)))))
        replay <- f.activate(authenticated = stale)
      } yield replay mustEqual InvitationActivationService.SignInRequired
    }
    "require mailbox verification before creating a global SAML account from an invitation" in withDatabase {
      for {
        f      <- fixture(existing = false).flatMap(throughSaml(_))
        result <- f.activate(authenticated = None).asTry
        owner  <- new EmailIdentityRepositoryImpl().resolve(f.record.invitation.identityValue)
      } yield {
        result.failed.toOption must beSome(InvitationActivationService.Rejected(InvitationActivationService.SignInRequired))
        owner mustEqual Resolution.Empty
      }
    }
  }

  "verified SAML invitation activation" should {
    "reject a different organization's SAML authority despite a matching confirmed recipient contact" in withDatabase {
      for {
        f      <- fixture(existing = true)
        other  <- withOrganization(allGenerators[Organization].normal.generate.copy(archivedAt = None))
        auth   <- boundAuthentication(f, other.id)
        valid  <- authentication.validate(auth, now)
        result <- f.activate(authenticated = Some(auth)).asTry
        counts <- f.counts
      } yield {
        valid must beTrue
        result.failed.toOption must beSome(InvitationActivationService.Rejected(InvitationActivationService.SignInRequired))
        counts mustEqual (0, 0)
      }
    }
    "reject a different organization's SAML authority when replaying a completed invitation" in withDatabase {
      for {
        f      <- fixture(existing = true)
        _      <- f.activate()
        other  <- withOrganization(allGenerators[Organization].normal.generate.copy(archivedAt = None))
        auth   <- boundAuthentication(f, other.id)
        valid  <- authentication.validate(auth, now)
        result <- f.activate(authenticated = Some(auth))
        counts <- f.counts
      } yield {
        valid must beTrue
        result mustEqual InvitationActivationService.SignInRequired
        counts mustEqual (1, 1)
      }
    }
    "reject stored SAML authentication from another organization during anonymous continuation" in withDatabase {
      for {
        f             <- fixture(existing = true).flatMap(throughSaml(_, reuseEmail = true, capturePrior = true))
        other         <- withOrganization(allGenerators[Organization].normal.generate.copy(archivedAt = None))
        auth          <- boundAuthentication(f, other.id)
        context       <- accesses.findByCookieHash(Digest(secrets.hash(f.cookie)))
        request       <- samlRequests.find(SamlRequestId(context.get.samlRequestId.get))
        _             <- samlRequests.update(request.get.copy(accountAuthentication = auth.accountAuthentication))
        reconstructed <- actions.serverAuthentication(context.get, Set(auth.id), Set(other.id, f.record.invitation.organizationId))
        result        <- f.activate(authenticated = None).asTry
        counts        <- f.counts
      } yield {
        reconstructed must beNone
        result.failed.toOption must beSome(InvitationActivationService.Rejected(InvitationActivationService.SignInRequired))
        counts mustEqual (0, 0)
      }
    }
    "allow current SAML authority for the invitation's own organization" in withDatabase {
      for {
        f      <- fixture(existing = true)
        auth   <- boundAuthentication(f, f.record.invitation.organizationId)
        result <- f.activate(authenticated = Some(auth))
        counts <- f.counts
      } yield {
        result must beAnInstanceOf[InvitationActivationService.Activated]
        counts mustEqual (1, 1)
      }
    }
    "classify a fresh confirmed recipient as Accept without changing context TTL or activation state" in withDatabase {
      for {
        f      <- fixture(existing = true)
        before <- accesses.findByCookieHash(Digest(secrets.hash(f.cookie)))
        next   <- service.nextActionAction(f.cookie, f.auth.get)
        after  <- accesses.findByCookieHash(Digest(secrets.hash(f.cookie)))
        counts <- f.counts
      } yield {
        next must beSome("Accept")
        after mustEqual before
        counts mustEqual ((0, 0))
      }
    }
    "decline stale original authentication when classifying a summary" in withDatabase {
      for {
        f <- fixture(existing = true)
        stale = f.auth.get.copy(accountAuthentication = f.auth.get.accountAuthentication.map(_.copy(authenticatedAt = now.minusSeconds(301))))
        next <- service.nextActionAction(f.cookie, stale).asTry
      } yield next.failed.get must beAnInstanceOf[InvitationActivationService.Rejected]
    }

    "deny legacy bootstrap if any other identity of the account has a password" in withDatabase {
      for {
        initial <- fixture(existing = false).flatMap(legacy)
        other <- identities.insert(
                   allGenerators[UserIdentity].normal.generate.copy(
                     userId = initial.user.get.id,
                     `type` = IdentityType.MailAddress,
                     value = UserIdentityValue(s"other-${UUID.randomUUID()}@example.test"),
                     confirmed = UserIdentityConfirmed(true)
                   )
                 )
        _       <- passwords.upsert(UserPassword(other.id, PasswordHash("password-on-another-alias")))
        f       <- throughSaml(initial, reuseEmail = true)
        result  <- f.activate(authenticated = None).asTry
        binding <- samlBindings.find(SamlBindingKey(f.record.invitation.organizationId, 1L, "issuer", "subject"))
        counts  <- f.counts
      } yield {
        result.failed.get mustEqual InvitationActivationService.Rejected(InvitationActivationService.SignInRequired)
        binding must beNone
        counts mustEqual (0, 0)
      }
    }
    "keep an unconfirmed recipient alias protected even with current SAML and fresh authentication through another own identity" in withDatabase {
      for {
        initial <- fixture(existing = true)
        _       <- identities.update(initial.identity.get.copy(confirmed = UserIdentityConfirmed(false)))
        other <- identities.insert(
                   allGenerators[UserIdentity].normal.generate.copy(
                     userId = initial.user.get.id,
                     `type` = IdentityType.MailAddress,
                     value = UserIdentityValue(s"other-${UUID.randomUUID()}@example.test"),
                     confirmed = UserIdentityConfirmed(true)
                   )
                 )
        _ <- passwords.upsert(UserPassword(other.id, PasswordHash("other-password")))
        f <- throughSaml(initial)
        auth = initial.auth.map(_.copy(accountAuthentication = Some(AccountAuthentication(now, Credential(CredentialMethod.Password, other.id)))))
        result  <- f.activate(authenticated = auth).asTry
        binding <- samlBindings.find(SamlBindingKey(f.record.invitation.organizationId, 1L, "issuer", "subject"))
      } yield { result.failed.get mustEqual InvitationActivationService.Rejected(InvitationActivationService.IdentityMismatch); binding must beNone }
    }
    "never create a duplicate when a current bound account loses its recipient identity after exchange" in withDatabase {
      for {
        f         <- fixture(existing = true).flatMap(throughSaml(_, bound = true))
        _         <- sqlu"DELETE FROM user_password WHERE user_identity_id=${f.identity.get.id.toString}::uuid"
        _         <- identities.purge(UserIdentityRepository.UserIdentityFilters(id = f.identity.map(_.id)))
        result    <- f.activate(authenticated = None).asTry
        remaining <- sql"SELECT count(*) FROM user_identity WHERE value=${f.record.canonicalEmail.get}".as[Int].head
        counts    <- f.counts
      } yield { result.isFailure must beTrue; remaining mustEqual 0; counts mustEqual (0, 0) }
    }
    Seq("Unconsumed", "WrongSubject", "ExpiredProof", "Epoch", "Disabled", "MissingRequest").foreach { changed =>
      s"require password fallback for $changed SAML proof without account binding or membership writes" in withDatabase {
        for {
          f       <- fixture(existing = false).flatMap(throughSaml(_, reuseEmail = true))
          context <- accesses.findByCookieHash(Digest(secrets.hash(f.cookie)))
          request <- samlRequests.find(SamlRequestId(context.get.samlRequestId.get))
          _ <- changed match {
                 case "Unconsumed"   => samlRequests.update(request.get.copy(used = SamlRequestUsed(false))).map(_ => ())
                 case "WrongSubject" => samlRequests.update(request.get.copy(verifiedSubject = Some("substituted"))).map(_ => ())
                 case "ExpiredProof" =>
                   accesses
                     .update(
                       context.get
                         .copy(proof = context.get.proof.copy(saml = context.get.proof.saml.map(_.copy(verifiedAt = now.minusSeconds(901))))),
                       1
                     )
                     .map(_ => ())
                 case "Epoch" =>
                   sqlu"UPDATE saml_config SET trust_epoch=trust_epoch+1 WHERE organization_id=${f.record.invitation.organizationId.toString}::uuid"
                     .map(_ => ())
                 case "Disabled" =>
                   sqlu"UPDATE saml_config SET is_enabled=false WHERE organization_id=${f.record.invitation.organizationId.toString}::uuid".map(_ =>
                     ()
                   )
                 case _ => accesses.update(context.get.copy(samlRequestId = None), 1).map(_ => ())
               }
          result  <- f.activate(authenticated = None).asTry
          binding <- samlBindings.find(SamlBindingKey(f.record.invitation.organizationId, 1L, "issuer", "subject"))
          owned   <- sql"SELECT count(*) FROM user_identity WHERE value=${f.record.canonicalEmail.get}".as[Int].head
          counts  <- f.counts
        } yield {
          result.failed.toOption mustEqual Some(InvitationActivationService.Rejected(InvitationActivationService.PasswordRequired))
          binding must beNone
          owned mustEqual 0
          counts mustEqual (0, 0)
        }
      }
    }
    "activate a SAML-only new recipient without hashing and issue a session only after binding and membership commit" in withCommitted(existing =
      false
    ) { initial =>
      import cats.effect.IO
      import cats.effect.unsafe.implicits.global
      import pl.iterators.epoca.auth.domain.AuthDomain.{DeviceId, Jwt, PasswordPlain}
      import pl.iterators.epoca.auth.routers.AuthRouter.LoginHttpRequestContext
      import pl.iterators.epoca.auth.services.{LoginResolveService, PasswordService}
      import pl.iterators.epoca.utils.db.PostgresIOTransactor
      val f          = run(throughSaml(initial, reuseEmail = true))
      val transactor = new PostgresIOTransactor(db)
      val limits     = new InvitationRateLimitService(transactor, accesses, Array.fill[Byte](32)(9), clock)
      val issued     = new java.util.concurrent.atomic.AtomicReference[AuthContext]()
      val hashing = new PasswordService {
        override def encrypt(password: PasswordPlain): PasswordHash = throw new IllegalStateException("SAML-only activation must not hash")
      }
      val login = new LoginResolveService(null, null, null, null, null) {
        override def resolve(request: LoginResolveService.LoginResolveRequest): IO[LoginResolveService.LoginResolution] = IO {
          assert(run(f.counts) == (1, 1))
          assert(run(samlBindings.find(SamlBindingKey(f.record.invitation.organizationId, 1L, "issuer", "subject"))).isDefined)
          issued.set(request.toAuthContext(java.time.ZonedDateTime.ofInstant(now, ZoneOffset.UTC)))
          LoginResolveService.LoginResolution(Jwt("access"), Jwt("refresh"))
        }
      }
      val sut     = new InvitationActivationService(transactor, access, actions, hashing, null, login, limits, browser, clock)
      val request = LoginHttpRequestContext(DeviceId("saml-invitation-device"), None)
      sut
        .activate(f.cookie, "0", None, None, origin, Some(f.csrf), request)
        .unsafeRunSync() must beAnInstanceOf[InvitationActivationService.SessionJoined]
      issued.get().accountAuthentication.map(_.authenticatedAt) must beSome(now)
      issued.get().accountAuthentication.get.evidence must beAnInstanceOf[pl.iterators.epoca.auth.domain.AccountAuthentication.BoundSaml]
      val authenticated = issued.get()
      sut.activate(f.cookie, "0", None, None, origin, Some(f.csrf), request).unsafeRunSync() mustEqual InvitationActivationService.SignInRequired
      issued.get() mustEqual authenticated
      sut
        .activate(f.cookie, "0", None, Some(authenticated), origin, Some(f.csrf), request)
        .unsafeRunSync() must beAnInstanceOf[InvitationActivationService.SessionJoined]
      issued.get().accountAuthentication mustEqual authenticated.accountAuthentication
      issued.get().fresh must beTrue
    }
    "roll back SAML account binding when the membership write fails" in withCommitted(existing = false) { initial =>
      val f      = run(throughSaml(initial, reuseEmail = true))
      val result = scala.util.Try(run(failingService.activateAction(f.cookie, "0", None, None, origin, Some(f.csrf))))
      result.failed.get mustEqual MembershipWriteFailure
      InvitationActivationService.recover(result.failed.get) must beNone
      run(samlBindings.find(SamlBindingKey(f.record.invitation.organizationId, 1L, "issuer", "subject"))) must beNone
      run(new EmailIdentityRepositoryImpl().resolve(f.record.invitation.identityValue)) mustEqual Resolution.Empty
      run(f.counts) mustEqual (0, 0)
      run(accesses.findByCookieHash(Digest(secrets.hash(f.cookie)))).get.completedAt must beNone
    }
    "write chosen Manager role for passwordless SAML activation" in withDatabase {
      for {
        f      <- fixture(existing = false).flatMap(throughSaml(_, reuseEmail = true))
        result <- f.activate(authenticated = None)
        members <- memberships.list(
                     pl.iterators.epoca.organization.repositories.OrganizationUserRepository
                       .OrganizationUserFilters(organizationId = Some(f.record.invitation.organizationId))
                   )
      } yield {
        result must beAnInstanceOf[InvitationActivationService.Activated]
        members.map(_.roles) mustEqual Seq(Seq(OrganizationUserRole.Manager))
      }
    }
    "create only a passwordless SAML identity and binding at explicit Manager acceptance after mailbox verification" in withDatabase {
      for {
        f       <- fixture(existing = false).flatMap(throughSaml(_, reuseEmail = true))
        before  <- f.counts
        context <- accesses.findByCookieHash(Digest(secrets.hash(f.cookie)))
        result  <- f.activate(authenticated = None).asTry
        record  <- invitations.find(f.record.invitation.id)
        owned <- record.get.acceptedUserId.fold[DBIO[Seq[UserIdentity]]](DBIO.successful(Seq.empty))(id =>
                   identities.list(UserIdentityRepository.UserIdentityFilters(userId = Some(id)))
                 )
        passwordCount <-
          sql"SELECT count(*) FROM user_password WHERE user_identity_id IN (SELECT id FROM user_identity WHERE value=${f.record.invitation.identityValue.toString})"
            .as[Int]
            .head
        binding <- samlBindings.find(SamlBindingKey(f.record.invitation.organizationId, 1L, "issuer", "subject"))
        members <- memberships.list(
                     pl.iterators.epoca.organization.repositories.OrganizationUserRepository
                       .OrganizationUserFilters(organizationId = Some(f.record.invitation.organizationId))
                   )
      } yield {
        before mustEqual (0, 0)
        context.get.proof.emailVerifiedAt must beSome(now)
        result.toOption
          .collect { case value: InvitationActivationService.Activated => value }
          .map(_.authentication.map(_.authenticatedAt)) must beSome(Some(now))
        owned.map(_.`type`) mustEqual Seq(IdentityType.Saml)
        owned.forall(_.confirmed) must beTrue
        passwordCount mustEqual 0
        binding.map(_.userId) mustEqual record.get.acceptedUserId
        members.map(_.roles) mustEqual Seq(Seq(OrganizationUserRole.Manager))
      }
    }
    "retain a fresh captured password proof and bind the existing confirmed recipient only at acceptance" in withDatabase {
      for {
        f        <- fixture(existing = true).flatMap(throughSaml(_, capturePrior = true))
        before   <- samlBindings.find(SamlBindingKey(f.record.invitation.organizationId, 1L, "issuer", "subject"))
        result   <- f.activate(authenticated = None).asTry
        after    <- samlBindings.find(SamlBindingKey(f.record.invitation.organizationId, 1L, "issuer", "subject"))
        password <- passwords.find(f.identity.get.id)
      } yield {
        before must beNone
        result.toOption.collect { case value: InvitationActivationService.Activated => value }.flatMap(_.authentication) mustEqual f.auth.flatMap(
          _.accountAuthentication
        )
        after.map(_.userId) mustEqual f.user.map(_.id)
        password.get.password mustEqual PasswordHash("original-hash")
      }
    }
    "accept a currently bound account from verified server evidence without a client global session" in withDatabase {
      for {
        f      <- fixture(existing = true).flatMap(throughSaml(_, bound = true))
        result <- f.activate(authenticated = None).asTry
        replay <- f.activate(authenticated = None).asTry
      } yield {
        result.toOption
          .collect { case value: InvitationActivationService.Activated => value }
          .flatMap(_.authentication)
          .map(_.authenticatedAt) must beSome(now)
        replay.toOption mustEqual Some(InvitationActivationService.SignInRequired)
      }
    }
    "deny legacy account adoption even with both same-invitation email and current SAML proofs" in withDatabase {
      for {
        f       <- fixture(existing = false).flatMap(legacy).flatMap(throughSaml(_, reuseEmail = true))
        result  <- f.activate(authenticated = None).asTry
        owned   <- identities.list(UserIdentityRepository.UserIdentityFilters(userId = f.user.map(_.id)))
        binding <- samlBindings.find(SamlBindingKey(f.record.invitation.organizationId, 1L, "issuer", "subject"))
      } yield {
        result.failed.get mustEqual InvitationActivationService.Rejected(InvitationActivationService.SignInRequired)
        owned.map(_.id) mustEqual Seq(f.identity.get.id)
        binding must beNone
      }
    }
    "require independent authentication for an existing legacy account with only SAML proof" in withDatabase {
      for {
        f       <- fixture(existing = false).flatMap(legacy).flatMap(throughSaml(_))
        result  <- f.activate(authenticated = None).asTry
        counts  <- f.counts
        binding <- samlBindings.find(SamlBindingKey(f.record.invitation.organizationId, 1L, "issuer", "subject"))
      } yield {
        result.failed.get mustEqual InvitationActivationService.Rejected(InvitationActivationService.SignInRequired); counts mustEqual (0, 0);
        binding must beNone
      }
    }
    "revalidate captured account proof and reject a password removed after callback exchange" in withDatabase {
      for {
        f       <- fixture(existing = true).flatMap(throughSaml(_, capturePrior = true))
        _       <- sqlu"DELETE FROM user_password WHERE user_identity_id=${f.identity.get.id.toString}::uuid"
        result  <- f.activate(authenticated = None).asTry
        binding <- samlBindings.find(SamlBindingKey(f.record.invitation.organizationId, 1L, "issuer", "subject"))
      } yield { result.failed.get mustEqual InvitationActivationService.Rejected(InvitationActivationService.SignInRequired); binding must beNone }
    }
  }

  "transactional invitation activation" should {
    "create one new account, confirmed identity, password and chosen-role membership" in withDatabase {
      for {
        f        <- fixture(existing = false)
        _        <- f.activate(hash = Some(PasswordHash("new-hash")))
        current  <- invitations.find(f.record.invitation.id)
        owned    <- identities.list(UserIdentityRepository.UserIdentityFilters(userId = current.get.acceptedUserId))
        password <- passwords.find(owned.head.id)
        member <- memberships.find(
                    pl.iterators.epoca.organization.repositories.OrganizationUserRepository
                      .OrganizationUserFilters(organizationId = Some(f.record.invitation.organizationId), userId = current.get.acceptedUserId)
                  )
        counts <- f.counts
      } yield {
        current.get.acceptedUserId must beSome
        owned.size mustEqual 1
        owned.head.confirmed must beTrue
        password.get.password mustEqual PasswordHash("new-hash")
        member.get.roles mustEqual Seq(OrganizationUserRole.Manager)
        counts mustEqual (1, 1)
      }
    }
    "join an existing account without overwriting its profile or password" in withDatabase {
      for {
        f        <- fixture(existing = true)
        _        <- f.activate()
        after    <- users.find(UserRepository.UserFilters(id = f.user.map(_.id)))
        password <- passwords.find(f.identity.get.id)
        counts   <- f.counts
      } yield {
        after mustEqual f.user
        password.get.password mustEqual PasswordHash("original-hash")
        counts mustEqual (1, 1)
      }
    }
    "preserve a later role change on accepted-context replay" in withDatabase {
      for {
        f <- fixture(existing = true)
        _ <- f.activate()
        _ <-
          sqlu"UPDATE organization_user SET roles=ARRAY['Employee']::text[] WHERE organization_id=${f.record.invitation.organizationId.toString}::uuid"
        _      <- f.activate(authenticated = None)
        roles  <- sql"SELECT roles::text FROM organization_user WHERE organization_id=${f.record.invitation.organizationId.toString}::uuid".as[String]
        counts <- f.counts
      } yield {
        roles mustEqual Vector("{Employee}")
        counts mustEqual (1, 1)
      }
    }
    "reactivate an archived membership with the invitation role and original row id" in withDatabase {
      for {
        f <- fixture(existing = true)
        memberId = UUID.randomUUID().toString
        _ <-
          sqlu"INSERT INTO organization_user(id,organization_id,user_id,roles,archived_at) VALUES ($memberId::uuid,${f.record.invitation.organizationId.toString}::uuid,${f.user.get.id.toString}::uuid,ARRAY['Employee']::text[],now())"
        _ <- f.activate()
        rows <-
          sql"SELECT id::text,roles::text,archived_at IS NULL FROM organization_user WHERE organization_id=${f.record.invitation.organizationId.toString}::uuid"
            .as[(String, String, Boolean)]
      } yield rows mustEqual Vector((memberId.toString, "{Manager}", true))
    }
    Seq("absent", "old", "future", "unconfirmed", "wrong-user", "archived").foreach { failure =>
      s"reject $failure account authentication without writes" in withDatabase {
        for {
          f <- fixture(existing = true)
          _ <- if (failure == "unconfirmed") identities.update(f.identity.get.copy(confirmed = UserIdentityConfirmed(false))).map(_ => ())
               else if (failure == "archived") users.update(f.user.get.copy(archivedAt = Some(UserArchivedAt(now)))).map(_ => ())
               else DBIO.successful(())
          proof = f.auth.get.accountAuthentication.get
          auth = failure match {
                   case "absent"     => None
                   case "old"        => f.auth.map(_.copy(accountAuthentication = Some(proof.copy(authenticatedAt = now.minusSeconds(301)))))
                   case "future"     => f.auth.map(_.copy(accountAuthentication = Some(proof.copy(authenticatedAt = now.plusSeconds(1)))))
                   case "wrong-user" => f.auth.map(_.copy(id = UserId(UUID.randomUUID())))
                   case _            => f.auth
                 }
          result <- f.activate(auth).asTry
          counts <- f.counts
        } yield {
          result.isFailure must beTrue
          counts mustEqual (0, 0)
        }
      }
    }
    "reject an unconfirmed recipient alias even when a different credential is confirmed" in withDatabase {
      for {
        f <- fixture(existing = true)
        _ <- identities.update(f.identity.get.copy(confirmed = UserIdentityConfirmed(false)))
        other <- identities.insert(
                   allGenerators[UserIdentity].normal.generate
                     .copy(userId = f.user.get.id, `type` = IdentityType.MailAddress, confirmed = UserIdentityConfirmed(true))
                 )
        auth = f.auth.map(_.copy(accountAuthentication = Some(AccountAuthentication(now, Credential(CredentialMethod.Token, other.id)))))
        result <- f.activate(auth).asTry
        counts <- f.counts
      } yield {
        result.failed.get mustEqual InvitationActivationService.Rejected(InvitationActivationService.IdentityMismatch); counts mustEqual (0, 0)
      }
    }
    "keep active membership roles and pending invitation unchanged" in withDatabase {
      for {
        f <- fixture(existing = true)
        _ <-
          sqlu"INSERT INTO organization_user(organization_id,user_id,roles) VALUES (${f.record.invitation.organizationId.toString}::uuid,${f.user.get.id.toString}::uuid,ARRAY['Employee']::text[])"
        result <- f.activate().asTry
        record <- invitations.find(f.record.invitation.id)
        roles  <- sql"SELECT roles::text FROM organization_user WHERE organization_id=${f.record.invitation.organizationId.toString}::uuid".as[String]
      } yield {
        result.failed.get mustEqual InvitationActivationService.Rejected(InvitationActivationService.AlreadyMember);
        record.get.invitation.acceptAt must beNone; roles mustEqual Vector("{Employee}")
      }
    }
    "never restore a removed membership on completed-context replay" in withDatabase {
      for {
        f      <- fixture(existing = true)
        _      <- f.activate()
        _      <- sqlu"DELETE FROM organization_user WHERE organization_id=${f.record.invitation.organizationId.toString}::uuid"
        result <- f.activate(authenticated = None)
        counts <- f.counts
      } yield { result mustEqual InvitationActivationService.InvalidInvitation; counts mustEqual (0, 1) }
    }
    "enforce pending revision, Origin and CSRF before writes" in withDatabase {
      for {
        f           <- fixture(existing = true)
        stale       <- service.activateAction(f.cookie, "1", f.auth, None, origin, Some(f.csrf))
        malformed   <- service.activateAction(f.cookie, "999999999999999999999999", f.auth, None, origin, Some(f.csrf))
        csrf        <- service.activateAction(f.cookie, "0", f.auth, None, origin, Some("wrong"))
        crossOrigin <- service.activateAction(f.cookie, "0", f.auth, None, Some("https://wrong.test"), Some(f.csrf))
        counts      <- f.counts
      } yield {
        stale mustEqual InvitationActivationService.RevisionConflict; malformed mustEqual stale; csrf mustEqual InvitationActivationService.Forbidden;
        crossOrigin mustEqual csrf; counts mustEqual (0, 0)
      }
    }
    "reject a context without recipient proof" in withDatabase {
      for {
        f       <- fixture(existing = false)
        context <- accesses.findByCookieHash(Digest(secrets.hash(f.cookie)))
        _       <- accesses.update(context.get.copy(proof = Proof(None, None)), 1)
        result  <- f.activate(hash = Some(PasswordHash("unused")))
        counts  <- f.counts
      } yield { result mustEqual InvitationActivationService.InvalidInvitation; counts mustEqual (0, 0) }
    }
    "reject a revoked completed context without repeating activation" in withDatabase {
      for {
        f       <- fixture(existing = true)
        _       <- f.activate()
        context <- accesses.findByCookieHash(Digest(secrets.hash(f.cookie)))
        _       <- accesses.revoke(context.get.id, now)
        replay  <- f.activate(authenticated = None)
        counts  <- f.counts
      } yield { replay mustEqual InvitationActivationService.InvalidInvitation; counts mustEqual (1, 1) }
    }

    "commit activation before session failure and require fresh account authentication to retry with current roles" in withCommitted(existing =
      true
    ) { f =>
      import cats.effect.IO
      import cats.effect.unsafe.implicits.global
      import pl.iterators.epoca.auth.domain.AuthDomain.{DeviceId, Jwt, PasswordPlain}
      import pl.iterators.epoca.auth.routers.AuthRouter.LoginHttpRequestContext
      import pl.iterators.epoca.auth.services.{LoginResolveService, PasswordService, PasswordValidateService}
      import pl.iterators.epoca.utils.db.PostgresIOTransactor
      val calls = new java.util.concurrent.atomic.AtomicInteger()
      val last  = new java.util.concurrent.atomic.AtomicReference[AuthContext]()
      val login = new LoginResolveService(null, null, null, null, null) {
        override def resolve(request: LoginResolveService.LoginResolveRequest): IO[LoginResolveService.LoginResolution] = IO.defer {
          last.set(request.toAuthContext(java.time.ZonedDateTime.ofInstant(now, ZoneOffset.UTC)))
          if (calls.incrementAndGet() == 1) IO.raiseError(new RuntimeException("synthetic session failure"))
          else IO.pure(LoginResolveService.LoginResolution(Jwt("access"), Jwt("refresh")))
        }
      }
      val noHash = new PasswordService {
        override def encrypt(password: PasswordPlain): PasswordHash =
          throw new IllegalStateException(s"must not hash existing account: ${password.length}")
      }
      val transactor = new PostgresIOTransactor(db)
      val limits     = new InvitationRateLimitService(transactor, accesses, Array.fill[Byte](32)(7), clock)
      val sut = new InvitationActivationService(
        transactor,
        access,
        actions,
        noHash,
        new PasswordValidateService(PasswordValidateService.Config(8, None, false, false, false, false, false)),
        login,
        limits,
        browser,
        clock
      )
      val request = LoginHttpRequestContext(DeviceId("test-device"), None)
      val first   = sut.activate(f.cookie, "0", Some(PasswordPlain("ignored-password")), f.auth, origin, Some(f.csrf), request).unsafeRunSync()
      first mustEqual InvitationActivationService.SessionIssueFailed()
      last.get().accountAuthentication mustEqual f.auth.get.accountAuthentication
      run(f.counts) mustEqual (1, 1)
      run(
        sqlu"UPDATE organization_user SET roles=ARRAY['Employee']::text[] WHERE organization_id=${f.record.invitation.organizationId.toString}::uuid"
      )
      val anonymousReplay = sut.activate(f.cookie, "0", None, None, origin, Some(f.csrf), request).unsafeRunSync()
      anonymousReplay mustEqual InvitationActivationService.SignInRequired
      calls.get() mustEqual 1
      val replay = sut.activate(f.cookie, "0", None, f.auth, origin, Some(f.csrf), request).unsafeRunSync()
      replay must beAnInstanceOf[InvitationActivationService.SessionJoined]
      calls.get() mustEqual 2
      last.get().fresh must beTrue
      last.get().accountAuthentication mustEqual f.auth.get.accountAuthentication
      last.get().roles.find(_.organizationId == f.record.invitation.organizationId).get.roles mustEqual Seq(OrganizationUserRole.Employee)
      run(f.counts) mustEqual (1, 1)
    }
    "consume valid-context failures separately and never allocate arbitrary-cookie rate buckets or hash existing recipients" in withCommitted(
      existing = true
    ) { f =>
      import cats.effect.unsafe.implicits.global
      import pl.iterators.epoca.auth.domain.AuthDomain.{DeviceId, PasswordPlain}
      import pl.iterators.epoca.auth.routers.AuthRouter.LoginHttpRequestContext
      import pl.iterators.epoca.auth.services.{PasswordService, PasswordValidateService}
      import pl.iterators.epoca.utils.db.PostgresIOTransactor
      val transactor = new PostgresIOTransactor(db)
      val limits     = new InvitationRateLimitService(transactor, accesses, Array.fill[Byte](32)(8), clock)
      val noHash = new PasswordService {
        override def encrypt(password: PasswordPlain): PasswordHash = throw new IllegalStateException(s"must not hash: ${password.length}")
      }
      val sut = new InvitationActivationService(
        transactor,
        access,
        actions,
        noHash,
        new PasswordValidateService(PasswordValidateService.Config(8, None, false, false, false, false, false)),
        null,
        limits,
        browser,
        clock
      )
      val request = LoginHttpRequestContext(DeviceId("test-device"), None)
      def attempt(cookie: String) =
        sut.activate(cookie, "0", Some(PasswordPlain("ignored-password")), None, origin, Some(f.csrf), request).unsafeRunSync()
      attempt("missing-context") mustEqual InvitationActivationService.InvalidInvitation
      val absentKey = limits.keyHash("Mutation", "missing-context").toArray.map(b => f"${b & 0xff}%02x").mkString
      run(sql"SELECT count(*) FROM invitation_rate_bucket WHERE encode(key_hash,'hex')=$absentKey".as[Int].head) mustEqual 0
      (1 to 5).foreach(_ => assert(attempt(f.cookie) == InvitationActivationService.SignInRequired))
      attempt(f.cookie) must beAnInstanceOf[InvitationActivationService.RateLimited]
      run(f.counts) mustEqual (0, 0)
    }

    "serialize opposite proof and recipient aliases before either can invert identity locks" in withCommitted(existing = true) { first =>
      import java.util.concurrent.{CountDownLatch, TimeUnit}
      import java.util.concurrent.atomic.AtomicInteger
      import scala.concurrent.{Await, Future}
      import scala.concurrent.duration._
      import pl.iterators.epoca.auth.services.AccountAuthenticationValidator
      import pl.iterators.epoca.auth.repositories.AccountAuthenticationRepositoryImpl
      def await[A](future: Future[A]): A = Await.result(future, 30.seconds)
      val parallelDb = Database.forURL(
        sys.env("TEST_DB_URL"),
        sys.env.getOrElse("TEST_DB_USER", "postgres"),
        sys.env.getOrElse("TEST_DB_PASSWORD", "postgres"),
        driver = "org.postgresql.Driver",
        executor = AsyncExecutor("ct208-activation-lock-order", 4, 4, 20, maxConnections = 4)
      )
      val locked        = new CountDownLatch(1)
      val release       = new CountDownLatch(1)
      val secondStarted = new CountDownLatch(1)
      val secondPid     = new AtomicInteger()
      val second = run(for {
        org <- withOrganization(allGenerators[Organization].normal.generate.copy(archivedAt = None))
        identity <-
          identities.insert(
            first.identity.get.copy(id = UserIdentityId(UUID.randomUUID()), value = UserIdentityValue(s"alias-${UUID.randomUUID()}@example.test"))
          )
        token = secrets.generate()
        record = first.record.copy(
                   invitation =
                     first.record.invitation.copy(id = InvitationId(UUID.randomUUID()), organizationId = org.id, identityValue = identity.value),
                   canonicalEmail = None,
                   tokenHash = Some(Digest(secrets.hash(token)))
                 )
        _       <- invitations.insert(record)
        current <- invitations.find(record.invitation.id)
        grant   <- access.exchangeAction(token, None).map(_.asInstanceOf[InvitationAccessService.Granted])
      } yield Fixture(current.get, grant.cookie, grant.dto.csrfToken, first.user, Some(identity)))
      val delayedValidator = new AccountAuthenticationValidator(new AccountAuthenticationRepositoryImpl, identities, passwords) {
        override def validate(
          auth: AuthContext,
          at: Instant,
          maxAge: java.time.Duration
        ): DBIO[Boolean] = super
          .validate(auth, at, maxAge)
          .flatMap(valid =>
            SimpleDBIO { _ =>
              locked.countDown(); require(release.await(20, TimeUnit.SECONDS), "release first activation"); valid
            }
          )
      }
      val delayedActions = new InvitationActivationActions(
        invitations,
        accesses,
        users,
        identities,
        memberships,
        creation,
        delayedValidator,
        clock,
        new pl.iterators.epoca.saml.repositories.SamlRequestRepositoryImpl,
        new pl.iterators.epoca.saml.repositories.SamlIdentityBindingRepositoryImpl
      )
      val delayed = new InvitationActivationService(null, access, delayedActions, null, null, null, null, browser, clock)
      val firstAuth =
        first.auth.map(_.copy(accountAuthentication = Some(AccountAuthentication(now, Credential(CredentialMethod.Token, second.identity.get.id)))))
      val secondAuth =
        first.auth.map(_.copy(accountAuthentication = Some(AccountAuthentication(now, Credential(CredentialMethod.Token, first.identity.get.id)))))
      try {
        val firstResult = parallelDb.run(delayed.activateAction(first.cookie, "0", firstAuth, None, origin, Some(first.csrf)).transactionally)
        require(locked.await(20, TimeUnit.SECONDS), "first activation must hold its proof identity")
        val secondResult = parallelDb.run(
          sql"SELECT pg_backend_pid()"
            .as[Int]
            .head
            .flatMap { pid =>
              secondPid.set(pid); secondStarted.countDown(); service.activateAction(second.cookie, "0", secondAuth, None, origin, Some(second.csrf))
            }
            .transactionally
        )
        require(secondStarted.await(20, TimeUnit.SECONDS), "second activation must start")
        val deadline = 10.seconds.fromNow
        val blocked = Iterator
          .continually(run(sql"SELECT cardinality(pg_blocking_pids(${secondPid.get()})) > 0".as[Boolean].head))
          .takeWhile(_ => deadline.hasTimeLeft())
          .exists(identity)
        require(blocked, "second activation must wait for the first account lock")
        release.countDown()
        await(firstResult) must beAnInstanceOf[InvitationActivationService.Activated]
        await(secondResult) must beAnInstanceOf[InvitationActivationService.Activated]
        run(first.counts) mustEqual (1, 1)
        run(second.counts) mustEqual (1, 1)
      } finally {
        release.countDown()
        try
          run(
            DBIO.seq(
              sqlu"DELETE FROM organization_user WHERE organization_id=${second.record.invitation.organizationId.toString}::uuid",
              sqlu"DELETE FROM invitation_access WHERE invitation_id=${second.record.invitation.id.toString}::uuid",
              sqlu"DELETE FROM invitation_audit WHERE invitation_id=${second.record.invitation.id.toString}::uuid",
              sqlu"DELETE FROM invitation WHERE id=${second.record.invitation.id.toString}::uuid",
              sqlu"DELETE FROM organization WHERE id=${second.record.invitation.organizationId.toString}::uuid"
            )
          )
        finally parallelDb.close()
      }
    }

    "require email proof for password creation even with a currently valid SAML-only context" in withDatabase {
      import pl.iterators.epoca.saml.domain.{SamlConfig, SamlDomain}
      for {
        f <- fixture(existing = false)
        _ <- new pl.iterators.epoca.saml.repositories.SamlConfigRepositoryImpl().upsert(
               allGenerators[SamlConfig].normal.generate.copy(
                 organizationId = f.record.invitation.organizationId,
                 isEnabled = SamlDomain.SamlIsEnabled(true),
                 idpEntityId = SamlDomain.SamlIdpEntityId("issuer")
               )
             )
        context <- accesses.findByCookieHash(Digest(secrets.hash(f.cookie)))
        proof = VerifiedSamlProof(f.record.invitation.organizationId, 1, "issuer", "subject", f.record.canonicalEmail.get, now)
        _      <- accesses.update(context.get.copy(proof = Proof(None, Some(proof))), 1)
        result <- f.activate(hash = Some(PasswordHash("unused"))).asTry
        counts <- f.counts
      } yield {
        result.failed.get mustEqual InvitationActivationService.Rejected(InvitationActivationService.SignInRequired); counts mustEqual (0, 0)
      }
    }
    "expire accepted replay exactly at its original context TTL" in withDatabase {
      val later       = Clock.fixed(now.plusSeconds(900), ZoneOffset.UTC)
      val lateAccess  = new InvitationAccessService(null, invitations, accesses, new EmailIdentityRepositoryImpl, secrets, browser, null, later)
      val lateService = new InvitationActivationService(null, lateAccess, actions, null, null, null, null, browser, later)
      for {
        f       <- fixture(existing = true)
        _       <- f.activate()
        context <- accesses.findByCookieHash(Digest(secrets.hash(f.cookie)))
        replay  <- lateService.activateAction(f.cookie, "0", None, None, origin, Some(f.csrf))
        counts  <- f.counts
      } yield {
        context.get.expiresAt mustEqual now.plusSeconds(900); replay mustEqual InvitationActivationService.InvalidInvitation; counts mustEqual (1, 1)
      }
    }
    "fail closed if the reserved email becomes ambiguous after context exchange" in withDatabase {
      for {
        f     <- fixture(existing = true)
        other <- withUser()
        otherIdentity <-
          identities.insert(
            f.identity.get.copy(
              id = UserIdentityId(UUID.randomUUID()),
              userId = other.id,
              `type` = IdentityType.Saml,
              value = UserIdentityValue(f.identity.get.value.toUpperCase(java.util.Locale.ROOT)),
              confirmed = UserIdentityConfirmed(false)
            )
          )
        resolution <- new EmailIdentityRepositoryImpl().resolve(f.record.invitation.identityValue)
        result     <- f.activate().asTry
        counts     <- f.counts
      } yield {
        otherIdentity.value must not(beEqualTo(f.identity.get.value))
        resolution mustEqual Resolution.Ambiguous
        result mustEqual scala.util.Failure(InvitationActivationService.Rejected(InvitationActivationService.IdentityConflict))
        counts mustEqual (0, 0)
      }
    }

    Seq("missing-cookie", "expired", "generation", "proof", "origin", "csrf", "completed").foreach { scenario =>
      s"keep ambiguous recipient details protected for $scenario context" in withDatabase {
        for {
          f     <- fixture(existing = true)
          _     <- if (scenario == "completed") f.activate().map(_ => ()) else DBIO.successful(())
          other <- withUser()
          _ <- identities.insert(
                 f.identity.get.copy(
                   id = UserIdentityId(UUID.randomUUID()),
                   userId = other.id,
                   `type` = IdentityType.Saml,
                   value = UserIdentityValue(f.identity.get.value.toUpperCase(java.util.Locale.ROOT)),
                   confirmed = UserIdentityConfirmed(false)
                 )
               )
          context <- accesses.findByCookieHash(Digest(secrets.hash(f.cookie)))
          _ <- scenario match {
                 case "expired" => accesses.revoke(context.get.id, now)
                 case "generation" =>
                   invitations.update(f.record.copy(generation = f.record.generation + 1), f.record.revision, f.record.generation).map(_ => ())
                 case "proof" => accesses.update(context.get.copy(proof = Proof(None, None)), f.record.generation).map(_ => ())
                 case _       => DBIO.successful(())
               }
          result <- service.activateAction(
                      if (scenario == "missing-cookie") "missing-context" else f.cookie,
                      f.record.revision.toString,
                      f.auth,
                      None,
                      if (scenario == "origin") Some("https://untrusted.example.test") else origin,
                      if (scenario == "csrf") Some("wrong-csrf") else Some(f.csrf)
                    )
          counts <- f.counts
        } yield {
          result mustEqual (if (Set("origin", "csrf")(scenario)) InvitationActivationService.Forbidden
                            else InvitationActivationService.InvalidInvitation)
          counts mustEqual (if (scenario == "completed") (1, 1) else (0, 0))
        }
      }
    }

    "return a safe identity conflict before hashing or account writes for a valid ambiguous context" in withCommitted(existing = true) { f =>
      import cats.effect.unsafe.implicits.global
      import pl.iterators.epoca.auth.domain.AuthDomain.{DeviceId, PasswordPlain}
      import pl.iterators.epoca.auth.routers.AuthRouter.LoginHttpRequestContext
      import pl.iterators.epoca.auth.services.PasswordService
      import pl.iterators.epoca.utils.db.PostgresIOTransactor
      val other = run(withUser())
      try {
        run(
          identities.insert(
            f.identity.get.copy(
              id = UserIdentityId(UUID.randomUUID()),
              userId = other.id,
              `type` = IdentityType.Saml,
              value = UserIdentityValue(f.identity.get.value.toUpperCase(java.util.Locale.ROOT)),
              confirmed = UserIdentityConfirmed(false)
            )
          )
        )
        val noHash = new PasswordService {
          override def encrypt(password: PasswordPlain): PasswordHash = throw new IllegalStateException("ambiguous recipient must not be hashed")
        }
        val transactor = new PostgresIOTransactor(db)
        val limits     = new InvitationRateLimitService(transactor, accesses, Array.fill[Byte](32)(9), clock)
        val sut        = new InvitationActivationService(transactor, access, actions, noHash, null, null, limits, browser, clock)
        val request    = LoginHttpRequestContext(DeviceId("test-conflict-device"), None)
        def attempt(
          cookie: String,
          requestOrigin: Option[String] = origin,
          csrf: Option[String] = Some(f.csrf)
        ): InvitationActivationService.Result =
          sut
            .activate(cookie, f.record.revision.toString, Some(PasswordPlain("ignored-password")), f.auth, requestOrigin, csrf, request)
            .unsafeRunSync()
        def bucketCount(cookie: String): Long = {
          val key = limits.keyHash("Mutation", cookie).toArray.map(b => f"${b & 0xff}%02x").mkString
          run(
            sql"SELECT COALESCE(sum(count),0)::bigint FROM invitation_rate_bucket WHERE dimension='Mutation' AND encode(key_hash,'hex')=$key"
              .as[Long]
              .head
          )
        }
        attempt("missing-context") mustEqual InvitationActivationService.InvalidInvitation
        attempt(f.cookie, Some("https://untrusted.example.test")) mustEqual InvitationActivationService.Forbidden
        attempt(f.cookie, csrf = Some("wrong-csrf")) mustEqual InvitationActivationService.Forbidden
        val context = run(accesses.findByCookieHash(Digest(secrets.hash(f.cookie)))).get
        run(accesses.update(context.copy(proof = Proof(None, None)), f.record.generation))
        attempt(f.cookie) mustEqual InvitationActivationService.InvalidInvitation
        bucketCount("missing-context") mustEqual 0L
        bucketCount(f.cookie) mustEqual 0L
        run(accesses.update(context, f.record.generation))
        (1 to 5).foreach(_ => assert(attempt(f.cookie) == InvitationActivationService.IdentityConflict))
        attempt(f.cookie) must beAnInstanceOf[InvitationActivationService.RateLimited]
        bucketCount(f.cookie) mustEqual 5L
        run(f.counts) mustEqual (0, 0)
        run(invitations.find(f.record.invitation.id)).get.invitation.acceptAt must beNone
        run(accesses.findByCookieHash(Digest(secrets.hash(f.cookie)))).get.completedAt must beNone
        run(passwords.find(f.identity.get.id)).get.password mustEqual PasswordHash("original-hash")
      } finally
        run(
          DBIO.seq(
            sqlu"DELETE FROM user_identity WHERE user_id=${other.id.toString}::uuid",
            sqlu"""DELETE FROM "user" WHERE id=${other.id.toString}::uuid"""
          )
        )
    }

    "roll back a newly inserted user identity and password when the membership write fails" in withCommitted(existing = false) { initial =>
      val recorded = new java.util.concurrent.atomic.AtomicReference[(UserId, UserIdentityId)]()
      val recordingCreation =
        new pl.iterators.epoca.user.services.UserCreationActions(users, identities, new EmailIdentityRepositoryImpl, passwords) {
          override def create(
            user: User,
            identity: UserIdentity,
            hash: Option[PasswordHash]
          ): DBIO[Option[(User, UserIdentity)]] = {
            recorded.set((user.id, identity.id)); super.create(user, identity, hash)
          }
        }
      val recordingActions = new InvitationActivationActions(
        invitations,
        accesses,
        users,
        identities,
        failingMemberships,
        recordingCreation,
        authentication,
        clock,
        new pl.iterators.epoca.saml.repositories.SamlRequestRepositoryImpl,
        new pl.iterators.epoca.saml.repositories.SamlIdentityBindingRepositoryImpl
      )
      val sut = new InvitationActivationService(null, access, recordingActions, null, null, null, null, browser, clock)
      val attempted =
        scala.util.Try(run(sut.activateAction(initial.cookie, "0", None, Some(PasswordHash("must-rollback")), origin, Some(initial.csrf))))
      attempted.failed.get mustEqual MembershipWriteFailure
      val (userId, identityId) = recorded.get()
      run(users.find(UserRepository.UserFilters(id = Some(userId), includeArchived = true))) must beNone
      run(identities.find(UserIdentityRepository.UserIdentityFilters(id = Some(identityId)))) must beNone
      run(passwords.find(identityId)) must beNone
      run(initial.counts) mustEqual (0, 0)
      run(invitations.find(initial.record.invitation.id)).get.acceptedUserId must beNone
      run(accesses.findByCookieHash(Digest(secrets.hash(initial.cookie)))).get.completedAt must beNone
    }

    "validate and hash a new password after committed throttling then issue proof at activation time" in withCommitted(existing = false) { f =>
      import cats.effect.IO
      import cats.effect.unsafe.implicits.global
      import pl.iterators.epoca.auth.domain.AuthDomain.{DeviceId, Jwt, PasswordPlain}
      import pl.iterators.epoca.auth.routers.AuthRouter.LoginHttpRequestContext
      import pl.iterators.epoca.auth.services.{LoginResolveService, PasswordService, PasswordValidateService}
      import pl.iterators.epoca.utils.db.PostgresIOTransactor
      val transactor = new PostgresIOTransactor(db)
      val limits     = new InvitationRateLimitService(transactor, accesses, Array.fill[Byte](32)(9), clock)
      val calls      = new java.util.concurrent.atomic.AtomicInteger()
      val issued     = new java.util.concurrent.atomic.AtomicReference[AuthContext]()
      val key        = limits.keyHash("Mutation", f.cookie).toArray.map(b => f"${b & 0xff}%02x").mkString
      val hashing = new PasswordService {
        override def encrypt(password: PasswordPlain): PasswordHash = {
          assert(run(sql"SELECT count FROM invitation_rate_bucket WHERE encode(key_hash,'hex')=$key".as[Int].head) == 2)
          calls.incrementAndGet(); super.encrypt(password)
        }
      }
      val login = new LoginResolveService(null, null, null, null, null) {
        override def resolve(request: LoginResolveService.LoginResolveRequest): IO[LoginResolveService.LoginResolution] = IO {
          assert(run(f.counts) == (1, 1))
          issued.set(request.toAuthContext(java.time.ZonedDateTime.ofInstant(now, ZoneOffset.UTC)))
          LoginResolveService.LoginResolution(Jwt("access"), Jwt("refresh"))
        }
      }
      val sut = new InvitationActivationService(
        transactor,
        access,
        actions,
        hashing,
        new PasswordValidateService(PasswordValidateService.Config(8, None, false, false, false, false, false)),
        login,
        limits,
        browser,
        clock
      )
      val request = LoginHttpRequestContext(DeviceId("new-device"), None)
      val invalid = sut.activate(f.cookie, "0", Some(PasswordPlain("x")), None, origin, Some(f.csrf), request).unsafeRunSync()
      invalid must beAnInstanceOf[InvitationActivationService.InvalidPassword]
      calls.get() mustEqual 0
      val plain = PasswordPlain("strong-invitation-password")
      sut
        .activate(f.cookie, "0", Some(plain), None, origin, Some(f.csrf), request)
        .unsafeRunSync() must beAnInstanceOf[InvitationActivationService.SessionJoined]
      calls.get() mustEqual 1
      val auth = issued.get()
      auth.fresh must beTrue
      auth.accountAuthentication.get.authenticatedAt mustEqual now
      val identityId = auth.accountAuthentication.get.evidence.asInstanceOf[Credential].identityId
      auth.accountAuthentication.get.evidence mustEqual Credential(CredentialMethod.Password, identityId)
      run(identities.find(UserIdentityRepository.UserIdentityFilters(id = Some(identityId)))).get.confirmed must beTrue
      hashing.check(plain, run(passwords.find(identityId)).get.password) must beTrue
      run(users.find(UserRepository.UserFilters(id = Some(auth.id)))).get.firstName mustEqual UserFirstName("New")
      sut
        .activate(f.cookie, "0", Some(plain), None, origin, Some(f.csrf), request)
        .unsafeRunSync() mustEqual InvitationActivationService.SignInRequired
      issued.get() mustEqual auth
      sut
        .activate(f.cookie, "0", None, Some(auth), origin, Some(f.csrf), request)
        .unsafeRunSync() must beAnInstanceOf[InvitationActivationService.SessionJoined]
      calls.get() mustEqual 1
      issued.get().accountAuthentication mustEqual auth.accountAuthentication
      issued.get().fresh must beTrue
    }

  }
}
