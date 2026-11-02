package pl.iterators.epoca.auth.repositories

import pl.iterators.epoca.auth.domain.UserLoginToken
import pl.iterators.epoca.auth.repositories.UserLoginTokenRepository.UserLoginTokenFilters
import pl.iterators.epoca.auth.repositories.tables.UserLoginTokenTable
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import slick.dbio.DBIO
import slick.lifted.TableQuery

import scala.concurrent.ExecutionContext

class UserLoginTokenRepositoryImpl(implicit e: ExecutionContext) extends UserLoginTokenRepository {

  override def insert(userLoginToken: UserLoginToken): DBIO[UserLoginToken] =
    (table += userLoginToken).map(_ => userLoginToken)

  override def find(filters: UserLoginTokenFilters): DBIO[Option[UserLoginToken]] =
    filter(filters).result.map(_.headOption)

  override def purge(filters: UserLoginTokenFilters): DBIO[Unit] =
    filter(filters).delete.map(_ => ())

  private def filter(filters: UserLoginTokenFilters): Query[UserLoginTokenTable, UserLoginToken, Seq] =
    table
      .filterOpt(filters.id)(_.id === _)
      .filterOpt(filters.loginToken)(_.loginToken === _)
      .filterOpt(filters.validToBefore)(_.validTo <= _)
      .filterOpt(filters.validToAfterOrNone) { case (row, param) =>
        row.validTo >= param || row.validTo.isEmpty
      }

  private lazy val table: TableQuery[UserLoginTokenTable] = TableQuery[UserLoginTokenTable]

}
