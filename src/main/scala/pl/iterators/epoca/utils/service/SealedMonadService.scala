package pl.iterators.epoca.utils.service
import cats.effect.unsafe.IORuntime
import cats.effect.{IO, Sync}
import pl.iterators.epoca.utils.clock.ClockExtension
import pl.iterators.epoca.utils.db.DBIOInstances._
import pl.iterators.sealedmonad.Sealed
import slick.dbio.DBIO

import scala.concurrent.ExecutionContext

abstract class SealedMonadServiceIO[ADT](val ioRuntime: IORuntime) {
  protected type Step[T] = Sealed[IO, T, ADT]
}

abstract class SealedMonadServiceDBIO[ADT](val ioRuntime: IORuntime) extends ClockExtension {
  protected type Step[T] = Sealed[DBIO, T, ADT]
  implicit val ec: ExecutionContext = ioRuntime.compute
  implicit val dbioSync: Sync[DBIO] = implicitly[Sync[DBIO]]
}

abstract class SealedMonadServiceIODBIO[ADT](val ioRuntime: IORuntime) extends ClockExtension {
  protected type StepDBIO[T] = Sealed[DBIO, T, ADT]
  protected type StepIO[T]   = Sealed[IO, T, ADT]

  implicit val ec: ExecutionContext = ioRuntime.compute
  implicit val dbioSync: Sync[DBIO] = implicitly[Sync[DBIO]]
}
