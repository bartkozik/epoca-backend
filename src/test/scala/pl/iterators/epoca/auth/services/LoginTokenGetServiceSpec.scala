package pl.iterators.epoca.auth.services

import pl.iterators.epoca.auth.domain.AuthDomain._
import pl.iterators.epoca.auth.domain.UserLoginToken
import pl.iterators.epoca.auth.repositories.UserLoginTokenRepository
import pl.iterators.epoca.auth.services.LoginTokenGenerateService.UserLoginTokenWithLoginTokenPlain
import pl.iterators.epoca.auth.services.LoginTokenGetService.{LoginTokenGetRequest, LoginTokenGetResult}
import pl.iterators.epoca.mail.domain.MailDomain.MailAddress
import pl.iterators.epoca.mail.emails.UserMagicLoginLink
import pl.iterators.epoca.sms.domain.SmsDomain.PhoneNumber
import pl.iterators.epoca.sms.smses.UserTokenSms
import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.user.domain.UserDomain._
import pl.iterators.epoca.user.domain.{User, UserIdentity, UserWithIdentities}
import pl.iterators.epoca.user.repositories.UserRepository
import pl.iterators.epoca.user.repositories.UserRepository.UserFilters
import pl.iterators.epoca.user.services.UserWithIdentitiesFindService
import pl.iterators.epoca.user.services.UserWithIdentitiesFindService.UserWithIdentitiesFindResult
import pl.iterators.epoca.utils.ServiceSpec

class LoginTokenGetServiceSpec extends ServiceSpec {

  val userLoginTokenGenerator: AllGenerators[UserLoginToken]      = allGenerators[UserLoginToken]
  val userGenerator: AllGenerators[User]                          = allGenerators[User]
  val identityGenerator: AllGenerators[UserIdentity]              = allGenerators[UserIdentity]
  val configGenerator: AllGenerators[LoginTokenGetService.Config] = allGenerators[LoginTokenGetService.Config]

  trait TestCase extends BaseScope {
    val loginTokenGenerateService: LoginTokenGenerateService         = vmock[LoginTokenGenerateService]
    val userRepository: UserRepository                               = vmock[UserRepository]
    val userWithIdentitiesFindService: UserWithIdentitiesFindService = vmock[UserWithIdentitiesFindService]
    val userLoginTokenRepository: UserLoginTokenRepository           = vmock[UserLoginTokenRepository]
    val mailUserMagicLoginLink: UserMagicLoginLink                   = vmock[UserMagicLoginLink]
    val userTokenSms: UserTokenSms                                   = vmock[UserTokenSms]
    val config                                                       = configGenerator.normal.generate

    val loginTokenGetService: LoginTokenGetService = wire[LoginTokenGetService]
  }

  "getToken" should {
    "return InvalidRequest if phone and mail not provided" in new TestCase {
      val request = LoginTokenGetRequest(email = None, phoneNumber = None, additionalUrlParams = None)

      loginTokenGetService.getToken(request).asserting(_ shouldEqual LoginTokenGetResult.InvalidRequest)
    }
    "return InvalidRequest if phone and mail provided" in new TestCase {
      val request = LoginTokenGetRequest(email = Some(MailAddress("m@m.m")), phoneNumber = Some(PhoneNumber("+49")), additionalUrlParams = None)

      loginTokenGetService.getToken(request).asserting(_ shouldEqual LoginTokenGetResult.InvalidRequest)
    }
    "return UserNotFound when user with certain email is not present in db" in new TestCase {
      val request = LoginTokenGetRequest(email = Some(MailAddress("m@m.m")), phoneNumber = None, additionalUrlParams = None)

      when(userRepository.find(UserFilters(email = Some(request.email.get))))
        .thenReturn(None.asDBIO)

      loginTokenGetService.getToken(request).asserting(_ shouldEqual LoginTokenGetResult.UserNotFound)

      verify(userRepository).find(UserFilters(email = Some(request.email.get)))
    }
    "return UserNotFound when user identity with certain email is not present in db" in new TestCase {
      val request = LoginTokenGetRequest(email = Some(MailAddress("m@m.m")), phoneNumber = None, additionalUrlParams = None)
      val user    = userGenerator.normal.generate

      when(userRepository.find(UserFilters(email = Some(request.email.get))))
        .thenReturn(Some(user).asDBIO)

      when(userWithIdentitiesFindService.find(user.id)).thenReturn(UserWithIdentitiesFindResult.UserDoNotExist.asIO)

      loginTokenGetService.getToken(request).asserting(_ shouldEqual LoginTokenGetResult.UserNotFound)

      verify(userRepository).find(UserFilters(email = Some(request.email.get)))

      verify(userWithIdentitiesFindService).find(user.id)
    }
    "return UserNotFound when proper identity do not exist" in new TestCase {
      val request  = LoginTokenGetRequest(email = Some(MailAddress("m@m.m")), phoneNumber = None, additionalUrlParams = None)
      val user     = userGenerator.normal.generate
      val identity = identityGenerator.normal.generate

      when(userRepository.find(UserFilters(email = Some(request.email.get))))
        .thenReturn(Some(user).asDBIO)

      when(userWithIdentitiesFindService.find(user.id)).thenReturn(UserWithIdentitiesFindResult.Ok(UserWithIdentities(user, Seq(identity))).asIO)

      loginTokenGetService.getToken(request).asserting(_ shouldEqual LoginTokenGetResult.UserNotFound)

      verify(userRepository).find(UserFilters(email = Some(request.email.get)))

      verify(userWithIdentitiesFindService).find(user.id)
    }
    "return OK otherwise and generate token and send it to client by email" in new TestCase {
      val request = LoginTokenGetRequest(email = Some(MailAddress("m@m.m")), phoneNumber = None, additionalUrlParams = None)
      val user    = userGenerator.normal.generate
      val identity =
        identityGenerator.normal.generate.copy(userId = user.id, `type` = IdentityType.MailAddress, value = UserIdentityValue(request.email.get))
      val token               = userLoginTokenGenerator.normal.generate
      val tokenGenerateResult = UserLoginTokenWithLoginTokenPlain(token, LoginTokenPlain(""))

      when(userRepository.find(UserFilters(email = Some(request.email.get))))
        .thenReturn(Some(user).asDBIO)

      when(userWithIdentitiesFindService.find(user.id)).thenReturn(UserWithIdentitiesFindResult.Ok(UserWithIdentities(user, Seq(identity))).asIO)

      when(loginTokenGenerateService.generateToken(identity, true)).thenReturn(tokenGenerateResult.asIO)

      when(userLoginTokenRepository.insert(any)).thenReturn(token.asDBIO)

      when(mailUserMagicLoginLink.send(any)).thenReturn(().asIO)

      loginTokenGetService.getToken(request).asserting(_ shouldEqual LoginTokenGetResult.Ok)

      verify(userRepository).find(UserFilters(email = Some(request.email.get)))

      verify(userWithIdentitiesFindService).find(user.id)

      verify(loginTokenGenerateService).generateToken(identity, true)

      verify(userLoginTokenRepository).insert(token)

      verify(mailUserMagicLoginLink).send(any)
    }
    "reject phone issuance before looking up accounts or creating tokens" in new TestCase {
      val request = LoginTokenGetRequest(None, Some(PhoneNumber("+488888")), None)
      when(userRepository.find(any)).thenReturn(None.asDBIO)
      loginTokenGetService.getToken(request).asserting(_ shouldEqual LoginTokenGetResult.InvalidRequest)
      org.mockito.Mockito.verifyNoInteractions(
        userRepository,
        userWithIdentitiesFindService,
        loginTokenGenerateService,
        userLoginTokenRepository,
        userTokenSms,
        mailUserMagicLoginLink
      )
    }
  }
}
