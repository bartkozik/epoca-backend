package pl.iterators.epoca.project.services

import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationUserId
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.project.domain.ProjectDomain.ProjectRole
import pl.iterators.epoca.project.domain.{Project, ProjectUser}
import pl.iterators.epoca.project.repositories.ProjectRepository.ProjectFilters
import pl.iterators.epoca.project.repositories.ProjectUserRepository.ProjectUserFilters
import pl.iterators.epoca.project.repositories.{ProjectRepository, ProjectUserRepository}
import pl.iterators.epoca.project.services.ProjectDeassignService.ProjectDeassignResult._
import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.repositories.UserRepository
import pl.iterators.epoca.user.repositories.UserRepository.UserFilters
import pl.iterators.epoca.utils.AuthContextProvider.authContextGenerator
import pl.iterators.epoca.utils.ServiceSpec

class ProjectDeassignServiceSpec extends ServiceSpec {
  lazy val projectGenerator: AllGenerators[Project]           = allGenerators[Project]
  lazy val userGenerator: AllGenerators[User]                 = allGenerators[User]
  lazy val projectUserGenerator: AllGenerators[ProjectUser]   = allGenerators[ProjectUser]
  lazy val organizationGenerator: AllGenerators[Organization] = allGenerators[Organization]

  trait TestCase extends BaseScope {
    val userRepository: UserRepository                         = vmock[UserRepository]
    val projectRepository: ProjectRepository                   = vmock[ProjectRepository]
    val projectUserRepository: ProjectUserRepository           = vmock[ProjectUserRepository]
    val organizationRepository: OrganizationRepository         = vmock[OrganizationRepository]
    val organizationUserRepository: OrganizationUserRepository = vmock[OrganizationUserRepository]

    val sut: ProjectDeassignService = wire[ProjectDeassignService]

  }

  "deassign" should {
    "return OrganizationNotFound when admin tries to update project in non existing organization" in new TestCase {
      val authCreator                = authContextGenerator.normal.generate
      val user                       = userGenerator.normal.generate
      val project                    = projectGenerator.normal.generate.copy(archivedAt = None, createdBy = authCreator.id)
      val organization: Organization = organizationGenerator.normal.generate.copy(archivedAt = None)

      when(organizationRepository.find(any)).thenReturn(None.asDBIO)

      sut.deassign(authAdmin, organization.slug, project.id, user.id).asserting(_ shouldEqual OrganizationNotFound)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(organization.slug)))
    }

    "return NoAccessToOrganization when user not from organization tries to update project" in new TestCase {
      val authCreator                = authContextGenerator.normal.generate
      val user                       = userGenerator.normal.generate
      val project                    = projectGenerator.normal.generate.copy(archivedAt = None, createdBy = authCreator.id)
      val organization: Organization = organizationGenerator.normal.generate.copy(archivedAt = None)

      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(any)).thenReturn(None.asDBIO)

      sut.deassign(authUser, organization.slug, project.id, user.id).asserting(_ shouldEqual NoAccessToOrganization)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(organization.slug)))
      verify(organizationUserRepository).find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id)))
    }

    "return ProjectNotFound when admin tries to deassign user from non existing project" in new TestCase {
      val authCreator  = authContextGenerator.normal.generate
      val project      = projectGenerator.normal.generate.copy(archivedAt = None, createdBy = authCreator.id)
      val user         = userGenerator.normal.generate
      val organization = organizationGenerator.normal.generate

      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(projectRepository.find(any)).thenReturn(None.asDBIO)

      sut.deassign(authAdmin, organization.slug, project.id, user.id).asserting(_ shouldEqual ProjectNotFound)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(organization.slug)))
      verify(projectRepository).find(ProjectFilters(id = Some(project.id)))
    }

    "return OperationNotPermitted when user tries to deassign user from project" in new TestCase {
      val authCreator  = authContextGenerator.normal.generate
      val project      = projectGenerator.normal.generate.copy(archivedAt = None, createdBy = authCreator.id)
      val user         = userGenerator.normal.generate
      val organization = organizationGenerator.normal.generate
      val orgUser      = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, Nil, None)

      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(any)).thenReturn(Some(orgUser).asDBIO)
      when(projectRepository.find(any)).thenReturn(Some(project).asDBIO)
      when(projectUserRepository.find(any)).thenReturn(None.asDBIO)

      sut.deassign(authUser, organization.slug, project.id, user.id).asserting(_ shouldEqual OperationNotPermitted)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(organization.slug)))
      verify(organizationUserRepository).find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id)))
      verify(projectRepository).find(ProjectFilters(id = Some(project.id)))
      verify(projectUserRepository)
        .find(ProjectUserFilters(projectIds = Some(Seq(project.id)), userId = Some(authUser.id), role = Some(ProjectRole.Owner)))
    }

    "return UserNotFound when admin tries to deassign user to non existing project" in new TestCase {
      val authCreator  = authContextGenerator.normal.generate
      val project      = projectGenerator.normal.generate.copy(archivedAt = None, createdBy = authCreator.id)
      val user         = userGenerator.normal.generate
      val organization = organizationGenerator.normal.generate

      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(projectRepository.find(any)).thenReturn(Some(project).asDBIO)
      when(userRepository.find(UserFilters(id = Some(user.id)))).thenReturn(None.asDBIO)

      sut.deassign(authAdmin, organization.slug, project.id, user.id).asserting(_ shouldEqual UserNotFound)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(organization.slug)))
      verify(projectRepository).find(ProjectFilters(id = Some(project.id)))
      verify(userRepository).find(UserFilters(id = Some(user.id)))
    }

    "return UserAlreadyUnassigned when admin tries to deassign unassigned user from project" in new TestCase {
      val authCreator  = authContextGenerator.normal.generate
      val project      = projectGenerator.normal.generate.copy(archivedAt = None, createdBy = authCreator.id)
      val user         = userGenerator.normal.generate
      val organization = organizationGenerator.normal.generate

      val projectUserFilters = ProjectUserFilters(projectIds = Some(Seq(project.id)), userId = Some(user.id))

      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(projectRepository.find(any)).thenReturn(Some(project).asDBIO)
      when(userRepository.find(UserFilters(id = Some(user.id)))).thenReturn(Some(user).asDBIO)
      when(projectUserRepository.find(any)).thenReturn(None.asDBIO)

      sut.deassign(authAdmin, organization.slug, project.id, user.id).asserting(_ shouldEqual UserAlreadyUnassigned)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(organization.slug)))
      verify(projectRepository).find(ProjectFilters(id = Some(project.id)))
      verify(userRepository).find(UserFilters(id = Some(user.id)))
      verify(projectUserRepository).find(projectUserFilters)
    }

    "return UserAlreadyUnassigned when project owner tries to deassign unassigned user from project" in new TestCase {
      val project      = projectGenerator.normal.generate.copy(archivedAt = None)
      val user         = userGenerator.normal.generate
      val projectUser  = projectUserGenerator.normal.generate.copy(projectId = project.id, userId = authUser.id, role = ProjectRole.Owner)
      val organization = organizationGenerator.normal.generate
      val orgUser      = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, Nil, None)

      val projectUserFilters = ProjectUserFilters(projectIds = Some(Seq(project.id)), userId = Some(user.id))

      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(any)).thenReturn(Some(orgUser).asDBIO)
      when(projectRepository.find(any)).thenReturn(Some(project).asDBIO)
      when(
        projectUserRepository
          .find(ProjectUserFilters(projectIds = Some(Seq(project.id)), userId = Some(authUser.id), role = Some(ProjectRole.Owner)))
      )
        .thenReturn(Some(projectUser).asDBIO)
      when(userRepository.find(UserFilters(id = Some(user.id)))).thenReturn(Some(user).asDBIO)
      when(projectUserRepository.find(ProjectUserFilters(projectIds = Some(Seq(project.id)), userId = Some(user.id)))).thenReturn(None.asDBIO)

      sut.deassign(authUser, organization.slug, project.id, user.id).asserting(_ shouldEqual UserAlreadyUnassigned)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(organization.slug)))
      verify(organizationUserRepository).find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id)))
      verify(projectRepository).find(ProjectFilters(id = Some(project.id)))
      verify(projectUserRepository).find(
        ProjectUserFilters(projectIds = Some(Seq(project.id)), userId = Some(authUser.id), role = Some(ProjectRole.Owner))
      )
      verify(userRepository).find(UserFilters(id = Some(user.id)))
      verify(projectUserRepository).find(projectUserFilters)
    }

    "return CreatorNotDeassignable when admin tries to deassign creator" in new TestCase {
      val creator        = userGenerator.normal.generate
      val project        = projectGenerator.normal.generate.copy(archivedAt = None, createdBy = creator.id)
      val projectCreator = projectUserGenerator.normal.generate.copy(projectId = project.id, userId = creator.id)
      val organization   = organizationGenerator.normal.generate

      val projectUserFilters = ProjectUserFilters(projectIds = Some(Seq(project.id)), userId = Some(creator.id))

      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(projectRepository.find(any)).thenReturn(Some(project).asDBIO)
      when(userRepository.find(UserFilters(id = Some(creator.id)))).thenReturn(Some(creator).asDBIO)
      when(projectUserRepository.find(any)).thenReturn(Some(projectCreator).asDBIO)

      sut.deassign(authAdmin, organization.slug, project.id, creator.id).asserting(_ shouldEqual CreatorNotDeassignable)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(organization.slug)))
      verify(projectRepository).find(ProjectFilters(id = Some(project.id)))
      verify(userRepository).find(UserFilters(id = Some(creator.id)))
      verify(projectUserRepository).find(projectUserFilters)
    }

    "return Deassigned when admin tries to deassign assigned user from project" in new TestCase {
      val authCreator  = authContextGenerator.normal.generate
      val user         = userGenerator.normal.generate
      val project      = projectGenerator.normal.generate.copy(archivedAt = None, createdBy = authCreator.id)
      val projectUser  = projectUserGenerator.normal.generate.copy(projectId = project.id, userId = user.id, archivedAt = None)
      val organization = organizationGenerator.normal.generate

      val projectUserFilters = ProjectUserFilters(projectIds = Some(Seq(project.id)), userId = Some(user.id))

      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(projectRepository.find(any)).thenReturn(Some(project).asDBIO)
      when(userRepository.find(any)).thenReturn(Some(user).asDBIO)
      when(projectUserRepository.find(any)).thenReturn(Some(projectUser).asDBIO)
      when(projectUserRepository.update(any)).thenReturn(Some(projectUser).asDBIO)

      sut.deassign(authAdmin, organization.slug, project.id, user.id).asserting(_ shouldEqual Deassigned)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(organization.slug)))
      verify(projectRepository).find(ProjectFilters(id = Some(project.id)))
      verify(userRepository).find(UserFilters(id = Some(user.id)))
      verify(projectUserRepository).find(projectUserFilters)
      verify(projectUserRepository).update(argThat { (projectUserDeleted: ProjectUser) =>
        projectUserDeleted.archivedAt.isDefined &&
        projectUserDeleted.copy(archivedAt = None) == projectUser
      })
    }
  }
}
