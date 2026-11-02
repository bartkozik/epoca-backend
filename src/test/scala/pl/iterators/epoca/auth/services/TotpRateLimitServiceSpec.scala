package pl.iterators.epoca.auth.services

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.mockito.{ArgumentMatchersSugar, MockitoSugar}
import org.specs2.mutable.Specification
import pl.iterators.epoca.auth.domain.AuthDomain.{TimeBasedOneTimePasswordSecret, TimeBasedOneTimePasswordValue}
import pl.iterators.epoca.auth.services.LoginByTimeBasedOneTimePasswordService._
import pl.iterators.epoca.invitation.repositories.InvitationAccessRepositoryImpl
import pl.iterators.epoca.user.domain.UserDomain._
import pl.iterators.epoca.user.domain.UserIdentity
import pl.iterators.epoca.user.repositories.UserIdentityRepository
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import pl.iterators.epoca.utils.db.{BaseTable, PostgresIOTransactor}

import java.time.{Clock, Instant, ZoneOffset}
import java.util.UUID
import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

class TotpRateLimitServiceSpec extends Specification with MockitoSugar with ArgumentMatchersSugar {
  sequential
  private implicit val ec: ExecutionContext = ExecutionContext.global
  private val clock                         = Clock.fixed(Instant.parse("2026-10-08T10:00:20Z"), ZoneOffset.UTC)
  private def await[A](value: Future[A]): A = Await.result(value, 30.seconds)

  private class Fixture extends AutoCloseable {
    val db: BaseTable.driver.backend.JdbcDatabaseDef = Database.forURL(
      sys.env.getOrElse("TEST_DB_URL", "epoca"),
      sys.env.getOrElse("TEST_DB_USER", "postgres"),
      sys.env.getOrElse("TEST_DB_PASSWORD", "postgres"),
      driver = "org.postgresql.Driver"
    )
    val tx                                              = new PostgresIOTransactor(db)
    val repository                                      = new InvitationAccessRepositoryImpl
    val limits                                          = new TotpRateLimitService(tx, repository, clock)
    val identityId: UserIdentityId                      = UserIdentityId(UUID.randomUUID())
    val request: LoginByTimeBasedOneTimePasswordRequest = LoginByTimeBasedOneTimePasswordRequest(identityId, TimeBasedOneTimePasswordValue("000000"))
    val hash: String                                    = limits.keyHash(identityId).toArray.map(b => f"${b & 0xff}%02x").mkString
    override def close(): Unit = {
      try await(db.run(sqlu"DELETE FROM invitation_rate_bucket WHERE key_hash=decode($hash,'hex')"))
      finally db.close()
      ()
    }
  }

  "TOTP authentication" should {
    "throttle the sixth unauthenticated attempt for the same identity before looking it up" in {
      val f          = new Fixture
      val identities = mock[UserIdentityRepository]
      when(identities.find(any)).thenReturn(DBIO.successful(None))
      val service = new LoginByTimeBasedOneTimePasswordService(f.tx, null, null, identities, null, null, f.limits)
      try {
        (1 to 5).foreach(_ =>
          service.login(None, f.request, null).unsafeRunSync() mustEqual LoginByTimeBasedOneTimePasswordResult.UserOrPasswordNotFound
        )
        service.login(None, f.request, null).unsafeRunSync() mustEqual LoginByTimeBasedOneTimePasswordResult.InvalidCredentials
        verify(identities, times(5)).find(any)
        success
      } finally f.close()
    }

    "retain rejected code attempts and refuse a correct sixth code" in {
      val f          = new Fixture
      val identities = mock[UserIdentityRepository]
      val passwords  = mock[TimeBasedOneTimePasswordService]
      val identity = UserIdentity(
        f.identityId,
        UserId(UUID.randomUUID()),
        IdentityType.TimeBasedOneTimePassword,
        UserIdentityValue("SECRET"),
        UserIdentityMain(false),
        UserIdentityConfirmed(true),
        None,
        None,
        UserIdentityCreatedAt(clock.instant())
      )
      when(identities.find(any)).thenReturn(DBIO.successful(Some(identity)))
      when(passwords.generateIO(TimeBasedOneTimePasswordSecret(identity.value))).thenReturn(IO.pure(TimeBasedOneTimePasswordValue("123456")))
      val service = new LoginByTimeBasedOneTimePasswordService(f.tx, null, null, identities, passwords, null, f.limits)
      try {
        (1 to 5).foreach(_ => service.login(None, f.request, null).unsafeRunSync() mustEqual LoginByTimeBasedOneTimePasswordResult.InvalidCredentials)
        val correct = f.request.copy(password = TimeBasedOneTimePasswordValue("123456"))
        service.login(None, correct, null).unsafeRunSync() mustEqual LoginByTimeBasedOneTimePasswordResult.InvalidCredentials
        verify(passwords, times(5)).generateIO(TimeBasedOneTimePasswordSecret(identity.value))
        await(
          f.db.run(sql"SELECT count FROM invitation_rate_bucket WHERE dimension='TotpMinute' AND key_hash=decode(${f.hash},'hex')".as[Long].head)
        ) mustEqual 5L
      } finally f.close()
    }

    "fail closed before credential lookup when the rate database is unavailable" in {
      val broken = new PostgresIOTransactor(null) {
        override def executeTransactionally[A](action: => DBIO[A]): IO[A] = IO.raiseError(new IllegalStateException("database unavailable"))
      }
      val limits  = new TotpRateLimitService(broken, new InvitationAccessRepositoryImpl, clock)
      val service = new LoginByTimeBasedOneTimePasswordService(broken, null, null, null, null, null, limits)
      val request = LoginByTimeBasedOneTimePasswordRequest(UserIdentityId(UUID.randomUUID()), TimeBasedOneTimePasswordValue("123456"))
      service.login(None, request, null).unsafeRunSync() mustEqual LoginByTimeBasedOneTimePasswordResult.InvalidCredentials
    }

    "admit only one concurrent attempt at the boundary across separate service instances" in {
      val f     = new Fixture
      val latch = new CountDownLatch(2)
      val pids  = java.util.concurrent.ConcurrentHashMap.newKeySet[Int]()
      val gated = new PostgresIOTransactor(f.db) {
        override def executeTransactionally[A](action: => DBIO[A]): IO[A] = super.executeTransactionally(SimpleDBIO { ctx =>
          val statement = ctx.connection.createStatement()
          try {
            val rows = statement.executeQuery("SELECT pg_backend_pid()")
            try { require(rows.next()); pids.add(rows.getInt(1)) }
            finally rows.close()
          } finally statement.close()
          latch.countDown()
          require(latch.await(20, TimeUnit.SECONDS))
          ()
        }.flatMap(_ => action))
      }
      try {
        (1 to 4).foreach(_ => f.limits.consume(f.identityId).unsafeRunSync() must beTrue)
        val a     = new TotpRateLimitService(gated, f.repository, clock)
        val b     = new TotpRateLimitService(gated, f.repository, clock)
        val left  = a.consume(f.identityId).unsafeToFuture()
        val right = b.consume(f.identityId).unsafeToFuture()
        Vector(await(left), await(right)).count(identity) mustEqual 1
        pids.size() mustEqual 2
      } finally f.close()
    }

    "reset minute buckets while retaining the hourly bound across service restarts" in {
      val f = new Fixture
      try {
        (0 to 3).foreach { minute =>
          val at      = Clock.offset(clock, java.time.Duration.ofMinutes(minute.toLong))
          val service = new TotpRateLimitService(f.tx, f.repository, at)
          (1 to 5).foreach(_ => service.consume(f.identityId).unsafeRunSync() must beTrue)
          service.consume(f.identityId).unsafeRunSync() must beFalse
        }
        val nextMinute = new TotpRateLimitService(f.tx, f.repository, Clock.offset(clock, java.time.Duration.ofMinutes(4)))
        nextMinute.consume(f.identityId).unsafeRunSync() must beFalse
        val nextHour = new TotpRateLimitService(f.tx, f.repository, Clock.offset(clock, java.time.Duration.ofHours(1)))
        nextHour.consume(f.identityId).unsafeRunSync() must beTrue
      } finally f.close()
    }

    "keep different identity buckets independent" in {
      val a = new Fixture
      val b = new Fixture
      try {
        (1 to 5).foreach(_ => a.limits.consume(a.identityId).unsafeRunSync() must beTrue)
        a.limits.consume(a.identityId).unsafeRunSync() must beFalse
        b.limits.consume(b.identityId).unsafeRunSync() must beTrue
      } finally { a.close(); b.close() }
    }
  }
}
