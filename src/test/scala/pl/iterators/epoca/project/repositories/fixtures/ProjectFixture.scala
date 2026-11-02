package pl.iterators.epoca.project.repositories.fixtures

import pl.iterators.epoca.client.domain.Client
import pl.iterators.epoca.organization.domain.Organization
import pl.iterators.epoca.project.domain.Project
import pl.iterators.epoca.project.repositories.ProjectRepositoryImpl
import pl.iterators.epoca.utils.Generators
import slick.dbio.DBIO

import scala.concurrent.ExecutionContext

trait ProjectFixture {
  deps: Generators =>
  implicit val ec: ExecutionContext

  private val _dbioProjectRepository: ProjectRepositoryImpl = new ProjectRepositoryImpl()

  private val _projectGenerator = allGenerators[Project].normal
  def withProject(
    organization: Organization,
    client: Client,
    obj: Project = _projectGenerator.generate
  ): DBIO[Project] =
    _dbioProjectRepository.insert(obj.copy(organizationId = organization.id, clientId = client.id))
}
