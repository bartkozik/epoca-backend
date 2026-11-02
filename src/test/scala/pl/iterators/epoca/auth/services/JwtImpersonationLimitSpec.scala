package pl.iterators.epoca.auth.services

import io.circe.syntax._
import pl.iterators.epoca.auth.domain.{AuthContext, AuthContextAuthedBy}
import pl.iterators.epoca.auth.services.AuthenticateService.AuthenticateTokenResult
import pl.iterators.epoca.user.domain.UserDomain.IdentityType
import pl.iterators.epoca.utils.ServiceSpec

import java.time.{Instant, ZoneOffset}

class JwtImpersonationLimitSpec extends ServiceSpec with pl.iterators.epoca.utils.json.JsonProtocol {
  trait TestCase extends BaseScope {
    val jwt = new JwtService(JwtService.Config("synthetic-jwt-impersonation-regression-key"))
    val sut = new JwtAuthenticateService(jwt)
    def token(context: AuthContext): String =
      jwt.encode(context.asJson, Instant.now().atZone(ZoneOffset.UTC), Some(Instant.now().plusSeconds(7200).atZone(ZoneOffset.UTC)))
  }
  "impersonation authentication" should {
    "reject a legacy long-lived token after the one-hour session cap" in new TestCase {
      val auth =
        authUser.copy(lastAuthedAt = Instant.now().minusSeconds(3601), authedBy = Set(AuthContextAuthedBy(IdentityType.AdminImpersonate, None)))
      sut.authenticateToken(token(auth)).asserting(_ shouldEqual AuthenticateTokenResult.JwtExpired)
    }
    "reject an impersonation token with a future authentication time" in new TestCase {
      val auth = authUser.copy(lastAuthedAt = Instant.now().plusSeconds(60), authedBy = Set(AuthContextAuthedBy(IdentityType.AdminImpersonate, None)))
      sut.authenticateToken(token(auth)).asserting(_ shouldEqual AuthenticateTokenResult.JwtExpired)
    }
    "keep a recent impersonation token usable" in new TestCase {
      val auth =
        authUser.copy(lastAuthedAt = Instant.now().minusSeconds(30), authedBy = Set(AuthContextAuthedBy(IdentityType.AdminImpersonate, None)))
      sut.authenticateToken(token(auth)).asserting(_ shouldEqual AuthenticateTokenResult.Ok(auth))
    }
  }
}
