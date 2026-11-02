package pl.iterators.epoca.project.services

import pl.iterators.epoca.client.domain.Client
import pl.iterators.epoca.client.domain.ClientDomain.ClientName
import pl.iterators.epoca.client.repositories.ClientRepository
import pl.iterators.epoca.client.repositories.ClientRepository.ClientFilters
import pl.iterators.epoca.project.domain.ProjectDomain.ProjectUserCount
import pl.iterators.epoca.project.domain.{Project, ProjectUser}
import pl.iterators.epoca.project.repositories.ProjectUserRepository
import pl.iterators.epoca.project.repositories.ProjectUserRepository.ProjectUserFilters
import pl.iterators.epoca.project.services.ProjectDtoService.{ProjectClientDto, ProjectDto, ProjectListDto, ProjectUserDto}
import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.repositories.UserRepository
import pl.iterators.epoca.user.repositories.UserRepository.UserFilters
import pl.iterators.epoca.utils.ServiceSpec

class ProjectDtoServiceSpec extends ServiceSpec {
  lazy val projectsGenerator: AllGenerators[Seq[Project]]         = allGenerators[Seq[Project]]
  lazy val projectGenerator: AllGenerators[Project]               = allGenerators[Project]
  lazy val clientsGenerator: AllGenerators[Seq[Client]]           = allGenerators[Seq[Client]]
  lazy val clientGenerator: AllGenerators[Client]                 = allGenerators[Client]
  lazy val projectUsersGenerator: AllGenerators[Seq[ProjectUser]] = allGenerators[Seq[ProjectUser]]
  lazy val userGenerator: AllGenerators[User]                     = allGenerators[User]

  trait TestCase extends BaseScope {
    val clientRepository: ClientRepository           = vmock[ClientRepository]
    val projectUserRepository: ProjectUserRepository = vmock[ProjectUserRepository]
    val userRepository: UserRepository               = vmock[UserRepository]

    val sut: ProjectDtoService = wire[ProjectDtoService]
  }

  "prepareListDto" should {
    "return Seq of ProjectListDto for projects" in new TestCase {
      val project       = projectGenerator.normal.generate
      val projects      = projectsGenerator.normal.generate
      val clients       = clientsGenerator.normal.generate
      val projectUsers  = projectUsersGenerator.normal.generate.map(_.copy(projectId = project.id))
      val projectsUsers = projectUsersGenerator.normal.generate

      val composedProjects     = projects.appended(project)
      val composedProjectUsers = projectsUsers ++ projectUsers

      when(clientRepository.list(any)).thenReturn(clients.asDBIO)
      when(projectUserRepository.list(any)).thenReturn(composedProjectUsers.asDBIO)

      val expected = composedProjects.map { project =>
        ProjectListDto(
          id = project.id,
          name = project.name,
          description = project.description,
          client = clients.find(_.id == project.clientId).map { client =>
            ProjectClientDto(client.id, client.name, client.color, client.`type`, client.texture)
          },
          userCount = ProjectUserCount(composedProjectUsers.count(_.projectId == project.id)),
          userIds = composedProjectUsers.filter(_.projectId == project.id).map(_.userId),
          organizationId = project.organizationId,
          createdAt = project.createdAt,
          createdBy = project.createdBy,
          modifiedAt = project.modifiedAt,
          modifiedBy = project.modifiedBy,
          archivedAt = project.archivedAt
        )
      }

      transactor.execute(sut.prepareListDtos(composedProjects)).asserting(_ shouldEqual expected)

      verify(clientRepository).list(ClientFilters(ids = Some(composedProjects.map(_.clientId)), includeArchived = true))
      verify(projectUserRepository).list(ProjectUserFilters(projectIds = Some(composedProjects.map(_.id))))
    }

    "return Seq of ProjectListDto for projects (no Clients)" in new TestCase {
      val project       = projectGenerator.normal.generate
      val projects      = projectsGenerator.normal.generate
      val projectUsers  = projectUsersGenerator.normal.generate.map(_.copy(projectId = project.id))
      val projectsUsers = projectUsersGenerator.normal.generate

      val composedProjects     = projects.appended(project)
      val composedProjectUsers = projectsUsers ++ projectUsers

      when(clientRepository.list(any)).thenReturn(Seq().asDBIO)
      when(projectUserRepository.list(any)).thenReturn(composedProjectUsers.asDBIO)

      val expected = composedProjects.map { project =>
        ProjectListDto(
          id = project.id,
          name = project.name,
          description = project.description,
          client = None,
          userCount = ProjectUserCount(composedProjectUsers.count(_.projectId == project.id)),
          userIds = composedProjectUsers.filter(_.projectId == project.id).map(_.userId),
          organizationId = project.organizationId,
          createdAt = project.createdAt,
          createdBy = project.createdBy,
          modifiedAt = project.modifiedAt,
          modifiedBy = project.modifiedBy,
          archivedAt = project.archivedAt
        )
      }

      transactor.execute(sut.prepareListDtos(composedProjects)).asserting(_ shouldEqual expected)

      verify(clientRepository).list(ClientFilters(ids = Some(composedProjects.map(_.clientId)), includeArchived = true))
      verify(projectUserRepository).list(ProjectUserFilters(projectIds = Some(composedProjects.map(_.id))))
    }

    "return Seq of ProjectListDto for projects (no ProjectUsers)" in new TestCase {
      val projects = projectsGenerator.normal.generate
      val clients  = clientsGenerator.normal.generate

      when(clientRepository.list(any)).thenReturn(clients.asDBIO)
      when(projectUserRepository.list(any)).thenReturn(Seq().asDBIO)

      val expected = projects.map { project =>
        ProjectListDto(
          id = project.id,
          name = project.name,
          description = project.description,
          client = clients.find(_.id == project.clientId).map { client =>
            ProjectClientDto(client.id, client.name, client.color, client.`type`, client.texture)
          },
          userCount = ProjectUserCount(0),
          userIds = Nil,
          organizationId = project.organizationId,
          createdAt = project.createdAt,
          createdBy = project.createdBy,
          modifiedAt = project.modifiedAt,
          modifiedBy = project.modifiedBy,
          archivedAt = project.archivedAt
        )
      }

      transactor.execute(sut.prepareListDtos(projects)).asserting(_ shouldEqual expected)

      verify(clientRepository).list(ClientFilters(ids = Some(projects.map(_.clientId)), includeArchived = true))
      verify(projectUserRepository).list(ProjectUserFilters(projectIds = Some(projects.map(_.id))))
    }
    "return Seq of ProjectListDto for projects (no ProjectUsers)" in new TestCase {
      val projects = projectsGenerator.normal.generate
      val clients  = clientsGenerator.normal.generate

      when(clientRepository.list(any)).thenReturn(clients.asDBIO)
      when(projectUserRepository.list(any)).thenReturn(Seq().asDBIO)

      val expected = projects.map { project =>
        ProjectListDto(
          id = project.id,
          name = project.name,
          description = project.description,
          client = clients.find(_.id == project.clientId).map { client =>
            ProjectClientDto(client.id, client.name, client.color, client.`type`, client.texture)
          },
          userCount = ProjectUserCount(0),
          userIds = Nil,
          organizationId = project.organizationId,
          createdAt = project.createdAt,
          createdBy = project.createdBy,
          modifiedAt = project.modifiedAt,
          modifiedBy = project.modifiedBy,
          archivedAt = project.archivedAt
        )
      }

      transactor.execute(sut.prepareListDtos(projects)).asserting(_ shouldEqual expected)

      verify(clientRepository).list(ClientFilters(ids = Some(projects.map(_.clientId)), includeArchived = true))
      verify(projectUserRepository).list(ProjectUserFilters(projectIds = Some(projects.map(_.id))))
    }

  }

  "prepareFindDto" should {
    "return ProjectDto for project" in new TestCase {
      val project      = projectGenerator.normal.generate
      val client       = clientGenerator.normal.generate
      val projectUsers = Seq(allGenerators[ProjectUser].normal.generate)
      val users        = projectUsers.map(projectUser => userGenerator.normal.generate.copy(id = projectUser.userId))

      when(clientRepository.find(any)).thenReturn(Some(client).asDBIO)
      when(projectUserRepository.list(any)).thenReturn(projectUsers.asDBIO)
      when(userRepository.list(any)).thenReturn(users.asDBIO)

      val expected =
        ProjectDto(
          id = project.id,
          name = project.name,
          description = project.description,
          client = Some(ProjectClientDto(client.id, client.name, client.color, client.`type`, client.texture)),
          projectUsers = users.zip(projectUsers).map { case (user, projectUser) =>
            ProjectUserDto(user.id, projectUser.role, user.firstName, user.lastName, user.avatar, projectUser.archivedAt)
          },
          organizationId = project.organizationId,
          createdAt = project.createdAt,
          createdBy = project.createdBy,
          modifiedAt = project.modifiedAt,
          modifiedBy = project.modifiedBy,
          archivedAt = project.archivedAt,
          archivedBy = project.archivedBy
        )

      transactor.execute(sut.prepareFindDto(project)).asserting(_ shouldEqual expected)

      verify(clientRepository).find(ClientFilters(id = Some(project.clientId), includeArchived = true))
      verify(projectUserRepository).list(ProjectUserFilters(projectIds = Some(Seq(project.id))))
      verify(userRepository).list(UserFilters(ids = Some(users.map(_.id))))
    }

    "return ProjectDto for project (no Client)" in new TestCase {
      val project      = projectGenerator.normal.generate
      val projectUsers = projectUsersGenerator.normal.generate
      val users        = projectUsers.map(projectUser => userGenerator.normal.generate.copy(id = projectUser.userId))

      when(clientRepository.find(any)).thenReturn(None.asDBIO)
      when(projectUserRepository.list(any)).thenReturn(projectUsers.asDBIO)
      when(userRepository.list(any)).thenReturn(users.asDBIO)

      val expected =
        ProjectDto(
          id = project.id,
          name = project.name,
          description = project.description,
          client = None,
          projectUsers = users.zip(projectUsers).map { case (user, projectUser) =>
            ProjectUserDto(user.id, projectUser.role, user.firstName, user.lastName, user.avatar, projectUser.archivedAt)
          },
          organizationId = project.organizationId,
          createdAt = project.createdAt,
          createdBy = project.createdBy,
          modifiedAt = project.modifiedAt,
          modifiedBy = project.modifiedBy,
          archivedAt = project.archivedAt,
          archivedBy = project.archivedBy
        )

      transactor.execute(sut.prepareFindDto(project)).asserting(_ shouldEqual expected)

      verify(clientRepository).find(ClientFilters(id = Some(project.clientId), includeArchived = true))
      verify(projectUserRepository).list(ProjectUserFilters(projectIds = Some(Seq(project.id))))
      verify(userRepository).list(UserFilters(ids = Some(users.map(_.id))))
    }

    "return ProjectDto for project (no ProjectUsers)" in new TestCase {
      val project = projectGenerator.normal.generate
      val client  = clientGenerator.normal.generate

      when(clientRepository.find(any)).thenReturn(Some(client).asDBIO)
      when(projectUserRepository.list(any)).thenReturn(Seq().asDBIO)
      when(userRepository.list(any)).thenReturn(Seq().asDBIO)

      val expected =
        ProjectDto(
          id = project.id,
          name = project.name,
          description = project.description,
          client = Some(ProjectClientDto(client.id, client.name, client.color, client.`type`, client.texture)),
          projectUsers = Seq[ProjectUserDto](),
          organizationId = project.organizationId,
          createdAt = project.createdAt,
          createdBy = project.createdBy,
          modifiedAt = project.modifiedAt,
          modifiedBy = project.modifiedBy,
          archivedAt = project.archivedAt,
          archivedBy = project.archivedBy
        )

      transactor.execute(sut.prepareFindDto(project)).asserting(_ shouldEqual expected)

      verify(clientRepository).find(ClientFilters(id = Some(project.clientId), includeArchived = true))
      verify(projectUserRepository).list(ProjectUserFilters(projectIds = Some(Seq(project.id))))
      verify(userRepository).list(UserFilters(ids = Some(Seq())))
    }
  }

  "mapping helpers" should {
    "copy every user value and attach project membership values" in {
      val user        = userGenerator.normal.generate
      val projectUser = projectUsersGenerator.normal.generate.headOption.getOrElse(allGenerators[ProjectUser].normal.generate)
      ProjectUserDto.from(user, projectUser) shouldEqual ProjectUserDto(
        id = user.id,
        role = projectUser.role,
        firstName = user.firstName,
        lastName = user.lastName,
        avatar = user.avatar,
        archivedAt = projectUser.archivedAt
      )
    }

    "copy every client value used by project DTOs" in {
      val client = clientGenerator.normal.generate.copy(name = ClientName("expected-client"))

      ProjectClientDto.from(client) shouldEqual ProjectClientDto(
        id = client.id,
        name = client.name,
        color = client.color,
        `type` = client.`type`,
        texture = client.texture
      )
    }
  }
}
