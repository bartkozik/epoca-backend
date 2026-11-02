package pl.iterators.epoca.saml.services

import com.onelogin.saml2.settings.Saml2Settings
import pl.iterators.epoca.organization.domain.Organization
import pl.iterators.epoca.organization.repositories.OrganizationRepository
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.saml.domain.SamlConfig
import pl.iterators.epoca.saml.domain.SamlDomain.SamlIsEnabled
import pl.iterators.epoca.saml.repositories.SamlConfigRepository
import pl.iterators.epoca.saml.services.SamlConfigXmlService.SamlConfigXmlFindResult
import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.utils.ServiceSpec

class SamlConfigXmlServiceSpec extends ServiceSpec {
  lazy val organizationGenerator: AllGenerators[Organization] = allGenerators[Organization]
  lazy val samlConfigGenerator: AllGenerators[SamlConfig]     = allGenerators[SamlConfig]
  lazy val stringGenerator: AllGenerators[String]             = allGenerators[String]

  trait TestCase extends BaseScope {
    val organizationRepository: OrganizationRepository = vmock[OrganizationRepository]
    val samlConfigRepository: SamlConfigRepository     = vmock[SamlConfigRepository]
    val samlUtilsService: SamlUtilsService             = vmock[SamlUtilsService]

    val sut: SamlConfigXmlService = wire[SamlConfigXmlService]
  }

  "find" should {
    "return OrganizationNotFound when user tries to get xml of saml config in non existing organization" in new TestCase {
      val organization = organizationGenerator.normal.generate
      val slug         = organization.slug

      val orgFilter = OrganizationFilters(slug = Some(slug))

      when(organizationRepository.find(orgFilter)).thenReturn(None.asDBIO)

      sut.find(slug).asserting(_ shouldEqual SamlConfigXmlFindResult.OrganizationNotFound)

      verify(organizationRepository).find(orgFilter)
    }
    "return SamlConfigNotFound when user tries to get xml of non existing saml config" in new TestCase {
      val organization = organizationGenerator.normal.generate
      val slug         = organization.slug

      val orgFilter = OrganizationFilters(slug = Some(slug))

      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(samlConfigRepository.find(organization.id)).thenReturn(None.asDBIO)

      sut.find(slug).asserting(_ shouldEqual SamlConfigXmlFindResult.SamlConfigNotFound)

      verify(organizationRepository).find(orgFilter)
      verify(samlConfigRepository).find(organization.id)
    }
    "return SamlDisabled if saml config exists but it isn't enabled" in new TestCase {
      val organization = organizationGenerator.normal.generate
      val slug         = organization.slug
      val samlConfig   = samlConfigGenerator.normal.generate.copy(isEnabled = SamlIsEnabled(false))

      val orgFilter = OrganizationFilters(slug = Some(slug))

      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(samlConfigRepository.find(organization.id)).thenReturn(Some(samlConfig).asDBIO)

      sut.find(slug).asserting(_ shouldEqual SamlConfigXmlFindResult.SamlDisabled)

      verify(organizationRepository).find(orgFilter)
      verify(samlConfigRepository).find(organization.id)
    }
    "return Ok with service data otherwise" in new TestCase {
      val organization = organizationGenerator.normal.generate
      val samlConfig   = samlConfigGenerator.normal.generate.copy(isEnabled = SamlIsEnabled(true))
      val slug         = organization.slug
      val samlSettings = mock[Saml2Settings]
      val answer       = stringGenerator.normal.generate

      val orgFilter = OrganizationFilters(slug = Some(slug))

      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(samlConfigRepository.find(organization.id)).thenReturn(Some(samlConfig).asDBIO)
      when(samlUtilsService.settings(samlConfig, organization)).thenReturn(samlSettings.asIO)
      when(samlSettings.getSPMetadata).thenReturn(answer)

      sut.find(slug).asserting(_ shouldEqual SamlConfigXmlFindResult.Ok(answer))

      verify(organizationRepository).find(orgFilter)
      verify(samlConfigRepository).find(organization.id)
      verify(samlUtilsService).settings(samlConfig, organization)
    }
  }
}
