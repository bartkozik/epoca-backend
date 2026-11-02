package pl.iterators.epoca.oauth.repositories

import pl.iterators.epoca.oauth.domain.OAuthDomain.OAuthRateLimitBucketKey
import pl.iterators.epoca.oauth.repositories.OAuthRateLimitRepository.RateLimitDecision
import pl.iterators.epoca.oauth.services.OAuthConfig.OAuthRateLimit
import pl.iterators.epoca.utils.PgRepositorySpec
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import slick.util.AsyncExecutor

import java.net.InetAddress
import java.time.Instant
import java.util.{Properties, UUID}
import scala.concurrent.duration._
import scala.concurrent.{Await, Promise}

class OAuthRateLimitRepositoryImplSpec extends PgRepositorySpec {
  sequential

  private val ipLimit                              = OAuthRateLimit(capacity = 2, refillPeriod = 10.seconds)
  private val clientLimit                          = OAuthRateLimit(capacity = 1, refillPeriod = 30.seconds)
  private val repository: OAuthRateLimitRepository = new OAuthRateLimitRepositoryImpl(ipLimit, clientLimit)

  "PostgreSQL token buckets" should {
    "consume an IP bucket atomically and return the exact earliest retry second" in withDatabase {
      val source = InetAddress.getByName("203.0.113.9")
      val now    = Instant.parse("2026-08-13T08:00:00Z")

      for {
        first       <- repository.consumeIp(source, now)
        second      <- repository.consumeIp(source, now)
        limited     <- repository.consumeIp(source, now)
        almost      <- repository.consumeIp(source, now.plusSeconds(4L))
        replenished <- repository.consumeIp(source, now.plusSeconds(5L))
      } yield {
        first must beAnInstanceOf[RateLimitDecision.Allowed]
        second must beAnInstanceOf[RateLimitDecision.Allowed]
        limited shouldEqual RateLimitDecision.Limited(retryAfterSeconds = 5L)
        almost shouldEqual RateLimitDecision.Limited(retryAfterSeconds = 1L)
        replenished must beAnInstanceOf[RateLimitDecision.Allowed]
      }
    }

    "share a known-client failure bucket across two independent database sessions" in {
      val bucketKey   = OAuthRateLimitBucketKey(s"oauth-client-${UUID.randomUUID()}")
      val now         = Instant.parse("2026-08-13T09:00:00Z")
      val firstDb     = independentDatabase("oauth-rate-first")
      val secondDb    = independentDatabase("oauth-rate-second")
      val firstReady  = Promise[Unit]()
      val secondReady = Promise[Unit]()
      val release     = Promise[Unit]()

      def consume(ready: Promise[Unit]): DBIO[RateLimitDecision] =
        for {
          _        <- DBIO.successful { ready.trySuccess(()); () }
          _        <- DBIO.from(release.future)
          decision <- repository.consumeClientFailure(bucketKey, now)
        } yield decision

      val first  = firstDb.run(consume(firstReady).transactionally)
      val second = secondDb.run(consume(secondReady).transactionally)
      try {
        Await.result(firstReady.future.zip(secondReady.future), 10.seconds)
        release.success(())
        val decisions = Await.result(first.zip(second), 10.seconds)
        Seq(decisions._1, decisions._2).count(_.isInstanceOf[RateLimitDecision.Allowed]) shouldEqual 1
        Seq(decisions._1, decisions._2).count(_ == RateLimitDecision.Limited(retryAfterSeconds = 30L)) shouldEqual 1
      } finally {
        release.trySuccess(())
        firstDb.close()
        secondDb.close()
      }
    }

    "keep the persisted refill clock monotonic when callers acquire the bucket out of timestamp order" in withDatabase {
      val source = InetAddress.getByName("203.0.113.10")
      val first  = Instant.parse("2026-08-13T09:30:00Z")
      for {
        _       <- repository.consumeIp(source, first)
        _       <- repository.consumeIp(source, first.plusSeconds(5L))
        stale   <- repository.consumeIp(source, first)
        current <- repository.consumeIp(source, first.plusSeconds(5L))
      } yield {
        stale must beAnInstanceOf[RateLimitDecision.Allowed]
        current shouldEqual RateLimitDecision.Limited(retryAfterSeconds = 5L)
      }
    }

    "delete expired buckets in bounded batches" in withDatabase {
      val uniquePart   = UUID.randomUUID().toString.replace("-", "").take(8)
      val firstSource  = InetAddress.getByName(s"2001:db8:${uniquePart.take(4)}:${uniquePart.drop(4)}::10")
      val secondSource = InetAddress.getByName(s"2001:db8:${uniquePart.take(4)}:${uniquePart.drop(4)}::11")
      val activeSource = InetAddress.getByName(s"2001:db8:${uniquePart.take(4)}:${uniquePart.drop(4)}::12")
      val ownKeys      = Set(firstSource, secondSource, activeSource).map(_.getHostAddress)
      val old          = Instant.parse("1900-01-01T00:00:00Z")
      val cutoff       = Instant.parse("2000-01-01T00:00:00Z")
      val active       = Instant.parse("2100-01-01T00:00:00Z")
      for {
        _             <- repository.consumeIp(firstSource, old)
        _             <- repository.consumeIp(secondSource, old)
        _             <- repository.consumeIp(activeSource, active)
        before        <- countBuckets(ownKeys)
        firstDeleted  <- repository.deleteExpired(cutoff, batchSize = 1)
        afterFirst    <- countBuckets(ownKeys)
        secondDeleted <- repository.deleteExpired(cutoff, batchSize = 1)
        afterSecond   <- countBuckets(ownKeys)
      } yield {
        before shouldEqual 3
        firstDeleted shouldEqual 1
        afterFirst shouldEqual 2
        secondDeleted shouldEqual 1
        afterSecond shouldEqual 1
      }
    }
  }

  private def countBuckets(keys: Set[String]): DBIO[Int] =
    sql"select bucket_key from oauth_rate_limit_bucket".as[String].map(_.count(keys))

  private def independentDatabase(name: String): Database =
    Database.forURL(
      url = sys.env.getOrElse("TEST_DB_URL", "epoca"),
      user = sys.env.getOrElse("TEST_DB_USER", "postgres"),
      password = sys.env.getOrElse("TEST_DB_PASSWORD", "postgres"),
      driver = "org.postgresql.Driver",
      executor = AsyncExecutor(s"$name-${UUID.randomUUID()}", minThreads = 1, maxThreads = 1, queueSize = 20, maxConnections = 1),
      prop = new Properties()
    )
}
