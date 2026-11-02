package pl.iterators.epoca.utils.db

import slick.lifted.{PrimaryKey, Rep}

trait IdTable[T, Id] extends PrimaryKeyTable[T] { this: BaseTable[T] =>
  def id: Rep[Id]

  override val primaryKey: PrimaryKey = primaryKey(id)
}
