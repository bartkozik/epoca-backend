package pl.iterators.epoca.mail.services

import cats.effect.IO
import io.circe.JsonObject
import pl.iterators.epoca.mail.domain.MailDomain.{MailAddress, MailType}
import pl.iterators.epoca.mail.services.MailerService.{Attachment, From, MailSendResult}

private[mail] trait MailerService {
  def send(
    toEmail: MailAddress,
    mailType: MailType,
    replacements: JsonObject,
    attachments: List[Attachment],
    fromOverride: Option[From]
  ): IO[MailSendResult]
}

private[mail] object MailerService {

  trait MailSendResult
  object MailSendResult {
    case object Sent             extends MailSendResult
    case object Skipped          extends MailSendResult
    case object TemplateNotFound extends MailSendResult
    case object Error            extends MailSendResult
  }

  case class From(email: String, name: String)

  case class Attachment(
    filename: String,
    content: String,
    `type`: String)

}
