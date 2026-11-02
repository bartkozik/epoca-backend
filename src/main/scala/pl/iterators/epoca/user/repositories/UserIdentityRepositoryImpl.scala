package pl.iterators.epoca.user.repositories

import pl.iterators.epoca.user.domain.UserDomain.UserIdentityId
import pl.iterators.epoca.user.domain.UserIdentity
import pl.iterators.epoca.user.repositories.UserIdentityRepository.UserIdentityFilters
import pl.iterators.epoca.user.repositories.tables.UserIdentityTable
import pl.iterators.epoca.utils.db.BaseRepository.driver.api._
import pl.iterators.epoca.utils.db.{FilteringRepository, IdRepository}
import slick.ast.BaseTypedType
import slick.dbio
import slick.lifted.{Query, TableQuery}

import scala.concurrent.ExecutionContext

class UserIdentityRepositoryImpl(implicit e: ExecutionContext)
    extends IdRepository[UserIdentity, UserIdentityId, UserIdentityTable]
    with FilteringRepository[UserIdentity, UserIdentityTable, UserIdentityFilters]
    with UserIdentityRepository {

  override protected def getId: UserIdentity => UserIdentityId = _.id

  override protected def idBaseTypedType: BaseTypedType[UserIdentityId] = implicitly[BaseTypedType[UserIdentityId]]

  override protected def ec: ExecutionContext = e

  override protected def prepareFilterQuery(tableQuery: Query[UserIdentityTable, UserIdentity, Seq], filters: UserIdentityFilters)
    : Query[UserIdentityTable, UserIdentity, Seq] = {
    val query = tableQuery
      .filterOpt(filters.id)(_.id === _)
      .filterOpt(filters.userId)(_.userId === _)
      .filterOpt(filters.userIds)(_.userId inSet _)
      .filterOpt(filters.value)(_.value === _)
      .filterOpt(filters.main)(_.main === _)
      .filterOpt(filters.confirmed)(_.confirmed === _)
      .filterOpt(filters.`type`)(_.`type` === _)
      .sortBy(_.createdAt)

    if (filters.forUpdate) query.forUpdate else query
  }

  override def purge(filter: UserIdentityFilters): dbio.DBIO[Unit] =
    prepareFilterQuery(table, filter).delete.map(_ => ())

  override protected val table: TableQuery[UserIdentityTable] = TableQuery[UserIdentityTable]
}
