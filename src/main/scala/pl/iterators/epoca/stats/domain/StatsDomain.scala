package pl.iterators.epoca.stats.domain

import enumeratum.{Enum, EnumEntry}
import pl.iterators.kebs.tag.meta.tagged
import pl.iterators.kebs.tagged._

import java.time.{LocalDate, YearMonth}
import scala.collection.immutable
@tagged trait Domain {

  trait StatsYearMonthTag
  type StatsYearMonth = YearMonth @@ StatsYearMonthTag
  object StatsYearMonth {
    def from(date: LocalDate): StatsYearMonth =
      StatsYearMonth(YearMonth.from(date))
  }

  trait StatsYearTag
  type StatsYear = Int @@ StatsYearTag

  trait StatsEntriesCountTag
  type StatsEntriesCount = Long @@ StatsEntriesCountTag
}

object StatsDomain extends Domain {
  sealed trait WorkUnitPivotTableKey extends EnumEntry

  object WorkUnitPivotTableKey extends Enum[WorkUnitPivotTableKey] {
    final case object UserKey      extends WorkUnitPivotTableKey
    final case object ProjectKey   extends WorkUnitPivotTableKey
    final case object ClientKey    extends WorkUnitPivotTableKey
    final case object YearKey      extends WorkUnitPivotTableKey
    final case object MonthYearKey extends WorkUnitPivotTableKey
    final case object DateKey      extends WorkUnitPivotTableKey
    final case object EntryTypeKey extends WorkUnitPivotTableKey
    final case object TaskKey      extends WorkUnitPivotTableKey

    override def values: immutable.IndexedSeq[WorkUnitPivotTableKey] = findValues
  }

  sealed trait WorkUnitPivotTableValue extends EnumEntry

  object WorkUnitPivotTableValue extends Enum[WorkUnitPivotTableValue] {
    final case object EntriesCount extends WorkUnitPivotTableValue
    final case object DurationSum  extends WorkUnitPivotTableValue
    final case object DurationAvg  extends WorkUnitPivotTableValue

    override def values: immutable.IndexedSeq[WorkUnitPivotTableValue] = findValues
  }
}
