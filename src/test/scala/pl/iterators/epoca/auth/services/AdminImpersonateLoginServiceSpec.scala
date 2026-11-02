package pl.iterators.epoca.auth.services

import pl.iterators.epoca.auth.routers.AuthRouter.LoginHttpRequestContext
import pl.iterators.epoca.auth.services.AdminImpersonateLoginService._
import pl.iterators.epoca.auth.services.LoginResolveService.{AdminImpersonate, LoginResolution}
import pl.iterators.epoca.organization.domain.OrganizationUser
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.user.domain.UserWithIdentities
import pl.iterators.epoca.user.services.UserWithIdentitiesFindService
import pl.iterators.epoca.user.services.UserWithIdentitiesFindService.UserWithIdentitiesFindResult
import pl.iterators.epoca.utils.ServiceSpec

import java.time.Duration

class AdminImpersonateLoginServiceSpec extends ServiceSpec {
  val loginHttpRequestContextGenerator: AllGenerators[LoginHttpRequestContext] = allGenerators[LoginHttpRequestContext]
  val userGenerator: AllGenerators[UserWithIdentities]                         = allGenerators[UserWithIdentities]
  val resultDataGenerator: AllGenerators[LoginResolution]                      = allGenerators[LoginResolution]
  val requestGenerator: AllGenerators[AdminImpersonateLoginRequest]            = allGenerators[AdminImpersonateLoginRequest]
  val organizationUserGenerator: AllGenerators[OrganizationUser]               = allGenerators[OrganizationUser]

  trait TestCase extends BaseScope {
    val loginResolveService: LoginResolveService                     = vmock[LoginResolveService]
    val userWithIdentitiesFindService: UserWithIdentitiesFindService = vmock[UserWithIdentitiesFindService]
    val organizationUserRepository: OrganizationUserRepository       = vmock[OrganizationUserRepository]

    val sut: AdminImpersonateLoginService = wire[AdminImpersonateLoginService]
  }

  "login" should {
    Seq(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofHours(1).plusNanos(1), Duration.ofDays(1)).foreach { duration =>
      s"reject impersonation lifetime $duration before finding a user" in new TestCase {
        val request = requestGenerator.normal.generate.copy(validFor = duration)
        when(userWithIdentitiesFindService.find(request.userId)).thenReturn(UserWithIdentitiesFindResult.UserDoNotExist.asIO)
        sut
          .login(authAdmin, request, loginHttpRequestContextGenerator.normal.generate)
          .asserting(_ shouldEqual AdminImpersonateLoginResult.OperationNotPermitted)
        org.mockito.Mockito.verifyNoInteractions(userWithIdentitiesFindService, organizationUserRepository, loginResolveService)
      }
    }

    "return OperationNotPermitted when caller is not admin" in new TestCase {
      val loginHttpRequestContext = loginHttpRequestContextGenerator.normal.generate
      val request                 = requestGenerator.normal.generate.copy(validFor = Duration.ofMinutes(30))

      sut.login(authUser, request, loginHttpRequestContext).asserting(_ shouldEqual AdminImpersonateLoginResult.OperationNotPermitted)
    }
    "return UserDoesNotExist when user is not found in repository" in new TestCase {
      val loginHttpRequestContext = loginHttpRequestContextGenerator.normal.generate
      val request                 = requestGenerator.normal.generate.copy(validFor = Duration.ofMinutes(30))

      when(userWithIdentitiesFindService.find(request.userId)).thenReturn(UserWithIdentitiesFindResult.UserDoNotExist.asIO)

      sut.login(authAdmin, request, loginHttpRequestContext).asserting(_ shouldEqual AdminImpersonateLoginResult.UserDoesNotExist)

      verify(userWithIdentitiesFindService).find(request.userId)
    }
    "return Ok" in new TestCase {
      val loginHttpRequestContext = loginHttpRequestContextGenerator.normal.generate
      val request                 = requestGenerator.normal.generate.copy(validFor = Duration.ofHours(1))
      val user                    = userGenerator.normal.generate
      val resultData              = resultDataGenerator.normal.generate
      val organizationUser        = organizationUserGenerator.normal.generate

      when(userWithIdentitiesFindService.find(request.userId)).thenReturn(UserWithIdentitiesFindResult.Ok(user).asIO)
      when(organizationUserRepository.list(OrganizationUserFilters(userId = Some(request.userId)))).thenReturn(Seq(organizationUser).asDBIO)
      when(loginResolveService.resolve(AdminImpersonate(loginHttpRequestContext, user, Seq(organizationUser), request.validFor)))
        .thenReturn(resultData.asIO)

      sut.login(authAdmin, request, loginHttpRequestContext).asserting(_ shouldEqual AdminImpersonateLoginResult.Ok(resultData))

      verify(userWithIdentitiesFindService).find(request.userId)
      verify(organizationUserRepository).list(any)
      verify(loginResolveService).resolve(AdminImpersonate(loginHttpRequestContext, user, Seq(organizationUser), request.validFor))
    }
  }
}
