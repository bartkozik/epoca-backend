package pl.iterators.epoca.unarchiver.services

import pl.iterators.epoca.client.domain.Client
import pl.iterators.epoca.client.domain.ClientDomain.ClientModifiedAt
import pl.iterators.epoca.client.repositories.ClientRepository
import pl.iterators.epoca.client.repositories.ClientRepository.ClientFilters
import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationSlug, OrganizationUserId, OrganizationUserRole}
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.unarchiver.services.ClientUnarchiveService.ClientUnarchiveResult
import pl.iterators.epoca.utils.ServiceSpec

import java.time.Instant

class ClientUnarchiveServiceSpec extends ServiceSpec {
  lazy val organizationGenerator: AllGenerators[Organization] = allGenerators[Organization]
  lazy val orgSlugGenerator: AllGenerators[OrganizationSlug]  = allGenerators[OrganizationSlug]
  lazy val clientGenerator: AllGenerators[Client]             = allGenerators[Client]

  private val pastModifiedAt = ClientModifiedAt(Instant.EPOCH)

  trait TestCase extends BaseScope {
    val clientRepository: ClientRepository                     = vmock[ClientRepository]
    val organizationUserRepository: OrganizationUserRepository = vmock[OrganizationUserRepository]
    val organizationRepository: OrganizationRepository         = vmock[OrganizationRepository]

    val sut: ClientUnarchiveService = wire[ClientUnarchiveService]

  }

  "unarchive" should {
    "return OrganizationNotFound when admin tries to unarchive client in non existing organization" in new TestCase {
      val organization = organizationGenerator.normal.generate.copy(archivedAt = None)
      val orgSlug      = orgSlugGenerator.normal.generate
      val client       = clientGenerator.maximal.generate.copy(organizationId = organization.id)

      when(organizationRepository.find(any)).thenReturn(None.asDBIO)

      sut.unarchive(authAdmin, orgSlug, client.id).asserting(_ shouldEqual ClientUnarchiveResult.OrganizationNotFound)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
    }

    "return NoAccessToOrganization when user not from organization tries to unarchive client" in new TestCase {
      val organization = organizationGenerator.normal.generate.copy(archivedAt = None)
      val orgSlug      = orgSlugGenerator.normal.generate
      val client       = clientGenerator.maximal.generate.copy(organizationId = organization.id)

      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(any)).thenReturn(None.asDBIO)

      sut.unarchive(authUser, orgSlug, client.id).asserting(_ shouldEqual ClientUnarchiveResult.NoAccessToOrganization)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
      verify(organizationUserRepository).find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id)))
    }

    "return InsufficientRole when user, with access to organization and insufficient roles, tries to unarchive client" in new TestCase {
      val organization = organizationGenerator.normal.generate.copy(archivedAt = None)
      val orgSlug      = orgSlugGenerator.normal.generate
      val client       = clientGenerator.maximal.generate.copy(organizationId = organization.id)
      val roles        = Seq(OrganizationUserRole.Employee)
      val orgUser      = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, roles, None)

      when(organizationUserRepository.find(any)).thenReturn(Some(orgUser).asDBIO)
      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)

      sut.unarchive(authUser, orgSlug, client.id).asserting(_ shouldEqual ClientUnarchiveResult.InsufficientRole)

      verify(organizationUserRepository).find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id)))
      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
    }

    "return ClientNotFound when user with access to organization tries to unarchive not existing client" in new TestCase {
      val organization = organizationGenerator.normal.generate.copy(archivedAt = None)
      val orgSlug      = orgSlugGenerator.normal.generate
      val client       = clientGenerator.maximal.generate.copy(organizationId = organization.id)
      val roles        = Seq(OrganizationUserRole.Owner)
      val orgUser      = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, roles, None)

      when(organizationUserRepository.find(any)).thenReturn(Some(orgUser).asDBIO)
      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(clientRepository.find(any)).thenReturn(None.asDBIO)

      sut.unarchive(authUser, orgSlug, client.id).asserting(_ shouldEqual ClientUnarchiveResult.ClientNotFound)

      verify(organizationUserRepository).find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id)))
      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
      verify(clientRepository).find(
        ClientFilters(organizationIds = Some(Seq(organization.id)), id = Some(client.id), forUpdate = true, includeArchived = true)
      )
    }

    "return ClientNotFound when admin tries to unarchive not existing client" in new TestCase {
      val organization = organizationGenerator.normal.generate.copy(archivedAt = None)
      val orgSlug      = orgSlugGenerator.normal.generate
      val client       = clientGenerator.maximal.generate.copy(organizationId = organization.id)

      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(clientRepository.find(any)).thenReturn(None.asDBIO)

      sut.unarchive(authAdmin, orgSlug, client.id).asserting(_ shouldEqual ClientUnarchiveResult.ClientNotFound)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
      verify(clientRepository).find(
        ClientFilters(organizationIds = Some(Seq(organization.id)), id = Some(client.id), forUpdate = true, includeArchived = true)
      )
    }

    "return NameNotUnique if unarchived client name is already used" in new TestCase {
      val organization = organizationGenerator.normal.generate.copy(archivedAt = None)
      val orgSlug      = orgSlugGenerator.normal.generate
      val client       = clientGenerator.maximal.generate.copy(organizationId = organization.id)
      val roles        = Seq(OrganizationUserRole.Owner)
      val orgUser      = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, roles, None)

      val unarchivedFilter =
        ClientFilters(organizationIds = Some(Seq(organization.id)), id = Some(client.id), forUpdate = true, includeArchived = true)
      val uniqueNameFilter = ClientFilters(organizationIds = Some(Seq(organization.id)), name = Some(client.name))

      when(organizationUserRepository.find(any)).thenReturn(Some(orgUser).asDBIO)
      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(clientRepository.find(unarchivedFilter)).thenReturn(Some(client).asDBIO)
      when(clientRepository.find(uniqueNameFilter)).thenReturn(Some(client).asDBIO)

      sut.unarchive(authUser, orgSlug, client.id).asserting(_ shouldEqual ClientUnarchiveResult.NameNotUnique)

      verify(organizationUserRepository).find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id)))
      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
      verify(clientRepository).find(unarchivedFilter)
      verify(clientRepository).find(uniqueNameFilter)
    }

    "return Unarchived for user with access to organization with unarchived client" in new TestCase {
      val organization     = organizationGenerator.normal.generate.copy(archivedAt = None)
      val orgSlug          = orgSlugGenerator.normal.generate
      val client           = clientGenerator.maximal.generate.copy(organizationId = organization.id, modifiedAt = pastModifiedAt)
      val unarchivedClient = client.copy(archivedBy = None, archivedAt = None)
      val roles            = Seq(OrganizationUserRole.Owner)
      val orgUser          = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, roles, None)

      val unarchivedFilter =
        ClientFilters(organizationIds = Some(Seq(organization.id)), id = Some(client.id), forUpdate = true, includeArchived = true)
      val uniqueNameFilter = ClientFilters(organizationIds = Some(Seq(organization.id)), name = Some(client.name))

      when(organizationUserRepository.find(any)).thenReturn(Some(orgUser).asDBIO)
      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(clientRepository.find(unarchivedFilter)).thenReturn(Some(client).asDBIO)
      when(clientRepository.find(uniqueNameFilter)).thenReturn(None.asDBIO)
      when(clientRepository.update(any)).thenReturn(Some(unarchivedClient).asDBIO)

      sut.unarchive(authUser, orgSlug, client.id).asserting(_ shouldEqual ClientUnarchiveResult.Unarchived(unarchivedClient))

      verify(organizationUserRepository).find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id)))
      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
      verify(clientRepository).find(unarchivedFilter)
      verify(clientRepository).find(uniqueNameFilter)
      verify(clientRepository).update(argThat { (clientUpdated: Client) =>
        clientUpdated.archivedAt.isEmpty &&
        clientUpdated.archivedBy.isEmpty &&
        clientUpdated.modifiedBy == authUser.id &&
        clientUpdated.modifiedAt.isAfter(pastModifiedAt) &&
        clientUpdated.copy(
          modifiedAt = client.modifiedAt,
          modifiedBy = client.modifiedBy,
          archivedAt = client.archivedAt,
          archivedBy = client.archivedBy
        ) == client
      })
    }

    "return Unarchived for admin with unarchived client" in new TestCase {
      val organization     = organizationGenerator.normal.generate.copy(archivedAt = None)
      val orgSlug          = orgSlugGenerator.normal.generate
      val client           = clientGenerator.maximal.generate.copy(organizationId = organization.id, modifiedAt = pastModifiedAt)
      val unarchivedClient = client.copy(archivedBy = None, archivedAt = None)

      val unarchivedFilter =
        ClientFilters(organizationIds = Some(Seq(organization.id)), id = Some(client.id), forUpdate = true, includeArchived = true)
      val uniqueNameFilter = ClientFilters(organizationIds = Some(Seq(organization.id)), name = Some(client.name))

      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(clientRepository.find(unarchivedFilter)).thenReturn(Some(client).asDBIO)
      when(clientRepository.find(uniqueNameFilter)).thenReturn(None.asDBIO)
      when(clientRepository.update(any)).thenReturn(Some(unarchivedClient).asDBIO)

      sut.unarchive(authAdmin, orgSlug, client.id).asserting(_ shouldEqual ClientUnarchiveResult.Unarchived(unarchivedClient))

      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
      verify(clientRepository).find(unarchivedFilter)
      verify(clientRepository).find(uniqueNameFilter)
      verify(clientRepository).update(argThat { (clientUpdated: Client) =>
        clientUpdated.archivedAt.isEmpty &&
        clientUpdated.archivedBy.isEmpty &&
        clientUpdated.modifiedBy == authAdmin.id &&
        clientUpdated.modifiedAt.isAfter(pastModifiedAt) &&
        clientUpdated.copy(
          modifiedAt = client.modifiedAt,
          modifiedBy = client.modifiedBy,
          archivedAt = client.archivedAt,
          archivedBy = client.archivedBy
        ) == client
      })
    }
  }
}
