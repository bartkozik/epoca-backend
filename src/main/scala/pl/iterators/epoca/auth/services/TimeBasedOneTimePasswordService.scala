package pl.iterators.epoca.auth.services

import cats.effect.{IO, Sync}
import com.typesafe.config.{Config => TsConfig}
import pl.iterators.epoca.auth.domain.AuthDomain.{TimeBasedOneTimePasswordSecret, TimeBasedOneTimePasswordValue}
import pl.iterators.epoca.utils.base32.Base32
import pl.iterators.epoca.utils.db.DBIOInstances._
import slick.dbio.DBIO

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import scala.concurrent.ExecutionContext

class TimeBasedOneTimePasswordService(config: TimeBasedOneTimePasswordService.Config)(implicit ec: ExecutionContext) {

  def generateIO(secretKey: TimeBasedOneTimePasswordSecret): IO[TimeBasedOneTimePasswordValue] =
    Sync[IO].delay(generate(secretKey))

  def generateDBIO(secretKey: TimeBasedOneTimePasswordSecret): DBIO[TimeBasedOneTimePasswordValue] =
    Sync[DBIO].delay(generate(secretKey))

  /*
   * Reference: https://datatracker.ietf.org/doc/html/rfc4226
   */
  private def generate(secretKey: TimeBasedOneTimePasswordSecret): TimeBasedOneTimePasswordValue = {
    val decodedKey        = Base32.decode(secretKey).getBytes
    val timeIntervalBytes = new Array[Byte](8)
    var tInterval         = System.currentTimeMillis / 1000 / config.timeStepInSeconds
    for (i <- 7 to 0 by -1) {
      timeIntervalBytes(i) = (tInterval & 0xff).toByte
      tInterval >>= 8
    }
    val hmac = Mac.getInstance(config.algorithm)
    hmac.init(new SecretKeySpec(decodedKey, config.algorithm))
    val hash = hmac.doFinal(timeIntervalBytes)

    val offset                    = hash(hash.length - 1) & 0xf
    val mostSignificantByte       = (hash(offset) & 0x7f) << 24
    val secondMostSignificantByte = (hash(offset + 1) & 0xff) << 16
    val thirdMostSignificantByte  = (hash(offset + 2) & 0xff) << 8
    val leastSignificantByte      = hash(offset + 3) & 0xff
    val binaryCode                = mostSignificantByte | secondMostSignificantByte | thirdMostSignificantByte | leastSignificantByte
    val totp                      = (binaryCode % Math.pow(10d, config.passwordLength.toDouble)).toInt
    TimeBasedOneTimePasswordValue(String.format("%0" + config.passwordLength + "d", totp))
  }

}

object TimeBasedOneTimePasswordService {
  case class Config(
    algorithm: String,
    timeStepInSeconds: Int,
    passwordLength: Int)

  object Config {
    def apply(tsConfig: TsConfig): Config = {
      val c = tsConfig.getConfig("user.totp")
      Config(algorithm = c.getString("algorithm"), timeStepInSeconds = c.getInt("timeStepInSeconds"), passwordLength = c.getInt("passwordLength"))
    }
  }

}
