package pl.iterators.epoca.project.repositories

import pl.iterators.epoca.project.domain.ProjectDomain.{ProjectId, ProjectRole, ProjectUserArchivedAt, ProjectUserId}
import pl.iterators.epoca.project.domain.ProjectUser
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.utils.db.FilterParamType
import slick.dbio.DBIO

import java.time.Instant

trait ProjectUserRepository {

  import ProjectUserRepository._

  def insert(projectUser: ProjectUser): DBIO[ProjectUser]
  def insert(projectUsers: Seq[ProjectUser]): DBIO[Seq[ProjectUser]]
  def update(projectUser: ProjectUser): DBIO[Option[ProjectUser]]
  def bulkArchiveAt(projectUserIds: Seq[ProjectUserId], at: ProjectUserArchivedAt): DBIO[Int]
  def find(filter: ProjectUserFilters): DBIO[Option[ProjectUser]]
  def list(filter: ProjectUserFilters): DBIO[Seq[ProjectUser]]
  def lockOrdered(filter: ProjectUserFilters): DBIO[Seq[ProjectUser]]
}

object ProjectUserRepository {
  private[epoca] case object ProjectUserNotPermitted extends RuntimeException("project user unavailable")

  case class ProjectUserFilters(
    projectIds: Option[Seq[ProjectId]] = None,
    userIds: Option[Seq[UserId]] = None,
    userId: Option[UserId] = None,
    role: Option[ProjectRole] = None,
    notDeletedAt: Option[Instant] = None,
    includeArchived: Boolean = false,
    forUpdate: Boolean = false)
      extends FilterParamType
}
