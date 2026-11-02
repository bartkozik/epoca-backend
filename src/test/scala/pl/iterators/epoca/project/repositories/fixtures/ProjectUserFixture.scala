package pl.iterators.epoca.project.repositories.fixtures

import pl.iterators.epoca.project.domain.{Project, ProjectUser}
import pl.iterators.epoca.project.repositories.ProjectUserRepositoryImpl
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.utils.Generators
import slick.dbio.DBIO

import scala.concurrent.ExecutionContext

trait ProjectUserFixture {
  deps: Generators =>
  implicit val ec: ExecutionContext

  private val _dbioProjectUserRepository: ProjectUserRepositoryImpl = new ProjectUserRepositoryImpl()

  private val _projectGenerator = allGenerators[ProjectUser].normal
  def withProjectUser(
    user: User,
    project: Project,
    obj: ProjectUser = _projectGenerator.generate.copy(archivedAt = None)
  ): DBIO[ProjectUser] =
    _dbioProjectUserRepository.insert(obj.copy(projectId = project.id, userId = user.id))
}
