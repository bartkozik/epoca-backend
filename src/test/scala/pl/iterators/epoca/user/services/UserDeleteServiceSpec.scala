package pl.iterators.epoca.user.services

import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.repositories.UserIdentityRepository.UserIdentityFilters
import pl.iterators.epoca.user.repositories.UserRepository.UserFilters
import pl.iterators.epoca.user.repositories.{UserIdentityRepository, UserRepository}
import pl.iterators.epoca.user.services.UserDeleteService.UserDeleteResult
import pl.iterators.epoca.utils.ServiceSpec

class UserDeleteServiceSpec extends ServiceSpec {
  val userGenerator: AllGenerators[User] = allGenerators[User]

  trait TestCase extends BaseScope {
    val userRepository: UserRepository                 = vmock[UserRepository]
    val userIdentityRepository: UserIdentityRepository = vmock[UserIdentityRepository]
    lazy val userDeleteService: UserDeleteService      = wire[UserDeleteService]
  }

  "delete" should {
    "returns OperationNotPermitted if user is not admin and try to delete other user" in new TestCase {
      val user = userGenerator.maximal.generate

      userDeleteService.delete(authUser, user.id).asserting(_ shouldEqual UserDeleteResult.OperationNotPermitted)

    }
    "returns UserDoNotExist if user do not exist in repository" in new TestCase {
      val user = userGenerator.maximal.generate.copy(id = authUser.id)

      when(userRepository.find(UserFilters(id = Some(user.id)))).thenReturn(None.asDBIO)

      userDeleteService.delete(authUser, user.id).asserting(_ shouldEqual UserDeleteResult.UserDoNotExist)

      verify(userRepository).find(UserFilters(id = Some(user.id)))

    }
    "returns Ok if user exists in repository and archive his record in db" in new TestCase {
      val user = userGenerator.maximal.generate.copy(id = authUser.id)

      when(userRepository.find(UserFilters(id = Some(user.id)))).thenReturn(Some(user).asDBIO)

      when(userRepository.update(any)).thenReturn(None.asDBIO)

      when(userIdentityRepository.purge(any)).thenReturn(().asDBIO)

      userDeleteService.delete(authUser, user.id).asserting(_ shouldEqual UserDeleteResult.Ok)

      verify(userRepository).find(UserFilters(id = Some(user.id)))

      verify(userRepository).update(argThat((user: User) => user.archivedAt.isDefined))

      verify(userIdentityRepository).purge(UserIdentityFilters(userId = Some(user.id)))
    }
    "returns Ok for admin as well" in new TestCase {
      val user = userGenerator.maximal.generate

      when(userRepository.find(UserFilters(id = Some(user.id)))).thenReturn(Some(user).asDBIO)

      when(userRepository.update(any)).thenReturn(None.asDBIO)

      when(userIdentityRepository.purge(any)).thenReturn(().asDBIO)

      userDeleteService.delete(authAdmin, user.id).asserting(_ shouldEqual UserDeleteResult.Ok)

      verify(userRepository).find(UserFilters(id = Some(user.id)))

      verify(userRepository).update(argThat((user: User) => user.archivedAt.isDefined))

      verify(userIdentityRepository).purge(UserIdentityFilters(userId = Some(user.id)))
    }
  }

}
