package pl.iterators.epoca.utils

import org.apache.pekko.Done
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse, StatusCodes, Uri}
import org.apache.pekko.http.scaladsl.settings.ServerSettings
import pl.iterators.epoca.utils.UriLengthTestSupport.{FormerMaxUriLength, SupportedMaxUriLength, withQuery}

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

class HttpServerUriLengthSpec extends RouteSpec {
  private val timeout = 5.seconds

  "Akka HTTP server URI parser" should {
    "accept a request above the former limit and reject one above the configured limit" in {
      val acceptedTarget = withQuery("/uri-length", "projectId" -> Seq.fill(50)(UUID.randomUUID().toString))
      val rejectedTarget = withQuery("/uri-length", "projectId" -> Seq.fill(360)(UUID.randomUUID().toString))

      (acceptedTarget.length > FormerMaxUriLength) shouldEqual true
      (acceptedTarget.length <= SupportedMaxUriLength) shouldEqual true
      (rejectedTarget.length > SupportedMaxUriLength) shouldEqual true

      val routeInvocations = new AtomicInteger(0)
      val serverSettings   = ServerSettings(system)
      val binding = Await.result(
        Http()
          .newServerAt("127.0.0.1", 0)
          .withSettings(serverSettings)
          .bindSync { _ =>
            val _ = routeInvocations.incrementAndGet()
            HttpResponse(StatusCodes.OK)
          },
        timeout
      )

      try {
        val serverUrl        = s"http://127.0.0.1:${binding.localAddress.getPort}"
        val acceptedResponse = Await.result(Http().singleRequest(HttpRequest(uri = Uri(s"$serverUrl$acceptedTarget"))), timeout)
        Await.result(acceptedResponse.discardEntityBytes().future, timeout) shouldEqual Done

        val rejectedResponse = Await.result(Http().singleRequest(HttpRequest(uri = Uri(s"$serverUrl$rejectedTarget"))), timeout)
        Await.result(rejectedResponse.discardEntityBytes().future, timeout) shouldEqual Done

        acceptedResponse.status shouldEqual StatusCodes.OK
        rejectedResponse.status shouldEqual StatusCodes.UriTooLong
        routeInvocations.get() shouldEqual 1
        serverSettings.parserSettings.maxUriLength shouldEqual SupportedMaxUriLength
      } finally {
        val _ = Await.result(binding.unbind(), timeout)
      }
    }
  }
}
