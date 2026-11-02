package pl.iterators.epoca.oauth.fixture

import cats.effect.IO
import org.apache.pekko.http.scaladsl.model.{StatusCode, StatusCodes}

import java.util.UUID
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

object OAuthE2EFixtureControlProtocol {
  object Limits {
    val MaximumRequestBytes: Int  = 16 * 1024
    val MaximumResponseBytes: Int = 64 * 1024
    val MaximumAcceptedCommands   = 32
    val MaximumFailedAuthAttempts = 5
    val FailedAuthWindowNanos     = 60000000000L

    val CommandTotalNanos: Long  = 10000000000L
    val CommandActiveNanos: Long = 8000000000L
    val CleanupTotalNanos: Long  = 120000000000L
    val CleanupActiveNanos: Long = 115000000000L
  }

  sealed trait ControlCommand {
    def runId: UUID
  }

  object ControlCommand {
    final case class Setup(runId: UUID)             extends ControlCommand
    final case class Advance300Seconds(runId: UUID) extends ControlCommand
    final case class RevokeOAuth029(runId: UUID)    extends ControlCommand
    final case class Cleanup(runId: UUID)           extends ControlCommand
    final case class Status(runId: UUID)            extends ControlCommand
  }

  final case class ControlDeadline private (
    admittedAtNanos: Long,
    activeNanos: Long,
    totalNanos: Long) {
    def remainingActiveNanos(nowNanos: Long): Long = remaining(activeNanos, nowNanos)
    def remainingTotalNanos(nowNanos: Long): Long  = remaining(totalNanos, nowNanos)

    private def remaining(durationNanos: Long, nowNanos: Long): Long = {
      val elapsed = nowNanos - admittedAtNanos
      if (elapsed <= 0L) durationNanos else math.max(0L, durationNanos - elapsed)
    }
  }

  object ControlDeadline {
    def command(admittedAtNanos: Long): ControlDeadline =
      ControlDeadline(admittedAtNanos, Limits.CommandActiveNanos, Limits.CommandTotalNanos)

    def cleanup(admittedAtNanos: Long): ControlDeadline =
      ControlDeadline(admittedAtNanos, Limits.CleanupActiveNanos, Limits.CleanupTotalNanos)

    private[fixture] def testing(
      admittedAtNanos: Long,
      activeNanos: Long,
      totalNanos: Long
    ): ControlDeadline = {
      require(activeNanos > 0L && totalNanos > activeNanos, "deadline must reserve settlement time")
      ControlDeadline(admittedAtNanos, activeNanos, totalNanos)
    }
  }

  /** One cancellation generation shared by request parsing, preparation and persistence. */
  final class ControlRequestContext private[fixture] (
    val deadline: ControlDeadline,
    invalidated: AtomicBoolean = new AtomicBoolean(false),
    persistenceSessionMarker: AtomicReference[Option[String]] = new AtomicReference(None)) {
    def remainingActiveNanos(nowNanos: Long): Long =
      if (invalidated.get()) 0L else deadline.remainingActiveNanos(nowNanos)

    def remainingTotalNanos(nowNanos: Long): Long = deadline.remainingTotalNanos(nowNanos)

    def invalidate: IO[Unit] = IO(invalidated.set(true))

    def ensurePersistenceAllowed(nowNanos: IO[Long]): IO[Unit] =
      nowNanos.flatMap(now => IO.raiseWhen(remainingActiveNanos(now) == 0L)(RequestGenerationInvalidated))

    private[fixture] def registerPersistenceSession(marker: String): IO[Unit] =
      IO {
        require(marker.nonEmpty, "fixture persistence session marker is empty")
        persistenceSessionMarker.set(Some(marker))
      }

    private[fixture] def markedPersistenceSession: Option[String] = persistenceSessionMarker.get()
  }

  object ControlRequestContext {
    private[fixture] def apply(deadline: ControlDeadline): ControlRequestContext = new ControlRequestContext(deadline)
  }

  private[fixture] case object RequestGenerationInvalidated
      extends RuntimeException("fixture request cancellation generation is invalid")
      with scala.util.control.NoStackTrace

  final case class ControlResult(status: StatusCode, json: String)
}

trait OAuthE2EFixtureControlHandler {
  import OAuthE2EFixtureControlProtocol.{ControlCommand, ControlRequestContext, ControlResult}

  def handle(
    command: ControlCommand,
    idempotencyKey: UUID,
    context: ControlRequestContext
  ): IO[ControlResult]
}

object OAuthE2EFixtureControlHandler {
  val unavailable: OAuthE2EFixtureControlHandler = new OAuthE2EFixtureControlHandler {
    override def handle(
      command: OAuthE2EFixtureControlProtocol.ControlCommand,
      idempotencyKey: UUID,
      context: OAuthE2EFixtureControlProtocol.ControlRequestContext
    ): IO[OAuthE2EFixtureControlProtocol.ControlResult] =
      IO.pure(OAuthE2EFixtureControlProtocol.ControlResult(StatusCodes.ServiceUnavailable, "{\"result\":\"fixture-service-unavailable\"}"))
  }
}
