package pl.iterators.epoca.auth.services

import cats.effect.IO
import com.typesafe.config.{Config => TsConfig}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.auth.domain.AuthDomain.TimeBasedOneTimePasswordSecret
import pl.iterators.epoca.auth.services.TimeBasedOneTimePasswordSecretGetService._
import pl.iterators.epoca.utils.base32.Base32

import scala.util.Random

class TimeBasedOneTimePasswordSecretGetService(config: TimeBasedOneTimePasswordSecretGetService.Config) {

  def get(authContext: AuthContext): IO[TimeBasedOneTimePasswordSecretGetResult] = IO.delay {
    val secret = TimeBasedOneTimePasswordSecret(Base32.encode(Random.alphanumeric.take(config.secretLength).mkString))
    val qr = config.qrFormat
      .replace("{secret}", secret)
      .replace(
        "{id}",
        authContext.mailAddresses.headOption
          .orElse(authContext.phones.headOption)
          .getOrElse(authContext.id.toString)
      )

    TimeBasedOneTimePasswordSecretGetResult.Ok(TimeBasedOneTimePasswordSecretGetResultData(secret, qr))
  }

}

object TimeBasedOneTimePasswordSecretGetService {
  case class Config(secretLength: Int, qrFormat: String)
  object Config {
    def apply(tsConfig: TsConfig): Config = {
      val c = tsConfig.getConfig("user.totp")
      Config(secretLength = c.getInt("secretLength"), qrFormat = c.getString("qrFormat"))
    }
  }

  final case class TimeBasedOneTimePasswordSecretGetResultData(secret: TimeBasedOneTimePasswordSecret, qr: String)

  sealed trait TimeBasedOneTimePasswordSecretGetResult
  object TimeBasedOneTimePasswordSecretGetResult {
    final case class Ok(data: TimeBasedOneTimePasswordSecretGetResultData) extends TimeBasedOneTimePasswordSecretGetResult

  }

}
