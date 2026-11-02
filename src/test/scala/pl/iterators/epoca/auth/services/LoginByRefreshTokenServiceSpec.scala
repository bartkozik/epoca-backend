package pl.iterators.epoca.auth.services

import pl.iterators.epoca.auth.domain.AccountAuthentication.BoundSaml
import pl.iterators.epoca.auth.domain.{AccountAuthentication, RefreshToken}
import pl.iterators.epoca.auth.repositories.RefreshTokenRepository
import pl.iterators.epoca.auth.repositories.RefreshTokenRepository.RefreshTokenFilters
import pl.iterators.epoca.auth.routers.AuthRouter.LoginHttpRequestContext
import pl.iterators.epoca.auth.services.JwtService.DecodingResult
import pl.iterators.epoca.auth.services.LoginByRefreshTokenService.{LoginByRefreshTokenRequest, LoginByRefreshTokenResult}
import pl.iterators.epoca.auth.services.LoginResolveService.{LoginResolution, RefreshTokenExchange}
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.organization.domain.OrganizationUser
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.user.domain.UserDomain.UserAccountType
import pl.iterators.epoca.user.domain.UserWithIdentities
import pl.iterators.epoca.user.services.UserWithIdentitiesFindService
import pl.iterators.epoca.user.services.UserWithIdentitiesFindService.UserWithIdentitiesFindResult
import pl.iterators.epoca.utils.ServiceSpec

import java.time.Instant

class LoginByRefreshTokenServiceSpec extends ServiceSpec {
  val loginHttpRequestContextGenerator: AllGenerators[LoginHttpRequestContext] = allGenerators[LoginHttpRequestContext]
  val requestGenerator: AllGenerators[LoginByRefreshTokenRequest]              = allGenerators[LoginByRefreshTokenRequest]
  val refreshTokenGenerator: AllGenerators[RefreshToken]                       = allGenerators[RefreshToken]
  val userGenerator: AllGenerators[UserWithIdentities]                         = allGenerators[UserWithIdentities]
  val resultDataGenerator: AllGenerators[LoginResolution]                      = allGenerators[LoginResolution]
  val organizationUserGenerator: AllGenerators[OrganizationUser]               = allGenerators[OrganizationUser]

  trait TestCase extends BaseScope {
    val userWithIdentitiesFindService: UserWithIdentitiesFindService = vmock[UserWithIdentitiesFindService]
    val refreshTokenRepository: RefreshTokenRepository               = vmock[RefreshTokenRepository]
    val loginResolveService: LoginResolveService                     = vmock[LoginResolveService]
    val jwtService: JwtService                                       = vmock[JwtService]
    val organizationUserRepository: OrganizationUserRepository       = vmock[OrganizationUserRepository]

    val samlSessionService: SamlSessionService = vmock[SamlSessionService]

    val loginByRefreshTokenService: LoginByRefreshTokenService = wire[LoginByRefreshTokenService]
  }

  "login" should {
    Seq(true, false).foreach { current =>
      s"validate the stored SAML binding before refresh when current=$current" in new TestCase {
        val context = loginHttpRequestContextGenerator.normal.generate
        val request = requestGenerator.normal.generate
        val user    = userGenerator.normal.generate.copy(accountType = UserAccountType.Human)
        val member  = organizationUserGenerator.normal.generate.copy(userId = user.id, archivedAt = None)
        val proof   = AccountAuthentication(Instant.now(), BoundSaml(member.organizationId, 4L, "issuer", "subject"))
        val token = refreshTokenGenerator.normal.generate.copy(
          userId = user.id,
          deviceId = context.deviceId,
          authedBy = Nil,
          accountAuthentication = Some(proof)
        )
        val resolution = resultDataGenerator.normal.generate
        when(jwtService.decode[RefreshToken](same(request.refreshTokenJwt))(any))
          .thenReturn(DecodingResult.Decoded(token.copy(accountAuthentication = None)))
        when(refreshTokenRepository.find(any)).thenReturn(Some(token).asDBIO)
        when(userWithIdentitiesFindService.find(user.id)).thenReturn(UserWithIdentitiesFindResult.Ok(user).asIO)
        when(organizationUserRepository.list(any)).thenReturn(Seq(member).asDBIO)
        when(samlSessionService.currentOrganization(any)).thenReturn(Option.when(current)(OrganizationSlug("scoped-org")).asIO)
        when(loginResolveService.resolve(any)).thenReturn(resolution.asIO)
        loginByRefreshTokenService
          .login(request, context)
          .asserting(_ shouldEqual (if (current) LoginByRefreshTokenResult.Ok(resolution) else LoginByRefreshTokenResult.InvalidRefreshToken))
        verify(jwtService).decode[RefreshToken](same(request.refreshTokenJwt))(any)
        verify(refreshTokenRepository).find(any)
        verify(userWithIdentitiesFindService).find(user.id)
        verify(organizationUserRepository).list(any)
        verify(samlSessionService).currentOrganization(argThat[pl.iterators.epoca.auth.domain.AuthContext](_.accountAuthentication.contains(proof)))
        verify(loginResolveService, times(if (current) 1 else 0)).resolve(any)
      }
    }

    "return InvalidRefreshToken when token from request is not valid and cant be decoded" in new TestCase {
      val loginHttpRequestContext = loginHttpRequestContextGenerator.normal.generate
      val request                 = requestGenerator.normal.generate

      when(jwtService.decode[RefreshToken](same(request.refreshTokenJwt))(any)).thenReturn(DecodingResult.InvalidToken(new Exception("any")))

      loginByRefreshTokenService.login(request, loginHttpRequestContext).asserting(_ shouldEqual LoginByRefreshTokenResult.InvalidRefreshToken)

      verify(jwtService).decode[RefreshToken](same(request.refreshTokenJwt))(any)
    }
    "return InvalidRefreshToken when token from request is valid but issued for different device id" in new TestCase {
      val loginHttpRequestContext = loginHttpRequestContextGenerator.normal.generate
      val request                 = requestGenerator.normal.generate
      val refreshToken            = refreshTokenGenerator.normal.generate

      when(jwtService.decode[RefreshToken](same(request.refreshTokenJwt))(any)).thenReturn(DecodingResult.Decoded(refreshToken))

      loginByRefreshTokenService.login(request, loginHttpRequestContext).asserting(_ shouldEqual LoginByRefreshTokenResult.InvalidRefreshToken)

      verify(jwtService).decode[RefreshToken](same(request.refreshTokenJwt))(any)
    }
    "return UserOrRefreshTokenNotFound when token from request is valid, issued for same device id but not exist in db" in new TestCase {
      val loginHttpRequestContext = loginHttpRequestContextGenerator.normal.generate
      val request                 = requestGenerator.normal.generate
      val refreshToken = refreshTokenGenerator.normal.generate.copy(deviceId = loginHttpRequestContext.deviceId, accountAuthentication = None)

      when(jwtService.decode[RefreshToken](same(request.refreshTokenJwt))(any)).thenReturn(DecodingResult.Decoded(refreshToken))

      when(
        refreshTokenRepository
          .find(RefreshTokenFilters(userId = Some(refreshToken.userId), deviceId = Some(refreshToken.deviceId), value = Some(refreshToken.value)))
      ).thenReturn(None.asDBIO)
      when(refreshTokenRepository.revokeIfStale(refreshToken)).thenReturn(0.asDBIO)

      loginByRefreshTokenService.login(request, loginHttpRequestContext).asserting(_ shouldEqual LoginByRefreshTokenResult.UserOrRefreshTokenNotFound)

      verify(jwtService).decode[RefreshToken](same(request.refreshTokenJwt))(any)

      verify(refreshTokenRepository).find(
        RefreshTokenFilters(userId = Some(refreshToken.userId), deviceId = Some(refreshToken.deviceId), value = Some(refreshToken.value))
      )
      verify(refreshTokenRepository).revokeIfStale(refreshToken)
    }
    "return UserOrLoginTokenNotFound when user is not present in db" in new TestCase {
      val loginHttpRequestContext = loginHttpRequestContextGenerator.normal.generate
      val request                 = requestGenerator.normal.generate
      val refreshToken = refreshTokenGenerator.normal.generate.copy(deviceId = loginHttpRequestContext.deviceId, accountAuthentication = None)

      when(jwtService.decode[RefreshToken](same(request.refreshTokenJwt))(any)).thenReturn(DecodingResult.Decoded(refreshToken))

      when(
        refreshTokenRepository
          .find(RefreshTokenFilters(userId = Some(refreshToken.userId), deviceId = Some(refreshToken.deviceId), value = Some(refreshToken.value)))
      )
        .thenReturn(Some(refreshToken).asDBIO)

      when(userWithIdentitiesFindService.find(refreshToken.userId))
        .thenReturn(UserWithIdentitiesFindResult.UserDoNotExist.asIO)

      loginByRefreshTokenService.login(request, loginHttpRequestContext).asserting(_ shouldEqual LoginByRefreshTokenResult.UserOrRefreshTokenNotFound)

      verify(jwtService).decode[RefreshToken](same(request.refreshTokenJwt))(any)

      verify(refreshTokenRepository)
        .find(RefreshTokenFilters(userId = Some(refreshToken.userId), deviceId = Some(refreshToken.deviceId), value = Some(refreshToken.value)))

      verify(userWithIdentitiesFindService).find(refreshToken.userId)
    }
    "return the same not-found result without resolving login for a service account" in new TestCase {
      val loginHttpRequestContext = loginHttpRequestContextGenerator.normal.generate
      val request                 = requestGenerator.normal.generate
      val refreshToken = refreshTokenGenerator.normal.generate.copy(deviceId = loginHttpRequestContext.deviceId, accountAuthentication = None)
      val serviceAccount =
        userGenerator.normal.generate.copy(id = refreshToken.userId, accountType = UserAccountType.ServiceAccount, archivedAt = None)
      val organizationUser = organizationUserGenerator.normal.generate
      val resolution       = resultDataGenerator.normal.generate

      when(jwtService.decode[RefreshToken](same(request.refreshTokenJwt))(any)).thenReturn(DecodingResult.Decoded(refreshToken))
      when(
        refreshTokenRepository
          .find(RefreshTokenFilters(userId = Some(refreshToken.userId), deviceId = Some(refreshToken.deviceId), value = Some(refreshToken.value)))
      ).thenReturn(Some(refreshToken).asDBIO)
      when(userWithIdentitiesFindService.find(refreshToken.userId)).thenReturn(UserWithIdentitiesFindResult.Ok(serviceAccount).asIO)
      when(organizationUserRepository.list(any)).thenReturn(Seq(organizationUser).asDBIO)
      when(loginResolveService.resolve(any)).thenReturn(resolution.asIO)

      loginByRefreshTokenService
        .login(request, loginHttpRequestContext)
        .asserting(_ shouldEqual LoginByRefreshTokenResult.UserOrRefreshTokenNotFound)

      verify(jwtService).decode[RefreshToken](same(request.refreshTokenJwt))(any)
      verify(refreshTokenRepository)
        .find(RefreshTokenFilters(userId = Some(refreshToken.userId), deviceId = Some(refreshToken.deviceId), value = Some(refreshToken.value)))
      verify(userWithIdentitiesFindService).find(refreshToken.userId)
      verify(organizationUserRepository, times(0)).list(any)
      verify(loginResolveService, times(0)).resolve(any)
    }
    "return Ok otherwise, generating login result data for user and purge token" in new TestCase {
      val loginHttpRequestContext = loginHttpRequestContextGenerator.normal.generate
      val user                    = userGenerator.normal.generate.copy(accountType = UserAccountType.Human)
      val request                 = requestGenerator.normal.generate
      val refreshToken     = refreshTokenGenerator.normal.generate.copy(deviceId = loginHttpRequestContext.deviceId, accountAuthentication = None)
      val presentedToken   = refreshToken.copy(accountAuthentication = None)
      val resultData       = resultDataGenerator.normal.generate
      val organizationUser = organizationUserGenerator.normal.generate

      when(jwtService.decode[RefreshToken](same(request.refreshTokenJwt))(any)).thenReturn(DecodingResult.Decoded(presentedToken))

      when(
        refreshTokenRepository
          .find(RefreshTokenFilters(userId = Some(refreshToken.userId), deviceId = Some(refreshToken.deviceId), value = Some(refreshToken.value)))
      )
        .thenReturn(Some(refreshToken).asDBIO)

      when(userWithIdentitiesFindService.find(refreshToken.userId))
        .thenReturn(UserWithIdentitiesFindResult.Ok(user).asIO)

      when(organizationUserRepository.list(any)).thenReturn(Seq(organizationUser).asDBIO)

      when(loginResolveService.resolve(RefreshTokenExchange(loginHttpRequestContext, user, Seq(organizationUser), refreshToken)))
        .thenReturn(resultData.asIO)

      loginByRefreshTokenService.login(request, loginHttpRequestContext).asserting(_ shouldEqual LoginByRefreshTokenResult.Ok(resultData))

      verify(jwtService).decode[RefreshToken](same(request.refreshTokenJwt))(any)

      verify(refreshTokenRepository)
        .find(RefreshTokenFilters(userId = Some(refreshToken.userId), deviceId = Some(refreshToken.deviceId), value = Some(refreshToken.value)))

      verify(userWithIdentitiesFindService).find(refreshToken.userId)

      verify(organizationUserRepository).list(OrganizationUserFilters(userId = Some(refreshToken.userId)))

      verify(loginResolveService).resolve(RefreshTokenExchange(loginHttpRequestContext, user, Seq(organizationUser), refreshToken))
    }
  }
}
