package pl.iterators.epoca.user.services

import io.circe.syntax._
import pl.iterators.epoca.user.domain.UserDomain._
import pl.iterators.epoca.user.domain.{UserIdentity, UserWithIdentities, UserWithIdentitiesDto}
import pl.iterators.epoca.user.repositories.UserRepository
import pl.iterators.epoca.user.repositories.UserRepository.UserFilters
import pl.iterators.epoca.user.services.UserListService.UserListResult
import pl.iterators.epoca.utils.ServiceSpec
import pl.iterators.epoca.utils.json.JsonProtocol

class UserListServiceSpec extends ServiceSpec with JsonProtocol {

  trait TestCase extends BaseScope {
    val userRepository: UserRepository = vmock[UserRepository]

    val userListService: UserListService = wire[UserListService]
  }

  "list" should {
    "returns OperationNotPermitted when called by noAdmin" in new TestCase {
      private val filters = UserFilters()

      userListService.listWithIdentities(authUser, filters).asserting(_ shouldEqual UserListResult.OperationNotPermitted)
    }

    "redact existing TOTP secrets and confirmation codes from the superadmin listing" in new TestCase {
      val raw = allGenerators[UserIdentity].normal.generate.copy(
        `type` = IdentityType.TimeBasedOneTimePassword,
        value = UserIdentityValue("secret-totp"),
        confirmationCode = Some(UserIdentityConfirmationCode("secret-code"))
      )
      val user = allGenerators[UserWithIdentities].normal.generate.copy(identities = Seq(raw))
      when(userRepository.listWithIdentities(UserFilters())).thenReturn(Seq(user).asDBIO)
      userListService.listWithIdentities(authAdmin, UserFilters()).asserting {
        case UserListResult.Ok(users) =>
          users.head.identities.head.value shouldEqual UserIdentityValue("")
          users.asJson.noSpaces.contains("secret-totp") shouldEqual false
          users.asJson.noSpaces.contains("secret-code") shouldEqual false
          users.head.identities.head.asJson.hcursor.downField("confirmationCode").focus shouldEqual None
        case result => result shouldEqual UserListResult.Ok(Seq.empty)
      }
      verify(userRepository).listWithIdentities(UserFilters())
    }
    "returns Ok with values from repository when called by admin" in new TestCase {
      private val filters = UserFilters()
      private val users   = allGenerators[Seq[UserWithIdentities]].maximal.generate
      when(userRepository.listWithIdentities(filters)).thenReturn(users.asDBIO)

      userListService.listWithIdentities(authAdmin, filters).asserting(_ shouldEqual UserListResult.Ok(users.map(UserWithIdentitiesDto.from)))

      verify(userRepository).listWithIdentities(filters)
    }
  }

}
