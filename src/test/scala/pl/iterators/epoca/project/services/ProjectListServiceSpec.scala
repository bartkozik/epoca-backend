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
import pl.iterators.epoca.project.repositories.{ProjectRepository, ProjectUserRepository}
import pl.iterators.epoca.project.services.ProjectDtoService.{ProjectClientDto, ProjectListDto}
import pl.iterators.epoca.project.services.ProjectListService.ProjectListResult
import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.repositories.UserRepository
import pl.iterators.epoca.utils.ServiceSpec
import pl.iterators.epoca.utils.db.PaginatedResult
import pl.iterators.epoca.utils.transactor.{IsolationLevel, TransactorCall}

class ProjectListServiceSpec extends ServiceSpec {
  lazy val organizationGenerator: AllGenerators[Organization] = allGenerators[Organization]
  lazy val orgSlugGenerator: AllGenerators[OrganizationSlug]  = allGenerators[OrganizationSlug]
  lazy val projectGenerator: AllGenerators[Project]           = allGenerators[Project]
  lazy val projectUserGenerator: AllGenerators[ProjectUser]   = allGenerators[ProjectUser]
  lazy val userGenerator: AllGenerators[User]                 = allGenerators[User]
  lazy val clientGenerator: AllGenerators[Client]             = allGenerators[Client]

  trait TestCase extends BaseScope {
    val projectRepository: ProjectRepository                   = vmock[ProjectRepository]
    val organizationUserRepository: OrganizationUserRepository = vmock[OrganizationUserRepository]
    val organizationRepository: OrganizationRepository         = vmock[OrganizationRepository]
    val projectUserRepository: ProjectUserRepository           = vmock[ProjectUserRepository]
    val clientRepository: ClientRepository                     = vmock[ClientRepository]
    val userRepository: UserRepository                         = vmock[UserRepository]

    val projectDtoService: ProjectDtoService = vmock[ProjectDtoService]

    val sut: ProjectListService = wire[ProjectListService]

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
    val projectListDto: ProjectListDto =
      ProjectListDto.from(
        project,
        Seq(client.id -> ProjectClientDto(client.id, client.name, client.color, client.`type`, client.texture)).toMap,
        Seq(project.id -> Seq(projectUserGenerator.normal.generate)).toMap
      )
  }

  "list" should {
    "return OrganizationNotFound when admin tries to list projects in non existing organization" in new TestCase {
      val filter: ProjectFilters = ProjectFilters()
      when(organizationRepository.find(any)).thenReturn(None.asDBIO)

      sut.list(authAdmin, orgSlug, filter).asserting(_ shouldEqual ProjectListResult.OrganizationNotFound)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
    }

    "return NoAccessToOrganization when user not from organization tries to list projects" in new TestCase {
      val filter: ProjectFilters = ProjectFilters()
      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(any)).thenReturn(None.asDBIO)

      sut.list(authUser, orgSlug, filter).asserting(_ shouldEqual ProjectListResult.NoAccessToOrganization)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
      verify(organizationUserRepository).find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id)))
    }

    "return Ok with service data for user assigned to existing organization" in new TestCase {
      val filter: ProjectFilters = ProjectFilters()
      val orgUser                = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, Seq(OrganizationUserRole.Manager), None)

      when(organizationUserRepository.find(any)).thenReturn(Some(orgUser).asDBIO)
      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(projectRepository.list(any)).thenReturn(Seq(project).asDBIO)
      when(projectRepository.count(any)).thenReturn(7.asDBIO)
      when(projectDtoService.prepareListDtos(any)).thenReturn(Seq(projectListDto).asDBIO)

      sut.list(authUser, orgSlug, filter).asserting(_ shouldEqual ProjectListResult.Ok(PaginatedResult(7, Seq(projectListDto))))

      verify(organizationUserRepository).find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id)))
      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
      verify(projectRepository).list(filter.copy(organizationIds = Some(Seq(organization.id))))
      verify(projectRepository).count(filter.copy(organizationIds = Some(Seq(organization.id))))
      verify(projectDtoService).prepareListDtos(Seq(project))
    }

    "read the page and the count in a single RepeatableRead transaction" in new TestCase {
      val filter: ProjectFilters = ProjectFilters()
      val orgUser                = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, Seq(OrganizationUserRole.Manager), None)

      when(organizationUserRepository.find(any)).thenReturn(Some(orgUser).asDBIO)
      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(projectRepository.list(any)).thenReturn(Seq(project).asDBIO)
      when(projectRepository.count(any)).thenReturn(7.asDBIO)
      when(projectDtoService.prepareListDtos(any)).thenReturn(Seq(projectListDto).asDBIO)

      sut
        .list(authUser, orgSlug, filter)
        .asserting(_ => transactorCalls shouldEqual Seq(TransactorCall.ExecuteTransactionallyAt(IsolationLevel.RepeatableRead)))

      verify(organizationUserRepository).find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id)))
      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
      verify(projectRepository).list(filter.copy(organizationIds = Some(Seq(organization.id))))
      verify(projectRepository).count(filter.copy(organizationIds = Some(Seq(organization.id))))
      verify(projectDtoService).prepareListDtos(Seq(project))
    }

    "return Ok with service data for admin" in new TestCase {
      val filter: ProjectFilters = ProjectFilters()
      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(projectRepository.list(any)).thenReturn(Seq(project).asDBIO)
      when(projectRepository.count(any)).thenReturn(5.asDBIO)
      when(projectDtoService.prepareListDtos(any)).thenReturn(Seq(projectListDto).asDBIO)

      sut.list(authAdmin, orgSlug, filter).asserting(_ shouldEqual ProjectListResult.Ok(PaginatedResult(5, Seq(projectListDto))))

      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
      verify(projectRepository).list(filter.copy(organizationIds = Some(Seq(organization.id))))
      verify(projectRepository).count(filter.copy(organizationIds = Some(Seq(organization.id))))
      verify(projectDtoService).prepareListDtos(Seq(project))
    }
    "return Ok with service data for regular user" in new TestCase {
      val filter: ProjectFilters = ProjectFilters()
      val restrictedFilter       = filter.copy(organizationIds = Some(Seq(organization.id)), userId = Some(authUser.id))
      val orgUser                = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, Seq(OrganizationUserRole.Employee), None)

      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(any)).thenReturn(Some(orgUser).asDBIO)
      when(projectRepository.list(any)).thenReturn(Seq(project).asDBIO)
      when(projectRepository.count(any)).thenReturn(5.asDBIO)
      when(projectDtoService.prepareListDtos(any)).thenReturn(Seq(projectListDto).asDBIO)

      sut.list(authUser, orgSlug, filter).asserting(_ shouldEqual ProjectListResult.Ok(PaginatedResult(5, Seq(projectListDto))))

      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
      verify(organizationUserRepository).find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id)))
      verify(projectRepository).list(restrictedFilter)
      verify(projectRepository).count(restrictedFilter)
      verify(projectDtoService).prepareListDtos(Seq(project))
    }
    "return Ok with service data for regular user with archived connection projects" in new TestCase {
      val filter: ProjectFilters = ProjectFilters()
      val restrictedFilter       = filter.copy(organizationIds = Some(Seq(organization.id)), archivedConnectionsUserId = Some(authUser.id))
      val orgUser                = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, Seq(OrganizationUserRole.Employee), None)

      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(any)).thenReturn(Some(orgUser).asDBIO)
      when(projectRepository.list(any)).thenReturn(Seq(project).asDBIO)
      when(projectRepository.count(any)).thenReturn(5.asDBIO)
      when(projectDtoService.prepareListDtos(any)).thenReturn(Seq(projectListDto).asDBIO)

      sut
        .list(authUser, orgSlug, filter, includeArchivedConnections = true)
        .asserting(_ shouldEqual ProjectListResult.Ok(PaginatedResult(5, Seq(projectListDto))))

      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
      verify(organizationUserRepository).find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id)))
      verify(projectRepository).list(restrictedFilter)
      verify(projectRepository).count(restrictedFilter)
      verify(projectDtoService).prepareListDtos(Seq(project))
    }
  }
}
