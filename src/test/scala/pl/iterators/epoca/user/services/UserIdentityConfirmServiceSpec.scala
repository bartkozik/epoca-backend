package pl.iterators.epoca.user.services

import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.user.domain.UserDomain._
import pl.iterators.epoca.user.domain.{UserIdentity, UserIdentityDto}
import pl.iterators.epoca.user.repositories.UserIdentityRepository
import pl.iterators.epoca.user.repositories.UserIdentityRepository.UserIdentityFilters
import pl.iterators.epoca.user.services.UserIdentityConfirmService._
import pl.iterators.epoca.utils.ServiceSpec

class UserIdentityConfirmServiceSpec extends ServiceSpec {
  val userIdentityGenerator: AllGenerators[UserIdentity]          = allGenerators[UserIdentity]
  val requestGenerator: AllGenerators[UserIdentityConfirmRequest] = allGenerators[UserIdentityConfirmRequest]

  trait TestCase extends BaseScope {
    val userIdentityRepository: UserIdentityRepository = vmock[UserIdentityRepository]

    val userIdentityConfirmService: UserIdentityConfirmService = wire[UserIdentityConfirmService]
  }

  "confirm" should {
    "returns IdentityNotFound when identity does not exists" in new TestCase {
      val request                 = requestGenerator.maximal.generate
      val notExistingUserIdentity = userIdentityGenerator.maximal.generate

      when(userIdentityRepository.find(UserIdentityFilters(id = Some(notExistingUserIdentity.id), userId = Some(authUser.id), forUpdate = true)))
        .thenReturn(None.asDBIO)

      userIdentityConfirmService
        .confirm(authUser, notExistingUserIdentity.id, request)
        .asserting(_ shouldEqual UserIdentityConfirmResult.IdentityNotFound)

      verify(userIdentityRepository).find(UserIdentityFilters(id = Some(notExistingUserIdentity.id), userId = Some(authUser.id), forUpdate = true))
    }

    "returns InvalidCode when code is not valid" in new TestCase {
      val request               = requestGenerator.maximal.generate
      val userIdentityToConfirm = userIdentityGenerator.maximal.generate.copy(confirmationCode = Some(UserIdentityConfirmationCode("valid-code")))

      when(userIdentityRepository.find(UserIdentityFilters(id = Some(userIdentityToConfirm.id), userId = Some(authUser.id), forUpdate = true)))
        .thenReturn(Some(userIdentityToConfirm).asDBIO)

      userIdentityConfirmService
        .confirm(authUser, userIdentityToConfirm.id, request)
        .asserting(_ shouldEqual UserIdentityConfirmResult.InvalidCode)

      verify(userIdentityRepository).find(UserIdentityFilters(id = Some(userIdentityToConfirm.id), userId = Some(authUser.id), forUpdate = true))
    }

    "returns Ok when code is valid" in new TestCase {
      val request = requestGenerator.maximal.generate.copy(confirmationCode = UserIdentityConfirmationCode("valid-code"))
      val userIdentityToConfirm = userIdentityGenerator.maximal.generate
        .copy(confirmed = UserIdentityConfirmed(false), confirmationCode = Some(UserIdentityConfirmationCode("valid-code")))

      when(userIdentityRepository.find(UserIdentityFilters(id = Some(userIdentityToConfirm.id), userId = Some(authUser.id), forUpdate = true)))
        .thenReturn(Some(userIdentityToConfirm).asDBIO)
      when(userIdentityRepository.update(any)).thenReturn(Some(userIdentityToConfirm).asDBIO)

      userIdentityConfirmService
        .confirm(authUser, userIdentityToConfirm.id, request)
        .asserting(_ shouldEqual UserIdentityConfirmResult.Ok(UserIdentityDto.from(userIdentityToConfirm)))

      verify(userIdentityRepository).find(UserIdentityFilters(id = Some(userIdentityToConfirm.id), userId = Some(authUser.id), forUpdate = true))
      verify(userIdentityRepository).update(
        argThat((userIdentity: UserIdentity) => userIdentity.confirmed == UserIdentityConfirmed(true) && userIdentity.confirmationCode.isEmpty)
      )
    }
  }
}
