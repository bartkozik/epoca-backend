package pl.iterators.epoca.`export`.domain

import enumeratum.{Enum, EnumEntry}
import pl.iterators.kebs.tag.meta.tagged
import pl.iterators.kebs.tagged._

import scala.collection.immutable

@tagged trait Domain {
  trait SpreadsheetNameTag
  type SpreadsheetName = String @@ SpreadsheetNameTag

  trait SpreadsheetColumnNameTag
  type SpreadsheetColumnName = String @@ SpreadsheetColumnNameTag
}

object SpreadsheetDomain extends Domain {
  sealed trait SpreadsheetFormat extends EnumEntry
  object SpreadsheetFormat extends Enum[SpreadsheetFormat] {
    final case object Csv  extends SpreadsheetFormat
    final case object Xlsx extends SpreadsheetFormat

    override def values: immutable.IndexedSeq[SpreadsheetFormat] = findValues

    def extension(format: SpreadsheetFormat): String = format match {
      case Csv  => "csv"
      case Xlsx => "xlsx"
    }
  }
}
