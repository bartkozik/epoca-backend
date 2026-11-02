package pl.iterators.epoca.utils.db

import slick.ast.BaseTypedType

import scala.concurrent.ExecutionContext

import BaseTable.driver.api._

trait IdRepository[T, Id, TableType <: BaseTable[T] with IdTable[T, Id]] extends UpsertRepository[T, TableType] {
  protected def getId: T => Id
  protected def idBaseTypedType: BaseTypedType[Id]
  protected def ec: ExecutionContext

  def insert(obj: T): DBIO[T] =
    (table returning table) += obj

  def insert(objs: Seq[T]): DBIO[Seq[T]] =
    (table returning table) ++= objs

  def find(id: Id): DBIO[Option[T]] = {
    implicit val btt: BaseTypedType[Id] = idBaseTypedType
    table.filter(_.id === id).result.headOption
  }

  def get(id: Id): DBIO[T] = {
    implicit val btt: BaseTypedType[Id] = idBaseTypedType
    table.filter(_.id === id).result.head
  }

  def list(): DBIO[Seq[T]] = table.result

  def list(ids: Seq[Id]): DBIO[Seq[T]] = {
    implicit val btt: BaseTypedType[Id] = idBaseTypedType
    table.filter(_.id inSet ids).result
  }

  def update(toBeUpdated: T): DBIO[Option[T]] = {
    implicit val btt: BaseTypedType[Id] = idBaseTypedType
    implicit val ecc: ExecutionContext  = ec
    table.filter(_.id === getId(toBeUpdated)).update(toBeUpdated).map {
      case 0 => None
      case _ => Option(toBeUpdated)
    }
  }

  def update(id: Id, transform: T => T): DBIO[Option[T]] = {
    implicit val btt: BaseTypedType[Id] = idBaseTypedType
    implicit val ecc: ExecutionContext  = ec
    table
      .filter(_.id === id)
      .forUpdate
      .result
      .headOption
      .flatMap {
        case Some(obj) =>
          val updated = transform(obj)
          table.filter(_.id === id).update(updated).andThen(DBIO.successful(Option(updated)))
        case _ => DBIO.successful(None)
      }
      .transactionally
  }

  def purgeById(id: Id): DBIO[Int] = {
    implicit val btt: BaseTypedType[Id] = idBaseTypedType
    table.filter(_.id === id).delete
  }

  def purge(obj: T): DBIO[Int] =
    purgeById(getId(obj))

  protected val table: TableQuery[TableType]
}
