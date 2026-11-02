package pl.iterators.epoca.`export`.domain

import org.apache.poi.ss.usermodel.{BorderStyle, Cell, CellType, SheetVisibility}
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.specs2.execute.Result
import org.specs2.mutable.SpecificationLike
import pl.iterators.epoca.export.domain.PivotExportProjection.Value.{Count, DurationMinutes}
import pl.iterators.epoca.export.domain.PivotExportProjection._
import pl.iterators.epoca.stats.domain.StatsDomain.{WorkUnitPivotTableKey, WorkUnitPivotTableValue}
import pl.iterators.epoca.stats.domain.WorkUnitPivotQuery.{Coordinate, CoordinateKind, FieldValue, PathSegment}

import java.io.{ByteArrayOutputStream, IOException, OutputStream}
import java.nio.file.Files
import java.time.YearMonth
import java.util.Date
import scala.jdk.CollectionConverters._
import scala.util.Using

class PivotXlsxEncoderSpec extends SpecificationLike {
  "PivotXlsxEncoder" should {
    "write completed Month groups with merges, panes, typed values, blanks, styles, and Grand Total last" in {
      val monthLabels = Vector(
        YearMonth.of(2026, 2) -> "Luty 2026",
        YearMonth.of(2026, 3) -> "Marzec 2026",
        YearMonth.of(2026, 4) -> "Kwiecień 2026",
        YearMonth.of(2026, 5) -> "Maj 2026",
        YearMonth.of(2026, 6) -> "Czerwiec 2026",
        YearMonth.of(2026, 7) -> "Lipiec 2026",
        YearMonth.of(2026, 8) -> "Sierpień 2026"
      )
      val groups = monthLabels.map { case (month, label) =>
        val coordinate = Coordinate(CoordinateKind.Leaf, Seq(PathSegment(WorkUnitPivotTableKey.MonthYearKey, FieldValue.Month(month))))
        coordinate -> label
      } :+ (grandTotalCoordinate -> "Suma końcowa")
      val columns = groups.flatMap { case (coordinate, label) =>
        Vector(
          column(coordinate, Vector(label), WorkUnitPivotTableValue.DurationSum),
          column(coordinate, Vector(label), WorkUnitPivotTableValue.EntriesCount)
        )
      }
      val table = Table(
        rowFieldLabels = Vector("Użytkownik"),
        columns = columns,
        rows = Vector(
          DataRow(
            RowHeader(Vector(Some("Ala")), CoordinateKind.Leaf),
            Vector.fill(6)(None) ++ Vector(
              duration(10),
              count(1),
              duration(20),
              count(2),
              duration(30),
              count(3),
              duration(40),
              count(4),
              duration(100),
              count(10)
            )
          )
        )
      )

      withWorkbook(table, measureLabels) { workbook =>
        val sheet  = workbook.getSheet("Pivot")
        val panes  = sheet.getPaneInformation
        val merged = sheet.getMergedRegions.asScala.map(_.formatAsString).toSet
        val row    = sheet.getRow(2)

        (sheet.getRow(0).getCell(1).getStringCellValue shouldEqual "Luty 2026") and
          (sheet.getRow(0).getCell(15).getStringCellValue shouldEqual "Suma końcowa") and
          (merged shouldEqual Set("A1:A2", "B1:C1", "D1:E1", "F1:G1", "H1:I1", "J1:K1", "L1:M1", "N1:O1", "P1:Q1")) and
          (panes.getVerticalSplitPosition shouldEqual 1) and
          (panes.getHorizontalSplitPosition shouldEqual 2) and
          ((1 to 6).map(index => row.getCell(index).getCellType) must containTheSameElementsAs(Seq.fill(6)(CellType.BLANK))) and
          (row.getCell(7).getCellType shouldEqual CellType.NUMERIC) and
          (row.getCell(7).getNumericCellValue must beCloseTo(10d / 1440d, 1e-12)) and
          (row.getCell(7).getCellStyle.getDataFormatString shouldEqual "[h]:mm") and
          (row.getCell(8).getCellType shouldEqual CellType.NUMERIC) and
          (row.getCell(8).getNumericCellValue shouldEqual 1d) and
          (row.getCell(16).getNumericCellValue shouldEqual 10d) and
          (row.getCell(16).getCellStyle.getDataFormatString shouldEqual "0")
      }
    }

    "write the complete canonical projection with semantic merges, panes, types, styles, and blanks" in {
      withWorkbook(canonicalTable, measureLabels) { workbook =>
        val sheet              = workbook.getSheet("Pivot")
        val panes              = sheet.getPaneInformation
        val merged             = sheet.getMergedRegions.asScala.map(_.formatAsString).toSet
        val duration           = sheet.getRow(2).getCell(2)
        val blankAverage       = sheet.getRow(2).getCell(3)
        val zeroCount          = sheet.getRow(2).getCell(4)
        val subtotalDuration   = sheet.getRow(3).getCell(2)
        val grandTotalDuration = sheet.getRow(4).getCell(2)

        (workbook.getNumberOfSheets shouldEqual 1) and
          (sheet must not(beNull)) and
          (sheet.getLastRowNum shouldEqual 4) and
          (merged shouldEqual Set("A1:A2", "B1:B2", "C1:E1", "F1:H1", "I1:K1")) and
          (panes.getVerticalSplitPosition shouldEqual 2) and
          (panes.getHorizontalSplitPosition shouldEqual 2) and
          (sheet.getRow(0).getCell(0).getStringCellValue shouldEqual "Użytkownik") and
          (sheet.getRow(0).getCell(2).getStringCellValue shouldEqual "Lipiec 2026") and
          (sheet.getRow(1).getCell(2).getStringCellValue shouldEqual "Suma czasu") and
          (sheet.getRow(1).getCell(3).getStringCellValue shouldEqual "Średni czas") and
          (duration.getCellType shouldEqual CellType.NUMERIC) and
          (duration.getNumericCellValue must beCloseTo(7530d / 1440d, 1e-12)) and
          (duration.getCellStyle.getDataFormatString shouldEqual "[h]:mm") and
          (blankAverage.getCellType shouldEqual CellType.BLANK) and
          (zeroCount.getCellType shouldEqual CellType.NUMERIC) and
          (zeroCount.getNumericCellValue shouldEqual 0d) and
          (zeroCount.getCellStyle.getDataFormatString shouldEqual "0") and
          (font(workbook, sheet.getRow(0).getCell(0)).getBold must beTrue) and
          (duration.getCellStyle.getBorderBottom shouldEqual BorderStyle.THIN) and
          (font(workbook, subtotalDuration).getBold must beTrue) and
          (font(workbook, grandTotalDuration).getBold must beTrue)
      }
    }

    "preserve a two-level column hierarchy without merging different measure cells" in {
      withWorkbook(twoLevelTable, measureLabels) { workbook =>
        val sheet  = workbook.getSheet("Pivot")
        val merged = sheet.getMergedRegions.asScala.map(_.formatAsString).toSet
        val panes  = sheet.getPaneInformation

        (merged shouldEqual Set("A1:A3", "B1:G1", "B2:C2", "D2:E2", "F2:G2", "H1:I2")) and
          (panes.getVerticalSplitPosition shouldEqual 1) and
          (panes.getHorizontalSplitPosition shouldEqual 3) and
          (sheet.getRow(0).getCell(1).getStringCellValue shouldEqual "2026") and
          (sheet.getRow(1).getCell(1).getStringCellValue shouldEqual "Styczeń") and
          (sheet.getRow(1).getCell(5).getStringCellValue shouldEqual "Suma częściowa") and
          (sheet.getRow(0).getCell(7).getStringCellValue shouldEqual "Razem") and
          (sheet.getRow(2).getCell(1).getStringCellValue shouldEqual "Suma czasu") and
          (sheet.getRow(2).getCell(2).getStringCellValue shouldEqual "Liczba wpisów") and
          (merged.exists(range => range == "B3:C3" || range == "D3:E3" || range == "F3:G3" || range == "H3:I3") must beFalse)
      }
    }

    "handle empty, row-only, and column-only axes without synthetic labels" in {
      val emptyAxes = Table(
        rowFieldLabels = Vector.empty,
        columns = Vector(column(emptyCoordinate, Vector.empty, WorkUnitPivotTableValue.EntriesCount)),
        rows = Vector(DataRow(RowHeader(Vector.empty, CoordinateKind.Leaf), Vector(Some(Count(2L)))))
      )
      val rowOnly = Table(
        rowFieldLabels = Vector("Użytkownik"),
        columns = Vector(column(emptyCoordinate, Vector.empty, WorkUnitPivotTableValue.EntriesCount)),
        rows = Vector(DataRow(RowHeader(Vector(Some("Ala")), CoordinateKind.Leaf), Vector(Some(Count(2L)))))
      )
      val columnOnly = Table(
        rowFieldLabels = Vector.empty,
        columns = Vector(
          column(januaryCoordinate, Vector("2026", "Styczeń"), WorkUnitPivotTableValue.EntriesCount),
          column(grandTotalCoordinate, Vector("Razem"), WorkUnitPivotTableValue.EntriesCount)
        ),
        rows = Vector(DataRow(RowHeader(Vector.empty, CoordinateKind.Leaf), Vector(Some(Count(2L)), Some(Count(2L)))))
      )

      withWorkbook(emptyAxes, measureLabels) { workbook =>
        val sheet = workbook.getSheet("Pivot")
        val panes = sheet.getPaneInformation
        (sheet.getRow(0).getLastCellNum shouldEqual 1) and
          (sheet.getRow(0).getCell(0).getStringCellValue shouldEqual "Liczba wpisów") and
          (sheet.getRow(1).getCell(0).getNumericCellValue shouldEqual 2d) and
          (panes.getVerticalSplitPosition shouldEqual 0) and
          (panes.getHorizontalSplitPosition shouldEqual 1) and
          (sheet.getNumMergedRegions shouldEqual 0)
      } and
        withWorkbook(rowOnly, measureLabels) { workbook =>
          val sheet = workbook.getSheet("Pivot")
          val panes = sheet.getPaneInformation
          (sheet.getRow(0).getLastCellNum shouldEqual 2) and
            (panes.getVerticalSplitPosition shouldEqual 1) and
            (panes.getHorizontalSplitPosition shouldEqual 1) and
            (sheet.getNumMergedRegions shouldEqual 0)
        } and
        withWorkbook(columnOnly, measureLabels) { workbook =>
          val sheet  = workbook.getSheet("Pivot")
          val panes  = sheet.getPaneInformation
          val merged = sheet.getMergedRegions.asScala.map(_.formatAsString).toSet
          (panes.getVerticalSplitPosition shouldEqual 0) and
            (panes.getHorizontalSplitPosition shouldEqual 3) and
            (merged shouldEqual Set("B1:B2"))
        }
    }

    "keep Unicode and formula-like labels as explicit strings in a safe visible workbook" in {
      val table = Table(
        rowFieldLabels = Vector("=Pole 🐈"),
        columns = Vector(column(januaryCoordinate, Vector("+Grupa", "-Miesiąc"), WorkUnitPivotTableValue.EntriesCount)),
        rows = Vector(DataRow(RowHeader(Vector(Some("@Użytkownik Żółty 漢字")), CoordinateKind.Leaf), Vector(Some(Count(1L)))))
      )
      val labels: Map[WorkUnitPivotTableValue, String] = Map(WorkUnitPivotTableValue.EntriesCount -> "=Liczba")

      withWorkbook(table, labels) { workbook =>
        val sheet = workbook.getSheet("Pivot")
        val textCells = Seq(
          sheet.getRow(0).getCell(0) -> "=Pole 🐈",
          sheet.getRow(0).getCell(1) -> "+Grupa",
          sheet.getRow(1).getCell(1) -> "-Miesiąc",
          sheet.getRow(2).getCell(1) -> "=Liczba",
          sheet.getRow(3).getCell(0) -> "@Użytkownik Żółty 漢字"
        )
        val allCells = sheet.iterator().asScala.flatMap(_.cellIterator().asScala).toVector

        (textCells.map(_._1.getCellType) should containTheSameElementsAs(Seq.fill(textCells.size)(CellType.STRING))) and
          (textCells.map { case (cell, _) => cell.getStringCellValue } shouldEqual textCells.map(_._2)) and
          (allCells.count(_.getCellType == CellType.FORMULA) shouldEqual 0) and
          (workbook.getExternalLinksTable.isEmpty must beTrue) and
          (workbook.getSheetVisibility(0) shouldEqual SheetVisibility.VISIBLE) and
          (workbook.isSheetHidden(0) must beFalse) and
          (workbook.isSheetVeryHidden(0) must beFalse)
      }
    }

    "preserve the exact XLSX integer boundary and reject the first imprecise duration and count before writing" in {
      val maximumExactInteger = BigInt(1) << 53
      val exactDurationBelow  = BigInt("9007199254740971")
      val exactTable = Table(
        rowFieldLabels = Vector.empty,
        columns = Vector(
          column(emptyCoordinate, Vector.empty, WorkUnitPivotTableValue.DurationSum),
          column(emptyCoordinate, Vector.empty, WorkUnitPivotTableValue.DurationAvg),
          column(emptyCoordinate, Vector.empty, WorkUnitPivotTableValue.EntriesCount)
        ),
        rows = Vector(
          DataRow(
            RowHeader(Vector.empty, CoordinateKind.Leaf),
            Vector(Some(DurationMinutes(maximumExactInteger)), Some(DurationMinutes(exactDurationBelow)), Some(Count(maximumExactInteger.toLong)))
          )
        )
      )
      val lossyDuration     = singleValueTable(DurationMinutes(BigInt("9007199254740972")), WorkUnitPivotTableValue.DurationSum)
      val impreciseDuration = singleValueTable(DurationMinutes(maximumExactInteger + 1), WorkUnitPivotTableValue.DurationSum)
      val impreciseCount    = singleValueTable(Count((maximumExactInteger + 1).toLong), WorkUnitPivotTableValue.EntriesCount)

      withWorkbook(exactTable, measureLabels) { workbook =>
        val row                    = workbook.getSheet("Pivot").getRow(1)
        val boundaryDurationResult = BigDecimal.decimal(row.getCell(0).getNumericCellValue * 1440d).toBigIntExact
        val lowerDurationResult    = BigDecimal.decimal(row.getCell(1).getNumericCellValue * 1440d).toBigIntExact
        val countResult            = BigDecimal.decimal(row.getCell(2).getNumericCellValue).toBigIntExact
        (boundaryDurationResult shouldEqual Some(maximumExactInteger)) and
          (lowerDurationResult shouldEqual Some(exactDurationBelow)) and
          (countResult shouldEqual Some(maximumExactInteger)) and
          (row.getCell(0).getCellStyle.getDataFormatString shouldEqual "[h]:mm") and
          (row.getCell(1).getCellStyle.getDataFormatString shouldEqual "[h]:mm") and
          (row.getCell(2).getCellStyle.getDataFormatString shouldEqual "0")
      } and
        Seq(lossyDuration, impreciseDuration, impreciseCount)
          .map { table =>
            val rejected = new ByteArrayOutputStream()
            (PivotXlsxEncoder.write(table, measureLabels, rejected) must throwA[IllegalArgumentException]) and
              (rejected.size shouldEqual 0)
          }
          .reduce(_ and _)
    }

    "bound every column width without font-dependent autosizing" in {
      val longLabel = "Bardzo długi nagłówek 🐈" * 30
      val table = Table(
        rowFieldLabels = Vector(longLabel),
        columns = Vector(column(januaryCoordinate, Vector(longLabel, longLabel), WorkUnitPivotTableValue.EntriesCount)),
        rows = Vector(DataRow(RowHeader(Vector(Some(longLabel)), CoordinateKind.Leaf), Vector(Some(Count(1L)))))
      )

      withWorkbook(table, Map(WorkUnitPivotTableValue.EntriesCount -> longLabel)) { workbook =>
        val sheet  = workbook.getSheet("Pivot")
        val widths = (0 until 2).map(sheet.getColumnWidth)
        (widths must contain(beGreaterThanOrEqualTo(8 * 256)).foreach) and
          (widths must contain(beLessThanOrEqualTo(48 * 256)).foreach) and
          (widths.max shouldEqual 48 * 256)
      }
    }

    "validate metrics and rectangular shape before writing any workbook bytes" in {
      val negativeDuration = singleValueTable(DurationMinutes(BigInt(-1)), WorkUnitPivotTableValue.DurationSum)
      val negativeCount    = singleValueTable(Count(-1L), WorkUnitPivotTableValue.EntriesCount)
      val shortHeader = Table(
        rowFieldLabels = Vector("First", "Second"),
        columns = Vector(column(emptyCoordinate, Vector.empty, WorkUnitPivotTableValue.EntriesCount)),
        rows = Vector(DataRow(RowHeader(Vector(Some("only first")), CoordinateKind.Leaf), Vector(Some(Count(1L)))))
      )
      val shortValues = Table(
        rowFieldLabels = Vector("First"),
        columns = Vector(column(emptyCoordinate, Vector.empty, WorkUnitPivotTableValue.EntriesCount)),
        rows = Vector(DataRow(RowHeader(Vector(Some("first")), CoordinateKind.Leaf), Vector.empty))
      )
      val invalid = Seq(
        negativeDuration                                                  -> measureLabels,
        negativeCount                                                     -> measureLabels,
        shortHeader                                                       -> measureLabels,
        shortValues                                                       -> measureLabels,
        singleValueTable(Count(1L), WorkUnitPivotTableValue.EntriesCount) -> Map.empty[WorkUnitPivotTableValue, String]
      )

      invalid
        .map { case (table, labels) =>
          val output = new ByteArrayOutputStream()
          (PivotXlsxEncoder.write(table, labels, output) must throwA[IllegalArgumentException]) and
            (output.size shouldEqual 0)
        }
        .reduce(_ and _)
    }

    "write directly to the caller stream without closing it" in {
      val output = new OwnershipOutputStream

      PivotXlsxEncoder.write(canonicalTable, measureLabels, output)
      val sizeAfterWorkbook = output.size
      output.write(0)

      val wroteWorkbook: Result = sizeAfterWorkbook must beGreaterThan(0)
      val keptOpen: Result      = output.closed must beFalse
      val remainsUsable: Result = output.size shouldEqual sizeAfterWorkbook + 1
      wroteWorkbook and keptOpen and remainsUsable
    }

    "produce deterministic logical workbook content across independent writes" in {
      logicalSnapshot(canonicalTable, measureLabels) shouldEqual logicalSnapshot(canonicalTable, measureLabels)
    }

    "set deterministic non-user workbook timestamps" in {
      withWorkbook(canonicalTable, measureLabels) { workbook =>
        val core = workbook.getProperties.getCoreProperties
        (core.getCreated shouldEqual new Date(0L)) and
          (core.getModified shouldEqual new Date(0L))
      }
    }
  }

  private val measureLabels: Map[WorkUnitPivotTableValue, String] = Map(
    WorkUnitPivotTableValue.DurationSum  -> "Suma czasu",
    WorkUnitPivotTableValue.DurationAvg  -> "Średni czas",
    WorkUnitPivotTableValue.EntriesCount -> "Liczba wpisów"
  )

  private val emptyCoordinate        = Coordinate(CoordinateKind.Leaf, Seq.empty)
  private val grandTotalCoordinate   = Coordinate(CoordinateKind.GrandTotal, Seq.empty)
  private val year2026               = PathSegment(WorkUnitPivotTableKey.YearKey, FieldValue.Year(2026))
  private val january2026            = PathSegment(WorkUnitPivotTableKey.MonthYearKey, FieldValue.Month(YearMonth.of(2026, 1)))
  private val february2026           = PathSegment(WorkUnitPivotTableKey.MonthYearKey, FieldValue.Month(YearMonth.of(2026, 2)))
  private val januaryCoordinate      = Coordinate(CoordinateKind.Leaf, Seq(year2026, january2026))
  private val februaryCoordinate     = Coordinate(CoordinateKind.Leaf, Seq(year2026, february2026))
  private val yearSubtotalCoordinate = Coordinate(CoordinateKind.Subtotal, Seq(year2026))

  private val oneLevelJanuary  = Coordinate(CoordinateKind.Leaf, Seq(january2026))
  private val oneLevelFebruary = Coordinate(CoordinateKind.Leaf, Seq(february2026))

  private val canonicalColumns =
    Vector(oneLevelJanuary -> "Lipiec 2026", oneLevelFebruary -> "Sierpień 2026", grandTotalCoordinate -> "Razem").flatMap {
      case (coordinate, label) =>
        Vector(
          column(coordinate, Vector(label), WorkUnitPivotTableValue.DurationSum),
          column(coordinate, Vector(label), WorkUnitPivotTableValue.DurationAvg),
          column(coordinate, Vector(label), WorkUnitPivotTableValue.EntriesCount)
        )
    }

  private val canonicalTable = Table(
    rowFieldLabels = Vector("Użytkownik", "Projekt"),
    columns = canonicalColumns,
    rows = Vector(
      DataRow(
        RowHeader(Vector(Some("=Użytkownik Żółty"), Some("Projekt 🐈")), CoordinateKind.Leaf),
        Vector(duration(7530), None, count(0), duration(60), duration(0), count(1), duration(7590), duration(3795), count(2))
      ),
      DataRow(
        RowHeader(Vector(Some("=Użytkownik Żółty"), Some("Suma częściowa")), CoordinateKind.Subtotal),
        Vector(duration(7530), None, count(0), duration(60), duration(0), count(1), duration(7590), duration(3795), count(2))
      ),
      DataRow(
        RowHeader(Vector(Some("Razem"), None), CoordinateKind.GrandTotal),
        Vector(duration(7530), None, count(0), duration(60), duration(0), count(1), duration(7590), duration(3795), count(2))
      )
    )
  )

  private val twoLevelColumns = Vector(
    januaryCoordinate      -> Vector("2026", "Styczeń"),
    februaryCoordinate     -> Vector("2026", "Luty"),
    yearSubtotalCoordinate -> Vector("2026", "Suma częściowa"),
    grandTotalCoordinate   -> Vector("Razem")
  ).flatMap { case (coordinate, labels) =>
    Vector(column(coordinate, labels, WorkUnitPivotTableValue.DurationSum), column(coordinate, labels, WorkUnitPivotTableValue.EntriesCount))
  }

  private val twoLevelTable = Table(
    rowFieldLabels = Vector("Użytkownik"),
    columns = twoLevelColumns,
    rows = Vector(
      DataRow(
        RowHeader(Vector(Some("Ala")), CoordinateKind.Leaf),
        Vector(duration(10), count(1), duration(20), count(2), duration(30), count(3), duration(30), count(3))
      )
    )
  )

  private def singleValueTable(value: Value, measure: WorkUnitPivotTableValue): Table =
    Table(
      rowFieldLabels = Vector.empty,
      columns = Vector(column(emptyCoordinate, Vector.empty, measure)),
      rows = Vector(DataRow(RowHeader(Vector.empty, CoordinateKind.Leaf), Vector(Some(value))))
    )

  private def column(
    coordinate: Coordinate,
    labels: Vector[String],
    measure: WorkUnitPivotTableValue
  ): ColumnHeader = ColumnHeader(coordinate, labels, measure)

  private def duration(minutes: Long): Option[Value] = Some(DurationMinutes(BigInt(minutes)))
  private def count(value: Long): Option[Value]      = Some(Count(value))

  private def withWorkbook[A](table: Table, labels: Map[WorkUnitPivotTableValue, String])(read: XSSFWorkbook => A): A = {
    val path = Files.createTempFile("pivot-xlsx-spec-", ".xlsx")
    try {
      Using.resource(Files.newOutputStream(path))(PivotXlsxEncoder.write(table, labels, _))
      // File-backed reading avoids POI's large buffers for ZIP entries with unknown sizes.
      Using.resource(new XSSFWorkbook(path.toFile))(read)
    } finally Files.delete(path)
  }

  private def font(workbook: XSSFWorkbook, cell: Cell) =
    workbook.getFontAt(cell.getCellStyle.getFontIndex)

  private def logicalSnapshot(table: Table, labels: Map[WorkUnitPivotTableValue, String]): String =
    withWorkbook(table, labels) { workbook =>
      val sheet = workbook.getSheet("Pivot")
      val panes = sheet.getPaneInformation
      val cells = sheet
        .iterator()
        .asScala
        .flatMap(_.cellIterator().asScala)
        .map { cell =>
          val value = cell.getCellType match {
            case CellType.STRING  => cell.getStringCellValue
            case CellType.NUMERIC => java.lang.Double.toHexString(cell.getNumericCellValue)
            case CellType.BLANK   => ""
            case other            => other.toString
          }
          s"${cell.getRowIndex}:${cell.getColumnIndex}:${cell.getCellType}:$value:${cell.getCellStyle.getDataFormatString}"
        }
        .mkString("|")
      val merged = sheet.getMergedRegions.asScala.map(_.formatAsString).sorted.mkString(",")
      val widths = (0 until sheet.getRow(0).getLastCellNum.toInt).map(sheet.getColumnWidth).mkString(",")
      s"${sheet.getSheetName};${workbook.getSheetVisibility(0)};${panes.getVerticalSplitPosition}:${panes.getHorizontalSplitPosition};$merged;$widths;$cells"
    }

  private final class OwnershipOutputStream extends OutputStream {
    private val delegate = new ByteArrayOutputStream()
    var closed           = false

    def size: Int = delegate.size()

    override def write(value: Int): Unit = {
      ensureOpen()
      delegate.write(value)
    }

    override def write(
      bytes: Array[Byte],
      offset: Int,
      length: Int
    ): Unit = {
      ensureOpen()
      delegate.write(bytes, offset, length)
    }

    override def close(): Unit = closed = true

    private def ensureOpen(): Unit =
      if (closed) throw new IOException("stream was closed")
  }
}
