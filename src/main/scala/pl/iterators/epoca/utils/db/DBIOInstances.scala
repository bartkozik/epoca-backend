package pl.iterators.epoca.utils.db

/** Copyright 2017-present, Risk Management Solutions, Inc. All rights reserved.
  *
  * This source code is licensed under the BSD-style license found in https://github.com/RMSone/slick-cats/blob/master/LICENSE.md
  */

import cats._
import cats.effect.Sync
import cats.effect.kernel.{CancelScope, Poll}
import cats.syntax.all._
import pl.iterators.epoca.utils.log.{TracingContext, TracingContextSource}
import slick.basic.BasicBackend
import slick.dbio._

import scala.concurrent.duration.{FiniteDuration, _}
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success}

object DBIOInstances extends DBIOInstances

/** Instances are only provided for `DBIO[A]` and not for `DBIOAction[A, NoStream, Effect.All]` or any other DBIOAction. An implicit
  * `ExecutionContext` must be in scope for the type class conversion to occur. It will otherwise fail with rather unhelpful errors.
  */
trait DBIOInstances extends DBIOInstances0 {
  implicit def dbioInstance(implicit ec: ExecutionContext)
    : MonadError[DBIO, Throwable] with CoflatMap[DBIO] with Sync[DBIO] with TracingContextSource[DBIO] =
    new DBIOCoflatMap with MonadError[DBIO, Throwable] with Sync[DBIO] with TracingContextSource[DBIO] {
      override def suspend[A](hint: Sync.Type)(thunk: => A): DBIO[A] = DBIO.successful(()).map(_ => thunk)

      override val monotonic: DBIO[FiniteDuration] = delay(System.nanoTime().nanos)

      override def realTime: DBIO[FiniteDuration] = delay(System.currentTimeMillis().millis)

      override def rootCancelScope: CancelScope = CancelScope.Uncancelable

      override def forceR[A, B](fa: DBIO[A])(fb: DBIO[B]): DBIO[B] = productR(attempt(fa))(fb)

      override def uncancelable[A](body: Poll[DBIO] => DBIO[A]): DBIO[A] = body(new Poll[DBIO] { def apply[X](fa: DBIO[X]): DBIO[X] = fa })

      override def canceled: DBIO[Unit] = unit

      override def onCancel[A](fa: DBIO[A], fin: DBIO[Unit]): DBIO[A] = fa

      override def pure[A](x: A): DBIO[A] = DBIO.successful(x)

      override def flatMap[A, B](fa: DBIO[A])(f: (A) => DBIO[B]): DBIO[B] = fa.flatMap(f)

      override def handleError[A](fea: DBIO[A])(f: (Throwable) => A): DBIO[A] =
        fea.asTry.map {
          case Success(a) => a
          case Failure(t) => f(t)
        }

      override def raiseError[A](e: Throwable): DBIO[A] = DBIO.failed(e)

      override def map[A, B](fa: DBIO[A])(f: A => B): DBIO[B] = fa.map(f)

      override def handleErrorWith[A](fa: DBIO[A])(f: (Throwable) => DBIO[A]): DBIO[A] =
        fa.asTry.flatMap {
          case Success(a) => DBIO.successful(a)
          case Failure(t) => f(t)
        }

      override def get: DBIO[Option[TracingContext]] = DBIO.successful(None) // noop, no slick support for tracing

      override def map[T](f: Option[TracingContext] => T): DBIO[T] = DBIO.successful(f(None)) // noop, no slick support for tracing

      override def update(updater: TracingContext => TracingContext): DBIO[Unit] = DBIO.successful(()) // noop, no slick support for tracing

      override def set(ctx: TracingContext): DBIO[Unit] = DBIO.successful(()) // noop, no slick support for tracing

      override def unset(): DBIO[Unit] = DBIO.successful(()) // noop, no slick support for tracing

      override def tailRecM[A, B](a: A)(f: A => DBIO[Either[A, B]]): DBIO[B] =
        f(a).flatMap {
          case Left(a1) => tailRecM(a1)(f)
          case Right(b) => DBIO.successful(b)
        }
    }

  implicit def dbioGroup[A: Group](implicit ec: ExecutionContext): Group[DBIO[A]] =
    new DBIOGroup[A]

  implicit def dbioMonoid[A: Monoid](implicit ec: ExecutionContext): Monoid[DBIO[A]] =
    new DBIOMonoid[A]

  implicit def dbioSemigroup[A: Semigroup](implicit ec: ExecutionContext): Semigroup[DBIO[A]] =
    new DBIOSemigroup[A]
}

private[db] abstract class DBIOCoflatMap(implicit ec: ExecutionContext) extends CoflatMap[DBIO] {
  def map[A, B](fa: DBIO[A])(f: A => B): DBIO[B]             = fa.map(f)
  def coflatMap[A, B](fa: DBIO[A])(f: DBIO[A] => B): DBIO[B] = DBIO.from(Future(f(fa)))
}

private[db] class DBIOSemigroup[A: Semigroup](implicit ec: ExecutionContext) extends Semigroup[DBIO[A]] {
  override def combine(fx: DBIO[A], fy: DBIO[A]): DBIO[A] =
    (fx zip fy).map { case (x, y) => x |+| y }
}

private[db] class DBIOMonoid[A](implicit A: Monoid[A], ec: ExecutionContext) extends DBIOSemigroup[A] with Monoid[DBIO[A]] {
  def empty: DBIO[A] = DBIO.successful(A.empty)
}

private[db] class DBIOGroup[A](implicit A: Group[A], ec: ExecutionContext) extends DBIOMonoid[A] with Group[DBIO[A]] {
  def inverse(fx: DBIO[A]): DBIO[A] = fx.map(_.inverse())

  override def remove(fx: DBIO[A], fy: DBIO[A]): DBIO[A] =
    (fx zip fy).map { case (x, y) => x |-| y }
}

private[db] sealed trait DBIOInstances0 extends DBIOInstances1 {
  def dbioComonad(atMost: FiniteDuration, db: BasicBackend#Database)(implicit ec: ExecutionContext): Comonad[DBIO] =
    new DBIOCoflatMap with Comonad[DBIO] {
      def extract[A](x: DBIO[A]): A =
        Await.result(db.run(x), atMost)
    }

  def dbioOrder[A: Order](atMost: FiniteDuration, db: BasicBackend#Database)(implicit ec: ExecutionContext): Order[DBIO[A]] =
    (x: DBIO[A], y: DBIO[A]) => Await.result(db.run((x zip y).map { case (a, b) => a compare b }), atMost)
}

private[db] sealed trait DBIOInstances1 extends DBIOInstances2 {
  def dbioPartialOrder[A: PartialOrder](atMost: FiniteDuration, db: BasicBackend#Database)(implicit ec: ExecutionContext): PartialOrder[DBIO[A]] =
    (x: DBIO[A], y: DBIO[A]) => Await.result(db.run((x zip y).map { case (a, b) => a partialCompare b }), atMost)
}

private[db] sealed trait DBIOInstances2 {
  def dbioEq[A: Eq](atMost: FiniteDuration, db: BasicBackend#Database)(implicit ec: ExecutionContext): Eq[DBIO[A]] =
    (x: DBIO[A], y: DBIO[A]) => Await.result(db.run((x zip y).map { case (a, b) => a === b }), atMost)
}
