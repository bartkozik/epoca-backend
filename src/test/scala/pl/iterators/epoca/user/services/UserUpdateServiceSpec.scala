package pl.iterators.epoca.user.services

import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.domain.UserDomain.UserArchivedAt
import pl.iterators.epoca.user.repositories.UserRepository
import pl.iterators.epoca.user.repositories.UserRepository.UserFilters
import pl.iterators.epoca.user.services.UserUpdateService._
import pl.iterators.epoca.utils.ServiceSpec

import java.time.Instant

class UserUpdateServiceSpec extends ServiceSpec {
  val userGenerator: AllGenerators[User]                 = allGenerators[User]
  val requestGenerator: AllGenerators[UserUpdateRequest] = allGenerators[UserUpdateRequest]
  val instantGenerator: AllGenerators[Instant]           = allGenerators[Instant]

  trait TestCase extends BaseScope {
    val userRepository: UserRepository = vmock[UserRepository]

    lazy val userUpdateService: UserUpdateService = wire[UserUpdateService]
  }

  "update" should {
    "returns OperationNotPermitted if user is not admin and try to update other user" in new TestCase {
      val user    = userGenerator.maximal.generate
      val request = requestGenerator.maximal.generate

      userUpdateService.update(authUser, user.id, request).asserting(_ shouldEqual UserUpdateResult.OperationNotPermitted)
    }

    "returns UserDoNotExist if user do not exist in repository" in new TestCase {
      val user    = userGenerator.maximal.generate.copy(id = authUser.id)
      val request = requestGenerator.maximal.generate

      when(userRepository.find(UserFilters(id = Some(user.id)))).thenReturn(None.asDBIO)

      userUpdateService.update(authUser, user.id, request).asserting(_ shouldEqual UserUpdateResult.UserDoNotExist)

      verify(userRepository).find(UserFilters(id = Some(user.id)))
    }
    "returns Ok if user exists in repository and archive his record in db" in new TestCase {
      val user    = userGenerator.maximal.generate.copy(id = authUser.id, archivedAt = Some(UserArchivedAt(instantGenerator.normal.generate)))
      val request = requestGenerator.maximal.generate

      when(userRepository.find(UserFilters(id = Some(user.id)))).thenReturn(Some(user).asDBIO)
      when(userRepository.update(any)).thenReturn(Some(user).asDBIO)

      userUpdateService.update(authUser, user.id, request).asserting(_ shouldEqual UserUpdateResult.Ok(user))

      verify(userRepository).find(UserFilters(id = Some(user.id)))

      verify(userRepository).update(argThat((user: User) => user.archivedAt.isDefined))
    }
    "returns Ok for admin as well" in new TestCase {
      val user    = userGenerator.maximal.generate.copy(archivedAt = Some(UserArchivedAt(instantGenerator.normal.generate)))
      val request = requestGenerator.maximal.generate

      when(userRepository.find(UserFilters(id = Some(user.id)))).thenReturn(Some(user).asDBIO)
      when(userRepository.update(any)).thenReturn(Some(user).asDBIO)

      userUpdateService.update(authAdmin, user.id, request).asserting(_ shouldEqual UserUpdateResult.Ok(user))

      verify(userRepository).find(UserFilters(id = Some(user.id)))

      verify(userRepository).update(argThat((user: User) => user.archivedAt.isDefined))
    }
  }

}
