package pl.iterators.epoca.mail.routers

import cats.effect.unsafe.IORuntime
import io.circe.parser.decode
import org.apache.pekko.http.scaladsl.model.StatusCodes._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.util.ByteString
import pl.iterators.epoca.mail.domain.MailDomain.SendgridMailEvent
import pl.iterators.epoca.mail.services.{SendgridMailEventCreateService, SendgridWebhookVerifier}
import pl.iterators.epoca.utils.http.BaseRouter
import pl.iterators.epoca.utils.log.TracingContext

class SendgridWebhookRouter(
  sendgridMailEventCreateService: SendgridMailEventCreateService,
  verifier: SendgridWebhookVerifier = new SendgridWebhookVerifier(SendgridWebhookVerifier.Config())
)(implicit runtime: IORuntime)
    extends BaseRouter {
  def routes(implicit tracingContext: TracingContext): Route =
    (post & path("sendgrid") & pathEndOrSingleSlash) {
      if (!verifier.available) complete(ServiceUnavailable)
      else
        extractRequest { request =>
          val signatures = request.headers.filter(_.is("x-twilio-email-event-webhook-signature")).map(_.value())
          val timestamps = request.headers.filter(_.is("x-twilio-email-event-webhook-timestamp")).map(_.value())
          if (signatures.size != 1 || timestamps.size != 1) complete(Unauthorized)
          else
            withSizeLimit(verifier.maxBodyBytes) {
              entity(as[ByteString]) { body =>
                if (!verifier.verify(signatures.head, timestamps.head, body.toArray)) complete(Unauthorized)
                else
                  decode[Seq[SendgridMailEvent]](body.utf8String) match {
                    case Left(_) => complete(BadRequest)
                    case Right(events) =>
                      complete(sendgridMailEventCreateService.create(events))(_ => OK)
                  }
              }
            }
        }
    }
}
