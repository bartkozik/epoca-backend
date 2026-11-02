package pl.iterators.epoca.organization.repositories.tables

import pl.iterators.epoca.organization.domain.OrganizationDomain._
import pl.iterators.epoca.organization.domain.OrganizationUser
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import pl.iterators.epoca.utils.db.{BaseTable, IdTable, SoftDeleteTable}
import slick.lifted.ProvenShape

import java.time.Instant

class OrganizationUserTable(tag: Tag)
    extends BaseTable[OrganizationUser](tag, "organization_user")
    with IdTable[OrganizationUser, OrganizationUserId]
    with SoftDeleteTable[OrganizationUser, OrganizationUserId] {

  def id: Rep[OrganizationUserId]                         = column[OrganizationUserId]("id")
  def organizationId: Rep[OrganizationId]                 = column[OrganizationId]("organization_id")
  def userId: Rep[UserId]                                 = column[UserId]("user_id")
  def roles: Rep[Seq[OrganizationUserRole]]               = column[Seq[OrganizationUserRole]]("roles")
  def archivedAt: Rep[Option[OrganizationUserArchivedAt]] = column[Option[OrganizationUserArchivedAt]]("archived_at")
  override def deletedAt: Rep[Option[Instant]]            = archivedAt

  override def * : ProvenShape[OrganizationUser] =
    (id, organizationId, userId, roles, archivedAt) <> (OrganizationUser.tupled, OrganizationUser.unapply _)

}
