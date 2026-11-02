package pl.iterators.epoca.project.services

import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationSlug, OrganizationUserId, OrganizationUserRole}
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.project.domain.ProjectDomain.{ProjectArchivedAt, ProjectModifiedAt}
import pl.iterators.epoca.project.domain.{Project, ProjectUser}
import pl.iterators.epoca.project.repositories.ProjectRepository.ProjectFilters
import pl.iterators.epoca.project.repositories.ProjectUserRepository.ProjectUserFilters
import pl.iterators.epoca.project.repositories.{ProjectRepository, ProjectUserRepository}
import pl.iterators.epoca.project.services.ProjectDeleteService.ProjectDeleteResult
import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.utils.ServiceSpec

import java.time.Instant

class ProjectDeleteServiceSpec extends ServiceSpec {
  private val pastModifiedAt = ProjectModifiedAt(Instant.EPOCH)

  lazy val organizationGenerator: AllGenerators[Organization]       = allGenerators[Organization]
  lazy val orgSlugGenerator: AllGenerators[OrganizationSlug]        = allGenerators[OrganizationSlug]
  lazy val projectGenerator: AllGenerators[Project]                 = allGenerators[Project]
  lazy val projectUserGenerator: AllGenerators[ProjectUser]         = allGenerators[ProjectUser]
  lazy val rolesGenerator: AllGenerators[Seq[OrganizationUserRole]] = allGenerators[Seq[OrganizationUserRole]]

  trait TestCase extends BaseScope {
    val projectRepository: ProjectRepository                   = vmock[ProjectRepository]
    val projectUserRepository: ProjectUserRepository           = vmock[ProjectUserRepository]
    val organizationUserRepository: OrganizationUserRepository = vmock[OrganizationUserRepository]
    val organizationRepository: OrganizationRepository         = vmock[OrganizationRepository]

    val sut: ProjectDeleteService = wire[ProjectDeleteService]

  }

  "delete" should {
    "return OrganizationNotFound when admin tries to delete project in non existing organization" in new TestCase {
      val organization = organizationGenerator.normal.generate.copy(archivedAt = None)
      val orgSlug      = orgSlugGenerator.normal.generate
      val project      = projectGenerator.normal.generate.copy(archivedAt = None, organizationId = organization.id)

      when(organizationRepository.find(any)).thenReturn(None.asDBIO)

      sut.delete(authAdmin, orgSlug, project.id).asserting(_ shouldEqual ProjectDeleteResult.OrganizationNotFound)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
    }

    "return NoAccessToOrganization when user not from organization tries to delete project" in new TestCase {
      val organization = organizationGenerator.normal.generate.copy(archivedAt = None)
      val orgSlug      = orgSlugGenerator.normal.generate
      val project      = projectGenerator.normal.generate.copy(archivedAt = None, organizationId = organization.id)

      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(any)).thenReturn(None.asDBIO)

      sut.delete(authUser, orgSlug, project.id).asserting(_ shouldEqual ProjectDeleteResult.NoAccessToOrganization)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
      verify(organizationUserRepository).find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id)))
    }

    "return NoAccessToOrganization when users role in organization is insufficient" in new TestCase {
      val organization = organizationGenerator.normal.generate.copy(archivedAt = None)
      val orgSlug      = orgSlugGenerator.normal.generate
      val project      = projectGenerator.normal.generate.copy(archivedAt = None, organizationId = organization.id)
      val roles        = Seq(OrganizationUserRole.Employee)
      val orgUser      = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, roles, None)

      when(organizationUserRepository.find(any)).thenReturn(Some(orgUser).asDBIO)
      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)

      sut.delete(authUser, orgSlug, project.id).asserting(_ shouldEqual ProjectDeleteResult.NoAccessToOrganization)

      verify(organizationUserRepository).find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id)))
      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
    }

    "return ProjectNotFound when user with access to organization tries to delete not existing project" in new TestCase {
      val organization = organizationGenerator.normal.generate.copy(archivedAt = None)
      val orgSlug      = orgSlugGenerator.normal.generate
      val project      = projectGenerator.normal.generate.copy(archivedAt = None, organizationId = organization.id)
      val roles        = Seq(OrganizationUserRole.Owner)
      val orgUser      = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, roles, None)

      when(organizationUserRepository.find(any)).thenReturn(Some(orgUser).asDBIO)
      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(projectRepository.find(any)).thenReturn(None.asDBIO)

      sut.delete(authUser, orgSlug, project.id).asserting(_ shouldEqual ProjectDeleteResult.ProjectNotFound)

      verify(organizationUserRepository).find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id)))
      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
      verify(projectRepository).find(ProjectFilters(organizationIds = Some(Seq(organization.id)), id = Some(project.id), forUpdate = true))
    }

    "return ProjectNotFound when admin tries to delete not existing project" in new TestCase {
      val organization = organizationGenerator.normal.generate.copy(archivedAt = None)
      val orgSlug      = orgSlugGenerator.normal.generate
      val project      = projectGenerator.normal.generate.copy(archivedAt = None, organizationId = organization.id)

      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(projectRepository.find(any)).thenReturn(None.asDBIO)

      sut.delete(authAdmin, orgSlug, project.id).asserting(_ shouldEqual ProjectDeleteResult.ProjectNotFound)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
      verify(projectRepository).find(ProjectFilters(organizationIds = Some(Seq(organization.id)), id = Some(project.id), forUpdate = true))
    }

    "return Deleted for user with access to organization with existing project" in new TestCase {
      val organization = organizationGenerator.normal.generate.copy(archivedAt = None)
      val orgSlug      = orgSlugGenerator.normal.generate
      val project =
        projectGenerator.normal.generate.copy(archivedAt = None, archivedBy = None, organizationId = organization.id, modifiedAt = pastModifiedAt)
      val roles   = Seq(OrganizationUserRole.Manager)
      val orgUser = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, roles, None)

      when(organizationUserRepository.find(any)).thenReturn(Some(orgUser).asDBIO)
      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(projectRepository.find(any)).thenReturn(Some(project).asDBIO)
      when(projectRepository.update(any)).thenReturn(Some(project).asDBIO)
      when(projectUserRepository.list(any)).thenReturn(Seq().asDBIO)
      when(projectUserRepository.bulkArchiveAt(any, any)).thenReturn(3.asDBIO)

      sut.delete(authUser, orgSlug, project.id).asserting(_ shouldEqual ProjectDeleteResult.Deleted)

      verify(organizationUserRepository).find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id)))
      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
      verify(projectRepository).find(ProjectFilters(organizationIds = Some(Seq(organization.id)), id = Some(project.id), forUpdate = true))
      verify(projectRepository).update(argThat { (projectDeleted: Project) =>
        projectDeleted.modifiedAt.isAfter(pastModifiedAt) &&
        projectDeleted.modifiedBy == authUser.id &&
        projectDeleted.archivedAt.contains(ProjectArchivedAt(projectDeleted.modifiedAt)) &&
        projectDeleted.archivedBy.contains(authUser.id) &&
        projectDeleted.copy(modifiedAt = project.modifiedAt, modifiedBy = project.modifiedBy, archivedAt = None, archivedBy = None) == project
      })
      verify(projectUserRepository).list(ProjectUserFilters(projectIds = Some(Seq(project.id))))
      verify(projectUserRepository).bulkArchiveAt(any, any)
    }

    "return Deleted for admin with existing project" in new TestCase {
      val organization = organizationGenerator.normal.generate.copy(archivedAt = None)
      val orgSlug      = orgSlugGenerator.normal.generate
      val project =
        projectGenerator.normal.generate.copy(archivedAt = None, archivedBy = None, organizationId = organization.id, modifiedAt = pastModifiedAt)

      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(projectRepository.find(any)).thenReturn(Some(project).asDBIO)
      when(projectRepository.update(any)).thenReturn(Some(project).asDBIO)
      when(projectUserRepository.list(any)).thenReturn(Seq().asDBIO)
      when(projectUserRepository.bulkArchiveAt(any, any)).thenReturn(3.asDBIO)

      sut.delete(authAdmin, orgSlug, project.id).asserting(_ shouldEqual ProjectDeleteResult.Deleted)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
      verify(projectRepository).find(ProjectFilters(organizationIds = Some(Seq(organization.id)), id = Some(project.id), forUpdate = true))
      verify(projectRepository).update(argThat { (projectDeleted: Project) =>
        projectDeleted.modifiedAt.isAfter(pastModifiedAt) &&
        projectDeleted.modifiedBy == authAdmin.id &&
        projectDeleted.archivedAt.contains(ProjectArchivedAt(projectDeleted.modifiedAt)) &&
        projectDeleted.archivedBy.contains(authAdmin.id) &&
        projectDeleted.copy(modifiedAt = project.modifiedAt, modifiedBy = project.modifiedBy, archivedAt = None, archivedBy = None) == project
      })
      verify(projectUserRepository).list(ProjectUserFilters(projectIds = Some(Seq(project.id))))
      verify(projectUserRepository).bulkArchiveAt(any, any)
    }
  }
}
