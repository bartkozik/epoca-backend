package pl.iterators.epoca.oauth.fixture

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.apache.pekko.http.scaladsl.model.headers.{Authorization, OAuth2BearerToken, RawHeader}
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, HttpMethods, HttpRequest, StatusCodes, Uri}
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.Specs2RouteTest
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import org.slf4j.{Logger, LoggerFactory}
import org.specs2.mutable.SpecificationLike
import org.specs2.specification.Scope
import pl.iterators.epoca.oauth.fixture.OAuthE2EFixtureControlProtocol.ControlCommand.{Advance300Seconds, Cleanup, RevokeOAuth029, Setup, Status}
import pl.iterators.epoca.oauth.fixture.OAuthE2EFixtureControlProtocol.{ControlCommand, ControlDeadline, ControlRequestContext, ControlResult}

import java.util.{Base64, UUID}
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters._

class OAuthE2EFixtureControlRouterSpec extends SpecificationLike with Specs2RouteTest {
  sequential

  private implicit val runtime: IORuntime = IORuntime.global

  "OAuth E2E fixture control router" should {
    "expose only the five exact versioned routes and methods without query aliases" in new RouterScope {
      post("/oauth-e2e-fixture/v1/runs", setupBody(runId)) ~> route ~> check(status shouldEqual StatusCodes.OK)
      post(s"/oauth-e2e-fixture/v1/runs/$runId/advance-300-seconds") ~> route ~> check(status shouldEqual StatusCodes.OK)
      post(s"/oauth-e2e-fixture/v1/runs/$runId/revoke-oauth029") ~> route ~> check(status shouldEqual StatusCodes.OK)
      get(s"/oauth-e2e-fixture/v1/runs/$runId/status") ~> route ~> check(status shouldEqual StatusCodes.OK)
      delete(s"/oauth-e2e-fixture/v1/runs/$runId") ~> route ~> check(status shouldEqual StatusCodes.OK)

      handled.toVector shouldEqual Vector(Setup(runId), Advance300Seconds(runId), RevokeOAuth029(runId), Status(runId), Cleanup(runId))

      Seq(
        HttpRequest(HttpMethods.GET, uri = "/oauth-e2e-fixture/v1/runs"),
        HttpRequest(HttpMethods.POST, uri = s"/oauth-e2e-fixture/v1/runs/$runId/status"),
        HttpRequest(HttpMethods.POST, uri = s"/oauth-e2e-fixture/v1/runs/$runId/advance-300-seconds/"),
        HttpRequest(HttpMethods.POST, uri = s"/oauth-e2e-fixture/v1/runs//$runId/advance-300-seconds"),
        HttpRequest(HttpMethods.POST, uri = s"/oauth-e2e-fixture/v1/runs/$runId/revoke"),
        HttpRequest(HttpMethods.GET, uri = s"/oauth-e2e-fixture/v1/runs/$runId/status?command=cleanup"),
        HttpRequest(HttpMethods.DELETE, uri = s"/oauth-e2e-fixture/v1/runs/$runId?force=true"),
        HttpRequest(HttpMethods.POST, uri = s"/api/v1/oauth-e2e-fixture/runs/$runId")
      ).foreach(request => authorized(request) ~> route ~> check(status shouldEqual StatusCodes.NotFound))
    }

    "authenticate in constant-time through a generic response and rate-limit after five failures" in new RouterScope {
      val missing = post(s"/oauth-e2e-fixture/v1/runs/$runId/advance-300-seconds", authorize = false)
      missing ~> route ~> check {
        status shouldEqual StatusCodes.Unauthorized
        responseAs[String] shouldEqual "{\"error\":\"unauthorized\"}"
      }

      (1 to 6).foreach { _ =>
        post(s"/oauth-e2e-fixture/v1/runs/$runId/advance-300-seconds", token = "oauth-e2e-v1.attacker") ~> route ~> check {
          status shouldEqual StatusCodes.Unauthorized
          responseAs[String] shouldEqual "{\"error\":\"unauthorized\"}"
        }
      }
      failedAuthLimiter.recordedFailures shouldEqual 5
      tokenVerifier.comparedInputs.size shouldEqual 5
      handled should beEmpty
    }

    "reject duplicate bearer headers and a control token reused by a replacement controller" in new RouterScope {
      val duplicate = HttpRequest(
        HttpMethods.POST,
        uri = s"/oauth-e2e-fixture/v1/runs/$runId/advance-300-seconds",
        headers = List(
          Authorization(OAuth2BearerToken(controlToken)),
          Authorization(OAuth2BearerToken(controlToken)),
          RawHeader("Idempotency-Key", idempotencyKey)
        )
      )
      duplicate ~> route ~> check {
        status shouldEqual StatusCodes.Unauthorized
        responseAs[String] shouldEqual "{\"error\":\"unauthorized\"}"
      }

      val replacementToken = versionedToken(96)
      val replacementRouter = new OAuthE2EFixtureControlRouter(
        controlTokenVerifier = new RecordingTokenVerifier(replacementToken),
        failedAuthLimiter = new OAuthE2EFixtureFailedAuthLimiter(() => 0L),
        handler = handler,
        nanoTime = () => 1000L
      )
      authorized(HttpRequest(HttpMethods.POST, uri = s"/oauth-e2e-fixture/v1/runs/$runId/advance-300-seconds")) ~>
        Route.seal(replacementRouter.route) ~> check {
          status shouldEqual StatusCodes.Unauthorized
          responseAs[String] shouldEqual "{\"error\":\"unauthorized\"}"
        }

      handled should beEmpty
    }

    "reject OAuth-shaped credentials on cleanup before invoking the control handler" in new RouterScope {
      val oauthCredential = "epoca_oauth_secret_v1.synthetic-not-a-control-token"

      authorized(HttpRequest(HttpMethods.DELETE, uri = s"/oauth-e2e-fixture/v1/runs/$runId"), token = oauthCredential) ~> route ~> check {
        status shouldEqual StatusCodes.Unauthorized
        responseAs[String] shouldEqual "{\"error\":\"unauthorized\"}"
      }
      tokenVerifier.comparedInputs shouldEqual Vector(oauthCredential)
      handled should beEmpty
    }

    "require one canonical UUID idempotency key on every request" in new RouterScope {
      Seq(
        HttpRequest(HttpMethods.POST, uri = s"/oauth-e2e-fixture/v1/runs/$runId/advance-300-seconds"),
        HttpRequest(
          HttpMethods.POST,
          uri = s"/oauth-e2e-fixture/v1/runs/$runId/advance-300-seconds",
          headers = List(RawHeader("Idempotency-Key", "not-a-uuid"))
        ),
        HttpRequest(
          HttpMethods.POST,
          uri = s"/oauth-e2e-fixture/v1/runs/$runId/advance-300-seconds",
          headers = List(RawHeader("Idempotency-Key", idempotencyKey), RawHeader("idempotency-key", UUID.randomUUID().toString))
        )
      ).foreach(request => authorizedOnly(request) ~> route ~> check(status shouldEqual StatusCodes.BadRequest))

      handled should beEmpty
    }

    "accept only the exact setup object and reject bodies on bodyless commands" in new RouterScope {
      Seq(
        "{}",
        s"{\"runId\":\"$runId\",\"alias\":\"oauth027\"}",
        s"{\"runId\":\"$runId\",\"runId\":\"$runId\"}",
        s"{\"run_id\":\"$runId\"}",
        s"{\"runId\":\"${runId.toString.toUpperCase}\"}",
        "{\"runId\":1}",
        "not-json"
      ).foreach(body => post("/oauth-e2e-fixture/v1/runs", body) ~> route ~> check(status shouldEqual StatusCodes.BadRequest))

      post(s"/oauth-e2e-fixture/v1/runs/$runId/advance-300-seconds", "{}") ~> route ~> check(status shouldEqual StatusCodes.BadRequest)
      get(s"/oauth-e2e-fixture/v1/runs/$runId/status", body = "{}") ~> route ~> check(status shouldEqual StatusCodes.BadRequest)
      handled should beEmpty
    }

    "reject SQL, identifier, alias, traversal, and encoded-path injection without echoing attacker material" in new RouterScope {
      val bodySentinel   = "oauth-e2e-attack-body-' OR 1=1 --"
      val headerSentinel = "oauth-e2e-attack-header-sentinel"
      val requests = Seq(
        authorized(
          HttpRequest(
            HttpMethods.POST,
            uri = "/oauth-e2e-fixture/v1/runs",
            entity = HttpEntity(ContentTypes.`application/json`, s"{\"runId\":\"$bodySentinel\",\"alias\":\"oauth029\"}")
          ).addHeader(RawHeader("X-Attack-Sentinel", headerSentinel))
        ),
        authorized(HttpRequest(HttpMethods.POST, uri = s"/oauth-e2e-fixture/v1/runs/$runId/%2e%2e/revoke-oauth029")),
        authorized(HttpRequest(HttpMethods.POST, uri = s"/oauth-e2e-fixture/v1/runs/$runId/revoke-oauth029%2fcleanup")),
        authorized(HttpRequest(HttpMethods.POST, uri = "/oauth-e2e-fixture/v1/runs/oauth029/revoke-oauth029"))
      )

      requests.foreach { request =>
        request ~> route ~> check {
          Set(StatusCodes.BadRequest, StatusCodes.NotFound) must contain(status)
          val rendered = response.toString + responseAs[String]
          rendered must not(contain(bodySentinel))
          rendered must not(contain(headerSentinel))
        }
      }
      handled should beEmpty
    }

    "never publish hostile fixture headers or entities to the application logging event stream" in new RouterScope {
      val headerSentinel  = "oauth-e2e-fixture-log-header-sentinel"
      val entitySentinel  = "oauth-e2e-fixture-log-entity-sentinel"
      val captureSentinel = "oauth-e2e-fixture-log-capture-control"
      val rootLogger      = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME).asInstanceOf[ch.qos.logback.classic.Logger]
      val oldLevel        = rootLogger.getLevel
      val appender        = new ListAppender[ILoggingEvent]
      appender.start()
      rootLogger.setLevel(Level.TRACE)
      rootLogger.addAppender(appender)

      try {
        rootLogger.warn(captureSentinel)
        appender.list.asScala.map(_.getFormattedMessage).mkString("\n") must contain(captureSentinel)
        appender.list.clear()

        val hostileRequest = authorized(
          HttpRequest(
            HttpMethods.POST,
            uri = "/oauth-e2e-fixture/v1/runs",
            entity = HttpEntity(ContentTypes.`application/json`, s"{\"runId\":\"$entitySentinel\"}")
          ).addHeader(RawHeader("X-OAuth E2E-Fixture-Sentinel", headerSentinel))
        )
        hostileRequest ~> route ~> check(status shouldEqual StatusCodes.BadRequest)

        val emitted = appender.list.asScala
          .map { event =>
            val arguments = Option(event.getArgumentArray).fold("")(_.mkString("\n"))
            val throwable = Option(event.getThrowableProxy).fold("")(_.getMessage)
            s"${event.getFormattedMessage}\n$arguments\n$throwable"
          }
          .mkString("\n")
        emitted must not(contain(headerSentinel))
        emitted must not(contain(entitySentinel))
        handled should beEmpty
      } finally {
        rootLogger.detachAppender(appender)
        rootLogger.setLevel(oldLevel)
        appender.stop()
      }
    }

    "require JSON content type for setup and enforce the exact 16 KiB input boundary" in new RouterScope {
      val wrongContent = authorized(
        HttpRequest(HttpMethods.POST, uri = "/oauth-e2e-fixture/v1/runs", entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, setupBody(runId)))
      )
      wrongContent ~> route ~> check(status shouldEqual StatusCodes.UnsupportedMediaType)

      val oversized = "x" * (OAuthE2EFixtureControlProtocol.Limits.MaximumRequestBytes + 1)
      post("/oauth-e2e-fixture/v1/runs", oversized) ~> route ~> check(status shouldEqual StatusCodes.ContentTooLarge)
      handled should beEmpty
    }

    "reject a response exceeding 64 KiB before it reaches the wire" in new RouterScope {
      handlerResult = ControlResult(StatusCodes.OK, "x" * (OAuthE2EFixtureControlProtocol.Limits.MaximumResponseBytes + 1))

      post(s"/oauth-e2e-fixture/v1/runs/$runId/advance-300-seconds") ~> route ~> check {
        status shouldEqual StatusCodes.InternalServerError
        responseAs[String] shouldEqual "{\"error\":\"fixture-control-failed\"}"
      }
    }

    "reject every redirect response without forwarding a location or entity" in new RouterScope {
      val redirectSentinel = "https://attacker.invalid/oauth-e2e-fixture-secret"
      handlerResult = ControlResult(StatusCodes.TemporaryRedirect, s"{\"location\":\"$redirectSentinel\"}")

      post(s"/oauth-e2e-fixture/v1/runs/$runId/advance-300-seconds") ~> route ~> check {
        status shouldEqual StatusCodes.InternalServerError
        response.headers.exists(_.is("location")) should beFalse
        val rendered = response.toString + responseAs[String]
        rendered must not(contain(redirectSentinel))
      }
    }

    "pin command and cleanup deadlines at 10/8 and 120/115 seconds from admission" in new RouterScope {
      post(s"/oauth-e2e-fixture/v1/runs/$runId/advance-300-seconds") ~> route ~> check(status shouldEqual StatusCodes.OK)
      delete(s"/oauth-e2e-fixture/v1/runs/$runId") ~> route ~> check(status shouldEqual StatusCodes.OK)

      deadlines.map(deadline => (deadline.totalNanos, deadline.activeNanos)) shouldEqual Vector(
        (10000000000L, 8000000000L),
        (120000000000L, 115000000000L)
      )
      OAuthE2EFixtureControlProtocol.Limits.MaximumAcceptedCommands shouldEqual 32
    }

    "discard a late pre-persistence admission and stop the controller before opening the handler" in new RouterScope {
      val stopCount = ArrayBuffer.empty[Unit]
      val clock     = ArrayBuffer(0L, OAuthE2EFixtureControlProtocol.Limits.CommandActiveNanos)
      val lateRouter = new OAuthE2EFixtureControlRouter(
        controlTokenVerifier = tokenVerifier,
        failedAuthLimiter = failedAuthLimiter,
        handler = handler,
        nanoTime = () => clock.remove(0),
        supervisor = new OAuthE2EFixtureControllerSupervisor {
          override def stopControllerAndDestroyRun: IO[Unit] = IO { stopCount += (()); () }
        }
      )

      post(s"/oauth-e2e-fixture/v1/runs/$runId/advance-300-seconds") ~> Route.seal(lateRouter.route) ~> check {
        status shouldEqual StatusCodes.InternalServerError
      }
      handled should beEmpty
      stopCount must haveSize(1)
    }

    "bound and cancel chunked or stalled setup bodies inside the same active deadline" in new RouterScope {
      val stopCount = ArrayBuffer.empty[Unit]
      val boundedRouter = new OAuthE2EFixtureControlRouter(
        controlTokenVerifier = tokenVerifier,
        failedAuthLimiter = failedAuthLimiter,
        handler = handler,
        supervisor = new OAuthE2EFixtureControllerSupervisor {
          override def stopControllerAndDestroyRun: IO[Unit] = IO { stopCount += (()); () }
        },
        commandDeadline = admitted => ControlDeadline.testing(admitted, activeNanos = 50000000L, totalNanos = 100000000L)
      )
      val oversizedChunks = Source.single(HttpEntity.Chunk(ByteString("x" * (OAuthE2EFixtureControlProtocol.Limits.MaximumRequestBytes + 1))))
      val oversized = authorized(
        HttpRequest(
          HttpMethods.POST,
          uri = "/oauth-e2e-fixture/v1/runs",
          entity = HttpEntity.Chunked(ContentTypes.`application/json`, oversizedChunks)
        )
      )
      oversized ~> Route.seal(boundedRouter.route) ~> check(status shouldEqual StatusCodes.ContentTooLarge)

      val stalled = authorized(
        HttpRequest(
          HttpMethods.POST,
          uri = "/oauth-e2e-fixture/v1/runs",
          entity = HttpEntity.Chunked(ContentTypes.`application/json`, Source.maybe[HttpEntity.ChunkStreamPart])
        )
      )
      stalled ~> Route.seal(boundedRouter.route) ~> check(status shouldEqual StatusCodes.InternalServerError)
      handled should beEmpty
      stopCount must haveSize(1)
    }

    "invalidate a late pre-persistence handler result before it can open persistence" in new RouterScope {
      @volatile var openedPersistence = false
      val lateHandler = new OAuthE2EFixtureControlHandler {
        override def handle(
          command: ControlCommand,
          key: UUID,
          context: ControlRequestContext
        ): IO[ControlResult] =
          IO.uncancelable(_ => IO.sleep(scala.concurrent.duration.DurationInt(35).millis)) *>
            context.ensurePersistenceAllowed(IO.monotonic.map(_.toNanos)) *>
            IO { openedPersistence = true; handlerResult }
      }
      val lateRouter = new OAuthE2EFixtureControlRouter(
        tokenVerifier,
        failedAuthLimiter,
        lateHandler,
        commandDeadline = admitted => ControlDeadline.testing(admitted, activeNanos = 20000000L, totalNanos = 100000000L)
      )

      post(s"/oauth-e2e-fixture/v1/runs/$runId/advance-300-seconds") ~> Route.seal(lateRouter.route) ~> check {
        status shouldEqual StatusCodes.InternalServerError
      }
      openedPersistence should beFalse
    }

    "request fatal controller termination when pre-persistence work cannot settle inside the reserve" in new RouterScope {
      val stopCount = ArrayBuffer.empty[Unit]
      val release   = cats.effect.Deferred[IO, Unit].unsafeRunSync()
      val stuckHandler = new OAuthE2EFixtureControlHandler {
        override def handle(
          command: ControlCommand,
          key: UUID,
          context: ControlRequestContext
        ): IO[ControlResult] =
          IO.uncancelable(_ => release.get).as(handlerResult)
      }
      val fatalRouter = new OAuthE2EFixtureControlRouter(
        tokenVerifier,
        failedAuthLimiter,
        stuckHandler,
        supervisor = new OAuthE2EFixtureControllerSupervisor {
          override def stopControllerAndDestroyRun: IO[Unit] = IO { stopCount += (()); () } *> release.complete(()).void
        },
        commandDeadline = admitted => ControlDeadline.testing(admitted, activeNanos = 20000000L, totalNanos = 50000000L)
      )

      post(s"/oauth-e2e-fixture/v1/runs/$runId/advance-300-seconds") ~> Route.seal(fatalRouter.route) ~> check {
        status shouldEqual StatusCodes.InternalServerError
      }
      stopCount must haveSize(1)
    }
  }

  private abstract class RouterScope extends Scope {
    val runId: UUID                             = UUID.fromString("00000000-0000-4000-8000-00000000a522")
    val idempotencyKey                          = "00000000-0000-4000-8000-000000000004"
    val handled: ArrayBuffer[ControlCommand]    = ArrayBuffer.empty[ControlCommand]
    val deadlines: ArrayBuffer[ControlDeadline] = ArrayBuffer.empty[ControlDeadline]
    var handlerResult: ControlResult            = ControlResult(StatusCodes.OK, "{\"result\":\"ok\"}")

    val tokenVerifier     = new RecordingTokenVerifier(controlToken)
    val failedAuthLimiter = new OAuthE2EFixtureFailedAuthLimiter(() => 0L)
    val handler: OAuthE2EFixtureControlHandler = new OAuthE2EFixtureControlHandler {
      override def handle(
        command: ControlCommand,
        key: UUID,
        context: ControlRequestContext
      ): IO[ControlResult] = IO {
        key shouldEqual UUID.fromString(idempotencyKey)
        handled += command
        deadlines += context.deadline
        handlerResult
      }
    }
    val router = new OAuthE2EFixtureControlRouter(
      controlTokenVerifier = tokenVerifier,
      failedAuthLimiter = failedAuthLimiter,
      handler = handler,
      nanoTime = () => 1000L
    )
    val route: Route = Route.seal(router.route)

    def post(
      path: String,
      body: String = "",
      token: String = controlToken,
      authorize: Boolean = true
    ): HttpRequest = {
      val entity  = if (body.isEmpty) HttpEntity.Empty else HttpEntity(ContentTypes.`application/json`, body)
      val request = HttpRequest(HttpMethods.POST, uri = Uri(path), entity = entity)
      if (authorize) authorized(request, token) else request.addHeader(RawHeader("Idempotency-Key", idempotencyKey))
    }

    def get(path: String, body: String = ""): HttpRequest = {
      val entity = if (body.isEmpty) HttpEntity.Empty else HttpEntity(ContentTypes.`application/json`, body)
      authorized(HttpRequest(HttpMethods.GET, uri = Uri(path), entity = entity))
    }

    def delete(path: String): HttpRequest = authorized(HttpRequest(HttpMethods.DELETE, uri = Uri(path)))

    def authorized(request: HttpRequest, token: String = controlToken): HttpRequest =
      request.withHeaders(request.headers ++ List(Authorization(OAuth2BearerToken(token)), RawHeader("Idempotency-Key", idempotencyKey)))

    def authorizedOnly(request: HttpRequest, token: String = controlToken): HttpRequest =
      request.withHeaders(request.headers :+ Authorization(OAuth2BearerToken(token)))
  }

  private final class RecordingTokenVerifier(token: String) extends OAuthE2EFixtureControlTokenVerifier(token) {
    val comparedInputs: ArrayBuffer[String] = ArrayBuffer.empty[String]
    override private[fixture] def comparePresented(presented: String): Boolean = {
      comparedInputs += presented
      super.comparePresented(presented)
    }
  }

  private val controlToken: String = {
    val bytes = Array.tabulate[Byte](32)(index => (index + 64).toByte)
    "oauth-e2e-v1." + Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)
  }

  private def versionedToken(start: Int): String = {
    val bytes = Array.tabulate[Byte](32)(index => (index + start).toByte)
    "oauth-e2e-v1." + Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)
  }

  private def setupBody(runId: UUID): String = s"{\"runId\":\"$runId\"}"
}
