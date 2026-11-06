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
  def startActorSystemUntyped(
    systemName: String,
    config: Option[Config],
    useIOExecutionContext: Boolean,
    timeoutAwaitCatsEffect: Duration,
    timeoutAwaitAkkaTermination: Duration
  ): Resource[IO, UntypedActorSystem] =
    Dispatcher.parallel[IO](await = true).flatMap { dispatcher =>
      Resource[IO, UntypedActorSystem](for {
        ec <- Option
                .when(useIOExecutionContext)(IO.executionContext)
                .sequence
        awaitCancel <- Deferred[IO, Unit]
        // For awaiting termination via coordinated-shutdown,
        // needed as `terminate()` is unreliable
        awaitTermination <- Deferred[IO, Unit]
        logger = LoggerFactory.getLogger(getClass)
        system <- IO {
                    logger.info("Creating actor system...")
                    val system = UntypedActorSystem(systemName.trim.replaceAll("\\W+", "-"), config = config, defaultExecutionContext = ec)
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
            _ <- awaitCancel.complete(())
            _ <- IO(logger.warn("Shutting down actor system!"))
            _ <- IO(system.terminate())
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
