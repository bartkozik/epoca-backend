package pl.iterators.epoca.invitation.jobs

import cats.effect.unsafe.IORuntime
import com.typesafe.config.ConfigFactory
import pl.iterators.epoca.invitation.InvitationRetentionModule
import pl.iterators.epoca.invitation.repositories.InvitationRetentionRepository
import pl.iterators.epoca.main.ApplicationLoader
import pl.iterators.epoca.oauth.time.OAuthAuthoritativeTime
import pl.iterators.epoca.utils.PgRepositorySpec
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.job.{ApplicationSchedulableJobProvider, SchedulableJob, SchedulableJobConfig}

import java.time.Instant
import scala.concurrent.duration._

class InvitationRetentionJobSpec extends PgRepositorySpec {
  private implicit val runtime: IORuntime = IORuntime.global
  private val transactor                  = new PostgresIOTransactor(db)
  private val jobConfig                   = SchedulableJobConfig(true, 1, false, 1.second, 1.hour, None, None)
  private val now                         = Instant.parse("2035-01-01T12:00:00Z")
  private val time = new OAuthAuthoritativeTime {
    override def now: DBIO[Instant] = DBIO.successful(InvitationRetentionJobSpec.this.now)
  }

  "invitation retention job" should {
    "use one authoritative cutoff and configured bounds for both cleanup operations" in {
      val repository = new RecordingRepository(2)
      val job        = new InvitationRetentionJob(jobConfig, transactor, time, repository, 17)
      val result     = job.apply.unsafeRunSync()
      (result mustEqual SchedulableJob.ScheduleJobResult.Done) and
        (repository.calls mustEqual Vector(("rate", now, 17), ("access", now, 17)))
    }

    "report no work when neither cleanup removes a row" in {
      val repository = new RecordingRepository(0)
      new InvitationRetentionJob(jobConfig, transactor, time, repository, 17).apply.unsafeRunSync() mustEqual SchedulableJob.ScheduleJobResult.NoWork
    }

    "register an enabled cleanup job in the actual application runtime" in {
      class Module extends ApplicationSchedulableJobProvider with InvitationRetentionModule {
        override implicit val executor: scala.concurrent.ExecutionContext = ec
        override implicit val runtime: IORuntime                          = InvitationRetentionJobSpec.this.runtime
        override val transactor: PostgresIOTransactor                     = InvitationRetentionJobSpec.this.transactor
        override val config: com.typesafe.config.Config = ConfigFactory.parseString("""
          invitation.retentionCleanupBatchSize = 100
          invitation.retentionSchedulableJob { enabled = true, initialDelay = 45s, tickTime = 1h }
        """)
        def registered: List[SchedulableJob] = schedulableJobs
      }
      val module = new Module
      (module.registered.exists(job => job.isInstanceOf[InvitationRetentionJob] && job.config.enabled) must beTrue) and
        (classOf[InvitationRetentionModule].isAssignableFrom(classOf[ApplicationLoader]) must beTrue)
    }
  }

  private class RecordingRepository(deleted: Int) extends InvitationRetentionRepository {
    var calls: Vector[(String, Instant, Int)] = Vector.empty[(String, Instant, Int)]
    override def deleteExpiredRateBuckets(now: Instant, batchSize: Int): DBIO[Int] = {
      calls :+= (("rate", now, batchSize))
      DBIO.successful(deleted)
    }
    override def deleteExpiredAccess(now: Instant, batchSize: Int): DBIO[Int] = {
      calls :+= (("access", now, batchSize))
      DBIO.successful(deleted)
    }
  }
}
