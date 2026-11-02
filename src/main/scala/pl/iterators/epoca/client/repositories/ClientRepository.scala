package pl.iterators.epoca.client.repositories

import pl.iterators.epoca.client.domain.Client
import pl.iterators.epoca.client.domain.ClientDomain._
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId
import pl.iterators.epoca.project.domain.ProjectDomain.ProjectId
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.utils.db.{FilterParamType, PageSizeAndNumber}
import slick.dbio.DBIO

trait ClientRepository {
  import ClientRepository._

  def insert(client: Client): DBIO[Client]
  def update(client: Client): DBIO[Option[Client]]
  def lockForMutation(clientId: ClientId): DBIO[Unit]
  def find(filter: ClientFilters): DBIO[Option[Client]]
  def list(filter: ClientFilters): DBIO[Seq[Client]]
  def count(filter: ClientFilters): DBIO[Int]
}

object ClientRepository {

  case class ClientFilters(
    id: Option[ClientId] = None,
    ids: Option[Seq[ClientId]] = None,
    name: Option[ClientName] = None,
    organizationIds: Option[Seq[OrganizationId]] = None,
    `type`: Option[ClientType] = None,
    userId: Option[UserId] = None,
    includeArchived: Boolean = false,
    forUpdate: Boolean = false,
    pageSizeAndNumber: Option[PageSizeAndNumber] = None,
    projectIds: Option[Seq[ProjectId]] = None,
    requireActiveProject: Boolean = false)
      extends FilterParamType
}
