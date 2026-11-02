package pl.iterators.epoca.user.services

import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.user.domain.UserDomain._
import pl.iterators.epoca.user.domain.UserIdentity
import pl.iterators.epoca.user.repositories.UserIdentityRepository
import pl.iterators.epoca.user.repositories.UserIdentityRepository.UserIdentityFilters
import pl.iterators.epoca.user.services.UserIdentityDeleteService._
import pl.iterators.epoca.utils.ServiceSpec

class UserIdentityDeleteServiceSpec extends ServiceSpec {
  val userIdentityGenerator: AllGenerators[UserIdentity] = allGenerators[UserIdentity]

  trait TestCase extends BaseScope {
    val userIdentityRepository: UserIdentityRepository = vmock[UserIdentityRepository]

    val userIdentityDeleteService: UserIdentityDeleteService = wire[UserIdentityDeleteService]

    val freshAuthUser: AuthContext = authUser.copy(fresh = true)
  }

  "delete" should {
    "returns InvalidRequest when auth user is not fresh" in new TestCase {
      val userIdentity = userIdentityGenerator.maximal.generate

      userIdentityDeleteService
        .delete(authUser.copy(fresh = false), userIdentity.id)
        .asserting(_ shouldEqual UserIdentityDeleteResult.IdentityNotFresh)
    }
    "returns IdentityNotFound when identity does not exists" in new TestCase {
      val userIdentity = userIdentityGenerator.maximal.generate

      when(userIdentityRepository.find(UserIdentityFilters(id = Some(userIdentity.id), userId = Some(freshAuthUser.id), forUpdate = true)))
        .thenReturn(None.asDBIO)

      userIdentityDeleteService
        .delete(freshAuthUser, userIdentity.id)
        .asserting(_ shouldEqual UserIdentityDeleteResult.IdentityNotFound)

      verify(userIdentityRepository).find(UserIdentityFilters(id = Some(userIdentity.id), userId = Some(freshAuthUser.id), forUpdate = true))
    }

    "returns CanNotDeleteMain when tries to delete main identity" in new TestCase {
      val userIdentity = userIdentityGenerator.maximal.generate.copy(main = UserIdentityMain(true))

      when(userIdentityRepository.find(UserIdentityFilters(id = Some(userIdentity.id), userId = Some(freshAuthUser.id), forUpdate = true)))
        .thenReturn(Some(userIdentity).asDBIO)

      userIdentityDeleteService
        .delete(freshAuthUser, userIdentity.id)
        .asserting(_ shouldEqual UserIdentityDeleteResult.CanNotDeleteMain)

      verify(userIdentityRepository).find(UserIdentityFilters(id = Some(userIdentity.id), userId = Some(freshAuthUser.id), forUpdate = true))
    }

    "returns Ok for not main identity" in new TestCase {
      val userIdentity = userIdentityGenerator.maximal.generate.copy(main = UserIdentityMain(false))

      when(userIdentityRepository.find(UserIdentityFilters(id = Some(userIdentity.id), userId = Some(freshAuthUser.id), forUpdate = true)))
        .thenReturn(Some(userIdentity).asDBIO)
      when(userIdentityRepository.purge(UserIdentityFilters(id = Some(userIdentity.id))))
        .thenReturn(().asDBIO)

      userIdentityDeleteService
        .delete(freshAuthUser, userIdentity.id)
        .asserting(_ shouldEqual UserIdentityDeleteResult.Ok)

      verify(userIdentityRepository).find(UserIdentityFilters(id = Some(userIdentity.id), userId = Some(freshAuthUser.id), forUpdate = true))
      verify(userIdentityRepository).purge(UserIdentityFilters(id = Some(userIdentity.id)))
    }
  }
}
