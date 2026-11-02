package pl.iterators.epoca.user.services

import cats.effect.IO
import org.specs2.matcher.MatchResult
import pl.iterators.epoca.auth.domain.AccountAuthentication.{Credential, CredentialMethod}
import pl.iterators.epoca.auth.domain.AuthDomain.{TimeBasedOneTimePasswordSecret, TimeBasedOneTimePasswordValue}
import pl.iterators.epoca.auth.domain.{AccountAuthentication, AuthContext}
import pl.iterators.epoca.auth.services.{AccountAuthenticationValidator, TimeBasedOneTimePasswordService}
import pl.iterators.epoca.mail.domain.MailDomain._
import pl.iterators.epoca.mail.emails.UserIdentityConfirmationCodeMail
import pl.iterators.epoca.sms.services.SmsService
import pl.iterators.epoca.sms.services.SmsService.SmsSendResult
import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.user.domain.UserDomain._
import pl.iterators.epoca.user.domain.{User, UserIdentity}
import pl.iterators.epoca.user.repositories.UserIdentityRepository.UserIdentityFilters
import pl.iterators.epoca.user.repositories.UserRepository.UserFilters
import pl.iterators.epoca.user.repositories.{EmailIdentityRepository, UserIdentityRepository, UserRepository}
import pl.iterators.epoca.user.services.UserIdentityCreateService.{UserIdentityCreateRequest, UserIdentityCreateResult}
import pl.iterators.epoca.utils.ServiceSpec

import java.time.Instant
import java.util.UUID

class UserIdentityCreateServiceSpec extends ServiceSpec {
  val userIdentityGenerator: AllGenerators[UserIdentity]         = allGenerators[UserIdentity]
  val userGenerator: AllGenerators[User]                         = allGenerators[User]
  val requestGenerator: AllGenerators[UserIdentityCreateRequest] = allGenerators[UserIdentityCreateRequest]

  trait TestCase extends BaseScope {
    val emailIdentityRepository: EmailIdentityRepository = vmock[EmailIdentityRepository]
    when(emailIdentityRepository.lock(any)).thenReturn(().asDBIO)
    val userIdentityRepository: UserIdentityRepository                     = vmock[UserIdentityRepository]
    val userRepository: UserRepository                                     = vmock[UserRepository]
    val mailUserIdentityConfirmationCode: UserIdentityConfirmationCodeMail = vmock[UserIdentityConfirmationCodeMail]
    val smsService: SmsService                                             = vmock[SmsService]
    val timeBasedOneTimePasswordService: TimeBasedOneTimePasswordService   = vmock[TimeBasedOneTimePasswordService]

    val accountAuthenticationValidator: AccountAuthenticationValidator = mock[AccountAuthenticationValidator]
    when(accountAuthenticationValidator.validate(any, any, any)).thenReturn(true.asDBIO)

    val userIdentityCreateService: UserIdentityCreateService = wire[UserIdentityCreateService]

    val freshAuthUser: AuthContext = authUser.copy(
      fresh = true,
      accountAuthentication = Some(AccountAuthentication(Instant.now(), Credential(CredentialMethod.Token, UserIdentityId(UUID.randomUUID()))))
    )
    when(userRepository.find(UserFilters(id = Some(freshAuthUser.id), forUpdate = true)))
      .thenReturn(Some(userGenerator.normal.generate.copy(id = freshAuthUser.id, accountType = UserAccountType.Human, archivedAt = None)).asDBIO)

    override def after: MatchResult[Any] = {
      val mocksIgnoringStubs = ignoreStubs(_allMocks.toList: _*).map(_.asInstanceOf[AnyRef])
      verifyNoMoreInteractions(mocksIgnoringStubs.toIndexedSeq: _*)
      1 shouldEqual 1
    }
  }

  "create" should {
    "deny enrollment by a fresh legacy session without trusted account proof" in new TestCase {
      val request = requestGenerator.maximal.generate.copy(`type` = IdentityType.AdminImpersonate)
      userIdentityCreateService.create(authUser.copy(fresh = true), request).asserting(_ shouldEqual UserIdentityCreateResult.IdentityNotFresh)
    }

    "deny enrollment when current database proof is no longer valid" in new TestCase {
      when(accountAuthenticationValidator.validate(any, any, any)).thenReturn(false.asDBIO)
      val request = requestGenerator.maximal.generate.copy(`type` = IdentityType.Phone)
      userIdentityCreateService.create(freshAuthUser, request).asserting(_ shouldEqual UserIdentityCreateResult.IdentityNotFresh)
      verify(userIdentityRepository, never).insert(any)
    }
    "returns InvalidRequest when auth user is not fresh" in new TestCase {
      val request = requestGenerator.maximal.generate

      userIdentityCreateService.create(authUser.copy(fresh = false), request).asserting(_ shouldEqual UserIdentityCreateResult.IdentityNotFresh)
    }
    "returns the same InvalidRequest without inserting for a service-account auth context" in new TestCase {
      val request = requestGenerator.maximal.generate.copy(
        `type` = IdentityType.MailAddress,
        value = UserIdentityValue("service-account@example.test"),
        main = UserIdentityMain(true)
      )
      val serviceAccount = userGenerator.normal.generate.copy(id = freshAuthUser.id, accountType = UserAccountType.ServiceAccount)
      when(userRepository.find(UserFilters(id = Some(freshAuthUser.id), forUpdate = true))).thenReturn(Some(serviceAccount).asDBIO)

      userIdentityCreateService.create(freshAuthUser, request).asserting(_ shouldEqual UserIdentityCreateResult.InvalidRequest)

      verify(userRepository).find(UserFilters(id = Some(freshAuthUser.id), forUpdate = true))
      verify(userIdentityRepository, never).insert(any)
    }

    "returns the same InvalidRequest without inserting for a nonexistent auth user" in new TestCase {
      val request = requestGenerator.maximal.generate.copy(
        `type` = IdentityType.MailAddress,
        value = UserIdentityValue("missing-user@example.test"),
        main = UserIdentityMain(true)
      )
      when(userRepository.find(UserFilters(id = Some(freshAuthUser.id), forUpdate = true))).thenReturn(None.asDBIO)

      userIdentityCreateService.create(freshAuthUser, request).asserting(_ shouldEqual UserIdentityCreateResult.InvalidRequest)

      verify(userRepository).find(UserFilters(id = Some(freshAuthUser.id), forUpdate = true))
      verify(userIdentityRepository, never).insert(any)
    }
    "returns InvalidRequest when type is AdminHackLogin" in new TestCase {
      val request = requestGenerator.maximal.generate.copy(`type` = IdentityType.AdminImpersonate)

      userIdentityCreateService.create(freshAuthUser, request).asserting(_ shouldEqual UserIdentityCreateResult.InvalidRequest)
    }

    "returns InvalidRequest when type is TimeBasedOneTimePassword and main is set" in new TestCase {
      val request = requestGenerator.maximal.generate.copy(`type` = IdentityType.TimeBasedOneTimePassword, main = UserIdentityMain(true))

      userIdentityCreateService.create(freshAuthUser, request).asserting(_ shouldEqual UserIdentityCreateResult.InvalidRequest)
    }

    "returns InvalidRequest when type is TimeBasedOneTimePassword and main is not set but value is not valid" in new TestCase {
      val request = requestGenerator.maximal.generate.copy(
        `type` = IdentityType.TimeBasedOneTimePassword,
        main = UserIdentityMain(false),
        value = UserIdentityValue("notBase32 79")
      )

      userIdentityCreateService.create(freshAuthUser, request).asserting(_ shouldEqual UserIdentityCreateResult.InvalidRequest)
    }

    "returns InvalidRequest when type is TimeBasedOneTimePassword and main is not set, value is valid but no verification token" in new TestCase {
      val request = requestGenerator.maximal.generate.copy(
        `type` = IdentityType.TimeBasedOneTimePassword,
        main = UserIdentityMain(false),
        value = UserIdentityValue("BASE32"),
        verifyToken = None
      )

      userIdentityCreateService.create(freshAuthUser, request).asserting(_ shouldEqual UserIdentityCreateResult.InvalidRequest)
    }

    "returns InvalidRequest when type is TimeBasedOneTimePassword and main is not set, value is valid but verification token is different than calculated by TimeBasedOneTimePasswordService" in new TestCase {
      val request = requestGenerator.maximal.generate.copy(
        `type` = IdentityType.TimeBasedOneTimePassword,
        main = UserIdentityMain(false),
        value = UserIdentityValue("BASE32"),
        verifyToken = Some(UserIdentityValueVerifyToken("TKN"))
      )

      when(timeBasedOneTimePasswordService.generateDBIO(TimeBasedOneTimePasswordSecret(request.value)))
        .thenReturn(TimeBasedOneTimePasswordValue("TKN2").asDBIO)

      userIdentityCreateService.create(freshAuthUser, request).asserting(_ shouldEqual UserIdentityCreateResult.InvalidRequest)

      verify(timeBasedOneTimePasswordService).generateDBIO(TimeBasedOneTimePasswordSecret(request.value))
    }

    "returns InvalidRequest when email value is not valid" in new TestCase {
      val request = requestGenerator.maximal.generate.copy(`type` = IdentityType.MailAddress, value = UserIdentityValue("notEmail"))

      userIdentityCreateService.create(freshAuthUser, request).asserting(_ shouldEqual UserIdentityCreateResult.InvalidRequest)
    }

    "returns InvalidRequest when phone value is not valid" in new TestCase {
      val request = requestGenerator.maximal.generate.copy(`type` = IdentityType.Phone, value = UserIdentityValue("notPhone"))

      userIdentityCreateService.create(freshAuthUser, request).asserting(_ shouldEqual UserIdentityCreateResult.InvalidRequest)
    }

    "returns InvalidRequest when type is CryptoVerification and device id is not set" in new TestCase {
      val request = requestGenerator.maximal.generate.copy(`type` = IdentityType.CryptoVerification, deviceId = None)

      userIdentityCreateService.create(freshAuthUser, request).asserting(_ shouldEqual UserIdentityCreateResult.InvalidRequest)
    }

    "returns IdentityValueAlreadyExist when value exists in system" in new TestCase {
      val request =
        requestGenerator.maximal.generate.copy(`type` = IdentityType.MailAddress, value = UserIdentityValue("user-61ea07eaa22c@example.test"))
      val existingUserIdentityValue = userIdentityGenerator.maximal.generate.copy(value = request.value)

      when(emailIdentityRepository.resolve(request.value))
        .thenReturn(EmailIdentityRepository.Resolution.Single(userGenerator.normal.generate, Seq(existingUserIdentityValue)).asDBIO)

      userIdentityCreateService.create(freshAuthUser, request).asserting(_ shouldEqual UserIdentityCreateResult.IdentityValueAlreadyExist)

      verify(emailIdentityRepository).resolve(request.value)
    }

    "returns NoMainIdentity when after adding there will be no main identity" in new TestCase {
      val request = requestGenerator.maximal.generate.copy(
        `type` = IdentityType.MailAddress,
        value = UserIdentityValue("user-61ea07eaa22c@example.test"),
        main = UserIdentityMain(false)
      )

      when(emailIdentityRepository.resolve(request.value)).thenReturn(EmailIdentityRepository.Resolution.Empty.asDBIO)
      when(userIdentityRepository.list(UserIdentityFilters(userId = Some(freshAuthUser.id), forUpdate = true))).thenReturn(Seq.empty.asDBIO)

      userIdentityCreateService.create(freshAuthUser, request).asserting(_ shouldEqual UserIdentityCreateResult.NoMainIdentity)

      verify(emailIdentityRepository).resolve(request.value)
      verify(userIdentityRepository).list(UserIdentityFilters(userId = Some(freshAuthUser.id), forUpdate = true))
    }

    "returns Ok for not main identity method" in new TestCase {
      val request = requestGenerator.maximal.generate.copy(
        `type` = IdentityType.MailAddress,
        value = UserIdentityValue("user-61ea07eaa22c@example.test"),
        main = UserIdentityMain(false)
      )
      val newUserIdentity =
        UserIdentity(
          id = UserIdentityId(UUID.randomUUID()),
          userId = freshAuthUser.id,
          `type` = request.`type`,
          value = request.value,
          main = request.main,
          confirmed = UserIdentityConfirmed(false),
          confirmationCode = Some(UserIdentityConfirmationCode("12345678")),
          deviceId = None,
          createdAt = UserIdentityCreatedAt(Instant.now())
        )
      val existingUserIdentityValue = userIdentityGenerator.maximal.generate.copy(userId = freshAuthUser.id, main = UserIdentityMain(true))

      when(emailIdentityRepository.resolve(request.value)).thenReturn(EmailIdentityRepository.Resolution.Empty.asDBIO)
      when(userIdentityRepository.list(UserIdentityFilters(userId = Some(freshAuthUser.id), forUpdate = true)))
        .thenReturn(Seq(existingUserIdentityValue).asDBIO)
      when(userIdentityRepository.insert(any)).thenReturn(newUserIdentity.asDBIO)
      when(
        mailUserIdentityConfirmationCode
          .send(UserIdentityConfirmationCodeMail.Event(MailAddress(newUserIdentity.value), newUserIdentity.confirmationCode.get))
      ).thenReturn(IO.unit)

      userIdentityCreateService
        .create(freshAuthUser, request)
        .asserting(_ shouldEqual UserIdentityCreateResult.Ok(newUserIdentity.copy(confirmationCode = None)))

      verify(emailIdentityRepository).resolve(request.value)
      verify(userIdentityRepository).list(UserIdentityFilters(userId = Some(freshAuthUser.id), forUpdate = true))
      verify(userIdentityRepository).insert(argThat { (userIdentity: UserIdentity) =>
        userIdentity.main == UserIdentityMain(false) &&
        userIdentity.confirmationCode.isDefined
      })
      verify(mailUserIdentityConfirmationCode).send(
        UserIdentityConfirmationCodeMail.Event(MailAddress(newUserIdentity.value), newUserIdentity.confirmationCode.get)
      )
    }

    "returns Ok for main identity method" in new TestCase {
      val request = requestGenerator.maximal.generate.copy(
        `type` = IdentityType.MailAddress,
        value = UserIdentityValue("user-61ea07eaa22c@example.test"),
        main = UserIdentityMain(true)
      )
      val newUserIdentity =
        UserIdentity(
          id = UserIdentityId(UUID.randomUUID()),
          userId = freshAuthUser.id,
          `type` = request.`type`,
          value = request.value,
          main = request.main,
          confirmed = UserIdentityConfirmed(false),
          confirmationCode = Some(UserIdentityConfirmationCode("12345678")),
          deviceId = None,
          createdAt = UserIdentityCreatedAt(Instant.now())
        )
      val existingUserIdentityValue = userIdentityGenerator.maximal.generate.copy(userId = freshAuthUser.id, main = UserIdentityMain(true))

      when(emailIdentityRepository.resolve(request.value)).thenReturn(EmailIdentityRepository.Resolution.Empty.asDBIO)
      when(userIdentityRepository.list(UserIdentityFilters(userId = Some(freshAuthUser.id), forUpdate = true)))
        .thenReturn(Seq(existingUserIdentityValue).asDBIO)
      when(userIdentityRepository.insert(any)).thenReturn(newUserIdentity.asDBIO)
      when(userIdentityRepository.update(any)).thenReturn(Some(existingUserIdentityValue).asDBIO)
      when(
        mailUserIdentityConfirmationCode
          .send(UserIdentityConfirmationCodeMail.Event(MailAddress(newUserIdentity.value), newUserIdentity.confirmationCode.get))
      ).thenReturn(IO.unit)

      userIdentityCreateService
        .create(freshAuthUser, request)
        .asserting(_ shouldEqual UserIdentityCreateResult.Ok(newUserIdentity.copy(confirmationCode = None)))

      verify(emailIdentityRepository).resolve(request.value)
      verify(userIdentityRepository).list(UserIdentityFilters(userId = Some(freshAuthUser.id), forUpdate = true))
      verify(userIdentityRepository).insert(argThat { (userIdentity: UserIdentity) =>
        userIdentity.main == UserIdentityMain(true) &&
        userIdentity.confirmationCode.isDefined
      })
      verify(userIdentityRepository).update(argThat { (userIdentity: UserIdentity) =>
        userIdentity.id == existingUserIdentityValue.id &&
        userIdentity.main == UserIdentityMain(false)
      })
      verify(mailUserIdentityConfirmationCode).send(
        UserIdentityConfirmationCodeMail.Event(MailAddress(newUserIdentity.value), newUserIdentity.confirmationCode.get)
      )
    }

    "returns Ok for phone type" in new TestCase {
      val request =
        requestGenerator.maximal.generate.copy(`type` = IdentityType.Phone, value = UserIdentityValue("+48123456789"), main = UserIdentityMain(false))
      val newUserIdentity =
        UserIdentity(
          id = UserIdentityId(UUID.randomUUID()),
          userId = freshAuthUser.id,
          `type` = request.`type`,
          value = request.value,
          main = request.main,
          confirmed = UserIdentityConfirmed(false),
          confirmationCode = Some(UserIdentityConfirmationCode("12345678")),
          deviceId = None,
          createdAt = UserIdentityCreatedAt(Instant.now())
        )
      val existingUserIdentityValue = userIdentityGenerator.maximal.generate.copy(userId = freshAuthUser.id, main = UserIdentityMain(true))

      when(userIdentityRepository.find(UserIdentityFilters(value = Some(request.value)))).thenReturn(None.asDBIO)
      when(userIdentityRepository.list(UserIdentityFilters(userId = Some(freshAuthUser.id), forUpdate = true)))
        .thenReturn(Seq(existingUserIdentityValue).asDBIO)
      when(userIdentityRepository.insert(any)).thenReturn(newUserIdentity.asDBIO)
      when(smsService.send(any, any)).thenReturn(IO(SmsSendResult.Sent))

      userIdentityCreateService
        .create(freshAuthUser, request)
        .asserting(_ shouldEqual UserIdentityCreateResult.Ok(newUserIdentity.copy(confirmationCode = None)))

      verify(userIdentityRepository).find(UserIdentityFilters(value = Some(request.value)))
      verify(userIdentityRepository).list(UserIdentityFilters(userId = Some(freshAuthUser.id), forUpdate = true))
      verify(userIdentityRepository).insert(argThat { (userIdentity: UserIdentity) =>
        userIdentity.main == UserIdentityMain(false) &&
        userIdentity.confirmationCode.isDefined
      })
      verify(smsService).send(any, any)
    }
    "returns Ok for TimeBasedOneTimePassword type" in new TestCase {
      val request =
        requestGenerator.maximal.generate.copy(
          `type` = IdentityType.TimeBasedOneTimePassword,
          value = UserIdentityValue("BASE32"),
          main = UserIdentityMain(false),
          verifyToken = Some(UserIdentityValueVerifyToken("TKN"))
        )
      val newUserIdentity =
        UserIdentity(
          id = UserIdentityId(UUID.randomUUID()),
          userId = freshAuthUser.id,
          `type` = request.`type`,
          value = request.value,
          main = request.main,
          confirmed = UserIdentityConfirmed(false),
          confirmationCode = None,
          deviceId = None,
          createdAt = UserIdentityCreatedAt(Instant.now())
        )
      val existingUserIdentityValue = userIdentityGenerator.maximal.generate.copy(userId = freshAuthUser.id, main = UserIdentityMain(true))

      when(timeBasedOneTimePasswordService.generateDBIO(TimeBasedOneTimePasswordSecret(request.value)))
        .thenReturn(TimeBasedOneTimePasswordValue("TKN").asDBIO)
      when(userIdentityRepository.find(UserIdentityFilters(value = Some(request.value)))).thenReturn(None.asDBIO)
      when(userIdentityRepository.list(UserIdentityFilters(userId = Some(freshAuthUser.id), forUpdate = true)))
        .thenReturn(Seq(existingUserIdentityValue).asDBIO)
      when(userIdentityRepository.insert(any)).thenReturn(newUserIdentity.asDBIO)

      userIdentityCreateService
        .create(freshAuthUser, request)
        .asserting(_ shouldEqual UserIdentityCreateResult.Ok(newUserIdentity))

      verify(timeBasedOneTimePasswordService).generateDBIO(TimeBasedOneTimePasswordSecret(request.value))
      verify(userIdentityRepository).find(UserIdentityFilters(value = Some(request.value)))
      verify(userIdentityRepository).list(UserIdentityFilters(userId = Some(freshAuthUser.id), forUpdate = true))
      verify(userIdentityRepository).insert(argThat { (userIdentity: UserIdentity) =>
        userIdentity.main == UserIdentityMain(false) &&
        userIdentity.confirmationCode.isEmpty
      })
    }
  }
}
