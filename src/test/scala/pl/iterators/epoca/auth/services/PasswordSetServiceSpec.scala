package pl.iterators.epoca.auth.services

import pl.iterators.epoca.auth.domain.AccountAuthentication.{Credential, CredentialMethod}
import pl.iterators.epoca.auth.domain.AuthDomain._
import pl.iterators.epoca.auth.domain.{AccountAuthentication, AuthContext, UserPassword}
import pl.iterators.epoca.auth.repositories.UserPasswordRepository
import pl.iterators.epoca.auth.services.PasswordSetService.{PasswordSetRequest, PasswordSetResult}
import pl.iterators.epoca.auth.services.PasswordValidateService.{PasswordValidateProblem, PasswordValidateResult}
import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.user.domain.UserDomain.IdentityType
import pl.iterators.epoca.user.domain.{User, UserIdentity}
import pl.iterators.epoca.user.repositories.UserIdentityRepository.UserIdentityFilters
import pl.iterators.epoca.user.repositories.UserRepository.UserFilters
import pl.iterators.epoca.user.repositories.{UserIdentityRepository, UserRepository}
import pl.iterators.epoca.utils.ServiceSpec

import scala.util.Random

class PasswordSetServiceSpec extends ServiceSpec {
  val identityGenerator: AllGenerators[UserIdentity] = allGenerators[UserIdentity]

  trait TestCase extends BaseScope {
    val userPasswordRepository: UserPasswordRepository   = vmock[UserPasswordRepository]
    val userIdentityRepository: UserIdentityRepository   = vmock[UserIdentityRepository]
    val passwordValidateService: PasswordValidateService = vmock[PasswordValidateService]
    val passwordService: PasswordService                 = vmock[PasswordService]

    val userRepository: UserRepository                                 = mock[UserRepository]
    val accountAuthenticationValidator: AccountAuthenticationValidator = mock[AccountAuthenticationValidator]
    when(userRepository.find(any[UserFilters])).thenReturn(Some(allGenerators[User].normal.generate.copy(id = authUser.id, archivedAt = None)).asDBIO)
    when(accountAuthenticationValidator.validate(any, any, any)).thenReturn(true.asDBIO)

    val identity = identityGenerator.normal.generate

    val trustedAuth: AuthContext = authUser.copy(
      fresh = true,
      accountAuthentication = Some(AccountAuthentication(java.time.Instant.now(), Credential(CredentialMethod.Token, identity.id)))
    )

    val passwordSetService: PasswordSetService = wire[PasswordSetService]

    val supportedIdentityTypes: Set[IdentityType]   = Set(IdentityType.MailAddress, IdentityType.Phone)
    val unsupportedIdentityTypes: Set[IdentityType] = IdentityType.values.toSet -- supportedIdentityTypes
  }

  "passwordSet" should {
    "deny a fresh legacy SAML session without trusted account proof" in new TestCase {
      val request = PasswordSetRequest(identity.id, PasswordPlain("weak"))
      when(passwordValidateService.validate(request.newPassword)).thenReturn(PasswordValidateResult.Invalid(Set.empty))
      passwordSetService.passwordSet(authUser.copy(fresh = true), request).asserting(_ shouldEqual PasswordSetResult.InvalidAuthContext)
    }

    "deny password changes when current database proof is no longer valid" in new TestCase {
      when(accountAuthenticationValidator.validate(any, any, any)).thenReturn(false.asDBIO)
      passwordSetService
        .passwordSet(trustedAuth, PasswordSetRequest(identity.id, PasswordPlain("password")))
        .asserting(_ shouldEqual PasswordSetResult.InvalidAuthContext)
      verify(userPasswordRepository, never).upsert(any)
    }
    "return InvalidAuthContext when auth context is not fresh" in new TestCase {
      val plainPassword = PasswordPlain("pas")
      val request       = PasswordSetRequest(identity.id, plainPassword)

      passwordSetService.passwordSet(authUser.copy(fresh = false), request).asserting(_ shouldEqual PasswordSetResult.InvalidAuthContext)
    }
    "return InvalidPassword when password validate service returns valse" in new TestCase {
      val plainPassword = PasswordPlain("pas")
      val request       = PasswordSetRequest(identity.id, plainPassword)
      val problems      = PasswordValidateProblem.values.take(2).toSet

      when(passwordValidateService.validate(plainPassword)).thenReturn(PasswordValidateResult.Invalid(problems))

      passwordSetService.passwordSet(trustedAuth, request).asserting(_ shouldEqual PasswordSetResult.InvalidPassword(problems))

      verify(passwordValidateService).validate(plainPassword)
    }
    "return IdentityNotFound when identity do not exist" in new TestCase {
      val plainPassword = PasswordPlain("password")
      val request       = PasswordSetRequest(identity.id, plainPassword)

      when(passwordValidateService.validate(plainPassword)).thenReturn(PasswordValidateResult.Valid)

      when(
        userIdentityRepository
          .find(UserIdentityFilters(id = Some(request.userIdentityId), userId = Some(authUser.id), forUpdate = true))
      ).thenReturn(None.asDBIO)

      passwordSetService.passwordSet(trustedAuth, request).asserting(_ shouldEqual PasswordSetResult.IdentityNotFound)

      verify(passwordValidateService).validate(plainPassword)

      verify(userIdentityRepository)
        .find(UserIdentityFilters(id = Some(request.userIdentityId), userId = Some(authUser.id), forUpdate = true))
    }
    "return IdentityNotFound when identity is not supported" in new TestCase {
      val plainPassword = PasswordPlain("password")
      val request       = PasswordSetRequest(identity.id, plainPassword)

      when(passwordValidateService.validate(plainPassword)).thenReturn(PasswordValidateResult.Valid)

      when(
        userIdentityRepository
          .find(UserIdentityFilters(id = Some(request.userIdentityId), userId = Some(authUser.id), forUpdate = true))
      ).thenReturn(Some(identity.copy(`type` = Random.shuffle(unsupportedIdentityTypes.toSeq).head)).asDBIO)

      passwordSetService.passwordSet(trustedAuth, request).asserting(_ shouldEqual PasswordSetResult.IdentityNotFound)

      verify(passwordValidateService).validate(plainPassword)

      verify(userIdentityRepository)
        .find(UserIdentityFilters(id = Some(request.userIdentityId), userId = Some(authUser.id), forUpdate = true))
    }

    "return PasswordNotChanged when hashed password is same as before" in new TestCase {
      val plainPassword  = PasswordPlain("password")
      val hashedPassword = PasswordHash("hashed")
      val userPassword   = UserPassword(identity.id, hashedPassword)
      val request        = PasswordSetRequest(identity.id, plainPassword)

      when(passwordValidateService.validate(plainPassword)).thenReturn(PasswordValidateResult.Valid)

      when(
        userIdentityRepository
          .find(UserIdentityFilters(id = Some(request.userIdentityId), userId = Some(authUser.id), forUpdate = true))
      ).thenReturn(Some(identity.copy(`type` = Random.shuffle(supportedIdentityTypes.toSeq).head)).asDBIO)

      when(passwordService.encrypt(request.newPassword)).thenReturn(hashedPassword)

      when(userPasswordRepository.find(identity.id)).thenReturn(Some(userPassword).asDBIO)

      passwordSetService.passwordSet(trustedAuth, request).asserting(_ shouldEqual PasswordSetResult.PasswordNotChanged)

      verify(passwordValidateService).validate(plainPassword)

      verify(userIdentityRepository)
        .find(UserIdentityFilters(id = Some(request.userIdentityId), userId = Some(authUser.id), forUpdate = true))

      verify(passwordService).encrypt(request.newPassword)

      verify(userPasswordRepository).find(identity.id)
    }
    "return OK and set password in db otherwise" in new TestCase {
      val plainPassword  = PasswordPlain("password")
      val hashedPassword = PasswordHash("hashed")
      val request        = PasswordSetRequest(identity.id, plainPassword)
      val userPassword   = UserPassword(identity.id, hashedPassword)

      when(passwordValidateService.validate(plainPassword)).thenReturn(PasswordValidateResult.Valid)

      when(
        userIdentityRepository
          .find(UserIdentityFilters(id = Some(request.userIdentityId), userId = Some(authUser.id), forUpdate = true))
      ).thenReturn(Some(identity.copy(`type` = Random.shuffle(supportedIdentityTypes.toSeq).head)).asDBIO)

      when(passwordService.encrypt(request.newPassword)).thenReturn(hashedPassword)

      when(userPasswordRepository.find(identity.id)).thenReturn(Some(userPassword.copy(password = PasswordHash("different"))).asDBIO)

      when(userPasswordRepository.upsert(userPassword)).thenReturn(userPassword.asDBIO)

      passwordSetService.passwordSet(trustedAuth, request).asserting(_ shouldEqual PasswordSetResult.Ok)

      verify(passwordValidateService).validate(plainPassword)

      verify(userIdentityRepository)
        .find(UserIdentityFilters(id = Some(request.userIdentityId), userId = Some(authUser.id), forUpdate = true))

      verify(passwordService).encrypt(request.newPassword)

      verify(userPasswordRepository).find(identity.id)

      verify(userPasswordRepository).upsert(userPassword)
    }
  }
}
