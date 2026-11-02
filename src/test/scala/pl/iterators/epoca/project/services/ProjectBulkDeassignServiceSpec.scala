package pl.iterators.epoca.project.services

import pl.iterators.epoca.client.domain.Client
import pl.iterators.epoca.client.repositories.ClientRepository
import pl.iterators.epoca.client.repositories.ClientRepository.ClientFilters
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationUserRole
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.project.domain.ProjectDomain.{ProjectRole, ProjectUserId}
import pl.iterators.epoca.project.domain.{Project, ProjectUser}
import pl.iterators.epoca.project.repositories.ProjectRepository.ProjectFilters
import pl.iterators.epoca.project.repositories.ProjectUserRepository.ProjectUserFilters
import pl.iterators.epoca.project.repositories.{ProjectRepository, ProjectUserRepository}
import pl.iterators.epoca.project.services.ProjectBulkDeassignService.{
  ProjectBulkDeassignRequest,
  ProjectBulkDeassignResponse,
  ProjectBulkDeassignResult
}
import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.utils.ServiceSpec

class ProjectBulkDeassignServiceSpec extends ServiceSpec {
  lazy val projectGenerator: AllGenerators[Project]                    = allGenerators[Project]
  lazy val userGenerator: AllGenerators[User]                          = allGenerators[User]
  lazy val clientGenerator: AllGenerators[Client]                      = allGenerators[Client]
  lazy val projectUserGenerator: AllGenerators[ProjectUser]            = allGenerators[ProjectUser]
  lazy val organizationUserGenerator: AllGenerators[OrganizationUser]  = allGenerators[OrganizationUser]
  lazy val organizationGenerator: AllGenerators[Organization]          = allGenerators[Organization]
  lazy val requestGenerator: AllGenerators[ProjectBulkDeassignRequest] = allGenerators[ProjectBulkDeassignRequest]

  trait TestCase extends BaseScope {
    val clientRepository: ClientRepository                     = vmock[ClientRepository]
    val projectRepository: ProjectRepository                   = vmock[ProjectRepository]
    val projectUserRepository: ProjectUserRepository           = vmock[ProjectUserRepository]
    val organizationRepository: OrganizationRepository         = vmock[OrganizationRepository]
    val organizationUserRepository: OrganizationUserRepository = vmock[OrganizationUserRepository]

    val sut: ProjectBulkDeassignService = wire[ProjectBulkDeassignService]
  }

  "deassign" should {
    "return OrganizationNotFound if target organization slug doesn't match any existing in db" in new TestCase {
      val organization = organizationGenerator.normal.generate
      val user         = userGenerator.normal.generate
      val request      = requestGenerator.minimal.generate

      val orgFilter = OrganizationFilters(slug = Some(organization.slug))

      when(organizationRepository.find(orgFilter)).thenReturn(None.asDBIO)

      sut.deassign(authUser, organization.slug, user.id, request).asserting(_ shouldEqual ProjectBulkDeassignResult.OrganizationNotFound)

      verify(organizationRepository).find(orgFilter)
    }
    "return NoAccessToOrganization if requestee isn't organization creator but a regular employee" in new TestCase {
      val organization = organizationGenerator.normal.generate
      val user         = userGenerator.normal.generate
      val request      = requestGenerator.minimal.generate

      val orgFilter      = OrganizationFilters(slug = Some(organization.slug))
      val orgUserFilters = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id))

      val orgUser = organizationUserGenerator.normal.generate.copy(roles = Seq(OrganizationUserRole.Employee))

      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(orgUserFilters)).thenReturn(Some(orgUser).asDBIO)

      sut.deassign(authUser, organization.slug, user.id, request).asserting(_ shouldEqual ProjectBulkDeassignResult.NoAccessToOrganization)

      verify(organizationRepository).find(orgFilter)
      verify(organizationUserRepository).find(orgUserFilters)
    }
    "return Deassigned when admin tries to deassign user from projects that he is assigned to" in new TestCase {
      val organization = organizationGenerator.normal.generate.copy(createdBy = authUser.id)
      val user         = userGenerator.normal.generate
      val project1     = projectGenerator.normal.generate
      val project2     = projectGenerator.normal.generate.copy(createdBy = user.id)
      val projectUser1 =
        projectUserGenerator.normal.generate.copy(projectId = project1.id, userId = user.id, archivedAt = None, role = ProjectRole.Member)
      val request = requestGenerator.minimal.generate

      val orgFilter          = OrganizationFilters(slug = Some(organization.slug))
      val projectFilter      = ProjectFilters(organizationIds = Some(Seq(organization.id)), clientIds = None, notCreatedBy = Some(user.id))
      val projectUserFilter1 = ProjectUserFilters(projectIds = Some(Seq(project1.id)), userId = Some(user.id))
      val orgUserFilters     = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id))
      val orgUser            = organizationUserGenerator.normal.generate.copy(roles = Seq(OrganizationUserRole.Owner))

      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(projectRepository.list(projectFilter)).thenReturn(Seq(project1).asDBIO)
      when(projectUserRepository.lockOrdered(projectUserFilter1)).thenReturn(Seq(projectUser1).asDBIO)
      when(projectUserRepository.bulkArchiveAt(any, any)).thenReturn(1.asDBIO)
      when(organizationUserRepository.find(orgUserFilters)).thenReturn(Some(orgUser).asDBIO)

      val data = ProjectBulkDeassignResponse(Seq(project1.id))
      sut.deassign(authUser, organization.slug, user.id, request).asserting(_ shouldEqual ProjectBulkDeassignResult.Deassigned(data))

      verify(organizationRepository).find(orgFilter)
      verify(projectRepository).list(projectFilter)
      verify(projectUserRepository).lockOrdered(projectUserFilter1)
      verify(projectUserRepository).bulkArchiveAt(argThat((ids: Seq[ProjectUserId]) => ids == Seq(projectUser1.id)), any)
      verify(organizationUserRepository).find(orgUserFilters)
    }
    "return Deassigned when admin tries to deassign user from projects that he is assigned to with client type filter" in new TestCase {
      val organization = organizationGenerator.normal.generate.copy(createdBy = authUser.id)
      val user         = userGenerator.normal.generate
      val project1     = projectGenerator.normal.generate.copy(createdBy = user.id)
      val project2     = projectGenerator.normal.generate
      val projectUser2 =
        projectUserGenerator.normal.generate.copy(projectId = project2.id, userId = user.id, archivedAt = None, role = ProjectRole.Member)
      val client  = clientGenerator.normal.generate
      val request = requestGenerator.maximal.generate

      val orgFilter     = OrganizationFilters(slug = Some(organization.slug))
      val projectFilter = ProjectFilters(organizationIds = Some(Seq(organization.id)), clientIds = Some(Seq(client.id)), notCreatedBy = Some(user.id))
      val projectUserFilter2 = ProjectUserFilters(projectIds = Some(Seq(project2.id)), userId = Some(user.id))
      val clientFilter       = ClientFilters(`type` = request.clientType, id = request.clientId, organizationIds = Some(Seq(organization.id)))
      val orgUserFilters     = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id))
      val orgUser            = organizationUserGenerator.normal.generate.copy(roles = Seq(OrganizationUserRole.Owner))

      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(projectRepository.list(projectFilter)).thenReturn(Seq(project2).asDBIO)
      when(clientRepository.list(clientFilter)).thenReturn(Seq(client).asDBIO)
      when(projectUserRepository.lockOrdered(projectUserFilter2)).thenReturn(Seq(projectUser2).asDBIO)
      when(projectUserRepository.bulkArchiveAt(any, any)).thenReturn(1.asDBIO)
      when(organizationUserRepository.find(orgUserFilters)).thenReturn(Some(orgUser).asDBIO)

      val data = ProjectBulkDeassignResponse(Seq(project2.id))
      sut.deassign(authUser, organization.slug, user.id, request).asserting(_ shouldEqual ProjectBulkDeassignResult.Deassigned(data))

      verify(organizationRepository).find(orgFilter)
      verify(projectRepository).list(projectFilter)
      verify(clientRepository).list(clientFilter)
      verify(projectUserRepository).lockOrdered(projectUserFilter2)
      verify(projectUserRepository).bulkArchiveAt(argThat((ids: Seq[ProjectUserId]) => ids == Seq(projectUser2.id)), any)
      verify(organizationUserRepository).find(orgUserFilters)
    }
  }
}
