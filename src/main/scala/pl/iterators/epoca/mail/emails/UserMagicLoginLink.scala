package pl.iterators.epoca.mail.emails

import cats.effect.IO
import io.circe._
import pl.iterators.epoca.mail.domain.MailDomain.{MailAddress, MailType}
import pl.iterators.epoca.mail.services.MailerService
import pl.iterators.epoca.mail.utils.{CommonMappingPreparer, GenericEmailSender}

class UserMagicLoginLink(mailer: MailerService, commonMappingPreparer: CommonMappingPreparer)
    extends GenericEmailSender[UserMagicLoginLink.Event](mailer, MailType.UserMagicLoginLink) {

  protected def mapper: UserMagicLoginLink.Event => IO[JsonObject] =
    event =>
      IO.pure {
        commonMappingPreparer.prepare().deepMerge(JsonObject("loginLink" -> Json.fromString(event.loginLink)))
      }
  protected def emailGetter: UserMagicLoginLink.Event => MailAddress = _.email
}

object UserMagicLoginLink {
  case class Event(email: MailAddress, loginLink: String)
}
