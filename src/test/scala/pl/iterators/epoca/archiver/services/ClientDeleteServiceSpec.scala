package pl.iterators.epoca.archiver.services

import pl.iterators.epoca.archiver.services.ClientDeleteService.ClientDeleteResult
import pl.iterators.epoca.client.domain.Client
import pl.iterators.epoca.client.repositories.ClientRepository
import pl.iterators.epoca.client.repositories.ClientRepository.ClientFilters
import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationSlug, OrganizationUserId, OrganizationUserRole}
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.project.domain.{Project, ProjectUser}
import pl.iterators.epoca.project.repositories.ProjectRepository.ProjectFilters
import pl.iterators.epoca.project.repositories.ProjectUserRepository.ProjectUserFilters
import pl.iterators.epoca.project.repositories.{ProjectRepository, ProjectUserRepository}
import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.utils.ServiceSpec

class ClientDeleteServiceSpec extends ServiceSpec {
  lazy val organizationGenerator: AllGenerators[Organization]       = allGenerators[Organization]
  lazy val orgSlugGenerator: AllGenerators[OrganizationSlug]        = allGenerators[OrganizationSlug]
  lazy val clientGenerator: AllGenerators[Client]                   = allGenerators[Client]
  lazy val projectGenerator: AllGenerators[Project]                 = allGenerators[Project]
  lazy val projectUserGenerator: AllGenerators[ProjectUser]         = allGenerators[ProjectUser]
  lazy val rolesGenerator: AllGenerators[Seq[OrganizationUserRole]] = allGenerators[Seq[OrganizationUserRole]]
  trait TestCase extends BaseScope {
    val clientRepository: ClientRepository                     = vmock[ClientRepository]
    val organizationUserRepository: OrganizationUserRepository = vmock[OrganizationUserRepository]
    val organizationRepository: OrganizationRepository         = vmock[OrganizationRepository]
    val projectRepository: ProjectRepository                   = vmock[ProjectRepository]
    val projectUserRepository: ProjectUserRepository           = vmock[ProjectUserRepository]

    val sut: ClientDeleteService = wire[ClientDeleteService]

  }

  "delete" should {
    "return OrganizationNotFound when admin tries to delete client in non existing organization" in new TestCase {
      val organization = organizationGenerator.normal.generate.copy(archivedAt = None)
      val orgSlug      = orgSlugGenerator.normal.generate
      val client       = clientGenerator.normal.generate.copy(archivedAt = None, organizationId = organization.id)

      when(organizationRepository.find(any)).thenReturn(None.asDBIO)

      sut.delete(authAdmin, orgSlug, client.id).asserting(_ shouldEqual ClientDeleteResult.OrganizationNotFound)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
    }

    "return NoAccessToOrganization when user not from organization tries to delete client" in new TestCase {
      val organization = organizationGenerator.normal.generate.copy(archivedAt = None)
      val orgSlug      = orgSlugGenerator.normal.generate
      val client       = clientGenerator.normal.generate.copy(archivedAt = None, organizationId = organization.id)

      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(any)).thenReturn(None.asDBIO)

      sut.delete(authUser, orgSlug, client.id).asserting(_ shouldEqual ClientDeleteResult.NoAccessToOrganization)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
      verify(organizationUserRepository).find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id)))
    }

    "return InsufficientRole when user, with access to organization and insufficient roles, tries to delete client" in new TestCase {
      val organization = organizationGenerator.normal.generate.copy(archivedAt = None)
      val orgSlug      = orgSlugGenerator.normal.generate
      val client       = clientGenerator.normal.generate.copy(archivedAt = None, organizationId = organization.id)
      val roles        = Seq(OrganizationUserRole.Employee)
      val orgUser      = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, roles, None)

      when(organizationUserRepository.find(any)).thenReturn(Some(orgUser).asDBIO)
      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)

      sut.delete(authUser, orgSlug, client.id).asserting(_ shouldEqual ClientDeleteResult.InsufficientRole)

      verify(organizationUserRepository).find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id)))
      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
    }

    "return ClientNotFound when user with access to organization tries to delete not existing client" in new TestCase {
      val organization = organizationGenerator.normal.generate.copy(archivedAt = None)
      val orgSlug      = orgSlugGenerator.normal.generate
      val client       = clientGenerator.normal.generate.copy(archivedAt = None, organizationId = organization.id)
      val roles        = Seq(OrganizationUserRole.Owner)
      val orgUser      = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, roles, None)

      when(organizationUserRepository.find(any)).thenReturn(Some(orgUser).asDBIO)
      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(clientRepository.lockForMutation(any)).thenReturn(().asDBIO)
      when(projectRepository.lockOrdered(any)).thenReturn(Seq.empty[Project].asDBIO)
      when(clientRepository.find(any)).thenReturn(None.asDBIO)

      sut.delete(authUser, orgSlug, client.id).asserting(_ shouldEqual ClientDeleteResult.ClientNotFound)

      verify(organizationUserRepository).find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id)))
      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
      val ordered = inOrder(clientRepository, projectRepository)
      ordered.verify(clientRepository).lockForMutation(client.id)
      ordered
        .verify(projectRepository)
        .lockOrdered(ProjectFilters(organizationIds = Some(Seq(organization.id)), clientIds = Some(Seq(client.id))))
      ordered
        .verify(clientRepository)
        .find(ClientFilters(organizationIds = Some(Seq(organization.id)), id = Some(client.id), forUpdate = true))
    }

    "return ClientNotFound when admin tries to delete not existing client" in new TestCase {
      val organization = organizationGenerator.normal.generate.copy(archivedAt = None)
      val orgSlug      = orgSlugGenerator.normal.generate
      val client       = clientGenerator.normal.generate.copy(archivedAt = None, organizationId = organization.id)

      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(clientRepository.lockForMutation(any)).thenReturn(().asDBIO)
      when(projectRepository.lockOrdered(any)).thenReturn(Seq.empty[Project].asDBIO)
      when(clientRepository.find(any)).thenReturn(None.asDBIO)

      sut.delete(authAdmin, orgSlug, client.id).asserting(_ shouldEqual ClientDeleteResult.ClientNotFound)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
      val ordered = inOrder(clientRepository, projectRepository)
      ordered.verify(clientRepository).lockForMutation(client.id)
      ordered
        .verify(projectRepository)
        .lockOrdered(ProjectFilters(organizationIds = Some(Seq(organization.id)), clientIds = Some(Seq(client.id))))
      ordered
        .verify(clientRepository)
        .find(ClientFilters(organizationIds = Some(Seq(organization.id)), id = Some(client.id), forUpdate = true))
    }

    "return Deleted for user with access to organization with existing client" in new TestCase {
      val organization = organizationGenerator.normal.generate.copy(archivedAt = None)
      val orgSlug      = orgSlugGenerator.normal.generate
      val client       = clientGenerator.normal.generate.copy(archivedAt = None, archivedBy = None, organizationId = organization.id)
      val roles        = Seq(OrganizationUserRole.Owner)
      val projects     = Seq(projectGenerator.normal.generate, projectGenerator.normal.generate)
      val projectUsers = Seq(projectUserGenerator.normal.generate, projectUserGenerator.normal.generate)
      val orgUser      = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, roles, None)

      when(organizationUserRepository.find(any)).thenReturn(Some(orgUser).asDBIO)
      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(clientRepository.lockForMutation(any)).thenReturn(().asDBIO)
      when(clientRepository.find(any)).thenReturn(Some(client).asDBIO)
      when(projectRepository.lockOrdered(any)).thenReturn(projects.asDBIO)
      when(projectUserRepository.lockOrdered(any)).thenReturn(projectUsers.asDBIO)
      when(clientRepository.update(any)).thenReturn(Some(client).asDBIO)
      when(projectRepository.archive(any, any, any)).thenReturn(2.asDBIO)
      when(projectUserRepository.bulkArchiveAt(any, any)).thenReturn(2.asDBIO)

      sut.delete(authUser, orgSlug, client.id).asserting(_ shouldEqual ClientDeleteResult.Deleted)

      verify(organizationUserRepository).find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id)))
      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
      val ordered = inOrder(clientRepository, projectRepository)
      ordered.verify(clientRepository).lockForMutation(client.id)
      ordered
        .verify(projectRepository)
        .lockOrdered(ProjectFilters(organizationIds = Some(Seq(organization.id)), clientIds = Some(Seq(client.id))))
      ordered
        .verify(clientRepository)
        .find(ClientFilters(organizationIds = Some(Seq(organization.id)), id = Some(client.id), forUpdate = true))
      verify(projectUserRepository).lockOrdered(ProjectUserFilters(projectIds = Some(projects.map(_.id))))
      verify(clientRepository).update(argThat { (clientDeleted: Client) =>
        clientDeleted.archivedAt.isDefined &&
        clientDeleted.archivedBy.isDefined &&
        clientDeleted.copy(archivedAt = None, archivedBy = None) == client
      })
      verify(projectRepository)
        .archive(eqTo(ProjectFilters(organizationIds = Some(Seq(organization.id)), clientIds = Some(Seq(client.id)))), any, eqTo(authUser.id))
      verify(projectUserRepository).bulkArchiveAt(eqTo(projectUsers.map(_.id)), any)
    }

    "return Deleted for admin with existing client" in new TestCase {
      val organization = organizationGenerator.normal.generate.copy(archivedAt = None)
      val orgSlug      = orgSlugGenerator.normal.generate
      val client       = clientGenerator.normal.generate.copy(archivedAt = None, archivedBy = None, organizationId = organization.id)
      val projects     = Seq(projectGenerator.normal.generate, projectGenerator.normal.generate)
      val projectUsers = Seq(projectUserGenerator.normal.generate, projectUserGenerator.normal.generate)

      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(clientRepository.lockForMutation(any)).thenReturn(().asDBIO)
      when(clientRepository.find(any)).thenReturn(Some(client).asDBIO)
      when(projectRepository.lockOrdered(any)).thenReturn(projects.asDBIO)
      when(projectUserRepository.lockOrdered(any)).thenReturn(projectUsers.asDBIO)
      when(clientRepository.update(any)).thenReturn(Some(client).asDBIO)
      when(projectRepository.archive(any, any, any)).thenReturn(2.asDBIO)
      when(projectUserRepository.bulkArchiveAt(any, any)).thenReturn(2.asDBIO)

      sut.delete(authAdmin, orgSlug, client.id).asserting(_ shouldEqual ClientDeleteResult.Deleted)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
      val ordered = inOrder(clientRepository, projectRepository)
      ordered.verify(clientRepository).lockForMutation(client.id)
      ordered
        .verify(projectRepository)
        .lockOrdered(ProjectFilters(organizationIds = Some(Seq(organization.id)), clientIds = Some(Seq(client.id))))
      ordered
        .verify(clientRepository)
        .find(ClientFilters(organizationIds = Some(Seq(organization.id)), id = Some(client.id), forUpdate = true))
      verify(projectUserRepository).lockOrdered(ProjectUserFilters(projectIds = Some(projects.map(_.id))))
      verify(clientRepository).update(argThat { (clientDeleted: Client) =>
        clientDeleted.archivedAt.isDefined &&
        clientDeleted.archivedBy.isDefined &&
        clientDeleted.copy(archivedAt = None, archivedBy = None) == client
      })
      verify(projectRepository)
        .archive(eqTo(ProjectFilters(organizationIds = Some(Seq(organization.id)), clientIds = Some(Seq(client.id)))), any, eqTo(authAdmin.id))
      verify(projectUserRepository).bulkArchiveAt(eqTo(projectUsers.map(_.id)), any)
    }
  }
}
