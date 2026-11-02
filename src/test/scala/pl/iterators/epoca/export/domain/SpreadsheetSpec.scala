package pl.iterators.epoca.`export`.domain

import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.xssf.usermodel.{XSSFCell, XSSFRow}
import org.specs2.execute.Result
import org.specs2.mutable.SpecificationLike
import pl.iterators.epoca.export.domain.SpreadsheetDomain.{SpreadsheetColumnName, SpreadsheetFormat, SpreadsheetName}
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.WorkUnitEntryType
import spoiwo.natures.csv.Model2CsvConversions._
import spoiwo.natures.xlsx.Model2XlsxConversions._

import java.time.Duration

class SpreadsheetSpec extends SpecificationLike {
  case class TextRow(description: String)
  case class OptionalTextRow(description: Option[String], omitted: Option[String])
  case class MixedRow(
    description: String,
    duration: Duration,
    count: Long,
    amount: Double)
  case class RegularRow(duration: Duration)
  case class PivotRow(durationSum: Option[Duration], durationAvg: Option[Duration])
  case class EntryTypeRow(
    description: String,
    duration: Duration,
    entryType: WorkUnitEntryType)

  private def fractionalHours(duration: Duration): Double =
    duration.getSeconds.toDouble / 3600d + duration.getNano.toDouble / 3_600_000_000_000d

  private def csvDataRow(spreadsheet: Spreadsheet): String =
    spreadsheet.workbook
      .convertAsCsv()
      .head
      ._2
      .linesIterator
      .toList
      .tail
      .head

  private def regularSpreadsheet(duration: Duration): Spreadsheet =
    Spreadsheet.from(SpreadsheetName("report.xlsx"), Seq(RegularRow(duration)), Map.empty)

  private def withXlsxDataCell(duration: Duration)(assertions: XSSFCell => Result): Result = {
    val workbookModel = Spreadsheet
      .from(SpreadsheetName("report.xlsx"), Seq(RegularRow(duration)), Map.empty)
      .workbook
    val workbook = workbookModel
      .withSheets(workbookModel.sheets.map(sheet => sheet.withColumns(sheet.columns.map(_.withoutAutoSized))))
      .convertAsXlsx()

    try assertions(workbook.getSheetAt(0).getRow(1).getCell(0).asInstanceOf[XSSFCell])
    finally workbook.close()
  }

  private def withXlsxDataRow(spreadsheet: Spreadsheet)(assertions: XSSFRow => Result): Result = {
    val workbookModel = spreadsheet.workbook
    val workbook = workbookModel
      .withSheets(workbookModel.sheets.map(sheet => sheet.withColumns(sheet.columns.map(_.withoutAutoSized))))
      .convertAsXlsx()

    try assertions(workbook.getSheetAt(0).getRow(1).asInstanceOf[XSSFRow])
    finally workbook.close()
  }

  "Spreadsheet" should {
    "neutralize formula prefixes in CSV text while preserving ordinary text" in {
      val values   = Seq("=1+1", "+1+1", "-1+1", "@SUM(A1)", "   =1+1", "'already safe", "ordinary note")
      val expected = Seq("'=1+1", "'+1+1", "'-1+1", "'@SUM(A1)", "'   =1+1", "'already safe", "ordinary note")

      values.map(value => csvDataRow(Spreadsheet.from(SpreadsheetName("report.csv"), Seq(TextRow(value)), Map.empty))) shouldEqual expected
    }

    "neutralize formula prefixes in user supplied CSV column names" in {
      val spreadsheet =
        Spreadsheet.from(SpreadsheetName("report.csv"), Seq(TextRow("ordinary note")), Map("description" -> SpreadsheetColumnName(" =Title")))

      spreadsheet.workbook.convertAsCsv().head._2.linesIterator.next() shouldEqual "' =Title"
    }

    "neutralize optional pivot text without adding omitted columns" in {
      val spreadsheet = Spreadsheet.fromPivot(SpreadsheetName("report.csv"), Seq(OptionalTextRow(Some("=1+1"), None)), Map.empty)

      csvDataRow(spreadsheet) shouldEqual "'=1+1"
    }

    "leave CSV quoting to the CSV writer after neutralizing formulas" in {
      val spreadsheet = Spreadsheet.from(SpreadsheetName("report.csv"), Seq(TextRow("=SUM(1,2)")), Map.empty)

      csvDataRow(spreadsheet) shouldEqual "\"'=SUM(1,2)\""
    }

    "neutralize XLSX text while preserving typed numeric cells" in {
      val spreadsheet = Spreadsheet.from(SpreadsheetName("report.xlsx"), Seq(MixedRow("=1+1", Duration.ofMinutes(90), -2L, -1.5)), Map.empty)

      withXlsxDataRow(spreadsheet) { row =>
        (row.getCell(0).getCellType shouldEqual CellType.STRING) and
          (row.getCell(0).getStringCellValue shouldEqual "'=1+1") and
          (row.getCell(1).getNumericCellValue shouldEqual 1.5) and
          (row.getCell(2).getNumericCellValue shouldEqual -2d) and
          (row.getCell(3).getNumericCellValue shouldEqual -1.5)
      }
    }

    "map each supported format to its file extension" in {
      SpreadsheetFormat.extension(SpreadsheetFormat.Csv) shouldEqual "csv"
      SpreadsheetFormat.extension(SpreadsheetFormat.Xlsx) shouldEqual "xlsx"
    }

    "build columns without font-dependent auto-sizing" in {
      val columns = regularSpreadsheet(Duration.ZERO).workbook.sheets.head.columns

      columns.map(_.autoSized) shouldEqual List(Some(false))
    }

    "preserve fractional hours for minute-only durations in CSV" in {
      val duration = Duration.ofMinutes(80)

      csvDataRow(regularSpreadsheet(duration)) shouldEqual fractionalHours(duration).toString
      csvDataRow(regularSpreadsheet(duration)) shouldNotEqual "1.33"
    }

    "preserve nanoseconds in CSV duration values" in {
      val duration = Duration.ofSeconds(1).dividedBy(3)

      csvDataRow(regularSpreadsheet(duration)) shouldEqual fractionalHours(duration).toString
    }

    "serialize durations larger than Duration.toNanos supports in CSV" in {
      val duration = Duration.ofDays(200000)

      csvDataRow(regularSpreadsheet(duration)) shouldEqual fractionalHours(duration).toString
    }

    "format minute-only durations to two decimal places in XLSX" in {
      val duration = Duration.ofMinutes(80)

      withXlsxDataCell(duration) { cell =>
        (cell.getNumericCellValue must beCloseTo(fractionalHours(duration), 1e-15)) and
          (cell.getCellStyle.getDataFormatString shouldEqual "0.00")
      }
    }

    "format fractional-second durations to two decimal places in XLSX" in {
      val duration = Duration.ofSeconds(1).dividedBy(3)

      withXlsxDataCell(duration) { cell =>
        (cell.getNumericCellValue must beCloseTo(fractionalHours(duration), 1e-18)) and
          (cell.getCellStyle.getDataFormatString shouldEqual "0.00")
      }
    }

    "serialize enum entryName and Note duration as numeric zero in CSV" in {
      val spreadsheet =
        Spreadsheet.from(SpreadsheetName("report.xlsx"), Seq(EntryTypeRow("ordinary note", Duration.ZERO, WorkUnitEntryType.Note)), Map.empty)

      csvDataRow(spreadsheet) shouldEqual s"ordinary note,0.0,${WorkUnitEntryType.Note.entryName}"
    }

    "serialize enum entryName and Note duration as numeric zero in XLSX" in {
      val spreadsheet =
        Spreadsheet.from(SpreadsheetName("report.xlsx"), Seq(EntryTypeRow("ordinary note", Duration.ZERO, WorkUnitEntryType.Note)), Map.empty)

      withXlsxDataRow(spreadsheet) { row =>
        (row.getLastCellNum shouldEqual 3) and
          (row.getCell(1).getNumericCellValue shouldEqual 0d) and
          (row.getCell(2).getStringCellValue shouldEqual WorkUnitEntryType.Note.entryName)
      }
    }

    "pivot duration export" should {
      "preserve fractional hours for optional duration values in CSV" in {
        val duration = Duration.ofSeconds(1).dividedBy(3)
        val spreadsheet =
          Spreadsheet.fromPivot(SpreadsheetName("report.xlsx"), Seq(PivotRow(durationSum = Some(duration), durationAvg = None)), Map.empty)

        csvDataRow(spreadsheet) shouldEqual fractionalHours(duration).toString
      }
    }
  }
}
