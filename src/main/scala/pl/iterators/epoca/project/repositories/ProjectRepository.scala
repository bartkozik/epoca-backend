package pl.iterators.epoca.project.repositories

import pl.iterators.epoca.client.domain.ClientDomain.ClientId
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId
import pl.iterators.epoca.project.domain.Project
import pl.iterators.epoca.project.domain.ProjectDomain._
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.utils.db.{FilterParamType, PageSizeAndNumber}
import slick.dbio.DBIO

trait ProjectRepository {
  import ProjectRepository._

  def insert(project: Project): DBIO[Project]
  def update(project: Project): DBIO[Option[Project]]
  def find(filter: ProjectFilters): DBIO[Option[Project]]
  def list(filter: ProjectFilters): DBIO[Seq[Project]]
  def lockOrdered(filter: ProjectFilters): DBIO[Seq[Project]]
  def count(filter: ProjectFilters): DBIO[Int]

  /** Acquires a PostgreSQL ROW SHARE lock on project. Must run inside the caller's transaction before locking dependent-table rows.
    */
  def lockTableForReference(): DBIO[Unit]
  def archive(
    filter: ProjectFilters,
    when: ProjectArchivedAt,
    who: UserId
  ): DBIO[Int]
}

object ProjectRepository {

  case class ProjectFilters(
    id: Option[ProjectId] = None,
    ids: Option[Seq[ProjectId]] = None,
    name: Option[ProjectName] = None,
    organizationIds: Option[Seq[OrganizationId]] = None,
    clientIds: Option[Seq[ClientId]] = None,
    userId: Option[UserId] = None,
    archivedConnectionsUserId: Option[UserId] = None,
    notCreatedBy: Option[UserId] = None,
    includeArchived: Boolean = false,
    forUpdate: Boolean = false,
    pageSizeAndNumber: Option[PageSizeAndNumber] = None)
      extends FilterParamType
}
