package pl.iterators.epoca.auth.services

import pl.iterators.epoca.auth.domain.AuthDomain.DeviceId
import pl.iterators.epoca.auth.domain.RefreshToken
import pl.iterators.epoca.auth.repositories.RefreshTokenRepository
import pl.iterators.epoca.auth.repositories.RefreshTokenRepository.RefreshTokenFilters
import pl.iterators.epoca.auth.services.RefreshTokenListService.{RefreshTokenDto, RefreshTokenListResult}
import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.utils.ServiceSpec

class RefreshTokenListServiceSpec extends ServiceSpec {
  val refreshTokenGenerator: AllGenerators[RefreshToken] = allGenerators[RefreshToken]

  trait TestCase extends BaseScope {
    val refreshTokenRepository: RefreshTokenRepository = vmock[RefreshTokenRepository]

    val refreshTokenListService: RefreshTokenListService = wire[RefreshTokenListService]
  }

  "list" should {
    "just call repo with proper params" in new TestCase {
      val refreshToken1 = refreshTokenGenerator.normal.generate.copy(deviceId = DeviceId("expected-device"))
      val refreshToken2 = refreshTokenGenerator.normal.generate
      val refreshToken3 = refreshTokenGenerator.normal.generate

      when(refreshTokenRepository.list(RefreshTokenFilters(userId = Some(authUser.id))))
        .thenReturn(Seq(refreshToken1, refreshToken2, refreshToken3).asDBIO)

      refreshTokenListService
        .list(authUser)
        .asserting(
          _ shouldEqual RefreshTokenListResult.Ok(
            Seq(
              RefreshTokenDto(
                refreshToken1.deviceId,
                refreshToken1.userAgent,
                refreshToken1.lastAuthedAt,
                refreshToken1.createdAt,
                refreshToken1.expiredAt
              ),
              RefreshTokenDto(
                refreshToken2.deviceId,
                refreshToken2.userAgent,
                refreshToken2.lastAuthedAt,
                refreshToken2.createdAt,
                refreshToken2.expiredAt
              ),
              RefreshTokenDto(
                refreshToken3.deviceId,
                refreshToken3.userAgent,
                refreshToken3.lastAuthedAt,
                refreshToken3.createdAt,
                refreshToken3.expiredAt
              )
            )
          )
        )

      verify(refreshTokenRepository).list(RefreshTokenFilters(userId = Some(authUser.id)))

    }
  }

}
