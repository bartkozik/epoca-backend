package pl.iterators.epoca.auth.services

import com.typesafe.config.ConfigFactory
import io.circe.Json
import pl.iterators.epoca.auth.services.JwtService.DecodingResult
import pl.iterators.epoca.utils.ServiceSpec

import java.time.ZonedDateTime

class JwtServiceSpec extends ServiceSpec {
  private val firstSecret  = "first-secret"
  private val secondSecret = "second-secret"
  private val issuedAt     = ZonedDateTime.parse("2026-01-02T03:04:05Z")
  private val futureExpiry = ZonedDateTime.parse("2099-01-02T03:04:05Z")
  private val pastExpiry   = ZonedDateTime.parse("2000-01-02T03:04:05Z")
  private val payload =
    Json.obj("subject" -> Json.fromString("ct-526-user"), "roles" -> Json.arr(Json.fromString("user"), Json.fromString("reviewer")))

  "JwtService" should {
    "encode and decode a future-expiry JSON payload without changing it" in {
      val sut   = new JwtService(JwtService.Config(firstSecret))
      val token = sut.encode(payload, issuedAt, Some(futureExpiry))

      sut.decode[Json](token) shouldEqual DecodingResult.Decoded(payload)
    }

    "report an expired token" in {
      val sut   = new JwtService(JwtService.Config(firstSecret))
      val token = sut.encode(payload, issuedAt, Some(pastExpiry))

      sut.decode[Json](token) must beLike { case DecodingResult.Expired(_) => ok }
    }

    "report a malformed token as invalid" in {
      val sut = new JwtService(JwtService.Config(firstSecret))

      sut.decode[Json]("not-a-jwt") must beLike { case DecodingResult.InvalidToken(_) => ok }
    }

    "report a token signed with another secret as invalid" in {
      val sut         = new JwtService(JwtService.Config(firstSecret))
      val otherSigner = new JwtService(JwtService.Config(secondSecret))
      val token       = otherSigner.encode(payload, issuedAt, Some(futureExpiry))

      sut.decode[Json](token) must beLike { case DecodingResult.InvalidToken(_) => ok }
    }
  }

  "JwtService.Config" should {
    def load(settings: String) = JwtService.Config(ConfigFactory.parseString(settings))

    "read a configured signing secret" in {
      load("login.jwtSecret = \"a-randomly-generated-signing-secret\"") shouldEqual JwtService.Config("a-randomly-generated-signing-secret")
    }
    "refuse to start without a signing secret" in {
      load("") must throwA[IllegalStateException]
    }
    "refuse to start with a blank or placeholder signing secret" in {
      load("login.jwtSecret = \"  \"") must throwA[IllegalStateException]
      load("login.jwtSecret = \"YouNeedToOverrideIt\"") must throwA[IllegalStateException]
      load("login.jwtSecret = \"secret\"") must throwA[IllegalStateException]
    }
  }
}
