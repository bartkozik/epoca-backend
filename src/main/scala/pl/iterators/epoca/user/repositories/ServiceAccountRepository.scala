package pl.iterators.epoca.user.repositories

import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId
import pl.iterators.epoca.organization.domain.OrganizationUser
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.domain.UserDomain.{UserId, UserLastName}
import slick.dbio.DBIO

import java.time.Instant

trait ServiceAccountRepository {
  def insert(user: User, membership: OrganizationUser): DBIO[(User, OrganizationUser)]
  def findById(id: UserId, forUpdate: Boolean = false): DBIO[Option[User]]
  def findMembership(userId: UserId, organizationId: OrganizationId): DBIO[Option[OrganizationUser]]
  private[epoca] def findMembershipForUpdate(userId: UserId, organizationId: OrganizationId): DBIO[Option[OrganizationUser]] =
    findMembership(userId, organizationId)
  def rename(
    id: UserId,
    lastName: UserLastName,
    at: Instant
  ): DBIO[User]
  def archive(
    id: UserId,
    organizationId: OrganizationId,
    at: Instant
  ): DBIO[Unit]
}
