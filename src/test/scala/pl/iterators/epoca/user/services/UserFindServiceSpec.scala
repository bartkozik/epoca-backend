package pl.iterators.epoca.user.services

import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.repositories.UserRepository
import pl.iterators.epoca.user.repositories.UserRepository.UserFilters
import pl.iterators.epoca.user.services.UserFindService.UserFindResult
import pl.iterators.epoca.utils.ServiceSpec

class UserFindServiceSpec extends ServiceSpec {
  val userGenerator: AllGenerators[User] = allGenerators[User]

  trait TestCase extends BaseScope {
    val userRepository: UserRepository = vmock[UserRepository]

    val userFindService: UserFindService = wire[UserFindService]
  }

  "find" should {
    "returns OperationNotPermitted when called by noAdmin for different id" in new TestCase {
      val user = userGenerator.maximal.generate

      userFindService.find(authUser, user.id).asserting(_ shouldEqual UserFindResult.OperationNotPermitted)
    }

    "returns NotFound when repository do not return user" in new TestCase {
      val user = userGenerator.maximal.generate.copy(id = authUser.id)

      when(userRepository.find(UserFilters(id = Some(user.id)))).thenReturn(None.asDBIO)

      userFindService.find(authUser, user.id).asserting(_ shouldEqual UserFindResult.UserDoNotExist)

      verify(userRepository).find(UserFilters(id = Some(user.id)))
    }
    "returns Ok with user data otherwise" in new TestCase {
      val user = userGenerator.maximal.generate.copy(id = authUser.id)

      when(userRepository.find(UserFilters(id = Some(user.id)))).thenReturn(Some(user).asDBIO)

      userFindService.find(authUser, user.id).asserting(_ shouldEqual UserFindResult.Ok(user))

      verify(userRepository).find(UserFilters(id = Some(user.id)))
    }
    "returns Ok with user data for admin as well" in new TestCase {
      val user = userGenerator.maximal.generate

      when(userRepository.find(UserFilters(id = Some(user.id)))).thenReturn(Some(user).asDBIO)

      userFindService.find(authAdmin, user.id).asserting(_ shouldEqual UserFindResult.Ok(user))

      verify(userRepository).find(UserFilters(id = Some(user.id)))
    }
  }

}
