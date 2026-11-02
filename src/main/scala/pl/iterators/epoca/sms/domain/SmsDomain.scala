package pl.iterators.epoca.sms.domain

import pl.iterators.kebs.tag.meta.tagged
import pl.iterators.kebs.tagged._

@tagged trait Domain {
  trait PhoneNumberTag
  type PhoneNumber = String @@ PhoneNumberTag

  trait SmsTextTag
  type SmsText = String @@ SmsTextTag
}

object SmsDomain extends Domain {}
