package pl.iterators.epoca.workunit.services

import com.typesafe.config.ConfigFactory
import pl.iterators.epoca.utils.ServiceSpec
import pl.iterators.epoca.utils.job.SchedulableJob.ScheduleJobResult
import pl.iterators.epoca.utils.job.SchedulableJobConfig
import pl.iterators.epoca.workunit.jobs.WorkUnitIdempotencyRedactJob
import pl.iterators.epoca.workunit.repositories.WorkUnitIdempotencyRepository
import pl.iterators.epoca.workunit.services.WorkUnitIdempotencyService.WorkUnitIdempotencyConfig
import slick.dbio.DBIO

import scala.concurrent.duration._

class WorkUnitIdempotencyRedactServiceSpec extends ServiceSpec {

  trait TestCase extends FixedTimeBaseScope {
    val repository: WorkUnitIdempotencyRepository = vmock[WorkUnitIdempotencyRepository]
    val sut                                       = new WorkUnitIdempotencyRedactService(transactor, repository)
    val jobConfig: SchedulableJobConfig = SchedulableJobConfig(
      enabled = true,
      workers = 1,
      scheduleAtFixedRate = false,
      initialDelay = 30.seconds,
      tickTime = 15.minutes,
      tickTimeAfterNoWork = None,
      timeout = None
    )
    val job = new WorkUnitIdempotencyRedactJob(jobConfig, sut)
  }

  "configuration" should {
    "load the retention from application config and accept the 24 hour minimum" in {
      val config = ConfigFactory.parseString("workUnit.idempotency.responseRetention = 24h")

      WorkUnitIdempotencyConfig(config).responseRetention shouldEqual 24.hours
    }

    "reject retention shorter than 24 hours" in {
      val config = ConfigFactory.parseString("workUnit.idempotency.responseRetention = 23h")

      WorkUnitIdempotencyConfig(config) must throwA[IllegalArgumentException]
    }
  }

  "redact" should {
    "use the current database clock instant and return the number of redacted records" in new TestCase {
      when(repository.redactExpired(now)).thenReturn(3.asDBIO)

      sut.redact().asserting(_ shouldEqual 3)

      verify(repository).redactExpired(now)
    }
  }

  "scheduled job" should {
    "report Done when at least one record was redacted" in new TestCase {
      when(repository.redactExpired(now)).thenReturn(2.asDBIO)

      job.apply.asserting(_ shouldEqual ScheduleJobResult.Done)

      verify(repository).redactExpired(now)
    }

    "report NoWork when no records were redacted" in new TestCase {
      when(repository.redactExpired(now)).thenReturn(0.asDBIO)

      job.apply.asserting(_ shouldEqual ScheduleJobResult.NoWork)

      verify(repository).redactExpired(now)
    }

    "propagate repository failures through the service and job" in new TestCase {
      val expected = new RuntimeException("redaction failed")
      when(repository.redactExpired(now)).thenReturn(DBIO.failed(expected))

      job.apply.attempt.asserting(_ shouldEqual Left(expected))

      verify(repository).redactExpired(now)
    }
  }
}
