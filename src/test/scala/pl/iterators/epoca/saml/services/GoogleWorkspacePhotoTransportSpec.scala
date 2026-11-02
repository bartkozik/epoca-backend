package pl.iterators.epoca.saml.services

import com.sun.net.httpserver.HttpServer
import org.specs2.mutable.Specification

import java.net.InetSocketAddress
import scala.concurrent.duration._

class GoogleWorkspacePhotoTransportSpec extends Specification {
  sequential
  "Workspace HTTP transport" should {
    "bound chunked responses and ignore redirect and error bodies" in {
      val server   = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
      var followed = false
      val _ = server.createContext(
        "/large",
        exchange => {
          exchange.sendResponseHeaders(200, 0L)
          try exchange.getResponseBody.write(new Array[Byte](2048))
          finally exchange.close()
        }
      )
      val _ = server.createContext(
        "/redirect",
        exchange => {
          exchange.getResponseHeaders.add("Location", "/destination")
          exchange.sendResponseHeaders(302, -1L)
          exchange.close()
        }
      )
      val _ = server.createContext(
        "/destination",
        exchange => {
          followed = true
          exchange.sendResponseHeaders(200, -1L)
          exchange.close()
        }
      )
      val _ = server.createContext(
        "/error",
        exchange => {
          exchange.sendResponseHeaders(403, 1000000000L)
          exchange.close()
        }
      )
      server.start()
      try {
        def request(path: String) = GoogleWorkspacePhotoTransport.request(
          s"http://127.0.0.1:${server.getAddress.getPort}$path",
          "GET",
          Map.empty,
          Array.emptyByteArray,
          1024,
          System.nanoTime() + 2.seconds.toNanos
        )
        request("/large") must throwA[java.io.IOException]("Workspace response too large")
        request("/redirect").status mustEqual 302
        followed must beFalse
        val error = request("/error")
        error.status mustEqual 403
        error.body.length must beLessThan(1024)
      } finally server.stop(0)
    }
  }
}
