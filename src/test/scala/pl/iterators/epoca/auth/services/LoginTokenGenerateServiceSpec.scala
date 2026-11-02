package pl.iterators.epoca.auth.services

import com.typesafe.config.ConfigFactory
import pl.iterators.epoca.auth.domain.AuthDomain._
import pl.iterators.epoca.auth.domain.UserLoginToken
import pl.iterators.epoca.auth.services.LoginTokenGenerateService.UserLoginTokenWithLoginTokenPlain
import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.user.domain.UserDomain.IdentityType
import pl.iterators.epoca.user.domain.UserIdentity
import pl.iterators.epoca.utils.ServiceSpec

import scala.concurrent.duration._

class LoginTokenGenerateServiceSpec extends ServiceSpec {

  val userLoginTokenGenerator: AllGenerators[UserLoginToken]           = allGenerators[UserLoginToken]
  val identityGenerator: AllGenerators[UserIdentity]                   = allGenerators[UserIdentity]
  val configGenerator: AllGenerators[LoginTokenGenerateService.Config] = allGenerators[LoginTokenGenerateService.Config]

  trait TestCase extends BaseScope {
    val loginTokenHashService: LoginTokenHashService = vmock[LoginTokenHashService]
    val config: LoginTokenGenerateService.Config     = LoginTokenGenerateService.Config(15.minutes)

    val loginTokenGenerateService: LoginTokenGenerateService = wire[LoginTokenGenerateService]
  }

  "configuration" should {
    "reject zero and negative magic-link lifetimes" in {
      LoginTokenGenerateService.Config(ConfigFactory.parseString("user.loginToken.validForDuration=0s")) must throwA[IllegalArgumentException]
      LoginTokenGenerateService.Config(ConfigFactory.parseString("user.loginToken.validForDuration=-1s")) must throwA[IllegalArgumentException]
    }
    "accept a positive configured lifetime" in {
      LoginTokenGenerateService.Config(ConfigFactory.parseString("user.loginToken.validForDuration=15m")).validFor mustEqual 15.minutes
    }
  }

  "getToken" should {
    "preserve a positive subsecond lifetime instead of truncating it to immediate expiration" in new BaseScope {
      val hashService = vmock[LoginTokenHashService]
      when(hashService.hash(any)).thenReturn(LoginTokenHash("hash"))
      val service = new LoginTokenGenerateService(LoginTokenGenerateService.Config(500.millis), hashService)
      val before  = java.time.Instant.now()
      service.generateToken(identityGenerator.normal.generate, expiring = true).asserting { result =>
        result.token.validTo.exists(_.isAfter(before.plusMillis(499L))) must beTrue
      }
      verify(hashService).hash(any)
    }
    "use a high entropy recovery token even for a phone identity" in new TestCase {
      val identity = identityGenerator.normal.generate.copy(`type` = IdentityType.Phone)
      when(loginTokenHashService.hash(any)).thenReturn(LoginTokenHash("hash"))
      loginTokenGenerateService.generateToken(identity, expiring = false).asserting { result =>
        scala.util.Try(java.util.UUID.fromString(result.loginTokenPlain)).toOption.map(_.version()) must beSome(4)
      }
      verify(loginTokenHashService).hash(any)
    }

    "works without exception" in new TestCase {
      val loginTokenHash = LoginTokenHash("hash")
      val identity       = identityGenerator.normal.generate

      when(loginTokenHashService.hash(any)).thenReturn(loginTokenHash)

      loginTokenGenerateService.generateToken(identity, expiring = true).asserting(_ should haveClass[UserLoginTokenWithLoginTokenPlain])

      verify(loginTokenHashService).hash(any)
    }
  }
}
