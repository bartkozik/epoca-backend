package pl.iterators.epoca.auth.services

import pl.iterators.epoca.auth.domain.AccountAuthentication.CredentialMethod
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.auth.domain.AuthDomain.{TimeBasedOneTimePasswordSecret, TimeBasedOneTimePasswordValue}
import pl.iterators.epoca.auth.routers.AuthRouter.LoginHttpRequestContext
import pl.iterators.epoca.auth.services.LoginByTimeBasedOneTimePasswordService._
import pl.iterators.epoca.auth.services.LoginResolveService.{LoginResolution, UserLogin}
import pl.iterators.epoca.organization.domain.OrganizationUser
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.user.domain.UserDomain.IdentityType
import pl.iterators.epoca.user.domain.{User, UserIdentity, UserWithIdentities}
import pl.iterators.epoca.user.repositories.UserIdentityRepository
import pl.iterators.epoca.user.repositories.UserIdentityRepository.UserIdentityFilters
import pl.iterators.epoca.user.services.UserWithIdentitiesFindService
import pl.iterators.epoca.user.services.UserWithIdentitiesFindService.UserWithIdentitiesFindResult
import pl.iterators.epoca.utils.ServiceSpec

class LoginByTimeBasedOneTimePasswordServiceSpec extends ServiceSpec {
  val loginHttpRequestContextGenerator: AllGenerators[LoginHttpRequestContext]     = allGenerators[LoginHttpRequestContext]
  val authContextGenerator: AllGenerators[Option[AuthContext]]                     = allGenerators[Option[AuthContext]]
  val loginRequestGenerator: AllGenerators[LoginByTimeBasedOneTimePasswordRequest] = allGenerators[LoginByTimeBasedOneTimePasswordRequest]
  val identityGenerator: AllGenerators[UserIdentity]                               = allGenerators[UserIdentity]
  val userGenerator: AllGenerators[User]                                           = allGenerators[User]
  val resultDataGenerator: AllGenerators[LoginResolution]                          = allGenerators[LoginResolution]
  val organizationUserGenerator: AllGenerators[OrganizationUser]                   = allGenerators[OrganizationUser]

  trait TestCase extends BaseScope {
    val userWithIdentitiesFindService: UserWithIdentitiesFindService     = vmock[UserWithIdentitiesFindService]
    val userIdentityRepository: UserIdentityRepository                   = vmock[UserIdentityRepository]
    val timeBasedOneTimePasswordService: TimeBasedOneTimePasswordService = vmock[TimeBasedOneTimePasswordService]
    val loginResolveService: LoginResolveService                         = vmock[LoginResolveService]
    val organizationUserRepository: OrganizationUserRepository           = vmock[OrganizationUserRepository]

    val totpRateLimitService: TotpRateLimitService = mock[TotpRateLimitService]
    when(totpRateLimitService.consume(any)).thenReturn(true.asIO)

    val service: LoginByTimeBasedOneTimePasswordService = wire[LoginByTimeBasedOneTimePasswordService]
  }

  "login" should {
    "returns UserOrPasswordNotFound when user do not exist" in new TestCase {
      val loginHttpRequestContext = loginHttpRequestContextGenerator.normal.generate
      val auth                    = authContextGenerator.normal.generate
      val request                 = loginRequestGenerator.maximal.generate

      when(
        userIdentityRepository
          .find(UserIdentityFilters(id = Some(request.userIdentityId), userId = auth.map(_.id), `type` = Some(IdentityType.TimeBasedOneTimePassword)))
      )
        .thenReturn(None.asDBIO)

      service.login(auth, request, loginHttpRequestContext).asserting(_ shouldEqual LoginByTimeBasedOneTimePasswordResult.UserOrPasswordNotFound)

      verify(userIdentityRepository).find(
        UserIdentityFilters(id = Some(request.userIdentityId), userId = auth.map(_.id), `type` = Some(IdentityType.TimeBasedOneTimePassword))
      )
    }
    "returns InvalidCredentials when provided password is different than calculated by password service" in new TestCase {
      val loginHttpRequestContext = loginHttpRequestContextGenerator.normal.generate
      val auth                    = authContextGenerator.normal.generate
      val request                 = loginRequestGenerator.maximal.generate
      val identity                = identityGenerator.maximal.generate

      when(
        userIdentityRepository
          .find(UserIdentityFilters(id = Some(request.userIdentityId), userId = auth.map(_.id), `type` = Some(IdentityType.TimeBasedOneTimePassword)))
      )
        .thenReturn(Some(identity).asDBIO)

      when(timeBasedOneTimePasswordService.generateIO(TimeBasedOneTimePasswordSecret(identity.value)))
        .thenReturn(TimeBasedOneTimePasswordValue("other").asIO)

      service.login(auth, request, loginHttpRequestContext).asserting(_ shouldEqual LoginByTimeBasedOneTimePasswordResult.InvalidCredentials)

      verify(userIdentityRepository).find(
        UserIdentityFilters(id = Some(request.userIdentityId), userId = auth.map(_.id), `type` = Some(IdentityType.TimeBasedOneTimePassword))
      )

      verify(timeBasedOneTimePasswordService).generateIO(TimeBasedOneTimePasswordSecret(identity.value))
    }
    "returns UserOrPasswordNotFound when user with identity service do not return user" in new TestCase {
      val loginHttpRequestContext = loginHttpRequestContextGenerator.normal.generate
      val auth                    = authContextGenerator.normal.generate
      val request                 = loginRequestGenerator.maximal.generate
      val identity                = identityGenerator.maximal.generate

      when(
        userIdentityRepository
          .find(UserIdentityFilters(id = Some(request.userIdentityId), userId = auth.map(_.id), `type` = Some(IdentityType.TimeBasedOneTimePassword)))
      )
        .thenReturn(Some(identity).asDBIO)

      when(timeBasedOneTimePasswordService.generateIO(TimeBasedOneTimePasswordSecret(identity.value)))
        .thenReturn(TimeBasedOneTimePasswordValue(request.password).asIO)

      when(userWithIdentitiesFindService.find(identity.userId)).thenReturn(UserWithIdentitiesFindResult.UserDoNotExist.asIO)

      service.login(auth, request, loginHttpRequestContext).asserting(_ shouldEqual LoginByTimeBasedOneTimePasswordResult.UserOrPasswordNotFound)

      verify(userIdentityRepository).find(
        UserIdentityFilters(id = Some(request.userIdentityId), userId = auth.map(_.id), `type` = Some(IdentityType.TimeBasedOneTimePassword))
      )

      verify(timeBasedOneTimePasswordService).generateIO(TimeBasedOneTimePasswordSecret(identity.value))

      verify(userWithIdentitiesFindService).find(identity.userId)
    }
    "returns UserOrPasswordNotFound when user with identity service do not return user" in new TestCase {
      val loginHttpRequestContext = loginHttpRequestContextGenerator.normal.generate
      val auth                    = authContextGenerator.normal.generate
      val request                 = loginRequestGenerator.maximal.generate
      val identity                = identityGenerator.maximal.generate
      val user                    = userGenerator.maximal.generate
      val resultData              = resultDataGenerator.normal.generate
      val organizationUser        = organizationUserGenerator.normal.generate

      when(
        userIdentityRepository
          .find(UserIdentityFilters(id = Some(request.userIdentityId), userId = auth.map(_.id), `type` = Some(IdentityType.TimeBasedOneTimePassword)))
      )
        .thenReturn(Some(identity).asDBIO)

      when(timeBasedOneTimePasswordService.generateIO(TimeBasedOneTimePasswordSecret(identity.value)))
        .thenReturn(TimeBasedOneTimePasswordValue(request.password).asIO)

      when(userWithIdentitiesFindService.find(identity.userId))
        .thenReturn(UserWithIdentitiesFindResult.Ok(UserWithIdentities(user, Seq(identity))).asIO)

      when(organizationUserRepository.list(any)).thenReturn(Seq(organizationUser).asDBIO)

      when(
        loginResolveService
          .resolve(
            UserLogin(
              loginHttpRequestContext,
              UserWithIdentities(user, Seq(identity)),
              Seq(organizationUser),
              auth,
              identity,
              Some(CredentialMethod.TimeBasedOneTimePassword)
            )
          )
      )
        .thenReturn(resultData.asIO)

      service.login(auth, request, loginHttpRequestContext).asserting(_ shouldEqual LoginByTimeBasedOneTimePasswordResult.Ok(resultData))

      verify(userIdentityRepository).find(
        UserIdentityFilters(id = Some(request.userIdentityId), userId = auth.map(_.id), `type` = Some(IdentityType.TimeBasedOneTimePassword))
      )

      verify(timeBasedOneTimePasswordService).generateIO(TimeBasedOneTimePasswordSecret(identity.value))

      verify(userWithIdentitiesFindService).find(identity.userId)

      verify(organizationUserRepository).list(OrganizationUserFilters(userId = Some(user.id)))

      verify(loginResolveService).resolve(
        UserLogin(
          loginHttpRequestContext,
          UserWithIdentities(user, Seq(identity)),
          Seq(organizationUser),
          auth,
          identity,
          Some(CredentialMethod.TimeBasedOneTimePassword)
        )
      )
    }
  }
}
