package pl.iterators.epoca.auth.services

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.specs2.mutable.SpecificationLike
import pl.iterators.epoca.auth.domain.AccountAuthentication.{Credential, CredentialMethod}
import pl.iterators.epoca.auth.domain.AuthDomain.PasswordPlain
import pl.iterators.epoca.auth.domain.{AccountAuthentication, AuthContext}
import pl.iterators.epoca.auth.repositories.{AccountAuthenticationRepositoryImpl, UserPasswordRepositoryImpl}
import pl.iterators.epoca.auth.services.PasswordSetService.{PasswordSetRequest, PasswordSetResult}
import pl.iterators.epoca.user.domain.UserDomain._
import pl.iterators.epoca.user.domain.{User, UserIdentity}
import pl.iterators.epoca.user.repositories.UserIdentityRepository.UserIdentityFilters
import pl.iterators.epoca.user.repositories.{UserIdentityRepositoryImpl, UserRepositoryImpl}
import pl.iterators.epoca.user.services.UserIdentitySetMainService
import pl.iterators.epoca.user.services.UserIdentitySetMainService.UserIdentitySetMainResult
import pl.iterators.epoca.utils.Generators
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import pl.iterators.epoca.utils.db.PostgresIOTransactor

import java.time.Instant
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Success

class AccountAuthenticationConcurrencySpec extends SpecificationLike with Generators {
  sequential
  private implicit val testExecutionContext: ExecutionContext = ExecutionContext.global
  private def await[A](future: Future[A]): A                  = Await.result(future, 30.seconds)

  "credential mutation lock order" should {
    "serialize SetMain before it can invert password proof and target identity locks" in {
      val db = Database.forURL(
        sys.env("TEST_DB_URL"),
        sys.env.getOrElse("TEST_DB_USER", "postgres"),
        sys.env.getOrElse("TEST_DB_PASSWORD", "postgres"),
        driver = "org.postgresql.Driver",
        executor = AsyncExecutor("ct208-proof-lock-order", 4, 4, 20, maxConnections = 4)
      )
      val users      = new UserRepositoryImpl()
      val identities = new UserIdentityRepositoryImpl()
      val passwords  = new UserPasswordRepositoryImpl()
      val user       = allGenerators[User].normal.generate.copy(archivedAt = None, accountType = UserAccountType.Human)
      val proofIdentity = allGenerators[UserIdentity].normal.generate.copy(
        userId = user.id,
        `type` = IdentityType.MailAddress,
        value = UserIdentityValue(s"proof-${user.id}@example.test"),
        confirmed = UserIdentityConfirmed(true),
        main = UserIdentityMain(true)
      )
      val targetIdentity = proofIdentity.copy(
        id = allGenerators[UserIdentity].normal.generate.id,
        value = UserIdentityValue(s"target-${user.id}@example.test"),
        main = UserIdentityMain(false)
      )
      val proofLocked        = new CountDownLatch(1)
      val releasePassword    = new CountDownLatch(1)
      val mainStarted        = new CountDownLatch(1)
      val mainPid            = new AtomicInteger()
      val targetLockedByMain = new AtomicBoolean(false)
      val proofIdentities = new UserIdentityRepositoryImpl() {
        override def find(filters: UserIdentityFilters): DBIO[Option[UserIdentity]] = super.find(filters).flatMap { found =>
          if (filters.id.contains(proofIdentity.id) && filters.forUpdate) SimpleDBIO { _ =>
            proofLocked.countDown()
            require(releasePassword.await(20, TimeUnit.SECONDS), "Password transaction must be released")
            found
          }
          else DBIO.successful(found)
        }
      }
      val mainIdentities = new UserIdentityRepositoryImpl() {
        override def find(filters: UserIdentityFilters): DBIO[Option[UserIdentity]] = super.find(filters).map { found =>
          if (filters.id.contains(targetIdentity.id) && filters.forUpdate) targetLockedByMain.set(true)
          found
        }
      }
      val mainTransactor = new PostgresIOTransactor(db) {
        override def executeTransactionally[A](action: => DBIO[A]): IO[A] =
          super.executeTransactionally(sql"SELECT pg_backend_pid()".as[Int].head.flatMap { pid =>
            mainPid.set(pid); mainStarted.countDown(); action
          })
      }
      val validator = new AccountAuthenticationValidator(new AccountAuthenticationRepositoryImpl(), proofIdentities, passwords)
      val passwordService = new PasswordSetService(
        new PostgresIOTransactor(db),
        new PasswordValidateService(PasswordValidateService.Config(8, None, false, false, false, false, false)),
        passwords,
        identities,
        new PasswordService(),
        users,
        validator
      )
      val mainService = new UserIdentitySetMainService(mainTransactor, mainIdentities, users)
      val now         = Instant.now()
      val auth = AuthContext(
        user.id,
        user.isSuperAdmin,
        Nil,
        Set.empty,
        Nil,
        now,
        true,
        Some(AccountAuthentication(now, Credential(CredentialMethod.Token, proofIdentity.id)))
      )
      await(db.run((users.insert(user) >> identities.insert(proofIdentity) >> identities.insert(targetIdentity)).transactionally))
      try {
        val passwordResult =
          passwordService.passwordSet(auth, PasswordSetRequest(targetIdentity.id, PasswordPlain("strong-password"))).unsafeToFuture()
        require(proofLocked.await(20, TimeUnit.SECONDS), "Password must hold its proof identity")
        val mainResult = mainService.setMain(auth, targetIdentity.id).unsafeToFuture()
        require(mainStarted.await(20, TimeUnit.SECONDS), "SetMain must run on its own connection")
        val deadline = 10.seconds.fromNow
        val blocked = Iterator
          .continually {
            val result = await(db.run(sql"SELECT cardinality(pg_blocking_pids(${mainPid.get()})) > 0".as[Boolean].head))
            result
          }
          .takeWhile(_ => deadline.hasTimeLeft())
          .exists(identity)
        require(blocked, "SetMain must reach a database lock wait")
        val tookTargetBeforeUser = targetLockedByMain.get()
        releasePassword.countDown()
        val passwordOutcome = await(passwordResult.transform(result => Success(result)))
        val mainOutcome     = await(mainResult.transform(result => Success(result)))
        tookTargetBeforeUser shouldEqual false
        passwordOutcome shouldEqual Success(PasswordSetResult.Ok)
        mainOutcome.toOption.exists(_.isInstanceOf[UserIdentitySetMainResult.Ok]) shouldEqual true
      } finally {
        releasePassword.countDown()
        try
          await(
            db.run(
              DBIO
                .seq(
                  sqlu"DELETE FROM user_password WHERE user_identity_id IN (SELECT id FROM user_identity WHERE user_id = ${user.id.toString}::uuid)",
                  sqlu"DELETE FROM user_identity WHERE user_id = ${user.id.toString}::uuid",
                  sqlu"""DELETE FROM "user" WHERE id = ${user.id.toString}::uuid"""
                )
                .transactionally
            )
          )
        finally db.close()
      }
    }
  }
}
