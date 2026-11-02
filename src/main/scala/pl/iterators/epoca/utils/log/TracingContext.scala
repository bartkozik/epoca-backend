package pl.iterators.epoca.utils.log

import cats.effect.{IO, IOLocal}
import slick.dbio.DBIO

// based on: https://github.com/softwaremill/bootzooka/blob/master/backend/src/main/scala/com/softwaremill/bootzooka/infrastructure/CorrelationId.scala

case class TracingContext(
  contextName: String,
  correlationId: String,
  contextMap: Map[String, String]) {
  val context: String = s"cid: $correlationId" + contextMap.map { case (key, value) => s" $key: $value" }.mkString

  def withMoreContext(kv: (String, String)*): TracingContext =
    copy(contextMap = contextMap ++ kv.toMap)

  override def toString: String = context
}

object TracingContext {
  import cats.effect.unsafe.implicits.global
  private val tracingContext: IOLocal[Option[TracingContext]] =
    IOLocal(None: Option[TracingContext]).unsafeRunSync()

  def get: IO[Option[TracingContext]]    = tracingContext.get
  def set(ctx: TracingContext): IO[Unit] = tracingContext.set(Some(ctx))
  def unset(): IO[Unit]                  = tracingContext.set(None)
}

trait TracingContextSource[+F[_]] {
  def get: F[Option[TracingContext]]
  def map[T](f: Option[TracingContext] => T): F[T]
  def update(updater: TracingContext => TracingContext): F[Unit]
  def set(ctx: TracingContext): F[Unit]
  def unset(): F[Unit]
}

object TracingContextSource {
  implicit val ioTracingContextSource: TracingContextSource[IO] = new TracingContextSource[IO] {
    override def get: IO[Option[TracingContext]]               = TracingContext.get
    override def map[T](f: Option[TracingContext] => T): IO[T] = TracingContext.get.map(f)
    override def update(updater: TracingContext => TracingContext): IO[Unit] = TracingContext.get.flatMap {
      case Some(c) => TracingContext.set(updater(c))
      case None    => IO.unit
    }
    override def set(ctx: TracingContext): IO[Unit] = TracingContext.set(ctx)
    override def unset(): IO[Unit]                  = TracingContext.unset()
  }

  implicit val dbioTracingContextSource: TracingContextSource[DBIO] = new TracingContextSource[DBIO] {
    override def get: DBIO[Option[TracingContext]]                             = DBIO.successful(None) // noop, no slick support for tracing
    override def map[T](f: Option[TracingContext] => T): DBIO[T]               = DBIO.successful(f(None)) // noop, no slick support for tracing
    override def update(updater: TracingContext => TracingContext): DBIO[Unit] = DBIO.successful(()) // noop, no slick support for tracing
    override def set(ctx: TracingContext): DBIO[Unit]                          = DBIO.successful(()) // noop, no slick support for tracing
    override def unset(): DBIO[Unit]                                           = DBIO.successful(()) // noop, no slick support for tracing
  }

  def apply[F[_]: TracingContextSource]: TracingContextSource[F] = implicitly[TracingContextSource[F]]
}
