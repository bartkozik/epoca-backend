package pl.iterators.epoca.utils

import cats.effect.kernel.Resource
import cats.effect.std.Dispatcher
import cats.effect.{Deferred, IO}
import cats.syntax.all._
import com.typesafe.config.Config
import org.apache.pekko.Done
import org.apache.pekko.actor.{ActorSystem => UntypedActorSystem, CoordinatedShutdown}
import org.slf4j.LoggerFactory

import scala.concurrent.TimeoutException
import scala.concurrent.duration._

package object ce {
  // https://alexn.org/blog/2023/04/17/integrating-akka-with-cats-effect-3/
  /** Starts an (untyped) Akka actor system in the context of a Cats-Effect `Resource`, and integrating with its cancellation abilities.
    *
    * HINT: for apps (in `main`), it's best if `akka.coordinated-shutdown.exit-jvm` is set to `on`, because Akka can decide to shutdown on its own.
    * And having this setting interacts well with Cats-Effect.
    *
    * @param systemName
    *   is the identifying name of the system.
    * @param config
    *   is an optional, parsed HOCON configuration; if None, then Akka will read its own, possibly from `application.conf`; this parameter is provided
    *   in order to control the source of the application's configuration.
    * @param useIOExecutionContext
    *   if true, then Cats-Effect's default thread-pool will get used by Akka, as well. This is needed in order to avoid having too many thread-pools.
    * @param timeoutAwaitCatsEffect
    *   is the maximum amount of time Akka's coordinated-shutdown is allowed to wait for Cats-Effect to finish. This is needed, as Cats-Effect could
    *   have a faulty stack of disposables, or because Akka could decide to shutdown on its own.
    * @param timeoutAwaitAkkaTermination
    *   is the maximum amount of time to wait for the actor system to terminate, after `terminate()` was called. We need the timeout, because
    *   `terminate()` proved to return a `Future` that never completes in certain scenarios (could be a bug, or a race condition).
    */
  def startActorSystemUntyped(
    systemName: String,
    config: Option[Config],
    useIOExecutionContext: Boolean,
    timeoutAwaitCatsEffect: Duration,
    timeoutAwaitAkkaTermination: Duration
  ): Resource[IO, UntypedActorSystem] =
    // Needed to turn IO into Future
    // https://typelevel.org/cats-effect/docs/std/dispatcher
    Dispatcher.parallel[IO](await = true).flatMap { dispatcher =>
      Resource[IO, UntypedActorSystem](for {
        // Fishing IO's `ExecutionContext`
        ec <- Option
                .when(useIOExecutionContext)(IO.executionContext)
                .sequence
        // For synchronizing Cats-Effect with Akka
        awaitCancel <- Deferred[IO, Unit]
        // For awaiting termination via coordinated-shutdown,
        // needed as `terminate()` is unreliable
        awaitTermination <- Deferred[IO, Unit]
        logger = LoggerFactory.getLogger(getClass)
        system <- IO {
                    logger.info("Creating actor system...")
                    val system = UntypedActorSystem(systemName.trim.replaceAll("\\W+", "-"), config = config, defaultExecutionContext = ec)
                    // Registering task in Akka's CoordinatedShutdown
                    // that will wait for Cats-Effect to catch up,
                    // blocking Akka from terminating, see:
                    // https://doc.akka.io/docs/akka/current/coordinated-shutdown.html
                    CoordinatedShutdown(system).addTask(CoordinatedShutdown.PhaseBeforeServiceUnbind, "sync-with-cats-effect") { () =>
                      dispatcher.unsafeToFuture(
                        // WARN: this may not happen, if Akka decided
                        // to terminate, and `coordinated-shutdown.exit-jvm`
                        // isn't `on`, hence the timeout:
                        awaitCancel.get
                          .timeout(timeoutAwaitCatsEffect)
                          .recoverWith { case _: TimeoutException =>
                            IO(
                              logger.error(
                                "Timed out waiting for Cats-Effect to catch up! " +
                                  "This might indicate either a non-terminating " +
                                  "cancellation logic, or a misconfiguration of Akka."
                              )
                            )
                          }
                          .as(Done)
                      )
                    }
                    CoordinatedShutdown(system).addTask(CoordinatedShutdown.PhaseActorSystemTerminate, "signal-actor-system-terminated") { () =>
                      dispatcher.unsafeToFuture(awaitTermination.complete(()).as(Done))
                    }
                    system
                  }
      } yield {
        val cancel =
          for {
            // Signals that Cats-Effect has caught up with Akka
            _ <- awaitCancel.complete(())
            _ <- IO(logger.warn("Shutting down actor system!"))
            // Shuts down Akka, and waits for its termination
            // Here, system.terminate() returns a `Future[Terminated]`,
            // but we are ignoring it, as it could be non-terminating
            _ <- IO(system.terminate())
            // Waiting for Akka to terminate via coordinated-shutdown
            _ <- awaitTermination.get
            // WARN: `whenTerminated` is unreliable, hence the timeout
            _ <- IO.fromFuture(IO(system.whenTerminated))
                   .void
                   .timeoutAndForget(timeoutAwaitAkkaTermination)
                   .handleErrorWith(_ => IO(logger.warn("Timed-out waiting for Akka to terminate!")))
          } yield ()
        (system, cancel)
      })
    }
}
