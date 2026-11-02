package pl.iterators.epoca.saml.routers

import cats.effect.unsafe.IORuntime
import io.circe.syntax._
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.model.headers.{Location, `User-Agent`}
import org.apache.pekko.http.scaladsl.server.{Directive1, Route}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.auth.routers.AuthRouter.LoginHttpRequestContext
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.saml.services.SamlAuthnService.SamlAuthnResult
import pl.iterators.epoca.saml.services.SamlAuthorizeService.SamlAuthorizeResult
import pl.iterators.epoca.saml.services.SamlConfigGetService.SamlConfigGetResult
import pl.iterators.epoca.saml.services.SamlConfigUpsertService.{SamlConfigUpsertRequest, SamlConfigUpsertResult}
import pl.iterators.epoca.saml.services.SamlConfigXmlService.SamlConfigXmlFindResult
import pl.iterators.epoca.saml.services.SamlLoginService.{SamlLoginRequest, SamlLoginResult}
import pl.iterators.epoca.saml.services._
import pl.iterators.epoca.utils.http.BaseRouter
import pl.iterators.epoca.utils.log.TracingContext

class SamlRouter(
  samlConfigXmlService: SamlConfigXmlService,
  samlAuthnService: SamlAuthnService,
  samlLoginService: SamlLoginService,
  samlAuthorizeService: SamlAuthorizeService,
  samlConfigUpsertService: SamlConfigUpsertService,
  samlConfigGetService: SamlConfigGetService,
  browserPolicy: Option[SamlBrowserPolicy]
)(implicit runtime: IORuntime)
    extends BaseRouter {
  import SamlRouter._

  def authRoutes(auth: AuthContext)(implicit tracingContext: TracingContext): Route =
    pathPrefix(organizationsPath / Segment.as[OrganizationSlug] / samlPath / configPath) { organizationSlug =>
      (post & pathEndOrSingleSlash & entity(as[SamlConfigUpsertRequest])) { request =>
        complete(samlConfigUpsertService.upsert(auth, organizationSlug, request)) {
          case SamlConfigUpsertResult.Created(samlConfig) => StatusCodes.Created -> samlConfig
          case SamlConfigUpsertResult.OrganizationNotFound =>
            StatusCodes.NotFound -> "You're not a member of this organization or organization does not exists"
          case SamlConfigUpsertResult.NoAccessToOrganization =>
            StatusCodes.NotFound -> "You're not a member of this organization or organization does not exists"
          case SamlConfigUpsertResult.PermissionsAreTooLow =>
            StatusCodes.Forbidden -> "You're a member of this organization, but your role doesn't allow you to modify SAML config."
        }
      } ~ (get & pathEndOrSingleSlash) {
        complete(samlConfigGetService.get(auth, organizationSlug)) {
          case SamlConfigGetResult.Ok(samlConfig) => StatusCodes.OK -> samlConfig
          case SamlConfigGetResult.OrganizationNotFound =>
            StatusCodes.NotFound -> "You're not a member of this organization or organization does not exists"
          case SamlConfigGetResult.SamlConfigNotFound => StatusCodes.NotFound -> "Requested SAML config doesn't exist"
          case SamlConfigGetResult.NoAccessToOrganization =>
            StatusCodes.NotFound -> "You're not a member of this organization or organization does not exists"
        }
      }
    } ~ browserPolicy.fold[Route](startRoutes(Some(auth)))(policy =>
      extractRequest { request =>
        if (request.uri.path.toString.stripSuffix("/").endsWith("/saml/authn"))
          pl.iterators.epoca.invitation.routers.InvitationHttpSecurity.browser(policy.security)(startRoutes(Some(auth)))
        else reject
      }
    )

  def routes(samlConfig: SamlUtilsService.Config)(implicit tracingContext: TracingContext): Route =
    extractRequest { request =>
      if (pl.iterators.epoca.utils.http.InvitationSensitiveRequest(request) && request.uri.path.toString.contains("/saml/")) {
        respondWithHeader(headers.RawHeader("Cache-Control", "no-store")) {
          if (request.uri.path.toString.endsWith("/acs")) publicRoutes(samlConfig)
          else
            browserPolicy.fold[Route](publicRoutes(samlConfig))(policy =>
              pl.iterators.epoca.invitation.routers.InvitationHttpSecurity.browser(policy.security)(publicRoutes(samlConfig))
            )
        }
      } else publicRoutes(samlConfig)
    }

  private def publicRoutes(samlConfig: SamlUtilsService.Config)(implicit tracingContext: TracingContext): Route =
    pathPrefix(organizationsPath / Segment.as[OrganizationSlug] / samlPath) { organizationSlug =>
      (get & pathPrefix(xmlPath) & pathEndOrSingleSlash) {
        complete(samlConfigXmlService.find(organizationSlug)) {
          case SamlConfigXmlFindResult.Ok(xml) => StatusCodes.OK -> HttpEntity(ContentTypes.`text/xml(UTF-8)`, xml)
          case SamlConfigXmlFindResult.OrganizationNotFound =>
            StatusCodes.NotFound -> "Requested organization doesn't exist or does not have SAML config"
          case SamlConfigXmlFindResult.SamlConfigNotFound =>
            StatusCodes.NotFound -> "Requested organization doesn't exist or does not have SAML config"
          case SamlConfigXmlFindResult.SamlDisabled => StatusCodes.Forbidden -> "SAML authorization is disabled"
        }
      }
    } ~ startRoutes(None) ~ callbackRoutes(samlConfig)

  private def startRoutes(auth: Option[AuthContext])(implicit tracingContext: TracingContext): Route =
    pathPrefix(organizationsPath / Segment.as[OrganizationSlug] / samlPath / authnPath) { slug =>
      pathEndOrSingleSlash {
        browserPolicy match {
          case None => complete(StatusCodes.ServiceUnavailable -> "SAML browser login is unavailable")
          case Some(policy) =>
            def start(
              cookie: Option[String],
              origin: Option[String],
              csrf: Option[String]
            ): Route =
              complete(samlAuthnService.authnRequest(slug, auth, cookie, origin, csrf)) {
                case SamlAuthnResult.Ok(data, nonce) =>
                  HttpResponse(
                    StatusCodes.Created,
                    headers = List(headers.RawHeader("Set-Cookie", policy.nonceCookie(nonce))),
                    entity = HttpEntity(ContentTypes.`application/json`, data.asJson.noSpaces)
                  )
                case SamlAuthnResult.OrganizationNotFound | SamlAuthnResult.SamlConfigNotFound =>
                  StatusCodes.NotFound -> "SAML configuration not found"
                case SamlAuthnResult.SamlDisabled   => StatusCodes.Forbidden          -> "SAML authorization is disabled"
                case SamlAuthnResult.InvalidContext => StatusCodes.Forbidden          -> "Invalid invitation context"
                case SamlAuthnResult.Unavailable    => StatusCodes.ServiceUnavailable -> "SAML browser login is unavailable"
              }
            get(start(None, None, None)) ~ post {
              optionalCookie("epoca_invitation_access") { cookie =>
                optionalHeaderValueByName("Origin") { origin =>
                  optionalHeaderValueByName("X-CSRF-Token") { csrf =>
                    if (cookie.isEmpty) complete(StatusCodes.Forbidden -> "Invalid invitation context")
                    else start(cookie.map(_.value), origin, csrf)
                  }
                }
              }
            }
        }
      }
    }

  private def callbackRoutes(samlConfig: SamlUtilsService.Config)(implicit tracingContext: TracingContext): Route =
    pathPrefix(organizationsPath / Segment.as[OrganizationSlug] / samlPath) { slug =>
      browserPolicy match {
        case None => (path(acsPath) | path(loginPath))(complete(StatusCodes.ServiceUnavailable -> "SAML browser login is unavailable"))
        case Some(policy) =>
          (post & path(acsPath) & formField("SAMLResponse")) { response =>
            complete(samlAuthorizeService.authorize(response, slug)) {
              case SamlAuthorizeResult.Authorized(token) =>
                HttpResponse(
                  StatusCodes.SeeOther,
                  Seq(Location(Uri(policy.callback).withFragment(Uri.Query("token" -> token.toString, "slug" -> slug.toString).toString)))
                )
              case _ =>
                HttpResponse(
                  StatusCodes.SeeOther,
                  Seq(Location(Uri(samlConfig.failLoginUrl).withQuery(Uri.Query("errorCode" -> "400", "reason" -> "SamlFailed"))))
                )
            }
          } ~ (post & path(loginPath)) {
            optionalHeaderValueByName("Origin") { origin =>
              extractRequest { http =>
                if (!policy.security.exchangeAllowed(origin, Some(http.entity.contentType.toString)))
                  complete(StatusCodes.Forbidden -> "Invalid SAML callback")
                else
                  optionalCookie(SamlBrowserPolicy.nonceCookieName) { nonce =>
                    entity(as[SamlLoginRequest]) { request =>
                      extractLoginHttpRequestContext { context =>
                        complete(samlLoginService.login(request, slug, context, nonce.map(_.value))) {
                          case SamlLoginResult.LoggedIn(tokens) =>
                            HttpResponse(
                              StatusCodes.OK,
                              List(headers.RawHeader("Set-Cookie", policy.clearNonceCookie)),
                              HttpEntity(ContentTypes.`application/json`, tokens.asJson.noSpaces)
                            )
                          case SamlLoginResult.InvitationContinuation(organizationSlug, nextAction, cookieHeader) =>
                            val response =
                              pl.iterators.epoca.saml.services.SamlLoginService.InvitationResponse("invitation", organizationSlug, nextAction)
                            HttpResponse(
                              StatusCodes.OK,
                              List(headers.RawHeader("Set-Cookie", policy.clearNonceCookie), headers.RawHeader("Set-Cookie", cookieHeader)),
                              HttpEntity(ContentTypes.`application/json`, response.asJson.noSpaces)
                            )
                          case SamlLoginResult.InvalidCallback => StatusCodes.NotFound -> "Invalid SAML callback"
                        }
                      }
                    }
                  }
              }
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

object SamlRouter {
  val organizationsPath = "organizations"
  val configPath        = "configs"
  val samlPath          = "saml"
  val acsPath           = "acs"
  val xmlPath           = "metadata.xml"
  val authnPath         = "authn"
  val loginPath         = "login"
}
