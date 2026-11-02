package pl.iterators.epoca.invitation.services

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.specs2.mutable.Specification
import pl.iterators.epoca.invitation.repositories.{InvitationAccessRepositoryImpl, InvitationOnboardingRepositoryImpl}
import pl.iterators.epoca.user.repositories.EmailIdentityRepositoryImpl
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import pl.iterators.epoca.utils.db.PostgresIOTransactor

import java.time.{Clock, Instant, ZoneOffset}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.{Properties, UUID}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

class InvitationRateLimitServiceSpec extends Specification {
  sequential
  private implicit val ec: ExecutionContext = ExecutionContext.global
  private val clock                         = Clock.fixed(Instant.parse("2026-10-02T10:00:20Z"), ZoneOffset.UTC)
  private val key                           = Array.fill[Byte](32)(7)
  private def database = Database.forURL(
    sys.env.getOrElse("TEST_DB_URL", "epoca"),
    sys.env.getOrElse("TEST_DB_USER", "postgres"),
    sys.env.getOrElse("TEST_DB_PASSWORD", "postgres"),
    driver = "org.postgresql.Driver",
    executor = AsyncExecutor("invitation-rate-race", 3, 3, 20, maxConnections = 3),
    prop = new Properties()
  )
  private def await[A](f: Future[A]): A = Await.result(f, 30.seconds)
  "invitation rate limit" should {
    "key IPs with a server secret and separate dimensions" in {
      val a = new InvitationRateLimitService(null, new InvitationAccessRepositoryImpl, key, clock)
      val b = new InvitationRateLimitService(null, new InvitationAccessRepositoryImpl, Array.fill[Byte](32)(8), clock)
      a.keyHash("Exchange", "192.0.2.1") must not equalTo (b.keyHash("Exchange", "192.0.2.1"))
      a.keyHash("Exchange", "192.0.2.1") must not equalTo (a.keyHash("Mutation", "192.0.2.1"))
    }
    "fail closed with bounded retry when the database fails" in {
      val broken = new PostgresIOTransactor(null) {
        override def executeTransactionally[A](action: => DBIO[A]): IO[A] = IO.raiseError(new IllegalStateException("database unavailable"))
      }
      val service = new InvitationRateLimitService(broken, new InvitationAccessRepositoryImpl, key, clock)
      service.exchange("192.0.2.1").unsafeRunSync() mustEqual InvitationRateLimitService.Denied(40)
    }
    Seq("Exchange", "Mutation").foreach { dimension =>
      s"atomically admit only one of two concurrent $dimension requests at the boundary" in {
        val db         = database
        val repository = new InvitationAccessRepositoryImpl
        val plain      = new PostgresIOTransactor(db)
        val latch      = new CountDownLatch(2)
        val pids       = java.util.concurrent.ConcurrentHashMap.newKeySet[Int]()
        val gated = new PostgresIOTransactor(db) {
          override def executeTransactionally[A](action: => DBIO[A]): IO[A] = super.executeTransactionally(SimpleDBIO { ctx =>
            val statement = ctx.connection.createStatement()
            try {
              val rows = statement.executeQuery("SELECT pg_backend_pid()");
              try { require(rows.next()); pids.add(rows.getInt(1)) }
              finally rows.close()
            } finally statement.close()
            latch.countDown(); require(latch.await(20, TimeUnit.SECONDS)); ()
          }.flatMap(_ => action))
        }
        val initial                                      = new InvitationRateLimitService(plain, repository, key, clock)
        val racing                                       = new InvitationRateLimitService(gated, repository, key, clock)
        val bucket                                       = UUID.randomUUID().toString
        val limit                                        = if (dimension == "Exchange") 30 else 5
        def consume(service: InvitationRateLimitService) = if (dimension == "Exchange") service.exchange(bucket) else service.mutation(bucket)
        try {
          (1 until limit).foreach(_ => consume(initial).unsafeRunSync() mustEqual InvitationRateLimitService.Allowed)
          val left    = consume(racing).unsafeToFuture()
          val right   = consume(racing).unsafeToFuture()
          val results = Vector(await(left), await(right))
          results.count(_ == InvitationRateLimitService.Allowed) mustEqual 1
          results.count(_ == InvitationRateLimitService.Denied(40)) mustEqual 1
          pids.size() mustEqual 2
          val hash = initial.keyHash(dimension, bucket).toArray.map(b => f"${b & 0xff}%02x").mkString
          await(
            db.run(sql"SELECT count FROM invitation_rate_bucket WHERE dimension=$dimension AND key_hash=decode($hash,'hex')".as[Long].head)
          ) mustEqual limit.toLong
        } finally {
          val hash = initial.keyHash(dimension, bucket).toArray.map(b => f"${b & 0xff}%02x").mkString
          await(db.run(sqlu"DELETE FROM invitation_rate_bucket WHERE dimension=$dimension AND key_hash=decode($hash,'hex')"))
          db.close()
        }
      }
    }
    "commit invalid exchanges so the thirty-first invalid token is throttled" in {
      val db         = database
      val repository = new InvitationAccessRepositoryImpl
      val tx         = new PostgresIOTransactor(db)
      val limits     = new InvitationRateLimitService(tx, repository, key, clock)
      val secrets    = new InvitationSecretService
      val browser    = new InvitationBrowserSecurity(Set("https://app.example.test"), "/api", false, false, secrets)
      val service = new InvitationAccessService(
        tx,
        new InvitationOnboardingRepositoryImpl,
        repository,
        new EmailIdentityRepositoryImpl,
        secrets,
        browser,
        limits,
        clock
      )
      val ip = UUID.randomUUID().toString
      try {
        (1 to 30).foreach(_ =>
          service
            .exchange("invalid", None, Some("https://app.example.test"), Some("application/json"), ip)
            .unsafeRunSync() mustEqual InvitationAccessService.InvalidInvitation
        )
        service
          .exchange("invalid", None, Some("https://app.example.test"), Some("application/json"), ip)
          .unsafeRunSync() mustEqual InvitationAccessService.RateLimited(40)
      } finally {
        val hash = limits.keyHash("Exchange", ip).toArray.map(b => f"${b & 0xff}%02x").mkString
        await(db.run(sqlu"DELETE FROM invitation_rate_bucket WHERE dimension='Exchange' AND key_hash=decode($hash,'hex')"))
        db.close()
      }
    }
  }
}
