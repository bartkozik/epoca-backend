package pl.iterators.epoca.auth.services

import pl.iterators.epoca.auth.domain.AccountAuthentication.CredentialMethod
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.auth.domain.AuthDomain._
import pl.iterators.epoca.auth.routers.AuthRouter.LoginHttpRequestContext
import pl.iterators.epoca.auth.services.CryptoVerificationService.CryptoVerificationResult
import pl.iterators.epoca.auth.services.LoginByCryptoVerificationService._
import pl.iterators.epoca.auth.services.LoginResolveService.{LoginResolution, UserLogin}
import pl.iterators.epoca.organization.domain.OrganizationUser
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.user.domain.UserDomain.IdentityType
import pl.iterators.epoca.user.domain.{User, UserIdentity, UserWithIdentities}
import pl.iterators.epoca.user.services.UserWithIdentitiesFindService
import pl.iterators.epoca.user.services.UserWithIdentitiesFindService.UserWithIdentitiesFindResult
import pl.iterators.epoca.utils.ServiceSpec

class LoginByCryptoVerificationServiceSpec extends ServiceSpec {
  val loginHttpRequestContextGenerator: AllGenerators[LoginHttpRequestContext] = allGenerators[LoginHttpRequestContext]
  val authContextGenerator: AllGenerators[Option[AuthContext]]                 = allGenerators[Option[AuthContext]]
  val loginRequestGenerator: AllGenerators[LoginByCryptoVerificationRequest]   = allGenerators[LoginByCryptoVerificationRequest]
  val userGenerator: AllGenerators[User]                                       = allGenerators[User]
  val identityGenerator: AllGenerators[UserIdentity]                           = allGenerators[UserIdentity]
  val resultDataGenerator: AllGenerators[LoginResolution]                      = allGenerators[LoginResolution]
  val organizationUserGenerator: AllGenerators[OrganizationUser]               = allGenerators[OrganizationUser]

  trait TestCase extends BaseScope {
    val loginResolveService: LoginResolveService                     = vmock[LoginResolveService]
    val userWithIdentitiesFindService: UserWithIdentitiesFindService = vmock[UserWithIdentitiesFindService]
    val cryptoVerificationService: CryptoVerificationService         = vmock[CryptoVerificationService]
    val organizationUserRepository: OrganizationUserRepository       = vmock[OrganizationUserRepository]

    val service: LoginByCryptoVerificationService = wire[LoginByCryptoVerificationService]
  }

  "login" should {
    "returns InvalidCredentials when prev auth context contains different id than login request" in new TestCase {
      val loginHttpRequestContext = loginHttpRequestContextGenerator.normal.generate
      val auth                    = authContextGenerator.maximal.generate
      val request                 = loginRequestGenerator.maximal.generate

      service.login(auth, request, loginHttpRequestContext).asserting(_ shouldEqual LoginByCryptoVerificationResult.InvalidCredentials)
    }
    "returns UserNotFound when user do not exist" in new TestCase {
      val loginHttpRequestContext = loginHttpRequestContextGenerator.normal.generate
      val request                 = loginRequestGenerator.maximal.generate
      val auth                    = authContextGenerator.normal.generate.map(_.copy(id = request.userId))

      when(userWithIdentitiesFindService.find(request.userId)).thenReturn(UserWithIdentitiesFindResult.UserDoNotExist.asIO)

      service.login(auth, request, loginHttpRequestContext).asserting(_ shouldEqual LoginByCryptoVerificationResult.UserNotFound)

      verify(userWithIdentitiesFindService).find(request.userId)
    }
    "returns InvalidCredentials when user do not have any crypto identities" in new TestCase {
      val loginHttpRequestContext = loginHttpRequestContextGenerator.normal.generate
      val request                 = loginRequestGenerator.maximal.generate
      val auth                    = authContextGenerator.normal.generate.map(_.copy(id = request.userId))
      val user                    = userGenerator.maximal.generate
      val otherIdentities = Seq(identityGenerator.normal.generate, identityGenerator.normal.generate, identityGenerator.normal.generate)
        .filterNot(_.`type` == IdentityType.CryptoVerification)

      when(userWithIdentitiesFindService.find(request.userId))
        .thenReturn(UserWithIdentitiesFindResult.Ok(UserWithIdentities(user, otherIdentities)).asIO)

      service.login(auth, request, loginHttpRequestContext).asserting(_ shouldEqual LoginByCryptoVerificationResult.InvalidCredentials)

      verify(userWithIdentitiesFindService).find(request.userId)
    }
    "returns InvalidCredentials when crypto service do not return verified for any of user crypto identities" in new TestCase {
      val loginHttpRequestContext = loginHttpRequestContextGenerator.normal.generate
      val request                 = loginRequestGenerator.maximal.generate
      val auth                    = authContextGenerator.normal.generate.map(_.copy(id = request.userId))
      val user                    = userGenerator.maximal.generate
      val i1                      = identityGenerator.normal.generate.copy(`type` = IdentityType.CryptoVerification)
      val i2                      = identityGenerator.normal.generate.copy(`type` = IdentityType.CryptoVerification)
      val i3                      = identityGenerator.normal.generate.copy(`type` = IdentityType.CryptoVerification)
      val otherIdentities = Seq(identityGenerator.normal.generate, identityGenerator.normal.generate, identityGenerator.normal.generate)
        .filterNot(_.`type` == IdentityType.CryptoVerification)
      val identities = Seq(i1, i2, i3).concat(otherIdentities)

      when(userWithIdentitiesFindService.find(request.userId))
        .thenReturn(UserWithIdentitiesFindResult.Ok(UserWithIdentities(user, identities)).asIO)

      when(
        cryptoVerificationService.verify(
          request.algorithm.getOrElse(SignatureAlgorithm.default),
          Base64PublicKey(i1.value),
          request.signature,
          CryptoVerificationData(i1.userId.toString)
        )
      )
        .thenReturn(CryptoVerificationResult.UnableToCreatePublicKey.asIO)
      when(
        cryptoVerificationService.verify(
          request.algorithm.getOrElse(SignatureAlgorithm.default),
          Base64PublicKey(i2.value),
          request.signature,
          CryptoVerificationData(i2.userId.toString)
        )
      )
        .thenReturn(CryptoVerificationResult.NotVerified.asIO)
      when(
        cryptoVerificationService.verify(
          request.algorithm.getOrElse(SignatureAlgorithm.default),
          Base64PublicKey(i3.value),
          request.signature,
          CryptoVerificationData(i3.userId.toString)
        )
      )
        .thenReturn(CryptoVerificationResult.InvalidAlgorithm.asIO)

      service.login(auth, request, loginHttpRequestContext).asserting(_ shouldEqual LoginByCryptoVerificationResult.InvalidCredentials)

      verify(userWithIdentitiesFindService).find(request.userId)

      verify(cryptoVerificationService).verify(
        request.algorithm.getOrElse(SignatureAlgorithm.default),
        Base64PublicKey(i1.value),
        request.signature,
        CryptoVerificationData(i1.userId.toString)
      )
      verify(cryptoVerificationService).verify(
        request.algorithm.getOrElse(SignatureAlgorithm.default),
        Base64PublicKey(i2.value),
        request.signature,
        CryptoVerificationData(i2.userId.toString)
      )
      verify(cryptoVerificationService).verify(
        request.algorithm.getOrElse(SignatureAlgorithm.default),
        Base64PublicKey(i3.value),
        request.signature,
        CryptoVerificationData(i3.userId.toString)
      )
    }
    "return OK and generate login data from from identity if any identity return Verified in verification service" in new TestCase {
      val loginHttpRequestContext = loginHttpRequestContextGenerator.normal.generate
      val request                 = loginRequestGenerator.maximal.generate
      val auth                    = authContextGenerator.normal.generate.map(_.copy(id = request.userId))
      val user                    = userGenerator.maximal.generate
      val i1                      = identityGenerator.normal.generate.copy(`type` = IdentityType.CryptoVerification)
      val i2                      = identityGenerator.normal.generate.copy(`type` = IdentityType.CryptoVerification)
      val i3                      = identityGenerator.normal.generate.copy(`type` = IdentityType.CryptoVerification)
      val otherIdentities = Seq(identityGenerator.normal.generate, identityGenerator.normal.generate, identityGenerator.normal.generate)
        .filterNot(_.`type` == IdentityType.CryptoVerification)
      val identities       = Seq(i1, i2, i3).concat(otherIdentities)
      val resultData       = resultDataGenerator.normal.generate
      val organizationUser = organizationUserGenerator.normal.generate

      when(userWithIdentitiesFindService.find(request.userId))
        .thenReturn(UserWithIdentitiesFindResult.Ok(UserWithIdentities(user, identities)).asIO)

      when(
        cryptoVerificationService.verify(
          request.algorithm.getOrElse(SignatureAlgorithm.default),
          Base64PublicKey(i1.value),
          request.signature,
          CryptoVerificationData(i1.userId.toString)
        )
      )
        .thenReturn(CryptoVerificationResult.UnableToCreatePublicKey.asIO)
      when(
        cryptoVerificationService.verify(
          request.algorithm.getOrElse(SignatureAlgorithm.default),
          Base64PublicKey(i2.value),
          request.signature,
          CryptoVerificationData(i2.userId.toString)
        )
      )
        .thenReturn(CryptoVerificationResult.Verified.asIO)
      when(
        cryptoVerificationService.verify(
          request.algorithm.getOrElse(SignatureAlgorithm.default),
          Base64PublicKey(i3.value),
          request.signature,
          CryptoVerificationData(i3.userId.toString)
        )
      )
        .thenReturn(CryptoVerificationResult.Verified.asIO)

      when(organizationUserRepository.list(any)).thenReturn(Seq(organizationUser).asDBIO)

      when(
        loginResolveService.resolve(
          UserLogin(
            loginHttpRequestContext,
            UserWithIdentities(user, identities),
            Seq(organizationUser),
            auth,
            i2,
            Some(CredentialMethod.CryptoVerification)
          )
        )
      )
        .thenReturn(resultData.asIO)

      service.login(auth, request, loginHttpRequestContext).asserting(_ shouldEqual LoginByCryptoVerificationResult.Ok(resultData))

      verify(userWithIdentitiesFindService).find(request.userId)

      verify(cryptoVerificationService).verify(
        request.algorithm.getOrElse(SignatureAlgorithm.default),
        Base64PublicKey(i1.value),
        request.signature,
        CryptoVerificationData(i1.userId.toString)
      )
      verify(cryptoVerificationService).verify(
        request.algorithm.getOrElse(SignatureAlgorithm.default),
        Base64PublicKey(i2.value),
        request.signature,
        CryptoVerificationData(i2.userId.toString)
      )
      verify(cryptoVerificationService).verify(
        request.algorithm.getOrElse(SignatureAlgorithm.default),
        Base64PublicKey(i3.value),
        request.signature,
        CryptoVerificationData(i3.userId.toString)
      )
      verify(organizationUserRepository).list(OrganizationUserFilters(userId = Some(user.id)))
      verify(loginResolveService).resolve(
        UserLogin(
          loginHttpRequestContext,
          UserWithIdentities(user, identities),
          Seq(organizationUser),
          auth,
          i2,
          Some(CredentialMethod.CryptoVerification)
        )
      )
    }
  }
}
