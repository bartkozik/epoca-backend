package pl.iterators.epoca.organization.repositories.tables

import pl.iterators.epoca.organization.domain.Organization
import pl.iterators.epoca.organization.domain.OrganizationDomain._
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import pl.iterators.epoca.utils.db.{BaseTable, IdTable, SoftDeleteTable}
import slick.lifted.ProvenShape

import java.time.Instant

class OrganizationTable(tag: Tag)
    extends BaseTable[Organization](tag, "organization")
    with IdTable[Organization, OrganizationId]
    with SoftDeleteTable[Organization, OrganizationId] {

  def id: Rep[OrganizationId]                         = column[OrganizationId]("id")
  def name: Rep[OrganizationName]                     = column[OrganizationName]("name")
  def slug: Rep[OrganizationSlug]                     = column[OrganizationSlug]("slug")
  def createdAt: Rep[OrganizationCreatedAt]           = column[OrganizationCreatedAt]("created_at")
  def createdBy: Rep[UserId]                          = column[UserId]("created_by")
  def modifiedAt: Rep[OrganizationModifiedAt]         = column[OrganizationModifiedAt]("modified_at")
  def modifiedBy: Rep[UserId]                         = column[UserId]("modified_by")
  def archivedAt: Rep[Option[OrganizationArchivedAt]] = column[Option[OrganizationArchivedAt]]("archived_at")

  override def deletedAt: Rep[Option[Instant]] = archivedAt

  override def * : ProvenShape[Organization] =
    (id, name, slug, createdAt, createdBy, modifiedAt, modifiedBy, archivedAt) <> (Organization.tupled, Organization.unapply _)

}
