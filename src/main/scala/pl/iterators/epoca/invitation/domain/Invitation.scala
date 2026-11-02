package pl.iterators.epoca.invitation.domain

import pl.iterators.epoca.invitation.domain.InvitationDomain._
import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationId, OrganizationUserRole}
import pl.iterators.epoca.user.domain.UserDomain.{IdentityType, UserId, UserIdentityValue}

case class Invitation(
  id: InvitationId,
  identityValue: UserIdentityValue,
  identityType: IdentityType,
  roles: Seq[OrganizationUserRole],
  expireAt: Option[InvitationExpireAt],
  acceptAt: Option[InvitationAcceptAt],
  rejectAt: Option[InvitationRejectAt],
  organizationId: OrganizationId,
  createdAt: InvitationCreatedAt,
  createdBy: UserId,
  modifiedAt: InvitationModifiedAt,
  modifiedBy: UserId,
  archivedAt: Option[InvitationArchivedAt])
