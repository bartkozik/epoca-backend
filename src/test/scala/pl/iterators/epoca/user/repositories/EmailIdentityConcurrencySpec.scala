package pl.iterators.epoca.user.repositories

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{mock, when}
import org.specs2.mutable.SpecificationLike
import pl.iterators.epoca.auth.domain.AccountAuthentication.{Credential, CredentialMethod}
import pl.iterators.epoca.auth.domain.AuthDomain.PasswordHash
import pl.iterators.epoca.auth.domain.{AccountAuthentication, AuthContext, UserPassword}
import pl.iterators.epoca.auth.repositories.{
  AccountAuthenticationRepositoryImpl,
  UserLoginTokenRepository,
  UserPasswordRepository,
  UserPasswordRepositoryImpl
}
import pl.iterators.epoca.auth.services.{
  AccountAuthenticationValidator,
  LoginTokenGenerateService,
  LoginTokenGetService,
  PasswordService,
  PasswordValidateService,
  TimeBasedOneTimePasswordService
}
import pl.iterators.epoca.mail.domain.MailDomain.MailAddress
import pl.iterators.epoca.mail.emails.UserIdentityConfirmationCodeMail
import pl.iterators.epoca.sms.services.SmsService
import pl.iterators.epoca.user.domain.UserDomain._
import pl.iterators.epoca.user.domain.{User, UserIdentity}
import pl.iterators.epoca.user.services.UserCreateService.{UserCreateRequest, UserCreateResult, UserCreateSamlRequest}
import pl.iterators.epoca.user.services.UserIdentityCreateService.{UserIdentityCreateRequest, UserIdentityCreateResult}
import pl.iterators.epoca.user.services.{UserCreateService, UserCreationActions, UserIdentityCreateService}
import pl.iterators.epoca.utils.Generators
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import pl.iterators.epoca.utils.db.PostgresIOTransactor

import java.time.Instant
import java.util.Properties
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

class EmailIdentityConcurrencySpec extends SpecificationLike with Generators {
  sequential
  private implicit val ec: ExecutionContext = ExecutionContext.global
  private val users                         = new UserRepositoryImpl
  private val identities                    = new UserIdentityRepositoryImpl
  private val emails                        = new EmailIdentityRepositoryImpl
  private val passwords                     = new UserPasswordRepositoryImpl
  private val creation                      = new UserCreationActions(users, identities, emails, passwords)
  private def user                          = allGenerators[User].normal.generate.copy(archivedAt = None, accountType = UserAccountType.Human)
  private def identity(
    u: User,
    kind: IdentityType,
    email: String
  ) = allGenerators[UserIdentity].normal.generate.copy(
    userId = u.id,
    `type` = kind,
    value = UserIdentityValue(email),
    main = UserIdentityMain(true),
    confirmed = UserIdentityConfirmed(true)
  )
  private def database = Database.forURL(
    sys.env.getOrElse("TEST_DB_URL", "epoca"),
    sys.env.getOrElse("TEST_DB_USER", "postgres"),
    sys.env.getOrElse("TEST_DB_PASSWORD", "postgres"),
    driver = "org.postgresql.Driver",
    executor = AsyncExecutor("email-reservation-race", 3, 3, 20, maxConnections = 3),
    prop = new Properties()
  )
  private def await[A](f: Future[A]): A = Await.result(f, 30.seconds)
  private def gate(latch: CountDownLatch): DBIO[Int] = SimpleDBIO { ctx =>
    val stmt = ctx.connection.createStatement()
    val pid =
      try {
        val rs = stmt.executeQuery("SELECT pg_backend_pid()")
        try { require(rs.next()); rs.getInt(1) }
        finally rs.close()
      } finally stmt.close()
    latch.countDown()
    require(latch.await(20, TimeUnit.SECONDS), "Both transactions must reach the barrier")
    pid
  }
  private def cleanup(us: User*): DBIO[Unit] = DBIO
    .sequence(us.map { u =>
      DBIO.seq(
        sqlu"DELETE FROM user_password WHERE user_identity_id IN (SELECT id FROM user_identity WHERE user_id = ${u.id.toString}::uuid)",
        sqlu"DELETE FROM user_identity WHERE user_id = ${u.id.toString}::uuid",
        sqlu"""DELETE FROM "user" WHERE id = ${u.id.toString}::uuid"""
      )
    })
    .map(_ => ())

  "email reservation transactions" should {
    "serialize account creation against the actual alias service on separate connections for both email kinds" in {
      Seq(IdentityType.MailAddress, IdentityType.Saml).foreach { kind =>
        val db       = database
        val owner    = user
        val newcomer = user
        val email    = s"race-${newcomer.id}@example.test"
        val start    = new CountDownLatch(2)
        val aliasPid = new AtomicInteger()
        val transactor = new PostgresIOTransactor(db) {
          override def executeTransactionally[A](action: => DBIO[A]): IO[A] =
            super.executeTransactionally(gate(start).flatMap { pid => aliasPid.set(pid); action })
        }
        val mail = mock(classOf[UserIdentityConfirmationCodeMail])
        when(mail.send(any[UserIdentityConfirmationCodeMail.Event])).thenReturn(IO.unit)
        val aliases = new UserIdentityCreateService(
          transactor,
          mail,
          mock(classOf[SmsService]),
          users,
          identities,
          emails,
          mock(classOf[TimeBasedOneTimePasswordService]),
          new AccountAuthenticationValidator(new AccountAuthenticationRepositoryImpl(), identities, passwords)
        )
        val credential = identity(owner, IdentityType.TimeBasedOneTimePassword, "BASE32").copy(main = UserIdentityMain(false))
        await(db.run((users.insert(owner) >> identities.insert(credential)).transactionally))
        try {
          val account = db.run((for {
            pid    <- gate(start)
            result <- creation.create(newcomer, identity(newcomer, kind, email.toUpperCase(java.util.Locale.ROOT)), None)
          } yield (pid, result)).transactionally)
          val alias = aliases
            .create(
              AuthContext(
                owner.id,
                owner.isSuperAdmin,
                Nil,
                Set.empty,
                Nil,
                Instant.now(),
                true,
                Some(AccountAuthentication(Instant.now(), Credential(CredentialMethod.TimeBasedOneTimePassword, credential.id)))
              ),
              UserIdentityCreateRequest(IdentityType.MailAddress, UserIdentityValue(email), None, None, UserIdentityMain(true))
            )
            .unsafeToFuture()
          val (pid, created) = await(account)
          val aliasResult    = await(alias)
          assert(pid != aliasPid.get(), "Race must use distinct PostgreSQL backends")
          assert(created.isDefined != aliasResult.isInstanceOf[UserIdentityCreateResult.Ok])
          assert(created.isDefined || aliasResult.isInstanceOf[UserIdentityCreateResult.Ok])
          if (created.isDefined) assert(aliasResult == UserIdentityCreateResult.IdentityValueAlreadyExist)
          val resolved = await(db.run(emails.resolve(email)))
          resolved match {
            case EmailIdentityRepository.Resolution.Single(found, matching) =>
              assert(Set(owner.id, newcomer.id).contains(found.id)); assert(matching.size == 1)
            case other => throw new AssertionError(s"Expected a single reserved user, got $other")
          }
        } finally { await(db.run(cleanup(owner, newcomer).transactionally)); db.close() }
      }
      ok
    }
    Seq("signup", "SAML", "alias").foreach { flow =>
      s"reject $flow creation when a legacy identity has control-character padding" in {
        Seq(IdentityType.MailAddress, IdentityType.Saml).foreach { kind =>
          val db         = database
          val owner      = user
          val aliasOwner = user
          val email      = s"padded-${owner.id}@example.test"
          val legacy     = identity(owner, kind, "\t\u001f" + email.toUpperCase(java.util.Locale.ROOT) + "\r\n")
          val transactor = new PostgresIOTransactor(db)
          val signup = new UserCreateService(
            UserCreateService.Config(None, 0),
            transactor,
            creation,
            mock(classOf[UserLoginTokenRepository]),
            mock(classOf[LoginTokenGetService]),
            new PasswordService,
            mock(classOf[PasswordValidateService]),
            mock(classOf[LoginTokenGenerateService])
          )
          val mail = mock(classOf[UserIdentityConfirmationCodeMail])
          when(mail.send(any[UserIdentityConfirmationCodeMail.Event])).thenReturn(IO.unit)
          val aliases = new UserIdentityCreateService(
            transactor,
            mail,
            mock(classOf[SmsService]),
            users,
            identities,
            emails,
            mock(classOf[TimeBasedOneTimePasswordService]),
            new AccountAuthenticationValidator(new AccountAuthenticationRepositoryImpl(), identities, passwords)
          )
          val credential = identity(aliasOwner, IdentityType.TimeBasedOneTimePassword, "BASE32").copy(main = UserIdentityMain(false))
          await(
            db.run((users.insert(owner) >> users.insert(aliasOwner) >> identities.insert(legacy) >> identities.insert(credential)).transactionally)
          )
          try
            if (flow == "alias") {
              val result = await(
                aliases
                  .create(
                    AuthContext(
                      aliasOwner.id,
                      aliasOwner.isSuperAdmin,
                      Nil,
                      Set.empty,
                      Nil,
                      Instant.now(),
                      true,
                      Some(AccountAuthentication(Instant.now(), Credential(CredentialMethod.TimeBasedOneTimePassword, credential.id)))
                    ),
                    UserIdentityCreateRequest(IdentityType.MailAddress, UserIdentityValue(email), None, None, UserIdentityMain(true))
                  )
                  .unsafeToFuture()
              )
              assert(result == UserIdentityCreateResult.IdentityValueAlreadyExist)
            } else {
              val result = await(
                (if (flow == "signup")
                   signup.create(
                     UserCreateRequest(
                       UserFirstName("Anna"),
                       UserLastName("Test"),
                       None,
                       None,
                       Some(MailAddress(email)),
                       None,
                       None,
                       None,
                       Some(true)
                     )
                   )
                 else signup.createWithSaml(UserCreateSamlRequest(UserFirstName("Anna"), UserLastName("Test"), MailAddress(email)))).unsafeToFuture()
              )
              try assert(result == UserCreateResult.IdentityAlreadyExist)
              finally
                result match {
                  case UserCreateResult.Ok(created) => await(db.run(cleanup(created.user).transactionally))
                  case _                            => ()
                }
            }
          finally
            try await(db.run(cleanup(owner, aliasOwner).transactionally))
            finally db.close()
        }
        ok
      }
    }
    "roll back user, identity and password if the initial password write fails" in {
      val db = database
      val u  = user
      val i  = identity(u, IdentityType.MailAddress, s"rollback-${u.id}@example.test")
      val failingPasswords = new UserPasswordRepository {
        def find(id: UserIdentityId): DBIO[Option[UserPassword]] = passwords.find(id)
        def upsert(p: UserPassword): DBIO[UserPassword] = passwords.upsert(p).flatMap(_ => DBIO.failed(new IllegalStateException("password failure")))
      }
      val actions = new UserCreationActions(users, identities, emails, failingPasswords)
      try {
        val result = await(db.run(actions.create(u, i, Some(PasswordHash("hash"))).transactionally.asTry))
        assert(result.failed.get.getMessage == "password failure")
        assert(await(db.run(users.find(u.id))).isEmpty)
        assert(await(db.run(identities.find(i.id))).isEmpty)
        assert(await(db.run(passwords.find(i.id))).isEmpty)
      } finally { await(db.run(cleanup(u).transactionally)); db.close() }
      ok
    }
    "roll back the new user when identity insertion fails" in {
      val db          = database
      val owner       = user
      val newcomer    = user
      val existing    = identity(owner, IdentityType.MailAddress, s"existing-${owner.id}@example.test")
      val duplicateId = identity(newcomer, IdentityType.Saml, s"new-${newcomer.id}@example.test").copy(id = existing.id)
      await(db.run((users.insert(owner) >> identities.insert(existing)).transactionally))
      try {
        val result = await(db.run(creation.create(newcomer, duplicateId, Some(PasswordHash("new"))).transactionally.asTry))
        assert(result.isFailure)
        assert(await(db.run(users.find(newcomer.id))).isEmpty)
        assert(await(db.run(identities.find(existing.id))).exists(_.userId == owner.id))
        assert(await(db.run(passwords.find(existing.id))).isEmpty)
      } finally { await(db.run(cleanup(owner, newcomer).transactionally)); db.close() }
      ok
    }
    "leave no identity or password when user insertion fails" in {
      val db = database
      val u  = user
      val i  = identity(u, IdentityType.MailAddress, s"duplicate-user-${u.id}@example.test")
      await(db.run(users.insert(u)))
      try {
        assert(await(db.run(creation.create(u, i, Some(PasswordHash("new"))).transactionally.asTry)).isFailure)
        assert(await(db.run(identities.find(i.id))).isEmpty)
        assert(await(db.run(passwords.find(i.id))).isEmpty)
      } finally { await(db.run(cleanup(u).transactionally)); db.close() }
      ok
    }
    "commit a new account with its initial password and never replace it on conflict" in {
      val db    = database
      val u     = user
      val other = user
      val i     = identity(u, IdentityType.MailAddress, s"password-${u.id}@example.test")
      try {
        assert(await(db.run(creation.create(u, i, Some(PasswordHash("initial"))).transactionally)).isDefined)
        assert(
          await(
            db.run(creation.create(other, identity(other, IdentityType.Saml, i.value), Some(PasswordHash("replacement"))).transactionally)
          ).isEmpty
        )
        assert(await(db.run(passwords.find(i.id))).contains(UserPassword(i.id, PasswordHash("initial"))))
        assert(await(db.run(users.find(other.id))).isEmpty)
      } finally { await(db.run(cleanup(u, other).transactionally)); db.close() }
      ok
    }
  }
}
