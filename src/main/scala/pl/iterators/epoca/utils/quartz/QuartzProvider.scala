package pl.iterators.epoca.utils.quartz

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import com.typesafe.config.{Config => TsConfig}
import org.quartz.impl.StdSchedulerFactory
import org.quartz.spi.{JobFactory, TriggerFiredBundle}
import org.quartz.{ObjectAlreadyExistsException, _}
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.quartz.QuartzProvider.{QuartzRegistrableJob, QuartzScheduleData}

import scala.collection.mutable

class QuartzProvider(config: QuartzProvider.Config)(implicit val ioRuntime: IORuntime) extends LoggingSupport {
  config.properties.foreach { case (key, value) =>
    System.setProperty(key, value)
  }

  private val scheduler: Scheduler = StdSchedulerFactory.getDefaultScheduler

  private val jobMap: mutable.Map[String, Job] = mutable.Map.empty

  private val jobFactory = new JobFactory {
    override def newJob(bundle: TriggerFiredBundle, scheduler: Scheduler): Job = {
      val jobDetail = bundle.getJobDetail
      val jobKey    = jobDetail.getKey
      jobMap.getOrElse(jobKey.getGroup, sys.error(s"Unable to find job class for key ${jobKey.getGroup}"))
    }
  }

  scheduler.setJobFactory(jobFactory)

  def getRegisteredJob(jobClassName: String): Option[Job] =
    jobMap.get(jobClassName)

  def register(job: QuartzRegistrableJob): IO[Unit] =
    Logger[IO].info(s"Registering job with className ${job.jobClassName}").flatMap { _ =>
      if (jobMap.contains(job.jobClassName))
        Logger[IO].warning("Registering another job with same key className is risky!. Job not registered")
      else if (scheduler.isStarted)
        Logger[IO].warning("Registering job after quartz has been started is very risky!. Job not regiestered")
      else
        IO.blocking {
          jobMap += (job.jobClassName -> job.job)
        }.void
    }

  def schedule(data: QuartzScheduleData): IO[Unit] =
    IO.blocking(scheduler.scheduleJob(data.details, data.trigger)).attempt.flatMap {
      case Right(_) =>
        Logger[IO].info(s"Scheduling job data ${data.details.getKey} success")
      case Left(e: ObjectAlreadyExistsException) =>
        Logger[IO].info(s"Scheduling job already in database ${data.details.getKey}. ${e.getMessage}")
      case Left(t) =>
        Logger[IO].error(s"Scheduling cron job failed with ${t.getClass} ${t.getLocalizedMessage}")
    }

  def start(): IO[Unit] =
    // that method should be executed after register all jobs
    for {
      _ <- Logger[IO].info("Starting quartz")
      _ <- IO.blocking(scheduler.start())
    } yield ()

  def stop(): IO[Unit] =
    for {
      _ <- Logger[IO].info("Stopping quartz")
      _ <- IO.blocking(scheduler.shutdown(true))
    } yield ()

}

object QuartzProvider {
  final case class QuartzRegistrableJob(jobClassName: String, job: Job)

  final case class QuartzScheduleData(details: JobDetail, trigger: Trigger)

  final case class Config(
    dbUrl: String,
    dbUser: String,
    dbPassword: String,
    connections: Int) {
    lazy val properties: List[(String, String)] =
      List(
        "org.quartz.dataSource.default.driver"          -> "org.postgresql.Driver",
        "org.quartz.dataSource.default.URL"             -> dbUrl,
        "org.quartz.dataSource.default.user"            -> dbUser,
        "org.quartz.dataSource.default.password"        -> dbPassword,
        "org.quartz.dataSource.default.maxConnections"  -> connections.toString,
        "org.quartz.dataSource.default.validationQuery" -> "select 1",
        "org.quartz.jobStore.dataSource"                -> "default"
      )
  }

  object Config {
    def apply(config: TsConfig): Config =
      Config(config.getString("db.url"), config.getString("db.user"), config.getString("db.password"), 3)
  }

}
