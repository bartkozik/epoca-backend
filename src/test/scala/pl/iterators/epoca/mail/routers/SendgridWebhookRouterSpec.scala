package pl.iterators.epoca.mail.routers

import cats.effect.unsafe.IORuntime
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.model.{HttpRequest, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Route
import org.mockito.Mockito.verifyNoInteractions
import pl.iterators.epoca.mail.services.{SendgridMailEventCreateService, SendgridWebhookVerifier}
import pl.iterators.epoca.utils.RouteSpec
import pl.iterators.epoca.utils.log.TracingContext

import java.nio.charset.StandardCharsets.UTF_8
import java.security.spec.ECGenParameterSpec
import java.security.{KeyPairGenerator, Signature}
import java.time.{Clock, Instant, ZoneOffset}
import java.util.Base64
import scala.concurrent.duration._

class SendgridWebhookRouterSpec extends RouteSpec {
  private implicit val runtime: IORuntime             = IORuntime.global
  private implicit val tracingContext: TracingContext = TracingContext("test", "signed-webhook", Map.empty)

  private val generator = KeyPairGenerator.getInstance("EC")
  generator.initialize(new ECGenParameterSpec("secp256r1"))
  private val key             = generator.generateKeyPair()
  private val publicKey       = Base64.getEncoder.encodeToString(key.getPublic.getEncoded)
  private val now             = Instant.parse("2026-10-08T12:00:00Z")
  private val fixedClock      = Clock.fixed(now, ZoneOffset.UTC)
  private val timestamp       = now.getEpochSecond.toString
  private val verifier        = new SendgridWebhookVerifier(SendgridWebhookVerifier.Config(publicKey), fixedClock)
  private val signatureHeader = "X-Twilio-Email-Event-Webhook-Signature"
  private val timestampHeader = "X-Twilio-Email-Event-Webhook-Timestamp"

  private def signature(ts: String, body: String): String = {
    val signer = Signature.getInstance("SHA256withECDSA")
    signer.initSign(key.getPrivate)
    signer.update(ts.getBytes(UTF_8))
    signer.update(body.getBytes(UTF_8))
    Base64.getEncoder.encodeToString(signer.sign())
  }

  private def signed(body: String, ts: String = timestamp): HttpRequest =
    Post("/sendgrid", requestJson(body)).withHeaders(RawHeader(signatureHeader, signature(ts, body)), RawHeader(timestampHeader, ts))

  "SendGrid webhook" should {
    "verify raw bytes before parsing and persist only authenticated events" in {
      val service = mock[SendgridMailEventCreateService]
      when(service.create(any)).thenReturn(io(Seq.empty))
      val route = Route.seal(new SendgridWebhookRouter(service, verifier).routes)
      val body =
        "[ {\"mailUuid\":\"11111111-2222-4333-8444-555555555555\",\"environment\":\"test\",\"email\":\"ż@example.test\",\"event\":\"delivered\"} ]\r\n"
      signed(body) ~> route ~> check(status mustEqual StatusCodes.OK)
      val events = org.mockito.ArgumentCaptor.forClass(classOf[Seq[pl.iterators.epoca.mail.domain.MailDomain.SendgridMailEvent]])
      verify(service).create(events.capture())
      events.getValue.head.email mustEqual "ż@example.test"
      events.getValue.head.event must beSome("delivered")
    }
    "reject absent invalid duplicated or modified signatures before parsing or persistence" in {
      val service = mock[SendgridMailEventCreateService]
      val route   = Route.seal(new SendgridWebhookRouter(service, verifier).routes)
      Post("/sendgrid", requestJson("not-json")) ~> route ~> check(status mustEqual StatusCodes.Unauthorized)
      signed("not-json").withHeaders(RawHeader(signatureHeader, "bad"), RawHeader(timestampHeader, timestamp)) ~>
        route ~> check(status mustEqual StatusCodes.Unauthorized)
      signed("[]").withEntity(requestJson("[ ]")) ~> route ~> check(status mustEqual StatusCodes.Unauthorized)
      val duplicate = signed("[]")
      duplicate.withHeaders(duplicate.headers :+ RawHeader(signatureHeader, signature(timestamp, "[]"))) ~>
        route ~> check(status mustEqual StatusCodes.Unauthorized)
      val stale = (now.getEpochSecond - 301).toString
      signed("[]", stale) ~> route ~> check(status mustEqual StatusCodes.Unauthorized)
      verifyNoInteractions(service)
      success
    }
    "reject malformed JSON after a valid signature without persisting events" in {
      val service = mock[SendgridMailEventCreateService]
      val route   = Route.seal(new SendgridWebhookRouter(service, verifier).routes)
      signed("not-json") ~> route ~> check(status mustEqual StatusCodes.BadRequest)
      verifyNoInteractions(service)
      success
    }
    "bound request bytes before signature verification and persistence" in {
      val service = mock[SendgridMailEventCreateService]
      val small   = new SendgridWebhookVerifier(SendgridWebhookVerifier.Config(publicKey, 5.minutes, 30.seconds, 16), fixedClock)
      val route   = Route.seal(new SendgridWebhookRouter(service, small).routes)
      signed("[" + " " * 32 + "]") ~> route ~> check(status mustEqual StatusCodes.ContentTooLarge)
      verifyNoInteractions(service)
      success
    }
    "fail closed before persistence when the verification key is unavailable" in {
      val service = mock[SendgridMailEventCreateService]
      when(service.create(any)).thenReturn(io(Seq.empty))
      val route = Route.seal(new SendgridWebhookRouter(service).routes)
      Post("/sendgrid", requestJson("[]")) ~> route ~> check(status mustEqual StatusCodes.ServiceUnavailable)
      verifyNoInteractions(service)
      success
    }
  }
}
