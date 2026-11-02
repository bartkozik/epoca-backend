package pl.iterators.epoca.`export`.domain

import pl.iterators.epoca.export.domain.PivotExportProjection.Value.{Count, DurationMinutes}
import pl.iterators.epoca.export.domain.PivotExportProjection.{DataRow, Table, Value}
import pl.iterators.epoca.stats.domain.StatsDomain.WorkUnitPivotTableValue

import java.io.{ByteArrayOutputStream, OutputStream}
import java.nio.charset.StandardCharsets.UTF_8

object PivotCsvEncoder {
  private val Utf8Bom        = Array(0xef.toByte, 0xbb.toByte, 0xbf.toByte)
  private val Delimiter      = Array(0x2c.toByte)
  private val RecordBoundary = Array(0x0d.toByte, 0x0a.toByte)

  def encode(table: Table, measureLabels: Map[WorkUnitPivotTableValue, String]): Array[Byte] = {
    val output = new ByteArrayOutputStream()
    write(table, measureLabels, output)
    output.toByteArray
  }

  def write(
    table: Table,
    measureLabels: Map[WorkUnitPivotTableValue, String],
    output: OutputStream
  ): Unit = {
    validate(table, measureLabels)
    output.write(Utf8Bom)
    writeRecord(headerFields(table, measureLabels), output)
    table.rows.foreach(dataRow => writeRecord(rowFields(dataRow), output))
  }

  private def validate(table: Table, measureLabels: Map[WorkUnitPivotTableValue, String]): Unit = {
    table.columns.foreach { column =>
      measureLabels.getOrElse(column.measure, throw new IllegalArgumentException(s"missing measure label: ${column.measure.entryName}"))
    }
    table.rows.foreach { dataRow =>
      require(
        dataRow.header.values.size == table.rowFieldLabels.size,
        s"row header field count ${dataRow.header.values.size} did not match ${table.rowFieldLabels.size}"
      )
      require(dataRow.values.size == table.columns.size, s"row value field count ${dataRow.values.size} did not match ${table.columns.size}")
      dataRow.values.foreach(_.foreach(validateValue))
    }
  }

  private def headerFields(table: Table, measureLabels: Map[WorkUnitPivotTableValue, String]): Iterator[String] =
    table.rowFieldLabels.iterator.map(textField) ++ table.columns.iterator.map { column =>
      textField((column.labels :+ measureLabels(column.measure)).mkString(" / "))
    }

  private def rowFields(dataRow: DataRow): Iterator[String] =
    dataRow.header.values.iterator.map(_.fold("")(textField)) ++ dataRow.values.iterator.map(_.fold("")(valueField))

  private def writeRecord(fields: Iterator[String], output: OutputStream): Unit = {
    var first = true
    fields.foreach { field =>
      if (first) first = false else output.write(Delimiter)
      output.write(field.getBytes(UTF_8))
    }
    output.write(RecordBoundary)
  }

  private def textField(value: String): String = {
    val neutralized = SpreadsheetCellText.neutralizeFormula(value)
    if (neutralized.exists(character => character == ',' || character == '"' || character == '\r' || character == '\n'))
      "\"" + neutralized.replace("\"", "\"\"") + "\""
    else neutralized
  }

  private def valueField(value: Value): String =
    value match {
      case DurationMinutes(minutes) => formatDuration(minutes)
      case Count(count) =>
        require(count >= 0L, s"count must be non-negative: $count")
        count.toString
    }

  private def validateValue(value: Value): Unit =
    value match {
      case DurationMinutes(minutes) => require(minutes >= 0, s"duration minutes must be non-negative: $minutes")
      case Count(count)             => require(count >= 0L, s"count must be non-negative: $count")
    }

  private def formatDuration(minutes: BigInt): String = {
    require(minutes >= 0, s"duration minutes must be non-negative: $minutes")
    val hoursText  = (minutes / 60).toString
    val minuteText = (minutes % 60).toString
    val hours      = if (hoursText.length >= 2) hoursText else "0" + hoursText
    val remainder  = if (minuteText.length >= 2) minuteText else "0" + minuteText
    hours + ":" + remainder
  }
}
