package pl.iterators.epoca.auth.services
import pl.iterators.epoca.auth.domain.AccountAuthentication.CredentialMethod
import pl.iterators.epoca.auth.domain.AuthDomain._
import pl.iterators.epoca.auth.domain.{AuthContext, UserLoginToken}
import pl.iterators.epoca.auth.repositories.UserLoginTokenRepository
import pl.iterators.epoca.auth.repositories.UserLoginTokenRepository.UserLoginTokenFilters
import pl.iterators.epoca.auth.routers.AuthRouter.LoginHttpRequestContext
import pl.iterators.epoca.auth.services.LoginByTokenService.{LoginByTokenRequest, LoginByTokenResult}
import pl.iterators.epoca.auth.services.LoginResolveService.{LoginResolution, UserLogin}
import pl.iterators.epoca.mail.domain.MailDomain.MailAddress
import pl.iterators.epoca.organization.domain.OrganizationUser
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.sms.domain.SmsDomain.PhoneNumber
import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.user.domain.UserDomain._
import pl.iterators.epoca.user.domain._
import pl.iterators.epoca.user.repositories.UserIdentityRepository
import pl.iterators.epoca.user.repositories.UserIdentityRepository.UserIdentityFilters
import pl.iterators.epoca.user.services.UserWithIdentitiesFindService
import pl.iterators.epoca.user.services.UserWithIdentitiesFindService.UserWithIdentitiesFindResult
import pl.iterators.epoca.utils.ServiceSpec

import java.time.Instant
import scala.util.Random

class LoginByTokenServiceSpec extends ServiceSpec {
  val loginHttpRequestContextGenerator: AllGenerators[LoginHttpRequestContext] = allGenerators[LoginHttpRequestContext]
  val authContextGenerator: AllGenerators[Option[AuthContext]]                 = allGenerators[Option[AuthContext]]
  val loginByTokenRequestGenerator: AllGenerators[LoginByTokenRequest]         = allGenerators[LoginByTokenRequest]
  val userLoginTokenGenerator: AllGenerators[UserLoginToken]                   = allGenerators[UserLoginToken]
  val userGenerator: AllGenerators[UserWithIdentities]                         = allGenerators[UserWithIdentities]
  val identityGenerator: AllGenerators[UserIdentity]                           = allGenerators[UserIdentity]
  val resultDataGenerator: AllGenerators[LoginResolution]                      = allGenerators[LoginResolution]
  val loginTokenHashGenerator: AllGenerators[LoginTokenHash]                   = allGenerators[LoginTokenHash]
  val organizationUserGenerator: AllGenerators[OrganizationUser]               = allGenerators[OrganizationUser]

  trait TestCase extends BaseScope with FixedTimeBaseScope {
    val loginTokenHashService: LoginTokenHashService                 = vmock[LoginTokenHashService]
    val userWithIdentitiesFindService: UserWithIdentitiesFindService = vmock[UserWithIdentitiesFindService]
    val userLoginTokenRepository: UserLoginTokenRepository           = vmock[UserLoginTokenRepository]
    val userIdentityRepository: UserIdentityRepository               = vmock[UserIdentityRepository]
    val loginResolveService: LoginResolveService                     = vmock[LoginResolveService]
    val organizationUserRepository: OrganizationUserRepository       = vmock[OrganizationUserRepository]

    val loginByTokenService: LoginByTokenService = wire[LoginByTokenService]

    val supportedIdentityTypes: Set[IdentityType]   = Set(IdentityType.MailAddress, IdentityType.Phone)
    val unsupportedIdentityTypes: Set[IdentityType] = IdentityType.values.toSet -- supportedIdentityTypes
  }

  "login" should {
    "reject a nonexpiring recovery code before confirming an unconfirmed email" in new TestCase {
      val identity = identityGenerator.normal.generate.copy(`type` = IdentityType.MailAddress, confirmed = UserIdentityConfirmed(false))
      val request  = loginByTokenRequestGenerator.normal.generate.copy(email = Some(MailAddress(identity.value)))
      val hashed   = loginTokenHashGenerator.normal.generate
      val token    = userLoginTokenGenerator.normal.generate.copy(userIdentityId = identity.id, loginToken = hashed, validTo = None)
      when(loginTokenHashService.hash(request.token)).thenReturn(hashed)
      when(userLoginTokenRepository.find(UserLoginTokenFilters(loginToken = Some(hashed), validToAfterOrNone = Some(LoginTokenValidTo(now)))))
        .thenReturn(Some(token).asDBIO)
      when(userIdentityRepository.find(UserIdentityFilters(id = Some(identity.id)))).thenReturn(Some(identity).asDBIO)
      when(userIdentityRepository.update(identity.copy(confirmed = UserIdentityConfirmed(true))))
        .thenReturn(Some(identity.copy(confirmed = UserIdentityConfirmed(true))).asDBIO)
      when(userWithIdentitiesFindService.find(identity.userId)).thenReturn(UserWithIdentitiesFindResult.UserDoNotExist.asIO)

      loginByTokenService
        .login(None, request, loginHttpRequestContextGenerator.normal.generate)
        .asserting(_ shouldEqual LoginByTokenResult.InvalidCredentials)
      verify(loginTokenHashService).hash(request.token)
      verify(userLoginTokenRepository).find(UserLoginTokenFilters(loginToken = Some(hashed), validToAfterOrNone = Some(LoginTokenValidTo(now))))
      verify(userIdentityRepository).find(UserIdentityFilters(id = Some(identity.id)))
      org.mockito.Mockito.verify(userIdentityRepository, org.mockito.Mockito.never()).update(any[UserIdentity])
      org.mockito.Mockito.verifyNoInteractions(userWithIdentitiesFindService, organizationUserRepository, loginResolveService)
      org.mockito.Mockito.verify(userLoginTokenRepository, org.mockito.Mockito.never()).purge(any[UserLoginTokenFilters])
    }

    "return UserOrLoginTokenNotFound when token is not present in db" in new TestCase {
      val loginTokenHashed        = loginTokenHashGenerator.normal.generate
      val loginHttpRequestContext = loginHttpRequestContextGenerator.normal.generate
      val auth                    = authContextGenerator.normal.generate
      val request                 = loginByTokenRequestGenerator.normal.generate

      when(loginTokenHashService.hash(request.token)).thenReturn(loginTokenHashed)

      when(
        userLoginTokenRepository.find(UserLoginTokenFilters(loginToken = Some(loginTokenHashed), validToAfterOrNone = Some(LoginTokenValidTo(now))))
      )
        .thenReturn(None.asDBIO)

      loginByTokenService.login(auth, request, loginHttpRequestContext).asserting(_ shouldEqual LoginByTokenResult.UserOrLoginTokenNotFound)

      verify(loginTokenHashService).hash(request.token)

      verify(userLoginTokenRepository).find(
        UserLoginTokenFilters(loginToken = Some(loginTokenHashed), validToAfterOrNone = Some(LoginTokenValidTo(now)))
      )
    }
    "return InvalidCredentials when identity do not exist" in new TestCase {
      val loginTokenHashed        = loginTokenHashGenerator.normal.generate
      val loginHttpRequestContext = loginHttpRequestContextGenerator.normal.generate
      val auth                    = authContextGenerator.normal.generate
      val request                 = loginByTokenRequestGenerator.normal.generate
      val userToken =
        userLoginTokenGenerator.normal.generate
          .copy(loginToken = loginTokenHashed, validTo = Some(LoginTokenValidTo(Instant.ofEpochMilli(System.currentTimeMillis()).plusSeconds(2000))))

      when(loginTokenHashService.hash(request.token)).thenReturn(loginTokenHashed)

      when(
        userLoginTokenRepository.find(UserLoginTokenFilters(loginToken = Some(loginTokenHashed), validToAfterOrNone = Some(LoginTokenValidTo(now))))
      )
        .thenReturn(Some(userToken).asDBIO)

      when(userIdentityRepository.find(UserIdentityFilters(id = Some(userToken.userIdentityId), userId = auth.map(_.id)))).thenReturn(None.asDBIO)

      loginByTokenService.login(auth, request, loginHttpRequestContext).asserting(_ shouldEqual LoginByTokenResult.InvalidCredentials)

      verify(loginTokenHashService).hash(request.token)

      verify(userLoginTokenRepository).find(
        UserLoginTokenFilters(loginToken = Some(loginTokenHashed), validToAfterOrNone = Some(LoginTokenValidTo(now)))
      )

      verify(userIdentityRepository).find(UserIdentityFilters(id = Some(userToken.userIdentityId), userId = auth.map(_.id)))
    }

    "return InvalidCredentials when identity is not supported" in new TestCase {
      val loginTokenHashed        = loginTokenHashGenerator.normal.generate
      val loginHttpRequestContext = loginHttpRequestContextGenerator.normal.generate
      val auth                    = authContextGenerator.normal.generate
      val request                 = loginByTokenRequestGenerator.normal.generate
      val user                    = userGenerator.normal.generate
      val identity = identityGenerator.normal.generate.copy(
        userId = user.id,
        confirmed = UserIdentityConfirmed(true),
        `type` = Random.shuffle(unsupportedIdentityTypes.toSeq).head
      )
      val userToken =
        userLoginTokenGenerator.normal.generate
          .copy(loginToken = loginTokenHashed, validTo = Some(LoginTokenValidTo(Instant.ofEpochMilli(System.currentTimeMillis()).plusSeconds(2000))))

      when(loginTokenHashService.hash(request.token)).thenReturn(loginTokenHashed)

      when(
        userLoginTokenRepository.find(UserLoginTokenFilters(loginToken = Some(loginTokenHashed), validToAfterOrNone = Some(LoginTokenValidTo(now))))
      )
        .thenReturn(Some(userToken).asDBIO)

      when(userIdentityRepository.find(UserIdentityFilters(id = Some(userToken.userIdentityId), userId = auth.map(_.id))))
        .thenReturn(Some(identity).asDBIO)

      loginByTokenService.login(auth, request, loginHttpRequestContext).asserting(_ shouldEqual LoginByTokenResult.InvalidCredentials)

      verify(loginTokenHashService).hash(request.token)

      verify(userLoginTokenRepository).find(
        UserLoginTokenFilters(loginToken = Some(loginTokenHashed), validToAfterOrNone = Some(LoginTokenValidTo(now)))
      )

      verify(userIdentityRepository).find(UserIdentityFilters(id = Some(userToken.userIdentityId), userId = auth.map(_.id)))
    }

    "return InvalidCredentials when identity is phone and no phone number in request" in new TestCase {
      val loginTokenHashed        = loginTokenHashGenerator.normal.generate
      val loginHttpRequestContext = loginHttpRequestContextGenerator.normal.generate
      val auth                    = authContextGenerator.normal.generate
      val request                 = loginByTokenRequestGenerator.minimal.generate
      val user                    = userGenerator.normal.generate
      val identity = identityGenerator.normal.generate.copy(userId = user.id, confirmed = UserIdentityConfirmed(true), `type` = IdentityType.Phone)
      val userToken =
        userLoginTokenGenerator.normal.generate
          .copy(loginToken = loginTokenHashed, validTo = Some(LoginTokenValidTo(Instant.ofEpochMilli(System.currentTimeMillis()).plusSeconds(2000))))

      when(loginTokenHashService.hash(request.token)).thenReturn(loginTokenHashed)

      when(
        userLoginTokenRepository.find(UserLoginTokenFilters(loginToken = Some(loginTokenHashed), validToAfterOrNone = Some(LoginTokenValidTo(now))))
      )
        .thenReturn(Some(userToken).asDBIO)

      when(userIdentityRepository.find(UserIdentityFilters(id = Some(userToken.userIdentityId), userId = auth.map(_.id))))
        .thenReturn(Some(identity).asDBIO)

      loginByTokenService.login(auth, request, loginHttpRequestContext).asserting(_ shouldEqual LoginByTokenResult.InvalidCredentials)

      verify(loginTokenHashService).hash(request.token)

      verify(userLoginTokenRepository).find(
        UserLoginTokenFilters(loginToken = Some(loginTokenHashed), validToAfterOrNone = Some(LoginTokenValidTo(now)))
      )

      verify(userIdentityRepository).find(UserIdentityFilters(id = Some(userToken.userIdentityId), userId = auth.map(_.id)))
    }

    "return InvalidCredentials when identity is email and no email in request" in new TestCase {
      val loginTokenHashed        = loginTokenHashGenerator.normal.generate
      val loginHttpRequestContext = loginHttpRequestContextGenerator.normal.generate
      val auth                    = authContextGenerator.normal.generate
      val request                 = loginByTokenRequestGenerator.minimal.generate
      val user                    = userGenerator.normal.generate
      val identity =
        identityGenerator.normal.generate.copy(userId = user.id, confirmed = UserIdentityConfirmed(true), `type` = IdentityType.MailAddress)
      val userToken =
        userLoginTokenGenerator.normal.generate
          .copy(loginToken = loginTokenHashed, validTo = Some(LoginTokenValidTo(Instant.ofEpochMilli(System.currentTimeMillis()).plusSeconds(2000))))

      when(loginTokenHashService.hash(request.token)).thenReturn(loginTokenHashed)

      when(
        userLoginTokenRepository.find(UserLoginTokenFilters(loginToken = Some(loginTokenHashed), validToAfterOrNone = Some(LoginTokenValidTo(now))))
      )
        .thenReturn(Some(userToken).asDBIO)

      when(userIdentityRepository.find(UserIdentityFilters(id = Some(userToken.userIdentityId), userId = auth.map(_.id))))
        .thenReturn(Some(identity).asDBIO)

      loginByTokenService.login(auth, request, loginHttpRequestContext).asserting(_ shouldEqual LoginByTokenResult.InvalidCredentials)

      verify(loginTokenHashService).hash(request.token)

      verify(userLoginTokenRepository).find(
        UserLoginTokenFilters(loginToken = Some(loginTokenHashed), validToAfterOrNone = Some(LoginTokenValidTo(now)))
      )

      verify(userIdentityRepository).find(UserIdentityFilters(id = Some(userToken.userIdentityId), userId = auth.map(_.id)))
    }

    "return InvalidCredentials when identity is phone and wrong phone number in request" in new TestCase {
      val loginTokenHashed        = loginTokenHashGenerator.normal.generate
      val loginHttpRequestContext = loginHttpRequestContextGenerator.normal.generate
      val auth                    = authContextGenerator.normal.generate
      val request                 = loginByTokenRequestGenerator.maximal.generate
      val user                    = userGenerator.normal.generate
      val identity = identityGenerator.normal.generate.copy(userId = user.id, confirmed = UserIdentityConfirmed(true), `type` = IdentityType.Phone)
      val userToken =
        userLoginTokenGenerator.normal.generate
          .copy(loginToken = loginTokenHashed, validTo = Some(LoginTokenValidTo(Instant.ofEpochMilli(System.currentTimeMillis()).plusSeconds(2000))))

      when(loginTokenHashService.hash(request.token)).thenReturn(loginTokenHashed)

      when(
        userLoginTokenRepository.find(UserLoginTokenFilters(loginToken = Some(loginTokenHashed), validToAfterOrNone = Some(LoginTokenValidTo(now))))
      )
        .thenReturn(Some(userToken).asDBIO)

      when(userIdentityRepository.find(UserIdentityFilters(id = Some(userToken.userIdentityId), userId = auth.map(_.id))))
        .thenReturn(Some(identity).asDBIO)

      loginByTokenService.login(auth, request, loginHttpRequestContext).asserting(_ shouldEqual LoginByTokenResult.InvalidCredentials)

      verify(loginTokenHashService).hash(request.token)

      verify(userLoginTokenRepository).find(
        UserLoginTokenFilters(loginToken = Some(loginTokenHashed), validToAfterOrNone = Some(LoginTokenValidTo(now)))
      )

      verify(userIdentityRepository).find(UserIdentityFilters(id = Some(userToken.userIdentityId), userId = auth.map(_.id)))
    }

    "return InvalidCredentials when identity is email and wrong email in request" in new TestCase {
      val loginTokenHashed        = loginTokenHashGenerator.normal.generate
      val loginHttpRequestContext = loginHttpRequestContextGenerator.normal.generate
      val auth                    = authContextGenerator.normal.generate
      val request                 = loginByTokenRequestGenerator.maximal.generate
      val user                    = userGenerator.normal.generate
      val identity =
        identityGenerator.normal.generate.copy(userId = user.id, confirmed = UserIdentityConfirmed(true), `type` = IdentityType.MailAddress)
      val userToken =
        userLoginTokenGenerator.normal.generate
          .copy(loginToken = loginTokenHashed, validTo = Some(LoginTokenValidTo(Instant.ofEpochMilli(System.currentTimeMillis()).plusSeconds(2000))))

      when(loginTokenHashService.hash(request.token)).thenReturn(loginTokenHashed)

      when(
        userLoginTokenRepository.find(UserLoginTokenFilters(loginToken = Some(loginTokenHashed), validToAfterOrNone = Some(LoginTokenValidTo(now))))
      )
        .thenReturn(Some(userToken).asDBIO)

      when(userIdentityRepository.find(UserIdentityFilters(id = Some(userToken.userIdentityId), userId = auth.map(_.id))))
        .thenReturn(Some(identity).asDBIO)

      loginByTokenService.login(auth, request, loginHttpRequestContext).asserting(_ shouldEqual LoginByTokenResult.InvalidCredentials)

      verify(loginTokenHashService).hash(request.token)

      verify(userLoginTokenRepository).find(
        UserLoginTokenFilters(loginToken = Some(loginTokenHashed), validToAfterOrNone = Some(LoginTokenValidTo(now)))
      )

      verify(userIdentityRepository).find(UserIdentityFilters(id = Some(userToken.userIdentityId), userId = auth.map(_.id)))
    }

    "return UserOrLoginTokenNotFound when user is not present in db" in new TestCase {
      val loginTokenHashed        = loginTokenHashGenerator.normal.generate
      val loginHttpRequestContext = loginHttpRequestContextGenerator.normal.generate
      val auth                    = authContextGenerator.normal.generate
      val user                    = userGenerator.normal.generate
      val identity =
        identityGenerator.normal.generate.copy(userId = user.id, confirmed = UserIdentityConfirmed(true), `type` = IdentityType.MailAddress)
      val request = loginByTokenRequestGenerator.normal.generate.copy(email = Some(MailAddress(identity.value)))
      val userToken = userLoginTokenGenerator.normal.generate.copy(
        userIdentityId = identity.id,
        loginToken = loginTokenHashed,
        validTo = Some(LoginTokenValidTo(Instant.ofEpochMilli(System.currentTimeMillis()).plusSeconds(2000)))
      )
      when(loginTokenHashService.hash(request.token)).thenReturn(loginTokenHashed)

      when(
        userLoginTokenRepository.find(UserLoginTokenFilters(loginToken = Some(loginTokenHashed), validToAfterOrNone = Some(LoginTokenValidTo(now))))
      )
        .thenReturn(Some(userToken).asDBIO)

      when(userIdentityRepository.find(UserIdentityFilters(id = Some(userToken.userIdentityId), userId = auth.map(_.id))))
        .thenReturn(Some(identity).asDBIO)

      when(userWithIdentitiesFindService.find(identity.userId))
        .thenReturn(UserWithIdentitiesFindResult.UserDoNotExist.asIO)

      loginByTokenService.login(auth, request, loginHttpRequestContext).asserting(_ shouldEqual LoginByTokenResult.UserOrLoginTokenNotFound)

      verify(loginTokenHashService).hash(request.token)

      verify(userLoginTokenRepository).find(
        UserLoginTokenFilters(loginToken = Some(loginTokenHashed), validToAfterOrNone = Some(LoginTokenValidTo(now)))
      )

      verify(userIdentityRepository).find(UserIdentityFilters(id = Some(userToken.userIdentityId), userId = auth.map(_.id)))

      verify(userWithIdentitiesFindService).find(identity.userId)
    }
    "allow a nonexpiring recovery code for an already confirmed email" in new TestCase {
      val loginTokenHashed        = loginTokenHashGenerator.normal.generate
      val loginHttpRequestContext = loginHttpRequestContextGenerator.normal.generate
      val auth                    = authContextGenerator.normal.generate
      val user                    = userGenerator.normal.generate
      val identity =
        identityGenerator.normal.generate.copy(userId = user.id, confirmed = UserIdentityConfirmed(true), `type` = IdentityType.MailAddress)
      val request          = loginByTokenRequestGenerator.normal.generate.copy(email = Some(MailAddress(identity.value)))
      val userToken        = userLoginTokenGenerator.normal.generate.copy(userIdentityId = identity.id, loginToken = loginTokenHashed, validTo = None)
      val resultData       = resultDataGenerator.normal.generate
      val organizationUser = organizationUserGenerator.normal.generate

      when(loginTokenHashService.hash(request.token)).thenReturn(loginTokenHashed)

      when(
        userLoginTokenRepository.find(UserLoginTokenFilters(loginToken = Some(loginTokenHashed), validToAfterOrNone = Some(LoginTokenValidTo(now))))
      )
        .thenReturn(Some(userToken).asDBIO)

      when(userIdentityRepository.find(UserIdentityFilters(id = Some(userToken.userIdentityId), userId = auth.map(_.id))))
        .thenReturn(Some(identity).asDBIO)

      when(userWithIdentitiesFindService.find(identity.userId))
        .thenReturn(UserWithIdentitiesFindResult.Ok(user).asIO)

      when(organizationUserRepository.list(any)).thenReturn(Seq(organizationUser).asDBIO)

      when(loginResolveService.resolve(UserLogin(loginHttpRequestContext, user, Seq(organizationUser), auth, identity, Some(CredentialMethod.Token))))
        .thenReturn(resultData.asIO)

      when(userLoginTokenRepository.purge(UserLoginTokenFilters(id = Some(userToken.id))))
        .thenReturn(().asDBIO)

      loginByTokenService.login(auth, request, loginHttpRequestContext).asserting(_ shouldEqual LoginByTokenResult.Ok(resultData))

      verify(loginTokenHashService).hash(request.token)

      verify(userLoginTokenRepository).find(
        UserLoginTokenFilters(loginToken = Some(loginTokenHashed), validToAfterOrNone = Some(LoginTokenValidTo(now)))
      )

      verify(userIdentityRepository).find(UserIdentityFilters(id = Some(userToken.userIdentityId), userId = auth.map(_.id)))

      verify(userWithIdentitiesFindService).find(identity.userId)

      verify(organizationUserRepository).list(OrganizationUserFilters(userId = Some(user.id)))

      verify(loginResolveService).resolve(
        UserLogin(loginHttpRequestContext, user, Seq(organizationUser), auth, identity, Some(CredentialMethod.Token))
      )

      verify(userLoginTokenRepository).purge(UserLoginTokenFilters(id = Some(userToken.id)))
    }

    "reject previously issued phone tokens without confirming identity or opening a session" in new TestCase {
      val identity = identityGenerator.normal.generate.copy(`type` = IdentityType.Phone, confirmed = UserIdentityConfirmed(false))
      val request  = LoginByTokenRequest(None, Some(PhoneNumber(identity.value)), LoginTokenPlain("12345"))
      val hashed   = loginTokenHashGenerator.normal.generate
      val token = userLoginTokenGenerator.normal.generate.copy(
        userIdentityId = identity.id,
        loginToken = hashed,
        validTo = Some(LoginTokenValidTo(now.plusSeconds(2000)))
      )
      when(loginTokenHashService.hash(request.token)).thenReturn(hashed)
      when(userLoginTokenRepository.find(UserLoginTokenFilters(loginToken = Some(hashed), validToAfterOrNone = Some(LoginTokenValidTo(now)))))
        .thenReturn(Some(token).asDBIO)
      when(userIdentityRepository.find(UserIdentityFilters(id = Some(identity.id)))).thenReturn(Some(identity).asDBIO)
      when(userIdentityRepository.update(identity.copy(confirmed = UserIdentityConfirmed(true))))
        .thenReturn(Some(identity.copy(confirmed = UserIdentityConfirmed(true))).asDBIO)
      when(userWithIdentitiesFindService.find(identity.userId)).thenReturn(UserWithIdentitiesFindResult.UserDoNotExist.asIO)

      loginByTokenService
        .login(None, request, loginHttpRequestContextGenerator.normal.generate)
        .asserting(_ shouldEqual LoginByTokenResult.InvalidCredentials)
      verify(loginTokenHashService).hash(request.token)
      verify(userLoginTokenRepository).find(UserLoginTokenFilters(loginToken = Some(hashed), validToAfterOrNone = Some(LoginTokenValidTo(now))))
      verify(userIdentityRepository).find(UserIdentityFilters(id = Some(identity.id)))
      org.mockito.Mockito.verify(userIdentityRepository, org.mockito.Mockito.never()).update(any[UserIdentity])
      org.mockito.Mockito.verifyNoInteractions(userWithIdentitiesFindService, organizationUserRepository, loginResolveService)
      org.mockito.Mockito.verify(userLoginTokenRepository, org.mockito.Mockito.never()).purge(any[UserLoginTokenFilters])
    }

    "return Ok form email generating login result data for user and purge token, confirming identity if not confirmed" in new TestCase {
      val loginTokenHashed        = loginTokenHashGenerator.normal.generate
      val loginHttpRequestContext = loginHttpRequestContextGenerator.normal.generate
      val auth                    = authContextGenerator.normal.generate
      val user                    = userGenerator.normal.generate
      val identity =
        identityGenerator.normal.generate.copy(userId = user.id, confirmed = UserIdentityConfirmed(false), `type` = IdentityType.MailAddress)
      val confirmedIdentity = identity.copy(confirmed = UserIdentityConfirmed(true))
      val request           = loginByTokenRequestGenerator.normal.generate.copy(email = Some(MailAddress(identity.value)))
      val userToken = userLoginTokenGenerator.normal.generate.copy(
        userIdentityId = identity.id,
        loginToken = loginTokenHashed,
        validTo = Some(LoginTokenValidTo(Instant.ofEpochMilli(System.currentTimeMillis()).plusSeconds(2000)))
      )
      val resultData       = resultDataGenerator.normal.generate
      val organizationUser = organizationUserGenerator.normal.generate

      when(loginTokenHashService.hash(request.token)).thenReturn(loginTokenHashed)

      when(
        userLoginTokenRepository.find(UserLoginTokenFilters(loginToken = Some(loginTokenHashed), validToAfterOrNone = Some(LoginTokenValidTo(now))))
      )
        .thenReturn(Some(userToken).asDBIO)

      when(userIdentityRepository.find(UserIdentityFilters(id = Some(userToken.userIdentityId), userId = auth.map(_.id))))
        .thenReturn(Some(identity).asDBIO)

      when(userIdentityRepository.update(confirmedIdentity)).thenReturn(Some(confirmedIdentity).asDBIO)

      when(userWithIdentitiesFindService.find(identity.userId))
        .thenReturn(UserWithIdentitiesFindResult.Ok(user).asIO)

      when(organizationUserRepository.list(any)).thenReturn(Seq(organizationUser).asDBIO)

      when(loginResolveService.resolve(UserLogin(loginHttpRequestContext, user, Seq(organizationUser), auth, identity, Some(CredentialMethod.Token))))
        .thenReturn(resultData.asIO)

      when(userLoginTokenRepository.purge(UserLoginTokenFilters(id = Some(userToken.id))))
        .thenReturn(().asDBIO)

      loginByTokenService.login(auth, request, loginHttpRequestContext).asserting(_ shouldEqual LoginByTokenResult.Ok(resultData))

      verify(loginTokenHashService).hash(request.token)

      verify(userLoginTokenRepository).find(
        UserLoginTokenFilters(loginToken = Some(loginTokenHashed), validToAfterOrNone = Some(LoginTokenValidTo(now)))
      )

      verify(userIdentityRepository).find(UserIdentityFilters(id = Some(userToken.userIdentityId), userId = auth.map(_.id)))

      verify(userIdentityRepository).update(confirmedIdentity)

      verify(userWithIdentitiesFindService).find(identity.userId)

      verify(organizationUserRepository).list(OrganizationUserFilters(userId = Some(user.id)))

      verify(loginResolveService).resolve(
        UserLogin(loginHttpRequestContext, user, Seq(organizationUser), auth, identity, Some(CredentialMethod.Token))
      )

      verify(userLoginTokenRepository).purge(UserLoginTokenFilters(id = Some(userToken.id)))
    }
  }
}
