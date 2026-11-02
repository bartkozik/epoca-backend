package pl.iterators.epoca.utils.db

import BaseTable.driver.api.{Table, Tag}

abstract class BaseTable[T](tag: Tag, tableName: String) extends Table[T](tag, tableName)

object BaseTable {
  val driver: PostgresDriver.type = PostgresDriver
}
