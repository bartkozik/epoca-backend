package pl.iterators.epoca.mail.emails

import com.softwaremill.macwire.wire
import pl.iterators.epoca.mail.EmailSpec

class UserMagicLoginLinkSpec extends EmailSpec[UserMagicLoginLink.Event] {

  override val sut: UserMagicLoginLink = wire[UserMagicLoginLink]

  override val event: UserMagicLoginLink.Event = UserMagicLoginLink.Event(getValidatedEmail, allGenerators[String].normal.generate)

}
