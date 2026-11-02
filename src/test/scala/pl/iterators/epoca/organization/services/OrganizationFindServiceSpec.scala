package pl.iterators.epoca.organization.services

import pl.iterators.epoca.organization.domain.Organization
import pl.iterators.epoca.organization.repositories.OrganizationRepository
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.services.OrganizationFindService.OrganizationFindResult
import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.utils.ServiceSpec

class OrganizationFindServiceSpec extends ServiceSpec {
  lazy val organizationGenerator: AllGenerators[Organization] = allGenerators[Organization]
  trait TestCase extends BaseScope {
    val organizationRepository: OrganizationRepository = vmock[OrganizationRepository]

    val sut: OrganizationFindService = wire[OrganizationFindService]

  }

  "find" should {
    "return NotFound if user can't access organization" in new TestCase {
      val organization = organizationGenerator.normal.generate
      val slug         = organization.slug
      val orgFilter    = OrganizationFilters(userId = Some(authUser.id), slug = Some(slug))

      when(organizationRepository.find(orgFilter)).thenReturn(None.asDBIO)

      sut.find(authUser, slug).asserting(_ shouldEqual OrganizationFindResult.NotFound)

      verify(organizationRepository).find(orgFilter)
    }
    "return Ok with found organization" in new TestCase {
      val organization = organizationGenerator.normal.generate
      val slug         = organization.slug
      val orgFilter    = OrganizationFilters(userId = Some(authUser.id), slug = Some(slug))

      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)

      sut.find(authUser, slug).asserting(_ shouldEqual OrganizationFindResult.Ok(organization))

      verify(organizationRepository).find(orgFilter)
    }
    "return Ok with found organization - ADMIN" in new TestCase {
      val organization = organizationGenerator.normal.generate
      val slug         = organization.slug
      val orgFilter    = OrganizationFilters(slug = Some(slug))

      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)

      sut.find(authAdmin, slug).asserting(_ shouldEqual OrganizationFindResult.Ok(organization))

      verify(organizationRepository).find(orgFilter)
    }

  }
}
