package pl.iterators.epoca.utils.streams

import cats.effect._
import cats.effect.kernel.Resource.ExitCase.{Canceled, Errored, Succeeded}
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import fs2._
import org.apache.pekko.stream._
import org.apache.pekko.stream.scaladsl.{Flow => AkkaFlow, Sink => AkkaSink, Source => AkkaSource, _}
import org.apache.pekko.{Done, NotUsed}

import scala.annotation.implicitNotFound
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

trait Fs2PekkoStreamConverter {

  def akkaSourceToFs2Stream[A](source: Graph[SourceShape[A], NotUsed])(implicit materializer: Materializer): Stream[IO, A] =
    Stream.force {
      Async[IO].delay {
        val subscriber = AkkaSource.fromGraph(source).toMat(AkkaSink.queue[A]())(Keep.right).run()
        subscriberStream[A](subscriber)
      }
    }

  def akkaSourceToFs2StreamMat[A, M](source: Graph[SourceShape[A], M])(implicit materializer: Materializer): IO[(Stream[IO, A], M)] =
    Async[IO].delay {
      val (mat, subscriber) =
        AkkaSource.fromGraph(source).toMat(AkkaSink.queue[A]())(Keep.both).run()
      (subscriberStream[A](subscriber), mat)
    }

  def akkaSinkToFs2Pipe[A](sink: Graph[SinkShape[A], NotUsed])(implicit materializer: Materializer): Pipe[IO, A, Unit] =
    (s: Stream[IO, A]) =>
      Stream.force {
        Async[IO].delay {
          val publisher =
            AkkaSource.queue[A](0, OverflowStrategy.backpressure).toMat(sink)(Keep.left).run()
          publisherStream[A](publisher, s)
        }
      }

  def akkaSinkToFs2PipeMat[A, M](sink: Graph[SinkShape[A], M])(implicit materializer: Materializer): IO[(Pipe[IO, A, Unit], M)] =
    Async[IO].delay {
      val (publisher, mat) =
        AkkaSource.queue[A](0, OverflowStrategy.backpressure).toMat(sink)(Keep.both).run()
      ((s: Stream[IO, A]) => publisherStream[A](publisher, s), mat)
    }

  def akkaSinkToFs2PipeMat[A, M](akkaSink: Graph[SinkShape[A], Future[M]])(implicit ec: ExecutionContext, m: Materializer)
    : IO[Pipe[IO, A, Either[Throwable, M]]] =
    for {
      promise <- Deferred[IO, Either[Throwable, M]]
      fs2Sink <- akkaSinkToFs2PipeMat[A, Future[M]](akkaSink).flatMap { case (stream, mat) =>
                   val callback = Async[IO].delay(mat.onComplete {
                     case Failure(ex)    => promise.complete(ex.asLeft).unsafeRunSync()
                     case Success(value) => promise.complete(value.asRight).unsafeRunSync()
                   })
                   callback.map(_ => stream)
                 }
    } yield { (in: Stream[IO, A]) =>
      val materializedResultStream    = Stream.eval(promise.get)
      val fs2Stream: Stream[IO, Unit] = fs2Sink.apply(in)

      fs2Stream.drain ++ materializedResultStream
    }

  def akkaFlowToFs2Pipe[A, B](flow: Graph[FlowShape[A, B], NotUsed])(implicit materializer: Materializer): Pipe[IO, A, B] =
    (s: Stream[IO, A]) =>
      Stream.force {
        Async[IO].delay {
          val src                     = AkkaSource.queue[A](0, OverflowStrategy.backpressure)
          val snk                     = AkkaSink.queue[B]()
          val (publisher, subscriber) = src.viaMat(flow)(Keep.left).toMat(snk)(Keep.both).run()
          transformerStream[A, B](subscriber, publisher, s)
        }
      }

  def akkaFlowToFs2PipeMat[A, B, M](flow: Graph[FlowShape[A, B], M])(implicit materializer: Materializer): IO[(Pipe[IO, A, B], M)] =
    Async[IO].delay {
      val src                            = AkkaSource.queue[A](0, OverflowStrategy.backpressure)
      val snk                            = AkkaSink.queue[B]()
      val ((publisher, mat), subscriber) = src.viaMat(flow)(Keep.both).toMat(snk)(Keep.both).run()
      ((s: Stream[IO, A]) => transformerStream[A, B](subscriber, publisher, s), mat)
    }

  def fs2StreamToAkkaSource[A](stream: Stream[IO, A]): Graph[SourceShape[A], NotUsed] = {
    val source = AkkaSource.queue[A](0, OverflowStrategy.backpressure)
    val sink = AkkaSink.foreach[SourceQueueWithComplete[A]] { p =>
      import cats.effect.unsafe.implicits.global

      publisherStream[A](p, stream).compile.drain.unsafeToFuture(): Unit
      ()
    }

    AkkaSource
      .fromGraph(GraphDSL.createGraph(source) { implicit builder => source =>
        import GraphDSL.Implicits._
        builder.materializedValue ~> sink
        SourceShape(source.out)
      })
      .mapMaterializedValue(_ => NotUsed)
  }

  def fs2PipeToAkkaSink[A](sink: Pipe[IO, A, Unit]): Graph[SinkShape[A], Future[Done]] = {
    val sink1: AkkaSink[A, SinkQueueWithCancel[A]] = AkkaSink.queue[A]()
    val sink2: AkkaSink[SinkQueueWithCancel[A], Future[Done]] = AkkaFlow[SinkQueueWithCancel[A]]
      .map(s => subscriberStream[A](s).through(sink).compile.drain.as(Done: Done).unsafeToFuture())
      .toMat(AkkaSink.head)(Keep.right)
      .mapMaterializedValue(ffd => ffd.flatten)

    AkkaSink
      .fromGraph(GraphDSL.createGraph(sink1, sink2)(Keep.both) { implicit builder => (sink1, sink2) =>
        import GraphDSL.Implicits._
        builder.materializedValue ~> AkkaFlow[(SinkQueueWithCancel[A], _)].map(_._1) ~> sink2
        SinkShape(sink1.in)
      })
      .mapMaterializedValue(_._2)
  }

  def fs2PipeToAkkaFlow[A, B](pipe: Pipe[IO, A, B]): Graph[FlowShape[A, B], NotUsed] = {
    val source                                     = AkkaSource.queue[B](0, OverflowStrategy.backpressure)
    val sink1: AkkaSink[A, SinkQueueWithCancel[A]] = AkkaSink.queue[A]()
    val sink2 = AkkaSink.foreach[(SourceQueueWithComplete[B], SinkQueueWithCancel[A])] { ps =>
      transformerStream(ps._2, ps._1, pipe).compile.drain.unsafeToFuture(): Unit
      ()
    }

    AkkaFlow
      .fromGraph(GraphDSL.createGraph(source, sink1)(Keep.both) { implicit builder => (source, sink1) =>
        import GraphDSL.Implicits._
        builder.materializedValue ~> sink2
        FlowShape(sink1.in, source.out)
      })
      .mapMaterializedValue(_ => NotUsed)
  }

  private def subscriberStream[A](subscriber: SinkQueueWithCancel[A]): Stream[IO, A] = {
    val pull   = Async[IO].fromFuture(Async[IO].delay(subscriber.pull()))
    val cancel = Async[IO].delay(subscriber.cancel())
    Stream.repeatEval(pull).unNoneTerminate.onFinalize(cancel)
  }

  private def publisherStream[A](publisher: SourceQueueWithComplete[A], stream: Stream[IO, A]): Stream[IO, Unit] = {
    def publish(a: A): IO[Option[Unit]] = Async[IO]
      .fromFuture(Async[IO].delay(publisher.offer(a)))
      .flatMap {
        case QueueOfferResult.Enqueued       => ().some.pure[IO]
        case QueueOfferResult.Failure(cause) => Async[IO].raiseError[Option[Unit]](cause)
        case QueueOfferResult.QueueClosed    => none[Unit].pure[IO]
        case QueueOfferResult.Dropped =>
          Concurrent[IO].raiseError[Option[Unit]](new IllegalStateException("This should never happen because we use OverflowStrategy.backpressure"))
      }
      .recover {
        // This handles a race condition between `interruptWhen` and `publish`.
        // There's no guarantee that, when the akka sink is terminated, we will observe the
        // `interruptWhen` termination before calling publish one last time.
        // Such a call fails with StreamDetachedException
        case _: StreamDetachedException => none[Unit]
      }

    def watchCompletion: IO[Unit] =
      Async[IO].fromFuture(Concurrent[IO].delay(publisher.watchCompletion())).void
    def fail(e: Throwable): IO[Unit] = Concurrent[IO].delay(publisher.fail(e)) >> watchCompletion
    def complete: IO[Unit]           = Concurrent[IO].delay(publisher.complete()) >> watchCompletion

    stream
      .interruptWhen(watchCompletion.attempt)
      .evalMap(publish)
      .unNoneTerminate
      .onFinalizeCase {
        case Canceled   => complete
        case Errored(e) => fail(e)
        case Succeeded  => complete
      }
  }

  private def transformerStream[A, B](
    subscriber: SinkQueueWithCancel[B],
    publisher: SourceQueueWithComplete[A],
    stream: Stream[IO, A]
  ): Stream[IO, B] =
    subscriberStream[B](subscriber).concurrently(publisherStream[A](publisher, stream))

  private def transformerStream[A, B](
    subscriber: SinkQueueWithCancel[A],
    publisher: SourceQueueWithComplete[B],
    pipe: Pipe[IO, A, B]
  ): Stream[IO, Unit] =
    subscriberStream[A](subscriber).through(pipe).through(s => publisherStream(publisher, s))
}

trait Fs2PekkoStreamConverterDsl extends Fs2PekkoStreamConverter {

  implicit class PekkoSourceDsl[A, M](source: Graph[SourceShape[A], M]) {

    def toStream(implicit
      materializer: Materializer,
      @implicitNotFound(
        "Cannot convert `Source[A, M]` to `Stream[F, A]` - `M` value would be discarded.\nIf that is intended, first convert the `Source` to `Source[A, NotUsed]`.\nIf `M` should not be discarded, then use `source.toStreamMat[F]` instead."
      ) ev: M <:< NotUsed
    ): Stream[IO, A] = {
      val _ =
        ev
      akkaSourceToFs2Stream(source.asInstanceOf[Graph[SourceShape[A], NotUsed]])
    }

    def toStreamMat(
      implicit
      materializer: Materializer
    ): IO[(Stream[IO, A], M)] =
      akkaSourceToFs2StreamMat(source)
  }

  implicit class PekkoSinkFutureDsl[A, M](sink: Graph[SinkShape[A], Future[M]]) {

    def toPipeMatWithResult(implicit
      ec: ExecutionContext,
      m: Materializer
    ): IO[Pipe[IO, A, Either[Throwable, M]]] =
      akkaSinkToFs2PipeMat[A, M](sink)

  }

  implicit class PekkoSinkDsl[A, M](sink: Graph[SinkShape[A], M]) {

    def toPipe(implicit
      materializer: Materializer,
      @implicitNotFound(
        "Cannot convert `Sink[A, M]` to `Pipe[F, A, Unit]` - `M` value would be discarded.\nIf that is intended, first convert the `Sink` to `Sink[A, NotUsed]`.\nIf `M` should not be discarded, then use `sink.toPipeMat[F]` instead."
      ) ev: M <:< NotUsed
    ): Pipe[IO, A, Unit] = {
      val _ =
        ev
      akkaSinkToFs2Pipe(sink.asInstanceOf[Graph[SinkShape[A], NotUsed]])
    }

    def toPipeMat(
      implicit
      materializer: Materializer
    ): IO[(Pipe[IO, A, Unit], M)] =
      akkaSinkToFs2PipeMat(sink)
  }

  implicit class PekkoFlowDsl[A, B, M](flow: Graph[FlowShape[A, B], M]) {

    def toPipe(implicit
      materializer: Materializer,
      @implicitNotFound(
        "Cannot convert `Flow[A, B, M]` to `Pipe[F, A, B]` - `M` value would be discarded.\nIf that is intended, first convert the `Flow` to `Flow[A, B, NotUsed]`.\nIf `M` should not be discarded, then use `flow.toPipeMat[F]` instead."
      ) ev: M <:< NotUsed
    ): Pipe[IO, A, B] = {
      val _ =
        ev
      akkaFlowToFs2Pipe(flow.asInstanceOf[Graph[FlowShape[A, B], NotUsed]])
    }

    def toPipeMat(
      implicit
      materializer: Materializer
    ): IO[(Pipe[IO, A, B], M)] =
      akkaFlowToFs2PipeMat(flow)
  }

  implicit class FS2StreamIODsl[A](stream: Stream[IO, A]) {

    def toSource: Graph[SourceShape[A], NotUsed] =
      fs2StreamToAkkaSource(stream)
  }

  implicit class FS2SinkIODsl[A](sink: Pipe[IO, A, Unit]) {

    def toSink: Graph[SinkShape[A], Future[Done]] =
      fs2PipeToAkkaSink(sink)
  }

  implicit class FS2PipeIODsl[A, B](pipe: Pipe[IO, A, B]) {

    def toFlow: Graph[FlowShape[A, B], NotUsed] =
      fs2PipeToAkkaFlow(pipe)
  }
}

object Fs2PekkoStreamConverterSyntax$ extends Fs2PekkoStreamConverterDsl
