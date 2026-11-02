package pl.iterators.epoca.auth.routers

import cats.effect.unsafe.IORuntime
import org.apache.pekko.http.scaladsl.model.StatusCodes._
import org.apache.pekko.http.scaladsl.model.headers.`User-Agent`
import org.apache.pekko.http.scaladsl.server.{Directive1, Route}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.auth.domain.AuthDomain.DeviceId
import pl.iterators.epoca.auth.services.AdminImpersonateLoginService.{AdminImpersonateLoginRequest, AdminImpersonateLoginResult}
import pl.iterators.epoca.auth.services.LoginByCryptoVerificationService.{LoginByCryptoVerificationRequest, LoginByCryptoVerificationResult}
import pl.iterators.epoca.auth.services.LoginByFirebaseService.{LoginByFirebaseRequest, LoginByFirebaseResult}
import pl.iterators.epoca.auth.services.LoginByPasswordService.{LoginRequest, LoginResult}
import pl.iterators.epoca.auth.services.LoginByRefreshTokenService.{LoginByRefreshTokenRequest, LoginByRefreshTokenResult}
import pl.iterators.epoca.auth.services.LoginByTimeBasedOneTimePasswordService._
import pl.iterators.epoca.auth.services.LoginByTokenService.{LoginByTokenRequest, LoginByTokenResult}
import pl.iterators.epoca.auth.services.LoginTokenGetService.{LoginTokenGetRequest, LoginTokenGetResult}
import pl.iterators.epoca.auth.services.PasswordSetService.{PasswordSetRequest, PasswordSetResult}
import pl.iterators.epoca.auth.services.RefreshTokenDeleteService.{RefreshTokenDeleteRequest, RefreshTokenDeleteResult}
import pl.iterators.epoca.auth.services.RefreshTokenListService.RefreshTokenListResult
import pl.iterators.epoca.auth.services.TimeBasedOneTimePasswordSecretGetService.TimeBasedOneTimePasswordSecretGetResult
import pl.iterators.epoca.auth.services._
import pl.iterators.epoca.utils.http.BaseRouter
import pl.iterators.epoca.utils.log.TracingContext

class AuthRouter(
  loginByPasswordService: LoginByPasswordService,
  LoginByCryptoVerificationService: LoginByCryptoVerificationService,
  loginByTokenService: LoginByTokenService,
  loginByFirebaseService: LoginByFirebaseService,
  loginByRefreshTokenService: LoginByRefreshTokenService,
  loginByTimeBasedOneTimePasswordService: LoginByTimeBasedOneTimePasswordService,
  adminImpersonateLoginService: AdminImpersonateLoginService,
  passwordSetService: PasswordSetService,
  loginTokenGetService: LoginTokenGetService,
  timeBasedOneTimePasswordSecretGetService: TimeBasedOneTimePasswordSecretGetService,
  refreshTokenDeleteService: RefreshTokenDeleteService,
  refreshTokenListService: RefreshTokenListService
)(implicit runtime: IORuntime)
    extends BaseRouter {
  import AuthRouter._

  private def loginRoutes(authContext: Option[AuthContext])(implicit tracingContext: TracingContext): Route =
    pathPrefix(authPath) {
      extractLoginHttpRequestContext { loginHttpRequestContext =>
        (post & path(loginPath) & pathEndOrSingleSlash & entity(as[LoginRequest])) { request =>
          complete(loginByPasswordService.login(authContext, request, loginHttpRequestContext)) {
            case LoginResult.Ok(data)                   => OK -> data
            case r @ LoginResult.InvalidRequest         => error(BadRequest, typeFromResult[r.type], "Invalid request")
            case r @ LoginResult.InvalidCredentials     => error(Unauthorized, typeFromResult[r.type], "Invalid credentials")
            case r @ LoginResult.UserOrPasswordNotFound => error(Unauthorized, typeFromResult[r.type], "User or password not found")
            case r @ LoginResult.IdentityNotConfirmed   => error(Forbidden, typeFromResult[r.type], "Identity not confirmed")
          }
        } ~
          (post & path(cryptoPath) & pathEndOrSingleSlash & entity(as[LoginByCryptoVerificationRequest])) { request =>
            complete(LoginByCryptoVerificationService.login(authContext, request, loginHttpRequestContext)) {
              case LoginByCryptoVerificationResult.Ok(data)               => OK -> data
              case r @ LoginByCryptoVerificationResult.InvalidCredentials => error(Unauthorized, typeFromResult[r.type], "Invalid credentials")
              case r @ LoginByCryptoVerificationResult.UserNotFound       => error(Unauthorized, typeFromResult[r.type], "User not found")
            }
          } ~
          (post & path(totpPath) & pathEndOrSingleSlash & entity(as[LoginByTimeBasedOneTimePasswordRequest])) { request =>
            complete(loginByTimeBasedOneTimePasswordService.login(authContext, request, loginHttpRequestContext)) {
              case LoginByTimeBasedOneTimePasswordResult.Ok(data)               => OK -> data
              case r @ LoginByTimeBasedOneTimePasswordResult.InvalidCredentials => error(Unauthorized, typeFromResult[r.type], "Invalid credentials")
              case r @ LoginByTimeBasedOneTimePasswordResult.UserOrPasswordNotFound =>
                error(Unauthorized, typeFromResult[r.type], "User or password not found")
            }
          } ~
          (post & path(tokenLoginPath) & pathEndOrSingleSlash & entity(as[LoginByTokenRequest])) { request =>
            complete(loginByTokenService.login(authContext, request, loginHttpRequestContext)) {
              case LoginByTokenResult.Ok(data)                     => OK -> data
              case r @ LoginByTokenResult.InvalidCredentials       => error(Unauthorized, typeFromResult[r.type], "Invalid credentials")
              case r @ LoginByTokenResult.UserOrLoginTokenNotFound => error(Unauthorized, typeFromResult[r.type], "User or login token not found")
              case r @ LoginByTokenResult.TokenExpired             => error(Conflict, typeFromResult[r.type], "Token expired")
            }
          } ~
          (post & path(firebasePath) & pathEndOrSingleSlash & entity(as[LoginByFirebaseRequest])) { request =>
            complete(loginByFirebaseService.login(authContext, request, loginHttpRequestContext)) {
              case LoginByFirebaseResult.Ok(data)                  => OK -> data
              case r @ LoginByFirebaseResult.InvalidCredentials    => error(Unauthorized, typeFromResult[r.type], "Invalid credentials")
              case r @ LoginByFirebaseResult.FirebaseNotConfigured => error(NotImplemented, typeFromResult[r.type], "Firebase not configured")
            }
          }

      }
    }

  def routes(implicit tracingContext: TracingContext): Route =
    loginRoutes(None) ~
      pathPrefix(authPath) {
        extractLoginHttpRequestContext { loginHttpRequestContext =>
          (post & path(refreshTokenPath) & pathEndOrSingleSlash & entity(as[LoginByRefreshTokenRequest])) { request =>
            complete(loginByRefreshTokenService.login(request, loginHttpRequestContext)) {
              case LoginByRefreshTokenResult.Ok(data) => OK -> data
              case r @ LoginByRefreshTokenResult.InvalidRefreshToken =>
                error(Unauthorized, typeFromResult[r.type], "Invalid refresh token")
              case r @ LoginByRefreshTokenResult.UserOrRefreshTokenNotFound =>
                error(Unauthorized, typeFromResult[r.type], "User or refresh token not found")
            }
          }
        } ~
          (post & path(loginTokenGetPath) & pathEndOrSingleSlash & entity(as[LoginTokenGetRequest])) { request =>
            complete(loginTokenGetService.getToken(request)) {
              case LoginTokenGetResult.Ok                 => OK
              case r @ LoginTokenGetResult.UserNotFound   => error(NotFound, typeFromResult[r.type], "User not found")
              case r @ LoginTokenGetResult.InvalidRequest => error(BadRequest, typeFromResult[r.type], "Invalid request")
            }
          }
      }

  def routes(authContext: AuthContext)(implicit tracingContext: TracingContext): Route =
    loginRoutes(Some(authContext)) ~
      pathPrefix(authPath) {
        extractLoginHttpRequestContext { loginHttpRequestContext =>
          (post & path(adminLoginPath) & pathEndOrSingleSlash & entity(as[AdminImpersonateLoginRequest])) { request =>
            complete(adminImpersonateLoginService.login(authContext, request, loginHttpRequestContext)) {
              case AdminImpersonateLoginResult.Ok(result)                => OK -> result
              case r @ AdminImpersonateLoginResult.UserDoesNotExist      => error(NotFound, typeFromResult[r.type], "User does not exist")
              case r @ AdminImpersonateLoginResult.OperationNotPermitted => error(Unauthorized, typeFromResult[r.type], "Operation not permitted")
            }
          }
        } ~ (get & path(totpPath) & pathEndOrSingleSlash) {
          complete(timeBasedOneTimePasswordSecretGetService.get(authContext)) { case TimeBasedOneTimePasswordSecretGetResult.Ok(data) =>
            OK -> data
          }
        } ~ (patch & path(passwordPath) & pathEndOrSingleSlash & entity(as[PasswordSetRequest])) { request =>
          complete(passwordSetService.passwordSet(authContext, request)) {
            case PasswordSetResult.Ok                     => OK
            case r @ PasswordSetResult.IdentityNotFound   => error(NotFound, typeFromResult[r.type], "Identity not found")
            case r @ PasswordSetResult.PasswordNotChanged => error(BadRequest, typeFromResult[r.type], "Password not changed")
            case r @ PasswordSetResult.InvalidPassword(problems) =>
              error(BadRequest, typeFromResult[r.type], "Invalid password", Some(problems.mkString(" ")), Map("problems" -> problems))
            case r @ PasswordSetResult.InvalidAuthContext => error(Forbidden, typeFromResult[r.type], "Invalid auth context")
          }
        } ~ pathPrefix(refreshTokensPath) {
          (get & pathEndOrSingleSlash) {
            complete(refreshTokenListService.list(authContext)) { case RefreshTokenListResult.Ok(devicesId) =>
              OK -> devicesId
            }
          } ~ (delete & path(Segment.as[DeviceId]) & pathEndOrSingleSlash) { deviceId =>
            complete(refreshTokenDeleteService.delete(authContext, RefreshTokenDeleteRequest(deviceId))) { case RefreshTokenDeleteResult.Ok =>
              OK
            }
          }
        }
      }

  private def extractLoginHttpRequestContext: Directive1[LoginHttpRequestContext] =
    extractDeviceId.flatMap { deviceId =>
      optionalHeaderValueByType(`User-Agent`).map { userAgent =>
        LoginHttpRequestContext(deviceId, userAgent.map(_.value()))
      }
    }
}

object AuthRouter {
  val authPath          = "auth"
  val loginPath         = "login"
  val cryptoPath        = "crypto"
  val totpPath          = "totp"
  val loginTokenGetPath = "get-token"
  val tokenLoginPath    = "token"
  val firebasePath      = "firebase"
  val adminLoginPath    = "admin"
  val refreshTokenPath  = "refresh-token"
  val refreshTokensPath = "refresh-tokens"
  val passwordPath      = "password"

  final case class LoginHttpRequestContext(deviceId: DeviceId, userAgent: Option[String])
}
