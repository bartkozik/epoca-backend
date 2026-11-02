package pl.iterators.epoca.auth.services

import pl.iterators.epoca.auth.domain.AccountAuthentication.CredentialMethod
import pl.iterators.epoca.auth.domain.{AuthContext, UserPassword}
import pl.iterators.epoca.auth.repositories.UserPasswordRepository
import pl.iterators.epoca.auth.routers.AuthRouter.LoginHttpRequestContext
import pl.iterators.epoca.auth.services.LoginByPasswordService.{LoginRequest, LoginResult}
import pl.iterators.epoca.auth.services.LoginResolveService.{LoginResolution, UserLogin}
import pl.iterators.epoca.organization.domain.OrganizationUser
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.user.domain.UserDomain._
import pl.iterators.epoca.user.domain.{User, UserIdentity, UserWithIdentities}
import pl.iterators.epoca.user.repositories.UserRepository
import pl.iterators.epoca.user.repositories.UserRepository.UserFilters
import pl.iterators.epoca.user.services.UserWithIdentitiesFindService
import pl.iterators.epoca.user.services.UserWithIdentitiesFindService.UserWithIdentitiesFindResult
import pl.iterators.epoca.utils.ServiceSpec

class LoginByPasswordServiceSpec extends ServiceSpec {
  val loginHttpRequestContextGenerator: AllGenerators[LoginHttpRequestContext] = allGenerators[LoginHttpRequestContext]
  val authContextGenerator: AllGenerators[Option[AuthContext]]                 = allGenerators[Option[AuthContext]]
  val userGenerator: AllGenerators[User]                                       = allGenerators[User]
  val identityGenerator: AllGenerators[UserIdentity]                           = allGenerators[UserIdentity]
  val loginRequestGenerator: AllGenerators[LoginRequest]                       = allGenerators[LoginRequest]
  val userPasswordGenerator: AllGenerators[UserPassword]                       = allGenerators[UserPassword]
  val resultDataGenerator: AllGenerators[LoginResolution]                      = allGenerators[LoginResolution]
  val organizationUserGenerator: AllGenerators[OrganizationUser]               = allGenerators[OrganizationUser]

  trait TestCase extends BaseScope {
    val userRepository: UserRepository                               = vmock[UserRepository]
    val userPasswordRepository: UserPasswordRepository               = vmock[UserPasswordRepository]
    val userWithIdentitiesFindService: UserWithIdentitiesFindService = vmock[UserWithIdentitiesFindService]
    val passwordService: PasswordService                             = vmock[PasswordService]
    val loginResolveService: LoginResolveService                     = vmock[LoginResolveService]
    val organizationUserRepository: OrganizationUserRepository       = vmock[OrganizationUserRepository]

    val loginService: LoginByPasswordService = wire[LoginByPasswordService]
  }

  "login" should {
    "return InvalidRequest when both email and phone number are defined" in new TestCase {
      val loginHttpRequestContext = loginHttpRequestContextGenerator.normal.generate
      val auth                    = authContextGenerator.normal.generate
      val request                 = loginRequestGenerator.maximal.generate

      loginService.login(auth, request, loginHttpRequestContext).asserting(_ shouldEqual LoginResult.InvalidRequest)
    }

    "return InvalidRequest when neither email nor phone number is defined" in new TestCase {
      val loginHttpRequestContext = loginHttpRequestContextGenerator.normal.generate
      val auth                    = authContextGenerator.normal.generate
      val request                 = loginRequestGenerator.minimal.generate

      loginService.login(auth, request, loginHttpRequestContext).asserting(_ shouldEqual LoginResult.InvalidRequest)
    }
    "return UserOrPasswordNotFound user with certain email is not present in db" in new TestCase {
      val loginHttpRequestContext = loginHttpRequestContextGenerator.normal.generate
      val auth                    = authContextGenerator.normal.generate
      val request                 = loginRequestGenerator.maximal.generate.copy(phoneNumber = None)

      when(userRepository.find(UserFilters(id = auth.map(_.id), email = request.email, phoneNumber = request.phoneNumber)))
        .thenReturn(None.asDBIO)

      loginService.login(auth, request, loginHttpRequestContext).asserting(_ shouldEqual LoginResult.UserOrPasswordNotFound)

      verify(userRepository).find(UserFilters(id = auth.map(_.id), email = request.email, phoneNumber = request.phoneNumber))
    }
    "return the same not-found result when direct-id and email lookup targets a service account" in new TestCase {
      val loginHttpRequestContext = loginHttpRequestContextGenerator.normal.generate
      val serviceAccount          = userGenerator.normal.generate.copy(accountType = UserAccountType.ServiceAccount, archivedAt = None)
      val auth                    = Some(authUser.copy(id = serviceAccount.id))
      val request                 = loginRequestGenerator.maximal.generate.copy(phoneNumber = None)
      val filters                 = UserFilters(id = Some(serviceAccount.id), email = request.email, phoneNumber = None)

      when(userRepository.find(filters)).thenReturn(None.asDBIO)

      loginService.login(auth, request, loginHttpRequestContext).asserting(_ shouldEqual LoginResult.UserOrPasswordNotFound)

      verify(userRepository).find(filters)
      verify(userPasswordRepository, times(0)).find(any)
      verify(loginResolveService, times(0)).resolve(any)
    }
    "return UserOrPasswordNotFound user with certain email is not present in db (no identity)" in new TestCase {
      val loginHttpRequestContext = loginHttpRequestContextGenerator.normal.generate
      val auth                    = authContextGenerator.normal.generate
      val request                 = loginRequestGenerator.maximal.generate.copy(phoneNumber = None)
      val user                    = userGenerator.normal.generate

      when(userRepository.find(UserFilters(id = auth.map(_.id), email = request.email, phoneNumber = request.phoneNumber)))
        .thenReturn(Some(user).asDBIO)

      when(userWithIdentitiesFindService.find(user.id)).thenReturn(UserWithIdentitiesFindResult.UserDoNotExist.asIO)

      loginService.login(auth, request, loginHttpRequestContext).asserting(_ shouldEqual LoginResult.UserOrPasswordNotFound)

      verify(userRepository).find(UserFilters(id = auth.map(_.id), email = request.email, phoneNumber = request.phoneNumber))

      verify(userWithIdentitiesFindService).find(user.id)
    }
    "return UserOrPasswordNotFound when user do not have generated password" in new TestCase {
      val loginHttpRequestContext = loginHttpRequestContextGenerator.normal.generate
      val auth                    = authContextGenerator.normal.generate
      val user                    = userGenerator.normal.generate
      val request                 = loginRequestGenerator.maximal.generate.copy(phoneNumber = None)
      val identity =
        identityGenerator.normal.generate.copy(
          userId = user.id,
          `type` = IdentityType.MailAddress,
          value = UserIdentityValue(request.email.get),
          confirmed = UserIdentityConfirmed(false)
        )

      when(userRepository.find(UserFilters(id = auth.map(_.id), email = request.email, phoneNumber = request.phoneNumber)))
        .thenReturn(Some(user).asDBIO)

      when(userWithIdentitiesFindService.find(user.id)).thenReturn(UserWithIdentitiesFindResult.Ok(UserWithIdentities(user, Seq(identity))).asIO)

      when(userPasswordRepository.find(identity.id))
        .thenReturn(None.asDBIO)

      loginService.login(auth, request, loginHttpRequestContext).asserting(_ shouldEqual LoginResult.UserOrPasswordNotFound)

      verify(userRepository).find(UserFilters(id = auth.map(_.id), email = request.email, phoneNumber = request.phoneNumber))

      verify(userWithIdentitiesFindService).find(user.id)

      verify(userPasswordRepository).find(identity.id)
    }
    "return InvalidCredentials when password is not correct" in new TestCase {
      val loginHttpRequestContext = loginHttpRequestContextGenerator.normal.generate
      val auth                    = authContextGenerator.normal.generate
      val user                    = userGenerator.normal.generate
      val request                 = loginRequestGenerator.maximal.generate.copy(phoneNumber = None)
      val identity =
        identityGenerator.normal.generate.copy(
          userId = user.id,
          `type` = IdentityType.MailAddress,
          value = UserIdentityValue(request.email.get),
          confirmed = UserIdentityConfirmed(false)
        )
      val userPassword = userPasswordGenerator.normal.generate.copy(userIdentityId = identity.id)

      when(userRepository.find(UserFilters(id = auth.map(_.id), email = request.email, phoneNumber = request.phoneNumber)))
        .thenReturn(Some(user).asDBIO)

      when(userWithIdentitiesFindService.find(user.id)).thenReturn(UserWithIdentitiesFindResult.Ok(UserWithIdentities(user, Seq(identity))).asIO)

      when(userPasswordRepository.find(identity.id))
        .thenReturn(Some(userPassword).asDBIO)

      when(passwordService.check(request.password, userPassword.password))
        .thenReturn(false)

      loginService.login(auth, request, loginHttpRequestContext).asserting(_ shouldEqual LoginResult.InvalidCredentials)

      verify(userRepository).find(UserFilters(id = auth.map(_.id), email = request.email, phoneNumber = request.phoneNumber))

      verify(userWithIdentitiesFindService).find(user.id)

      verify(userPasswordRepository).find(identity.id)

      verify(passwordService).check(request.password, userPassword.password)
    }
    "return IdentityNotConfirmed if identity is not confirmed" in new TestCase {
      val loginHttpRequestContext = loginHttpRequestContextGenerator.normal.generate
      val auth                    = authContextGenerator.normal.generate
      val user                    = userGenerator.normal.generate
      val request                 = loginRequestGenerator.maximal.generate.copy(phoneNumber = None)
      val identity =
        identityGenerator.normal.generate.copy(
          userId = user.id,
          `type` = IdentityType.MailAddress,
          value = UserIdentityValue(request.email.get),
          confirmed = UserIdentityConfirmed(false)
        )
      val userPassword = userPasswordGenerator.normal.generate.copy(userIdentityId = identity.id)

      when(userRepository.find(UserFilters(id = auth.map(_.id), email = request.email, phoneNumber = request.phoneNumber)))
        .thenReturn(Some(user).asDBIO)

      when(userWithIdentitiesFindService.find(user.id)).thenReturn(UserWithIdentitiesFindResult.Ok(UserWithIdentities(user, Seq(identity))).asIO)

      when(userPasswordRepository.find(identity.id))
        .thenReturn(Some(userPassword).asDBIO)

      when(passwordService.check(request.password, userPassword.password))
        .thenReturn(true)

      loginService.login(auth, request, loginHttpRequestContext).asserting(_ shouldEqual LoginResult.IdentityNotConfirmed)

      verify(userRepository).find(UserFilters(id = auth.map(_.id), email = request.email, phoneNumber = request.phoneNumber))

      verify(userWithIdentitiesFindService).find(user.id)

      verify(userPasswordRepository).find(identity.id)

      verify(passwordService).check(request.password, userPassword.password)
    }

    "return Ok otherwise, generating login result data token for user logged by email" in new TestCase {
      val loginHttpRequestContext = loginHttpRequestContextGenerator.normal.generate
      val auth                    = authContextGenerator.normal.generate
      val user                    = userGenerator.normal.generate
      val request                 = loginRequestGenerator.maximal.generate.copy(phoneNumber = None)
      val identity =
        identityGenerator.normal.generate.copy(
          userId = user.id,
          `type` = IdentityType.MailAddress,
          value = UserIdentityValue(request.email.get),
          confirmed = UserIdentityConfirmed(true)
        )
      val userPassword     = userPasswordGenerator.normal.generate.copy(userIdentityId = identity.id)
      val resultData       = resultDataGenerator.normal.generate
      val organizationUser = organizationUserGenerator.normal.generate

      when(userRepository.find(UserFilters(id = auth.map(_.id), email = request.email, phoneNumber = request.phoneNumber)))
        .thenReturn(Some(user).asDBIO)

      when(userWithIdentitiesFindService.find(user.id)).thenReturn(UserWithIdentitiesFindResult.Ok(UserWithIdentities(user, Seq(identity))).asIO)

      when(userPasswordRepository.find(identity.id))
        .thenReturn(Some(userPassword).asDBIO)

      when(passwordService.check(request.password, userPassword.password))
        .thenReturn(true)

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
              Some(CredentialMethod.Password)
            )
          )
      )
        .thenReturn(resultData.asIO)

      loginService.login(auth, request, loginHttpRequestContext).asserting(_ shouldEqual LoginResult.Ok(resultData))

      verify(userRepository).find(UserFilters(id = auth.map(_.id), email = request.email, phoneNumber = request.phoneNumber))

      verify(userWithIdentitiesFindService).find(user.id)

      verify(userPasswordRepository).find(identity.id)

      verify(passwordService).check(request.password, userPassword.password)

      verify(organizationUserRepository).list(OrganizationUserFilters(userId = Some(user.id)))

      verify(loginResolveService).resolve(
        UserLogin(
          loginHttpRequestContext,
          UserWithIdentities(user, Seq(identity)),
          Seq(organizationUser),
          auth,
          identity,
          Some(CredentialMethod.Password)
        )
      )
    }

    "return Ok otherwise, generating login result data for user logged by phone" in new TestCase {
      val loginHttpRequestContext = loginHttpRequestContextGenerator.normal.generate
      val auth                    = authContextGenerator.normal.generate
      val user                    = userGenerator.normal.generate
      val request                 = loginRequestGenerator.maximal.generate.copy(email = None)
      val identity =
        identityGenerator.normal.generate.copy(
          userId = user.id,
          `type` = IdentityType.Phone,
          value = UserIdentityValue(request.phoneNumber.get),
          confirmed = UserIdentityConfirmed(true)
        )
      val userPassword     = userPasswordGenerator.normal.generate.copy(userIdentityId = identity.id)
      val resultData       = resultDataGenerator.normal.generate
      val organizationUser = organizationUserGenerator.normal.generate

      when(userRepository.find(UserFilters(id = auth.map(_.id), email = request.email, phoneNumber = request.phoneNumber)))
        .thenReturn(Some(user).asDBIO)

      when(userWithIdentitiesFindService.find(user.id)).thenReturn(UserWithIdentitiesFindResult.Ok(UserWithIdentities(user, Seq(identity))).asIO)

      when(userPasswordRepository.find(identity.id))
        .thenReturn(Some(userPassword).asDBIO)

      when(passwordService.check(request.password, userPassword.password))
        .thenReturn(true)

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
              Some(CredentialMethod.Password)
            )
          )
      )
        .thenReturn(resultData.asIO)

      loginService.login(auth, request, loginHttpRequestContext).asserting(_ shouldEqual LoginResult.Ok(resultData))

      verify(userRepository).find(UserFilters(id = auth.map(_.id), email = request.email, phoneNumber = request.phoneNumber))

      verify(userWithIdentitiesFindService).find(user.id)

      verify(userPasswordRepository).find(identity.id)

      verify(passwordService).check(request.password, userPassword.password)

      verify(organizationUserRepository).list(OrganizationUserFilters(userId = Some(user.id)))

      verify(loginResolveService).resolve(
        UserLogin(
          loginHttpRequestContext,
          UserWithIdentities(user, Seq(identity)),
          Seq(organizationUser),
          auth,
          identity,
          Some(CredentialMethod.Password)
        )
      )
    }
  }
}
