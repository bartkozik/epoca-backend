package pl.iterators.epoca.sms.smses

import cats.effect.IO
import com.typesafe.config.{Config => TsConfig}
import pl.iterators.epoca.sms.domain.SmsDomain._
import pl.iterators.epoca.sms.services.SmsService
import pl.iterators.epoca.sms.services.SmsService.SmsSendResult
import pl.iterators.epoca.sms.smses.UserTokenSms.{Config, Event}

class UserTokenSms(config: Config, smsService: SmsService) {
  def send(event: Event): IO[SmsSendResult] =
    smsService.send(event.number, SmsText(config.text.replace("{token}", event.token)))
}

object UserTokenSms {
  case class Config(text: String)

  object Config {
    def apply(tsConfig: TsConfig): Config = Config(tsConfig.getString("smses.userTokenSms"))
  }

  case class Event(number: PhoneNumber, token: String)
}
