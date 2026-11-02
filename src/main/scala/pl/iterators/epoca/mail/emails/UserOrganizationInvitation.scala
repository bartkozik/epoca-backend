package pl.iterators.epoca.mail.emails

import cats.effect.IO
import io.circe.{Json, JsonObject}
import pl.iterators.epoca.mail.domain.InvitationMailResult
import pl.iterators.epoca.mail.domain.MailDomain.{MailAddress, MailType}
import pl.iterators.epoca.mail.services.MailerService.MailSendResult
import pl.iterators.epoca.mail.services.{InvitationMailSender, MailerService, SendgridMailer}

import java.time.Instant

class UserOrganizationInvitation(mailer: MailerService, config: SendgridMailer.Config) extends InvitationMailSender {
  override def send(event: UserOrganizationInvitation.Event): IO[InvitationMailResult] = IO.defer {
    val locale = if (event.locale == "pl") "pl" else "en"
    val kind   = if (locale == "pl") MailType.UserOrganizationInvitationPl else MailType.UserOrganizationInvitation
    val link   = config.appUrl.stripSuffix("/") + "/invitations/accept#token=" + event.secret
    val data = JsonObject(
      "organizationName" -> Json.fromString(event.organizationName),
      "invitationLink"   -> Json.fromString(link),
      "expiresAt"        -> Json.fromString(event.expiresAt.toString),
      "locale"           -> Json.fromString(locale)
    )
    mailer.send(event.email, kind, data, Nil, None).map {
      case MailSendResult.Sent             => InvitationMailResult.Sent
      case MailSendResult.Skipped          => InvitationMailResult.Skipped
      case MailSendResult.TemplateNotFound => InvitationMailResult.TemplateNotFound
      case MailSendResult.Error            => InvitationMailResult.Error
    }
  }
}
object UserOrganizationInvitation {
  final case class Event(
    email: MailAddress,
    organizationName: String,
    secret: String,
    expiresAt: Instant,
    locale: String) {
    override def toString: String = "UserOrganizationInvitation.Event(<redacted>)"
  }
}
