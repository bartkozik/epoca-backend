package pl.iterators.epoca.auth.services

import cats.effect.IO
import cats.effect.kernel.Sync
import cats.effect.unsafe.IORuntime
import pl.iterators.epoca.auth.domain.AccountAuthentication.BoundSaml
import pl.iterators.epoca.auth.domain.AuthDomain._
import pl.iterators.epoca.auth.domain.RefreshToken
import pl.iterators.epoca.auth.repositories.RefreshTokenRepository
import pl.iterators.epoca.auth.repositories.RefreshTokenRepository.RefreshTokenFilters
import pl.iterators.epoca.auth.routers.AuthRouter.LoginHttpRequestContext
import pl.iterators.epoca.auth.services.JwtService.DecodingResult
import pl.iterators.epoca.auth.services.LoginByRefreshTokenService._
import pl.iterators.epoca.auth.services.LoginResolveService.{LoginResolution, RefreshTokenExchange}
import pl.iterators.epoca.organization.domain.OrganizationUser
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.user.domain.UserDomain._
import pl.iterators.epoca.user.domain.UserWithIdentities
import pl.iterators.epoca.user.services.UserWithIdentitiesFindService
import pl.iterators.epoca.user.services.UserWithIdentitiesFindService.UserWithIdentitiesFindResult
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.json.JsonProtocol
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.service.SealedMonadServiceIODBIO
import pl.iterators.sealedmonad.syntax._

import java.time.{ZoneOffset, ZonedDateTime}

class LoginByRefreshTokenService(
  transactor: PostgresIOTransactor,
  jwtService: JwtService,
  userWithIdentitiesFindService: UserWithIdentitiesFindService,
  organizationUserRepository: OrganizationUserRepository,
  refreshTokenRepository: RefreshTokenRepository,
  loginResolveService: LoginResolveService,
  samlSessionService: SamlSessionService
)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIODBIO[LoginByRefreshTokenResult](ioRuntime)
    with JsonProtocol
    with LoggingSupport {

  def login(loginByRefreshTokenRequest: LoginByRefreshTokenRequest, requestContext: LoginHttpRequestContext): IO[LoginByRefreshTokenResult] =
    (for {
      refreshToken <- decodeRefreshToken(loginByRefreshTokenRequest)
      _            <- validateRefreshToken(refreshToken, requestContext.deviceId)
      token        <- ensureRefreshTokenExists(refreshToken)
      user         <- findUserWithIdentities(token.userId)
      orgUsers     <- listOrgUsers(token.userId)
      _            <- validateSamlSession(requestContext, user, orgUsers, token)
      result       <- loginResolve(requestContext, user, orgUsers, token)
    } yield LoginByRefreshTokenResult.Ok(result)).run.handleErrorWith {
      case LoginResolveService.InvalidRefreshSession => IO.pure(LoginByRefreshTokenResult.InvalidRefreshToken)
      case error                                     => IO.raiseError(error)
    }

  private def decodeRefreshToken(request: LoginByRefreshTokenRequest): StepIO[RefreshToken] =
    IO.delay(jwtService.decode[RefreshToken](request.refreshTokenJwt)).seal.attempt {
      case DecodingResult.Decoded(token) => Right(token)
      case _                             => Left(LoginByRefreshTokenResult.InvalidRefreshToken)
    }

  private def validateRefreshToken(refreshToken: RefreshToken, deviceId: DeviceId): StepIO[Boolean] =
    IO.pure(refreshToken.deviceId == deviceId).seal.ensure(identity, LoginByRefreshTokenResult.InvalidRefreshToken)

  private def ensureRefreshTokenExists(refreshToken: RefreshToken): StepIO[RefreshToken] =
    transactor
      .execute(
        refreshTokenRepository
          .find(RefreshTokenFilters(userId = Some(refreshToken.userId), deviceId = Some(refreshToken.deviceId), value = Some(refreshToken.value)))
      )
      .flatMap {
        case found @ Some(_) => IO.pure(found)
        case None            => transactor.execute(refreshTokenRepository.revokeIfStale(refreshToken)).as(Option.empty[RefreshToken])
      }
      .valueOr(LoginByRefreshTokenResult.UserOrRefreshTokenNotFound)

  private def findUserWithIdentities(userId: UserId): StepIO[UserWithIdentities] = userWithIdentitiesFindService.find(userId).attemptF {
    case UserWithIdentitiesFindResult.Ok(user) if user.accountType == UserAccountType.Human => Sync[IO].pure(Right(user))
    case UserWithIdentitiesFindResult.Ok(_) | UserWithIdentitiesFindResult.UserDoNotExist =>
      Logger[IO].warning(s"User $userId does not exist") *> Sync[IO].pure(Left(LoginByRefreshTokenResult.UserOrRefreshTokenNotFound))
  }

  private def listOrgUsers(userId: UserId): StepIO[Seq[OrganizationUser]] =
    transactor.execute {
      organizationUserRepository.list(OrganizationUserFilters(userId = Some(userId)))
    }.seal

  private def validateSamlSession(
    requestContext: LoginHttpRequestContext,
    user: UserWithIdentities,
    orgUsers: Seq[OrganizationUser],
    token: RefreshToken
  ): StepIO[Unit] = token.accountAuthentication.map(_.evidence) match {
    case Some(_: BoundSaml) =>
      val context =
        RefreshTokenExchange(requestContext, user, orgUsers, token).toAuthContext(ZonedDateTime.ofInstant(token.lastAuthedAt, ZoneOffset.UTC))
      samlSessionService.currentOrganization(context).seal.ensure(_.nonEmpty, LoginByRefreshTokenResult.InvalidRefreshToken).map(_ => ())
    case _ => IO.unit.seal
  }

  private def loginResolve(
    requestContext: LoginHttpRequestContext,
    user: UserWithIdentities,
    orgUsers: Seq[OrganizationUser],
    token: RefreshToken
  ): StepIO[LoginResolution] =
    loginResolveService.resolve(RefreshTokenExchange(requestContext, user, orgUsers, token)).seal
}

object LoginByRefreshTokenService {
  final case class LoginByRefreshTokenRequest(refreshTokenJwt: Jwt)

  sealed trait LoginByRefreshTokenResult
  object LoginByRefreshTokenResult {
    final case class Ok(data: LoginResolution)   extends LoginByRefreshTokenResult
    final case object UserOrRefreshTokenNotFound extends LoginByRefreshTokenResult
    final case object InvalidRefreshToken        extends LoginByRefreshTokenResult
  }

}
