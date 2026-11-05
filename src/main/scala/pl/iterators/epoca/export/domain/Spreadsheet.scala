package pl.iterators.epoca.`export`.domain

import cats.effect.IO
import fs2._
import pl.iterators.epoca.export.domain.SpreadsheetDomain.{SpreadsheetColumnName, SpreadsheetFormat, SpreadsheetName}
import spoiwo.model._
import spoiwo.model.enums.FontFamily
import spoiwo.natures.csv.Model2CsvConversions._
import spoiwo.natures.xlsx.Model2XlsxConversions._

import java.time.{Duration, Instant}
import java.util.Date

case class Spreadsheet(fileName: SpreadsheetName, workbook: Workbook) {
  def toStream(format: SpreadsheetFormat): Stream[IO, Byte] =
    fs2.io.readOutputStream[IO](Spreadsheet.chunkSize) { outputStream =>
      IO.interruptible {
        format match {
          case SpreadsheetFormat.Csv =>
            workbook
              .convertAsCsv()
              .foreach(v => outputStream.write(v._2.getBytes("UTF-8")))
          case SpreadsheetFormat.Xlsx =>
            val xlsx = workbook.convertAsXlsx()
            try xlsx.write(outputStream)
            finally xlsx.close()
        }
        outputStream.flush()
        outputStream.close()
      }
    }
}

object Spreadsheet {
  private val chunkSize         = 1024
  private val secondsPerHour    = 3600d
  private val nanosPerHour      = 3_600_000_000_000d
  private val durationCellStyle = CellStyle(dataFormat = CellDataFormat("0.00"))

  def from[T <: Product](
    fileName: SpreadsheetName,
    entries: Seq[T],
    columnNamesMap: Map[String, SpreadsheetColumnName]
  ): Spreadsheet = {
    val fieldNames = getFieldNames(entries).map(oldName => columnNamesMap.getOrElse(oldName, oldName))
    Spreadsheet(fileName, getWorkbook(fieldNames, entries.map(_.toRow)))
  }

  def fromPivot[T <: Product](
    fileName: SpreadsheetName,
    entries: Seq[T],
    columnNamesMap: Map[String, SpreadsheetColumnName]
  ): Spreadsheet = {
    val fieldNames = getPivotFieldNames(entries).map(oldName => columnNamesMap.getOrElse(oldName, oldName))
    Spreadsheet(fileName, getWorkbook(fieldNames, entries.map(_.toRowWithoutEmpty)))
  }

  private def getFieldNames[T <: Product](entries: Seq[T]): List[String] =
    entries.headOption.fold(List[String]())(_.productElementNames.toList)

  private def getPivotFieldNames[T <: Product](entries: Seq[T]): List[String] =
    entries.headOption.fold(List[String]())(entry =>
      entry.productElementNames
        .zip(entry.productIterator)
        .filter {
          case (_, None) => false
          case _         => true
        }
        .map { case (fieldName, _) => fieldName }
        .toList
    )

  private def getWorkbook(fieldNames: List[String], rows: Seq[Row]): Workbook =
    Workbook(
      Sheet(name = defaultSheetName)
        .withRows(getHeaderRow(fieldNames) :: rows.toList)
        .withColumns(getFormattedColumns(fieldNames.length))
    )

  private def getHeaderRow(fieldNames: List[String]): Row =
    Row(style = CellStyle(font = Font(bold = true, family = FontFamily.Roman))).withCellValues(fieldNames.map(SpreadsheetCellText.neutralizeFormula))

  private def getFormattedColumns(length: Int): List[Column] =
    List.tabulate(length)(Column(_, autoSized = false))

  implicit class EntryRow[T <: Product](entry: T) {

    def toRow: Row =
      Row().withCells(entry.productIterator.map(fieldToCell).toList)

    def toRowWithoutEmpty: Row =
      Row().withCells(entry.productIterator.map(fieldToCell).filterNot(_ == Cell.Empty).toList)

    private def fieldToCell(field: Any): Cell = field match {
      case option: Option[_]  => option.toCell
      case duration: Duration => duration.toCell
      case instant: Instant   => instant.toCell
      case double: Double     => Cell(double)
      case long: Long         => Cell(long)
      case date: Date         => Cell(date)
      case field              => Cell(SpreadsheetCellText.neutralizeFormula(field.toString))
    }

    implicit class OptionCell[A](option: Option[A]) {
      def toCell: Cell = option match {
        case Some(value) => fieldToCell(value)
        case None        => Cell.Empty
      }
    }

    implicit class DurationCell(duration: Duration) {
      def toCell: Cell = {
        val hours = duration.getSeconds.toDouble / secondsPerHour + duration.getNano.toDouble / nanosPerHour
        Cell(hours, style = durationCellStyle)
      }
    }

    implicit class InstantCell(instant: Instant) {
      def toCell: Cell =
        Cell(instant.toString.split('.').head.replace("T", " "))
    }
  }

  private final val defaultSheetName = "Report"
}
