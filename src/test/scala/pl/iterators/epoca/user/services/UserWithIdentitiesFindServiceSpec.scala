package pl.iterators.epoca.user.services

import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.user.domain.UserDomain.UserAccountType
import pl.iterators.epoca.user.domain.{User, UserIdentity, UserWithIdentities}
import pl.iterators.epoca.user.repositories.UserIdentityRepository.UserIdentityFilters
import pl.iterators.epoca.user.repositories.UserRepository.UserFilters
import pl.iterators.epoca.user.repositories.{UserIdentityRepository, UserRepository}
import pl.iterators.epoca.user.services.UserWithIdentitiesFindService.UserWithIdentitiesFindResult
import pl.iterators.epoca.utils.ServiceSpec

class UserWithIdentitiesFindServiceSpec extends ServiceSpec {
  val userGenerator: AllGenerators[User]                 = allGenerators[User]
  val userIdentityGenerator: AllGenerators[UserIdentity] = allGenerators[UserIdentity]

  trait TestCase extends BaseScope {
    val userRepository: UserRepository                 = vmock[UserRepository]
    val userIdentityRepository: UserIdentityRepository = vmock[UserIdentityRepository]

    val userWithIdentitiesFindService: UserWithIdentitiesFindService = wire[UserWithIdentitiesFindService]
  }

  "find" should {
    "returns NotFound when repository do not return user" in new TestCase {
      val user = userGenerator.maximal.generate

      when(userRepository.find(UserFilters(id = Some(user.id)))).thenReturn(None.asDBIO)

      userWithIdentitiesFindService.find(user.id).asserting(_ shouldEqual UserWithIdentitiesFindResult.UserDoNotExist)

      verify(userRepository).find(UserFilters(id = Some(user.id)))
    }
    "returns the same NotFound for a service account hidden by the Human repository" in new TestCase {
      val serviceAccount = userGenerator.maximal.generate.copy(accountType = UserAccountType.ServiceAccount, archivedAt = None)

      when(userRepository.find(UserFilters(id = Some(serviceAccount.id)))).thenReturn(None.asDBIO)

      userWithIdentitiesFindService.find(serviceAccount.id).asserting(_ shouldEqual UserWithIdentitiesFindResult.UserDoNotExist)

      verify(userRepository).find(UserFilters(id = Some(serviceAccount.id)))
      verify(userIdentityRepository, times(0)).list(any)
    }
    "returns Ok with user data otherwise" in new TestCase {
      val user       = userGenerator.maximal.generate
      val identities = Seq(userIdentityGenerator.normal.generate, userIdentityGenerator.normal.generate)

      when(userRepository.find(UserFilters(id = Some(user.id)))).thenReturn(Some(user).asDBIO)

      when(userIdentityRepository.list(UserIdentityFilters(userId = Some(user.id))))
        .thenReturn(identities.asDBIO)

      val expected = UserWithIdentities(
        id = user.id,
        identities = identities,
        firstName = user.firstName,
        lastName = user.lastName,
        avatar = user.avatar,
        color = user.color,
        isSuperAdmin = user.isSuperAdmin,
        createdAt = user.createdAt,
        modifiedAt = user.modifiedAt,
        archivedAt = user.archivedAt,
        accountType = user.accountType
      )

      userWithIdentitiesFindService.find(user.id).asserting(_ shouldEqual UserWithIdentitiesFindResult.Ok(expected))

      verify(userRepository).find(UserFilters(id = Some(user.id)))

      verify(userIdentityRepository).list(UserIdentityFilters(userId = Some(user.id)))
    }
  }

}
