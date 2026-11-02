package pl.iterators.epoca.project.repositories.tables

import pl.iterators.epoca.client.domain.ClientDomain.ClientId
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId
import pl.iterators.epoca.project.domain.Project
import pl.iterators.epoca.project.domain.ProjectDomain._
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import pl.iterators.epoca.utils.db.{BaseTable, IdTable, SoftDeleteTable}
import slick.lifted.ProvenShape

import java.time.Instant

class ProjectTable(tag: Tag) extends BaseTable[Project](tag, "project") with IdTable[Project, ProjectId] with SoftDeleteTable[Project, ProjectId] {

  def id: Rep[ProjectId]                           = column[ProjectId]("id")
  def name: Rep[ProjectName]                       = column[ProjectName]("name")
  def description: Rep[Option[ProjectDescription]] = column[Option[ProjectDescription]]("description")
  def organizationId: Rep[OrganizationId]          = column[OrganizationId]("organization_id")
  def clientId: Rep[ClientId]                      = column[ClientId]("client_id")
  def createdAt: Rep[ProjectCreatedAt]             = column[ProjectCreatedAt]("created_at")
  def createdBy: Rep[UserId]                       = column[UserId]("created_by")
  def modifiedAt: Rep[ProjectModifiedAt]           = column[ProjectModifiedAt]("modified_at")
  def modifiedBy: Rep[UserId]                      = column[UserId]("modified_by")
  def archivedAt: Rep[Option[ProjectArchivedAt]]   = column[Option[ProjectArchivedAt]]("archived_at")
  def archivedBy: Rep[Option[UserId]]              = column[Option[UserId]]("archived_by")

  override def deletedAt: Rep[Option[Instant]] = archivedAt

  override def * : ProvenShape[Project] =
    (
      id,
      name,
      description,
      organizationId,
      clientId,
      createdAt,
      createdBy,
      modifiedAt,
      modifiedBy,
      archivedAt,
      archivedBy
    ) <> (Project.tupled, Project.unapply _)

}
