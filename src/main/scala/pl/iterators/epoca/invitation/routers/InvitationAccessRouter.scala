package pl.iterators.epoca.invitation.routers

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import io.circe.Json
import io.circe.syntax._
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.server.Route
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.auth.domain.AuthDomain.PasswordPlain
import pl.iterators.epoca.auth.routers.AuthRouter.LoginHttpRequestContext
import pl.iterators.epoca.invitation.services.{
  InvitationAccessService,
  InvitationActivationService,
  InvitationBrowserSecurity,
  InvitationManagementJson
}
import pl.iterators.epoca.oauth.services.TrustedClientIpService
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.saml.services.{SamlAuthnService, SamlBrowserPolicy, SamlInvitationService}
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.http.BaseRouter
import pl.iterators.epoca.utils.log.TracingContext

class InvitationAccessRouter(
  access: InvitationAccessService,
  activation: InvitationActivationService,
  browser: InvitationBrowserSecurity,
  saml: SamlInvitationService,
  samlAuthn: SamlAuthnService,
  samlPolicy: Option[SamlBrowserPolicy],
  transactor: PostgresIOTransactor,
  trusted: TrustedClientIpService
)(implicit runtime: IORuntime)
    extends BaseRouter {

  private def failure(status: StatusCode, code: String): HttpResponse = HttpResponse(
    status,
    entity = HttpEntity(
      ContentTypes.`application/json`,
      Json
        .obj(
          "type"   -> Json.fromString("result:invitation:" + code),
          "status" -> Json.fromInt(status.intValue),
          "title"  -> Json.fromString("Invitation request could not be completed")
        )
        .noSpaces
    )
  )
  private def json(status: StatusCode, dto: Json): HttpResponse =
    HttpResponse(status, entity = HttpEntity(ContentTypes.`application/json`, dto.noSpaces))
  private def limited(retry: Long): HttpResponse =
    failure(StatusCodes.TooManyRequests, "rate-limited").addHeader(RawHeader("Retry-After", retry.toString))
  private def projection(dto: InvitationAccessService.SafeAccessDto): Json = Json.obj(
    "organization" -> Json.obj("name" -> Json.fromString(dto.organization.name), "slug" -> Json.fromString(dto.organization.slug)),
    "email"        -> Json.fromString(dto.email),
    "firstName"    -> dto.firstName.fold[Json](Json.Null)(Json.fromString),
    "lastName"     -> dto.lastName.fold[Json](Json.Null)(Json.fromString),
    "roles"        -> Json.fromValues(dto.roles.map(Json.fromString)),
    "revision"     -> Json.fromString(dto.revision),
    "expiresAt"    -> Json.fromString(dto.expiresAt.toString),
    "csrfToken"    -> Json.fromString(dto.csrfToken),
    "nextAction"   -> Json.fromString(dto.nextAction),
    "ssoEnabled"   -> Json.fromBoolean(dto.ssoEnabled)
  )

  private def classify(result: InvitationAccessService.Result, auth: Option[AuthContext]): IO[InvitationAccessService.Result] = result match {
    case grant: InvitationAccessService.Granted =>
      activation.nextAction(grant.cookie, auth).flatMap { human =>
        transactor.executeTransactionally(saml.classifyContinuationAction(grant.cookie)).map { continuation =>
          grant.copy(dto = grant.dto.copy(nextAction = human.orElse(continuation).getOrElse(grant.dto.nextAction)))
        }
      }
    case other => IO.pure(other)
  }
  private def accessResponse(result: InvitationAccessService.Result): HttpResponse = result match {
    case grant: InvitationAccessService.Granted =>
      json(StatusCodes.OK, projection(grant.dto)).addHeader(RawHeader("Set-Cookie", access.cookieHeader(grant)))
    case InvitationAccessService.InvalidInvitation  => failure(StatusCodes.NotFound, "invalid-invitation")
    case InvitationAccessService.Forbidden          => failure(StatusCodes.Forbidden, "forbidden")
    case InvitationAccessService.Revoked            => HttpResponse(StatusCodes.NoContent, List(RawHeader("Set-Cookie", browser.clearCookie)))
    case InvitationAccessService.RateLimited(retry) => limited(retry)
  }
  private def activationResponse(result: InvitationActivationService.Result): HttpResponse = {
    import InvitationActivationService._
    result match {
      case SessionJoined(slug, accessToken, refreshToken) =>
        json(
          StatusCodes.OK,
          Json.obj(
            "organizationSlug" -> Json.fromString(slug),
            "accessToken"      -> Json.fromString(accessToken),
            "refreshToken"     -> Json.fromString(refreshToken)
          )
        ).addHeader(RawHeader("Set-Cookie", browser.clearCookie))
      case SessionIssueFailed(_) =>
        json(StatusCodes.ServiceUnavailable, Json.obj("type" -> Json.fromString("result:invitation:session-issue-failed"), "joined" -> Json.True))
      case InvalidInvitation                     => failure(StatusCodes.NotFound, "invalid-invitation")
      case Forbidden                             => failure(StatusCodes.Forbidden, "forbidden")
      case IdentityMismatch                      => failure(StatusCodes.Forbidden, "identity-mismatch")
      case SignInRequired                        => failure(StatusCodes.Unauthorized, "sign-in-required")
      case PasswordRequired | _: InvalidPassword => failure(StatusCodes.BadRequest, "invalid-request")
      case RateLimited(retry)                    => limited(retry)
      case RevisionConflict                      => failure(StatusCodes.Conflict, "revision-conflict")
      case AlreadyMember                         => failure(StatusCodes.Conflict, "already-member")
      case IdentityConflict                      => failure(StatusCodes.Conflict, "identity-conflict")
      case _                                     => failure(StatusCodes.Conflict, "invitation-conflict")
    }
  }
  private def strict(body: Json, keys: Set[String]): Boolean = body.asObject.exists(_.keys.toSet == keys)
  def routes(auth: Option[AuthContext])(implicit tracing: TracingContext): Route = pathPrefix("invitation-access") {
    InvitationHttpSecurity.browser(browser) {
      extractRequest { request =>
        if (auth.isEmpty && request.headers.exists(_.is("authorization"))) complete(failure(StatusCodes.Unauthorized, "sign-in-required"))
        else
          optionalCookie("epoca_invitation_access") { cookie =>
            val secret = cookie.map(_.value)
            val origin = request.headers.find(_.is("origin")).map(_.value())
            val csrf   = request.headers.find(_.is("x-csrf-token")).map(_.value())
            (post & path("exchange") & entity(as[Json])) { body =>
              body.hcursor.get[String]("token").toOption.filter(_ => strict(body, Set("token"))) match {
                case None => complete(failure(StatusCodes.BadRequest, "invalid-request"))
                case Some(token) =>
                  complete(
                    access
                      .exchange(token, secret, origin, Some(request.entity.contentType.toString), InvitationHttpSecurity.trustedIp(request, trusted))
                      .flatMap(classify(_, auth))
                  )(accessResponse)
              }
            } ~ extractUnmatchedPath { remaining =>
              if (!Set("", "/", "/password", "/accept", "/saml")(remaining.toString)) reject
              else
                secret.fold[Route](complete(failure(StatusCodes.NotFound, "invalid-invitation"))) { value =>
                  (get & pathEndOrSingleSlash)(complete(access.summary(value).flatMap(classify(_, auth)))(accessResponse)) ~
                    (delete & pathEndOrSingleSlash)(complete(access.revoke(value, origin, csrf))(accessResponse)) ~
                    (post & (path("password") | path("accept")) & entity(as[Json])) { body =>
                      val passwordRoute = request.uri.path.toString.endsWith("/password")
                      val password      = if (passwordRoute) body.hcursor.get[String]("password").toOption.map(PasswordPlain(_)) else None
                      val keys          = if (passwordRoute) Set("expectedRevision", "password") else Set("expectedRevision")
                      if (!strict(body, keys) || (passwordRoute && password.isEmpty) || InvitationManagementJson.revision(body).isLeft)
                        complete(failure(StatusCodes.BadRequest, "invalid-request"))
                      else
                        extractDeviceId { device =>
                          val context = LoginHttpRequestContext(device, request.headers.find(_.is("user-agent")).map(_.value()))
                          complete(
                            activation
                              .activate(value, body.hcursor.get[String]("expectedRevision").toOption.get, password, auth, origin, csrf, context)
                          )(activationResponse)
                        }
                    } ~ (post & path("saml")) {
                      complete(access.organizationSlug(value).flatMap {
                        case Some(slug) => samlAuthn.authnRequest(OrganizationSlug(slug), auth, Some(value), origin, csrf)
                        case _          => IO.pure(SamlAuthnService.SamlAuthnResult.InvalidContext)
                      }) {
                        case SamlAuthnService.SamlAuthnResult.Ok(data, nonce) =>
                          samlPolicy match {
                            case Some(policy) => json(StatusCodes.Created, data.asJson).addHeader(RawHeader("Set-Cookie", policy.nonceCookie(nonce)))
                            case None         => failure(StatusCodes.ServiceUnavailable, "saml-unavailable")
                          }
                        case _ => failure(StatusCodes.NotFound, "invalid-invitation")
                      }
                    }
                }
            }
          }
      }
    }
  }
}
