package pl.iterators.epoca.organization.domain

import pl.iterators.epoca.organization.domain.OrganizationDomain.{
  OrganizationId,
  OrganizationUserArchivedAt,
  OrganizationUserId,
  OrganizationUserRole
}
import pl.iterators.epoca.user.domain.UserDomain.UserId

case class OrganizationUser(
  id: OrganizationUserId,
  organizationId: OrganizationId,
  userId: UserId,
  roles: Seq[OrganizationUserRole],
  archivedAt: Option[OrganizationUserArchivedAt]) {
  lazy val hasManagementRole: Boolean = OrganizationUserRole.managementAccessRoles.intersect(roles).nonEmpty
  lazy val isOrgAdmin: Boolean        = roles.contains(OrganizationUserRole.Owner)
  lazy val isManager: Boolean         = roles.contains(OrganizationUserRole.Manager)
}
