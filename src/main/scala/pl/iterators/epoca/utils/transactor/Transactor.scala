package pl.iterators.epoca.utils.transactor

import cats.~>
import org.reactivestreams.Publisher

sealed trait IsolationLevel
object IsolationLevel {
  case object ReadUncommitted extends IsolationLevel
  case object ReadCommitted   extends IsolationLevel
  case object RepeatableRead  extends IsolationLevel
  case object Serializable    extends IsolationLevel
}

case object StreamingNotSupported

trait Transactor[F[_], G[_]] extends (F ~> G) {
  def failed(t: => Throwable): F[Nothing]
  def from[T](g: => G[T]): F[T]

  def stream[T](f: => F[T]): Either[StreamingNotSupported.type, Publisher[T]]

  def execute[T](f: => F[T]): G[T]
  def executePinned[T](f: => F[T]): G[T]
  def executeTransactionally[T](f: => F[T]): G[T]
  def executeTransactionally[T](level: IsolationLevel)(f: => F[T]): G[T]

  override def apply[A](fa: F[A]): G[A] = executeTransactionally(fa)
}
