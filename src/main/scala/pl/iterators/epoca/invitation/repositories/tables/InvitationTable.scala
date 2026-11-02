package pl.iterators.epoca.invitation.repositories.tables

import pl.iterators.epoca.invitation.domain.Invitation
import pl.iterators.epoca.invitation.domain.InvitationDomain._
import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationId, OrganizationUserRole}
import pl.iterators.epoca.user.domain.UserDomain.{IdentityType, UserId, UserIdentityValue}
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import pl.iterators.epoca.utils.db.{BaseTable, IdTable, SoftDeleteTable}
import slick.lifted.ProvenShape

import java.time.Instant

class InvitationTable(tag: Tag)
    extends BaseTable[Invitation](tag, "invitation")
    with IdTable[Invitation, InvitationId]
    with SoftDeleteTable[Invitation, InvitationId] {

  def protocolVersion: Rep[Int] = column[Int]("protocol_version")

  def id: Rep[InvitationId]                         = column[InvitationId]("id")
  def identityValue: Rep[UserIdentityValue]         = column[UserIdentityValue]("identity_value")
  def identityType: Rep[IdentityType]               = column[IdentityType]("identity_type")
  def roles: Rep[Seq[OrganizationUserRole]]         = column[Seq[OrganizationUserRole]]("roles")
  def expireAt: Rep[Option[InvitationExpireAt]]     = column[Option[InvitationExpireAt]]("expire_at")
  def acceptAt: Rep[Option[InvitationAcceptAt]]     = column[Option[InvitationAcceptAt]]("accept_at")
  def rejectAt: Rep[Option[InvitationRejectAt]]     = column[Option[InvitationRejectAt]]("reject_at")
  def organizationId: Rep[OrganizationId]           = column[OrganizationId]("organization_id")
  def createdAt: Rep[InvitationCreatedAt]           = column[InvitationCreatedAt]("created_at")
  def createdBy: Rep[UserId]                        = column[UserId]("created_by")
  def modifiedAt: Rep[InvitationModifiedAt]         = column[InvitationModifiedAt]("modified_at")
  def modifiedBy: Rep[UserId]                       = column[UserId]("modified_by")
  def archivedAt: Rep[Option[InvitationArchivedAt]] = column[Option[InvitationArchivedAt]]("archived_at")

  override def deletedAt: Rep[Option[Instant]] = archivedAt

  override def * : ProvenShape[Invitation] =
    (
      id,
      identityValue,
      identityType,
      roles,
      expireAt,
      acceptAt,
      rejectAt,
      organizationId,
      createdAt,
      createdBy,
      modifiedAt,
      modifiedBy,
      archivedAt
    ) <> (Invitation.tupled, Invitation.unapply _)

}
