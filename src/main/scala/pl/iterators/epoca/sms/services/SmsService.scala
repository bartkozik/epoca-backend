package pl.iterators.epoca.sms.services

import cats.effect.IO
import pl.iterators.epoca.sms.domain.SmsDomain.{PhoneNumber, SmsText}
import pl.iterators.epoca.sms.services.SmsService.SmsSendResult

import scala.annotation.unused

class SmsService {
  // Delivery is intentionally unavailable. Never log authentication codes or recipient phone numbers.
  def send(@unused number: PhoneNumber, @unused text: SmsText): IO[SmsSendResult] =
    IO.pure(SmsSendResult.Error)
}

object SmsService {
  trait SmsSendResult
  object SmsSendResult {
    case object Sent  extends SmsSendResult
    case object Error extends SmsSendResult
  }
}
