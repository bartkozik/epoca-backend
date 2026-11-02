package pl.iterators.epoca.saml.services

import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationUserId, OrganizationUserRole}
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.saml.domain.SamlConfig
import pl.iterators.epoca.saml.repositories.SamlConfigRepository
import pl.iterators.epoca.saml.services.SamlConfigUpsertService.{SamlConfigUpsertRequest, SamlConfigUpsertResult}
import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.utils.ServiceSpec

class SamlConfigUpsertServiceSpec extends ServiceSpec {
  lazy val organizationGenerator: AllGenerators[Organization]       = allGenerators[Organization]
  lazy val requestGenerator: AllGenerators[SamlConfigUpsertRequest] = allGenerators[SamlConfigUpsertRequest]
  lazy val samlConfigGenerator: AllGenerators[SamlConfig]           = allGenerators[SamlConfig]

  trait TestCase extends BaseScope {
    val organizationRepository: OrganizationRepository         = vmock[OrganizationRepository]
    val organizationUserRepository: OrganizationUserRepository = vmock[OrganizationUserRepository]
    val samlConfigRepository: SamlConfigRepository             = vmock[SamlConfigRepository]

    val sut: SamlConfigUpsertService = wire[SamlConfigUpsertService]
  }

  "upsert" should {
    "return OrganizationNotFound when user tries to create saml config in non existing organization" in new TestCase {
      val organization = organizationGenerator.normal.generate
      val orgSlug      = organization.slug
      val request      = requestGenerator.normal.generate

      val orgFilter = OrganizationFilters(slug = Some(orgSlug), forUpdate = true)

      when(organizationRepository.find(orgFilter)).thenReturn(None.asDBIO)

      sut.upsert(authUser, orgSlug, request).asserting(_ shouldEqual SamlConfigUpsertResult.OrganizationNotFound)

      verify(organizationRepository).find(orgFilter)
    }
    "return NoAccessToOrganization when user tries to create saml config in non accessible organization" in new TestCase {
      val organization = organizationGenerator.normal.generate
      val orgSlug      = organization.slug
      val request      = requestGenerator.normal.generate

      val orgFilter     = OrganizationFilters(slug = Some(orgSlug), forUpdate = true)
      val orgUserFilter = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id), forUpdate = true)

      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(orgUserFilter)).thenReturn(None.asDBIO)

      sut.upsert(authUser, orgSlug, request).asserting(_ shouldEqual SamlConfigUpsertResult.NoAccessToOrganization)

      verify(organizationRepository).find(orgFilter)
      verify(organizationUserRepository).find(orgUserFilter)
    }
    "return PermissionsAreTooLow when user tries to create saml config in organization without required role" in new TestCase {
      val organization = organizationGenerator.normal.generate
      val orgSlug      = organization.slug
      val request      = requestGenerator.normal.generate
      val roles        = Seq(OrganizationUserRole.Employee)
      val orgUser      = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, roles, None)

      val orgFilter     = OrganizationFilters(slug = Some(orgSlug), forUpdate = true)
      val orgUserFilter = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id), forUpdate = true)

      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(orgUserFilter)).thenReturn(Some(orgUser).asDBIO)

      sut.upsert(authUser, orgSlug, request).asserting(_ shouldEqual SamlConfigUpsertResult.PermissionsAreTooLow)

      verify(organizationRepository).find(orgFilter)
      verify(organizationUserRepository).find(orgUserFilter)
    }
    "deny Manager changes even with an active organization membership" in new TestCase {
      val organization = organizationGenerator.normal.generate
      val orgSlug      = organization.slug
      val request      = requestGenerator.normal.generate
      val roles        = Seq(OrganizationUserRole.Manager)
      val orgUser      = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, roles, None)
      val samlConfig   = samlConfigGenerator.normal.generate

      val orgFilter     = OrganizationFilters(slug = Some(orgSlug), forUpdate = true)
      val orgUserFilter = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id), forUpdate = true)

      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(orgUserFilter)).thenReturn(Some(orgUser).asDBIO)
      when(samlConfigRepository.upsert(any)).thenReturn(samlConfig.asDBIO)

      sut.upsert(authUser, orgSlug, request).asserting(_ shouldEqual SamlConfigUpsertResult.PermissionsAreTooLow)

      verify(organizationRepository).find(orgFilter)
      verify(organizationUserRepository).find(orgUserFilter)
      org.mockito.Mockito.verifyNoInteractions(samlConfigRepository)
    }
    "return Created with service data" in new TestCase {
      val organization = organizationGenerator.normal.generate
      val orgSlug      = organization.slug
      val request      = requestGenerator.normal.generate
      val roles        = Seq(OrganizationUserRole.Owner)
      val orgUser      = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, roles, None)
      val samlConfig   = samlConfigGenerator.normal.generate

      val orgFilter     = OrganizationFilters(slug = Some(orgSlug), forUpdate = true)
      val orgUserFilter = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id), forUpdate = true)

      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(orgUserFilter)).thenReturn(Some(orgUser).asDBIO)
      when(samlConfigRepository.upsert(any)).thenReturn(samlConfig.asDBIO)

      sut.upsert(authUser, orgSlug, request).asserting(_ shouldEqual SamlConfigUpsertResult.Created(samlConfig))

      verify(organizationRepository).find(orgFilter)
      verify(organizationUserRepository).find(orgUserFilter)
      verify(samlConfigRepository).upsert(argThat { (inserted: SamlConfig) =>
        inserted.organizationId == organization.id &&
        inserted.idpCert == request.idpCert &&
        inserted.idpEntityId == request.idpEntityId &&
        inserted.idpSsoUrl == request.idpSsoUrl &&
        inserted.langCode == request.langCode &&
        inserted.validFor == request.validFor &&
        inserted.allowAccountInit == request.allowAccountInit &&
        inserted.isEnabled == request.isEnabled
      })
    }
  }

}
