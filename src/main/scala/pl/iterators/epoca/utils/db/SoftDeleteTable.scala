package pl.iterators.epoca.utils.db

import pl.iterators.epoca.utils.db.BaseTable.driver.api._

import java.time.Instant
trait SoftDeleteTable[T, Id] { this: BaseTable[T] =>
  def deletedAt: Rep[Option[Instant]]

  def notDeletedNow: Rep[Boolean] = notDeletedAt(Instant.ofEpochMilli(System.currentTimeMillis()))

  def notDeletedAt(when: Instant): Rep[Boolean] = (this.deletedAt > when).getOrElse(true)

  def deletedAt(when: Instant): Rep[Boolean] = (this.deletedAt <= when).getOrElse(false)

  def deletedNow(when: Instant): Rep[Boolean] = deletedAt(when)
}
