package pl.iterators.epoca.oauth.routers

import cats.effect.unsafe.IORuntime
import org.apache.pekko.http.scaladsl.model.headers.{Authorization, BasicHttpCredentials, RawHeader}
import org.apache.pekko.http.scaladsl.model.{ContentTypes, FormData, HttpEntity, StatusCodes}
import org.apache.pekko.http.scaladsl.server.{PathMatcher, Route}
import org.mockito.Mockito.verifyNoInteractions
import pl.iterators.epoca.oauth.services.OAuthTokenService.OAuthTokenResponse
import pl.iterators.epoca.oauth.services.OAuthTokenService.OAuthTokenResult.{Issued, _}
import pl.iterators.epoca.oauth.services.{OAuthTokenIssuer, TrustedClientIpService}
import pl.iterators.epoca.utils.RouteSpec
import pl.iterators.epoca.utils.log.TracingContext

import java.net.InetAddress
import scala.jdk.CollectionConverters._

class PostOAuthTokenRouteSpec extends RouteSpec {
  private implicit val runtime: IORuntime             = IORuntime.global
  private implicit val tracingContext: TracingContext = TracingContext("context", "oauth-token-route", Map.empty)

  "POST /oauth/token" should {
    "return a no-store bearer response for Basic client credentials and an exact form" in {
      val service       = mock[OAuthTokenIssuer]
      val tokenResponse = OAuthTokenResponse("header.payload.signature", scope = "projects:read")
      when(service.issue(any, any, any[InetAddress], any)).thenReturn(io(Issued(tokenResponse)))
      val route = Route.seal(new OAuthTokenRouter(service, new TrustedClientIpService(Vector.empty)).routes)
      val request = Post("/oauth/token", FormData("grant_type" -> "client_credentials", "scope" -> "projects:read").toEntity)
        .withHeaders(Authorization(BasicHttpCredentials("epoca_oauth_client_v1_AAAAAAAAAAAAAAAAAAAAAA", "epoca_oauth_secret_v1_secret")))

      request ~> route ~> check {
        response.status shouldEqual StatusCodes.OK
        responseAs[Json].hcursor.get[String]("access_token") shouldEqual Right("header.payload.signature")
        responseAs[Json].hcursor.get[String]("token_type") shouldEqual Right("Bearer")
        responseAs[Json].hcursor.get[Int]("expires_in") shouldEqual Right(300)
        responseAs[Json].hcursor.get[String]("scope") shouldEqual Right("projects:read")
        responseAs[Json].hcursor.downField("refresh_token").succeeded should beFalse
        header("Cache-Control").map(_.value()) shouldEqual Some("no-store")
        header("Pragma").map(_.value()) shouldEqual Some("no-cache")
        header("Access-Control-Allow-Origin") shouldEqual None
        response.toString must not(contain("header.payload.signature"))
      }

      verify(service).issue(any, eqTo(Some("projects:read")), any[InetAddress], any)
      1 shouldEqual 1
    }

    "reject a non-form body and non-client-credentials grant before authentication" in {
      val service = mock[OAuthTokenIssuer]
      val route   = Route.seal(new OAuthTokenRouter(service, new TrustedClientIpService(Vector.empty)).routes)

      Post("/oauth/token", requestJson("{\"grant_type\":\"client_credentials\"}")) ~> route ~> check {
        response.status shouldEqual StatusCodes.BadRequest
        responseAs[Json].hcursor.get[String]("error") shouldEqual Right("invalid_request")
        assertNoStoreHeaders()
      }
      Post("/oauth/token", FormData("grant_type" -> "password").toEntity) ~> route ~> check {
        response.status shouldEqual StatusCodes.BadRequest
        responseAs[Json].hcursor.get[String]("error") shouldEqual Right("unsupported_grant_type")
        assertNoStoreHeaders()
      }

      verifyNoInteractions(service)
      1 shouldEqual 1
    }

    "return invalid_request for unknown form fields and malformed percent encoding" in {
      val service = mock[OAuthTokenIssuer]
      val route   = Route.seal(new OAuthTokenRouter(service, new TrustedClientIpService(Vector.empty)).routes)

      Post("/oauth/token", FormData("grant_type" -> "client_credentials", "unexpected" -> "value").toEntity) ~> route ~> check {
        response.status shouldEqual StatusCodes.BadRequest
        responseAs[Json].hcursor.get[String]("error") shouldEqual Right("invalid_request")
        assertNoStoreHeaders()
      }
      Post("/oauth/token", HttpEntity(ContentTypes.`application/x-www-form-urlencoded`, "grant_type=client_credentials&scope=%")) ~> route ~> check {
        response.status shouldEqual StatusCodes.BadRequest
        responseAs[Json].hcursor.get[String]("error") shouldEqual Right("invalid_request")
        assertNoStoreHeaders()
      }

      verifyNoInteractions(service)
      1 shouldEqual 1
    }

    "challenge every invalid client through the same redacted Basic boundary" in {
      val service = mock[OAuthTokenIssuer]
      when(service.issue(any, any, any[InetAddress], any)).thenReturn(io(InvalidClient))
      val route = Route.seal(new OAuthTokenRouter(service, new TrustedClientIpService(Vector.empty)).routes)

      Post("/oauth/token", FormData("grant_type" -> "client_credentials").toEntity) ~> route ~> check {
        response.status shouldEqual StatusCodes.Unauthorized
        responseAs[Json] shouldEqualJson """{"error":"invalid_client"}"""
        header("WWW-Authenticate").map(_.value()) shouldEqual Some("Basic realm=\"oauth\"")
        assertNoStoreHeaders()
      }
    }

    "keep expired and revoked lifecycle state outside the public invalid-client response" in {
      val service = mock[OAuthTokenIssuer]
      when(service.issue(any, any, any[InetAddress], any)).thenReturn(io(InvalidClient), io(InvalidClient))
      val route = Route.seal(new OAuthTokenRouter(service, new TrustedClientIpService(Vector.empty)).routes)

      Seq("expired", "revoked").foreach { label =>
        tokenRequest ~> route ~> check {
          response.status shouldEqual StatusCodes.Unauthorized
          responseAs[Json] shouldEqualJson """{"error":"invalid_client"}"""
          response.entity.toString.toLowerCase must not(contain(label))
          headers.map(header => header.name.toLowerCase -> header.value.toLowerCase).mkString("\n") must not(contain(label))
          header("WWW-Authenticate").map(_.value()) shouldEqual Some("Basic realm=\"oauth\"")
          assertNoStoreHeaders()
        }
      }

      verify(service, times(2)).issue(any, any, any[InetAddress], any)
      1 shouldEqual 1
    }

    "map invalid scope and unavailable state without exposing CORS or cacheable responses" in {
      val invalidScopeService = mock[OAuthTokenIssuer]
      when(invalidScopeService.issue(any, any, any[InetAddress], any)).thenReturn(io(InvalidScope))
      val invalidScopeRoute = Route.seal(new OAuthTokenRouter(invalidScopeService, new TrustedClientIpService(Vector.empty)).routes)

      tokenRequest ~> invalidScopeRoute ~> check {
        response.status shouldEqual StatusCodes.BadRequest
        responseAs[Json] shouldEqualJson """{"error":"invalid_scope"}"""
        assertNoStoreHeaders()
      }

      val unavailableService = mock[OAuthTokenIssuer]
      when(unavailableService.issue(any, any, any[InetAddress], any)).thenReturn(io(TemporarilyUnavailable))
      val unavailableRoute = Route.seal(new OAuthTokenRouter(unavailableService, new TrustedClientIpService(Vector.empty)).routes)

      tokenRequest ~> unavailableRoute ~> check {
        response.status shouldEqual StatusCodes.ServiceUnavailable
        responseAs[Json] shouldEqualJson """{"error":"temporarily_unavailable"}"""
        assertNoStoreHeaders()
      }
    }

    "return the bounded retry delay for distributed rate limits" in {
      val service = mock[OAuthTokenIssuer]
      when(service.issue(any, any, any[InetAddress], any)).thenReturn(io(RateLimited(17)))
      val route = Route.seal(new OAuthTokenRouter(service, new TrustedClientIpService(Vector.empty)).routes)

      tokenRequest ~> route ~> check {
        response.status shouldEqual StatusCodes.TooManyRequests
        responseAs[Json] shouldEqualJson """{"error":"rate_limited"}"""
        header("Retry-After").map(_.value()) shouldEqual Some("17")
        assertNoStoreHeaders()
      }
    }

    "be public only when enabled and redact the full token exchange from application logs" in new EnabledLoggingTestCase {
      val clientSentinel = "epoca_oauth_client_v1_oauth-client-log-sentinel"
      val secretSentinel = "epoca_oauth_secret_v1_oauth-secret-log-sentinel"
      val scopeSentinel  = "oauth-scope-log-sentinel"
      val tokenSentinel  = "oauth-token-log-sentinel"
      when(oauthTokenIssuer.issue(any, any, any[InetAddress], any))
        .thenReturn(io(Issued(OAuthTokenResponse(tokenSentinel, scope = scopeSentinel))), io(InvalidClient))
      val request = Post("/api/v1/oauth/token", FormData("grant_type" -> "client_credentials", "scope" -> scopeSentinel).toEntity)
        .withHeaders(Authorization(BasicHttpCredentials(clientSentinel, secretSentinel)))

      request ~> allRoutes ~> check(response.status shouldEqual StatusCodes.OK)
      request ~> allRoutes ~> check(response.status shouldEqual StatusCodes.Unauthorized)

      val logs = loggedMessages
      verify(oauthTokenIssuer, times(2)).issue(any, eqTo(Some(scopeSentinel)), any[InetAddress], any)
      (logs.contains(clientSentinel) should beFalse) and
        (logs.contains(secretSentinel) should beFalse) and
        (logs.contains(scopeSentinel) should beFalse) and
        (logs.contains(tokenSentinel) should beFalse) and
        ("""entity=\[[^\]]*\*\*\*\]""".r.findFirstIn(logs).isDefined should beTrue)
    }

    "be unavailable without evaluating token dependencies when OAuth is disabled" in new DisabledApplicationTestCase {
      Post("/api/v1/oauth/token", FormData("grant_type" -> "client_credentials").toEntity) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }
    }
  }

  trait EnabledLoggingTestCase extends BaseScope {
    override protected def oauthTokenEnabled: Boolean                = true
    override lazy val oauthTokenIssuer: OAuthTokenIssuer             = vmock[OAuthTokenIssuer]
    override lazy val trustedClientIpService: TrustedClientIpService = new TrustedClientIpService(Vector.empty)
    override val authContextForTest                                  = None
    override lazy val pathPrefixMatcher: PathMatcher[Unit]           = Slash ~ "api" / "v1"
    override lazy val logger: org.slf4j.Logger = {
      val captured = mock[org.slf4j.Logger]
      when(captured.isInfoEnabled).thenReturn(true)
      captured
    }

    def loggedMessages: String =
      mockingDetails(logger).getInvocations.asScala.flatMap(_.getArguments.collect { case value: String => value }).mkString("\n")
  }

  trait DisabledApplicationTestCase extends BaseScope {
    override protected def oauthTokenEnabled: Boolean = false
    override lazy val oauthTokenIssuer: OAuthTokenIssuer =
      throw new AssertionError("disabled token routes must not evaluate token dependencies")
    override lazy val trustedClientIpService: TrustedClientIpService =
      throw new AssertionError("disabled token routes must not evaluate proxy dependencies")
    override val authContextForTest                        = None
    override lazy val pathPrefixMatcher: PathMatcher[Unit] = Slash ~ "api" / "v1"
  }

  private def tokenRequest =
    Post("/oauth/token", FormData("grant_type" -> "client_credentials", "scope" -> "projects:read").toEntity)
      .withHeaders(
        Authorization(BasicHttpCredentials("epoca_oauth_client_v1_AAAAAAAAAAAAAAAAAAAAAA", "epoca_oauth_secret_v1_secret")),
        RawHeader("X-Forwarded-For", "198.51.100.21")
      )

  private def assertNoStoreHeaders() = {
    header("Cache-Control").map(_.value()) shouldEqual Some("no-store")
    header("Pragma").map(_.value()) shouldEqual Some("no-cache")
    header("Access-Control-Allow-Origin") shouldEqual None
  }
}
