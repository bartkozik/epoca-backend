package pl.iterators.epoca.utils.db

import slick.lifted.Rep

trait ForeignIdTable[T, ForeignId] { this: BaseTable[T] =>
  def foreignId: Rep[ForeignId]
}
