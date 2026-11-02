package pl.iterators.epoca.auth.services

import cats.effect.{Clock, IO}
import com.typesafe.config.{Config => TsConfig}
import io.circe.syntax._
import pl.iterators.epoca.auth.domain.AccountAuthentication.{BoundSaml, Credential, CredentialMethod}
import pl.iterators.epoca.auth.domain.AuthContext.OrganizationRoles
import pl.iterators.epoca.auth.domain.AuthDomain._
import pl.iterators.epoca.auth.domain.{AccountAuthentication, AuthContext, AuthContextAuthedBy, RefreshToken}
import pl.iterators.epoca.auth.repositories.RefreshTokenRepository
import pl.iterators.epoca.auth.routers.AuthRouter.LoginHttpRequestContext
import pl.iterators.epoca.auth.services.LoginResolveService._
import pl.iterators.epoca.organization.domain.OrganizationUser
import pl.iterators.epoca.user.domain.UserDomain.{IdentityType, UserAccountType}
import pl.iterators.epoca.user.domain.{UserIdentity, UserIdentityShortDto, UserWithIdentities}
import pl.iterators.epoca.utils.clock.ClockExtension
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.json.JsonProtocol

import java.time.{Duration, Instant, ZoneId, ZonedDateTime}
import scala.concurrent.duration.FiniteDuration
import scala.jdk.DurationConverters._
class LoginResolveService(
  transactor: PostgresIOTransactor,
  config: LoginResolveService.Config,
  jwtService: JwtService,
  refreshTokenRepository: RefreshTokenRepository,
  refreshTokenValueGeneratorService: RefreshTokenValueGeneratorService
)(implicit clockIO: Clock[IO])
    extends JsonProtocol
    with ClockExtension {

  def resolve(request: LoginResolveRequest): IO[LoginResolution] =
    for {
      _   <- IO.raiseWhen(request.user.accountType != UserAccountType.Human)(ServiceAccountNotPermitted)
      _   <- validateRequest(request)
      now <- getNow
      authContext       = buildAuthContext(now, request)
      refreshTokenValue = refreshTokenValueGeneratorService.generate
      refreshToken      = buildRefreshToken(config, now, authContext, request, refreshTokenValue)
      refreshToken <- persistRefreshToken(refreshToken, request, now.toInstant)
      encodedAccessToken  = encodeAccessToken(now, authContext, request)
      encodedRefreshToken = encodeRefreshToken(now, refreshToken)
    } yield LoginResolution(encodedAccessToken, encodedRefreshToken)

  private def getNow: IO[ZonedDateTime] =
    clockIO.instantNow.map { now =>
      ZonedDateTime.ofInstant(now, ZoneId.systemDefault())
    }

  private def buildAuthContext(now: ZonedDateTime, request: LoginResolveRequest): AuthContext =
    request.toAuthContext(now)

  private def validateRequest(request: LoginResolveRequest): IO[Unit] = request match {
    case impersonation: AdminImpersonate => IO.raiseUnless(isAllowedImpersonationDuration(impersonation.validFor))(InvalidImpersonationDuration)
    case refresh: RefreshTokenExchange =>
      val token = refresh.refreshToken
      val usableIdentity = refresh.user.identities.exists(i =>
        token.authedBy.contains(i.id) && i.`type` != IdentityType.Saml && i.`type` != IdentityType.AdminImpersonate
      )
      val authenticationValid = token.accountAuthentication.map(_.evidence) match {
        case Some(bound: BoundSaml) =>
          refresh.orgUsers.exists(m => m.userId == refresh.user.id && m.organizationId == bound.organizationId && m.archivedAt.isEmpty)
        case _ => usableIdentity
      }
      IO.raiseUnless(token.userId == refresh.user.id && token.deviceId == refresh.requestContext.deviceId && authenticationValid)(
        InvalidRefreshSession
      )
    case invitation: InvitationLogin =>
      IO.raiseWhen(invitation.authedBy.exists(_.`type` == IdentityType.AdminImpersonate))(InvalidSession)
    case login: UserLogin =>
      val inheritedImpersonation =
        login.prevAuthContext.exists(c => c.id == login.user.id && c.authedBy.exists(_.`type` == IdentityType.AdminImpersonate))
      IO.raiseWhen(inheritedImpersonation && !(login.verifiedMethod.nonEmpty && login.authBy.userId == login.user.id))(InvalidSession)
    case _ => IO.unit
  }

  private def persistRefreshToken(
    refreshToken: RefreshToken,
    request: LoginResolveRequest,
    now: Instant
  ): IO[RefreshToken] = request match {
    // Keep the API response shape, but the signed refresh token is deliberately not redeemable.
    case _: AdminImpersonate => IO.pure(refreshToken)
    case exchange: RefreshTokenExchange =>
      transactor.execute(refreshTokenRepository.rotate(exchange.refreshToken, refreshToken, now)).flatMap { rotated =>
        // This error must be outside the database transaction so replay revocation is committed.
        IO.fromOption(rotated)(InvalidRefreshSession)
      }
    case _ => transactor.execute(refreshTokenRepository.upsert(refreshToken))
  }

  private def encodeAccessToken(
    now: ZonedDateTime,
    authContext: AuthContext,
    request: LoginResolveRequest
  ): Jwt =
    Jwt(
      jwtService
        .encode(
          authContext.asJson,
          issuedAt = now,
          expiresAt = Some(now.plusSeconds(request.accessTokenValidFor.getOrElse(config.accessTokenValidFor.toJava).toSeconds))
        )
    )

  private def encodeRefreshToken(now: ZonedDateTime, refreshToken: RefreshToken): Jwt =
    Jwt(
      jwtService
        .encode(refreshToken.asJson, issuedAt = now, expiresAt = refreshToken.expiredAt.map(e => ZonedDateTime.ofInstant(e, ZoneId.systemDefault())))
    )

}

object LoginResolveService {
  val MaxImpersonationDuration: Duration = Duration.ofHours(1)
  private[services] def isAllowedImpersonationDuration(duration: Duration): Boolean =
    !duration.isNegative && !duration.isZero && duration.compareTo(MaxImpersonationDuration) <= 0
  case object InvalidImpersonationDuration extends RuntimeException("invalid impersonation lifetime")
  case object InvalidRefreshSession        extends RuntimeException("refresh session unavailable")
  case object InvalidSession               extends RuntimeException("session unavailable")
  case object ServiceAccountNotPermitted   extends RuntimeException("login user unavailable")

  case class Config(
    accessTokenValidFor: FiniteDuration,
    refreshTokenValidFor: FiniteDuration,
    refreshTokenValidForStrategy: RefreshTokenValidForStrategy) {
    require(accessTokenValidFor.toSeconds > 0, "login.accessTokenValidFor must be positive")
    require(refreshTokenValidFor.toSeconds > 0, "login.refreshTokenValidFor must be positive")
    require(refreshTokenValidForStrategy != RefreshTokenValidForStrategy.Off, "Unbounded refresh tokens are disabled; use FromFirstUsage")
  }
  object Config {
    def apply(tsConfig: TsConfig): Config =
      Config(
        accessTokenValidFor = tsConfig.getDuration("login.accessTokenValidFor").toScala,
        refreshTokenValidFor = tsConfig.getDuration("login.refreshTokenValidFor").toScala,
        refreshTokenValidForStrategy = RefreshTokenValidForStrategy.withNameInsensitive(tsConfig.getString("login.refreshTokenValidForStrategy"))
      )
  }

  sealed trait LoginResolveRequest {
    val requestContext: LoginHttpRequestContext
    val user: UserWithIdentities

    def toAuthContext(at: ZonedDateTime): AuthContext

    val accessTokenValidFor: Option[Duration] = None
  }

  final case class AdminImpersonate(
    requestContext: LoginHttpRequestContext,
    user: UserWithIdentities,
    orgUsers: Seq[OrganizationUser],
    validFor: Duration)
      extends LoginResolveRequest {

    def toAuthContext(at: ZonedDateTime): AuthContext =
      SamlSessionScope.restrict(
        AuthContext(
          user.id,
          user.isSuperAdmin,
          user.identities.map(i => UserIdentityShortDto(i)),
          Set(AuthContextAuthedBy(IdentityType.AdminImpersonate, None)),
          orgUsers.filter(_.archivedAt.isEmpty).map(OrganizationRoles.apply),
          at.toInstant,
          fresh = true
        )
      )

    override val accessTokenValidFor: Option[Duration] = Some(validFor)
  }

  final case class UserLogin(
    requestContext: LoginHttpRequestContext,
    user: UserWithIdentities,
    orgUsers: Seq[OrganizationUser],
    prevAuthContext: Option[AuthContext],
    authBy: UserIdentity,
    verifiedMethod: Option[CredentialMethod] = None)
      extends LoginResolveRequest {

    def toAuthContext(at: ZonedDateTime): AuthContext =
      SamlSessionScope.restrict(
        AuthContext(
          user.id,
          user.isSuperAdmin,
          user.identities.map(i => UserIdentityShortDto(i)),
          prevAuthContext
            .filter(_.id == user.id)
            .map(_.authedBy)
            .getOrElse(Set.empty)
            .filterNot(a =>
              verifiedMethod.nonEmpty && authBy.userId == user.id && (a.`type` == IdentityType.Saml || a.`type` == IdentityType.AdminImpersonate)
            ) + AuthContextAuthedBy(authBy.`type`, Some(authBy.id)),
          orgUsers.filter(_.archivedAt.isEmpty).map(OrganizationRoles.apply),
          at.toInstant,
          fresh = true,
          accountAuthentication =
            verifiedMethod.filter(_ => authBy.userId == user.id).map(method => AccountAuthentication(at.toInstant, Credential(method, authBy.id)))
        )
      )
  }

  /** Server-only invitation grant issuance: membership changes are not a new authentication event. */
  private[epoca] final case class InvitationLogin(
    requestContext: LoginHttpRequestContext,
    user: UserWithIdentities,
    orgUsers: Seq[OrganizationUser],
    authentication: Option[AccountAuthentication],
    authedBy: Set[AuthContextAuthedBy],
    lastAuthedAt: java.time.Instant)
      extends LoginResolveRequest {
    def toAuthContext(at: ZonedDateTime): AuthContext = {
      val fresh = authentication.exists(p => !p.authenticatedAt.isAfter(at.toInstant) && !p.authenticatedAt.isBefore(at.toInstant.minusSeconds(300)))
      SamlSessionScope.restrict(
        AuthContext(
          user.id,
          user.isSuperAdmin,
          user.identities.map(UserIdentityShortDto(_)),
          authedBy,
          orgUsers.filter(_.archivedAt.isEmpty).map(OrganizationRoles.apply),
          lastAuthedAt,
          fresh,
          authentication
        )
      )
    }
  }

  final case class RefreshTokenExchange(
    requestContext: LoginHttpRequestContext,
    user: UserWithIdentities,
    orgUsers: Seq[OrganizationUser],
    refreshToken: RefreshToken)
      extends LoginResolveRequest {

    def toAuthContext(at: ZonedDateTime): AuthContext =
      SamlSessionScope.restrict(
        AuthContext(
          user.id,
          user.isSuperAdmin,
          user.identities.map(i => UserIdentityShortDto(i)),
          user.identities.filter(i => refreshToken.authedBy.contains(i.id)).map(authBy => AuthContextAuthedBy(authBy.`type`, Some(authBy.id))).toSet,
          orgUsers.filter(_.archivedAt.isEmpty).map(OrganizationRoles.apply),
          refreshToken.lastAuthedAt,
          fresh = false,
          accountAuthentication = Option.when(refreshToken.userId == user.id)(refreshToken.accountAuthentication).flatten
        )
      )
  }

  final case class SamlLogin(
    requestContext: LoginHttpRequestContext,
    user: UserWithIdentities,
    orgUsers: Seq[OrganizationUser],
    authentication: Option[AccountAuthentication] = None,
    authenticatedAt: Option[Instant] = None)
      extends LoginResolveRequest {

    def toAuthContext(at: ZonedDateTime): AuthContext =
      SamlSessionScope.restrict(
        AuthContext(
          user.id,
          user.isSuperAdmin,
          user.identities.map(i => UserIdentityShortDto(i)),
          Set(AuthContextAuthedBy(IdentityType.Saml, None)),
          orgUsers.filter(_.archivedAt.isEmpty).map(OrganizationRoles.apply),
          authenticatedAt.getOrElse(at.toInstant),
          fresh = authentication.exists(p => !p.authenticatedAt.isAfter(at.toInstant) && !p.authenticatedAt.isBefore(at.toInstant.minusSeconds(300))),
          accountAuthentication = authentication
        )
      )
  }

  def buildRefreshToken(
    config: Config,
    now: ZonedDateTime,
    authContext: AuthContext,
    request: LoginResolveRequest,
    refreshTokenValue: RefreshTokenValue
  ): RefreshToken = {

    val exchangingRefreshToken = request match {
      case RefreshTokenExchange(_, _, _, exchangingRefreshToken) => Some(exchangingRefreshToken)
      case _                                                     => None
    }

    val expireAtFromNow: Option[RefreshTokenExpiredAt] =
      Some(RefreshTokenExpiredAt(now.plusSeconds(config.refreshTokenValidFor.toSeconds).toInstant))

    val expirationDate: Option[RefreshTokenExpiredAt] = config.refreshTokenValidForStrategy match {
      case RefreshTokenValidForStrategy.Off             => throw new IllegalArgumentException("Unbounded refresh tokens are disabled")
      case RefreshTokenValidForStrategy.FromLatestUsage => expireAtFromNow
      case RefreshTokenValidForStrategy.FromFirstUsage =>
        exchangingRefreshToken
          .map(token => token.expiredAt.getOrElse(RefreshTokenExpiredAt(token.createdAt.plusSeconds(config.refreshTokenValidFor.toSeconds))))
          .orElse(expireAtFromNow)
    }

    RefreshToken(
      authContext.id,
      request.requestContext.deviceId,
      request.requestContext.userAgent.map(u => RefreshTokenUserAgent(u)),
      refreshTokenValue,
      authContext.authedBy.flatMap(_.id).toSeq,
      RefreshTokenLastUsedAt(authContext.lastAuthedAt),
      RefreshTokenCreatedAt(now.toInstant),
      request match {
        case impersonation: AdminImpersonate => Some(RefreshTokenExpiredAt(now.plus(impersonation.validFor).toInstant))
        case _                               => expirationDate
      },
      authContext.accountAuthentication
    )
  }

  final case class LoginResolution(accessToken: Jwt, refreshToken: Jwt)
}
