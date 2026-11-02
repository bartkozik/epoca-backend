package pl.iterators.epoca.project.services

import pl.iterators.epoca.client.domain.Client
import pl.iterators.epoca.client.repositories.ClientRepository
import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationSlug, OrganizationUserId, OrganizationUserRole}
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.project.domain.{Project, ProjectUser}
import pl.iterators.epoca.project.repositories.ProjectRepository.ProjectFilters
import pl.iterators.epoca.project.repositories.ProjectUserRepository.ProjectUserFilters
import pl.iterators.epoca.project.repositories.{ProjectRepository, ProjectUserRepository}
import pl.iterators.epoca.project.services.ProjectDtoService.{ProjectClientDto, ProjectDto, ProjectUserDto}
import pl.iterators.epoca.project.services.ProjectFindService.ProjectFindResult
import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.repositories.UserRepository
import pl.iterators.epoca.utils.ServiceSpec

class ProjectFindServiceSpec extends ServiceSpec {
  lazy val organizationGenerator: AllGenerators[Organization]       = allGenerators[Organization]
  lazy val orgSlugGenerator: AllGenerators[OrganizationSlug]        = allGenerators[OrganizationSlug]
  lazy val projectGenerator: AllGenerators[Project]                 = allGenerators[Project]
  lazy val projectUserGenerator: AllGenerators[ProjectUser]         = allGenerators[ProjectUser]
  lazy val userGenerator: AllGenerators[User]                       = allGenerators[User]
  lazy val clientGenerator: AllGenerators[Client]                   = allGenerators[Client]
  lazy val rolesGenerator: AllGenerators[Seq[OrganizationUserRole]] = allGenerators[Seq[OrganizationUserRole]]

  trait TestCase extends BaseScope {
    val projectRepository: ProjectRepository                   = vmock[ProjectRepository]
    val organizationUserRepository: OrganizationUserRepository = vmock[OrganizationUserRepository]
    val organizationRepository: OrganizationRepository         = vmock[OrganizationRepository]
    val projectUserRepository: ProjectUserRepository           = vmock[ProjectUserRepository]
    val clientRepository: ClientRepository                     = vmock[ClientRepository]
    val userRepository: UserRepository                         = vmock[UserRepository]

    val projectDtoService: ProjectDtoService = vmock[ProjectDtoService]

    val sut: ProjectFindService = wire[ProjectFindService]

    val organization: Organization = organizationGenerator.normal.generate.copy(archivedAt = None)
    val orgSlug                    = orgSlugGenerator.normal.generate
    val client                     = clientGenerator.normal.generate
    val user                       = userGenerator.normal.generate
    val project: Project =
      projectGenerator.normal.generate.copy(
        archivedAt = None,
        organizationId = organization.id,
        createdBy = user.id,
        modifiedBy = user.id,
        clientId = client.id
      )

    val projectUser: ProjectUser = projectUserGenerator.normal.generate.copy(userId = user.id, projectId = project.id)
    val roles                    = rolesGenerator.normal.generate
    val projectDto: ProjectDto =
      ProjectDto.from(project, Some(ProjectClientDto.from(client)), Seq(ProjectUserDto.from(user, projectUser)))
  }

  "find" should {
    "return OrganizationNotFound when admin tries to find projects in non existing organization" in new TestCase {
      when(organizationRepository.find(any)).thenReturn(None.asDBIO)

      sut.find(authAdmin, orgSlug, project.id).asserting(_ shouldEqual ProjectFindResult.OrganizationNotFound)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
    }

    "return NoAccessToOrganization when user not from organization tries to find projects" in new TestCase {
      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(any)).thenReturn(None.asDBIO)

      sut.find(authUser, orgSlug, project.id).asserting(_ shouldEqual ProjectFindResult.NoAccessToOrganization)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
      verify(organizationUserRepository).find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id)))
    }

    "return NoAccessToProject when user tries to access not assigned project" in new TestCase {
      val orgUser = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, Seq(OrganizationUserRole.Employee), None)

      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(any)).thenReturn(Some(orgUser).asDBIO)
      when(projectUserRepository.find(any)).thenReturn(None.asDBIO)

      sut.find(authUser, orgSlug, project.id).asserting(_ shouldEqual ProjectFindResult.NoAccessToProject)

      verify(projectUserRepository).find(ProjectUserFilters(projectIds = Some(Seq(project.id)), userId = Some(authUser.id)))
      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
      verify(organizationUserRepository).find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id)))
    }

    "return ProjectNotFound when project can't be found" in new TestCase {
      val orgUser = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, Seq(OrganizationUserRole.Owner), None)

      when(organizationUserRepository.find(any)).thenReturn(Some(orgUser).asDBIO)
      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(projectRepository.find(any)).thenReturn(None.asDBIO)

      sut.find(authUser, orgSlug, project.id).asserting(_ shouldEqual ProjectFindResult.ProjectNotFound)

      verify(organizationUserRepository).find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id)))
      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
      verify(projectRepository).find(ProjectFilters(id = Some(project.id), organizationIds = Some(Seq(organization.id)), includeArchived = true))
    }

    "return Ok with service data for user assigned to existing organization" in new TestCase {
      val orgUser = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, Seq(OrganizationUserRole.Owner), None)

      when(organizationUserRepository.find(any)).thenReturn(Some(orgUser).asDBIO)
      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(projectRepository.find(any)).thenReturn(Some(project).asDBIO)
      when(projectDtoService.prepareFindDto(any)).thenReturn(projectDto.asDBIO)

      sut.find(authUser, orgSlug, project.id).asserting(_ shouldEqual ProjectFindResult.Ok(projectDto))

      verify(organizationUserRepository).find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id)))
      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
      verify(projectRepository).find(ProjectFilters(id = Some(project.id), organizationIds = Some(Seq(organization.id)), includeArchived = true))
      verify(projectDtoService).prepareFindDto(project)
    }

    "return Ok with service data for admin" in new TestCase {
      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(projectRepository.find(any)).thenReturn(Some(project).asDBIO)
      when(projectDtoService.prepareFindDto(any)).thenReturn(projectDto.asDBIO)

      sut.find(authAdmin, orgSlug, project.id).asserting(_ shouldEqual ProjectFindResult.Ok(projectDto))

      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
      verify(projectRepository).find(ProjectFilters(id = Some(project.id), organizationIds = Some(Seq(organization.id)), includeArchived = true))
      verify(projectDtoService).prepareFindDto(project)
    }
    "return Ok with service data for regular user" in new TestCase {
      val orgUser = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, Seq(OrganizationUserRole.Employee), None)

      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(any)).thenReturn(Some(orgUser).asDBIO)
      when(projectRepository.find(any)).thenReturn(Some(project).asDBIO)
      when(projectDtoService.prepareFindDto(any)).thenReturn(projectDto.asDBIO)
      when(projectUserRepository.find(any)).thenReturn(Some(projectUser).asDBIO)

      sut.find(authUser, orgSlug, project.id).asserting(_ shouldEqual ProjectFindResult.Ok(projectDto))

      verify(projectUserRepository).find(ProjectUserFilters(projectIds = Some(Seq(project.id)), userId = Some(authUser.id)))
      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
      verify(projectRepository).find(ProjectFilters(id = Some(project.id), organizationIds = Some(Seq(organization.id)), includeArchived = true))
      verify(organizationUserRepository).find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id)))
      verify(projectDtoService).prepareFindDto(project)
    }
  }
}
