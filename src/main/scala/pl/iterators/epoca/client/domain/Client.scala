package pl.iterators.epoca.client.domain

import pl.iterators.epoca.client.domain.ClientDomain._
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId
import pl.iterators.epoca.user.domain.UserDomain.UserId

case class Client(
  id: ClientId,
  name: ClientName,
  color: ClientColor,
  texture: ClientTexture,
  logo: ClientLogo,
  organizationId: OrganizationId,
  `type`: ClientType,
  createdAt: ClientCreatedAt,
  createdBy: UserId,
  modifiedAt: ClientModifiedAt,
  modifiedBy: UserId,
  archivedAt: Option[ClientArchivedAt],
  archivedBy: Option[UserId])
