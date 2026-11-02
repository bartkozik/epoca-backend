package pl.iterators.epoca.utils.http.directives

import org.apache.pekko.http.scaladsl.server.Directives.reject
import org.apache.pekko.http.scaladsl.server.directives.{HeaderDirectives, MiscDirectives}
import org.apache.pekko.http.scaladsl.server.{Directive1, MissingHeaderRejection}
import pl.iterators.epoca.auth.domain.AuthDomain.DeviceId
import pl.iterators.epoca.utils.http.directives.ExtractDeviceIdDirective.extractDeviceIdHeaderName

trait ExtractDeviceIdDirective extends HeaderDirectives with MiscDirectives {
  def extractDeviceId: Directive1[DeviceId] = headerValuePF {
    case header if header.is(extractDeviceIdHeaderName) =>
      DeviceId(header.value)
  } | reject(MissingHeaderRejection(extractDeviceIdHeaderName))
}

object ExtractDeviceIdDirective {
  val extractDeviceIdHeaderName: String = "device-id"

}
