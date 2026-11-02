package pl.iterators.epoca.utils.db

import slick.lifted.{PrimaryKey, Rep}

trait PrimaryKeyTable[T] { this: BaseTable[T] =>
  def primaryKey(columns: Rep[_]*): PrimaryKey = PrimaryKey("primary_key", columns.map(_.toNode).toIndexedSeq)
  val primaryKey: PrimaryKey
}
