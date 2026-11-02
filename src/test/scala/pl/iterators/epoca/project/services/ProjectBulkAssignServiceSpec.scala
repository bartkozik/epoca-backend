package pl.iterators.epoca.project.services

import pl.iterators.epoca.client.domain.Client
import pl.iterators.epoca.client.repositories.ClientRepository
import pl.iterators.epoca.client.repositories.ClientRepository.ClientFilters
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
import pl.iterators.epoca.project.services.ProjectBulkAssignService.{ProjectBulkAssignRequest, ProjectBulkAssignResponse, ProjectBulkAssignResult}
import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.domain.UserDomain.UserAccountType
import pl.iterators.epoca.utils.ServiceSpec
import slick.dbio.DBIO

class ProjectBulkAssignServiceSpec extends ServiceSpec {
  lazy val projectGenerator: AllGenerators[Project]                   = allGenerators[Project]
  lazy val userGenerator: AllGenerators[User]                         = allGenerators[User]
  lazy val clientGenerator: AllGenerators[Client]                     = allGenerators[Client]
  lazy val projectUserGenerator: AllGenerators[ProjectUser]           = allGenerators[ProjectUser]
  lazy val organizationUserGenerator: AllGenerators[OrganizationUser] = allGenerators[OrganizationUser]
  lazy val organizationGenerator: AllGenerators[Organization]         = allGenerators[Organization]
  lazy val requestGenerator: AllGenerators[ProjectBulkAssignRequest]  = allGenerators[ProjectBulkAssignRequest]

  trait TestCase extends BaseScope {
    val clientRepository: ClientRepository                     = vmock[ClientRepository]
    val projectRepository: ProjectRepository                   = vmock[ProjectRepository]
    val projectUserRepository: ProjectUserRepository           = vmock[ProjectUserRepository]
    val organizationRepository: OrganizationRepository         = vmock[OrganizationRepository]
    val organizationUserRepository: OrganizationUserRepository = vmock[OrganizationUserRepository]

    val sut: ProjectBulkAssignService = wire[ProjectBulkAssignService]
  }

  "assign" should {
    "return OrganizationNotFound if target organization slug doesn't match any existing in db" in new TestCase {
      val organization = organizationGenerator.normal.generate
      val user         = userGenerator.normal.generate
      val request      = requestGenerator.minimal.generate

      val orgFilter = OrganizationFilters(slug = Some(organization.slug))

      when(organizationRepository.find(orgFilter)).thenReturn(None.asDBIO)

      sut.assign(authUser, organization.slug, user.id, request).asserting(_ shouldEqual ProjectBulkAssignResult.OrganizationNotFound)

      verify(organizationRepository).find(orgFilter)
    }
    "return NoAccessToOrganization if requestee isn't organization creator or member" in new TestCase {
      val organization = organizationGenerator.normal.generate
      val user         = userGenerator.normal.generate
      val request      = requestGenerator.minimal.generate

      val orgFilter     = OrganizationFilters(slug = Some(organization.slug))
      val orgUserFilter = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id))

      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(orgUserFilter)).thenReturn(None.asDBIO)

      sut.assign(authUser, organization.slug, user.id, request).asserting(_ shouldEqual ProjectBulkAssignResult.NoAccessToOrganization)

      verify(organizationRepository).find(orgFilter)
      verify(organizationUserRepository).find(orgUserFilter)
    }
    "return NoAccessToOrganization if requestee is only an Employee" in new TestCase {
      val organization = organizationGenerator.normal.generate
      val user         = userGenerator.normal.generate
      val request      = requestGenerator.minimal.generate

      val orgUser = organizationUserGenerator.normal.generate.copy(roles = Seq(OrganizationUserRole.Employee))

      val orgFilter     = OrganizationFilters(slug = Some(organization.slug))
      val orgUserFilter = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id))

      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(orgUserFilter)).thenReturn(Some(orgUser).asDBIO)

      sut.assign(authUser, organization.slug, user.id, request).asserting(_ shouldEqual ProjectBulkAssignResult.NoAccessToOrganization)

      verify(organizationRepository).find(orgFilter)
      verify(organizationUserRepository).find(orgUserFilter)
    }
    "return NotAMember if target user isn't organization member" in new TestCase {
      val organization = organizationGenerator.normal.generate.copy(createdBy = authUser.id)
      val user         = userGenerator.normal.generate
      val request      = requestGenerator.minimal.generate
      val orgUser1     = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, Seq(OrganizationUserRole.Manager), None)

      val orgFilter      = OrganizationFilters(slug = Some(organization.slug))
      val orgUserFilter1 = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id))
      val orgUserFilter2 = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(user.id))

      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(orgUserFilter1)).thenReturn(Some(orgUser1).asDBIO)
      when(organizationUserRepository.find(orgUserFilter2)).thenReturn(None.asDBIO)

      sut.assign(authUser, organization.slug, user.id, request).asserting(_ shouldEqual ProjectBulkAssignResult.NotAMember)

      verify(organizationRepository).find(orgFilter)
      verify(organizationUserRepository).find(orgUserFilter1)
      verify(organizationUserRepository).find(orgUserFilter2)
    }
    "return the same NotAMember result for a service account filtered from organization membership" in new TestCase {
      val organization   = organizationGenerator.normal.generate.copy(createdBy = authUser.id)
      val serviceAccount = userGenerator.normal.generate.copy(accountType = UserAccountType.ServiceAccount, archivedAt = None)
      val request        = requestGenerator.minimal.generate
      val orgUser        = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, Seq(OrganizationUserRole.Manager), None)

      val orgFilter        = OrganizationFilters(slug = Some(organization.slug))
      val authUserFilter   = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id))
      val targetUserFilter = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(serviceAccount.id))

      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(authUserFilter)).thenReturn(Some(orgUser).asDBIO)
      when(organizationUserRepository.find(targetUserFilter)).thenReturn(None.asDBIO)

      sut.assign(authUser, organization.slug, serviceAccount.id, request).asserting(_ shouldEqual ProjectBulkAssignResult.NotAMember)

      verify(organizationRepository).find(orgFilter)
      verify(organizationUserRepository).find(authUserFilter)
      verify(organizationUserRepository).find(targetUserFilter)
      verify(projectUserRepository, times(0)).insert(any: Seq[ProjectUser])
    }
    "map a repository ineligible-user race to the same NotAMember result" in new TestCase {
      val organization = organizationGenerator.normal.generate.copy(createdBy = authUser.id)
      val human        = userGenerator.normal.generate.copy(accountType = UserAccountType.Human, archivedAt = None)
      val actorMember  = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, Seq(OrganizationUserRole.Manager), None)
      val humanMember  = OrganizationUser(OrganizationUserId.random, organization.id, human.id, Nil, None)
      val project      = projectGenerator.normal.generate
      val request      = requestGenerator.minimal.generate

      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id))))
        .thenReturn(Some(actorMember).asDBIO)
      when(organizationUserRepository.find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(human.id))))
        .thenReturn(Some(humanMember).asDBIO)
      when(projectRepository.list(any)).thenReturn(Seq(project).asDBIO)
      when(projectUserRepository.list(any)).thenReturn(Nil.asDBIO)
      when(projectUserRepository.insert(any: Seq[ProjectUser])).thenReturn(DBIO.failed(ProjectUserNotPermitted))

      sut.assign(authUser, organization.slug, human.id, request).asserting(_ shouldEqual ProjectBulkAssignResult.NotAMember)

      verify(organizationRepository).find(any)
      verify(organizationUserRepository, times(2)).find(any)
      verify(projectRepository).list(any)
      verify(projectUserRepository).list(any)
      verify(projectUserRepository).insert(any: Seq[ProjectUser])
    }
    "return Assigned when admin tries to assign user to project that he is not assigned to" in new TestCase {
      val organization = organizationGenerator.normal.generate.copy(createdBy = authUser.id)
      val user         = userGenerator.normal.generate
      val orgUser1     = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, Seq(OrganizationUserRole.Manager), None)
      val orgUser2     = OrganizationUser(OrganizationUserId.random, organization.id, user.id, Nil, None)
      val project1     = projectGenerator.normal.generate
      val project2     = projectGenerator.normal.generate
      val projectUser =
        projectUserGenerator.normal.generate.copy(projectId = project1.id, userId = user.id, archivedAt = None, role = ProjectRole.Member)
      val request = requestGenerator.minimal.generate

      val orgFilter         = OrganizationFilters(slug = Some(organization.slug))
      val orgUserFilter1    = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id))
      val orgUserFilter2    = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(user.id))
      val projectFilter     = ProjectFilters(organizationIds = Some(Seq(organization.id)), clientIds = None)
      val projectUserFilter = ProjectUserFilters(projectIds = Some(Seq(project1.id, project2.id)), userId = Some(user.id))

      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(orgUserFilter1)).thenReturn(Some(orgUser1).asDBIO)
      when(organizationUserRepository.find(orgUserFilter2)).thenReturn(Some(orgUser2).asDBIO)
      when(projectRepository.list(projectFilter)).thenReturn(Seq(project1, project2).asDBIO)
      when(projectUserRepository.list(projectUserFilter)).thenReturn(Seq(projectUser).asDBIO)
      when(projectUserRepository.insert(any: Seq[ProjectUser])).thenReturn(Seq().asDBIO)

      val data = ProjectBulkAssignResponse(Seq(project2.id))
      sut.assign(authUser, organization.slug, user.id, request).asserting(_ shouldEqual ProjectBulkAssignResult.Assigned(data))

      verify(organizationRepository).find(orgFilter)
      verify(organizationUserRepository).find(orgUserFilter1)
      verify(organizationUserRepository).find(orgUserFilter2)
      verify(projectRepository).list(projectFilter)
      verify(projectUserRepository).list(projectUserFilter)
      verify(projectUserRepository).insert(argThat { (inserted: Seq[ProjectUser]) =>
        inserted.head.projectId == project2.id &&
        inserted.head.userId == user.id &&
        inserted.head.archivedAt.isEmpty &&
        inserted.head.role == ProjectRole.Member &&
        inserted.length == 1
      })
    }
    "return Assigned when admin tries to assign user to project that he is not assigned to filtered by client type" in new TestCase {
      val organization = organizationGenerator.normal.generate.copy(createdBy = authUser.id)
      val user         = userGenerator.normal.generate
      val orgUser1     = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, Seq(OrganizationUserRole.Manager), None)
      val orgUser2     = OrganizationUser(OrganizationUserId.random, organization.id, user.id, Nil, None)
      val project1     = projectGenerator.normal.generate
      val project2     = projectGenerator.normal.generate
      val projectUser =
        projectUserGenerator.normal.generate.copy(projectId = project1.id, userId = user.id, archivedAt = None, role = ProjectRole.Member)
      val client  = clientGenerator.normal.generate
      val request = requestGenerator.maximal.generate

      val orgFilter         = OrganizationFilters(slug = Some(organization.slug))
      val orgUserFilter1    = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id))
      val orgUserFilter2    = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(user.id))
      val projectFilter     = ProjectFilters(organizationIds = Some(Seq(organization.id)), clientIds = Some(Seq(client.id)))
      val projectUserFilter = ProjectUserFilters(projectIds = Some(Seq(project1.id, project2.id)), userId = Some(user.id))
      val clientFilter      = ClientFilters(`type` = request.clientType, id = request.clientId, organizationIds = Some(Seq(organization.id)))

      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(orgUserFilter1)).thenReturn(Some(orgUser1).asDBIO)
      when(organizationUserRepository.find(orgUserFilter2)).thenReturn(Some(orgUser2).asDBIO)
      when(clientRepository.list(clientFilter)).thenReturn(Seq(client).asDBIO)
      when(projectRepository.list(projectFilter)).thenReturn(Seq(project1, project2).asDBIO)
      when(projectUserRepository.list(projectUserFilter)).thenReturn(Seq(projectUser).asDBIO)
      when(projectUserRepository.insert(any: Seq[ProjectUser])).thenReturn(Seq().asDBIO)

      val data = ProjectBulkAssignResponse(Seq(project2.id))
      sut.assign(authUser, organization.slug, user.id, request).asserting(_ shouldEqual ProjectBulkAssignResult.Assigned(data))

      verify(organizationRepository).find(orgFilter)
      verify(organizationUserRepository).find(orgUserFilter1)
      verify(organizationUserRepository).find(orgUserFilter2)
      verify(clientRepository).list(clientFilter)
      verify(projectRepository).list(projectFilter)
      verify(projectUserRepository).list(projectUserFilter)
      verify(projectUserRepository).insert(argThat { (inserted: Seq[ProjectUser]) =>
        inserted.head.projectId == project2.id &&
        inserted.head.userId == user.id &&
        inserted.head.archivedAt.isEmpty &&
        inserted.head.role == ProjectRole.Member &&
        inserted.length == 1
      })
    }
  }
}
