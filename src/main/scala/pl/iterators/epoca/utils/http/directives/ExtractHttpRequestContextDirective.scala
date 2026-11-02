package pl.iterators.epoca.utils.http.directives

import org.apache.pekko.http.scaladsl.model.RemoteAddress
import org.apache.pekko.http.scaladsl.model.Uri.Host
import org.apache.pekko.http.scaladsl.model.headers.{Origin, Referer, `User-Agent`}
import org.apache.pekko.http.scaladsl.server.Directive1
import org.apache.pekko.http.scaladsl.server.directives.{HeaderDirectives, MiscDirectives}
import pl.iterators.epoca.utils.http.directives.ExtractHttpRequestContextDirective.HttpRequestContext

trait ExtractHttpRequestContextDirective extends HeaderDirectives with MiscDirectives {
  def extractHttpRequestContext: Directive1[HttpRequestContext] =
    extractClientIP.flatMap { clientIp =>
      optionalHeaderValueByType(Origin).flatMap { origin =>
        optionalHeaderValueByType(Referer).flatMap { referer =>
          optionalHeaderValueByType(`User-Agent`).map { userAgent =>
            HttpRequestContext(clientIp, origin, userAgent, referer)
          }
        }
      }
    }
}

object ExtractHttpRequestContextDirective {
  final case class HttpRequestContext(
    clientIp: RemoteAddress,
    origin: Option[Origin],
    userAgent: Option[`User-Agent`],
    referer: Option[Referer]) {
    def hostOption: Option[Host] =
      origin.flatMap(_.origins.headOption.map(_.host.host)).orElse(referer.map(_.uri.authority.host))
  }
}
