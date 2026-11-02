package pl.iterators.epoca.auth.services

import cats.effect.IO
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.auth.services.AuthenticateService.AuthenticateTokenResult

trait AuthenticateService {
  def authenticateToken(jwt: String): IO[AuthenticateTokenResult]
}

object AuthenticateService {
  sealed trait AuthenticateTokenResult
  object AuthenticateTokenResult {
    case class Ok(authContext: AuthContext) extends AuthenticateTokenResult
    case object JwtExpired                  extends AuthenticateTokenResult
    case object InvalidJwt                  extends AuthenticateTokenResult
  }
}
