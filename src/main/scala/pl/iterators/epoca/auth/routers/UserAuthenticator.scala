package pl.iterators.epoca.auth.routers

import cats.effect.unsafe.IORuntime
import org.apache.pekko.http.scaladsl.model.headers.{HttpChallenge, HttpCredentials}
import org.apache.pekko.http.scaladsl.server.directives.SecurityDirectives.AuthenticationResult
import org.apache.pekko.http.scaladsl.server.directives.{AuthenticationResult, SecurityDirectives}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.auth.services.AuthenticateService
import pl.iterators.epoca.auth.services.AuthenticateService.AuthenticateTokenResult
import pl.iterators.epoca.utils.log.LoggingSupport

import scala.concurrent.Future

trait UserAuthenticator extends (Option[HttpCredentials] => Future[AuthenticationResult[AuthContext]])

class AuthServiceUserAuthenticator(authService: AuthenticateService)(implicit runtime: IORuntime) extends UserAuthenticator with LoggingSupport {
  override def apply(credentialsOpt: Option[HttpCredentials]): Future[AuthenticationResult[AuthContext]] =
    credentialsOpt match {
      case Some(credentials) if credentials.scheme() == "Bearer" =>
        authService
          .authenticateToken(credentials.token())
          .map {
            case AuthenticateTokenResult.Ok(auth) => AuthenticationResult.success(auth)
            case _                                => Challenge
          }
          .recover { case _ =>
            loggingAdapter.warning("OAuth-compatible bearer authentication failed because required state was unavailable")
            Challenge
          }
          .unsafeToFuture()
      case _ => ChallengeFuture
    }

  private val Challenge: SecurityDirectives.AuthenticationResult[Nothing] =
    AuthenticationResult.failWithChallenge(HttpChallenge(scheme = "Bearer", realm = "epoca-backend"))

  private val ChallengeFuture: Future[SecurityDirectives.AuthenticationResult[Nothing]] =
    Future.successful(Challenge)
}
