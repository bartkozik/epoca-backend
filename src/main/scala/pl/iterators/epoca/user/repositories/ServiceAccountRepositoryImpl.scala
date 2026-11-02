package pl.iterators.epoca.user.repositories

import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationId, OrganizationUserArchivedAt}
import pl.iterators.epoca.organization.domain.OrganizationUser
import pl.iterators.epoca.organization.repositories.tables.OrganizationUserTable
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.domain.UserDomain.{UserAccountType, UserArchivedAt, UserFirstName, UserId, UserLastName, UserModifiedAt}
import pl.iterators.epoca.user.repositories.tables.UserTable
import pl.iterators.epoca.utils.db.BaseRepository.driver.api._
import slick.lifted.TableQuery

import java.time.Instant
import scala.concurrent.ExecutionContext

class ServiceAccountRepositoryImpl(implicit ec: ExecutionContext) extends ServiceAccountRepository {
  private val userTable             = TableQuery[UserTable]
  private val organizationUserTable = TableQuery[OrganizationUserTable]

  override def insert(user: User, membership: OrganizationUser): DBIO[(User, OrganizationUser)] =
    if (user.accountType != UserAccountType.ServiceAccount || user.isSuperAdmin || membership.userId != user.id || membership.roles.nonEmpty)
      DBIO.failed(new IllegalArgumentException("Invalid service-account identity"))
    else
      for {
        _ <- userTable += user
        _ <- organizationUserTable += membership
      } yield user -> membership

  override def findById(id: UserId, forUpdate: Boolean): DBIO[Option[User]] = {
    val query = userTable.filter(user => user.id === id && user.accountType === LiteralColumn(UserAccountType.ServiceAccount: UserAccountType))
    (if (forUpdate) query.forUpdate else query).result.headOption
  }

  override def findMembership(userId: UserId, organizationId: OrganizationId): DBIO[Option[OrganizationUser]] =
    organizationUserTable.filter(row => row.userId === userId && row.organizationId === organizationId).result.headOption

  override private[epoca] def findMembershipForUpdate(userId: UserId, organizationId: OrganizationId): DBIO[Option[OrganizationUser]] =
    organizationUserTable.filter(row => row.userId === userId && row.organizationId === organizationId).forUpdate.result.headOption

  override def rename(
    id: UserId,
    lastName: UserLastName,
    at: Instant
  ): DBIO[User] =
    if (lastName.toString.trim.isEmpty) DBIO.failed(new IllegalArgumentException("Service-account name must not be blank"))
    else {
      val query = userTable.filter(user => user.id === id && user.accountType === LiteralColumn(UserAccountType.ServiceAccount: UserAccountType))
      for {
        updated <-
          query.map(user => (user.firstName, user.lastName, user.modifiedAt)).update((UserFirstName("Integration"), lastName, UserModifiedAt(at)))
        _    <- requireSingleUpdate("service-account user rename", updated)
        user <- query.result.head
      } yield user
    }

  override def archive(
    id: UserId,
    organizationId: OrganizationId,
    at: Instant
  ): DBIO[Unit] = {
    val userQuery = userTable.filter(user => user.id === id && user.accountType === LiteralColumn(UserAccountType.ServiceAccount: UserAccountType))
    val membershipQuery = organizationUserTable.filter(row => row.userId === id && row.organizationId === organizationId)
    for {
      archivedUser       <- userQuery.map(_.archivedAt).update(Some(UserArchivedAt(at)))
      _                  <- requireSingleUpdate("service-account user archive", archivedUser)
      archivedMembership <- membershipQuery.map(_.archivedAt).update(Some(OrganizationUserArchivedAt(at)))
      _                  <- requireSingleUpdate("service-account membership archive", archivedMembership)
    } yield ()
  }

  private def requireSingleUpdate(operation: String, updated: Int): DBIO[Unit] =
    if (updated == 1) DBIO.successful(())
    else DBIO.failed(new IllegalStateException(s"$operation affected $updated rows"))
}
