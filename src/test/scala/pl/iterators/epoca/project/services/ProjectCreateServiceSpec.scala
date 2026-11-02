package pl.iterators.epoca.project.services

import pl.iterators.epoca.client.domain.Client
import pl.iterators.epoca.client.repositories.ClientRepository
import pl.iterators.epoca.client.repositories.ClientRepository.ClientFilters
import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationSlug, OrganizationUserId, OrganizationUserRole}
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.project.domain.ProjectDomain.ProjectRole
import pl.iterators.epoca.project.domain.{Project, ProjectUser}
import pl.iterators.epoca.project.repositories.ProjectRepository.ProjectFilters
import pl.iterators.epoca.project.repositories.ProjectUserRepository.ProjectUserNotPermitted
import pl.iterators.epoca.project.repositories.{ProjectRepository, ProjectUserRepository}
import pl.iterators.epoca.project.services.ProjectCreateService.ProjectCreateRequest
import pl.iterators.epoca.project.services.ProjectCreateService.ProjectCreateResult._
import pl.iterators.epoca.project.services.ProjectDtoService.{ProjectClientDto, ProjectDto}
import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.domain.UserDomain.UserAccountType
import pl.iterators.epoca.utils.ServiceSpec
import slick.dbio.DBIO

class ProjectCreateServiceSpec extends ServiceSpec {
  lazy val organizationGenerator: AllGenerators[Organization]    = allGenerators[Organization]
  lazy val orgSlugGenerator: AllGenerators[OrganizationSlug]     = allGenerators[OrganizationSlug]
  lazy val clientGenerator: AllGenerators[Client]                = allGenerators[Client]
  lazy val projectGenerator: AllGenerators[Project]              = allGenerators[Project]
  lazy val projectUserGenerator: AllGenerators[ProjectUser]      = allGenerators[ProjectUser]
  lazy val userGenerator: AllGenerators[User]                    = allGenerators[User]
  lazy val requestGenerator: AllGenerators[ProjectCreateRequest] = allGenerators[ProjectCreateRequest]

  trait TestCase extends BaseScope {
    val projectRepository: ProjectRepository                   = vmock[ProjectRepository]
    val organizationUserRepository: OrganizationUserRepository = vmock[OrganizationUserRepository]
    val organizationRepository: OrganizationRepository         = vmock[OrganizationRepository]
    val clientRepository: ClientRepository                     = vmock[ClientRepository]
    val projectUserRepository: ProjectUserRepository           = vmock[ProjectUserRepository]
    val projectDtoService: ProjectDtoService                   = vmock[ProjectDtoService]
    val sut: ProjectCreateService                              = wire[ProjectCreateService]
  }

  "create" should {
    "return OrganizationNotFound when admin tries to create project in non existing organization" in new TestCase {
      val orgSlug = orgSlugGenerator.normal.generate
      val request = requestGenerator.normal.generate

      when(organizationRepository.find(any)).thenReturn(None.asDBIO)

      sut.create(authAdmin, orgSlug, request).asserting(_ shouldEqual OrganizationNotFound)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
    }

    "return NoAccessToOrganization when user not from organization tries to create project" in new TestCase {
      val organization = organizationGenerator.normal.generate.copy(archivedAt = None)
      val orgSlug      = orgSlugGenerator.normal.generate
      val request      = requestGenerator.normal.generate

      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(any)).thenReturn(None.asDBIO)

      sut.create(authUser, orgSlug, request).asserting(_ shouldEqual NoAccessToOrganization)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
      verify(organizationUserRepository).find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id)))
    }

    "return NoAccessToOrganization when organization employee tries to create new project" in new TestCase {
      val organization = organizationGenerator.normal.generate.copy(archivedAt = None)
      val orgSlug      = orgSlugGenerator.normal.generate
      val request      = requestGenerator.normal.generate
      val orgUser      = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, Seq(OrganizationUserRole.Employee), None)

      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(any)).thenReturn(Some(orgUser).asDBIO)

      sut.create(authUser, orgSlug, request).asserting(_ shouldEqual NoAccessToOrganization)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
      verify(organizationUserRepository).find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id)))
    }

    "return ClientNotFound when user tries to create project for non existing client" in new TestCase {
      val organization = organizationGenerator.normal.generate.copy(archivedAt = None)
      val orgSlug      = orgSlugGenerator.normal.generate
      val client       = clientGenerator.normal.generate.copy(archivedAt = None)
      val request      = requestGenerator.normal.generate.copy(clientId = client.id)
      val orgUser      = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, Seq(OrganizationUserRole.Manager), None)

      when(organizationUserRepository.find(any)).thenReturn(Some(orgUser).asDBIO)
      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(clientRepository.lockForMutation(any)).thenReturn(().asDBIO)
      when(clientRepository.find(any)).thenReturn(None.asDBIO)

      sut.create(authUser, orgSlug, request).asserting(_ shouldEqual ClientNotFound)

      verify(organizationUserRepository).find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id)))
      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
      val ordered = inOrder(clientRepository)
      ordered.verify(clientRepository).lockForMutation(client.id)
      ordered.verify(clientRepository).find(ClientFilters(id = Some(client.id), organizationIds = Some(Seq(organization.id))))

    }

    "return ProjectNameNotUnique if name in request is already in use" in new TestCase {
      val organization = organizationGenerator.normal.generate.copy(archivedAt = None)
      val orgSlug      = orgSlugGenerator.normal.generate
      val client       = clientGenerator.normal.generate.copy(archivedAt = None)
      val request      = requestGenerator.normal.generate.copy(clientId = client.id)
      val project      = projectGenerator.normal.generate.copy(archivedAt = None, organizationId = organization.id)
      val orgUser      = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, Seq(OrganizationUserRole.Manager), None)

      when(organizationUserRepository.find(any)).thenReturn(Some(orgUser).asDBIO)
      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(clientRepository.lockForMutation(any)).thenReturn(().asDBIO)
      when(clientRepository.find(any)).thenReturn(Some(client).asDBIO)
      when(projectRepository.find(any)).thenReturn(Some(project).asDBIO)

      sut.create(authUser, orgSlug, request).asserting(_ shouldEqual ProjectNameNotUnique)

      verify(organizationUserRepository).find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id)))
      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
      val ordered = inOrder(clientRepository)
      ordered.verify(clientRepository).lockForMutation(client.id)
      ordered.verify(clientRepository).find(ClientFilters(id = Some(client.id), organizationIds = Some(Seq(organization.id))))
      verify(projectRepository).find(ProjectFilters(organizationIds = Some(Seq(organization.id)), name = Some(request.name)))
    }

    "return ProjectUsersHaveNoAccessToOrganization where one of projectUsers has no access to organization" in new TestCase {
      val organization   = organizationGenerator.normal.generate.copy(archivedAt = None)
      val orgSlug        = orgSlugGenerator.normal.generate
      val client         = clientGenerator.normal.generate.copy(archivedAt = None)
      val request        = requestGenerator.normal.generate.copy(clientId = client.id)
      val projectUserIds = (request.ownerIds ++ request.memberIds).toSeq
      val orgUser        = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, Seq(OrganizationUserRole.Manager), None)

      when(organizationUserRepository.find(any)).thenReturn(Some(orgUser).asDBIO)
      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(clientRepository.lockForMutation(any)).thenReturn(().asDBIO)
      when(clientRepository.find(any)).thenReturn(Some(client).asDBIO)
      when(projectRepository.find(any)).thenReturn(None.asDBIO)
      when(organizationUserRepository.list(any)).thenReturn(Seq().asDBIO)

      sut.create(authUser, orgSlug, request).asserting(_ shouldEqual ProjectUsersHaveNoAccessToOrganization)

      verify(organizationUserRepository).find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id)))
      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
      val ordered = inOrder(clientRepository)
      ordered.verify(clientRepository).lockForMutation(client.id)
      ordered.verify(clientRepository).find(ClientFilters(id = Some(client.id), organizationIds = Some(Seq(organization.id))))
      verify(projectRepository).find(ProjectFilters(organizationIds = Some(Seq(organization.id)), name = Some(request.name)))
      verify(organizationUserRepository).list(OrganizationUserFilters(organizationId = Some(organization.id), userIds = Some(projectUserIds)))
      verify(projectRepository, times(0)).insert(any)
    }

    "reject a service account project member before inserting the project" in new TestCase {
      val organization   = organizationGenerator.normal.generate.copy(archivedAt = None)
      val orgSlug        = orgSlugGenerator.normal.generate
      val client         = clientGenerator.normal.generate.copy(archivedAt = None)
      val serviceAccount = userGenerator.normal.generate.copy(accountType = UserAccountType.ServiceAccount, archivedAt = None)
      val request        = requestGenerator.normal.generate.copy(clientId = client.id, ownerIds = Set.empty, memberIds = Set(serviceAccount.id))

      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(clientRepository.lockForMutation(any)).thenReturn(().asDBIO)
      when(clientRepository.find(any)).thenReturn(Some(client).asDBIO)
      when(projectRepository.find(any)).thenReturn(None.asDBIO)
      when(organizationUserRepository.list(any)).thenReturn(Nil.asDBIO)

      sut.create(authAdmin, orgSlug, request).asserting(_ shouldEqual ProjectUsersHaveNoAccessToOrganization)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
      val ordered = inOrder(clientRepository)
      ordered.verify(clientRepository).lockForMutation(client.id)
      ordered.verify(clientRepository).find(ClientFilters(id = Some(client.id), organizationIds = Some(Seq(organization.id))))
      verify(projectRepository).find(ProjectFilters(organizationIds = Some(Seq(organization.id)), name = Some(request.name)))
      verify(organizationUserRepository)
        .list(OrganizationUserFilters(organizationId = Some(organization.id), userIds = Some(Seq(serviceAccount.id))))
      verify(projectRepository, times(0)).insert(any)
    }

    "map a repository ineligible-user race to the existing project-user access result" in new TestCase {
      val organization = organizationGenerator.normal.generate.copy(archivedAt = None)
      val client       = clientGenerator.normal.generate.copy(archivedAt = None)
      val human        = userGenerator.normal.generate.copy(accountType = UserAccountType.Human, archivedAt = None)
      val request      = requestGenerator.normal.generate.copy(clientId = client.id, ownerIds = Set(human.id), memberIds = Set.empty)
      val project      = projectGenerator.normal.generate.copy(archivedAt = None, organizationId = organization.id)
      val membership   = OrganizationUser(OrganizationUserId.random, organization.id, human.id, Nil, None)

      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(clientRepository.lockForMutation(any)).thenReturn(().asDBIO)
      when(clientRepository.find(any)).thenReturn(Some(client).asDBIO)
      when(projectRepository.find(any)).thenReturn(None.asDBIO)
      when(organizationUserRepository.list(any)).thenReturn(Seq(membership).asDBIO)
      when(projectRepository.insert(any)).thenReturn(project.asDBIO)
      when(projectUserRepository.insert(any: Seq[ProjectUser])).thenReturn(DBIO.failed(ProjectUserNotPermitted))

      sut.create(authAdmin, organization.slug, request).asserting(_ shouldEqual ProjectUsersHaveNoAccessToOrganization)

      verify(organizationRepository).find(any)
      verify(clientRepository).lockForMutation(any)
      verify(clientRepository).find(any)
      verify(projectRepository).find(any)
      verify(organizationUserRepository).list(any)
      verify(projectRepository).insert(any)
      verify(projectUserRepository).insert(any: Seq[ProjectUser])
    }

    "return Created with new project for user assigned to existing organization" in new TestCase {
      val organization   = organizationGenerator.normal.generate.copy(archivedAt = None)
      val orgSlug        = orgSlugGenerator.normal.generate
      val client         = clientGenerator.normal.generate.copy(archivedAt = None)
      val request        = requestGenerator.normal.generate.copy(clientId = client.id)
      val project        = projectGenerator.normal.generate.copy(archivedAt = None, organizationId = organization.id)
      val projectUserIds = (request.ownerIds ++ request.memberIds).toSeq
      val organizationUsers =
        projectUserIds.map(userId => OrganizationUser(OrganizationUserId.random, organization.id, userId, Seq(OrganizationUserRole.Manager), None))
      val projectUsers = projectUserIds.map(userId => projectUserGenerator.normal.generate.copy(userId = userId, projectId = project.id))
      val projectDto   = ProjectDto.from(project, Some(ProjectClientDto.from(client)), Seq())
      val orgUser      = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, Seq(OrganizationUserRole.Manager), None)

      when(organizationUserRepository.find(any)).thenReturn(Some(orgUser).asDBIO)
      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(clientRepository.lockForMutation(any)).thenReturn(().asDBIO)
      when(clientRepository.find(any)).thenReturn(Some(client).asDBIO)
      when(projectRepository.find(any)).thenReturn(None.asDBIO)
      when(projectRepository.insert(any)).thenReturn(project.asDBIO)
      when(organizationUserRepository.list(any)).thenReturn(organizationUsers.asDBIO)
      when(projectUserRepository.insert(any: Seq[ProjectUser])).thenReturn(projectUsers.asDBIO)
      when(projectDtoService.prepareFindDto(project)).thenReturn(projectDto.asDBIO)

      sut.create(authUser, orgSlug, request).asserting(_ shouldEqual Created(projectDto))

      verify(organizationUserRepository).find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id)))
      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
      val ordered = inOrder(clientRepository)
      ordered.verify(clientRepository).lockForMutation(client.id)
      ordered.verify(clientRepository).find(ClientFilters(id = Some(client.id), organizationIds = Some(Seq(organization.id))))
      verify(projectRepository).find(ProjectFilters(organizationIds = Some(Seq(organization.id)), name = Some(request.name)))
      verify(projectRepository).insert(argThat { (project: Project) =>
        project.organizationId == organization.id &&
        project.name == request.name &&
        project.createdBy == authUser.id &&
        project.modifiedBy == authUser.id &&
        project.archivedAt.isEmpty
      })
      verify(organizationUserRepository).list(OrganizationUserFilters(organizationId = Some(organization.id), userIds = Some(projectUserIds)))
      verify(projectUserRepository).insert(argThat { (users: Seq[ProjectUser]) =>
        users.map(_.projectId).distinct.length <= 1
      })
      verify(projectDtoService).prepareFindDto(project)
    }

    "return Created with new project for user assigned to existing organization (empty users, creator assigned as owner)" in new TestCase {
      val organization   = organizationGenerator.normal.generate.copy(archivedAt = None)
      val orgSlug        = orgSlugGenerator.normal.generate
      val client         = clientGenerator.normal.generate.copy(archivedAt = None)
      val request        = requestGenerator.normal.generate.copy(clientId = client.id, ownerIds = Set(), memberIds = Set())
      val project        = projectGenerator.normal.generate.copy(archivedAt = None, organizationId = organization.id)
      val projectUserIds = (request.ownerIds ++ request.memberIds).toSeq
      val projectUser    = projectUserGenerator.normal.generate.copy(userId = authUser.id, role = ProjectRole.Owner)
      val projectDto     = ProjectDto.from(project, Some(ProjectClientDto.from(client)), Seq())
      val orgUser        = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, Seq(OrganizationUserRole.Manager), None)

      when(organizationUserRepository.find(any)).thenReturn(Some(orgUser).asDBIO)
      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(clientRepository.lockForMutation(any)).thenReturn(().asDBIO)
      when(clientRepository.find(any)).thenReturn(Some(client).asDBIO)
      when(projectRepository.find(any)).thenReturn(None.asDBIO)
      when(projectRepository.insert(any)).thenReturn(project.asDBIO)
      when(organizationUserRepository.list(any)).thenReturn(Nil.asDBIO)
      when(projectUserRepository.insert(any: Seq[ProjectUser])).thenReturn(Seq(projectUser).asDBIO)
      when(projectDtoService.prepareFindDto(project)).thenReturn(projectDto.asDBIO)

      sut.create(authUser, orgSlug, request).asserting(_ shouldEqual Created(projectDto))

      verify(organizationUserRepository).find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id)))
      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
      val ordered = inOrder(clientRepository)
      ordered.verify(clientRepository).lockForMutation(client.id)
      ordered.verify(clientRepository).find(ClientFilters(id = Some(client.id), organizationIds = Some(Seq(organization.id))))
      verify(projectRepository).find(ProjectFilters(organizationIds = Some(Seq(organization.id)), name = Some(request.name)))
      verify(projectRepository).insert(argThat { (project: Project) =>
        project.organizationId == organization.id &&
        project.name == request.name &&
        project.createdBy == authUser.id &&
        project.modifiedBy == authUser.id &&
        project.archivedAt.isEmpty
      })
      verify(organizationUserRepository).list(OrganizationUserFilters(organizationId = Some(organization.id), userIds = Some(projectUserIds)))
      verify(projectUserRepository).insert(argThat { (projectUser: Seq[ProjectUser]) =>
        projectUser.head.userId == authUser.id &&
        projectUser.head.role == ProjectRole.Owner
      })
      verify(projectDtoService).prepareFindDto(project)
    }

    "return Created with new project for admin" in new TestCase {
      val organization      = organizationGenerator.normal.generate.copy(archivedAt = None)
      val orgSlug           = orgSlugGenerator.normal.generate
      val client            = clientGenerator.normal.generate.copy(archivedAt = None)
      val request           = requestGenerator.normal.generate.copy(clientId = client.id)
      val project           = projectGenerator.normal.generate.copy(archivedAt = None, organizationId = organization.id)
      val projectUserIds    = (request.ownerIds ++ request.memberIds).toSeq
      val organizationUsers = projectUserIds.map(userId => OrganizationUser(OrganizationUserId.random, organization.id, userId, Nil, None))
      val projectUsers      = projectUserIds.map(userId => projectUserGenerator.normal.generate.copy(userId = userId, projectId = project.id))
      val projectDto        = ProjectDto.from(project, Some(ProjectClientDto.from(client)), Seq())

      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(clientRepository.lockForMutation(any)).thenReturn(().asDBIO)
      when(clientRepository.find(any)).thenReturn(Some(client).asDBIO)
      when(projectRepository.find(any)).thenReturn(None.asDBIO)
      when(projectRepository.insert(any)).thenReturn(project.asDBIO)
      when(organizationUserRepository.list(any)).thenReturn(organizationUsers.asDBIO)
      when(projectUserRepository.insert(any: Seq[ProjectUser])).thenReturn(projectUsers.asDBIO)
      when(projectDtoService.prepareFindDto(project)).thenReturn(projectDto.asDBIO)

      sut.create(authAdmin, orgSlug, request).asserting(_ shouldEqual Created(projectDto))

      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
      val ordered = inOrder(clientRepository)
      ordered.verify(clientRepository).lockForMutation(client.id)
      ordered.verify(clientRepository).find(ClientFilters(id = Some(client.id), organizationIds = Some(Seq(organization.id))))
      verify(projectRepository).find(ProjectFilters(organizationIds = Some(Seq(organization.id)), name = Some(request.name)))
      verify(projectRepository).insert(argThat { (project: Project) =>
        project.organizationId == organization.id &&
        project.name == request.name &&
        project.description == request.description &&
        project.clientId == request.clientId &&
        project.createdBy == authAdmin.id &&
        project.modifiedBy == authAdmin.id &&
        project.createdAt == project.modifiedAt &&
        project.archivedAt.isEmpty &&
        project.archivedBy.isEmpty
      })
      verify(organizationUserRepository).list(OrganizationUserFilters(organizationId = Some(organization.id), userIds = Some(projectUserIds)))
      verify(projectUserRepository).insert(argThat { (users: Seq[ProjectUser]) =>
        users.map(_.projectId).distinct.length <= 1
      })
      verify(projectDtoService).prepareFindDto(project)
    }
  }
}
