package pl.iterators.epoca.oauth.routers

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import org.apache.pekko.http.scaladsl.model.headers.{HttpChallenge, HttpCredentials}
import org.apache.pekko.http.scaladsl.server.directives.SecurityDirectives.AuthenticationResult
import org.apache.pekko.http.scaladsl.server.directives.{AuthenticationResult, SecurityDirectives}
import pl.iterators.epoca.oauth.domain.OAuthAuthContext
import pl.iterators.epoca.oauth.domain.OAuthDomain.{OAuthClientEffectiveStatus, OAuthTokenClaims}
import pl.iterators.epoca.oauth.repositories.OAuthClientRepository
import pl.iterators.epoca.oauth.services.OAuthJwtService
import pl.iterators.epoca.oauth.time.OAuthAuthoritativeTime
import pl.iterators.epoca.user.repositories.ServiceAccountRepository
import pl.iterators.epoca.utils.db.BaseRepository.driver.api.DBIO
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.log.LoggingSupport

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}

trait OAuthPrincipalAuthenticator extends (Option[HttpCredentials] => Future[AuthenticationResult[OAuthAuthContext]])
object OAuthPrincipalAuthenticator {
  val disabled: OAuthPrincipalAuthenticator = _ =>
    Future.successful(AuthenticationResult.failWithChallenge(HttpChallenge(scheme = "Bearer", realm = "epoca-backend")))
}

final class OAuthAuthenticator(
  transactor: PostgresIOTransactor,
  authoritativeTime: OAuthAuthoritativeTime,
  jwtService: OAuthJwtService,
  clientRepository: OAuthClientRepository,
  serviceAccountRepository: ServiceAccountRepository
)(implicit
  runtime: IORuntime,
  ec: ExecutionContext)
    extends OAuthPrincipalAuthenticator
    with LoggingSupport {

  override def apply(credentials: Option[HttpCredentials]): Future[AuthenticationResult[OAuthAuthContext]] =
    credentials match {
      case Some(value) if value.scheme() == "Bearer" =>
        authenticate(value.token())
          .map {
            case Some(context) => AuthenticationResult.success(context)
            case None          => Challenge
          }
          .unsafeToFuture()
      case _ => ChallengeFuture
    }

  private def authenticate(token: String) =
    jwtService.verifyClaims(token) match {
      case Left(_) => IO.pure(Option.empty[OAuthAuthContext])
      case Right(claims) =>
        transactor
          .executeTransactionally(authenticateCurrent(claims))
          .attempt
          .flatMap {
            case Right(Some(authenticated)) if authenticated.shouldTouchLastUsedAt => recordLastUse(authenticated)
            case Right(Some(authenticated))                                        => IO.pure(Some(authenticated.context))
            case Right(None)                                                       => IO.pure(None)
            case Left(_) =>
              loggingAdapter.warning("OAuth bearer authentication failed because required state was unavailable")
              IO.pure(None)
          }
    }

  private def authenticateCurrent(claims: OAuthTokenClaims): DBIO[Option[AuthenticatedOAuth]] =
    clientRepository.findPublic(claims.clientId).flatMap {
      case Some(snapshot)
          if snapshot.client.serviceAccountUserId == claims.subject &&
            snapshot.client.organizationId == claims.organizationId &&
            snapshot.client.policyVersion.asInstanceOf[Long] == claims.policyVersion &&
            claims.scopes.subsetOf(snapshot.scopes) =>
        authoritativeTime.now.flatMap { now =>
          val active = OAuthClientEffectiveStatus.resolve(snapshot.client.status, snapshot.client.expiresAt, now) == OAuthClientEffectiveStatus.Active
          if (active && jwtService.validateAt(claims, now).isRight)
            serviceAccountRepository.findById(claims.subject).map {
              case Some(account) if account.archivedAt.isEmpty =>
                Some(
                  new AuthenticatedOAuth(
                    OAuthAuthContext(
                      snapshot.client.serviceAccountUserId,
                      snapshot.client.id,
                      snapshot.client.organizationId,
                      claims.scopes,
                      snapshot.projectAccess,
                      claims.tokenId,
                      claims.policyVersion
                    ),
                    now,
                    snapshot.client.lastUsedAt.forall { lastUsedAt =>
                      !lastUsedAt.asInstanceOf[java.time.Instant].isAfter(now.minusNanos(LastUsedMinInterval.toNanos))
                    }
                  )
                )
              case _ => None
            }
          else DBIO.successful(None)
        }
      case _ => DBIO.successful(None)
    }

  private def recordLastUse(authenticated: AuthenticatedOAuth): IO[Option[OAuthAuthContext]] =
    transactor
      .executeTransactionally(clientRepository.touchLastUsedAt(authenticated.context.clientId, authenticated.at, LastUsedMinInterval))
      .attempt
      .map {
        case Right(_) =>
          touchFailureLogged.set(false)
          Some(authenticated.context)
        case Left(_) =>
          if (touchFailureLogged.compareAndSet(false, true))
            loggingAdapter.warning("OAuth bearer usage timestamp update failed")
          Some(authenticated.context)
      }

  private final class AuthenticatedOAuth(
    val context: OAuthAuthContext,
    val at: java.time.Instant,
    val shouldTouchLastUsedAt: Boolean)

  private val LastUsedMinInterval = 5.minutes
  private val touchFailureLogged  = new AtomicBoolean(false)

  private val Challenge: SecurityDirectives.AuthenticationResult[Nothing] =
    AuthenticationResult.failWithChallenge(HttpChallenge(scheme = "Bearer", realm = "epoca-backend"))

  private val ChallengeFuture: Future[SecurityDirectives.AuthenticationResult[Nothing]] =
    Future.successful(Challenge)
}
