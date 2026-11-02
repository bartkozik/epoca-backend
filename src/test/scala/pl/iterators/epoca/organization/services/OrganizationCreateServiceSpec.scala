package pl.iterators.epoca.organization.services

import org.mockito.invocation.InvocationOnMock
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationUserId
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationUserRole.Owner
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.organization.services.OrganizationCreateService.{OrganizationCreateRequest, OrganizationCreateResult}
import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.repositories.UserRepository
import pl.iterators.epoca.user.repositories.UserRepository.UserFilters
import pl.iterators.epoca.utils.ServiceSpec

class OrganizationCreateServiceSpec extends ServiceSpec {
  lazy val organizationGenerator: AllGenerators[Organization]         = allGenerators[Organization]
  lazy val requestGenerator: AllGenerators[OrganizationCreateRequest] = allGenerators[OrganizationCreateRequest]
  lazy val userGenerator: AllGenerators[User]                         = allGenerators[User]

  trait TestCase extends BaseScope {
    val organizationRepository: OrganizationRepository         = vmock[OrganizationRepository]
    val organizationUserRepository: OrganizationUserRepository = vmock[OrganizationUserRepository]
    val userRepository: UserRepository                         = vmock[UserRepository]

    val organization: Organization = organizationGenerator.normal.generate.copy(archivedAt = None)
    val user: User                 = userGenerator.normal.generate.copy(archivedAt = None)
    val request                    = requestGenerator.normal.generate

    when(userRepository.find(any)).thenReturn(Some(user).asDBIO)

    val config: OrganizationCreateService.Config = OrganizationCreateService.Config(Some(3))
    val sut: OrganizationCreateService           = wire[OrganizationCreateService]
  }

  "create" should {

    "return UserOrganizationsLimitExceeded if user organizations count is higher or equal to limit and tries to create new one" in new TestCase {
      when(organizationRepository.count(any)).thenReturn(config.organizationLimitUserCanCreate.get.asDBIO)

      sut
        .create(authUser, request)
        .asserting(
          _ shouldEqual OrganizationCreateResult
            .UserOrganizationsLimitExceeded(config.organizationLimitUserCanCreate.get, config.organizationLimitUserCanCreate.get)
        )

      verify(organizationRepository).count(OrganizationFilters(creatorId = Some(authUser.id)))
      verify(userRepository).find(UserFilters(id = Some(authUser.id)))
    }

    "return SlugIsAlreadyTaken if requested slug is taken" in new TestCase {
      when(organizationRepository.count(any)).thenReturn(1.asDBIO)
      when(organizationRepository.find(OrganizationFilters(slug = Some(request.slug)))).thenReturn(Some(organization).asDBIO)

      sut.create(authUser, request).asserting(_ shouldEqual OrganizationCreateResult.SlugIsAlreadyTaken)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(request.slug)))
      verify(organizationRepository).count(OrganizationFilters(creatorId = Some(authUser.id)))
      verify(userRepository).find(UserFilters(id = Some(authUser.id)))
    }

    "return Created with new organization" in new TestCase {
      val orgUser = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, Seq(Owner), None)

      when(organizationRepository.count(any)).thenReturn(1.asDBIO)
      when(organizationRepository.find(any)).thenReturn(None.asDBIO)
      when(organizationRepository.insert(any)).thenReturn(organization.asDBIO)
      when(organizationUserRepository.upsert(any)).thenAnswer { (invocation: InvocationOnMock) =>
        invocation.getArguments()(0).asInstanceOf[OrganizationUser].asDBIO
      }

      sut.create(authUser, request).asserting(_ shouldEqual OrganizationCreateResult.Created(organization))

      verify(organizationRepository).count(OrganizationFilters(creatorId = Some(authUser.id)))
      verify(organizationRepository).find(OrganizationFilters(slug = Some(request.slug)))
      verify(organizationRepository).insert(argThat { (organization: Organization) =>
        organization.name == request.name &&
        organization.slug == request.slug &&
        organization.createdBy == authUser.id &&
        organization.modifiedBy == authUser.id &&
        organization.createdAt == organization.modifiedAt &&
        organization.archivedAt.isEmpty
      })
      verify(organizationUserRepository).upsert(argThat((arg: OrganizationUser) => arg.copy(id = orgUser.id) == orgUser))
      verify(userRepository).find(UserFilters(id = Some(authUser.id)))
    }
    "return Created with new organization if admin already has organization and tries to create new one" in new TestCase {
      val orgUser = OrganizationUser(OrganizationUserId.random, organization.id, authAdmin.id, Seq(Owner), None)

      when(organizationRepository.find(any)).thenReturn(None.asDBIO)
      when(organizationRepository.insert(any)).thenReturn(organization.asDBIO)
      when(organizationUserRepository.upsert(any)).thenAnswer { (invocation: InvocationOnMock) =>
        invocation.getArguments()(0).asInstanceOf[OrganizationUser].asDBIO
      }

      sut.create(authAdmin, request).asserting(_ shouldEqual OrganizationCreateResult.Created(organization))

      verify(organizationRepository).find(OrganizationFilters(slug = Some(request.slug)))
      verify(organizationRepository).insert(argThat { (organization: Organization) =>
        organization.name == request.name &&
        organization.slug == request.slug &&
        organization.createdBy == authAdmin.id &&
        organization.modifiedBy == authAdmin.id &&
        organization.archivedAt.isEmpty
      })
      verify(organizationUserRepository).upsert(argThat((arg: OrganizationUser) => arg.copy(id = orgUser.id) == orgUser))
      verify(userRepository).find(UserFilters(id = Some(authAdmin.id)))
    }
  }
}
