package pl.iterators.epoca.invitation.routers

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.model.headers.{Cookie, RawHeader}
import pl.iterators.epoca.invitation.repositories.InvitationAccessRepository
import pl.iterators.epoca.invitation.services._
import pl.iterators.epoca.oauth.services.TrustedClientIpService
import pl.iterators.epoca.saml.services.{SamlAuthnService, SamlInvitationService}
import pl.iterators.epoca.utils.RouteSpec
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.log.TracingContext
import pl.iterators.epoca.utils.transactor.FakeTransactor
import slick.dbio.DBIO

import java.net.InetAddress
import java.time.{Clock, Instant, ZoneOffset}

class InvitationAccessRouterSpec extends RouteSpec {
  implicit val tracing: TracingContext = TracingContext("test", "test", Map.empty)
  private val origin                   = "https://app.example.test"
  private val now                      = Instant.parse("2026-10-05T10:00:00Z")
  private val clock                    = Clock.fixed(now, ZoneOffset.UTC)
  private val secrets                  = new InvitationSecretService
  private val browser                  = new InvitationBrowserSecurity(Set(origin), "/api/v1", false, false, secrets)
  private val trusted                  = new TrustedClientIpService(Vector.empty)
  private val grant = InvitationAccessService.Granted(
    "cookie_secret",
    InvitationAccessService.SafeAccessDto(
      InvitationAccessService.SafeOrganization("Acme", "acme"),
      "recipient@example.test",
      Some("Anna"),
      Some("Test"),
      Seq("Employee"),
      "0",
      now.plusSeconds(900),
      "csrf_secret",
      "SignIn",
      true
    )
  )

  private class OutcomeTx(result: Any) extends PostgresIOTransactor(null) {
    override def executeTransactionally[A](action: => DBIO[A]): IO[A] = IO.pure(result.asInstanceOf[A])
  }
  private def router(
    accessResult: InvitationAccessService.Result = grant,
    continuation: Option[String] = Some("Accept"),
    activationResult: InvitationActivationService.Result = InvitationActivationService.SessionIssueFailed(),
    allowRate: Boolean = true
  ) = {
    val accesses = mock[InvitationAccessRepository]
    when(accesses.findByCookieHash(any)).thenReturn(DBIO.successful(None))
    when(accesses.consumeRateBucket(any, any, any, any, any)).thenReturn(DBIO.successful(allowRate))
    val limits     = new InvitationRateLimitService(new FakeTransactor, accesses, Array.fill[Byte](32)(1), clock)
    val access     = new InvitationAccessService(new OutcomeTx(accessResult), null, accesses, null, secrets, browser, limits, clock)
    val activation = new InvitationActivationService(new OutcomeTx(activationResult), access, null, null, null, null, limits, browser, clock)
    val saml       = mock[SamlInvitationService]
    when(saml.classifyContinuationAction(any)).thenReturn(DBIO.successful(continuation))
    new InvitationAccessRouter(access, activation, browser, saml, mock[SamlAuthnService], None, new FakeTransactor, trusted)
  }
  "public invitation API" should {
    "compose enabled public access and SAML services in the actual application loader" in new BaseScope {
      override val authContextForTest = None
      override lazy val invitationConfig: InvitationConfig = InvitationConfig.fromConfig(
        com.typesafe.config.ConfigFactory
          .parseString("""invitation {
          featureEnabled=true
          allowedOrigins=["https://app.example.test"]
          rateKey="01234567890123456789012345678901"
        }""")
          .withFallback(testConfig)
      )
      Get("/invitation-access") ~> allRoutes ~> check {
        status shouldEqual StatusCodes.NotFound
        responseAs[Json].hcursor.get[String]("type") shouldEqual Right("result:invitation:invalid-invitation")
      }
      Post("/invitation-access/exchange", requestJson("malformed-json-secret")).withHeaders(RawHeader("Origin", origin)) ~> allRoutes ~> check {
        status shouldEqual StatusCodes.BadRequest
        header("Cache-Control").map(_.value()) shouldEqual Some("no-store")
        header("Access-Control-Allow-Origin").map(_.value()) shouldEqual Some(origin)
        header("Access-Control-Allow-Credentials").map(_.value()) shouldEqual Some("true")
        responseAs[Json].noSpaces.contains("malformed-json-secret") shouldEqual false
      }
      Post("/invitation-access/exchange", requestJson("{\"token\":\"invalid\"}")) ~> allRoutes ~> check {
        status shouldEqual StatusCodes.Forbidden
        header("Cache-Control").map(_.value()) shouldEqual Some("no-store")
      }
    }

    "preserve validated SAML next actions on summary reload and return only the access projection" in {
      Seq("Accept", "VerifyLegacySso").foreach { next =>
        Get("/invitation-access").withHeaders(Cookie("epoca_invitation_access", "cookie_secret")) ~> router(continuation = Some(next))
          .routes(None) ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[Json].hcursor.get[String]("nextAction") shouldEqual Right(next)
          responseAs[Json].asObject.get.keys.toSet shouldEqual Set(
            "organization",
            "email",
            "firstName",
            "lastName",
            "roles",
            "revision",
            "expiresAt",
            "csrfToken",
            "nextAction",
            "ssoEnabled"
          )
          header("Cache-Control").map(_.value()) shouldEqual Some("no-store")
          val cookie = header("Set-Cookie").get.value()
          cookie.contains("HttpOnly") shouldEqual true
          cookie.contains("Secure") shouldEqual true
          cookie.contains("SameSite=Lax") shouldEqual true
          cookie.contains("Path=/api/v1") shouldEqual true
          cookie.contains("Domain=") shouldEqual false
        }
      }
      success
    }
    "support the actual frontend preflight headers with exact credentialed CORS" in {
      Options("/invitation-access").withHeaders(
        RawHeader("Origin", origin),
        RawHeader("Access-Control-Request-Method", "GET"),
        RawHeader("Access-Control-Request-Headers", "authorization,content-type,correlation-id,device-id,x-csrf-token")
      ) ~> router().routes(None) ~> check {
        status shouldEqual StatusCodes.NoContent
        header("Access-Control-Allow-Origin").map(_.value()) shouldEqual Some(origin)
        header("Access-Control-Allow-Credentials").map(_.value()) shouldEqual Some("true")
        header("Access-Control-Allow-Headers").get.value().toLowerCase must contain("correlation-id")
        header("Access-Control-Expose-Headers").get.value().toLowerCase must contain("retry-after")
      }
    }
    "reject foreign and duplicate Origin or CSRF headers before any context work" in {
      Seq(
        List(RawHeader("Origin", "https://foreign.example")),
        List(RawHeader("Origin", origin), RawHeader("Origin", origin)),
        List(RawHeader("Origin", origin), RawHeader("X-CSRF-Token", "a"), RawHeader("X-CSRF-Token", "b")),
        List(RawHeader("Sec-Fetch-Site", "cross-site"))
      ).foreach { headers =>
        Get("/invitation-access").withHeaders(headers) ~> router().routes(None) ~> check {
          status shouldEqual StatusCodes.Forbidden
          header("Access-Control-Allow-Origin") shouldEqual None
        }
      }
      success
    }
    "return the same safe missing-context response without cookie or for an invalid context" in {
      Get("/invitation-access") ~> router().routes(None) ~> check(status shouldEqual StatusCodes.NotFound)
      Get("/invitation-access")
        .withHeaders(Cookie("epoca_invitation_access", "invalid")) ~> router(InvitationAccessService.InvalidInvitation).routes(None) ~> check {
        status shouldEqual StatusCodes.NotFound
        responseAs[Json].noSpaces.contains("invalid") shouldEqual true
        header("Cache-Control").map(_.value()) shouldEqual Some("no-store")
      }
    }
    "reject an unauthenticated bearer instead of using a cookie fallback" in {
      Get("/invitation-access").withHeaders(
        RawHeader("Authorization", "Bearer invalid"),
        Cookie("epoca_invitation_access", "cookie_secret")
      ) ~> router().routes(None) ~> check {
        status shouldEqual StatusCodes.Unauthorized
      }
    }
    "return a committed rate denial with Retry-After before token lookup" in {
      Post("/invitation-access/exchange", requestJson("{\"token\":\"bad\"}")).withHeaders(RawHeader("Origin", origin)) ~> router(allowRate = false)
        .routes(None) ~> check {
        status shouldEqual StatusCodes.TooManyRequests
        header("Retry-After").map(_.value()) shouldEqual Some("60")
      }
    }
    "clear the invitation cookie after successful session issuance" in {
      val joined = InvitationActivationService.SessionJoined(
        "acme",
        pl.iterators.epoca.auth.domain.AuthDomain.Jwt("access"),
        pl.iterators.epoca.auth.domain.AuthDomain.Jwt("refresh")
      )
      Post("/invitation-access/accept", requestJson("{\"expectedRevision\":\"0\"}")).withHeaders(
        Cookie("epoca_invitation_access", "cookie_secret"),
        RawHeader("Origin", origin),
        RawHeader("X-CSRF-Token", "csrf_secret"),
        RawHeader("Device-Id", "browser")
      ) ~> router(activationResult = joined).routes(None) ~> check {
        status shouldEqual StatusCodes.OK
        header("Set-Cookie").map(_.value()) shouldEqual Some(browser.clearCookie)
        responseAs[Json].hcursor.get[String]("organizationSlug") shouldEqual Right("acme")
      }
    }
    "return a controlled joined response when post-commit session issuance fails" in {
      Post("/invitation-access/accept", requestJson("{\"expectedRevision\":\"0\"}")).withHeaders(
        Cookie("epoca_invitation_access", "cookie_secret"),
        RawHeader("Origin", origin),
        RawHeader("X-CSRF-Token", "csrf_secret"),
        RawHeader("Device-Id", "browser")
      ) ~> router().routes(None) ~> check {
        status shouldEqual StatusCodes.ServiceUnavailable
        responseAs[Json].hcursor.get[Boolean]("joined") shouldEqual Right(true)
        responseAs[Json].hcursor.get[String]("accessToken").isLeft shouldEqual true
      }
    }
    "preserve safe named activation conflicts" in {
      Seq(InvitationActivationService.AlreadyMember -> "already-member", InvitationActivationService.IdentityConflict -> "identity-conflict")
        .foreach { case (result, code) =>
          Post("/invitation-access/accept", requestJson("{\"expectedRevision\":\"0\"}")).withHeaders(
            Cookie("epoca_invitation_access", "cookie_secret"),
            RawHeader("Origin", origin),
            RawHeader("X-CSRF-Token", "csrf_secret"),
            RawHeader("Device-Id", "browser")
          ) ~> router(activationResult = result).routes(None) ~> check {
            status shouldEqual StatusCodes.Conflict
            responseAs[Json].hcursor.get[String]("type") shouldEqual Right("result:invitation:" + code)
          }
        }
      success
    }
    "use only server remote-address attributes and ignore spoofed forwarding from untrusted peers" in {
      val forged = Get("/invitation-access/exchange").withHeaders(RawHeader("X-Forwarded-For", "192.0.2.9"), RawHeader("X-Real-IP", "192.0.2.10"))
      InvitationHttpSecurity.trustedIp(forged, trusted) shouldEqual "missing-socket-peer"
      InvitationHttpSecurity.trustedIp(
        forged.addAttribute(AttributeKeys.remoteAddress, RemoteAddress(InetAddress.getByName("192.0.2.1"))),
        trusted
      ) shouldEqual "192.0.2.1"
    }
  }
}
