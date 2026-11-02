package pl.iterators.epoca.user.services

import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.user.domain.UserDomain._
import pl.iterators.epoca.user.domain.{User, UserIdentity, UserIdentityDto}
import pl.iterators.epoca.user.repositories.UserIdentityRepository.UserIdentityFilters
import pl.iterators.epoca.user.repositories.UserRepository.UserFilters
import pl.iterators.epoca.user.repositories.{UserIdentityRepository, UserRepository}
import pl.iterators.epoca.user.services.UserIdentitySetMainService.UserIdentitySetMainResult
import pl.iterators.epoca.utils.ServiceSpec

import java.util.UUID

class UserIdentitySetMainServiceSpec extends ServiceSpec {
  val userIdentityGenerator: AllGenerators[UserIdentity] = allGenerators[UserIdentity]

  trait TestCase extends BaseScope {
    val userIdentityRepository: UserIdentityRepository = vmock[UserIdentityRepository]

    val userRepository: UserRepository = mock[UserRepository]
    when(userRepository.find(any[UserFilters])).thenReturn(Some(allGenerators[User].normal.generate.copy(id = authUser.id, archivedAt = None)).asDBIO)

    val userIdentitySetMainService: UserIdentitySetMainService = wire[UserIdentitySetMainService]
  }

  "update" should {
    "returns IdentityNotFound without identity locks when the user no longer exists" in new TestCase {
      when(userRepository.find(any[UserFilters])).thenReturn(None.asDBIO)
      userIdentitySetMainService
        .setMain(authUser, UserIdentityId(UUID.randomUUID()))
        .asserting(_ shouldEqual UserIdentitySetMainResult.IdentityNotFound)
    }
    "returns IdentityNotFound when identity does not exists" in new TestCase {
      val id = UserIdentityId(UUID.randomUUID())

      when(userIdentityRepository.find(UserIdentityFilters(id = Some(id), userId = Some(authUser.id), forUpdate = true)))
        .thenReturn(None.asDBIO)

      userIdentitySetMainService
        .setMain(authUser, id)
        .asserting(_ shouldEqual UserIdentitySetMainResult.IdentityNotFound)

      verify(userIdentityRepository).find(UserIdentityFilters(id = Some(id), userId = Some(authUser.id), forUpdate = true))
    }

    "returns AlreadyMain when identity is already main" in new TestCase {
      val id           = UserIdentityId(UUID.randomUUID())
      val userIdentity = userIdentityGenerator.maximal.generate.copy(id = id, main = UserIdentityMain(true))

      when(userIdentityRepository.find(UserIdentityFilters(id = Some(id), userId = Some(authUser.id), forUpdate = true)))
        .thenReturn(Some(userIdentity).asDBIO)
      when(userIdentityRepository.find(UserIdentityFilters(userId = Some(authUser.id), main = Some(UserIdentityMain(true)), forUpdate = true)))
        .thenReturn(Some(userIdentity).asDBIO)

      userIdentitySetMainService
        .setMain(authUser, id)
        .asserting(_ shouldEqual UserIdentitySetMainResult.AlreadyMain)

      verify(userIdentityRepository).find(UserIdentityFilters(id = Some(id), userId = Some(authUser.id), forUpdate = true))
      verify(userIdentityRepository).find(UserIdentityFilters(userId = Some(authUser.id), main = Some(UserIdentityMain(true)), forUpdate = true))
    }

    "returns Ok and set identity to main" in new TestCase {
      val id                      = UserIdentityId(UUID.randomUUID())
      val currentMainUserIdentity = userIdentityGenerator.maximal.generate.copy(main = UserIdentityMain(true))
      val userIdentityToBeMain    = userIdentityGenerator.maximal.generate.copy(id = id, main = UserIdentityMain(false))

      when(userIdentityRepository.find(UserIdentityFilters(id = Some(id), userId = Some(authUser.id), forUpdate = true)))
        .thenReturn(Some(userIdentityToBeMain).asDBIO)
      when(userIdentityRepository.find(UserIdentityFilters(userId = Some(authUser.id), main = Some(UserIdentityMain(true)), forUpdate = true)))
        .thenReturn(Some(currentMainUserIdentity).asDBIO)

      when(userIdentityRepository.update(currentMainUserIdentity.copy(main = UserIdentityMain(false))))
        .thenReturn(Some(currentMainUserIdentity.copy(main = UserIdentityMain(false))).asDBIO)
      when(userIdentityRepository.update(userIdentityToBeMain.copy(main = UserIdentityMain(true))))
        .thenReturn(Some(userIdentityToBeMain.copy(main = UserIdentityMain(true))).asDBIO)

      val dto = UserIdentityDto.from(userIdentityToBeMain.copy(main = UserIdentityMain(true)))

      userIdentitySetMainService
        .setMain(authUser, id)
        .asserting(_ shouldEqual UserIdentitySetMainResult.Ok(dto))

      verify(userIdentityRepository).find(UserIdentityFilters(id = Some(id), userId = Some(authUser.id), forUpdate = true))
      verify(userIdentityRepository).find(UserIdentityFilters(userId = Some(authUser.id), main = Some(UserIdentityMain(true)), forUpdate = true))
      verify(userIdentityRepository).update(currentMainUserIdentity.copy(main = UserIdentityMain(false)))
      verify(userIdentityRepository).update(userIdentityToBeMain.copy(main = UserIdentityMain(true)))
    }
  }
}
