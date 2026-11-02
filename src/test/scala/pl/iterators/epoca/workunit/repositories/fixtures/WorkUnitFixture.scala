package pl.iterators.epoca.workunit.repositories.fixtures

import pl.iterators.epoca.organization.domain.Organization
import pl.iterators.epoca.project.domain.Project
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.utils.Generators
import pl.iterators.epoca.workunit.domain.WorkUnit
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.{WorkUnitDuration, WorkUnitEntryType}
import pl.iterators.epoca.workunit.repositories.{WorkUnitRepository, WorkUnitRepositoryImpl}
import slick.dbio.DBIO

import java.time.Duration
import scala.concurrent.ExecutionContext

trait WorkUnitFixture {
  deps: Generators =>
  implicit val ec: ExecutionContext

  private val _dbioWorkUnitRepository: WorkUnitRepository = new WorkUnitRepositoryImpl()
  private val _workUnitGenerator                          = allGenerators[WorkUnit].normal
  private def generatedWorklog: WorkUnit =
    _workUnitGenerator.generate.copy(entryType = WorkUnitEntryType.Worklog, duration = WorkUnitDuration(Duration.ofMinutes(30)))

  def withWorkUnit(
    organization: Organization,
    user: User,
    project: Project
  ): DBIO[WorkUnit] =
    withWorkUnit(organization, user, project, generatedWorklog)

  def withWorkUnit(
    organization: Organization,
    user: User,
    project: Project,
    obj: WorkUnit
  ): DBIO[WorkUnit] = {
    val workUnit = obj.copy(organizationId = organization.id, userId = user.id, projectId = project.id, archivedAt = None)
    _dbioWorkUnitRepository.insert(workUnit)
  }

}
