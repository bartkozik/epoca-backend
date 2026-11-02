package pl.iterators.epoca.saml.services

import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationUserId
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.saml.domain.SamlConfig
import pl.iterators.epoca.saml.repositories.SamlConfigRepository
import pl.iterators.epoca.saml.services.SamlConfigGetService.SamlConfigGetResult
import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.utils.ServiceSpec

class SamlConfigGetServiceSpec extends ServiceSpec {
  lazy val organizationGenerator: AllGenerators[Organization] = allGenerators[Organization]
  lazy val samlConfigGenerator: AllGenerators[SamlConfig]     = allGenerators[SamlConfig]

  trait TestCase extends BaseScope {
    val organizationRepository: OrganizationRepository         = vmock[OrganizationRepository]
    val organizationUserRepository: OrganizationUserRepository = vmock[OrganizationUserRepository]
    val samlConfigRepository: SamlConfigRepository             = vmock[SamlConfigRepository]

    val sut: SamlConfigGetService = wire[SamlConfigGetService]
  }

  "get" should {
    "return OrganizationNotFound when user tries to get saml config in non existing organization" in new TestCase {
      val organization = organizationGenerator.normal.generate
      val slug         = organization.slug

      val orgFilter = OrganizationFilters(slug = Some(slug))

      when(organizationRepository.find(orgFilter)).thenReturn(None.asDBIO)

      sut.get(authUser, slug).asserting(_ shouldEqual SamlConfigGetResult.OrganizationNotFound)

      verify(organizationRepository).find(orgFilter)
    }
    "return NoAccessToOrganization when user tries to get saml config in organization without membership" in new TestCase {
      val organization = organizationGenerator.normal.generate
      val slug         = organization.slug

      val orgFilter     = OrganizationFilters(slug = Some(slug))
      val orgUserFilter = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id))

      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(orgUserFilter)).thenReturn(None.asDBIO)

      sut.get(authUser, slug).asserting(_ shouldEqual SamlConfigGetResult.NoAccessToOrganization)

      verify(organizationRepository).find(orgFilter)
      verify(organizationUserRepository).find(orgUserFilter)
    }
    "return SamlConfigNotFound when user tries to get saml config that doesn't exist" in new TestCase {
      val organization = organizationGenerator.normal.generate
      val slug         = organization.slug
      val orgUser      = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, Seq(), None)

      val orgFilter     = OrganizationFilters(slug = Some(slug))
      val orgUserFilter = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id))

      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(orgUserFilter)).thenReturn(Some(orgUser).asDBIO)
      when(samlConfigRepository.find(organization.id)).thenReturn(None.asDBIO)

      sut.get(authUser, slug).asserting(_ shouldEqual SamlConfigGetResult.SamlConfigNotFound)

      verify(organizationRepository).find(orgFilter)
      verify(organizationUserRepository).find(orgUserFilter)
      verify(samlConfigRepository).find(organization.id)
    }
    "return Ok with service data otherwise" in new TestCase {
      val organization = organizationGenerator.normal.generate
      val slug         = organization.slug
      val orgUser      = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, Seq(), None)
      val samlConfig   = samlConfigGenerator.normal.generate

      val orgFilter     = OrganizationFilters(slug = Some(slug))
      val orgUserFilter = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id))

      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(orgUserFilter)).thenReturn(Some(orgUser).asDBIO)
      when(samlConfigRepository.find(organization.id)).thenReturn(Some(samlConfig).asDBIO)

      sut.get(authUser, slug).asserting(_ shouldEqual SamlConfigGetResult.Ok(samlConfig))

      verify(organizationRepository).find(orgFilter)
      verify(organizationUserRepository).find(orgUserFilter)
      verify(samlConfigRepository).find(organization.id)
    }
    "return Ok with service data and skips steps as admin" in new TestCase {
      val organization = organizationGenerator.normal.generate
      val slug         = organization.slug
      val samlConfig   = samlConfigGenerator.normal.generate

      val orgFilter = OrganizationFilters(slug = Some(slug))

      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(samlConfigRepository.find(organization.id)).thenReturn(Some(samlConfig).asDBIO)

      sut.get(authAdmin, slug).asserting(_ shouldEqual SamlConfigGetResult.Ok(samlConfig))

      verify(organizationRepository).find(orgFilter)
      verify(samlConfigRepository).find(organization.id)
    }
  }
}
