package pl.iterators.epoca.auth.services

import pl.iterators.epoca.auth.domain.RefreshToken
import pl.iterators.epoca.auth.repositories.RefreshTokenRepository
import pl.iterators.epoca.auth.services.RefreshTokenDeleteService.{RefreshTokenDeleteRequest, RefreshTokenDeleteResult}
import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.utils.ServiceSpec

class RefreshTokenDeleteServiceSpec extends ServiceSpec {
  val requestGenerator: AllGenerators[RefreshTokenDeleteRequest] = allGenerators[RefreshTokenDeleteRequest]
  val refreshTokenGenerator: AllGenerators[RefreshToken]         = allGenerators[RefreshToken]

  trait TestCase extends BaseScope {
    val refreshTokenRepository: RefreshTokenRepository = vmock[RefreshTokenRepository]

    val refreshTokenDeleteService: RefreshTokenDeleteService = wire[RefreshTokenDeleteService]
  }

  "delete" should {
    "just call repo with proper params" in new TestCase {
      val request      = requestGenerator.normal.generate
      val refreshToken = refreshTokenGenerator.normal.generate.copy(userId = authUser.id, deviceId = request.deviceId)

      when(refreshTokenRepository.purge(authUser.id, request.deviceId)).thenReturn(0.asDBIO)

      refreshTokenDeleteService.delete(authUser, request).asserting(_ shouldEqual RefreshTokenDeleteResult.Ok)

      verify(refreshTokenRepository).purge(authUser.id, request.deviceId)
    }
  }

}
