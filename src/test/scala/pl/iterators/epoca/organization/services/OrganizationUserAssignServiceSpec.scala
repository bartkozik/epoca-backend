package pl.iterators.epoca.organization.services

import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationUserId, OrganizationUserRole}
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.organization.services.OrganizationUserAssignService.{OrganizationUserAssignRequest, OrganizationUserAssignResult}
import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.domain.UserDomain.UserAccountType
import pl.iterators.epoca.utils.ServiceSpec

class OrganizationUserAssignServiceSpec extends ServiceSpec {
  lazy val organizationGenerator: AllGenerators[Organization] = allGenerators[Organization]
  lazy val userGenerator: AllGenerators[User]                 = allGenerators[User]

  trait TestCase extends BaseScope {
    val organizationUserMembershipService: OrganizationUserMembershipService = vmock[OrganizationUserMembershipService]
    val organizationRepository: OrganizationRepository                       = vmock[OrganizationRepository]
    val organizationUserRepository: OrganizationUserRepository               = vmock[OrganizationUserRepository]

    val sut: OrganizationUserAssignService = wire[OrganizationUserAssignService]
  }

  "assign" should {
    "return the same not-a-member result for a service account and perform no write" in new TestCase {
      val organization       = organizationGenerator.normal.generate
      val serviceAccount     = userGenerator.normal.generate.copy(accountType = UserAccountType.ServiceAccount, archivedAt = None)
      val request            = OrganizationUserAssignRequest(serviceAccount.id, Seq(OrganizationUserRole.Employee))
      val organizationFilter = OrganizationFilters(slug = Some(organization.slug))
      val membershipFilter   = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(serviceAccount.id))

      when(organizationRepository.find(organizationFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(membershipFilter)).thenReturn(None.asDBIO)

      sut.assign(authAdmin, organization.slug, request).asserting(_ shouldEqual OrganizationUserAssignResult.UserIsNotOrganizationMember)

      verify(organizationRepository).find(organizationFilter)
      verify(organizationUserRepository).find(membershipFilter)
      verify(organizationUserRepository, times(0)).upsert(any)
    }

    "retain Human organization role assignment behavior" in new TestCase {
      val organization       = organizationGenerator.normal.generate
      val human              = userGenerator.normal.generate.copy(accountType = UserAccountType.Human, archivedAt = None)
      val request            = OrganizationUserAssignRequest(human.id, Seq(OrganizationUserRole.Manager))
      val membership         = OrganizationUser(OrganizationUserId.random, organization.id, human.id, Nil, None)
      val organizationFilter = OrganizationFilters(slug = Some(organization.slug))
      val membershipFilter   = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(human.id))

      when(organizationRepository.find(organizationFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(membershipFilter)).thenReturn(Some(membership).asDBIO)
      when(organizationUserRepository.upsert(any)).thenAnswerArgAsDBIO()

      sut.assign(authAdmin, organization.slug, request).asserting(_ shouldEqual OrganizationUserAssignResult.Assigned)

      verify(organizationRepository).find(organizationFilter)
      verify(organizationUserRepository).find(membershipFilter)
      verify(organizationUserRepository).upsert(membership.copy(roles = request.roles))
    }
  }
}
