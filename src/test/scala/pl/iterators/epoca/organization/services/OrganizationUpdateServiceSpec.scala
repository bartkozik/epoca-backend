package pl.iterators.epoca.organization.services

import org.mockito.invocation.InvocationOnMock
import pl.iterators.epoca.organization.domain.Organization
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationModifiedAt
import pl.iterators.epoca.organization.repositories.OrganizationRepository
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.services.OrganizationUpdateService.{OrganizationUpdateRequest, OrganizationUpdateResult}
import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.utils.ServiceSpec

import java.time.Instant

class OrganizationUpdateServiceSpec extends ServiceSpec {
  private val pastModifiedAt = OrganizationModifiedAt(Instant.EPOCH)

  lazy val organizationGenerator: AllGenerators[Organization]         = allGenerators[Organization]
  lazy val requestGenerator: AllGenerators[OrganizationUpdateRequest] = allGenerators[OrganizationUpdateRequest]
  trait TestCase extends BaseScope {
    val organizationRepository: OrganizationRepository = vmock[OrganizationRepository]

    val sut: OrganizationUpdateService = wire[OrganizationUpdateService]

    val organization: Organization = organizationGenerator.normal.generate.copy(archivedAt = None, modifiedAt = pastModifiedAt)
    val authOrganization: Organization =
      organizationGenerator.normal.generate.copy(archivedAt = None, createdBy = authUser.id, modifiedAt = pastModifiedAt)
    val request = requestGenerator.normal.generate
  }

  "update" should {

    "return OrganizationNotFound when user tries to update non existing organization" in new TestCase {
      when(organizationRepository.find(any)).thenReturn(None.asDBIO)

      sut.update(authUser, organization.slug, request).asserting(_ shouldEqual OrganizationUpdateResult.OrganizationNotFound)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(organization.slug)))
    }

    "return OrganizationNotFound when admin tries to update non existing organization" in new TestCase {
      when(organizationRepository.find(any)).thenReturn(None.asDBIO)

      sut.update(authAdmin, organization.slug, request).asserting(_ shouldEqual OrganizationUpdateResult.OrganizationNotFound)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(organization.slug)))
    }

    "return OperationNotPermitted when user tries to update someone else's organization" in new TestCase {
      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)

      sut.update(authUser, organization.slug, request).asserting(_ shouldEqual OrganizationUpdateResult.OperationNotPermitted)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(organization.slug)))
    }

    "return Updated with updated organization when user tries to update his organization" in new TestCase {
      when(organizationRepository.find(any)).thenReturn(Some(authOrganization).asDBIO)
      when(organizationRepository.update(any)).thenAnswer { (invocation: InvocationOnMock) =>
        Some(invocation.getArguments()(0).asInstanceOf[Organization]).asDBIO
      }

      val result = sut.update(authUser, authOrganization.slug, request)
      result.asserting(_ should beAnInstanceOf[OrganizationUpdateResult.Updated])

      result.asserting(_.asInstanceOf[OrganizationUpdateResult.Updated].organization.id shouldEqual authOrganization.id)
      result.asserting(_.asInstanceOf[OrganizationUpdateResult.Updated].organization.name shouldEqual request.name)
      result.asserting(_.asInstanceOf[OrganizationUpdateResult.Updated].organization.createdBy shouldEqual authOrganization.createdBy)
      result.asserting(_.asInstanceOf[OrganizationUpdateResult.Updated].organization.createdAt shouldEqual authOrganization.createdAt)
      result.asserting(_.asInstanceOf[OrganizationUpdateResult.Updated].organization.modifiedBy shouldEqual authUser.id)
      result.asserting(_.asInstanceOf[OrganizationUpdateResult.Updated].organization.modifiedAt.isAfter(pastModifiedAt) should beTrue)
      result.asserting(_.asInstanceOf[OrganizationUpdateResult.Updated].organization.archivedAt shouldEqual None)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(authOrganization.slug)))
      verify(organizationRepository).update(any)
    }

    "return Updated with updated organization when admin tries to update organization" in new TestCase {
      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(organizationRepository.update(any)).thenAnswer { (invocation: InvocationOnMock) =>
        Some(invocation.getArguments()(0).asInstanceOf[Organization]).asDBIO
      }

      val result = sut.update(authAdmin, organization.slug, request)
      result.asserting(_ should beAnInstanceOf[OrganizationUpdateResult.Updated])

      result.asserting(_.asInstanceOf[OrganizationUpdateResult.Updated].organization.id shouldEqual organization.id)
      result.asserting(_.asInstanceOf[OrganizationUpdateResult.Updated].organization.name shouldEqual request.name)
      result.asserting(_.asInstanceOf[OrganizationUpdateResult.Updated].organization.createdBy shouldEqual organization.createdBy)
      result.asserting(_.asInstanceOf[OrganizationUpdateResult.Updated].organization.createdAt shouldEqual organization.createdAt)
      result.asserting(_.asInstanceOf[OrganizationUpdateResult.Updated].organization.modifiedBy shouldEqual authAdmin.id)
      result.asserting(_.asInstanceOf[OrganizationUpdateResult.Updated].organization.modifiedAt.isAfter(pastModifiedAt) should beTrue)
      result.asserting(_.asInstanceOf[OrganizationUpdateResult.Updated].organization.archivedAt shouldEqual None)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(organization.slug)))
      verify(organizationRepository).update(any)
    }
  }
}
