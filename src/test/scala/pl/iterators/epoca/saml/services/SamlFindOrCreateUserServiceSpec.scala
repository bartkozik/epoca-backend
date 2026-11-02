package pl.iterators.epoca.saml.services

import org.mockito.Mockito.verifyNoInteractions
import pl.iterators.epoca.organization.domain.Organization
import pl.iterators.epoca.saml.domain.SamlDomain.SamlAllowAccountInitialization
import pl.iterators.epoca.saml.domain.{SamlAuthContext, SamlConfig}
import pl.iterators.epoca.saml.services.SamlFindOrCreateUserService.SamlFindOrCreateUserResult
import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.user.domain.UserDomain.{IdentityType, UserAccountType, UserArchivedAt, UserFirstName, UserIdentityConfirmed, UserLastName}
import pl.iterators.epoca.user.domain.{User, UserIdentity}
import pl.iterators.epoca.user.repositories.EmailIdentityRepository
import pl.iterators.epoca.user.repositories.EmailIdentityRepository.Resolution
import pl.iterators.epoca.user.services.UserCreateService
import pl.iterators.epoca.user.services.UserCreateService.{UserCreateResult, UserCreateResultData, UserCreateSamlRequest}
import pl.iterators.epoca.utils.ServiceSpec

class SamlFindOrCreateUserServiceSpec extends ServiceSpec {
  lazy val organizationGenerator: AllGenerators[Organization]   = allGenerators[Organization]
  lazy val userGenerator: AllGenerators[User]                   = allGenerators[User]
  lazy val samlConfigGenerator: AllGenerators[SamlConfig]       = allGenerators[SamlConfig]
  lazy val samlContextGenerator: AllGenerators[SamlAuthContext] = allGenerators[SamlAuthContext]
  lazy val mapAttributesEmptyString: Map[String, List[String]]  = Map.apply("firstName" -> List("abc"), "lastName" -> List(""))
  lazy val mapAttributesCorrect: Map[String, List[String]]      = Map.apply("firstName" -> List("abc"), "lastName" -> List("cba"))
  trait TestCase extends BaseScope {
    val emailIdentityRepository: EmailIdentityRepository = vmock[EmailIdentityRepository]
    val userCreateService: UserCreateService             = vmock[UserCreateService]

    val sut: SamlFindOrCreateUserService = wire[SamlFindOrCreateUserService]
  }

  "findOrCreateUser" should {
    "treat a pre-existing service account as a reserved address without binding or creation" in new TestCase {
      val serviceAccount  = userGenerator.normal.generate.copy(accountType = UserAccountType.ServiceAccount)
      val samlConfig      = samlConfigGenerator.normal.generate.copy(allowAccountInit = SamlAllowAccountInitialization(false))
      val samlAuthContext = samlContextGenerator.normal.generate
      val userFilters     = samlAuthContext.email

      when(emailIdentityRepository.resolve(userFilters)).thenReturn(Resolution.Single(serviceAccount, Nil).asDBIO)

      sut.findOrCreateUser(samlAuthContext, samlConfig).asserting(_ shouldEqual SamlFindOrCreateUserResult.UserCreationError)

      verify(emailIdentityRepository).resolve(userFilters)
      verifyNoInteractions(userCreateService)
    }

    "return Found with user if user was found in the first step" in new TestCase {
      val user            = userGenerator.normal.generate.copy(archivedAt = None, accountType = UserAccountType.Human)
      val samlConfig      = samlConfigGenerator.normal.generate
      val samlAuthContext = samlContextGenerator.normal.generate

      val userFilters = samlAuthContext.email

      when(emailIdentityRepository.resolve(userFilters))
        .thenReturn(Resolution.Single(user, Seq(allGenerators[UserIdentity].normal.generate.copy(confirmed = UserIdentityConfirmed(true)))).asDBIO)

      sut.findOrCreateUser(samlAuthContext, samlConfig).asserting(_ shouldEqual SamlFindOrCreateUserResult.Found(user))

      verify(emailIdentityRepository).resolve(userFilters)
    }
    Seq("unconfirmed", "archived", "ambiguous").foreach { state =>
      s"reject $state reservations without creating an account" in new TestCase {
        val user = userGenerator.normal.generate.copy(archivedAt = None, accountType = UserAccountType.Human)
        val identity = allGenerators[UserIdentity].normal.generate
          .copy(userId = user.id, `type` = IdentityType.MailAddress, confirmed = UserIdentityConfirmed(state != "unconfirmed"))
        val reserved: Resolution = state match {
          case "ambiguous" => Resolution.Ambiguous
          case "archived"  => Resolution.Single(user.copy(archivedAt = Some(UserArchivedAt(java.time.Instant.now()))), Seq(identity))
          case _           => Resolution.Single(user, Seq(identity))
        }
        val context = samlContextGenerator.normal.generate.copy(attributes = mapAttributesCorrect)
        val config  = samlConfigGenerator.normal.generate.copy(allowAccountInit = SamlAllowAccountInitialization(true))
        when(emailIdentityRepository.resolve(context.email)).thenReturn(reserved.asDBIO)
        sut.findOrCreateUser(context, config).asserting(_ shouldEqual SamlFindOrCreateUserResult.UserCreationError)
        verify(emailIdentityRepository).resolve(context.email)
        verifyNoInteractions(userCreateService)
      }
    }
    "return InitializationDisabled if saml config has disabled user initialization" in new TestCase {
      val samlConfig      = samlConfigGenerator.normal.generate.copy(allowAccountInit = SamlAllowAccountInitialization(false))
      val samlAuthContext = samlContextGenerator.normal.generate

      val userFilters = samlAuthContext.email

      when(emailIdentityRepository.resolve(userFilters)).thenReturn(Resolution.Empty.asDBIO)

      sut.findOrCreateUser(samlAuthContext, samlConfig).asserting(_ shouldEqual SamlFindOrCreateUserResult.InitializationDisabled)

      verify(emailIdentityRepository).resolve(userFilters)
    }
    "return AttributesMissing if SamlAuthContext is missing firstName or lastName fields" in new TestCase {
      val samlConfig      = samlConfigGenerator.normal.generate.copy(allowAccountInit = SamlAllowAccountInitialization(true))
      val samlAuthContext = samlContextGenerator.normal.generate

      val userFilters = samlAuthContext.email

      when(emailIdentityRepository.resolve(userFilters)).thenReturn(Resolution.Empty.asDBIO)

      sut.findOrCreateUser(samlAuthContext, samlConfig).asserting(_ shouldEqual SamlFindOrCreateUserResult.AttributesMissing)

      verify(emailIdentityRepository).resolve(userFilters)
    }
    "return AttributesMissing if SamlAuthContext firstName or lastName fields has empty strings" in new TestCase {
      val samlConfig      = samlConfigGenerator.normal.generate.copy(allowAccountInit = SamlAllowAccountInitialization(true))
      val samlAuthContext = samlContextGenerator.normal.generate.copy(attributes = mapAttributesEmptyString)

      val userFilters = samlAuthContext.email

      when(emailIdentityRepository.resolve(userFilters)).thenReturn(Resolution.Empty.asDBIO)

      sut.findOrCreateUser(samlAuthContext, samlConfig).asserting(_ shouldEqual SamlFindOrCreateUserResult.AttributesMissing)

      verify(emailIdentityRepository).resolve(userFilters)
    }
    "return UserCreationError if UserCreateService returns an error" in new TestCase {
      val samlConfig      = samlConfigGenerator.normal.generate.copy(allowAccountInit = SamlAllowAccountInitialization(true))
      val samlAuthContext = samlContextGenerator.normal.generate.copy(attributes = mapAttributesCorrect)
      val request         = UserCreateSamlRequest(firstName = UserFirstName("abc"), lastName = UserLastName("cba"), email = samlAuthContext.email)

      val userFilters = samlAuthContext.email

      when(emailIdentityRepository.resolve(userFilters)).thenReturn(Resolution.Empty.asDBIO)
      when(userCreateService.createWithSaml(request)).thenReturn(UserCreateResult.IdentityAlreadyExist.asIO)

      sut.findOrCreateUser(samlAuthContext, samlConfig).asserting(_ shouldEqual SamlFindOrCreateUserResult.UserCreationError)

      verify(emailIdentityRepository).resolve(userFilters)
      verify(userCreateService).createWithSaml(request)
    }
    "return Created with user otherwise" in new TestCase {
      val user             = userGenerator.normal.generate
      val createUserResult = UserCreateResultData(user, Nil)
      val samlConfig       = samlConfigGenerator.normal.generate.copy(allowAccountInit = SamlAllowAccountInitialization(true))
      val samlAuthContext  = samlContextGenerator.normal.generate.copy(attributes = mapAttributesCorrect)
      val request          = UserCreateSamlRequest(firstName = UserFirstName("abc"), lastName = UserLastName("cba"), email = samlAuthContext.email)

      val userFilters = samlAuthContext.email

      when(emailIdentityRepository.resolve(userFilters)).thenReturn(Resolution.Empty.asDBIO)
      when(userCreateService.createWithSaml(request)).thenReturn(UserCreateResult.Ok(createUserResult).asIO)

      sut.findOrCreateUser(samlAuthContext, samlConfig).asserting(_ shouldEqual SamlFindOrCreateUserResult.Created(user))

      verify(emailIdentityRepository).resolve(userFilters)
      verify(userCreateService).createWithSaml(request)
    }
  }
}
