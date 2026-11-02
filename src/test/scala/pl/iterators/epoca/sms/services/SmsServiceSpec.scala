package pl.iterators.epoca.sms.services

import pl.iterators.epoca.sms.domain.SmsDomain.{PhoneNumber, SmsText}
import pl.iterators.epoca.utils.ServiceSpec

class SmsServiceSpec extends ServiceSpec {
  "SMS stub" should {
    "report unavailable delivery instead of claiming a login code was sent" in new BaseScope {
      new SmsService()
        .send(PhoneNumber("+48123456789"), SmsText("secret-code"))
        .asserting(_ mustEqual SmsService.SmsSendResult.Error)
    }
  }
}
