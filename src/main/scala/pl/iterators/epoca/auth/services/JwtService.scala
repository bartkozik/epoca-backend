package pl.iterators.epoca.auth.services

import com.typesafe.config.{Config => TsConfig}
import io.circe._
import pdi.jwt.exceptions.JwtExpirationException
import pdi.jwt.{Jwt, JwtAlgorithm, JwtClaim}
import pl.iterators.epoca.auth.services.JwtService.DecodingResult
import pl.iterators.epoca.utils.json.JsonProtocol

import java.time.ZonedDateTime
import java.util.Locale
import scala.util.{Failure, Success}

class JwtService(config: JwtService.Config) extends JsonProtocol {
  private val algorithm = JwtAlgorithm.HS256

  def decode[T](jwt: String)(implicit decoder: Decoder[T]): DecodingResult[T] =
    Jwt.decode(jwt, config.secret, Seq(algorithm)) match {
      case Success(payload) =>
        parser.parse(payload.content) match {
          case Right(jsObject) =>
            jsObject.as[T] match {
              case Right(data) => DecodingResult.Decoded(data)
              case Left(t)     => DecodingResult.DecodingFailure(t)
            }
          case Left(t) => DecodingResult.ParsingFailure(t)
        }
      case Failure(e: JwtExpirationException) => DecodingResult.Expired(e)
      case Failure(t)                         => DecodingResult.InvalidToken(t)
    }

  def encode(
    payload: Json,
    issuedAt: ZonedDateTime,
    expiresAt: Option[ZonedDateTime]
  ): String =
    Jwt.encode(
      JwtClaim(content = payload.toString(), expiration = expiresAt.map(_.toEpochSecond), issuedAt = Some(issuedAt.toEpochSecond)),
      config.secret,
      algorithm
    )
}

object JwtService {

  case class Config(secret: String)

  object Config {
    private val SecretPath = "login.jwtSecret"
    // The former application.conf default and common placeholder values; a deployment must never sign tokens with them.
    private val KnownPlaceholders = Set("youneedtooverrideit", "changeme", "change-me", "placeholder", "secret")

    /** Reads the human-session signing secret and fails when it is missing, blank or a known placeholder, so that a misconfigured deployment refuses
      * to start instead of issuing tokens signed with a public value.
      */
    def apply(tsConfig: TsConfig): Config = {
      if (!tsConfig.hasPath(SecretPath))
        throw new IllegalStateException(s"$SecretPath is not configured; set JWT_SECRET")
      val secret = tsConfig.getString(SecretPath)
      if (secret.trim.isEmpty || KnownPlaceholders.contains(secret.trim.toLowerCase(Locale.ROOT)))
        throw new IllegalStateException(s"$SecretPath is blank or a known placeholder; set JWT_SECRET to a random value")
      Config(secret = secret)
    }
  }

  sealed trait DecodingResult[T]
  object DecodingResult {
    case class Decoded[T](data: T)                             extends DecodingResult[T]
    case class DecodingFailure[T](t: io.circe.DecodingFailure) extends DecodingResult[T]
    case class ParsingFailure[T](t: io.circe.ParsingFailure)   extends DecodingResult[T]
    case class InvalidToken[T](t: Throwable)                   extends DecodingResult[T]
    case class Expired[T](e: JwtExpirationException)           extends DecodingResult[T]
  }

}
