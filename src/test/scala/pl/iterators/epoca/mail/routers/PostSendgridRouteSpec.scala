package pl.iterators.epoca.mail.routers

import com.typesafe.config.{Config, ConfigValueFactory}
import org.apache.pekko.http.scaladsl.model.HttpRequest
import org.apache.pekko.http.scaladsl.model.StatusCodes._
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import pl.iterators.baklava.core.model.RouteRepresentation
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.mail.domain.MailDomain.SendgridMailEvent
import pl.iterators.epoca.mail.services.SendgridMailEventCreateService
import pl.iterators.epoca.utils.RouteDocSpec

import java.nio.charset.StandardCharsets.UTF_8
import java.security.spec.ECGenParameterSpec
import java.security.{KeyPair, KeyPairGenerator, Signature}
import java.time.Instant
import java.util.Base64

class PostSendgridRouteSpec extends RouteDocSpec {
  private lazy val signingKey: KeyPair = {
    val generator = KeyPairGenerator.getInstance("EC")
    generator.initialize(new ECGenParameterSpec("secp256r1"))
    generator.generateKeyPair()
  }

  override def testConfig: Config = super.testConfig
    .withValue("mail.sendgrid.webhook.publicKey", ConfigValueFactory.fromAnyRef(Base64.getEncoder.encodeToString(signingKey.getPublic.getEncoded)))

  override val routeRepresentation: RouteRepresentation[Seq[SendgridMailEvent], Unit] =
    RouteRepresentation[Seq[SendgridMailEvent], Unit]("Saves signature-verified SendGrid history data", "POST", "/sendgrid")

  private def signed(body: String): HttpRequest = {
    val timestamp = Instant.now().getEpochSecond.toString
    val signer    = Signature.getInstance("SHA256withECDSA")
    signer.initSign(signingKey.getPrivate)
    signer.update(timestamp.getBytes(UTF_8))
    signer.update(body.getBytes(UTF_8))
    Post(routeRepresentation.path, requestJson(body)).withHeaders(
      RawHeader("X-Twilio-Email-Event-Webhook-Signature", Base64.getEncoder.encodeToString(signer.sign())),
      RawHeader("X-Twilio-Email-Event-Webhook-Timestamp", timestamp)
    )
  }

  trait NoLoggedTestCase extends BaseScope {
    override val authContextForTest: Option[AuthContext]                             = None
    override lazy val sendgridMailEventCreateService: SendgridMailEventCreateService = vmock[SendgridMailEventCreateService]
  }

  routeRepresentation.name should {
    "return BadRequest for a signed JSON object instead of an event array" in new NoLoggedTestCase {
      signed(emptyJson) ~> allRoutes ~> check {
        response.status shouldEqual BadRequest
      }
    }
    "return OK for a signed event array and call the service" in new NoLoggedTestCase {
      val requestObj = routeRepresentation.request.normal
      when(sendgridMailEventCreateService.create(requestObj.value)).thenReturn(io(requestObj.value))
      signed(requestObj.getJsonString) ~> allRoutes ~> check {
        response.status shouldEqual OK
        verify(sendgridMailEventCreateService).create(requestObj.value)
      }
    }
    "reject unsigned events before calling the service" in new NoLoggedTestCase {
      Post(routeRepresentation.path, requestJson("[]")) ~> allRoutes ~> check {
        response.status shouldEqual Unauthorized
      }
    }
    "reject a changed body before calling the service" in new NoLoggedTestCase {
      signed("[]").withEntity(requestJson("[ ]")) ~> allRoutes ~> check {
        response.status shouldEqual Unauthorized
      }
    }
  }
}
