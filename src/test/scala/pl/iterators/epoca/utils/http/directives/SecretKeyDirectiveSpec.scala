package pl.iterators.epoca.utils.http.directives

import com.typesafe.config.ConfigFactory
import org.apache.pekko.http.scaladsl.model.StatusCodes.{NotFound, OK}
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.server.{Directives, Route}
import pl.iterators.epoca.utils.RouteSpec

class SecretKeyDirectiveSpec extends RouteSpec {

  private def protectedRoute(config: SecretKeyConfig): Route =
    Route.seal(Directives.path("protected")(SecretKeyDirective.secretKeyProtection(config)(_ => Directives.complete(OK))))

  private val configured = SecretKeyConfig("secret-key", Some("operational-secret"))

  "SecretKeyDirective.secretKeyProtection" should {
    "pass a request carrying the configured secret" in {
      Get("/protected").withHeaders(RawHeader("secret-key", "operational-secret")) ~> protectedRoute(configured) ~> check {
        status shouldEqual OK
      }
    }
    "reject a request carrying a different secret" in {
      Get("/protected").withHeaders(RawHeader("secret-key", "operational-secreT")) ~> protectedRoute(configured) ~> check {
        status shouldBeInaccessible
      }
    }
    "reject every request when no secret is configured" in {
      Get("/protected").withHeaders(RawHeader("secret-key", "secret-key")) ~> protectedRoute(SecretKeyConfig("secret-key", None)) ~> check {
        status shouldEqual NotFound
      }
    }
  }

  "SecretKeyConfig" should {
    "disable the protected routes when the secret is unset or blank" in {
      val unset = SecretKeyConfig(ConfigFactory.parseString("app.secretKey.name = secret-key"))
      val blank = SecretKeyConfig(ConfigFactory.parseString("app.secretKey { name = secret-key, value = \" \" }"))

      (unset.value must beNone) and (blank.value must beNone)
    }
    "read a configured secret" in {
      SecretKeyConfig(ConfigFactory.parseString("app.secretKey { name = secret-key, value = configured }")).value must beSome("configured")
    }
  }
}
