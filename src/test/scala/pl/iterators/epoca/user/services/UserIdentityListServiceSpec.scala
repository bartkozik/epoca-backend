package pl.iterators.epoca.user.services

import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.user.domain.UserDomain.{IdentityType, UserIdentityValue}
import pl.iterators.epoca.user.domain.{UserIdentity, UserIdentityDto}
import pl.iterators.epoca.user.repositories.UserIdentityRepository
import pl.iterators.epoca.user.repositories.UserIdentityRepository.UserIdentityFilters
import pl.iterators.epoca.user.services.UserIdentityListService._
import pl.iterators.epoca.utils.ServiceSpec

class UserIdentityListServiceSpec extends ServiceSpec {
  val userIdentityGenerator: AllGenerators[UserIdentity] = allGenerators[UserIdentity]

  trait TestCase extends BaseScope {
    val userIdentityRepository: UserIdentityRepository = vmock[UserIdentityRepository]

    val userIdentityListService: UserIdentityListService = wire[UserIdentityListService]
  }

  IdentityType.values.foreach { kind =>
    s"list $kind" should {
      "return public values and redact secret values" in new TestCase {
        val userIdentity = userIdentityGenerator.maximal.generate.copy(`type` = kind)

        val userIdentityDto = UserIdentityDto(
          id = userIdentity.id,
          userId = userIdentity.userId,
          `type` = userIdentity.`type`,
          value =
            if (Set[IdentityType](IdentityType.MailAddress, IdentityType.Phone, IdentityType.Saml).contains(kind)) userIdentity.value
            else UserIdentityValue(""),
          main = userIdentity.main,
          confirmed = userIdentity.confirmed,
          deviceId = userIdentity.deviceId,
          createdAt = userIdentity.createdAt
        )

        when(userIdentityRepository.list(UserIdentityFilters(userId = Some(authUser.id)))).thenReturn(Seq(userIdentity).asDBIO)

        userIdentityListService
          .list(authUser)
          .asserting(_ shouldEqual UserIdentityListResult.Ok(Seq(userIdentityDto)))

        verify(userIdentityRepository).list(UserIdentityFilters(userId = Some(authUser.id)))
      }
    }
  }
}
