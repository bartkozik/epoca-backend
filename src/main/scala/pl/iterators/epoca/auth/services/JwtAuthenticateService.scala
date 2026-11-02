package pl.iterators.epoca.auth.services

import cats.effect.IO
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.auth.services.AuthenticateService.AuthenticateTokenResult
import pl.iterators.epoca.auth.services.JwtService.DecodingResult
import pl.iterators.epoca.user.domain.UserDomain.IdentityType
import pl.iterators.epoca.utils.json.JsonProtocol

import java.time.{Duration, Instant}

class JwtAuthenticateService(jwtService: JwtService) extends AuthenticateService with JsonProtocol {

  override def authenticateToken(jwt: String): IO[AuthenticateService.AuthenticateTokenResult] =
    IO.delay {
      jwtService.decode[AuthContext](jwt) match {
        case DecodingResult.Decoded(context) if impersonationExpired(context) => AuthenticateTokenResult.JwtExpired
        case DecodingResult.Decoded(context)                                  => AuthenticateTokenResult.Ok(context)
        case DecodingResult.Expired(_)                                        => AuthenticateTokenResult.JwtExpired
        case DecodingResult.InvalidToken(_)                                   => AuthenticateTokenResult.InvalidJwt
        case DecodingResult.ParsingFailure(_)                                 => AuthenticateTokenResult.InvalidJwt
        case DecodingResult.DecodingFailure(_)                                => AuthenticateTokenResult.InvalidJwt
      }
    }
  private def impersonationExpired(context: AuthContext): Boolean =
    context.authedBy.exists(_.`type` == IdentityType.AdminImpersonate) && {
      val age = Duration.between(context.lastAuthedAt, Instant.now())
      age.isNegative || age.compareTo(LoginResolveService.MaxImpersonationDuration) >= 0
    }
}
