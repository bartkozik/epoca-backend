package pl.iterators.epoca.oauth

import cats.effect.unsafe.IORuntime
import com.typesafe.config.Config
import pl.iterators.epoca.oauth.jobs.OAuthRetentionJob
import pl.iterators.epoca.oauth.repositories._
import pl.iterators.epoca.oauth.services.OAuthConfig
import pl.iterators.epoca.oauth.time.{OAuthAuthoritativeTime, PostgresOAuthAuthoritativeTime}
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.job.{SchedulableJob, SchedulableJobConfig, SchedulableJobProvider}

import scala.concurrent.ExecutionContext

trait OAuthPersistenceModule extends SchedulableJobProvider {
  implicit val executor: ExecutionContext
  implicit val runtime: IORuntime
  val config: Config
  val transactor: PostgresIOTransactor

  lazy val oauthConfig: OAuthConfig = OAuthConfig.load(config) match {
    case Right(value) => value
    case Left(error)  => throw new IllegalArgumentException(s"Invalid OAuth configuration: $error")
  }
  lazy val oauthAuthoritativeTime: OAuthAuthoritativeTime = new PostgresOAuthAuthoritativeTime()
  lazy val oauthClientRepository: OAuthClientRepository   = new OAuthClientRepositoryImpl(oauthAuthoritativeTime)
  lazy val oauthAuditRepository: OAuthAuditRepository     = new OAuthAuditRepositoryImpl()
  lazy val oauthRateLimitRepository: OAuthRateLimitRepository =
    new OAuthRateLimitRepositoryImpl(oauthConfig.ipRateLimit, oauthConfig.knownClientFailedSecretRateLimit)

  private lazy val oauthRetentionJob = new OAuthRetentionJob(
    SchedulableJobConfig(config.getConfig("oauth.retentionSchedulableJob")),
    transactor,
    oauthAuthoritativeTime,
    oauthAuditRepository,
    oauthRateLimitRepository,
    oauthConfig
  )

  abstract override protected def schedulableJobs: List[SchedulableJob] = oauthRetentionJob :: super.schedulableJobs
}
