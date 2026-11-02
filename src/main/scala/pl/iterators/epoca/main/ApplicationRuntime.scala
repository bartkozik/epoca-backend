package pl.iterators.epoca.main

import cats.effect.{IO, IOApp, Resource}
import com.typesafe.config.{Config, ConfigFactory}
import kamon.Kamon
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import pl.iterators.epoca.auth.services.JwtService
import pl.iterators.epoca.utils.ce._
import pl.iterators.epoca.utils.db.{DatabaseSchemaConfiguration, PostgresDriver, PostgresIOTransactor}
import pl.iterators.epoca.utils.job.{CronJobScheduler, TriggeredJobRegister, TriggeredJobScheduler}
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.quartz.{QuartzCronJobScheduler, QuartzProvider, QuartzTriggeredJobRegister, QuartzTriggeredJobScheduler}
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.WebSocketStreamBackend
import sttp.client4.httpclient.fs2.HttpClientFs2Backend

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{Duration, DurationInt}

trait ApplicationRuntime extends IOApp.Simple with LoggingSupport {
  protected def loadConfig(): Config = ConfigFactory.load()

  protected def createLoader(
    config: Config,
    transactor: PostgresIOTransactor,
    httpClient: WebSocketStreamBackend[IO, Fs2Streams[IO]],
    scheduler: TriggeredJobScheduler,
    invitationSupervisor: cats.effect.std.Supervisor[IO]
  ): ApplicationLoader = new ApplicationLoader(config, runtime.compute, runtime, transactor, httpClient, scheduler, invitationSupervisor)

  protected def additionalResources(@scala.annotation.unused loader: ApplicationLoader, @scala.annotation.unused actorSystem: ActorSystem)
    : Resource[IO, Unit] =
    Resource.unit[IO]

  override def run: IO[Unit] =
    (for {
      config <- Resource.eval(IO.delay(DatabaseSchemaConfiguration(loadConfig())))
      // Fail before opening any resources when the JWT signing secret is missing or a placeholder.
      _ <- Resource.eval(IO.delay(JwtService.Config(config)))
      _ = if (config.getBoolean("kamon.enabled")) Kamon.init(config)

      actorSystem <- startActorSystemUntyped(
                       "default",
                       config = None,
                       useIOExecutionContext = true,
                       timeoutAwaitCatsEffect = Duration(10, "seconds"),
                       timeoutAwaitAkkaTermination = Duration(10, "seconds")
                     )

      httpClient <- HttpClientFs2Backend.resource[IO]()

      dbCtx <- Resource.make(IO.delay {
                 val db = PostgresDriver.api.Database.forConfig("db", config)
                 (db, new PostgresIOTransactor(db)(runtime))
               })(dbctx => IO.blocking(dbctx._1.close()))

      quartzCtx <- Resource.make(IO.delay {
                     val quartzProviderConfig                         = QuartzProvider.Config(config)
                     val quartzProvider                               = new QuartzProvider(quartzProviderConfig)(runtime)
                     val cronJobScheduler: CronJobScheduler           = new QuartzCronJobScheduler(quartzProvider)
                     val triggeredJobScheduler: TriggeredJobScheduler = new QuartzTriggeredJobScheduler(quartzProvider)
                     val triggeredJobRegister: TriggeredJobRegister   = new QuartzTriggeredJobRegister(quartzProvider)
                     (quartzProvider, cronJobScheduler, triggeredJobScheduler, triggeredJobRegister)
                   })(_._1.stop())

      invitationSupervisor <- cats.effect.std.Supervisor[IO](await = false)
      loader = createLoader(config, dbCtx._2, httpClient, quartzCtx._3, invitationSupervisor)

      // created eagerly so that a misconfigured storage fails the startup; closed on shutdown
      _ <- Resource.make(IO.delay(loader.fileStorage))(storage =>
             storage match {
               case closeable: AutoCloseable => IO.blocking(closeable.close())
               case _                        => IO.unit
             }
           )

      _ <- additionalResources(loader, actorSystem)
      _ <- Resource.eval(initializeClientModule(loader))
      _ <- Resource.eval(initializeExportModule(loader))
      _ <- Resource.eval(loader.startupJobsSequence) // blocking
      _ <- Resource.eval(loader.setupCronJobs(quartzCtx._2)) // blocking
      _ <- Resource.eval(loader.setupTriggeredJobs(quartzCtx._4)) // blocking
      _ <- Resource.eval(quartzCtx._1.start())

      _ <- loader.schedulableJobSequence.background
    } yield (actorSystem, runtime.compute, loader)).use { case (actorSystem, executor, loader) =>
      implicit val ec: ExecutionContext = executor
      implicit val system: ActorSystem  = actorSystem

      val interface = loader.config.getString("http.interface")
      val port      = loader.config.getInt("http.port")

      IO.fromFuture {
        IO(
          Http()
            .newServerAt(interface, port)
            .bindFlow(loader.routes)
            .map(_.addToCoordinatedShutdown(hardTerminationDeadline = 10.seconds))
        ) <* Logger[IO].info(s"Server started at http://$interface:$port")
      } *> IO.never
    }

  private[main] def initializeClientModule(loader: ApplicationLoader): IO[Unit] =
    IO.delay(loader.clientRepository: Unit)

  private[main] def initializeExportModule(loader: ApplicationLoader): IO[Unit] =
    IO.delay(loader.pivotExportArtifactStore: Unit)
}
