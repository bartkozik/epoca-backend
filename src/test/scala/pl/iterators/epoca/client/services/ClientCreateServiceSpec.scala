package pl.iterators.epoca.client.services

import pl.iterators.epoca.client.domain.Client
import pl.iterators.epoca.client.repositories.ClientRepository
import pl.iterators.epoca.client.repositories.ClientRepository.ClientFilters
import pl.iterators.epoca.client.services.ClientCreateService.{ClientCreateRequest, ClientCreateResult}
import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationSlug, OrganizationUserId, OrganizationUserRole}
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.utils.ServiceSpec

class ClientCreateServiceSpec extends ServiceSpec {
  lazy val organizationGenerator: AllGenerators[Organization]       = allGenerators[Organization]
  lazy val orgSlugGenerator: AllGenerators[OrganizationSlug]        = allGenerators[OrganizationSlug]
  lazy val clientGenerator: AllGenerators[Client]                   = allGenerators[Client]
  lazy val requestGenerator: AllGenerators[ClientCreateRequest]     = allGenerators[ClientCreateRequest]
  lazy val rolesGenerator: AllGenerators[Seq[OrganizationUserRole]] = allGenerators[Seq[OrganizationUserRole]]
  trait TestCase extends BaseScope {
    val clientRepository: ClientRepository                     = vmock[ClientRepository]
    val organizationUserRepository: OrganizationUserRepository = vmock[OrganizationUserRepository]
    val organizationRepository: OrganizationRepository         = vmock[OrganizationRepository]
    val sut: ClientCreateService                               = wire[ClientCreateService]
  }

  "create" should {
    "return OrganizationNotFound when admin tries to create client in non existing organization" in new TestCase {
      val orgSlug = orgSlugGenerator.normal.generate
      val request = requestGenerator.normal.generate

      when(organizationRepository.find(any)).thenReturn(None.asDBIO)

      sut.create(authAdmin, orgSlug, request).asserting(_ shouldEqual ClientCreateResult.OrganizationNotFound)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
    }

    "return NoAccessToOrganization when user not from organization tries to create client" in new TestCase {
      val organization = organizationGenerator.normal.generate.copy(archivedAt = None)
      val orgSlug      = orgSlugGenerator.normal.generate
      val request      = requestGenerator.normal.generate

      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(any)).thenReturn(None.asDBIO)

      sut.create(authUser, orgSlug, request).asserting(_ shouldEqual ClientCreateResult.NoAccessToOrganization)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
      verify(organizationUserRepository).find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id)))
    }

    "employee can't create a client" in new TestCase {
      val organization = organizationGenerator.normal.generate.copy(archivedAt = None)
      val orgSlug      = orgSlugGenerator.normal.generate
      val request      = requestGenerator.normal.generate
      val roles        = Seq(OrganizationUserRole.Employee)
      val orgUser      = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, roles, None)

      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(any)).thenReturn(Some(orgUser).asDBIO)

      sut.create(authUser, orgSlug, request).asserting(_ shouldEqual ClientCreateResult.InsufficientRole)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
      verify(organizationUserRepository).find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id)))
    }

    "return DuplicatedName if client with the same name already exists in this organization" in new TestCase {
      val organization = organizationGenerator.normal.generate.copy(archivedAt = None)
      val orgSlug      = orgSlugGenerator.normal.generate
      val client       = clientGenerator.normal.generate.copy(archivedAt = None, organizationId = organization.id)
      val request      = requestGenerator.normal.generate
      val roles        = Seq(OrganizationUserRole.Manager)
      val orgUser      = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, roles, None)

      when(organizationUserRepository.find(any)).thenReturn(Some(orgUser).asDBIO)
      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(clientRepository.find(any)).thenReturn(Some(client).asDBIO)

      sut.create(authUser, orgSlug, request).asserting(_ shouldEqual ClientCreateResult.DuplicatedName)

      verify(organizationUserRepository).find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id)))
      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
      verify(clientRepository).find(ClientFilters(organizationIds = Some(Seq(organization.id)), name = Some(request.name)))
    }

    "return Created with new client for user assigned to existing organization" in new TestCase {
      val organization = organizationGenerator.normal.generate.copy(archivedAt = None)
      val orgSlug      = orgSlugGenerator.normal.generate
      val client       = clientGenerator.normal.generate.copy(archivedAt = None, organizationId = organization.id)
      val request      = requestGenerator.normal.generate
      val roles        = Seq(OrganizationUserRole.Manager)
      val orgUser      = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, roles, None)

      when(organizationUserRepository.find(any)).thenReturn(Some(orgUser).asDBIO)
      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(clientRepository.find(any)).thenReturn(None.asDBIO)
      when(clientRepository.insert(any)).thenReturn(client.asDBIO)

      sut.create(authUser, orgSlug, request).asserting(_ shouldEqual ClientCreateResult.Created(client))

      verify(organizationUserRepository).find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id)))
      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
      verify(clientRepository).find(ClientFilters(organizationIds = Some(Seq(organization.id)), name = Some(request.name)))
      verify(clientRepository).insert(argThat { (client: Client) =>
        client.organizationId == organization.id &&
        client.name == request.name &&
        client.createdBy == authUser.id &&
        client.modifiedBy == authUser.id &&
        client.archivedAt.isEmpty
      })
    }

    "return Created with new client for admin" in new TestCase {
      val organization = organizationGenerator.normal.generate.copy(archivedAt = None)
      val orgSlug      = orgSlugGenerator.normal.generate
      val client       = clientGenerator.normal.generate.copy(archivedAt = None, organizationId = organization.id)
      val request      = requestGenerator.normal.generate

      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(clientRepository.insert(any)).thenReturn(client.asDBIO)
      when(clientRepository.find(any)).thenReturn(None.asDBIO)

      sut.create(authAdmin, orgSlug, request).asserting(_ shouldEqual ClientCreateResult.Created(client))

      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
      verify(clientRepository).find(ClientFilters(organizationIds = Some(Seq(organization.id)), name = Some(request.name)))
      verify(clientRepository).insert(argThat { (client: Client) =>
        client.organizationId == organization.id &&
        client.name == request.name &&
        client.color == request.color &&
        client.texture == request.texture &&
        client.logo == request.logo &&
        client.`type` == request.`type` &&
        client.createdBy == authAdmin.id &&
        client.modifiedBy == authAdmin.id &&
        client.createdAt == client.modifiedAt &&
        client.archivedAt.isEmpty &&
        client.archivedBy.isEmpty
      })
    }
  }
}
