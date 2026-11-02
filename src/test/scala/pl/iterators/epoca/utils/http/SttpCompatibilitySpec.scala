package pl.iterators.epoca.utils.http

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import fs2.Stream
import io.circe.Decoder
import org.specs2.execute.{AsResult, Error, Failure, Result, Success}
import org.specs2.matcher.MatchResult
import org.specs2.mutable.SpecificationLike
import org.specs2.specification.Around
import pl.iterators.epoca.utils.log.{CorrelationIdBackend, TracingContext}
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4._
import sttp.client4.circe.asJson
import sttp.client4.httpclient.fs2.HttpClientFs2Backend
import sttp.client4.logging.{LogLevel, Logger, LoggingBackend}
import sttp.model.{StatusCode, Uri}

import java.io.{BufferedReader, IOException, InputStreamReader}
import java.net.{InetAddress, InetSocketAddress, ServerSocket, Socket, SocketException}
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, ExecutorService, Executors, Future, TimeUnit}
import scala.concurrent.duration.DurationInt
import scala.util.Try

class SttpCompatibilitySpec extends SpecificationLike {
  import SttpCompatibilitySpec._

  sequential

  private implicit val compatibilityPayloadDecoder: Decoder[CompatibilityPayload] =
    Decoder.forProduct2("id", "message")(CompatibilityPayload.apply)

  private final class RecordingLogger extends Logger[IO] {
    private val entries = new ConcurrentLinkedQueue[String]()

    override def apply(level: LogLevel, message: => String): IO[Unit] =
      IO.delay {
        val _ = entries.add(s"$level:$message")
      }

    override def apply(
      level: LogLevel,
      message: => String,
      throwable: Throwable
    ): IO[Unit] =
      IO.delay {
        val _ = entries.add(s"$level:$message:${throwable.getClass.getName}")
      }

    def messages: Vector[String] = entries.toArray(new Array[String](0)).toVector
  }

  private trait HttpScope extends Around {
    protected def loopbackResponse: LoopbackResponse
    protected def verifyStreamRelease: Boolean = false

    private val server = new ServerSocket()
    server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 1)

    private val activeSocket    = new AtomicReference[Socket]()
    private val serverFailure   = new AtomicReference[Throwable]()
    private val backendReleased = new AtomicBoolean(false)
    private val cleanupStarted  = new AtomicBoolean(false)
    private val lifecycle       = new ConcurrentLinkedQueue[String]()
    private val requestLines    = new ConcurrentLinkedQueue[String]()
    private val serverThread    = new AtomicReference[Thread]()
    private val serverExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable: Runnable =>
      val thread = new Thread(runnable, s"sttp-compatibility-${server.getLocalPort}")
      thread.setDaemon(true)
      serverThread.set(thread)
      thread
    }

    private val serverTask = serverExecutor.submit(new Runnable {
      override def run(): Unit = serveOneRequest()
    })
    private val partialAcquisition = new PartialAcquisition(server, activeSocket, serverExecutor, serverTask, serverThread)
    observePartialAcquisition(partialAcquisition)

    protected def observePartialAcquisition(resources: PartialAcquisition): Unit = ()

    protected def allocateBackend(): AllocatedBackend = HttpClientFs2Backend.resource[IO]().allocated.unsafeRunSync()

    private val allocatedBackend =
      try allocateBackend()
      catch {
        case acquisitionFailure: Throwable =>
          try partialAcquisition.rollbackServerResources()
          catch { case cleanupFailure: Throwable => acquisitionFailure.addSuppressed(cleanupFailure) }
          throw acquisitionFailure
      }
    val backend: WebSocketStreamBackend[IO, Fs2Streams[IO]] = allocatedBackend._1
    private val releaseBackend: IO[Unit]                    = allocatedBackend._2
    val endpoint: Uri                                       = uri"http://127.0.0.1:${server.getLocalPort}/compatibility"

    private def serveOneRequest(): Unit =
      try {
        val socket = server.accept()
        activeSocket.set(socket)
        try {
          val reader = new BufferedReader(new InputStreamReader(socket.getInputStream, StandardCharsets.US_ASCII))
          var line   = reader.readLine()
          while (line != null && line.nonEmpty) {
            val _ = requestLines.add(line)
            line = reader.readLine()
          }

          if (loopbackResponse.responseDelayMillis > 0L) Thread.sleep(loopbackResponse.responseDelayMillis)

          val bodyBytes = loopbackResponse.body.getBytes(StandardCharsets.UTF_8)
          val headers =
            s"HTTP/1.1 ${loopbackResponse.statusCode} ${loopbackResponse.reason}\r\n" +
              s"Content-Type: ${loopbackResponse.contentType}\r\n" +
              s"Content-Length: ${bodyBytes.length}\r\n" +
              "Connection: close\r\n\r\n"
          val output = socket.getOutputStream
          output.write(headers.getBytes(StandardCharsets.US_ASCII))
          output.write(bodyBytes)
          output.flush()
        } finally {
          socket.close()
          val _ = activeSocket.compareAndSet(socket, null)
        }
      } catch {
        case _: InterruptedException if loopbackResponse.clientCancellationExpected => Thread.currentThread().interrupt()
        case _: IOException if loopbackResponse.clientCancellationExpected          => ()
        case _: SocketException if server.isClosed                                  => ()
        case throwable: Throwable =>
          val _ = serverFailure.compareAndSet(null, throwable)
      }

    def recordedRequestLines: Vector[String] = requestLines.toArray(new Array[String](0)).toVector

    def markStreamConsumed[A](value: A): IO[A] =
      IO.delay {
        val _ = lifecycle.add("stream-consumed")
        value
      }

    private def cleanupResult: Result = {
      val cleanupErrors = Vector.newBuilder[Throwable]
      def capture(action: => Unit): Unit =
        try action
        catch { case throwable: Throwable => cleanupErrors += throwable }

      if (cleanupStarted.compareAndSet(false, true)) {
        capture(TracingContext.unset().unsafeRunSync())
        capture {
          releaseBackend.unsafeRunSync()
          backendReleased.set(true)
          val _ = lifecycle.add("backend-released")
        }
        capture(server.close())
        capture(Option(activeSocket.getAndSet(null)).foreach(_.close()))
        capture {
          val _ = serverTask.cancel(true)
        }
        capture {
          val _ = serverExecutor.shutdownNow()
          val _ = serverExecutor.awaitTermination(2L, TimeUnit.SECONDS)
        }
      }

      val expectedLifecycle =
        if (verifyStreamRelease)
          Vector("stream-consumed", "backend-released")
        else Vector("backend-released")
      val actualLifecycleState =
        (backendReleased.get(), server.isClosed, serverExecutor.isTerminated, lifecycle.toArray(new Array[String](0)).toVector)
      val expectedLifecycleState = (true, true, true, expectedLifecycle)
      val failures               = cleanupErrors.result() ++ Option(serverFailure.get())
      val failureResult: Result = failures.headOption match {
        case None => Success()
        case Some(head) =>
          failures.tail.foreach(head.addSuppressed)
          Error(head)
      }
      val stateResult: Result =
        if (actualLifecycleState == expectedLifecycleState) Success()
        else Failure(s"HTTP scope cleanup state mismatch: actual=$actualLifecycleState expected=$expectedLifecycleState")

      Result.issues(Vector(failureResult, stateResult), "\n")
    }

    private def cleanupThrowable(result: Result): Throwable = result match {
      case Error(_, throwable) => throwable
      case other               => new IllegalStateException(s"HTTP scope cleanup issue: ${other.message}")
    }

    private def combineLifecycleResults(primary: Result, cleanup: Result): Result =
      if (cleanup.isSuccess) primary
      else if (primary.isSuccess) cleanup
      else {
        primary match {
          case Error(_, primaryThrowable) =>
            val secondaryThrowable = cleanupThrowable(cleanup)
            if ((secondaryThrowable ne primaryThrowable) && !primaryThrowable.getSuppressed.contains(secondaryThrowable))
              primaryThrowable.addSuppressed(secondaryThrowable)
          case _ => ()
        }

        primary
          .updateMessage(s"${primary.message}\nsecondary cleanup issue: ${cleanup.message}")
          .setExpectationsNb(primary.expectationsNb + cleanup.expectationsNb)
      }

    override def around[T: AsResult](body: => T): Result = {
      val primary =
        try AsResult.safely(body)
        catch {
          case fatal: Throwable =>
            val cleanup = cleanupResult
            if (!cleanup.isSuccess) fatal.addSuppressed(cleanupThrowable(cleanup))
            throw fatal
        }

      combineLifecycleResults(primary, cleanupResult)
    }
  }

  private final class FailingFinalizerScope(responseBody: String, cleanupFailure: Throwable) extends HttpScope {
    override protected def loopbackResponse: LoopbackResponse = LoopbackResponse(StatusCode.Ok.code, "OK", responseBody)

    override protected def allocateBackend(): AllocatedBackend = {
      val (allocated, release) = super.allocateBackend()
      (allocated, release *> IO.raiseError(cleanupFailure))
    }

    def passingBody: MatchResult[Any] = {
      val response = basicRequest.get(endpoint).send(backend).unsafeRunSync()
      response.body mustEqual Right(responseBody)
    }
  }

  "the STTP FS2 real client" should {
    "decode a successful JSON response with Circe" in new HttpScope {
      override protected def loopbackResponse: LoopbackResponse =
        LoopbackResponse(StatusCode.Ok.code, "OK", """{"id":7,"message":"ready"}""")

      val response = basicRequest.get(endpoint).response(asJson[CompatibilityPayload]).send(backend).unsafeRunSync()

      (response.code mustEqual StatusCode.Ok) and
        (response.body mustEqual Right(CompatibilityPayload(7, "ready")))
    }

    "classify a successful response with invalid JSON as a Circe decoding failure" in new HttpScope {
      override protected def loopbackResponse: LoopbackResponse =
        LoopbackResponse(StatusCode.Ok.code, "OK", """{"id":"wrong","message":false}""")

      val response = basicRequest.get(endpoint).response(asJson[CompatibilityPayload]).send(backend).unsafeRunSync()
      val isDecodeFailure = response.body match {
        case Left(_: DeserializationException[_]) => true
        case _                                    => false
      }

      (response.code mustEqual StatusCode.Ok) and (isDecodeFailure must beTrue)
    }

    "classify a 500 response as an HTTP error without decoding its body" in new HttpScope {
      override protected def loopbackResponse: LoopbackResponse =
        LoopbackResponse(StatusCode.InternalServerError.code, "Internal Server Error", "body500", contentType = "text/plain")

      val response = basicRequest.get(endpoint).response(asJson[CompatibilityPayload]).send(backend).unsafeRunSync()
      val isExpectedHttpError = response.body match {
        case Left(HttpError("body500", StatusCode.InternalServerError)) => true
        case _                                                          => false
      }

      (response.code mustEqual StatusCode.InternalServerError) and (isExpectedHttpError must beTrue)
    }

    "cancel a request when a delayed response exceeds the read timeout" in new HttpScope {
      override protected def loopbackResponse: LoopbackResponse =
        LoopbackResponse(StatusCode.Ok.code, "OK", "delayed", responseDelayMillis = 350L, clientCancellationExpected = true)

      val outcome = basicRequest.get(endpoint).readTimeout(100.millis).send(backend).attempt.unsafeRunSync()
      val isTimeout = outcome match {
        case Left(_: SttpClientException.TimeoutException) => true
        case _                                             => false
      }

      isTimeout must beTrue
    }

    "consume a finite streamed body completely before releasing the backend" in new HttpScope {
      override protected def loopbackResponse: LoopbackResponse =
        LoopbackResponse(StatusCode.Ok.code, "OK", "first-chunk|second-chunk|done", contentType = "application/octet-stream")
      override protected def verifyStreamRelease: Boolean = true

      val response = basicRequest
        .get(endpoint)
        .response(asStreamAlways(Fs2Streams[IO]) { stream: Stream[IO, Byte] =>
          stream.compile.toVector.flatMap(markStreamConsumed)
        })
        .send(backend)
        .unsafeRunSync()
      val body = new String(response.body.toArray, StandardCharsets.UTF_8)

      (response.code mustEqual StatusCode.Ok) and (body mustEqual "first-chunk|second-chunk|done")
    }

    "send through LoggingBackend and CorrelationIdBackend" in new HttpScope {
      override protected def loopbackResponse: LoopbackResponse = LoopbackResponse(StatusCode.Ok.code, "OK", "logged")

      val logger            = new RecordingLogger
      val loggingBackend    = LoggingBackend(backend, logger)
      val correlationHeader = "Correlation-Id"
      val correlatedBackend = CorrelationIdBackend(loggingBackend, correlationHeader)
      val tracingContext    = TracingContext("context", "task9-correlation", Map.empty)

      val response           = (TracingContext.set(tracingContext) *> basicRequest.get(endpoint).send(correlatedBackend)).unsafeRunSync()
      val correlationWasSent = recordedRequestLines.exists(_.equalsIgnoreCase(s"$correlationHeader: task9-correlation"))

      (response.body mustEqual Right("logged")) and
        (correlationWasSent must beTrue) and
        (logger.messages.nonEmpty must beTrue)
    }

    "roll back server resources when backend acquisition fails" in {
      val injectedFailure = new IllegalStateException("injected backend acquisition failure")
      val observedPartial = new AtomicReference[PartialAcquisition]()

      val acquisition = Try {
        new HttpScope {
          override protected def loopbackResponse: LoopbackResponse = LoopbackResponse(StatusCode.Ok.code, "OK", "unused")

          override protected def observePartialAcquisition(resources: PartialAcquisition): Unit =
            observedPartial.set(resources)

          override protected def allocateBackend(): AllocatedBackend = throw injectedFailure
        }
      }
      val resources           = Option(observedPartial.get()).getOrElse(throw new IllegalStateException("partial acquisition missing"))
      val stateBeforeRecovery = resources.snapshot
      resources.rollbackServerResources()
      val stateAfterRecovery = resources.snapshot
      val expectedCleanState = PartialAcquisitionState(
        serverClosed = true,
        executorShutdown = true,
        executorTerminated = true,
        serverTaskDone = true,
        portOpen = false,
        serverThreadAlive = false
      )

      (acquisition.failed.toOption must beSome(injectedFailure)) and
        (stateBeforeRecovery mustEqual expectedCleanState) and
        (stateAfterRecovery mustEqual expectedCleanState)
    }

    "wait for the server thread to exit after its executor terminates during rollback" in {
      val server                  = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
      val activeSocket            = new AtomicReference[Socket]()
      val serverThread            = new AtomicReference[Thread]()
      val workerTailEntered       = new CountDownLatch(1)
      val allowWorkerThreadToExit = new CountDownLatch(1)
      val rollbackFinished        = new CountDownLatch(1)
      val rollbackFailure         = new AtomicReference[Throwable]()
      val executor = Executors.newSingleThreadExecutor { runnable: Runnable =>
        val thread = new Thread(
          new Runnable {
            override def run(): Unit = {
              runnable.run()
              workerTailEntered.countDown()
              val wasInterrupted = Thread.interrupted()
              try allowWorkerThreadToExit.await()
              finally if (wasInterrupted) Thread.currentThread().interrupt()
            }
          },
          "sttp-compatibility-gated-worker"
        )
        serverThread.set(thread)
        thread
      }
      val serverTask = executor.submit(new Runnable {
        override def run(): Unit = ()
      })
      val resources = new PartialAcquisition(server, activeSocket, executor, serverTask, serverThread)
      val rollbackThread = new Thread(
        new Runnable {
          override def run(): Unit =
            try resources.rollbackServerResources()
            catch { case throwable: Throwable => rollbackFailure.set(throwable) }
            finally rollbackFinished.countDown()
        },
        "sttp-compatibility-rollback"
      )

      rollbackThread.start()
      val (executorTerminatedBeforeWorkerExit, rollbackReturnedBeforeWorkerExit) =
        try {
          val executorTerminated = workerTailEntered.await(2L, TimeUnit.SECONDS) && executor.isTerminated
          val rollbackReturned   = rollbackFinished.await(250L, TimeUnit.MILLISECONDS)
          (executorTerminated, rollbackReturned)
        } finally {
          allowWorkerThreadToExit.countDown()
          rollbackThread.join(TimeUnit.SECONDS.toMillis(2L))
          Option(serverThread.get()).foreach(_.join(TimeUnit.SECONDS.toMillis(2L)))
          val _ = executor.shutdownNow()
          if (!server.isClosed) server.close()
        }

      val expectedCleanState = PartialAcquisitionState(
        serverClosed = true,
        executorShutdown = true,
        executorTerminated = true,
        serverTaskDone = true,
        portOpen = false,
        serverThreadAlive = false
      )

      (executorTerminatedBeforeWorkerExit must beTrue) and
        (rollbackReturnedBeforeWorkerExit must beFalse) and
        (rollbackFinished.getCount mustEqual 0L) and
        (rollbackFailure.get() must beNull) and
        (resources.snapshot mustEqual expectedCleanState)
    }

    "surface a failing backend finalizer through the Specs2 lifecycle" in {
      val cleanupFailure = new IllegalStateException("injected backend finalizer failure")
      val scope          = new FailingFinalizerScope("cleanup", cleanupFailure)

      val lifecycleResult = scope.apply(scope.passingBody)
      val cleanupFailureWasReported = lifecycleResult match {
        case Error(_, throwable) => throwable eq cleanupFailure
        case _                   => false
      }

      (lifecycleResult.isError must beTrue) and (cleanupFailureWasReported must beTrue)
    }

    "preserve a primary body error and attach a failing finalizer through the Specs2 lifecycle" in {
      val bodyFailure    = new IllegalArgumentException("injected primary body failure")
      val cleanupFailure = new IllegalStateException("injected secondary finalizer failure")
      val scope          = new FailingFinalizerScope("unused", cleanupFailure)

      val lifecycleResult = scope.apply[Result] {
        throw bodyFailure
      }
      val primaryAndSecondaryWerePreserved = lifecycleResult match {
        case Error(_, throwable) =>
          (throwable eq bodyFailure) && throwable.getSuppressed.contains(cleanupFailure)
        case _ => false
      }

      primaryAndSecondaryWerePreserved must beTrue
    }
  }
}

object SttpCompatibilitySpec {
  type AllocatedBackend = (WebSocketStreamBackend[IO, Fs2Streams[IO]], IO[Unit])

  private final case class CompatibilityPayload(id: Int, message: String)
  private final case class LoopbackResponse(
    statusCode: Int,
    reason: String,
    body: String,
    contentType: String = "application/json",
    responseDelayMillis: Long = 0L,
    clientCancellationExpected: Boolean = false)

  final case class PartialAcquisitionState(
    serverClosed: Boolean,
    executorShutdown: Boolean,
    executorTerminated: Boolean,
    serverTaskDone: Boolean,
    portOpen: Boolean,
    serverThreadAlive: Boolean)

  final class PartialAcquisition(
    server: ServerSocket,
    activeSocket: AtomicReference[Socket],
    serverExecutor: ExecutorService,
    serverTask: Future[_],
    serverThread: AtomicReference[Thread]) {

    private val terminationTimeoutSeconds = 2L

    def snapshot: PartialAcquisitionState = PartialAcquisitionState(
      serverClosed = server.isClosed,
      executorShutdown = serverExecutor.isShutdown,
      executorTerminated = serverExecutor.isTerminated,
      serverTaskDone = serverTask.isDone,
      portOpen = server.isBound && !server.isClosed,
      serverThreadAlive = Option(serverThread.get()).exists(_.isAlive)
    )

    def rollbackServerResources(): Unit = {
      val cleanupFailures = Vector.newBuilder[Throwable]
      def capture(action: => Unit): Unit =
        try action
        catch { case throwable: Throwable => cleanupFailures += throwable }

      capture(server.close())
      capture(Option(activeSocket.getAndSet(null)).foreach(_.close()))
      capture {
        val _ = serverTask.cancel(true)
      }
      capture {
        val _ = serverExecutor.shutdownNow()
        if (!serverExecutor.awaitTermination(terminationTimeoutSeconds, TimeUnit.SECONDS))
          throw new IllegalStateException("HTTP scope server executor did not terminate")
      }
      capture {
        Option(serverThread.get()).foreach { thread =>
          thread.join(TimeUnit.SECONDS.toMillis(terminationTimeoutSeconds))
          if (thread.isAlive) throw new IllegalStateException("HTTP scope server thread did not terminate")
        }
      }

      val failures = cleanupFailures.result()
      failures.headOption.foreach { head =>
        failures.tail.foreach(head.addSuppressed)
        throw head
      }
    }
  }
}
