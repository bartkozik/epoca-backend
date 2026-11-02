package pl.iterators.epoca.utils.db

import BaseTable.driver.api._

trait UpsertRepository[T, TableType <: BaseTable[T] with PrimaryKeyTable[T]] {

  def upsert(obj: T): DBIO[T] = table.insertOrUpdate(obj).andThen(DBIO.successful(obj))

  protected val table: TableQuery[TableType]
}
