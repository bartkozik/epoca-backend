package pl.iterators.epoca.oauth.routers

import cats.effect.unsafe.IORuntime
import io.circe.Json
import org.apache.pekko.http.scaladsl.marshalling.ToResponseMarshallable
import org.apache.pekko.http.scaladsl.model.headers.{Authorization, BasicHttpCredentials, RawHeader}
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, MediaTypes, StatusCodes, Uri}
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.unmarshalling.{FromEntityUnmarshaller, Unmarshaller}
import pl.iterators.epoca.oauth.domain.OAuthDomain.{OAuthAuditCorrelationId, OAuthClientCredentials, OAuthClientPublicId, OAuthClientSecret}
import pl.iterators.epoca.oauth.services.OAuthTokenService.OAuthTokenResult._
import pl.iterators.epoca.oauth.services.OAuthTokenService.{OAuthTokenResponse, OAuthTokenResult}
import pl.iterators.epoca.oauth.services.{OAuthTokenIssuer, TrustedClientIpService}
import pl.iterators.epoca.utils.http.BaseRouter
import pl.iterators.epoca.utils.log.TracingContext

import java.net.InetAddress
import scala.util.Try

final class OAuthTokenRouter(tokenService: OAuthTokenIssuer, trustedClientIpService: TrustedClientIpService)(implicit runtime: IORuntime)
    extends BaseRouter {

  private implicit val rawEntityUnmarshaller: FromEntityUnmarshaller[String] =
    Unmarshaller.byteStringUnmarshaller.map(_.utf8String)

  def routes(implicit tracingContext: TracingContext): Route =
    path("oauth" / "token") {
      post {
        respondWithHeaders(RawHeader("Cache-Control", "no-store"), RawHeader("Pragma", "no-cache")) {
          extractRequest { request =>
            extractClientIP { remoteAddress =>
              optionalHeaderValueByName("X-Forwarded-For") { forwardedFor =>
                optionalHeaderValueByType(Authorization) { authorization =>
                  entity(as[String]) { rawForm =>
                    val credentials = authorization.collect { case Authorization(BasicHttpCredentials(clientId, clientSecret)) =>
                      OAuthClientCredentials(OAuthClientPublicId(clientId), OAuthClientSecret(clientSecret))
                    }
                    val result = parseForm(request.entity.contentType.mediaType, rawForm) match {
                      case Right(scope) =>
                        tokenService.issue(
                          credentials,
                          scope,
                          effectiveAddress(remoteAddress.toOption, forwardedFor),
                          Option(tracingContext.correlationId).map(OAuthAuditCorrelationId.apply)
                        )
                      case Left(error) => cats.effect.IO.pure(error)
                    }
                    complete(result)(tokenResponse)
                  }
                }
              }
            }
          }
        }
      }
    }

  private def parseForm(mediaType: org.apache.pekko.http.scaladsl.model.MediaType, rawForm: String): Either[OAuthTokenResult, Option[String]] =
    if (mediaType != MediaTypes.`application/x-www-form-urlencoded`) Left(InvalidRequest)
    else
      Try(Uri.Query(rawForm).toMultiMap).toEither.left
        .map(_ => InvalidRequest: OAuthTokenResult)
        .flatMap { fields =>
          fields.get("grant_type") match {
            case Some(Seq("client_credentials")) if fields.keySet.subsetOf(Set("grant_type", "scope")) =>
              fields.get("scope") match {
                case None             => Right(None)
                case Some(Seq(scope)) => Right(Some(scope))
                case _                => Left(InvalidRequest)
              }
            case Some(Seq("client_credentials")) => Left(InvalidRequest)
            case Some(Seq(_))                    => Left(UnsupportedGrantType)
            case _                               => Left(InvalidRequest)
          }
        }

  private def effectiveAddress(socketPeer: Option[InetAddress], forwardedFor: Option[String]): InetAddress = {
    val peer = socketPeer.getOrElse(InetAddress.getLoopbackAddress)
    trustedClientIpService.resolve(peer, forwardedFor.toSeq.flatMap(_.split(',').map(_.trim)))
  }

  private def tokenResponse(result: OAuthTokenResult): ToResponseMarshallable =
    result match {
      case Issued(response)     => jsonResponse(StatusCodes.OK, successJson(response))
      case InvalidRequest       => jsonResponse(StatusCodes.BadRequest, Json.obj("error" -> Json.fromString("invalid_request")))
      case UnsupportedGrantType => jsonResponse(StatusCodes.BadRequest, Json.obj("error" -> Json.fromString("unsupported_grant_type")))
      case InvalidScope         => jsonResponse(StatusCodes.BadRequest, Json.obj("error" -> Json.fromString("invalid_scope")))
      case InvalidClient =>
        jsonResponse(StatusCodes.Unauthorized, Json.obj("error" -> Json.fromString("invalid_client")))
          .withHeaders(RawHeader("WWW-Authenticate", "Basic realm=\"oauth\""))
      case RateLimited(retryAfterSeconds) =>
        jsonResponse(StatusCodes.TooManyRequests, Json.obj("error" -> Json.fromString("rate_limited")))
          .withHeaders(RawHeader("Retry-After", math.max(1L, retryAfterSeconds).toString))
      case TemporarilyUnavailable => jsonResponse(StatusCodes.ServiceUnavailable, Json.obj("error" -> Json.fromString("temporarily_unavailable")))
    }

  private def successJson(response: OAuthTokenResponse): Json =
    Json.obj(
      "access_token" -> Json.fromString(response.access_token),
      "token_type"   -> Json.fromString(response.token_type),
      "expires_in"   -> Json.fromInt(response.expires_in),
      "scope"        -> Json.fromString(response.scope)
    )

  private def jsonResponse(status: org.apache.pekko.http.scaladsl.model.StatusCode, json: Json): HttpResponse =
    HttpResponse(status = status, entity = HttpEntity(ContentTypes.`application/json`, json.noSpaces))
}
