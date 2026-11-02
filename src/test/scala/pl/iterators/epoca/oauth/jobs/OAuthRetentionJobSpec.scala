package pl.iterators.epoca.oauth.jobs

import cats.effect.unsafe.IORuntime
import com.typesafe.config.ConfigFactory
import io.circe.Json
import pl.iterators.epoca.oauth.domain.OAuthDomain._
import pl.iterators.epoca.oauth.repositories.OAuthAuditRepository.NewOAuthAuditEvent
import pl.iterators.epoca.oauth.repositories.OAuthRateLimitRepository.RateLimitDecision
import pl.iterators.epoca.oauth.repositories._
import pl.iterators.epoca.oauth.repositories.tables.OAuthAuditEventRow
import pl.iterators.epoca.oauth.services.OAuthConfig
import pl.iterators.epoca.oauth.time.{OAuthAuthoritativeTime, PostgresOAuthAuthoritativeTime}
import pl.iterators.epoca.utils.PgRepositorySpec
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.job.{SchedulableJob, SchedulableJobConfig}

import java.net.InetAddress
import java.time.Instant
import java.util.UUID
import scala.concurrent.duration._

class OAuthRetentionJobSpec extends PgRepositorySpec {
  sequential

  private implicit val runtime: IORuntime = IORuntime.global
  private val oauthConfig = OAuthConfig.load(ConfigFactory.load()) match {
    case Right(value) => value
    case Left(error)  => throw new IllegalStateException(s"Invalid OAuth test configuration: $error")
  }
  private val authoritativeTime: OAuthAuthoritativeTime = new PostgresOAuthAuthoritativeTime()
  private val auditRepository: OAuthAuditRepository     = new OAuthAuditRepositoryImpl()
  private val rateRepository: OAuthRateLimitRepository =
    new OAuthRateLimitRepositoryImpl(oauthConfig.ipRateLimit, oauthConfig.knownClientFailedSecretRateLimit)
  private val transactor = new PostgresIOTransactor(db)
  private val jobConfig  = SchedulableJobConfig(enabled = true, workers = 1, scheduleAtFixedRate = false, 1.second, 1.hour, None, None)
  private val job =
    new OAuthRetentionJob(jobConfig, transactor, authoritativeTime, auditRepository, rateRepository, oauthConfig)

  "OAuthRetentionJob" should {
    "delete expired authentication audit and rate-limit rows in one bounded run" in {
      val auditId = OAuthAuditEventId(UUID.randomUUID())
      val runId   = UUID.randomUUID()
      val source  = InetAddress.getByAddress(Array(198.toByte, 51.toByte, runId.getMostSignificantBits.toByte, runId.getLeastSignificantBits.toByte))
      val old     = Instant.parse("2020-01-01T00:00:00Z")

      transactor
        .executeTransactionally(
          auditRepository.insert(
            NewOAuthAuditEvent(
              auditId,
              OAuthAuditOccurredAt(old),
              OAuthAuditCategory.Authentication,
              None,
              None,
              OAuthAuditActorType.Anonymous,
              None,
              OAuthAuditEventType.InvalidClient,
              OAuthAuditOutcome.Failure,
              None,
              None,
              Some(source),
              Json.obj("failureCategory" -> Json.fromString("unknown-client"))
            )
          ) >> rateRepository.consumeIp(source, old)
        )
        .unsafeRunSync()

      job.apply.unsafeRunSync() shouldEqual SchedulableJob.ScheduleJobResult.Done

      val remaining = transactor
        .execute(for {
          audit  <- auditRepository.find(auditId)
          bucket <- sql"select count(*) from oauth_rate_limit_bucket where bucket_key = ${source.getHostAddress}".as[Int].head
        } yield audit -> bucket)
        .unsafeRunSync()

      remaining shouldEqual (None -> 0)
    }

    "derive both cleanup cutoffs from the injected authoritative time" in {
      val fixedNow = Instant.parse("2035-06-07T08:09:10Z")
      val fixed = new OAuthAuthoritativeTime {
        override def now: DBIO[Instant] = DBIO.successful(fixedNow)
      }
      val recordingAudit = new RecordingAuditRepository(auditRepository)
      val recordingRate  = new RecordingRateLimitRepository(rateRepository)
      val fixedJob       = new OAuthRetentionJob(jobConfig, transactor, fixed, recordingAudit, recordingRate, oauthConfig)

      fixedJob.apply.unsafeRunSync() shouldEqual SchedulableJob.ScheduleJobResult.Done

      (recordingAudit.cutoffs shouldEqual Vector(fixedNow.minusNanos(oauthConfig.authenticationAuditRetention.toNanos))) and
        (recordingRate.cutoffs shouldEqual Vector(fixedNow))
    }
  }

  private final class RecordingAuditRepository(delegate: OAuthAuditRepository) extends OAuthAuditRepository {
    var cutoffs: Vector[Instant] = Vector.empty

    override def insert(event: NewOAuthAuditEvent): DBIO[OAuthAuditEventRow] = delegate.insert(event)
    override def insertUnknownClientFailure(
      id: OAuthAuditEventId,
      occurredAt: OAuthAuditOccurredAt,
      correlationId: Option[OAuthAuditCorrelationId],
      sourceIp: Option[InetAddress],
      failureCategory: OAuthAuditFailureCategory
    ): DBIO[OAuthAuditEventRow] = delegate.insertUnknownClientFailure(id, occurredAt, correlationId, sourceIp, failureCategory)
    override def find(id: OAuthAuditEventId): DBIO[Option[OAuthAuditEventRow]]                             = delegate.find(id)
    override def list(filters: OAuthAuditRepository.OAuthAuditEventFilters): DBIO[Seq[OAuthAuditEventRow]] = delegate.list(filters)
    override def count(filters: OAuthAuditRepository.OAuthAuditEventFilters): DBIO[Int]                    = delegate.count(filters)
    override def deleteExpiredAuthentication(cutoff: Instant, batchSize: Int): DBIO[Int] = {
      cutoffs = cutoffs :+ cutoff
      DBIO.successful(1)
    }
  }

  private final class RecordingRateLimitRepository(delegate: OAuthRateLimitRepository) extends OAuthRateLimitRepository {
    var cutoffs: Vector[Instant] = Vector.empty

    override def consumeIp(source: InetAddress, now: Instant): DBIO[RateLimitDecision] = delegate.consumeIp(source, now)
    override def consumeClientFailure(bucketKey: OAuthRateLimitBucketKey, now: Instant): DBIO[RateLimitDecision] =
      delegate.consumeClientFailure(bucketKey, now)
    override def deleteExpired(now: Instant, batchSize: Int): DBIO[Int] = {
      cutoffs = cutoffs :+ now
      DBIO.successful(0)
    }
  }
}
