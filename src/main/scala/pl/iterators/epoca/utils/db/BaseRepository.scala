package pl.iterators.epoca.utils.db

abstract class BaseRepository

object BaseRepository {
  val driver: PostgresDriver.type = BaseTable.driver
}
