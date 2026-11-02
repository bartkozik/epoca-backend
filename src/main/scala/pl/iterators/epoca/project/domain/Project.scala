package pl.iterators.epoca.project.domain

import pl.iterators.epoca.client.domain.ClientDomain.ClientId
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId
import pl.iterators.epoca.project.domain.ProjectDomain._
import pl.iterators.epoca.user.domain.UserDomain.UserId

case class Project(
  id: ProjectId,
  name: ProjectName,
  description: Option[ProjectDescription],
  organizationId: OrganizationId,
  clientId: ClientId,
  createdAt: ProjectCreatedAt,
  createdBy: UserId,
  modifiedAt: ProjectModifiedAt,
  modifiedBy: UserId,
  archivedAt: Option[ProjectArchivedAt],
  archivedBy: Option[UserId])
