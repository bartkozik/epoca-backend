package pl.iterators.epoca.user.services

import com.typesafe.config.ConfigFactory
import pl.iterators.epoca.auth.domain.AuthDomain.{PasswordHash, PasswordPlain}
import pl.iterators.epoca.auth.repositories.{UserLoginTokenRepository, UserPasswordRepository}
import pl.iterators.epoca.auth.services.LoginTokenGenerateService.UserLoginTokenWithLoginTokenPlain
import pl.iterators.epoca.auth.services.LoginTokenGetService.{LoginTokenGetRequest, LoginTokenGetResult}
import pl.iterators.epoca.auth.services.PasswordValidateService.{PasswordValidateProblem, PasswordValidateResult}
import pl.iterators.epoca.auth.services.{LoginTokenGenerateService, LoginTokenGetService, PasswordService, PasswordValidateService}
import pl.iterators.epoca.mail.domain.MailDomain.MailAddress
import pl.iterators.epoca.sms.domain.SmsDomain.PhoneNumber
import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.user.domain.UserDomain._
import pl.iterators.epoca.user.domain.{User, UserIdentity}
import pl.iterators.epoca.user.repositories.UserRepository.UserFilters
import pl.iterators.epoca.user.repositories.{EmailIdentityRepository, UserIdentityRepository, UserRepository}
import pl.iterators.epoca.user.services.UserCreateService.{UserCreateRequest, UserCreateResult, UserCreateResultData, UserCreateSamlRequest}
import pl.iterators.epoca.utils.ServiceSpec
import pl.iterators.epoca.utils.transactor.TransactorCall
class UserCreateServiceSpec extends ServiceSpec {

  val requestGenerator: AllGenerators[UserCreateRequest]               = allGenerators[UserCreateRequest]
  val userGenerator: AllGenerators[User]                               = allGenerators[User]
  val userIdentityGenerator: AllGenerators[UserIdentity]               = allGenerators[UserIdentity]
  val tokenGenerator: AllGenerators[UserLoginTokenWithLoginTokenPlain] = allGenerators[UserLoginTokenWithLoginTokenPlain]

  private val superAdminKey = "proper-admin-key-0123456789-abcdefgh"

  // Generated requests may carry a random super admin key; ordinary sign-up requests send none.
  private def plainRequest: UserCreateRequest = requestGenerator.normal.generate.copy(superAdminKey = None)

  trait TestCase extends BaseScope {
    def configuredSuperAdminKey: Option[String]          = Some(superAdminKey)
    val config: UserCreateService.Config                 = UserCreateService.Config(configuredSuperAdminKey, recoveryCount = 2)
    val userRepository: UserRepository                   = vmock[UserRepository]
    val userIdentityRepository: UserIdentityRepository   = vmock[UserIdentityRepository]
    val loginTokenGetService: LoginTokenGetService       = vmock[LoginTokenGetService]
    val passwordService: PasswordService                 = vmock[PasswordService]
    val userPasswordRepository: UserPasswordRepository   = vmock[UserPasswordRepository]
    val emailIdentityRepository: EmailIdentityRepository = vmock[EmailIdentityRepository]
    val userCreationActions: UserCreationActions         = wire[UserCreationActions]
    when(emailIdentityRepository.lock(any)).thenReturn(().asDBIO)
    when(userPasswordRepository.upsert(any)).thenAnswerArgAsDBIO()
    val passwordValidateService: PasswordValidateService     = vmock[PasswordValidateService]
    val userLoginTokenRepository: UserLoginTokenRepository   = vmock[UserLoginTokenRepository]
    val loginTokenGenerateService: LoginTokenGenerateService = vmock[LoginTokenGenerateService]

    val userCreateService: UserCreateService = wire[UserCreateService]
  }

  "createWithSaml" should {
    "return a controlled conflict for a reserved canonical address" in new TestCase {
      val user    = userGenerator.normal.generate
      val request = UserCreateSamlRequest(UserFirstName("Anna"), UserLastName("Test"), MailAddress("Anna@example.test"))
      when(emailIdentityRepository.resolve(request.email)).thenReturn(EmailIdentityRepository.Resolution.Single(user, Nil).asDBIO)
      userCreateService.createWithSaml(request).asserting(_ shouldEqual UserCreateResult.IdentityAlreadyExist)
      verify(emailIdentityRepository).lock(request.email)
      verify(emailIdentityRepository).resolve(request.email)
    }
    "create a confirmed SAML identity without password, mail or recovery codes" in new TestCase {
      val user     = userGenerator.normal.generate
      val identity = userIdentityGenerator.normal.generate
      val request  = UserCreateSamlRequest(UserFirstName("Anna"), UserLastName("Test"), MailAddress("Anna@example.test"))
      when(emailIdentityRepository.resolve(request.email)).thenReturn(EmailIdentityRepository.Resolution.Empty.asDBIO)
      when(userRepository.insert(any)).thenReturn(user.asDBIO)
      when(userIdentityRepository.insert(any)).thenReturn(identity.asDBIO)
      userCreateService.createWithSaml(request).asserting(_ shouldEqual UserCreateResult.Ok(UserCreateResultData(user, Seq.empty)))
      verify(emailIdentityRepository).lock(request.email)
      verify(emailIdentityRepository).resolve(request.email)
      verify(userRepository).insert(any)
      verify(userIdentityRepository).insert(argThat { (i: UserIdentity) =>
        i.`type` == IdentityType.Saml && i.value == request.email && i.confirmed
      })
    }
  }

  "create" should {
    "return InvalidRequest if phone and mail not provided" in new TestCase {
      val request = plainRequest.copy(email = None, phoneNumber = None, password = None)

      userCreateService.create(request).asserting(_ shouldEqual UserCreateResult.InvalidRequest)
    }
    "return InvalidRequest if phone and mail provided" in new TestCase {
      val request = plainRequest.copy(email = Some(MailAddress("m@m.m")), phoneNumber = Some(PhoneNumber("+49")), password = None)

      userCreateService.create(request).asserting(_ shouldEqual UserCreateResult.InvalidRequest)
    }
    "return InvalidPassword if provided password is invalid" in new TestCase {
      val request =
        plainRequest.copy(email = Some(MailAddress("m@m.m")), phoneNumber = None, password = Some(PasswordPlain("test")))

      val problems = PasswordValidateProblem.values.take(2).toSet

      when(passwordValidateService.validate(request.password.get)).thenReturn(PasswordValidateResult.Invalid(problems))

      userCreateService.create(request).asserting(_ shouldEqual UserCreateResult.InvalidPassword(problems))

      verify(passwordValidateService).validate(request.password.get)
    }

    "returns IdentityAlreadyExist if email exists in repository" in new TestCase {
      val request = plainRequest.copy(email = Some(MailAddress("m@m.m")), phoneNumber = None, password = None)
      val user    = userGenerator.maximal.generate

      when(emailIdentityRepository.resolve(request.email.get)).thenReturn(EmailIdentityRepository.Resolution.Single(user, Nil).asDBIO)

      userCreateService.create(request).asserting(_ shouldEqual UserCreateResult.IdentityAlreadyExist)

      verify(emailIdentityRepository).lock(request.email.get)
      verify(emailIdentityRepository).resolve(request.email.get)
    }
    "reject a reserved email even when a valid super admin key is supplied" in new TestCase {
      val request = plainRequest.copy(
        email = Some(MailAddress("Reserved@example.test")),
        phoneNumber = None,
        password = None,
        superAdminKey = Some(superAdminKey)
      )
      val user = userGenerator.normal.generate
      when(emailIdentityRepository.resolve(request.email.get)).thenReturn(EmailIdentityRepository.Resolution.Single(user, Nil).asDBIO)

      userCreateService.create(request).asserting(_ shouldEqual UserCreateResult.IdentityAlreadyExist)

      verify(emailIdentityRepository).lock(request.email.get)
      verify(emailIdentityRepository).resolve(request.email.get)
      transactorCalls shouldEqual Seq(TransactorCall.ExecuteTransactionally)
    }
    "create an ordinary unconfirmed account transactionally when super admin creation is disabled" in new TestCase {
      override def configuredSuperAdminKey: Option[String] = None

      val request =
        plainRequest.copy(email = Some(MailAddress("ordinary@example.test")), phoneNumber = None, password = None, doNotSendLoginToken = Some(true))
      val token1 = tokenGenerator.normal.generate
      val token2 = tokenGenerator.normal.generate
      when(emailIdentityRepository.resolve(request.email.get)).thenReturn(EmailIdentityRepository.Resolution.Empty.asDBIO)
      when(userRepository.insert(any)).thenAnswerArgAsDBIO()
      when(userIdentityRepository.insert(any)).thenAnswerArgAsDBIO()
      when(loginTokenGenerateService.generateToken(any, eqTo(false))).thenReturn(token1.asIO, token2.asIO)
      when(userLoginTokenRepository.insert(any)).thenAnswerArgAsDBIO()

      userCreateService.create(request).asserting {
        case UserCreateResult.Ok(data) => data.user.isSuperAdmin shouldEqual UserIsSuperAdmin(false)
        case other                     => other should beAnInstanceOf[UserCreateResult.Ok]
      }

      verify(emailIdentityRepository).lock(request.email.get)
      verify(emailIdentityRepository).resolve(request.email.get)
      verify(userRepository).insert(argThat((user: User) => !user.isSuperAdmin))
      verify(userIdentityRepository).insert(argThat { (identity: UserIdentity) =>
        identity.`type` == IdentityType.MailAddress && identity.value == request.email.get && !identity.confirmed
      })
      verify(loginTokenGenerateService, times(2)).generateToken(any, eqTo(false))
      verify(userLoginTokenRepository).insert(token1.token)
      verify(userLoginTokenRepository).insert(token2.token)
      transactorCalls shouldEqual Seq(TransactorCall.ExecuteTransactionally, TransactorCall.Execute, TransactorCall.Execute)
    }
    "returns IdentityAlreadyExist if phoneNumber exists in repository" in new TestCase {
      val request = plainRequest.copy(email = None, phoneNumber = Some(PhoneNumber("+49")), password = None)
      val user    = userGenerator.maximal.generate

      when(userRepository.find(UserFilters(phoneNumber = request.phoneNumber))).thenReturn(Some(user).asDBIO)

      userCreateService.create(request).asserting(_ shouldEqual UserCreateResult.IdentityAlreadyExist)

      verify(userRepository).find(UserFilters(phoneNumber = request.phoneNumber))
    }
    "returns OK if email do not exist and password is provided and insert user with no roles, no confirmed identity and send email with link" in new TestCase {
      val password = PasswordPlain("pass")
      val request  = plainRequest.copy(email = Some(MailAddress("m@m.m")), phoneNumber = None, password = Some(password), doNotSendLoginToken = None)
      val user     = userGenerator.maximal.generate
      val userIdentity = userIdentityGenerator.normal.generate
      val token1       = tokenGenerator.normal.generate
      val token2       = tokenGenerator.normal.generate

      when(passwordValidateService.validate(request.password.get)).thenReturn(PasswordValidateResult.Valid)
      when(emailIdentityRepository.resolve(request.email.get)).thenReturn(EmailIdentityRepository.Resolution.Empty.asDBIO)
      when(passwordService.encrypt(password)).thenReturn(PasswordHash("initial-hash"))
      when(userRepository.insert(any)).thenReturn(user.asDBIO)
      when(userIdentityRepository.insert(any)).thenReturn(userIdentity.asDBIO)
      when(loginTokenGetService.getToken(LoginTokenGetRequest(request.email, None, None)))
        .thenReturn(LoginTokenGetResult.Ok.asIO)
      when(loginTokenGenerateService.generateToken(userIdentity, false)).thenReturn(token1.asIO, token2.asIO)
      when(userLoginTokenRepository.insert(any)).thenAnswerArgAsDBIO()

      userCreateService
        .create(request)
        .asserting(
          _ shouldEqual
            UserCreateResult.Ok(UserCreateResultData(user, Seq(token1.loginTokenPlain, token2.loginTokenPlain)))
        )

      verify(passwordValidateService).validate(request.password.get)
      verify(emailIdentityRepository).lock(request.email.get)
      verify(emailIdentityRepository).resolve(request.email.get)
      verify(passwordService).encrypt(password)
      verify(userPasswordRepository).upsert(pl.iterators.epoca.auth.domain.UserPassword(userIdentity.id, PasswordHash("initial-hash")))
      verify(userRepository).insert(argThat { (argument: User) =>
        argument.firstName == request.firstName &&
        argument.lastName == request.lastName &&
        argument.avatar == request.avatar &&
        argument.color == request.color &&
        argument.archivedAt.isEmpty
      })
      verify(userIdentityRepository).insert(argThat { (argument: UserIdentity) =>
        argument.value == UserIdentityValue(request.email.get) && argument.`type` == IdentityType.MailAddress &&
        !argument.confirmed
      })
      verify(loginTokenGetService).getToken(LoginTokenGetRequest(request.email, None, None))
      verify(loginTokenGenerateService, times(2)).generateToken(userIdentity, false)
      verify(userLoginTokenRepository).insert(token1.token)
      verify(userLoginTokenRepository).insert(token2.token)
    }
    "returns OK if email do not exist and password is not provided and insert user with no roles to db and send email with link" in new TestCase {
      val request      = plainRequest.copy(email = Some(MailAddress("m@m.m")), phoneNumber = None, password = None, doNotSendLoginToken = Some(false))
      val user         = userGenerator.maximal.generate
      val userIdentity = userIdentityGenerator.normal.generate
      val token1       = tokenGenerator.normal.generate
      val token2       = tokenGenerator.normal.generate

      when(emailIdentityRepository.resolve(request.email.get)).thenReturn(EmailIdentityRepository.Resolution.Empty.asDBIO)
      when(userRepository.insert(any)).thenReturn(user.asDBIO)
      when(userIdentityRepository.insert(any)).thenReturn(userIdentity.asDBIO)
      when(loginTokenGetService.getToken(LoginTokenGetRequest(request.email, None, None)))
        .thenReturn(LoginTokenGetResult.Ok.asIO)
      when(loginTokenGenerateService.generateToken(userIdentity, false)).thenReturn(token1.asIO, token2.asIO)
      when(userLoginTokenRepository.insert(any)).thenAnswerArgAsDBIO()

      userCreateService
        .create(request)
        .asserting(
          _ shouldEqual
            UserCreateResult.Ok(UserCreateResultData(user, Seq(token1.loginTokenPlain, token2.loginTokenPlain)))
        )

      verify(emailIdentityRepository).lock(request.email.get)
      verify(emailIdentityRepository).resolve(request.email.get)
      verify(userRepository).insert(argThat { (argument: User) =>
        argument.firstName == request.firstName &&
        argument.lastName == request.lastName &&
        argument.avatar == request.avatar &&
        argument.color == request.color &&
        argument.archivedAt.isEmpty
      })
      verify(userIdentityRepository).insert(argThat { (argument: UserIdentity) =>
        argument.value == UserIdentityValue(request.email.get) && argument.`type` == IdentityType.MailAddress &&
        !argument.confirmed
      })
      verify(loginTokenGetService).getToken(LoginTokenGetRequest(request.email, None, None))
      verify(loginTokenGenerateService, times(2)).generateToken(userIdentity, false)
      verify(userLoginTokenRepository).insert(token1.token)
      verify(userLoginTokenRepository).insert(token2.token)
    }
    "returns OK if email do not exist and password is not provided and insert user with admin role (if proper admin key is provided)" in new TestCase {
      val request = plainRequest.copy(
        email = Some(MailAddress("m@m.m")),
        phoneNumber = None,
        superAdminKey = Some(superAdminKey),
        password = None,
        doNotSendLoginToken = Some(false)
      )
      val user         = userGenerator.maximal.generate
      val userIdentity = userIdentityGenerator.normal.generate
      val token1       = tokenGenerator.normal.generate
      val token2       = tokenGenerator.normal.generate

      when(emailIdentityRepository.resolve(request.email.get)).thenReturn(EmailIdentityRepository.Resolution.Empty.asDBIO)
      when(userRepository.insert(any)).thenReturn(user.asDBIO)
      when(userIdentityRepository.insert(any)).thenReturn(userIdentity.asDBIO)
      when(loginTokenGetService.getToken(LoginTokenGetRequest(request.email, None, None)))
        .thenReturn(LoginTokenGetResult.Ok.asIO)
      when(loginTokenGenerateService.generateToken(userIdentity, false)).thenReturn(token1.asIO, token2.asIO)
      when(userLoginTokenRepository.insert(any)).thenAnswerArgAsDBIO()

      userCreateService
        .create(request)
        .asserting(
          _ shouldEqual
            UserCreateResult.Ok(UserCreateResultData(user, Seq(token1.loginTokenPlain, token2.loginTokenPlain)))
        )

      verify(emailIdentityRepository).lock(request.email.get)
      verify(emailIdentityRepository).resolve(request.email.get)
      verify(userRepository).insert(argThat { (argument: User) =>
        argument.firstName == request.firstName &&
        argument.lastName == request.lastName &&
        argument.avatar == request.avatar &&
        argument.color == request.color &&
        argument.isSuperAdmin &&
        argument.archivedAt.isEmpty
      })
      verify(userIdentityRepository).insert(argThat { (argument: UserIdentity) =>
        argument.value == UserIdentityValue(request.email.get) && argument.`type` == IdentityType.MailAddress &&
        argument.confirmed
      })
      verify(loginTokenGetService).getToken(LoginTokenGetRequest(request.email, None, None))
      verify(loginTokenGenerateService, times(2)).generateToken(userIdentity, false)
      verify(userLoginTokenRepository).insert(token1.token)
      verify(userLoginTokenRepository).insert(token2.token)
    }
    "returns OK if phone as well" in new TestCase {
      val password = PasswordPlain("pass")
      val request =
        plainRequest.copy(email = None, phoneNumber = Some(PhoneNumber("+49")), password = Some(password), doNotSendLoginToken = Some(false))
      val user         = userGenerator.maximal.generate
      val userIdentity = userIdentityGenerator.normal.generate
      val token1       = tokenGenerator.normal.generate
      val token2       = tokenGenerator.normal.generate

      when(passwordValidateService.validate(request.password.get)).thenReturn(PasswordValidateResult.Valid)
      when(userRepository.find(UserFilters(phoneNumber = request.phoneNumber))).thenReturn(None.asDBIO)
      when(passwordService.encrypt(password)).thenReturn(PasswordHash("initial-hash"))
      when(userRepository.insert(any)).thenReturn(user.asDBIO)
      when(userIdentityRepository.insert(any)).thenReturn(userIdentity.asDBIO)
      when(loginTokenGetService.getToken(LoginTokenGetRequest(None, request.phoneNumber, None)))
        .thenReturn(LoginTokenGetResult.Ok.asIO)
      when(loginTokenGenerateService.generateToken(userIdentity, false)).thenReturn(token1.asIO, token2.asIO)
      when(userLoginTokenRepository.insert(any)).thenAnswerArgAsDBIO()

      userCreateService
        .create(request)
        .asserting(
          _ shouldEqual
            UserCreateResult.Ok(UserCreateResultData(user, Seq(token1.loginTokenPlain, token2.loginTokenPlain)))
        )

      verify(passwordValidateService).validate(request.password.get)
      verify(userRepository).find(UserFilters(phoneNumber = request.phoneNumber))
      verify(passwordService).encrypt(password)
      verify(userPasswordRepository).upsert(pl.iterators.epoca.auth.domain.UserPassword(userIdentity.id, PasswordHash("initial-hash")))
      verify(userRepository).insert(argThat { (argument: User) =>
        argument.firstName == request.firstName &&
        argument.lastName == request.lastName &&
        argument.avatar == request.avatar &&
        argument.color == request.color &&
        argument.archivedAt.isEmpty
      })
      verify(userIdentityRepository).insert(argThat { (argument: UserIdentity) =>
        argument.value == UserIdentityValue(request.phoneNumber.get) && argument.`type` == IdentityType.Phone &&
        !argument.confirmed
      })
      verify(loginTokenGetService).getToken(LoginTokenGetRequest(None, request.phoneNumber, None))
      verify(loginTokenGenerateService, times(2)).generateToken(userIdentity, false)
      verify(userLoginTokenRepository).insert(token1.token)
      verify(userLoginTokenRepository).insert(token2.token)
    }
    "returns OK if phone as well. if doNotSendLoginToken contains true, do not send it :)" in new TestCase {
      val password = PasswordPlain("pass")
      val request =
        plainRequest.copy(email = None, phoneNumber = Some(PhoneNumber("+49")), password = Some(password), doNotSendLoginToken = Some(true))
      val user         = userGenerator.maximal.generate
      val userIdentity = userIdentityGenerator.normal.generate
      val token1       = tokenGenerator.normal.generate
      val token2       = tokenGenerator.normal.generate

      when(passwordValidateService.validate(request.password.get)).thenReturn(PasswordValidateResult.Valid)
      when(userRepository.find(UserFilters(phoneNumber = request.phoneNumber))).thenReturn(None.asDBIO)
      when(passwordService.encrypt(password)).thenReturn(PasswordHash("initial-hash"))
      when(userRepository.insert(any)).thenReturn(user.asDBIO)
      when(userIdentityRepository.insert(any)).thenReturn(userIdentity.asDBIO)
      when(loginTokenGenerateService.generateToken(userIdentity, false)).thenReturn(token1.asIO, token2.asIO)
      when(userLoginTokenRepository.insert(any)).thenAnswerArgAsDBIO()

      userCreateService
        .create(request)
        .asserting(
          _ shouldEqual
            UserCreateResult.Ok(UserCreateResultData(user, Seq(token1.loginTokenPlain, token2.loginTokenPlain)))
        )

      verify(passwordValidateService).validate(request.password.get)
      verify(userRepository).find(UserFilters(phoneNumber = request.phoneNumber))
      verify(passwordService).encrypt(password)
      verify(userPasswordRepository).upsert(pl.iterators.epoca.auth.domain.UserPassword(userIdentity.id, PasswordHash("initial-hash")))
      verify(userRepository).insert(argThat { (argument: User) =>
        argument.firstName == request.firstName &&
        argument.lastName == request.lastName &&
        argument.avatar == request.avatar &&
        argument.color == request.color &&
        argument.archivedAt.isEmpty
      })
      verify(userIdentityRepository).insert(argThat { (argument: UserIdentity) =>
        argument.value == UserIdentityValue(request.phoneNumber.get) && argument.`type` == IdentityType.Phone &&
        !argument.confirmed
      })
      verify(loginTokenGenerateService, times(2)).generateToken(userIdentity, false)
      verify(userLoginTokenRepository).insert(token1.token)
      verify(userLoginTokenRepository).insert(token2.token)
    }
    "return SuperAdminKeyRejected without creating a user if the super admin key does not match" in new TestCase {
      val request = plainRequest.copy(email = Some(MailAddress("m@m.m")), phoneNumber = None, password = None, superAdminKey = Some("wrong-key"))

      userCreateService.create(request).asserting(_ shouldEqual UserCreateResult.SuperAdminKeyRejected)
      transactorCalls shouldEqual Seq.empty
    }
    "return SuperAdminKeyRejected without creating a user if super admin creation is disabled" in new TestCase {
      override def configuredSuperAdminKey: Option[String] = None

      val request = plainRequest.copy(email = Some(MailAddress("m@m.m")), phoneNumber = None, password = None, superAdminKey = Some(superAdminKey))

      userCreateService.create(request).asserting(_ shouldEqual UserCreateResult.SuperAdminKeyRejected)
      transactorCalls shouldEqual Seq.empty
    }
  }

  "Config" should {
    def load(settings: String) =
      UserCreateService.Config(ConfigFactory.parseString(s"user.loginToken.recoveryCount = 2\n$settings"))

    "disable super admin creation if the key is not configured" in {
      load("").superAdminSecretKey must beNone
    }
    "disable super admin creation if the configured key is blank or shorter than the minimum length" in {
      load("user.createAdminSecretKey = \"\"").superAdminSecretKey must beNone
      load("user.createAdminSecretKey = \"CreateAdminSecretKey\"").superAdminSecretKey must beNone
    }
    "enable super admin creation with a configured key of at least the minimum length" in {
      val key = "k" * UserCreateService.Config.MinimumSuperAdminKeyLength

      load(s"user.createAdminSecretKey = \"$key\"").superAdminSecretKey must beSome(key)
    }
  }

}
