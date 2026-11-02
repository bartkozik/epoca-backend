package pl.iterators.epoca.utils.db

import slick.ast.BaseTypedType

import java.time.Instant
import java.time.temporal.ChronoUnit

import BaseTable.driver.api._
trait SoftDeleteRepository[T, Id, TableType <: BaseTable[T] with IdTable[T, Id] with SoftDeleteTable[T, Id]] extends IdRepository[T, Id, TableType] {
  def find(
    id: Id,
    includeDeleted: Boolean,
    when: Instant
  ): DBIO[Option[T]] = {
    implicit val btt: BaseTypedType[Id] = idBaseTypedType

    table.filter(e => e.id === id && (e.notDeletedAt(when) || includeDeleted)).result.headOption
  }

  def find(id: Id, includeDeleted: Boolean): DBIO[Option[T]] =
    find(id, includeDeleted, Instant.ofEpochMilli(System.currentTimeMillis()))

  def list(includeDeleted: Boolean, when: Instant): DBIO[Seq[T]] =
    table.filter(_.notDeletedAt(when) || includeDeleted).result

  def list(includeDeleted: Boolean): DBIO[Seq[T]] =
    table.filter(_.notDeletedAt(Instant.ofEpochMilli(System.currentTimeMillis())) || includeDeleted).result

  def list(
    ids: Seq[Id],
    includeDeleted: Boolean,
    when: Instant
  ): DBIO[Seq[T]] = {
    implicit val btt: BaseTypedType[Id] = idBaseTypedType
    table.filter(e => (e.id inSet ids) && (e.notDeletedAt(when) || includeDeleted)).result
  }

  def list(ids: Seq[Id], includeDeleted: Boolean): DBIO[Seq[T]] =
    list(ids, includeDeleted, Instant.ofEpochMilli(System.currentTimeMillis()))

  def delete(id: Id, when: Instant = Instant.ofEpochMilli(System.currentTimeMillis())): DBIO[Int] = {
    implicit val btt: BaseTypedType[Id] = idBaseTypedType
    val normalizedWhen                  = when.truncatedTo(ChronoUnit.MILLIS)

    table.filter(_.id === id).map(_.deletedAt).update(Option(normalizedWhen))
  }

  def deleted(when: Instant = Instant.ofEpochMilli(System.currentTimeMillis())): DBIO[Seq[T]] =
    table.filter(_.deletedAt(when)).result
}
