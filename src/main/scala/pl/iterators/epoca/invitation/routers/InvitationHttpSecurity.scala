package pl.iterators.epoca.invitation.routers

import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.server.{Directives, Route}
import pl.iterators.epoca.invitation.services.InvitationBrowserSecurity
import pl.iterators.epoca.oauth.services.TrustedClientIpService
import pl.iterators.epoca.utils.http.Handlers
import pl.iterators.epoca.utils.log.TracingContext

object InvitationHttpSecurity extends Directives with Handlers {
  def trustedIp(request: HttpRequest, trusted: TrustedClientIpService): String =
    request
      .attribute(AttributeKeys.remoteAddress)
      .flatMap(_.toOption)
      .map { peer =>
        val forwarded = request.headers.filter(_.is("x-forwarded-for")).flatMap(_.value().split(',').toVector)
        trusted.resolve(peer, forwarded).getHostAddress
      }
      .getOrElse("missing-socket-peer")

  def browser(security: InvitationBrowserSecurity)(route: => Route)(implicit tracing: TracingContext): Route = extractRequest { request =>
    val origins      = request.headers.filter(_.is("origin")).map(_.value())
    val csrf         = request.headers.filter(_.is("x-csrf-token"))
    val cookies      = request.headers.collect { case value: headers.Cookie => value.cookies }.flatten.count(_.name == "epoca_invitation_access")
    val origin       = origins.headOption
    val foreignFetch = request.headers.exists(h => h.is("sec-fetch-site") && h.value() == "cross-site") && origin.isEmpty
    respondWithHeader(RawHeader("Cache-Control", "no-store")) {
      if (origins.size > 1 || csrf.size > 1 || cookies > 1 || foreignFetch || origin.exists(_ => !security.originAllowed(origin)))
        complete(StatusCodes.Forbidden)
      else {
        val cors = origin.toList.flatMap(value =>
          List(
            RawHeader("Access-Control-Allow-Origin", value),
            RawHeader("Access-Control-Allow-Credentials", "true"),
            RawHeader("Access-Control-Expose-Headers", "Retry-After, Correlation-Id"),
            RawHeader("Vary", "Origin")
          )
        )
        respondWithHeaders(cors) {
          handleExceptions(exceptionHandler(Some(tracing), pass)) {
            handleRejections(rejectionHandler(Some(tracing), pass)) {
              options {
                if (origin.isEmpty) complete(StatusCodes.Forbidden)
                else
                  respondWithHeaders(
                    RawHeader("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS"),
                    RawHeader("Access-Control-Allow-Headers", "Content-Type, X-CSRF-Token, Authorization, Device-Id, Correlation-Id")
                  )(complete(StatusCodes.NoContent))
              } ~ route
            }
          }
        }
      }
    }
  }
}
