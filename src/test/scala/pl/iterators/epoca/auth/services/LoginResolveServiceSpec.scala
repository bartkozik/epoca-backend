package pl.iterators.epoca.auth.services

import io.circe.syntax._
import org.mockito.Mockito.verifyNoInteractions
import pl.iterators.epoca.auth.domain.AuthDomain.{Jwt, RefreshTokenValidForStrategy, RefreshTokenValue}
import pl.iterators.epoca.auth.repositories.RefreshTokenRepository
import pl.iterators.epoca.auth.services.LoginResolveService._
import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.user.domain.UserDomain.UserAccountType
import pl.iterators.epoca.utils.ServiceSpec
import pl.iterators.epoca.utils.json.JsonProtocol

import java.time.{ZoneId, ZonedDateTime}
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.Duration

class LoginResolveServiceSpec extends ServiceSpec with JsonProtocol {
  val loginResolveAdminImpersonateRequestDataGenerator: AllGenerators[AdminImpersonate] =
    allGenerators[AdminImpersonate]
  val loginResolveNormalRequestDataGenerator: AllGenerators[UserLogin] = allGenerators[UserLogin]
  val loginResolveRefreshTokenRequestDataGenerator: AllGenerators[RefreshTokenExchange] =
    allGenerators[RefreshTokenExchange]
  val jwtGenerator: AllGenerators[Jwt]                             = allGenerators[Jwt]
  val refreshTokenValueGenerator: AllGenerators[RefreshTokenValue] = allGenerators[RefreshTokenValue]

  trait TestCase extends BaseScope with FixedTimeBaseScope {

    val jwtService: JwtService = vmock[JwtService]
    val config: LoginResolveService.Config =
      LoginResolveService.Config(
        Duration.apply(1, TimeUnit.SECONDS),
        Duration.apply(2, TimeUnit.SECONDS),
        RefreshTokenValidForStrategy.FromFirstUsage
      )
    val refreshTokenRepository: RefreshTokenRepository                       = vmock[RefreshTokenRepository]
    val refreshTokenValueGeneratorService: RefreshTokenValueGeneratorService = vmock[RefreshTokenValueGeneratorService]

    val service: LoginResolveService = wire[LoginResolveService]
  }

  "configuration" should {
    "reject an unbounded refresh token strategy" in {
      LoginResolveService
        .Config(Duration(1, TimeUnit.HOURS), Duration(1, TimeUnit.DAYS), RefreshTokenValidForStrategy.Off) must throwA[IllegalArgumentException]
    }
    "reject nonpositive refresh token lifetimes" in {
      LoginResolveService.Config(Duration(1, TimeUnit.HOURS), Duration(0, TimeUnit.SECONDS), RefreshTokenValidForStrategy.FromFirstUsage) must throwA[
        IllegalArgumentException
      ]
    }
  }

  "resolve" should {
    "reject service accounts before issuing or persisting refresh tokens" in new TestCase {
      val baseRequest       = loginResolveNormalRequestDataGenerator.normal.generate
      val request           = baseRequest.copy(user = baseRequest.user.copy(accountType = UserAccountType.ServiceAccount))
      val refreshTokenValue = refreshTokenValueGenerator.normal.generate
      when(refreshTokenValueGeneratorService.generate).thenReturn(refreshTokenValue)

      service.resolve(request).attempt.asserting(_ shouldEqual Left(ServiceAccountNotPermitted))

      verifyNoInteractions(refreshTokenRepository, jwtService)
    }

    "generates access and refresh tokens for an independent user login" in new TestCase {
      val baseRequest = loginResolveNormalRequestDataGenerator.normal.generate
      val request = baseRequest.copy(user = baseRequest.user.copy(accountType = UserAccountType.Human), prevAuthContext = None, verifiedMethod = None)
      val refreshTokenValue = refreshTokenValueGenerator.normal.generate

      val zonedNow = ZonedDateTime.ofInstant(now, ZoneId.systemDefault())

      val authContext  = request.toAuthContext(zonedNow)
      val refreshToken = LoginResolveService.buildRefreshToken(config, zonedNow, authContext, request, refreshTokenValue)

      val authContextJwt  = jwtGenerator.normal.generate
      val refreshTokenJwt = jwtGenerator.normal.generate

      when(refreshTokenValueGeneratorService.generate).thenReturn(refreshTokenValue)
      when(refreshTokenRepository.upsert(refreshToken)).thenAnswerArgAsDBIO()
      when(jwtService.encode(authContext.asJson, zonedNow, Some(zonedNow.plusSeconds(1)))).thenReturn(authContextJwt)
      when(
        jwtService
          .encode(refreshToken.asJson, issuedAt = zonedNow, refreshToken.expiredAt.map(e => ZonedDateTime.ofInstant(e, ZoneId.systemDefault())))
      ).thenReturn(refreshTokenJwt)

      service.resolve(request).asserting(_ shouldEqual LoginResolution(authContextJwt, refreshTokenJwt))

      verify(refreshTokenValueGeneratorService).generate
      verify(refreshTokenRepository).upsert(any)
      verify(jwtService).encode(authContext.asJson, zonedNow, Some(zonedNow.plusSeconds(1)))
      verify(jwtService).encode(
        refreshToken.asJson,
        issuedAt = zonedNow,
        refreshToken.expiredAt.map(e => ZonedDateTime.ofInstant(e, ZoneId.systemDefault()))
      )

    }
  }
}
