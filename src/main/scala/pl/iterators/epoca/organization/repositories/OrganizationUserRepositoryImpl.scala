package pl.iterators.epoca.organization.repositories

import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationUserId
import pl.iterators.epoca.organization.domain.OrganizationUser
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.tables.OrganizationUserTable
import pl.iterators.epoca.user.domain.UserDomain.UserAccountType
import pl.iterators.epoca.user.repositories.tables.UserTable
import pl.iterators.epoca.utils.db.BaseRepository.driver.api._
import pl.iterators.epoca.utils.db.{FilteringRepository, SoftDeleteRepository}
import slick.ast.BaseTypedType
import slick.lifted.TableQuery

import scala.concurrent.ExecutionContext

class OrganizationUserRepositoryImpl(implicit e: ExecutionContext)
    extends SoftDeleteRepository[OrganizationUser, OrganizationUserId, OrganizationUserTable]
    with FilteringRepository[OrganizationUser, OrganizationUserTable, OrganizationUserFilters]
    with OrganizationUserRepository {

  protected val table: TableQuery[OrganizationUserTable] = TableQuery[OrganizationUserTable]
  private val userTable: TableQuery[UserTable]           = TableQuery[UserTable]

  override private[epoca] def insertPrepared(organizationUser: OrganizationUser): DBIO[OrganizationUser] =
    (table returning table) += organizationUser

  override protected def prepareFilterQuery(tableQuery: Query[OrganizationUserTable, OrganizationUser, Seq], params: OrganizationUserFilters)
    : Query[OrganizationUserTable, OrganizationUser, Seq] = {
    val query = tableQuery
      .filter(row =>
        row.userId in userTable
          .filter(_.accountType === LiteralColumn(UserAccountType.Human: UserAccountType))
          .map(_.id)
      )
      .filterOpt(params.userIds)(_.userId inSet _)
      .filterOpt(params.userId)(_.userId === _)
      .filterOpt(params.organizationId)(_.organizationId === _)
      .filterOpt(params.organizationIds)(_.organizationId inSet _)
      .filterOpt(params.role)(_.roles.any === _)
      .filterIf(!params.includeArchived)(_.notDeletedNow)
      .sortBy(_.userId)

    if (params.forUpdate) query.forUpdate else query
  }

  override protected def getId: OrganizationUser => OrganizationUserId      = _.id
  override protected def idBaseTypedType: BaseTypedType[OrganizationUserId] = implicitly[BaseTypedType[OrganizationUserId]]
  override protected def ec: ExecutionContext                               = e
}
