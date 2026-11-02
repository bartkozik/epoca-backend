package pl.iterators.epoca.client.services

import pl.iterators.epoca.client.domain.Client
import pl.iterators.epoca.client.domain.ClientDomain.ClientProjectCount
import pl.iterators.epoca.client.services.ClientDtoService.ClientDto
import pl.iterators.epoca.project.domain.Project
import pl.iterators.epoca.project.repositories.ProjectRepository
import pl.iterators.epoca.project.repositories.ProjectRepository.ProjectFilters
import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.utils.ServiceSpec

class ClientDtoServiceSpec extends ServiceSpec {
  lazy val clientGenerator: AllGenerators[Client]         = allGenerators[Client]
  lazy val clientsGenerator: AllGenerators[Seq[Client]]   = allGenerators[Seq[Client]]
  lazy val projectsGenerator: AllGenerators[Seq[Project]] = allGenerators[Seq[Project]]

  trait TestCase extends BaseScope {
    val projectRepository: ProjectRepository = vmock[ProjectRepository]

    val sut: ClientDtoService = wire[ClientDtoService]
  }

  "prepareDto" should {
    "return Seq of ClientDto for clients" in new TestCase {
      val client         = clientGenerator.normal.generate
      val clients        = clientsGenerator.normal.generate
      val clientProjects = projectsGenerator.normal.generate.map(_.copy(clientId = client.id))
      val projects       = projectsGenerator.normal.generate

      val composedClients  = clients.appended(client)
      val composedProjects = clientProjects ++ projects

      when(projectRepository.list(any)).thenReturn(composedProjects.asDBIO)

      val expected = composedClients.map { client =>
        ClientDto(
          id = client.id,
          name = client.name,
          color = client.color,
          texture = client.texture,
          logo = client.logo,
          organizationId = client.organizationId,
          `type` = client.`type`,
          projectCount = ClientProjectCount(composedProjects.count(_.clientId == client.id)),
          createdAt = client.createdAt,
          createdBy = client.createdBy,
          modifiedAt = client.modifiedAt,
          modifiedBy = client.modifiedBy,
          archivedAt = client.archivedAt
        )
      }

      transactor.execute(sut.prepareDtos(composedClients, None)).asserting(_ shouldEqual expected)

      verify(projectRepository).list(ProjectFilters(clientIds = Some(composedClients.map(_.id))))
    }
    "return Seq of ClientDto for clients with project count restricted by userId" in new TestCase {
      val client         = clientGenerator.normal.generate
      val clients        = clientsGenerator.normal.generate
      val clientProjects = projectsGenerator.normal.generate.map(_.copy(clientId = client.id))
      val projects       = projectsGenerator.normal.generate

      val composedClients  = clients.appended(client)
      val composedProjects = clientProjects ++ projects

      when(projectRepository.list(any)).thenReturn(composedProjects.asDBIO)

      val expected = composedClients.map { client =>
        ClientDto(
          id = client.id,
          name = client.name,
          color = client.color,
          texture = client.texture,
          logo = client.logo,
          organizationId = client.organizationId,
          `type` = client.`type`,
          projectCount = ClientProjectCount(composedProjects.count(_.clientId == client.id)),
          createdAt = client.createdAt,
          createdBy = client.createdBy,
          modifiedAt = client.modifiedAt,
          modifiedBy = client.modifiedBy,
          archivedAt = client.archivedAt
        )
      }

      transactor.execute(sut.prepareDtos(composedClients, Some(authUser.id))).asserting(_ shouldEqual expected)

      verify(projectRepository).list(ProjectFilters(clientIds = Some(composedClients.map(_.id)), userId = Some(authUser.id)))
    }
  }
}
