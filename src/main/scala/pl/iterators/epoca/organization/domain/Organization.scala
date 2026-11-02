package pl.iterators.epoca.organization.domain

import pl.iterators.epoca.organization.domain.OrganizationDomain._
import pl.iterators.epoca.user.domain.UserDomain.UserId

case class Organization(
  id: OrganizationId,
  name: OrganizationName,
  slug: OrganizationSlug,
  createdAt: OrganizationCreatedAt,
  createdBy: UserId,
  modifiedAt: OrganizationModifiedAt,
  modifiedBy: UserId,
  archivedAt: Option[OrganizationArchivedAt])
