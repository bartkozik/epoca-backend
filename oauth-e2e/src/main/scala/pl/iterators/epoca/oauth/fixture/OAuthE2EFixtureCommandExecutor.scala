package pl.iterators.epoca.oauth.fixture

import cats.effect.IO
import org.postgresql.util.PSQLException
import pl.iterators.epoca.oauth.fixture.OAuthE2EFixtureControlProtocol.{ControlDeadline, ControlRequestContext}
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import slick.dbio.{AndThenAction, CleanUpAction, FailedAction, FlatMapAction, SequenceAction}

import scala.concurrent.duration.DurationLong
import scala.concurrent.{ExecutionContext, Promise}
import scala.util.control.NoStackTrace

trait OAuthE2EFixtureControllerSupervisor {

  /** Quarantines the controller by unbinding its private listener and making the database lease terminal. The disposable environment supervisor must
    * then terminate the backend process, await PostgreSQL session disappearance and destroy the run volume; application code never destroys that
    * volume.
    */
  def stopControllerAndDestroyRun: IO[Unit]

  def stopControllerAndDestroyRun(sessionMarker: String): IO[Unit] =
    if (sessionMarker.nonEmpty) stopControllerAndDestroyRun
    else IO.raiseError(new IllegalArgumentException("Fixture termination session marker is empty"))
}

object OAuthE2EFixtureControllerSupervisor {
  private[fixture] val noop: OAuthE2EFixtureControllerSupervisor = new OAuthE2EFixtureControllerSupervisor {
    override def stopControllerAndDestroyRun: IO[Unit] = IO.unit
  }
}

final class OAuthE2EFixtureCommandExecutor(
  transactor: PostgresIOTransactor,
  backendCanceller: PostgresOAuthE2EFixtureBackendCanceller,
  supervisor: OAuthE2EFixtureControllerSupervisor,
  nanoTime: () => Long = () => System.nanoTime(),
  backendPidObserved: Int => Unit = _ => (),
  sessionMarker: () => String = () => s"oauth-e2e-fixture-command-${java.util.UUID.randomUUID()}",
  beforePersistenceOpen: ControlRequestContext => IO[Unit] = _ => IO.unit,
  statementCompleted: Int => Unit = _ => ()
)(implicit executionContext: ExecutionContext) {

  import OAuthE2EFixtureCommandExecutor._

  def execute[A](deadline: ControlDeadline)(command: OAuthE2EFixturePinnedTransaction => OAuthE2EFixtureCommandProgram[A]): IO[ExecutionResult[A]] =
    execute(ControlRequestContext(deadline))(command)

  def execute[A](context: ControlRequestContext)(command: OAuthE2EFixturePinnedTransaction => OAuthE2EFixtureCommandProgram[A])
    : IO[ExecutionResult[A]] =
    executeWith(context, StatementLimits.Command)(command)

  def executeCleanup[A](deadline: ControlDeadline)(command: OAuthE2EFixturePinnedTransaction => OAuthE2EFixtureCommandProgram[A])
    : IO[ExecutionResult[A]] =
    executeCleanup(ControlRequestContext(deadline))(command)

  def executeCleanup[A](context: ControlRequestContext)(command: OAuthE2EFixturePinnedTransaction => OAuthE2EFixtureCommandProgram[A])
    : IO[ExecutionResult[A]] =
    executeWith(context, StatementLimits.Cleanup)(command)

  private def executeWith[A](
    context: ControlRequestContext,
    statementLimits: StatementLimits
  )(
    command: OAuthE2EFixturePinnedTransaction => OAuthE2EFixtureCommandProgram[A]
  ): IO[ExecutionResult[A]] = {
    val deadline        = context.deadline
    val activeRemaining = context.remainingActiveNanos(nanoTime())
    if (activeRemaining == 0L) IO.pure(ExecutionResult.TimedOut(settled = true))
    else {
      val backendPid = Promise[Int]()
      val marker     = sessionMarker()
      val transaction = sql"select set_config('application_name', $marker, true), pg_backend_pid()".as[(String, Int)].head.flatMap { case (_, pid) =>
        DBIO.successful {
          val _ = backendPid.trySuccess(pid)
          backendPidObserved(pid)
        } >> command(new OAuthE2EFixturePinnedTransaction(context, statementLimits, nanoTime, statementCompleted)).dbio.map(result => (result, pid))
      }

      for {
        _     <- beforePersistenceOpen(context)
        ready <- context.ensurePersistenceAllowed(IO(nanoTime())).attempt
        result <- ready match {
                    case Left(_)  => IO.pure(ExecutionResult.TimedOut(settled = true))
                    case Right(_) =>
                      // Once the pinned PostgreSQL child can start, outer HTTP/handler cancellation must not detach it. This lifecycle owns the
                      // child until definitive settlement or until the exact marked fatal handoff has completed.
                      IO.uncancelable { _ =>
                        for {
                          _     <- context.registerPersistenceSession(marker)
                          fiber <- transactor.executePinnedTransactionally(transaction).attempt.start
                          remaining = context.remainingActiveNanos(nanoTime())
                          first <-
                            if (remaining == 0L) IO.pure(Right(()))
                            else IO.race(fiber.joinWithNever, IO.sleep(remaining.nanos))
                          result <- first match {
                                      case Left(Right((value, pid))) => classifySettledSuccess(value, pid, context, marker)
                                      case Left(Left(error))         => classifyEarlyFailure(error, context)
                                      case Right(())                 => context.invalidate *> cancelAndSettle(fiber, backendPid, marker, deadline)
                                    }
                        } yield result
                      }
                  }
      } yield result
    }
  }

  private def classifySettledSuccess[A](
    value: A,
    backendPid: Int,
    context: ControlRequestContext,
    sessionMarker: String
  ): IO[ExecutionResult[A]] =
    if (context.remainingActiveNanos(nanoTime()) == 0L)
      supervisor.stopControllerAndDestroyRun(sessionMarker).attempt.void.as(ExecutionResult.TimedOut(settled = true))
    else IO.pure(ExecutionResult.Succeeded(value, backendPid))

  private def classifyEarlyFailure[A](error: Throwable, context: ControlRequestContext): IO[ExecutionResult[A]] =
    if (context.remainingActiveNanos(nanoTime()) == 0L || isPostgresTimeout(error))
      IO.pure(ExecutionResult.TimedOut(settled = true))
    else IO.pure(ExecutionResult.Failed(error))

  private def cancelAndSettle[A](
    fiber: cats.effect.FiberIO[Either[Throwable, (A, Int)]],
    backendPid: Promise[Int],
    sessionMarker: String,
    deadline: ControlDeadline
  ): IO[ExecutionResult[A]] =
    deadline.remainingTotalNanos(nanoTime()) match {
      case 0L => fatalUnsettled(sessionMarker)
      case pidWaitRemaining =>
        IO.fromFuture(IO(backendPid.future)).start.flatMap { pidWaiter =>
          // A Scala Future cannot be cancelled. Bound only the join so an exhausted primary pool cannot make the request wait forever for a PID.
          IO.race(pidWaiter.joinWithNever, IO.sleep(pidWaitRemaining.nanos)).flatMap {
            case Left(pid) => cancelObservedBackendAndSettle(fiber, pid, sessionMarker, deadline)
            case Right(()) => fatalUnsettled(sessionMarker)
          }
        }
    }

  private def cancelObservedBackendAndSettle[A](
    fiber: cats.effect.FiberIO[Either[Throwable, (A, Int)]],
    backendPid: Int,
    sessionMarker: String,
    deadline: ControlDeadline
  ): IO[ExecutionResult[A]] =
    deadline.remainingTotalNanos(nanoTime()) match {
      case 0L => fatalUnsettled(sessionMarker)
      case reserveRemaining =>
        val cancelBudget = math.min(1000000000L, reserveRemaining)
        backendCanceller.cancelBackend(backendPid, sessionMarker).void.handleErrorWith(_ => IO.unit).start.flatMap { cancelFiber =>
          // The dedicated cancel pool may itself be exhausted. Bound only the wait here; do not await cancellation of the uncancellable JDBC
          // future. Its exact PID + marker predicate makes a late completion a no-op after the marked session disappears.
          IO.race(cancelFiber.joinWithNever, IO.sleep(cancelBudget.nanos)).void *> awaitSettlement(fiber, sessionMarker, deadline)
        }
    }

  private def awaitSettlement[A](
    fiber: cats.effect.FiberIO[Either[Throwable, (A, Int)]],
    sessionMarker: String,
    deadline: ControlDeadline
  ): IO[ExecutionResult[A]] = {
    val remaining = deadline.remainingTotalNanos(nanoTime())
    if (remaining == 0L) fatalUnsettled(sessionMarker)
    else
      IO.race(fiber.joinWithNever, IO.sleep(remaining.nanos)).flatMap {
        case Left(Left(_))  => IO.pure(ExecutionResult.TimedOut(settled = true))
        case Left(Right(_)) => supervisor.stopControllerAndDestroyRun(sessionMarker).attempt.void.as(ExecutionResult.TimedOut(settled = true))
        case Right(())      => fatalUnsettled(sessionMarker)
      }
  }

  private def fatalUnsettled[A](sessionMarker: String): IO[ExecutionResult[A]] =
    supervisor.stopControllerAndDestroyRun(sessionMarker).attempt.void.as(ExecutionResult.TimedOut(settled = false))

  private def isPostgresTimeout(error: Throwable): Boolean =
    Iterator
      .iterate(Option(error))(_.flatMap(value => Option(value.getCause)))
      .takeWhile(_.nonEmpty)
      .flatten
      .collectFirst { case postgres: PSQLException => postgres.getSQLState }
      .exists(state => state == "55P03" || state == "57014")
}

sealed trait OAuthE2EFixtureCommandProgram[+A] { self =>
  private[fixture] def dbio: DBIO[A]

  final def map[B](f: A => B)(implicit executionContext: ExecutionContext): OAuthE2EFixtureCommandProgram[B] =
    OAuthE2EFixtureCommandProgram.composed(dbio.map(f))

  final def flatMap[B](f: A => OAuthE2EFixtureCommandProgram[B])(implicit executionContext: ExecutionContext): OAuthE2EFixtureCommandProgram[B] =
    OAuthE2EFixtureCommandProgram.composed(dbio.flatMap(value => f(value).dbio))

  final def >>[B](next: => OAuthE2EFixtureCommandProgram[B]): OAuthE2EFixtureCommandProgram[B] =
    OAuthE2EFixtureCommandProgram.composed(dbio >> next.dbio)
}

private[fixture] object OAuthE2EFixtureCommandProgram {
  private final case class Composed[A](dbio: DBIO[A]) extends OAuthE2EFixtureCommandProgram[A]

  private[fixture] def composed[A](dbio: DBIO[A]): OAuthE2EFixtureCommandProgram[A]  = Composed(dbio)
  private[fixture] def pure[A](value: A): OAuthE2EFixtureCommandProgram[A]           = Composed(DBIO.successful(value))
  private[fixture] def failed[A](error: Throwable): OAuthE2EFixtureCommandProgram[A] = Composed(DBIO.failed(error))
}

private[fixture] trait OAuthE2EFixtureStatementBoundary {
  def statement[A](action: DBIO[A]): OAuthE2EFixtureCommandProgram[A]
  final def pure[A](value: A): OAuthE2EFixtureCommandProgram[A]           = OAuthE2EFixtureCommandProgram.pure(value)
  final def failed[A](error: Throwable): OAuthE2EFixtureCommandProgram[A] = OAuthE2EFixtureCommandProgram.failed(error)
}

private[fixture] object OAuthE2EFixtureUnguardedStatementBoundary extends OAuthE2EFixtureStatementBoundary {
  override def statement[A](action: DBIO[A]): OAuthE2EFixtureCommandProgram[A] = OAuthE2EFixtureCommandProgram.composed(action)
}

final class OAuthE2EFixturePinnedTransaction private[fixture] (
  context: ControlRequestContext,
  limits: OAuthE2EFixtureCommandExecutor.StatementLimits,
  nanoTime: () => Long,
  statementCompleted: Int => Unit
)(implicit executionContext: ExecutionContext)
    extends OAuthE2EFixtureStatementBoundary {

  private val completedStatements = new java.util.concurrent.atomic.AtomicInteger(0)

  import OAuthE2EFixtureCommandExecutor._

  override def statement[A](action: DBIO[A]): OAuthE2EFixtureCommandProgram[A] =
    if (isComposite(action)) OAuthE2EFixtureCommandProgram.composed(DBIO.failed(CompositeFixtureStatementRejected))
    else
      OAuthE2EFixtureCommandProgram.composed(SimpleDBIO[StatementTimeouts] { _ =>
        val remaining = context.remainingActiveNanos(nanoTime())
        if (remaining < MinimumPostgresTimeoutNanos) throw ActiveDeadlineExceeded
        StatementTimeouts(
          statementMillis = nanosToBoundedMillis(remaining, limits.maximumStatementNanos),
          lockMillis = nanosToBoundedMillis(remaining, limits.maximumLockNanos)
        )
      }.flatMap { timeouts =>
        val statementTimeout = s"${timeouts.statementMillis}ms"
        val lockTimeout      = s"${timeouts.lockMillis}ms"
        sql"select set_config('statement_timeout', $statementTimeout, true), set_config('lock_timeout', $lockTimeout, true)"
          .as[(String, String)]
          .head >> action.map { value =>
          statementCompleted(completedStatements.incrementAndGet())
          value
        }
      })

  private[fixture] def preparedManagement[A](action: DBIO[A]): OAuthE2EFixtureCommandProgram[A] =
    OAuthE2EFixtureCommandProgram.composed(SimpleDBIO[StatementTimeouts] { _ =>
      val remaining = context.remainingActiveNanos(nanoTime())
      if (remaining < MinimumPostgresTimeoutNanos) throw ActiveDeadlineExceeded
      StatementTimeouts(
        statementMillis = nanosToBoundedMillis(remaining, limits.maximumStatementNanos),
        lockMillis = nanosToBoundedMillis(remaining, limits.maximumLockNanos)
      )
    }.flatMap { timeouts =>
      val statementTimeout = s"${timeouts.statementMillis}ms"
      val lockTimeout      = s"${timeouts.lockMillis}ms"
      sql"select set_config('statement_timeout', $statementTimeout, true), set_config('lock_timeout', $lockTimeout, true)"
        .as[(String, String)]
        .head >> action
    })

  private def isComposite[A](action: DBIO[A]): Boolean =
    fullyUnfused(action) match {
      case _: FlatMapAction[_, _, _, _] | _: AndThenAction[_, _, _] | _: SequenceAction[_, _, _] | _: CleanUpAction[_, _, _] | _: FailedAction[_] =>
        true
      case _ => false
    }

  @scala.annotation.tailrec
  private def fullyUnfused(action: DBIO[_], remaining: Int = 16): DBIO[_] = {
    val next = action.nonFusedEquivalentAction.asInstanceOf[DBIO[_]]
    if ((next eq action) || remaining == 0) action else fullyUnfused(next, remaining - 1)
  }

  private def nanosToBoundedMillis(remainingNanos: Long, maximumNanos: Long): Long =
    math.max(1L, math.min(remainingNanos, maximumNanos) / 1000000L)
}

object OAuthE2EFixtureCommandExecutor {
  sealed trait ExecutionResult[+A]
  object ExecutionResult {
    final case class Succeeded[A](value: A, backendPid: Int) extends ExecutionResult[A]
    final case class TimedOut(settled: Boolean)              extends ExecutionResult[Nothing]
    final case class Failed(error: Throwable)                extends ExecutionResult[Nothing]
  }

  private[fixture] final case class StatementLimits(maximumStatementNanos: Long, maximumLockNanos: Long)
  private[fixture] object StatementLimits {
    val Command: StatementLimits = StatementLimits(maximumStatementNanos = 8000000000L, maximumLockNanos = 1000000000L)
    val Cleanup: StatementLimits = StatementLimits(maximumStatementNanos = 10000000000L, maximumLockNanos = 2000000000L)
  }

  private[fixture] final case class StatementTimeouts(statementMillis: Long, lockMillis: Long)
  private[fixture] case object ActiveDeadlineExceeded extends RuntimeException("fixture active deadline exceeded") with NoStackTrace
  private[fixture] case object CompositeFixtureStatementRejected
      extends RuntimeException("fixture composite statement rejected; every SQL boundary must be guarded separately")
      with NoStackTrace
  private[fixture] val MinimumPostgresTimeoutNanos = 1000000L
}
