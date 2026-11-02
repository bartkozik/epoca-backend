package pl.iterators.epoca.utils.db

import BaseTable.driver.api._

trait FilterParamType

trait FilteringRepository[RowType, TableType <: Table[RowType], FilterType <: FilterParamType] {

  protected def prepareFilterQuery(tableQuery: Query[TableType, RowType, Seq], params: FilterType): Query[TableType, RowType, Seq]

  protected val table: TableQuery[TableType]

  def list(params: FilterType): DBIO[Seq[RowType]] =
    prepareFilterQuery(table, params).result

  def find(params: FilterType): DBIO[Option[RowType]] =
    prepareFilterQuery(table, params).take(1).result.headOption
}
