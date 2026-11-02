package pl.iterators.epoca.mail.emails

import cats.effect.IO
import io.circe._
import pl.iterators.epoca.mail.domain.MailDomain.{MailAddress, MailType}
import pl.iterators.epoca.mail.services.MailerService
import pl.iterators.epoca.mail.utils.{CommonMappingPreparer, GenericEmailSender}

class UserIdentityConfirmationCodeMail(mailer: MailerService, commonMappingPreparer: CommonMappingPreparer)
    extends GenericEmailSender[UserIdentityConfirmationCodeMail.Event](mailer, MailType.UserIdentityConfirmationCode) {

  protected def mapper: UserIdentityConfirmationCodeMail.Event => IO[JsonObject] =
    event =>
      IO.pure {
        commonMappingPreparer.prepare().deepMerge(JsonObject("confirmationCode" -> Json.fromString(event.confirmationCode)))
      }
  protected def emailGetter: UserIdentityConfirmationCodeMail.Event => MailAddress = _.email
}

object UserIdentityConfirmationCodeMail {
  case class Event(email: MailAddress, confirmationCode: String)
}
