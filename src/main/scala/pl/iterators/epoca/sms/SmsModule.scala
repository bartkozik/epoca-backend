package pl.iterators.epoca.sms

import com.softwaremill.macwire.wire
import com.typesafe.config.Config
import pl.iterators.epoca.sms.services.SmsService
import pl.iterators.epoca.sms.smses.UserTokenSms

trait SmsModule {
  val config: Config

  lazy val smsService: SmsService = wire[SmsService]

  private lazy val userTokenSmsConfig = UserTokenSms.Config(config)
  lazy val userTokenSms: UserTokenSms = wire[UserTokenSms]
}
