package pl.iterators.epoca.project.repositories.tables

import pl.iterators.epoca.project.domain.ProjectDomain._
import pl.iterators.epoca.project.domain.ProjectUser
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import pl.iterators.epoca.utils.db.{BaseTable, IdTable, SoftDeleteTable}
import slick.lifted.ProvenShape

import java.time.Instant

class ProjectUserTable(tag: Tag)
    extends BaseTable[ProjectUser](tag, "project_user")
    with IdTable[ProjectUser, ProjectUserId]
    with SoftDeleteTable[ProjectUser, ProjectUserId] {

  def id: Rep[ProjectUserId]                         = column[ProjectUserId]("id")
  def projectId: Rep[ProjectId]                      = column[ProjectId]("project_id")
  def userId: Rep[UserId]                            = column[UserId]("user_id")
  def role: Rep[ProjectRole]                         = column[ProjectRole]("role")
  def createdAt: Rep[ProjectUserCreatedAt]           = column[ProjectUserCreatedAt]("created_at")
  def archivedAt: Rep[Option[ProjectUserArchivedAt]] = column[Option[ProjectUserArchivedAt]]("archived_at")

  override def deletedAt: Rep[Option[Instant]] = archivedAt

  override def * : ProvenShape[ProjectUser] =
    (id, projectId, userId, role, createdAt, archivedAt) <> ((ProjectUser.apply _).tupled, ProjectUser.unapply _)

}
