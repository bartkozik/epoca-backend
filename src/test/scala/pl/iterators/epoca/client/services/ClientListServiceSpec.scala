package pl.iterators.epoca.client.services

import pl.iterators.epoca.client.domain.Client
import pl.iterators.epoca.client.domain.ClientDomain.ClientProjectCount
import pl.iterators.epoca.client.repositories.ClientRepository
import pl.iterators.epoca.client.repositories.ClientRepository.ClientFilters
import pl.iterators.epoca.client.services.ClientDtoService.ClientDto
import pl.iterators.epoca.client.services.ClientListService.ClientListResult
import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationSlug, OrganizationUserId, OrganizationUserRole}
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.project.domain.Project
import pl.iterators.epoca.project.repositories.ProjectRepository
import pl.iterators.epoca.project.repositories.ProjectRepository.ProjectFilters
import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.utils.ServiceSpec
import pl.iterators.epoca.utils.db.PaginatedResult
import pl.iterators.epoca.utils.transactor.{IsolationLevel, TransactorCall}

class ClientListServiceSpec extends ServiceSpec {
  lazy val organizationGenerator: AllGenerators[Organization]       = allGenerators[Organization]
  lazy val orgSlugGenerator: AllGenerators[OrganizationSlug]        = allGenerators[OrganizationSlug]
  lazy val clientGenerator: AllGenerators[Client]                   = allGenerators[Client]
  lazy val projectGenerator: AllGenerators[Project]                 = allGenerators[Project]
  lazy val rolesGenerator: AllGenerators[Seq[OrganizationUserRole]] = allGenerators[Seq[OrganizationUserRole]]
  trait TestCase extends BaseScope {
    val clientRepository: ClientRepository                     = vmock[ClientRepository]
    val organizationUserRepository: OrganizationUserRepository = vmock[OrganizationUserRepository]
    val organizationRepository: OrganizationRepository         = vmock[OrganizationRepository]
    val clientDtoService: ClientDtoService                     = vmock[ClientDtoService]
    val projectRepository: ProjectRepository                   = vmock[ProjectRepository]

    val sut: ClientListService = wire[ClientListService]

    val organization: Organization = organizationGenerator.normal.generate.copy(archivedAt = None)
    val orgSlug                    = orgSlugGenerator.normal.generate
    val client: Client =
      clientGenerator.normal.generate.copy(archivedAt = None, organizationId = organization.id)
    val clientListDto: ClientDto = ClientDto.from(client, Seq(client.id -> ClientProjectCount(1)).toMap)
  }

  "list" should {
    "return OrganizationNotFound when admin tries to list clients in non existing organization" in new TestCase {
      val filter: ClientFilters = ClientFilters()

      when(organizationRepository.find(any)).thenReturn(None.asDBIO)

      sut.list(authAdmin, orgSlug, filter).asserting(_ shouldEqual ClientListResult.OrganizationNotFound)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
    }

    "return NoAccessToOrganization when user not from organization tries to list clients" in new TestCase {
      val filter: ClientFilters = ClientFilters()

      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(any)).thenReturn(None.asDBIO)

      sut.list(authUser, orgSlug, filter).asserting(_ shouldEqual ClientListResult.NoAccessToOrganization)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
      verify(organizationUserRepository).find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id)))
    }

    "return Ok with service data for user assigned to existing organization" in new TestCase {
      val filter: ClientFilters = ClientFilters()
      val roles                 = Seq(OrganizationUserRole.Manager)
      val orgUser               = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, roles, None)

      when(organizationUserRepository.find(any)).thenReturn(Some(orgUser).asDBIO)
      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(clientRepository.list(any)).thenReturn(Seq(client).asDBIO)
      when(clientRepository.count(any)).thenReturn(7.asDBIO)
      when(clientDtoService.prepareDtos(any, any)).thenReturn(Seq(clientListDto).asDBIO)

      sut.list(authUser, orgSlug, filter).asserting(_ shouldEqual ClientListResult.Ok(PaginatedResult(7, Seq(clientListDto))))

      verify(organizationUserRepository).find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id)))
      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
      verify(clientRepository).list(filter.copy(organizationIds = Some(Seq(organization.id))))
      verify(clientRepository).count(filter.copy(organizationIds = Some(Seq(organization.id))))
      verify(clientDtoService).prepareDtos(Seq(client), None)
    }

    "read the page and the count in a single RepeatableRead transaction" in new TestCase {
      val filter: ClientFilters = ClientFilters()
      val orgUser               = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, Seq(OrganizationUserRole.Manager), None)

      when(organizationUserRepository.find(any)).thenReturn(Some(orgUser).asDBIO)
      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(clientRepository.list(any)).thenReturn(Seq(client).asDBIO)
      when(clientRepository.count(any)).thenReturn(7.asDBIO)
      when(clientDtoService.prepareDtos(any, any)).thenReturn(Seq(clientListDto).asDBIO)

      sut
        .list(authUser, orgSlug, filter)
        .asserting(_ => transactorCalls shouldEqual Seq(TransactorCall.ExecuteTransactionallyAt(IsolationLevel.RepeatableRead)))

      verify(organizationUserRepository).find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id)))
      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
      verify(clientRepository).list(filter.copy(organizationIds = Some(Seq(organization.id))))
      verify(clientRepository).count(filter.copy(organizationIds = Some(Seq(organization.id))))
      verify(clientDtoService).prepareDtos(Seq(client), None)
    }

    "restrict filters if user isn't a manager" in new TestCase {
      val filter: ClientFilters = ClientFilters()

      val projects: Seq[Project] = Seq(projectGenerator.normal.generate, projectGenerator.normal.generate, projectGenerator.normal.generate)
      val projectFilter          = ProjectFilters(organizationIds = Some(Seq(organization.id)), userId = Some(authUser.id))

      val restrictedFilter = filter.copy(ids = Some(projects.map(_.clientId)))
      val roles            = Seq(OrganizationUserRole.Employee)
      val orgUser          = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, roles, None)

      when(organizationUserRepository.find(any)).thenReturn(Some(orgUser).asDBIO)
      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(projectRepository.list(any)).thenReturn(projects.asDBIO)
      when(clientRepository.list(any)).thenReturn(Seq(client).asDBIO)
      when(clientRepository.count(any)).thenReturn(7.asDBIO)
      when(clientDtoService.prepareDtos(any, any)).thenReturn(Seq(clientListDto).asDBIO)

      sut.list(authUser, orgSlug, filter).asserting(_ shouldEqual ClientListResult.Ok(PaginatedResult(7, Seq(clientListDto))))

      verify(organizationUserRepository).find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id)))
      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
      verify(projectRepository).list(projectFilter)
      verify(clientRepository).list(restrictedFilter.copy(organizationIds = Some(Seq(organization.id))))
      verify(clientRepository).count(restrictedFilter.copy(organizationIds = Some(Seq(organization.id))))
      verify(clientDtoService).prepareDtos(Seq(client), Some(authUser.id))
    }

    "restrict filters if user isn't a manager with archived connections" in new TestCase {
      val filter: ClientFilters = ClientFilters(includeArchived = true)

      val projects: Seq[Project] = Seq(projectGenerator.normal.generate, projectGenerator.normal.generate, projectGenerator.normal.generate)
      val projectFilter =
        ProjectFilters(organizationIds = Some(Seq(organization.id)), archivedConnectionsUserId = Some(authUser.id), includeArchived = true)

      val restrictedFilter = filter.copy(ids = Some(projects.map(_.clientId)))
      val roles            = Seq(OrganizationUserRole.Employee)
      val orgUser          = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, roles, None)

      when(organizationUserRepository.find(any)).thenReturn(Some(orgUser).asDBIO)
      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(projectRepository.list(any)).thenReturn(projects.asDBIO)
      when(clientRepository.list(any)).thenReturn(Seq(client).asDBIO)
      when(clientRepository.count(any)).thenReturn(7.asDBIO)
      when(clientDtoService.prepareDtos(any, any)).thenReturn(Seq(clientListDto).asDBIO)

      sut.list(authUser, orgSlug, filter, true).asserting(_ shouldEqual ClientListResult.Ok(PaginatedResult(7, Seq(clientListDto))))

      verify(organizationUserRepository).find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id)))
      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
      verify(projectRepository).list(projectFilter)
      verify(clientRepository).list(restrictedFilter.copy(organizationIds = Some(Seq(organization.id))))
      verify(clientRepository).count(restrictedFilter.copy(organizationIds = Some(Seq(organization.id))))
      verify(clientDtoService).prepareDtos(Seq(client), Some(authUser.id))
    }

    "return Ok with service data for admin" in new TestCase {
      val filter: ClientFilters = ClientFilters()

      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(clientRepository.list(any)).thenReturn(Seq(client).asDBIO)
      when(clientRepository.count(any)).thenReturn(5.asDBIO)
      when(clientDtoService.prepareDtos(any, any)).thenReturn(Seq(clientListDto).asDBIO)

      sut.list(authAdmin, orgSlug, filter).asserting(_ shouldEqual ClientListResult.Ok(PaginatedResult(5, Seq(clientListDto))))

      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
      verify(clientRepository).list(filter.copy(organizationIds = Some(Seq(organization.id))))
      verify(clientRepository).count(filter.copy(organizationIds = Some(Seq(organization.id))))
      verify(clientDtoService).prepareDtos(Seq(client), None)
    }
  }
}
