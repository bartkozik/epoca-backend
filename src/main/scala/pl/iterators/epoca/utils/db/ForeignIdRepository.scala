package pl.iterators.epoca.utils.db

import slick.ast.BaseTypedType

import BaseTable.driver.api._

trait ForeignIdRepository[T, Id, ForeignId, TableType <: BaseTable[T] with ForeignIdTable[T, ForeignId] with IdTable[T, Id]]
    extends IdRepository[T, Id, TableType] {
  def find(id: Id, foreignId: ForeignId): DBIO[Option[T]] = {
    implicit val idbtt: BaseTypedType[Id]         = idBaseTypedType
    implicit val fidbtt: BaseTypedType[ForeignId] = foreignIdBaseTypedType
    table.filter(e => e.id === id && e.foreignId === foreignId).result.headOption
  }

  def list(foreignId: ForeignId): DBIO[Seq[T]] = {
    implicit val fidbtt: BaseTypedType[ForeignId] = foreignIdBaseTypedType
    table.filter(_.foreignId === foreignId).result
  }

  def list(foreignIds: Seq[ForeignId]): DBIO[Seq[T]] = {
    implicit val fidbtt: BaseTypedType[ForeignId] = foreignIdBaseTypedType
    table.filter(_.foreignId.inSet(foreignIds)).result
  }

  protected val table: TableQuery[TableType]
  protected def foreignIdBaseTypedType: BaseTypedType[ForeignId]
}
