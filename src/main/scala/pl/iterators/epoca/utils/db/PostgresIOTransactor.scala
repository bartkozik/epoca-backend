package pl.iterators.epoca.utils.db

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import org.reactivestreams.Publisher
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import pl.iterators.epoca.utils.transactor.{IsolationLevel, StreamingNotSupported, Transactor}
import slick.jdbc.TransactionIsolation

import scala.concurrent.ExecutionContext
import scala.util.Try

class PostgresIOTransactor(db: Database)(implicit runtime: IORuntime) extends Transactor[DBIO, IO] {
  override def failed(t: => Throwable): DBIO[Nothing] = DBIO.failed(t)
  override def from[T](g: => IO[T]): DBIO[T]          = DBIO.from(g.unsafeToFuture())
  override def stream[T](f: => DBIO[T]): Either[StreamingNotSupported.type, Publisher[T]] =
    Try(db.stream(f.asInstanceOf[DBIOAction[T, Streaming[T], Effect.All]])).toEither.left
      .map(_ => StreamingNotSupported)
  override def execute[T](f: => DBIO[T]): IO[T]                = IO.fromFuture(IO(db.run(f)))
  override def executePinned[T](f: => DBIO[T]): IO[T]          = IO.fromFuture(IO(db.run(f.withPinnedSession)))
  override def executeTransactionally[T](f: => DBIO[T]): IO[T] = IO.fromFuture(IO(db.run(f.transactionally)))
  override def executeTransactionally[T](level: IsolationLevel)(f: => DBIO[T]): IO[T] =
    IO.fromFuture(IO(db.run(f.transactionally.withTransactionIsolation(isolationLevelToTransactionIsolation(level)))))

  private[epoca] def executePinnedTransactionally[T](f: => DBIO[T]): IO[T] =
    IO.fromFuture(IO(db.run(f.transactionally.withPinnedSession)))

  /** Used by bounded background work only. Await JDBC completion on cancellation so the transaction cannot outlive its admission slot. */
  private[epoca] def executeBoundedTransactionally[T](deadlineNanos: Long)(action: => DBIO[T])(implicit ec: ExecutionContext): IO[T] =
    IO.uncancelable { _ =>
      executeTransactionally {
        for {
          _ <- PostgresIOTransactor.requireBefore(deadlineNanos)
          remainingMillis  = math.max(1L, (deadlineNanos - System.nanoTime()) / 1000000L)
          lockTimeout      = math.min(1000L, remainingMillis).toString + "ms"
          statementTimeout = math.min(3000L, remainingMillis).toString + "ms"
          _ <-
            sql"SELECT set_config('lock_timeout', $lockTimeout, true), set_config('statement_timeout', $statementTimeout, true)".as[(String, String)]
          result <- action
          _      <- PostgresIOTransactor.requireBefore(deadlineNanos)
        } yield result
      }
    }

  private def isolationLevelToTransactionIsolation(isolationLevel: IsolationLevel): TransactionIsolation =
    isolationLevel match {
      case IsolationLevel.ReadCommitted   => TransactionIsolation.ReadCommitted
      case IsolationLevel.ReadUncommitted => TransactionIsolation.ReadUncommitted
      case IsolationLevel.RepeatableRead  => TransactionIsolation.RepeatableRead
      case IsolationLevel.Serializable    => TransactionIsolation.Serializable
    }
}

object PostgresIOTransactor {
  private[epoca] def requireBefore(deadlineNanos: Long)(implicit ec: ExecutionContext): DBIO[Unit] =
    DBIO.successful(()).flatMap { _ =>
      if (deadlineNanos - System.nanoTime() > 0) DBIO.successful(())
      else DBIO.failed(new java.util.concurrent.TimeoutException("Background database job expired"))
    }
}
