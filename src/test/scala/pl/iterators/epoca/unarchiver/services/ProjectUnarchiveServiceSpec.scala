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
import pl.iterators.epoca.project.domain.ProjectDomain.ProjectModifiedAt
import pl.iterators.epoca.project.domain.{Project, ProjectUser}
import pl.iterators.epoca.project.repositories.ProjectRepository.ProjectFilters
import pl.iterators.epoca.project.repositories.{ProjectRepository, ProjectUserRepository}
import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.unarchiver.services.ProjectUnarchiveService.ProjectUnarchiveResult
import pl.iterators.epoca.utils.ServiceSpec

import java.time.Instant

class ProjectUnarchiveServiceSpec extends ServiceSpec {
  lazy val organizationGenerator: AllGenerators[Organization] = allGenerators[Organization]
  lazy val orgSlugGenerator: AllGenerators[OrganizationSlug]  = allGenerators[OrganizationSlug]
  lazy val clientGenerator: AllGenerators[Client]             = allGenerators[Client]
  lazy val projectGenerator: AllGenerators[Project]           = allGenerators[Project]
  lazy val projectUserGenerator: AllGenerators[ProjectUser]   = allGenerators[ProjectUser]

  private val pastClientModifiedAt  = ClientModifiedAt(Instant.EPOCH)
  private val pastProjectModifiedAt = ProjectModifiedAt(Instant.EPOCH)

  trait TestCase extends BaseScope {
    val clientRepository: ClientRepository                     = vmock[ClientRepository]
    val organizationUserRepository: OrganizationUserRepository = vmock[OrganizationUserRepository]
    val organizationRepository: OrganizationRepository         = vmock[OrganizationRepository]
    val projectRepository: ProjectRepository                   = vmock[ProjectRepository]
    val projectUserRepository: ProjectUserRepository           = vmock[ProjectUserRepository]

    val sut: ProjectUnarchiveService = wire[ProjectUnarchiveService]

  }

  "unarchive" should {
    "return OrganizationNotFound when admin tries to unarchive project in non existing organization" in new TestCase {
      val organization = organizationGenerator.normal.generate.copy(archivedAt = None)
      val orgSlug      = orgSlugGenerator.normal.generate
      val project      = projectGenerator.normal.generate.copy(organizationId = organization.id)

      when(organizationRepository.find(any)).thenReturn(None.asDBIO)

      sut.unarchive(authAdmin, orgSlug, project.id).asserting(_ shouldEqual ProjectUnarchiveResult.OrganizationNotFound)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
    }

    "return NoAccessToOrganization when user not from organization tries to unarchive project" in new TestCase {
      val organization = organizationGenerator.normal.generate.copy(archivedAt = None)
      val orgSlug      = orgSlugGenerator.normal.generate
      val project      = projectGenerator.normal.generate.copy(organizationId = organization.id)

      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(any)).thenReturn(None.asDBIO)

      sut.unarchive(authUser, orgSlug, project.id).asserting(_ shouldEqual ProjectUnarchiveResult.NoAccessToOrganization)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
      verify(organizationUserRepository).find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id)))
    }

    "return InsufficientRole when user, with access to organization and insufficient roles, tries to unarchive project" in new TestCase {
      val organization = organizationGenerator.normal.generate.copy(archivedAt = None)
      val orgSlug      = orgSlugGenerator.normal.generate
      val project      = projectGenerator.normal.generate.copy(organizationId = organization.id)
      val roles        = Seq(OrganizationUserRole.Employee)
      val orgUser      = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, roles, None)

      when(organizationUserRepository.find(any)).thenReturn(Some(orgUser).asDBIO)
      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)

      sut.unarchive(authUser, orgSlug, project.id).asserting(_ shouldEqual ProjectUnarchiveResult.InsufficientRole)

      verify(organizationUserRepository).find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id)))
      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
    }

    "return ProjectNotFound if project doesn't exist" in new TestCase {
      val organization = organizationGenerator.normal.generate.copy(archivedAt = None)
      val orgSlug      = orgSlugGenerator.normal.generate
      val project      = projectGenerator.maximal.generate.copy(organizationId = organization.id)
      val roles        = Seq(OrganizationUserRole.Manager)
      val orgUser      = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, roles, None)

      when(organizationUserRepository.find(any)).thenReturn(Some(orgUser).asDBIO)
      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(projectRepository.find(any)).thenReturn(None.asDBIO)

      sut.unarchive(authUser, orgSlug, project.id).asserting(_ shouldEqual ProjectUnarchiveResult.ProjectNotFound)

      verify(organizationUserRepository).find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id)))
      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
      verify(projectRepository).find(
        ProjectFilters(id = Some(project.id), organizationIds = Some(Seq(organization.id)), forUpdate = true, includeArchived = true)
      )
    }

    "return ClientNotFound if client doesn't exist" in new TestCase {
      val organization = organizationGenerator.normal.generate.copy(archivedAt = None)
      val orgSlug      = orgSlugGenerator.normal.generate
      val client       = clientGenerator.normal.generate
      val project      = projectGenerator.maximal.generate.copy(organizationId = organization.id, clientId = client.id)
      val roles        = Seq(OrganizationUserRole.Manager)
      val orgUser      = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, roles, None)

      when(organizationUserRepository.find(any)).thenReturn(Some(orgUser).asDBIO)
      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(clientRepository.find(any)).thenReturn(None.asDBIO)
      when(projectRepository.find(any)).thenReturn(Some(project).asDBIO)

      sut.unarchive(authUser, orgSlug, project.id).asserting(_ shouldEqual ProjectUnarchiveResult.ClientNotFound)

      verify(organizationUserRepository).find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id)))
      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
      verify(clientRepository).find(
        ClientFilters(organizationIds = Some(Seq(organization.id)), id = Some(client.id), forUpdate = true, includeArchived = true)
      )
      verify(projectRepository).find(
        ProjectFilters(id = Some(project.id), organizationIds = Some(Seq(organization.id)), forUpdate = true, includeArchived = true)
      )
    }

    "return ClientNameNotUnique if unarchived client name is taken" in new TestCase {
      val organization = organizationGenerator.normal.generate.copy(archivedAt = None)
      val orgSlug      = orgSlugGenerator.normal.generate
      val client       = clientGenerator.normal.generate.copy(archivedAt = None)
      val project      = projectGenerator.maximal.generate.copy(organizationId = organization.id, clientId = client.id)
      val roles        = Seq(OrganizationUserRole.Manager)
      val orgUser      = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, roles, None)

      val projectUnarchivedFilter =
        ProjectFilters(id = Some(project.id), organizationIds = Some(Seq(organization.id)), forUpdate = true, includeArchived = true)
      val clientUnarchivedFilter =
        ClientFilters(organizationIds = Some(Seq(organization.id)), id = Some(client.id), forUpdate = true, includeArchived = true)
      val projectUniqueNameFilter = ProjectFilters(name = Some(project.name), organizationIds = Some(Seq(organization.id)))
      val clientUniqueNameFilter  = ClientFilters(name = Some(client.name), organizationIds = Some(Seq(organization.id)))

      when(organizationUserRepository.find(any)).thenReturn(Some(orgUser).asDBIO)
      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(clientRepository.find(clientUnarchivedFilter)).thenReturn(Some(client).asDBIO)
      when(projectRepository.find(projectUnarchivedFilter)).thenReturn(Some(project).asDBIO)
      when(clientRepository.find(clientUniqueNameFilter)).thenReturn(Some(client).asDBIO)
      when(projectRepository.find(projectUniqueNameFilter)).thenReturn(None.asDBIO)

      sut.unarchive(authUser, orgSlug, project.id).asserting(_ shouldEqual ProjectUnarchiveResult.ClientNameNotUnique)

      verify(organizationUserRepository).find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id)))
      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
      verify(clientRepository).find(clientUnarchivedFilter)
      verify(projectRepository).find(projectUnarchivedFilter)
      verify(clientRepository).find(clientUniqueNameFilter)
      verify(projectRepository).find(projectUniqueNameFilter)
    }

    "return ProjectNameNotUnique if unarchived project name is taken" in new TestCase {
      val organization = organizationGenerator.normal.generate.copy(archivedAt = None)
      val orgSlug      = orgSlugGenerator.normal.generate
      val client       = clientGenerator.normal.generate.copy(archivedAt = None)
      val project      = projectGenerator.maximal.generate.copy(organizationId = organization.id, clientId = client.id)
      val roles        = Seq(OrganizationUserRole.Manager)
      val orgUser      = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, roles, None)

      val projectUnarchivedFilter =
        ProjectFilters(id = Some(project.id), organizationIds = Some(Seq(organization.id)), forUpdate = true, includeArchived = true)
      val clientUnarchivedFilter =
        ClientFilters(organizationIds = Some(Seq(organization.id)), id = Some(client.id), forUpdate = true, includeArchived = true)
      val projectUniqueNameFilter = ProjectFilters(name = Some(project.name), organizationIds = Some(Seq(organization.id)))

      when(organizationUserRepository.find(any)).thenReturn(Some(orgUser).asDBIO)
      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(clientRepository.find(clientUnarchivedFilter)).thenReturn(Some(client).asDBIO)
      when(projectRepository.find(projectUnarchivedFilter)).thenReturn(Some(project).asDBIO)
      when(projectRepository.find(projectUniqueNameFilter)).thenReturn(Some(project).asDBIO)

      sut.unarchive(authUser, orgSlug, project.id).asserting(_ shouldEqual ProjectUnarchiveResult.ProjectNameNotUnique)

      verify(organizationUserRepository).find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id)))
      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
      verify(clientRepository).find(clientUnarchivedFilter)
      verify(projectRepository).find(projectUnarchivedFilter)
      verify(projectRepository).find(projectUniqueNameFilter)
    }

    "return Unarchived for user with access to organization with unarchived project" in new TestCase {
      val organization = organizationGenerator.normal.generate.copy(archivedAt = None)
      val orgSlug      = orgSlugGenerator.normal.generate
      val client       = clientGenerator.normal.generate.copy(archivedAt = None)
      val project = projectGenerator.maximal.generate.copy(organizationId = organization.id, clientId = client.id, modifiedAt = pastProjectModifiedAt)
      val projectUpdated = project.copy(archivedAt = None, archivedBy = None)
      val roles          = Seq(OrganizationUserRole.Manager)
      val orgUser        = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, roles, None)

      val projectUnarchivedFilter =
        ProjectFilters(id = Some(project.id), organizationIds = Some(Seq(organization.id)), forUpdate = true, includeArchived = true)
      val clientUnarchivedFilter =
        ClientFilters(organizationIds = Some(Seq(organization.id)), id = Some(client.id), forUpdate = true, includeArchived = true)
      val projectUniqueNameFilter = ProjectFilters(name = Some(project.name), organizationIds = Some(Seq(organization.id)))
      val clientUniqueNameFilter  = ClientFilters(name = Some(client.name), organizationIds = Some(Seq(organization.id)))

      when(organizationUserRepository.find(any)).thenReturn(Some(orgUser).asDBIO)
      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(clientRepository.find(clientUnarchivedFilter)).thenReturn(Some(client).asDBIO)
      when(projectRepository.find(projectUnarchivedFilter)).thenReturn(Some(project).asDBIO)
      when(clientRepository.find(clientUniqueNameFilter)).thenReturn(None.asDBIO)
      when(projectRepository.find(projectUniqueNameFilter)).thenReturn(None.asDBIO)
      when(projectRepository.update(any)).thenReturn(Some(projectUpdated).asDBIO)

      sut.unarchive(authUser, orgSlug, project.id).asserting(_ shouldEqual ProjectUnarchiveResult.Unarchived(projectUpdated))

      verify(organizationUserRepository).find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id)))
      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
      verify(clientRepository).find(clientUnarchivedFilter)
      verify(projectRepository).find(projectUnarchivedFilter)
      verify(clientRepository).find(clientUniqueNameFilter)
      verify(projectRepository).find(projectUniqueNameFilter)
      verify(projectRepository).update(argThat { (projectUpdated: Project) =>
        projectUpdated.archivedAt.isEmpty &&
        projectUpdated.archivedBy.isEmpty &&
        projectUpdated.modifiedAt.isAfter(pastProjectModifiedAt) &&
        projectUpdated.modifiedBy == authUser.id &&
        projectUpdated.copy(
          modifiedAt = project.modifiedAt,
          modifiedBy = project.modifiedBy,
          archivedAt = project.archivedAt,
          archivedBy = project.archivedBy
        ) == project
      })
    }
    "return Unarchived for admin with unarchived project" in new TestCase {
      val organization = organizationGenerator.normal.generate.copy(archivedAt = None)
      val orgSlug      = orgSlugGenerator.normal.generate
      val client       = clientGenerator.normal.generate.copy(archivedAt = None)
      val project = projectGenerator.maximal.generate.copy(organizationId = organization.id, clientId = client.id, modifiedAt = pastProjectModifiedAt)
      val projectUpdated = project.copy(archivedAt = None, archivedBy = None)

      val projectUnarchivedFilter =
        ProjectFilters(id = Some(project.id), organizationIds = Some(Seq(organization.id)), forUpdate = true, includeArchived = true)
      val clientUnarchivedFilter =
        ClientFilters(organizationIds = Some(Seq(organization.id)), id = Some(client.id), forUpdate = true, includeArchived = true)
      val projectUniqueNameFilter = ProjectFilters(name = Some(project.name), organizationIds = Some(Seq(organization.id)))
      val clientUniqueNameFilter  = ClientFilters(name = Some(client.name), organizationIds = Some(Seq(organization.id)))

      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(clientRepository.find(clientUnarchivedFilter)).thenReturn(Some(client).asDBIO)
      when(projectRepository.find(projectUnarchivedFilter)).thenReturn(Some(project).asDBIO)
      when(clientRepository.find(clientUniqueNameFilter)).thenReturn(None.asDBIO)
      when(projectRepository.find(projectUniqueNameFilter)).thenReturn(None.asDBIO)
      when(projectRepository.update(any)).thenReturn(Some(projectUpdated).asDBIO)

      sut.unarchive(authAdmin, orgSlug, project.id).asserting(_ shouldEqual ProjectUnarchiveResult.Unarchived(projectUpdated))

      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
      verify(clientRepository).find(clientUnarchivedFilter)
      verify(projectRepository).find(projectUnarchivedFilter)
      verify(clientRepository).find(clientUniqueNameFilter)
      verify(projectRepository).find(projectUniqueNameFilter)
      verify(projectRepository).update(argThat { (projectUpdated: Project) =>
        projectUpdated.archivedAt.isEmpty &&
        projectUpdated.archivedBy.isEmpty &&
        projectUpdated.modifiedAt.isAfter(pastProjectModifiedAt) &&
        projectUpdated.modifiedBy == authAdmin.id &&
        projectUpdated.copy(
          modifiedAt = project.modifiedAt,
          modifiedBy = project.modifiedBy,
          archivedAt = project.archivedAt,
          archivedBy = project.archivedBy
        ) == project
      })
    }

    "unarchive client if related client is archived" in new TestCase {
      val organization  = organizationGenerator.normal.generate.copy(archivedAt = None)
      val orgSlug       = orgSlugGenerator.normal.generate
      val client        = clientGenerator.maximal.generate.copy(modifiedAt = pastClientModifiedAt)
      val clientUpdated = client.copy(archivedAt = None, archivedBy = None)
      val project = projectGenerator.maximal.generate.copy(organizationId = organization.id, clientId = client.id, modifiedAt = pastProjectModifiedAt)
      val projectUpdated = project.copy(archivedAt = None, archivedBy = None)
      val roles          = Seq(OrganizationUserRole.Manager)
      val orgUser        = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, roles, None)

      val projectUnarchivedFilter =
        ProjectFilters(id = Some(project.id), organizationIds = Some(Seq(organization.id)), forUpdate = true, includeArchived = true)
      val clientUnarchivedFilter =
        ClientFilters(organizationIds = Some(Seq(organization.id)), id = Some(client.id), forUpdate = true, includeArchived = true)
      val projectUniqueNameFilter = ProjectFilters(name = Some(project.name), organizationIds = Some(Seq(organization.id)))
      val clientUniqueNameFilter  = ClientFilters(name = Some(client.name), organizationIds = Some(Seq(organization.id)))

      when(organizationUserRepository.find(any)).thenReturn(Some(orgUser).asDBIO)
      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(clientRepository.find(clientUnarchivedFilter)).thenReturn(Some(client).asDBIO)
      when(projectRepository.find(projectUnarchivedFilter)).thenReturn(Some(project).asDBIO)
      when(clientRepository.find(clientUniqueNameFilter)).thenReturn(None.asDBIO)
      when(projectRepository.find(projectUniqueNameFilter)).thenReturn(None.asDBIO)
      when(projectRepository.update(any)).thenReturn(Some(projectUpdated).asDBIO)
      when(clientRepository.update(any)).thenReturn(Some(clientUpdated).asDBIO)

      sut.unarchive(authUser, orgSlug, project.id).asserting(_ shouldEqual ProjectUnarchiveResult.Unarchived(projectUpdated))

      verify(organizationUserRepository).find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id)))
      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
      verify(clientRepository).find(clientUnarchivedFilter)
      verify(projectRepository).find(projectUnarchivedFilter)
      verify(clientRepository).find(clientUniqueNameFilter)
      verify(projectRepository).find(projectUniqueNameFilter)
      verify(projectRepository).update(argThat { (projectUpdated: Project) =>
        projectUpdated.archivedAt.isEmpty &&
        projectUpdated.archivedBy.isEmpty &&
        projectUpdated.modifiedAt.isAfter(pastProjectModifiedAt) &&
        projectUpdated.modifiedBy == authUser.id &&
        projectUpdated.copy(
          modifiedAt = project.modifiedAt,
          modifiedBy = project.modifiedBy,
          archivedAt = project.archivedAt,
          archivedBy = project.archivedBy
        ) == project
      })
      verify(clientRepository).update(argThat { (clientUpdated: Client) =>
        clientUpdated.archivedAt.isEmpty &&
        clientUpdated.archivedBy.isEmpty &&
        clientUpdated.modifiedAt.isAfter(pastClientModifiedAt) &&
        clientUpdated.modifiedBy == authUser.id &&
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
