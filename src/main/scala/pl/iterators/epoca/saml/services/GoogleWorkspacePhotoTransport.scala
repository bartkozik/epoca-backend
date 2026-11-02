package pl.iterators.epoca.saml.services

import cats.effect.IO
import com.google.api.client.http.{HttpTransport, LowLevelHttpRequest, LowLevelHttpResponse}
import com.google.auth.oauth2.ServiceAccountCredentials
import pl.iterators.epoca.saml.services.WorkspaceDirectoryPhotoProvider.{Response, Tenant, Transport, maxResponseBytes}

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, IOException, InputStream}
import java.net.{HttpURLConnection, URI, URLEncoder}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Paths}
import scala.concurrent.duration._

/** Fixed Google endpoints, no redirects or retries. The token exchange is bounded too, not only the photo GET. */
final class GoogleWorkspacePhotoTransport(maxBytes: Int) extends Transport {
  import GoogleWorkspacePhotoTransport._

  override def fetch(tenant: Tenant, email: String): IO[Response] = IO.interruptibleMany {
    val deadline       = System.nanoTime() + 20.seconds.toNanos
    val tokenTransport = new TokenTransport(deadline)
    val path           = Paths.get(tenant.credentialsFile)
    if (!Files.isRegularFile(path)) throw new IOException("Workspace credentials unavailable")
    val input = Files.newInputStream(path)
    val credentialBytes =
      try input.readNBytes(65537)
      finally input.close()
    if (credentialBytes.length > 65536) throw new IOException("Workspace credentials too large")
    val loaded = ServiceAccountCredentials.fromStream(new ByteArrayInputStream(credentialBytes))
    val credentials = loaded.toBuilder
      .setTokenServerUri(URI.create(TokenEndpoint))
      .setHttpTransportFactory(() => tokenTransport)
      .setScopes(java.util.Collections.singleton("https://www.googleapis.com/auth/admin.directory.user.readonly"))
      .setServiceAccountUser(tenant.delegatedAdmin)
      .setDefaultRetriesEnabled(false)
      .setUseJwtAccessWithScope(false)
      .build()
    val token   = credentials.refreshAccessToken().getTokenValue
    val encoded = URLEncoder.encode(email, UTF_8.name()).replace("+", "%20")
    request(
      s"https://admin.googleapis.com/admin/directory/v1/users/$encoded/photos/thumbnail",
      "GET",
      Map("Authorization" -> s"Bearer $token", "Accept" -> "application/json"),
      Array.emptyByteArray,
      maxResponseBytes(maxBytes),
      deadline
    )
  }
}

private[services] object GoogleWorkspacePhotoTransport {
  private val TokenEndpoint = "https://oauth2.googleapis.com/token"

  private def remainingMillis(deadline: Long): Int = {
    val remaining = (deadline - System.nanoTime()) / 1000000L
    if (remaining <= 0) throw new IOException("Workspace request deadline exceeded")
    math.min(remaining, 5000L).toInt
  }

  private[services] def request(
    url: String,
    method: String,
    headers: Map[String, String],
    body: Array[Byte],
    maxBytes: Int,
    deadline: Long
  ): Response = {
    val connection = URI.create(url).toURL.openConnection().asInstanceOf[HttpURLConnection]
    try {
      connection.setInstanceFollowRedirects(false)
      connection.setConnectTimeout(remainingMillis(deadline))
      connection.setReadTimeout(remainingMillis(deadline))
      connection.setRequestMethod(method)
      headers.foreach { case (key, value) => connection.setRequestProperty(key, value) }
      if (body.nonEmpty) {
        connection.setDoOutput(true)
        connection.setFixedLengthStreamingMode(body.length)
        val output = connection.getOutputStream
        try output.write(body)
        finally output.close()
      }
      val status = connection.getResponseCode
      // Never buffer error bodies, including unbounded error pages or sensitive Google error details.
      if (status != 200) Response(status, "{}".getBytes(UTF_8))
      else {
        if (connection.getContentLengthLong > maxBytes.toLong) throw new IOException("Workspace response too large")
        val input = connection.getInputStream
        try {
          val output = new ByteArrayOutputStream()
          val buffer = new Array[Byte](8192)
          var done   = false
          while (!done) {
            connection.setReadTimeout(remainingMillis(deadline))
            val count = input.read(buffer, 0, math.min(buffer.length, maxBytes + 1 - output.size()))
            if (count < 0) done = true
            else {
              output.write(buffer, 0, count)
              if (output.size() > maxBytes) throw new IOException("Workspace response too large")
            }
          }
          Response(status, output.toByteArray)
        } finally input.close()
      }
    } finally connection.disconnect()
  }

  /** The Google SDK uses this transport for signed JWT exchange; endpoint and response allocation stay under our control. */
  private final class TokenTransport(deadline: Long) extends HttpTransport {
    override protected def buildRequest(method: String, url: String): LowLevelHttpRequest = {
      if (method != "POST" || url != TokenEndpoint) throw new IOException("Unexpected Workspace token endpoint")
      new LowLevelHttpRequest {
        private var headers                                       = Map.empty[String, String]
        override def addHeader(name: String, value: String): Unit = headers = headers.updated(name, value)
        override def execute(): LowLevelHttpResponse = {
          val body = new ByteArrayOutputStream()
          Option(getStreamingContent).foreach(_.writeTo(body))
          if (body.size() > 65536) throw new IOException("Workspace token request too large")
          val response = request(url, method, headers ++ Option(getContentType).map("Content-Type" -> _), body.toByteArray, 65536, deadline)
          new LowLevelHttpResponse {
            override def getContent: InputStream            = new ByteArrayInputStream(response.body)
            override def getContentEncoding: String         = null
            override def getContentLength: Long             = response.body.length.toLong
            override def getContentType: String             = "application/json"
            override def getStatusLine: String              = s"HTTP/1.1 ${response.status}"
            override def getStatusCode: Int                 = response.status
            override def getReasonPhrase: String            = ""
            override def getHeaderCount: Int                = 0
            override def getHeaderName(index: Int): String  = throw new IndexOutOfBoundsException(index.toString)
            override def getHeaderValue(index: Int): String = throw new IndexOutOfBoundsException(index.toString)
          }
        }
      }
    }
  }
}
