package pl.iterators.epoca.project.services

import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationUserId, OrganizationUserRole}
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.project.domain.ProjectDomain.ProjectRole
import pl.iterators.epoca.project.domain.{Project, ProjectUser}
import pl.iterators.epoca.project.repositories.ProjectRepository.ProjectFilters
import pl.iterators.epoca.project.repositories.ProjectUserRepository.{ProjectUserFilters, ProjectUserNotPermitted}
import pl.iterators.epoca.project.repositories.{ProjectRepository, ProjectUserRepository}
import pl.iterators.epoca.project.services.ProjectAssignService.ProjectAssignRequest
import pl.iterators.epoca.project.services.ProjectAssignService.ProjectAssignResult._
import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.domain.UserDomain.UserAccountType
import pl.iterators.epoca.user.repositories.UserRepository
import pl.iterators.epoca.user.repositories.UserRepository.UserFilters
import pl.iterators.epoca.utils.AuthContextProvider.authContextGenerator
import pl.iterators.epoca.utils.ServiceSpec
import slick.dbio.DBIO

class ProjectAssignServiceSpec extends ServiceSpec {
  lazy val projectGenerator: AllGenerators[Project]              = allGenerators[Project]
  lazy val requestGenerator: AllGenerators[ProjectAssignRequest] = allGenerators[ProjectAssignRequest]
  lazy val userGenerator: AllGenerators[User]                    = allGenerators[User]
  lazy val projectUserGenerator: AllGenerators[ProjectUser]      = allGenerators[ProjectUser]
  lazy val organizationGenerator: AllGenerators[Organization]    = allGenerators[Organization]

  trait TestCase extends BaseScope {
    val userRepository: UserRepository                         = vmock[UserRepository]
    val projectRepository: ProjectRepository                   = vmock[ProjectRepository]
    val projectUserRepository: ProjectUserRepository           = vmock[ProjectUserRepository]
    val organizationRepository: OrganizationRepository         = vmock[OrganizationRepository]
    val organizationUserRepository: OrganizationUserRepository = vmock[OrganizationUserRepository]

    val sut: ProjectAssignService = wire[ProjectAssignService]
  }

  "assign" should {
    "return OrganizationNotFound when admin tries to update project in non existing organization" in new TestCase {
      val authCreator                = authContextGenerator.normal.generate
      val request                    = requestGenerator.normal.generate
      val user                       = userGenerator.normal.generate
      val project                    = projectGenerator.normal.generate.copy(archivedAt = None, createdBy = authCreator.id)
      val organization: Organization = organizationGenerator.normal.generate.copy(archivedAt = None)

      when(organizationRepository.find(any)).thenReturn(None.asDBIO)

      sut.assign(authAdmin, organization.slug, project.id, user.id, request).asserting(_ shouldEqual OrganizationNotFound)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(organization.slug)))
    }

    "return NoAccessToOrganization when user not from organization tries to update project" in new TestCase {
      val authCreator                = authContextGenerator.normal.generate
      val request                    = requestGenerator.normal.generate
      val user                       = userGenerator.normal.generate
      val project                    = projectGenerator.normal.generate.copy(archivedAt = None, createdBy = authCreator.id)
      val organization: Organization = organizationGenerator.normal.generate.copy(archivedAt = None)

      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(any)).thenReturn(None.asDBIO)

      sut.assign(authUser, organization.slug, project.id, user.id, request).asserting(_ shouldEqual NoAccessToOrganization)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(organization.slug)))
      verify(organizationUserRepository).find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id)))
    }

    "return ProjectNotFound when admin tries to assign user to non existing project" in new TestCase {
      val authCreator  = authContextGenerator.normal.generate
      val request      = requestGenerator.normal.generate
      val user         = userGenerator.normal.generate
      val project      = projectGenerator.normal.generate.copy(archivedAt = None, createdBy = authCreator.id)
      val organization = organizationGenerator.normal.generate

      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(projectRepository.find(any)).thenReturn(None.asDBIO)

      sut.assign(authAdmin, organization.slug, project.id, user.id, request).asserting(_ shouldEqual ProjectNotFound)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(organization.slug)))
      verify(projectRepository).find(ProjectFilters(organizationIds = Some(Seq(organization.id)), id = Some(project.id), forUpdate = true))
    }

    "return OperationNotPermitted when non owner tries to assign user to project" in new TestCase {
      val authCreator  = authContextGenerator.normal.generate
      val project      = projectGenerator.normal.generate.copy(archivedAt = None, createdBy = authCreator.id)
      val user         = userGenerator.normal.generate
      val request      = requestGenerator.normal.generate
      val organization = organizationGenerator.normal.generate
      val orgUser      = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, Seq(OrganizationUserRole.Employee), None)

      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(any)).thenReturn(Some(orgUser).asDBIO)
      when(projectRepository.find(any)).thenReturn(Some(project).asDBIO)
      when(projectUserRepository.find(any)).thenReturn(None.asDBIO)

      sut.assign(authUser, organization.slug, project.id, user.id, request).asserting(_ shouldEqual OperationNotPermitted)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(organization.slug)))
      verify(organizationUserRepository).find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id)))
      verify(projectRepository).find(ProjectFilters(organizationIds = Some(Seq(organization.id)), id = Some(project.id), forUpdate = true))
      verify(projectUserRepository)
        .find(ProjectUserFilters(projectIds = Some(Seq(project.id)), userId = Some(authUser.id), role = Some(ProjectRole.Owner)))
    }

    "return UserNotFound when admin tries to assign user that doesn't exist" in new TestCase {
      val authCreator  = authContextGenerator.normal.generate
      val request      = requestGenerator.normal.generate
      val project      = projectGenerator.normal.generate.copy(archivedAt = None, createdBy = authCreator.id)
      val user         = userGenerator.normal.generate
      val organization = organizationGenerator.normal.generate

      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(projectRepository.find(any)).thenReturn(Some(project).asDBIO)
      when(userRepository.find(UserFilters(id = Some(user.id)))).thenReturn(None.asDBIO)

      sut.assign(authAdmin, organization.slug, project.id, user.id, request).asserting(_ shouldEqual UserNotFound)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(organization.slug)))
      verify(projectRepository).find(ProjectFilters(organizationIds = Some(Seq(organization.id)), id = Some(project.id), forUpdate = true))
      verify(userRepository).find(UserFilters(id = Some(user.id)))
    }

    "return the same UserNotFound without writing when admin targets a service account" in new TestCase {
      val authCreator    = authContextGenerator.normal.generate
      val request        = requestGenerator.normal.generate
      val project        = projectGenerator.normal.generate.copy(archivedAt = None, createdBy = authCreator.id)
      val serviceAccount = userGenerator.normal.generate.copy(accountType = UserAccountType.ServiceAccount)
      val organization   = organizationGenerator.normal.generate
      val membership     = OrganizationUser(OrganizationUserId.random, organization.id, serviceAccount.id, Nil, None)

      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(projectRepository.find(any)).thenReturn(Some(project).asDBIO)
      when(userRepository.find(UserFilters(id = Some(serviceAccount.id)))).thenReturn(Some(serviceAccount).asDBIO)
      when(organizationUserRepository.find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(serviceAccount.id))))
        .thenReturn(Some(membership).asDBIO)
      when(projectUserRepository.find(any)).thenReturn(None.asDBIO)
      when(projectUserRepository.insert(any[ProjectUser])).thenAnswer((projectUser: ProjectUser) => projectUser.asDBIO)

      sut.assign(authAdmin, organization.slug, project.id, serviceAccount.id, request).asserting(_ shouldEqual UserNotFound)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(organization.slug)))
      verify(projectRepository).find(ProjectFilters(organizationIds = Some(Seq(organization.id)), id = Some(project.id), forUpdate = true))
      verify(userRepository).find(UserFilters(id = Some(serviceAccount.id)))
      verify(projectUserRepository, never).insert(any[ProjectUser])
    }

    "map a repository ineligible-user race to the same UserNotFound result" in new TestCase {
      val project      = projectGenerator.normal.generate.copy(archivedAt = None)
      val request      = requestGenerator.normal.generate
      val human        = userGenerator.normal.generate.copy(accountType = UserAccountType.Human, archivedAt = None)
      val organization = organizationGenerator.normal.generate
      val membership   = OrganizationUser(OrganizationUserId.random, organization.id, human.id, Nil, None)

      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(projectRepository.find(any)).thenReturn(Some(project).asDBIO)
      when(userRepository.find(UserFilters(id = Some(human.id)))).thenReturn(Some(human).asDBIO)
      when(organizationUserRepository.find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(human.id))))
        .thenReturn(Some(membership).asDBIO)
      when(projectUserRepository.find(any)).thenReturn(None.asDBIO)
      when(projectUserRepository.insert(any[ProjectUser])).thenReturn(DBIO.failed(ProjectUserNotPermitted))

      sut.assign(authAdmin, organization.slug, project.id, human.id, request).asserting(_ shouldEqual UserNotFound)

      verify(organizationRepository).find(any)
      verify(projectRepository).find(any)
      verify(userRepository).find(UserFilters(id = Some(human.id)))
      verify(organizationUserRepository).find(any)
      verify(projectUserRepository).find(any)
      verify(projectUserRepository).insert(any[ProjectUser])
    }

    "return NotAMember when admin tries to assign user that isn't organization member" in new TestCase {
      val authCreator   = authContextGenerator.normal.generate
      val project       = projectGenerator.normal.generate.copy(archivedAt = None, createdBy = authCreator.id)
      val request       = requestGenerator.normal.generate.copy(role = ProjectRole.Owner)
      val user          = userGenerator.normal.generate
      val organization  = organizationGenerator.normal.generate
      val orgUserFilter = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(user.id))

      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(projectRepository.find(any)).thenReturn(Some(project).asDBIO)
      when(userRepository.find(UserFilters(id = Some(user.id)))).thenReturn(Some(user).asDBIO)
      when(organizationUserRepository.find(orgUserFilter)).thenReturn(None.asDBIO)

      sut.assign(authAdmin, organization.slug, project.id, user.id, request).asserting(_ shouldEqual NotAMember)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(organization.slug)))
      verify(projectRepository).find(ProjectFilters(organizationIds = Some(Seq(organization.id)), id = Some(project.id), forUpdate = true))
      verify(userRepository).find(UserFilters(id = Some(user.id)))
      verify(organizationUserRepository).find(orgUserFilter)
    }

    "return UserAlreadyAssigned when admin tries to assign already assigned user to project with same role" in new TestCase {
      val authCreator = authContextGenerator.normal.generate
      val project     = projectGenerator.normal.generate.copy(archivedAt = None, createdBy = authCreator.id)
      val request     = requestGenerator.normal.generate.copy(role = ProjectRole.Owner)
      val user        = userGenerator.normal.generate
      val projectUser =
        projectUserGenerator.normal.generate.copy(projectId = project.id, userId = user.id, archivedAt = None, role = ProjectRole.Owner)
      val organization  = organizationGenerator.normal.generate
      val orgUserFilter = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(user.id))
      val orgUser       = OrganizationUser(OrganizationUserId.random, organization.id, user.id, Nil, None)

      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(projectRepository.find(any)).thenReturn(Some(project).asDBIO)
      when(userRepository.find(UserFilters(id = Some(user.id)))).thenReturn(Some(user).asDBIO)
      when(projectUserRepository.find(any)).thenReturn(Some(projectUser).asDBIO)
      when(organizationUserRepository.find(orgUserFilter)).thenReturn(Some(orgUser).asDBIO)

      sut.assign(authAdmin, organization.slug, project.id, user.id, request).asserting(_ shouldEqual UserAlreadyAssigned)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(organization.slug)))
      verify(projectRepository).find(ProjectFilters(organizationIds = Some(Seq(organization.id)), id = Some(project.id), forUpdate = true))
      verify(userRepository).find(UserFilters(id = Some(user.id)))
      verify(projectUserRepository).find(ProjectUserFilters(projectIds = Some(Seq(project.id)), userId = Some(user.id), forUpdate = true))
      verify(organizationUserRepository).find(orgUserFilter)
    }

    "return UserAlreadyAssigned when project owner tries to assign already assigned user to project with same role" in new TestCase {
      val project = projectGenerator.normal.generate.copy(archivedAt = None)
      val request = requestGenerator.normal.generate.copy(role = ProjectRole.Owner)
      val user    = userGenerator.normal.generate
      val projectUser =
        projectUserGenerator.normal.generate.copy(projectId = project.id, userId = user.id, archivedAt = None, role = ProjectRole.Owner)
      val organization  = organizationGenerator.normal.generate
      val orgUser       = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, Nil, None)
      val orgUserFilter = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(user.id))
      val orgUserTarget = OrganizationUser(OrganizationUserId.random, organization.id, user.id, Nil, None)

      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(any)).thenReturn(Some(orgUser).asDBIO)
      when(projectRepository.find(any)).thenReturn(Some(project).asDBIO)
      when(projectUserRepository.find(any)).thenReturn(Some(projectUser).asDBIO)
      when(userRepository.find(UserFilters(id = Some(user.id)))).thenReturn(Some(user).asDBIO)
      when(organizationUserRepository.find(orgUserFilter)).thenReturn(Some(orgUserTarget).asDBIO)

      sut.assign(authUser, organization.slug, project.id, user.id, request).asserting(_ shouldEqual UserAlreadyAssigned)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(organization.slug)))
      verify(organizationUserRepository).find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id)))
      verify(projectRepository).find(ProjectFilters(organizationIds = Some(Seq(organization.id)), id = Some(project.id), forUpdate = true))
      verify(projectUserRepository).find(
        ProjectUserFilters(projectIds = Some(Seq(project.id)), userId = Some(authUser.id), role = Some(ProjectRole.Owner))
      )
      verify(userRepository).find(UserFilters(id = Some(user.id)))
      verify(projectUserRepository).find(ProjectUserFilters(projectIds = Some(Seq(project.id)), userId = Some(user.id), forUpdate = true))
      verify(organizationUserRepository).find(orgUserFilter)
    }

    "return CreatorNotDeassignable when admin tries to assign creator to member role" in new TestCase {
      val authCreator = authContextGenerator.normal.generate
      val project     = projectGenerator.normal.generate.copy(archivedAt = None, createdBy = authCreator.id)
      val request     = requestGenerator.normal.generate.copy(role = ProjectRole.Member)
      val user        = userGenerator.normal.generate.copy(id = project.createdBy)
      val projectUser =
        projectUserGenerator.normal.generate.copy(projectId = project.id, userId = user.id, archivedAt = None, role = ProjectRole.Owner)
      val organization  = organizationGenerator.normal.generate
      val orgUserFilter = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(user.id))
      val orgUser       = OrganizationUser(OrganizationUserId.random, organization.id, user.id, Nil, None)

      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(projectRepository.find(any)).thenReturn(Some(project).asDBIO)
      when(userRepository.find(UserFilters(id = Some(user.id)))).thenReturn(Some(user).asDBIO)
      when(projectUserRepository.find(any)).thenReturn(Some(projectUser).asDBIO)
      when(organizationUserRepository.find(orgUserFilter)).thenReturn(Some(orgUser).asDBIO)

      sut.assign(authAdmin, organization.slug, project.id, user.id, request).asserting(_ shouldEqual CreatorNotDeassignable)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(organization.slug)))
      verify(projectRepository).find(ProjectFilters(organizationIds = Some(Seq(organization.id)), id = Some(project.id), forUpdate = true))
      verify(userRepository).find(UserFilters(id = Some(user.id)))
      verify(projectUserRepository).find(ProjectUserFilters(projectIds = Some(Seq(project.id)), userId = Some(user.id), forUpdate = true))
      verify(organizationUserRepository).find(orgUserFilter)
    }

    "return Assigned when admin tries to assign projectOwner as projectMember" in new TestCase {
      val authCreator = authContextGenerator.normal.generate
      val project     = projectGenerator.normal.generate.copy(archivedAt = None, createdBy = authCreator.id)
      val request     = requestGenerator.normal.generate.copy(role = ProjectRole.Member)
      val user        = userGenerator.normal.generate
      val projectUser =
        projectUserGenerator.normal.generate.copy(projectId = project.id, userId = user.id, archivedAt = None, role = ProjectRole.Owner)
      val organization  = organizationGenerator.normal.generate
      val orgUserFilter = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(user.id))
      val orgUser       = OrganizationUser(OrganizationUserId.random, organization.id, user.id, Nil, None)

      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(projectRepository.find(any)).thenReturn(Some(project).asDBIO)
      when(userRepository.find(UserFilters(id = Some(user.id)))).thenReturn(Some(user).asDBIO)
      when(projectUserRepository.find(any)).thenReturn(Some(projectUser).asDBIO)
      when(projectUserRepository.update(any)).thenReturn(Some(projectUser).asDBIO)
      when(projectUserRepository.insert(any: ProjectUser)).thenReturn(projectUser.asDBIO)
      when(organizationUserRepository.find(orgUserFilter)).thenReturn(Some(orgUser).asDBIO)

      sut.assign(authAdmin, organization.slug, project.id, user.id, request).asserting(_ shouldEqual Assigned)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(organization.slug)))
      verify(projectRepository).find(ProjectFilters(organizationIds = Some(Seq(organization.id)), id = Some(project.id), forUpdate = true))
      verify(userRepository).find(UserFilters(id = Some(user.id)))
      verify(projectUserRepository).find(ProjectUserFilters(projectIds = Some(Seq(project.id)), userId = Some(user.id), forUpdate = true))
      verify(projectUserRepository).update(argThat { (archived: ProjectUser) =>
        archived.projectId == projectUser.projectId &&
        archived.userId == projectUser.userId &&
        archived.archivedAt.isDefined
      })
      verify(projectUserRepository).insert(argThat { (inserted: ProjectUser) =>
        inserted.projectId == projectUser.projectId &&
        inserted.userId == projectUser.userId &&
        inserted.role == request.role &&
        inserted.archivedAt.isEmpty
      })
      verify(organizationUserRepository).find(orgUserFilter)
    }

    "return Assigned when admin tries to assign projectMember as projectOwner" in new TestCase {
      val authCreator = authContextGenerator.normal.generate
      val project     = projectGenerator.normal.generate.copy(archivedAt = None, createdBy = authCreator.id)
      val request     = requestGenerator.normal.generate.copy(role = ProjectRole.Owner)
      val user        = userGenerator.normal.generate
      val projectUser =
        projectUserGenerator.normal.generate.copy(projectId = project.id, userId = user.id, archivedAt = None, role = ProjectRole.Member)
      val organization  = organizationGenerator.normal.generate
      val orgUserFilter = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(user.id))
      val orgUser       = OrganizationUser(OrganizationUserId.random, organization.id, user.id, Nil, None)

      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(projectRepository.find(any)).thenReturn(Some(project).asDBIO)
      when(userRepository.find(UserFilters(id = Some(user.id)))).thenReturn(Some(user).asDBIO)
      when(projectUserRepository.find(any)).thenReturn(Some(projectUser).asDBIO)
      when(projectUserRepository.update(any)).thenReturn(Some(projectUser).asDBIO)
      when(projectUserRepository.insert(any: ProjectUser)).thenReturn(projectUser.asDBIO)
      when(organizationUserRepository.find(orgUserFilter)).thenReturn(Some(orgUser).asDBIO)

      sut.assign(authAdmin, organization.slug, project.id, user.id, request).asserting(_ shouldEqual Assigned)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(organization.slug)))
      verify(projectRepository).find(ProjectFilters(organizationIds = Some(Seq(organization.id)), id = Some(project.id), forUpdate = true))
      verify(userRepository).find(UserFilters(id = Some(user.id)))
      verify(projectUserRepository).find(ProjectUserFilters(projectIds = Some(Seq(project.id)), userId = Some(user.id), forUpdate = true))
      verify(projectUserRepository).update(argThat { (archived: ProjectUser) =>
        archived.projectId == projectUser.projectId &&
        archived.userId == projectUser.userId &&
        archived.archivedAt.isDefined
      })
      verify(projectUserRepository).insert(argThat { (inserted: ProjectUser) =>
        inserted.projectId == projectUser.projectId &&
        inserted.userId == projectUser.userId &&
        inserted.archivedAt.isEmpty
      })
      verify(organizationUserRepository).find(orgUserFilter)
    }

    "return Assigned when admin tries to assign user to project that he is not assigned to" in new TestCase {
      val authCreator   = authContextGenerator.normal.generate
      val project       = projectGenerator.normal.generate.copy(archivedAt = None, createdBy = authCreator.id)
      val request       = requestGenerator.normal.generate
      val user          = userGenerator.normal.generate
      val projectUser   = projectUserGenerator.normal.generate.copy(projectId = project.id, userId = user.id, archivedAt = None)
      val organization  = organizationGenerator.normal.generate
      val orgUserFilter = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(user.id))
      val orgUser       = OrganizationUser(OrganizationUserId.random, organization.id, user.id, Nil, None)

      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(projectRepository.find(any)).thenReturn(Some(project).asDBIO)
      when(userRepository.find(UserFilters(id = Some(user.id)))).thenReturn(Some(user).asDBIO)
      when(projectUserRepository.find(any)).thenReturn(None.asDBIO)
      when(projectUserRepository.insert(any: ProjectUser)).thenReturn(projectUser.asDBIO)
      when(organizationUserRepository.find(orgUserFilter)).thenReturn(Some(orgUser).asDBIO)

      sut.assign(authAdmin, organization.slug, project.id, user.id, request).asserting(_ shouldEqual Assigned)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(organization.slug)))
      verify(projectRepository).find(ProjectFilters(organizationIds = Some(Seq(organization.id)), id = Some(project.id), forUpdate = true))
      verify(userRepository).find(UserFilters(id = Some(user.id)))
      verify(projectUserRepository).find(ProjectUserFilters(projectIds = Some(Seq(project.id)), userId = Some(user.id), forUpdate = true))
      verify(projectUserRepository).insert(argThat { (inserted: ProjectUser) =>
        inserted.projectId == projectUser.projectId &&
        inserted.userId == projectUser.userId &&
        inserted.archivedAt.isEmpty
      })
      verify(organizationUserRepository).find(orgUserFilter)
    }
  }
}
