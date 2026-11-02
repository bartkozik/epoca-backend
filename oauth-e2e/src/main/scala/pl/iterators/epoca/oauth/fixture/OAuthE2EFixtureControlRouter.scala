package pl.iterators.epoca.oauth.fixture

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import io.circe.Json
import io.circe.parser.parse
import org.apache.pekko.http.scaladsl.model.headers.{Authorization, OAuth2BearerToken}
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, HttpRequest, HttpResponse, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives.{complete, extractRequest, onSuccess}
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.util.ByteString
import pl.iterators.epoca.oauth.fixture.OAuthE2EFixtureControlProtocol.ControlCommand.{Advance300Seconds, Cleanup, RevokeOAuth029, Setup, Status}
import pl.iterators.epoca.oauth.fixture.OAuthE2EFixtureControlProtocol.{ControlCommand, ControlDeadline, ControlRequestContext, ControlResult, Limits}

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.UUID
import scala.collection.mutable.ArrayDeque
import scala.concurrent.duration.DurationLong
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

class OAuthE2EFixtureControlTokenVerifier(configured: String) {
  private val configuredDigest = sha256(configured)

  private[fixture] def comparePresented(presented: String): Boolean =
    MessageDigest.isEqual(configuredDigest, sha256(presented))

  private def sha256(value: String): Array[Byte] =
    MessageDigest.getInstance("SHA-256").digest(value.getBytes(UTF_8))
}

final class OAuthE2EFixtureFailedAuthLimiter(nanoTime: () => Long) {
  private val failures = ArrayDeque.empty[Long]

  def permitComparison(): Boolean = synchronized {
    removeExpired(nanoTime())
    failures.size < Limits.MaximumFailedAuthAttempts
  }

  def recordFailure(): Unit = synchronized {
    val now = nanoTime()
    removeExpired(now)
    if (failures.size < Limits.MaximumFailedAuthAttempts) {
      val _ = failures.append(now)
    }
  }

  private[fixture] def recordedFailures: Int = synchronized(failures.size)

  private def removeExpired(now: Long): Unit =
    while (failures.headOption.exists(failedAt => now - failedAt >= Limits.FailedAuthWindowNanos)) {
      val _ = failures.removeHead()
    }
}

final class OAuthE2EFixtureControlRouter(
  controlTokenVerifier: OAuthE2EFixtureControlTokenVerifier,
  failedAuthLimiter: OAuthE2EFixtureFailedAuthLimiter,
  handler: OAuthE2EFixtureControlHandler,
  nanoTime: () => Long = () => System.nanoTime(),
  supervisor: OAuthE2EFixtureControllerSupervisor = OAuthE2EFixtureControllerSupervisor.noop,
  commandDeadline: Long => ControlDeadline = ControlDeadline.command,
  cleanupDeadline: Long => ControlDeadline = ControlDeadline.cleanup
)(implicit
  runtime: IORuntime,
  executionContext: ExecutionContext,
  materializer: Materializer) {

  val route: Route = extractRequest { request =>
    onSuccess(process(request).unsafeToFuture())(complete(_))
  }

  private def process(request: HttpRequest): IO[HttpResponse] = {
    val admittedAt = nanoTime()
    if (!authenticated(request)) IO.pure(unauthorized)
    else
      parseAdmission(request, admittedAt).flatMap {
        case Left(response) => IO.pure(response)
        case Right((command, idempotencyKey, context)) =>
          if (context.remainingActiveNanos(nanoTime()) == 0L)
            supervisor.stopControllerAndDestroyRun.attempt.void.as(failed)
          else
            runHandler(command, idempotencyKey, context)
      }
  }

  private def runHandler(
    command: ControlCommand,
    idempotencyKey: UUID,
    context: ControlRequestContext
  ): IO[HttpResponse] =
    IO.uncancelable { _ =>
      for {
        fiber <- handler.handle(command, idempotencyKey, context).attempt.start
        remaining = context.remainingActiveNanos(nanoTime())
        first <- if (remaining == 0L) IO.pure(Right(())) else IO.race(fiber.joinWithNever, IO.sleep(remaining.nanos))
        response <- first match {
                      case Left(Right(result)) if context.remainingActiveNanos(nanoTime()) > 0L => IO.pure(boundedResponse(result))
                      case Left(Right(_)) =>
                        context.invalidate *> requireTerminationIfPersistenceStarted(context).as(failed)
                      case Left(Left(_)) => context.invalidate.as(failed)
                      case Right(()) =>
                        context.invalidate *> settleCancelledHandler(fiber, context)
                    }
      } yield response
    }

  private def settleCancelledHandler(fiber: cats.effect.FiberIO[Either[Throwable, ControlResult]], context: ControlRequestContext): IO[HttpResponse] =
    for {
      cancellation <- fiber.cancel.start
      remaining = context.remainingTotalNanos(nanoTime())
      settled <- if (remaining == 0L) IO.pure(false)
                 else IO.race(cancellation.joinWithNever, IO.sleep(remaining.nanos)).map(_.isLeft)
      _ <- if (settled) IO.unit else requireTermination(context)
    } yield failed

  private def requireTerminationIfPersistenceStarted(context: ControlRequestContext): IO[Unit] =
    context.markedPersistenceSession.fold(IO.unit)(marker => supervisor.stopControllerAndDestroyRun(marker).attempt.void)

  private def requireTermination(context: ControlRequestContext): IO[Unit] =
    context.markedPersistenceSession match {
      case Some(marker) => supervisor.stopControllerAndDestroyRun(marker).attempt.void
      case None         => supervisor.stopControllerAndDestroyRun.attempt.void
    }

  private def authenticated(request: HttpRequest): Boolean = {
    val presented = request.headers.collect { case Authorization(OAuth2BearerToken(token)) => token }
    val allowed   = failedAuthLimiter.permitComparison()
    val compared  = allowed && controlTokenVerifier.comparePresented(presented.headOption.getOrElse(""))
    val valid     = compared && presented.size == 1
    if (!valid) failedAuthLimiter.recordFailure()
    valid
  }

  private def parseAdmission(request: HttpRequest, admittedAt: Long): IO[Either[HttpResponse, (ControlCommand, UUID, ControlRequestContext)]] = {
    val path   = request.uri.path.toString()
    val rawUri = request.uri.toString()
    if (request.uri.rawQueryString.nonEmpty || rawUri.contains('%') || path.endsWith("/") || path.contains("//")) IO.pure(Left(notFound))
    else
      canonicalIdempotencyKey(request) match {
        case None => IO.pure(Left(badRequest))
        case Some(key) =>
          (request.method.value, pathSegments(path)) match {
            case ("POST", Vector("oauth-e2e-fixture", "v1", "runs")) =>
              val context = ControlRequestContext(commandDeadline(admittedAt))
              readSetupBody(request, context).map(_.map(runId => (Setup(runId), key, context)))
            case ("POST", Vector("oauth-e2e-fixture", "v1", "runs", rawRunId, "advance-300-seconds")) =>
              bodyless(request, rawRunId).map(_.map(runId => (Advance300Seconds(runId), key, ControlRequestContext(commandDeadline(admittedAt)))))
            case ("POST", Vector("oauth-e2e-fixture", "v1", "runs", rawRunId, "revoke-oauth029")) =>
              bodyless(request, rawRunId).map(_.map(runId => (RevokeOAuth029(runId), key, ControlRequestContext(commandDeadline(admittedAt)))))
            case ("GET", Vector("oauth-e2e-fixture", "v1", "runs", rawRunId, "status")) =>
              bodyless(request, rawRunId).map(_.map(runId => (Status(runId), key, ControlRequestContext(commandDeadline(admittedAt)))))
            case ("DELETE", Vector("oauth-e2e-fixture", "v1", "runs", rawRunId)) =>
              bodyless(request, rawRunId).map(_.map(runId => (Cleanup(runId), key, ControlRequestContext(cleanupDeadline(admittedAt)))))
            case _ => IO.pure(Left(notFound))
          }
      }
  }

  private def bodyless(request: HttpRequest, rawRunId: String): IO[Either[HttpResponse, UUID]] =
    IO.pure(
      if (!request.entity.isKnownEmpty) Left(badRequest)
      else canonicalUuid(rawRunId).toRight(badRequest)
    )

  private def readSetupBody(request: HttpRequest, context: ControlRequestContext): IO[Either[HttpResponse, UUID]] =
    if (request.entity.contentType != ContentTypes.`application/json`) IO.pure(Left(unsupportedMediaType))
    else if (request.entity.contentLengthOption.exists(_ > Limits.MaximumRequestBytes.toLong)) IO.pure(Left(payloadTooLarge))
    else
      context.remainingActiveNanos(nanoTime()) match {
        case 0L => supervisor.stopControllerAndDestroyRun.attempt.void.as(Left(failed))
        case remaining =>
          IO.fromFuture(IO(readBoundedEntity(request, remaining)))
            .timeoutTo(remaining.nanos, IO.raiseError(new java.util.concurrent.TimeoutException("fixture request body timed out")))
            .attempt
            .flatMap {
              case Right(raw)                                                              => IO.pure(parseSetup(raw))
              case Left(_: org.apache.pekko.http.scaladsl.model.EntityStreamSizeException) => IO.pure(Left(payloadTooLarge))
              case Left(_: java.util.concurrent.TimeoutException) => supervisor.stopControllerAndDestroyRun.attempt.void.as(Left(failed))
              case Left(_)                                        => IO.pure(Left(badRequest))
            }
      }

  private def readBoundedEntity(request: HttpRequest, remainingNanos: Long): Future[String] =
    request.entity
      .withSizeLimit(Limits.MaximumRequestBytes.toLong)
      .dataBytes
      .completionTimeout(remainingNanos.nanos)
      .runWith(Sink.fold(ByteString.empty)(_ ++ _))
      .map(_.utf8String)

  private def parseSetup(raw: String): Either[HttpResponse, UUID] =
    Option
      .when(SetupRunIdFieldPattern.findAllMatchIn(raw).size == 1)(raw)
      .flatMap(value => parse(value).toOption)
      .flatMap(_.asObject)
      .filter(_.keys.toSet == Set("runId"))
      .flatMap(_.apply("runId"))
      .flatMap(_.asString)
      .flatMap(canonicalUuid)
      .toRight(badRequest)

  private def canonicalIdempotencyKey(request: HttpRequest): Option[UUID] = {
    val values = request.headers.filter(_.is("idempotency-key")).map(_.value())
    Option.when(values.size == 1)(values.head).flatMap(canonicalUuid)
  }

  private def canonicalUuid(value: String): Option[UUID] =
    Try(UUID.fromString(value)).toOption.filter(_.toString == value)

  private def pathSegments(path: String): Vector[String] =
    path.split("/", -1).iterator.filter(_.nonEmpty).toVector

  private def boundedResponse(result: ControlResult): HttpResponse = {
    val encoded = ByteString(result.json, UTF_8)
    if (encoded.length > Limits.MaximumResponseBytes || parse(result.json).isLeft || result.status.isRedirection()) failed
    else HttpResponse(result.status, entity = HttpEntity(ContentTypes.`application/json`, encoded))
  }

  private val unauthorized = jsonResponse(StatusCodes.Unauthorized, Json.obj("error" -> Json.fromString("unauthorized")))
  private val badRequest   = jsonResponse(StatusCodes.BadRequest, Json.obj("error" -> Json.fromString("invalid-request")))
  private val unsupportedMediaType =
    jsonResponse(StatusCodes.UnsupportedMediaType, Json.obj("error" -> Json.fromString("invalid-content-type")))
  private val payloadTooLarge        = jsonResponse(StatusCodes.ContentTooLarge, Json.obj("error" -> Json.fromString("request-too-large")))
  private val notFound               = jsonResponse(StatusCodes.NotFound, Json.obj("error" -> Json.fromString("not-found")))
  private val failed                 = jsonResponse(StatusCodes.InternalServerError, Json.obj("error" -> Json.fromString("fixture-control-failed")))
  private val SetupRunIdFieldPattern = "\\\"runId\\\"\\s*:".r

  private def jsonResponse(status: org.apache.pekko.http.scaladsl.model.StatusCode, json: Json): HttpResponse =
    HttpResponse(status, entity = HttpEntity(ContentTypes.`application/json`, json.noSpaces))
}
