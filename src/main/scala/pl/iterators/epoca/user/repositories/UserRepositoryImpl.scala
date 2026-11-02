package pl.iterators.epoca.user.repositories

import pl.iterators.epoca.user.domain.UserDomain.{IdentityType, UserAccountType, UserId}
import pl.iterators.epoca.user.domain.{User, UserWithIdentities}
import pl.iterators.epoca.user.repositories.UserRepository.{UserFilters, UserSortBy}
import pl.iterators.epoca.user.repositories.tables.{UserIdentityTable, UserTable}
import pl.iterators.epoca.utils.db.BaseRepository.driver.api._
import pl.iterators.epoca.utils.db.{FilteringRepository, IdRepository}
import slick.ast.BaseTypedType
import slick.lifted.{Query, TableQuery}

import scala.concurrent.ExecutionContext

class UserRepositoryImpl(implicit e: ExecutionContext)
    extends IdRepository[User, UserId, UserTable]
    with FilteringRepository[User, UserTable, UserFilters]
    with UserRepository {

  override protected def getId: User => UserId = _.id

  override protected def idBaseTypedType: BaseTypedType[UserId] = implicitly[BaseTypedType[UserId]]

  override protected def ec: ExecutionContext = e

  override def find(id: UserId): DBIO[Option[User]] =
    humanUsers.filter(_.id === id).result.headOption

  override def get(id: UserId): DBIO[User] =
    humanUsers.filter(_.id === id).result.head

  override def list(): DBIO[Seq[User]] =
    humanUsers.result

  override def list(ids: Seq[UserId]): DBIO[Seq[User]] =
    humanUsers.filter(_.id inSet ids).result

  override protected def prepareFilterQuery(tableQuery: Query[UserTable, User, Seq], filters: UserFilters): Query[UserTable, User, Seq] = {
    val query = humanUsers
      .filterOpt(filters.id)(_.id === _)
      .filterOpt(filters.ids)(_.id inSet _)
      .filterOpt(filters.email)((row, param) =>
        row.id in userIdentityTable
          .filter(ui => ui.value.toLowerCase === param.toLowerCase && ui.`type` === LiteralColumn(IdentityType.MailAddress: IdentityType))
          .map(_.userId)
      )
      .filterOpt(filters.phoneNumber)((row, param) =>
        row.id in userIdentityTable
          .filter(ui => ui.value.toLowerCase === param.toLowerCase && ui.`type` === LiteralColumn(IdentityType.Phone: IdentityType))
          .map(_.userId)
      )
      .filterIf(!filters.includeArchived)(_.archivedAt.isEmpty)
      .sortBy(entity =>
        filters.sortBy.getOrElse(UserSortBy.default) match {
          case UserSortBy.CreatedAtAsc  => entity.createdAt.asc
          case UserSortBy.CreatedAtDesc => entity.createdAt.desc
        }
      )

    val paginatedQuery = filters.pageSizeAndNumber.fold(query) { page =>
      query.drop(page.offset).take(page.size)
    }

    if (filters.forUpdate) paginatedQuery.forUpdate else paginatedQuery
  }

  override def listWithIdentities(filter: UserFilters): DBIO[Seq[UserWithIdentities]] =
    prepareFilterQuery(table, filter)
      .joinLeft(userIdentityTable)
      .on(_.id === _.userId)
      .result
      .map(
        _.groupBy { case (user, _) => user }.view
          .mapValues(_.map { case (_, identitiesOpt) => identitiesOpt })
          .toSeq
          .map { case (user, identitiesOpt) => UserWithIdentities(user, identitiesOpt.flatten) }
      )

  override protected val table: TableQuery[UserTable] = TableQuery[UserTable]
  private lazy val humanUsers: Query[UserTable, User, Seq] =
    table.filter(_.accountType === LiteralColumn(UserAccountType.Human: UserAccountType))
  private lazy val userIdentityTable: TableQuery[UserIdentityTable] = TableQuery[UserIdentityTable]
}
