package pl.iterators.epoca.organization.repositories

import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationId, OrganizationUserId, OrganizationUserRole}
import pl.iterators.epoca.organization.domain.OrganizationUser
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.utils.db.FilterParamType
import slick.dbio.DBIO

import java.time.Instant

trait OrganizationUserRepository {

  import OrganizationUserRepository._

  def upsert(organizationUser: OrganizationUser): DBIO[OrganizationUser]
  private[epoca] def insertPrepared(organizationUser: OrganizationUser): DBIO[OrganizationUser] = upsert(organizationUser)
  def find(filter: OrganizationUserFilters): DBIO[Option[OrganizationUser]]
  def list(filter: OrganizationUserFilters): DBIO[Seq[OrganizationUser]]
  def delete(id: OrganizationUserId, when: Instant): DBIO[Int]
}

object OrganizationUserRepository {
  case class OrganizationUserFilters(
    organizationId: Option[OrganizationId] = None,
    organizationIds: Option[Seq[OrganizationId]] = None,
    userIds: Option[Seq[UserId]] = None,
    userId: Option[UserId] = None,
    role: Option[OrganizationUserRole] = None,
    includeArchived: Boolean = false,
    forUpdate: Boolean = false)
      extends FilterParamType
}
