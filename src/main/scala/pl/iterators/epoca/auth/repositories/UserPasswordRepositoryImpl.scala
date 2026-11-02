package pl.iterators.epoca.auth.repositories

import pl.iterators.epoca.auth.domain.UserPassword
import pl.iterators.epoca.auth.repositories.tables.UserPasswordTable
import pl.iterators.epoca.user.domain.UserDomain.UserIdentityId
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import slick.dbio.DBIO
import slick.lifted.TableQuery

import scala.concurrent.ExecutionContext

class UserPasswordRepositoryImpl(implicit e: ExecutionContext) extends UserPasswordRepository {

  override def upsert(userPassword: UserPassword): DBIO[UserPassword] =
    table.insertOrUpdate(userPassword).map(_ => userPassword)

  override def find(userIdentityId: UserIdentityId): DBIO[Option[UserPassword]] =
    table.filter(_.userIdentityId === userIdentityId).result.map(_.headOption)

  private lazy val table: TableQuery[UserPasswordTable] = TableQuery[UserPasswordTable]
}
