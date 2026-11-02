package pl.iterators.epoca.organization.services
import org.mockito.invocation.InvocationOnMock
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.organization.domain.OrganizationDomain.{
  OrganizationArchivedAt,
  OrganizationModifiedAt,
  OrganizationUserId,
  OrganizationUserRole
}
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.organization.services.OrganizationDeleteService.OrganizationDeleteResult
import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.utils.ServiceSpec
import pl.iterators.epoca.utils.log.LoggingSupport

import java.time.Instant

class OrganizationDeleteServiceSpec extends ServiceSpec with LoggingSupport {
  private val pastModifiedAt = OrganizationModifiedAt(Instant.EPOCH)

  lazy val organizationGenerator: AllGenerators[Organization]       = allGenerators[Organization]
  lazy val rolesGenerator: AllGenerators[Seq[OrganizationUserRole]] = allGenerators[Seq[OrganizationUserRole]]
  trait TestCase extends BaseScope {
    val organizationRepository: OrganizationRepository         = vmock[OrganizationRepository]
    val organizationUserRepository: OrganizationUserRepository = vmock[OrganizationUserRepository]

    val sut: OrganizationDeleteService = wire[OrganizationDeleteService]

    val organization: Organization = organizationGenerator.normal.generate.copy(archivedAt = None, modifiedAt = pastModifiedAt)
    val authOrganization: Organization =
      organizationGenerator.normal.generate.copy(archivedAt = None, createdBy = authUser.id, modifiedAt = pastModifiedAt)
    val roles: Seq[OrganizationUserRole] = rolesGenerator.normal.generate
  }

  def generateOrganizationUser(
    org: Organization,
    auth: AuthContext,
    roles: Seq[OrganizationUserRole]
  ): OrganizationUser =
    OrganizationUser(OrganizationUserId.random, org.id, auth.id, roles, None)

  "delete" should {

    "return OrganizationNotFound when user tries to delete non existing organization" in new TestCase {
      when(organizationRepository.find(any)).thenReturn(None.asDBIO)

      sut.delete(authUser, organization.slug).asserting(_ shouldEqual OrganizationDeleteResult.OrganizationNotFound)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(organization.slug)))
    }

    "return OrganizationNotFound when admin tries to delete non existing organization" in new TestCase {
      when(organizationRepository.find(any)).thenReturn(None.asDBIO)

      sut.delete(authAdmin, organization.slug).asserting(_ shouldEqual OrganizationDeleteResult.OrganizationNotFound)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(organization.slug)))
    }

    "return OperationNotPermitted when user tries to delete someone else's organization" in new TestCase {
      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)

      sut.delete(authUser, organization.slug).asserting(_ shouldEqual OrganizationDeleteResult.OperationNotPermitted)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(organization.slug)))
    }

    "return OrganizationHasUsers when organization owner tries to delete his organization with other users assigned" in new TestCase {
      when(organizationRepository.find(any)).thenReturn(Some(authOrganization).asDBIO)
      when(organizationUserRepository.list(any)).thenReturn(Seq(generateOrganizationUser(authOrganization, authAdmin, roles)).asDBIO)

      sut.delete(authUser, authOrganization.slug).asserting(_ shouldEqual OrganizationDeleteResult.OrganizationHasUsers)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(authOrganization.slug)))
      verify(organizationUserRepository).list(OrganizationUserFilters(organizationId = Some(authOrganization.id)))
    }

    "return OrganizationHasUsers when admin tries to delete organization with other users assigned" in new TestCase {
      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.list(any)).thenReturn(Seq(generateOrganizationUser(organization, authUser, roles)).asDBIO)

      sut.delete(authAdmin, organization.slug).asserting(_ shouldEqual OrganizationDeleteResult.OrganizationHasUsers)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(organization.slug)))
      verify(organizationUserRepository).list(OrganizationUserFilters(organizationId = Some(organization.id)))
    }

    "return Deleted when organization owner tries to delete his organization with no existing OrgUsers" in new TestCase {
      when(organizationRepository.find(any)).thenReturn(Some(authOrganization).asDBIO)
      when(organizationUserRepository.list(any)).thenReturn(Seq(generateOrganizationUser(authOrganization, authUser, roles)).asDBIO)
      when(organizationRepository.update(any)).thenAnswer { (invocation: InvocationOnMock) =>
        Some(invocation.getArguments()(0).asInstanceOf[Organization]).asDBIO
      }

      sut.delete(authUser, authOrganization.slug).asserting(_ shouldEqual OrganizationDeleteResult.Deleted)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(authOrganization.slug)))
      verify(organizationUserRepository).list(OrganizationUserFilters(organizationId = Some(authOrganization.id)))

      verify(organizationRepository).update(argThat { (organization: Organization) =>
        organization.modifiedAt.isAfter(pastModifiedAt) &&
        organization.modifiedBy == authUser.id &&
        organization.archivedAt.contains(OrganizationArchivedAt(organization.modifiedAt)) &&
        organization.copy(modifiedAt = authOrganization.modifiedAt, modifiedBy = authOrganization.modifiedBy, archivedAt = None) == authOrganization
      })
    }

    "return Deleted when admin tries to delete organization with no existing OrgUsers" in new TestCase {
      when(organizationRepository.find(any)).thenReturn(Some(authOrganization).asDBIO)
      when(organizationUserRepository.list(any)).thenReturn(Seq(generateOrganizationUser(authOrganization, authUser, roles)).asDBIO)
      when(organizationRepository.update(any)).thenAnswer { (invocation: InvocationOnMock) =>
        Some(invocation.getArguments()(0).asInstanceOf[Organization]).asDBIO
      }

      sut.delete(authAdmin, authOrganization.slug).asserting(_ shouldEqual OrganizationDeleteResult.Deleted)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(authOrganization.slug)))
      verify(organizationUserRepository).list(OrganizationUserFilters(organizationId = Some(authOrganization.id)))

      verify(organizationRepository).update(argThat { (organization: Organization) =>
        organization.modifiedAt.isAfter(pastModifiedAt) &&
        organization.modifiedBy == authAdmin.id &&
        organization.archivedAt.contains(OrganizationArchivedAt(organization.modifiedAt)) &&
        organization.copy(modifiedAt = authOrganization.modifiedAt, modifiedBy = authOrganization.modifiedBy, archivedAt = None) == authOrganization
      })
    }
  }
}
