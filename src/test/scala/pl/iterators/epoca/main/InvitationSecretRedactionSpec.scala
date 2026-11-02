package pl.iterators.epoca.main

import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, StatusCodes}
import org.apache.pekko.http.scaladsl.server.{PathMatcher, Route, ValidationRejection}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.utils.log.TracingContext
import pl.iterators.epoca.utils.{AuthContextProvider, RouteSpec}

import scala.jdk.CollectionConverters._

class InvitationSecretRedactionSpec extends RouteSpec {
  private val sentinel = "secret-sentinel-never-log"
  "invitation logging" should {
    "redact invitation and SAML cookies on ordinary API requests" in new LoggedCase {
      Get("/api/v1/ordinary")
        .withHeaders(RawHeader("Cookie", s"epoca_invitation_access=$sentinel; epoca_saml_nonce=$sentinel")) ~> allRoutes ~> check {
        status shouldEqual StatusCodes.OK
      }
      messages.contains("Request:") shouldEqual true
      messages.contains("/api/v1/ordinary") shouldEqual true
      messages.contains(sentinel) shouldEqual false
    }
    Seq("ordinary", "streaming").foreach { responsePath =>
      s"redact Set-Cookie on $responsePath API responses without changing the response" in new LoggedCase {
        Get(s"/api/v1/$responsePath") ~> allRoutes ~> check {
          status shouldEqual StatusCodes.OK
          header("Set-Cookie").map(_.value()).exists(_.contains(sentinel)) shouldEqual true
        }
        messages.contains("Response:") shouldEqual true
        messages.contains(sentinel) shouldEqual false
      }
    }
    "redact actual request, response, query and headers" in new LoggedCase {
      Post("/api/v1/invitation-access/exchange?token=" + sentinel, requestJson(s"{\"token\":\"$sentinel\"}"))
        .withHeaders(RawHeader("Cookie", "epoca_invitation_access=" + sentinel), RawHeader("X-CSRF-Token", sentinel)) ~> allRoutes ~> check {
        status shouldEqual StatusCodes.OK
      }
      messages.contains(sentinel) shouldEqual false
    }
    "redact invitation exchange and SAML continuation traffic using the actual logger" in new LoggedCase {
      Seq("/api/v1/organizations/acme/invitations/exchange", "/api/v1/organizations/acme/saml/exchange").foreach { path =>
        Post(path + "?token=" + sentinel, requestJson(s"{\"payload\":\"$sentinel\"}"))
          .withHeaders(RawHeader("Cookie", "epoca_invitation_access=" + sentinel), RawHeader("X-CSRF-Token", sentinel)) ~> allRoutes ~> check {
          status shouldEqual StatusCodes.OK
        }
      }
      messages.contains(sentinel) shouldEqual false
    }
    "redact validation rejection details in logs and errors" in new LoggedCase {
      Get("/api/v1/invitation-access/rejected") ~> allRoutes ~> check {
        status shouldEqual StatusCodes.BadRequest
        responseAs[Json].noSpaces.contains(sentinel) shouldEqual false
      }
      messages.contains(sentinel) shouldEqual false
    }
    "redact throwable messages in logs and errors" in new LoggedCase {
      Get("/api/v1/invitation-access/failed") ~> allRoutes ~> check {
        status shouldEqual StatusCodes.InternalServerError
        responseAs[Json].noSpaces.contains(sentinel) shouldEqual false
      }
      messages.contains(sentinel) shouldEqual false
    }
  }
  trait LoggedCase extends BaseScope {
    override val authContextForTest: Some[AuthContext]     = Some(AuthContextProvider.user)
    override lazy val pathPrefixMatcher: PathMatcher[Unit] = Slash ~ "api" / "v1"
    override lazy val logger: org.slf4j.Logger = {
      val result = mock[org.slf4j.Logger]
      when(result.isInfoEnabled).thenReturn(true)
      when(result.isErrorEnabled).thenReturn(true)
      result
    }
    override def route(auth: AuthContext)(implicit tracingContext: TracingContext): Route = {
      val _ = auth
      val _ = tracingContext
      def inner: Route =
        path("exchange")(
          complete(
            HttpResponse(
              StatusCodes.OK,
              List(RawHeader("Set-Cookie", sentinel), RawHeader("Location", sentinel)),
              HttpEntity(ContentTypes.`application/json`, sentinel)
            )
          )
        ) ~
          path("rejected")(reject(ValidationRejection(sentinel))) ~
          path("failed")(throw new RuntimeException(sentinel))
      path("ordinary" | "streaming") {
        extractRequest { request =>
          val entity =
            if (request.uri.path.toString.endsWith("streaming"))
              HttpEntity.Chunked.fromData(
                ContentTypes.`text/plain(UTF-8)`,
                org.apache.pekko.stream.scaladsl.Source.single(org.apache.pekko.util.ByteString("ok"))
              )
            else HttpEntity(ContentTypes.`text/plain(UTF-8)`, "ok")
          complete(HttpResponse(StatusCodes.OK, List(RawHeader("Set-Cookie", s"epoca_invitation_access=$sentinel; HttpOnly")), entity))
        }
      } ~ pathPrefix("invitation-access")(inner) ~ pathPrefix("organizations" / Segment)(_ => pathPrefix("invitations" | "saml")(inner))
    }
    def messages: String =
      mockingDetails(logger).getInvocations.asScala.flatMap(_.getArguments.collect { case value: String => value }).mkString("\n")
  }
}
