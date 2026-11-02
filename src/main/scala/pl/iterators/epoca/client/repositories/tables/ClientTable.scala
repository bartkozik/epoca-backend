package pl.iterators.epoca.client.repositories.tables

import pl.iterators.epoca.client.domain.Client
import pl.iterators.epoca.client.domain.ClientDomain._
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import pl.iterators.epoca.utils.db.{BaseTable, IdTable, SoftDeleteTable}
import slick.lifted.ProvenShape

import java.time.Instant

class ClientTable(tag: Tag) extends BaseTable[Client](tag, "client") with IdTable[Client, ClientId] with SoftDeleteTable[Client, ClientId] {

  def id: Rep[ClientId]                         = column[ClientId]("id")
  def name: Rep[ClientName]                     = column[ClientName]("name")
  def color: Rep[ClientColor]                   = column[ClientColor]("color")
  def texture: Rep[ClientTexture]               = column[ClientTexture]("texture")
  def logo: Rep[ClientLogo]                     = column[ClientLogo]("logo")
  def organizationId: Rep[OrganizationId]       = column[OrganizationId]("organization_id")
  def `type`: Rep[ClientType]                   = column[ClientType]("type")
  def createdAt: Rep[ClientCreatedAt]           = column[ClientCreatedAt]("created_at")
  def createdBy: Rep[UserId]                    = column[UserId]("created_by")
  def modifiedAt: Rep[ClientModifiedAt]         = column[ClientModifiedAt]("modified_at")
  def modifiedBy: Rep[UserId]                   = column[UserId]("modified_by")
  def archivedAt: Rep[Option[ClientArchivedAt]] = column[Option[ClientArchivedAt]]("archived_at")
  def archivedBy: Rep[Option[UserId]]           = column[Option[UserId]]("archived_by")

  override def deletedAt: Rep[Option[Instant]] = archivedAt

  override def * : ProvenShape[Client] =
    (
      id,
      name,
      color,
      texture,
      logo,
      organizationId,
      `type`,
      createdAt,
      createdBy,
      modifiedAt,
      modifiedBy,
      archivedAt,
      archivedBy
    ) <> (Client.tupled, Client.unapply _)

}
