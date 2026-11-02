package pl.iterators.epoca.`export`.domain

import org.apache.poi.ss.usermodel._
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel.{XSSFCellStyle, XSSFFont, XSSFSheet, XSSFWorkbook}
import pl.iterators.epoca.export.domain.PivotExportProjection.Value.{Count, DurationMinutes}
import pl.iterators.epoca.export.domain.PivotExportProjection.{Table, Value}
import pl.iterators.epoca.stats.domain.StatsDomain.WorkUnitPivotTableValue
import pl.iterators.epoca.stats.domain.WorkUnitPivotQuery.{Coordinate, CoordinateKind, PathSegment}

import java.io.OutputStream
import java.util.{Date, Optional}

object PivotXlsxEncoder {
  private val SheetName          = "Pivot"
  private val DurationDataFormat = "[h]:mm"
  private val CountDataFormat    = "0"
  private val MinutesPerDay      = 1440d
  private val MinimumColumnWidth = 8
  private val MaximumColumnWidth = 48
  private val ColumnWidthUnit    = 256
  private val ColumnWidthPadding = 2
  // Excel numeric cells are IEEE-754 doubles, which represent every integer exactly through 2^53.
  private val MaximumExactInteger = BigInt(1) << 53

  def write(
    table: Table,
    measureLabels: Map[WorkUnitPivotTableValue, String],
    output: OutputStream
  ): Unit = {
    validate(table, measureLabels)
    val workbook = new XSSFWorkbook()
    try {
      setDeterministicMetadata(workbook)
      val sheet  = workbook.createSheet(SheetName)
      val styles = Styles.create(workbook)
      writeHeaders(sheet, table, measureLabels, styles)
      writeRows(sheet, table, styles)
      sheet.createFreezePane(table.rowFieldLabels.size, headerRowCount(table))
      setBoundedWidths(sheet, table, measureLabels)
      workbook.write(output)
    } finally workbook.close()
  }

  private def validate(table: Table, measureLabels: Map[WorkUnitPivotTableValue, String]): Unit = {
    table.columns.foreach { column =>
      require(measureLabels.contains(column.measure), s"missing measure label: ${column.measure.entryName}")
      val expectedLabelCount = column.coordinate.kind match {
        case CoordinateKind.Leaf       => column.coordinate.path.size
        case CoordinateKind.Subtotal   => column.coordinate.path.size + 1
        case CoordinateKind.GrandTotal => 1
      }
      require(
        column.labels.size == expectedLabelCount,
        s"column label count ${column.labels.size} did not match $expectedLabelCount for ${column.coordinate.kind.entryName}"
      )
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

  private def validateValue(value: Value): Unit =
    value match {
      case DurationMinutes(minutes) =>
        require(minutes >= 0, s"duration minutes must be non-negative: $minutes")
        require(minutes <= MaximumExactInteger, s"duration minutes exceed exact XLSX numeric range: $minutes")
        // The bound makes minutes.toDouble exact; this additionally proves the emitted day fraction recovers that same integer.
        require(toExcelDays(minutes) * MinutesPerDay == minutes.toDouble, s"duration minutes lose precision in XLSX day fraction: $minutes")
      case Count(count) =>
        require(count >= 0L, s"count must be non-negative: $count")
        require(BigInt(count) <= MaximumExactInteger, s"count exceeds exact XLSX numeric range: $count")
    }

  private def setDeterministicMetadata(workbook: XSSFWorkbook): Unit = {
    val core = workbook.getProperties.getCoreProperties
    core.setCreated(Optional.of(new Date(0L)))
    core.setModified(Optional.of(new Date(0L)))
  }

  private def writeHeaders(
    sheet: XSSFSheet,
    table: Table,
    measureLabels: Map[WorkUnitPivotTableValue, String],
    styles: Styles
  ): Unit = {
    val coordinateDepth = headerDepth(table)
    val rowCount        = coordinateDepth + 1
    (0 until rowCount).foreach(index => sheet.createRow(index))

    table.rowFieldLabels.zipWithIndex.foreach { case (label, columnIndex) =>
      fillStyledCells(sheet, 0, rowCount - 1, columnIndex, columnIndex, styles.header)
      writeText(sheet.getRow(0), columnIndex, label, styles.header)
      addMergedRegion(sheet, 0, rowCount - 1, columnIndex, columnIndex)
    }

    val valueColumnOffset = table.rowFieldLabels.size
    (0 until coordinateDepth).foreach { level =>
      headerGroups(table, level).foreach { group =>
        val firstColumn = valueColumnOffset + group.first
        val lastColumn  = valueColumnOffset + group.last
        val lastRow =
          if (group.labelCount < coordinateDepth && level == group.labelCount - 1) coordinateDepth - 1
          else level
        fillStyledCells(sheet, level, lastRow, firstColumn, lastColumn, styles.header)
        writeText(sheet.getRow(level), firstColumn, group.label, styles.header)
        addMergedRegion(sheet, level, lastRow, firstColumn, lastColumn)
      }
    }

    table.columns.zipWithIndex.foreach { case (column, index) =>
      writeText(sheet.getRow(coordinateDepth), valueColumnOffset + index, measureLabels(column.measure), styles.header)
    }
  }

  private def headerGroups(table: Table, level: Int): Vector[HeaderGroup] = {
    val indexed = table.columns.zipWithIndex.filter { case (column, _) => level < column.labels.size }
    indexed.foldLeft(Vector.empty[HeaderGroup]) { case (groups, (column, index)) =>
      val key = headerGroupKey(column.coordinate, level)
      groups.lastOption match {
        case Some(previous) if previous.last + 1 == index && previous.key == key =>
          groups.updated(groups.size - 1, previous.copy(last = index))
        case _ =>
          groups :+ HeaderGroup(index, index, key, column.labels(level), column.labels.size)
      }
    }
  }

  private def headerGroupKey(coordinate: Coordinate, level: Int): HeaderGroupKey =
    if (level < coordinate.path.size) HeaderGroupKey.PathPrefix(coordinate.path.take(level + 1).toVector)
    else HeaderGroupKey.Terminal(coordinate.kind, coordinate.path.toVector)

  private def writeRows(
    sheet: XSSFSheet,
    table: Table,
    styles: Styles
  ): Unit = {
    val firstDataRow = headerRowCount(table)
    val valueOffset  = table.rowFieldLabels.size
    table.rows.zipWithIndex.foreach { case (dataRow, rowIndex) =>
      val row       = sheet.createRow(firstDataRow + rowIndex)
      val textStyle = styles.text(dataRow.header.kind)
      dataRow.header.values.zipWithIndex.foreach {
        case (Some(label), columnIndex) => writeText(row, columnIndex, label, textStyle)
        case (None, columnIndex)        => writeBlank(row, columnIndex, textStyle)
      }
      dataRow.values.zip(table.columns).zipWithIndex.foreach { case ((value, column), index) =>
        val columnIndex = valueOffset + index
        value match {
          case None        => writeBlank(row, columnIndex, styles.numeric(dataRow.header.kind, column.measure))
          case Some(value) => writeValue(row, columnIndex, value, dataRow.header.kind, styles)
        }
      }
    }
  }

  private def writeValue(
    row: Row,
    columnIndex: Int,
    value: Value,
    kind: CoordinateKind,
    styles: Styles
  ): Unit =
    value match {
      case DurationMinutes(minutes) =>
        val cell = row.createCell(columnIndex, CellType.NUMERIC)
        cell.setCellValue(toExcelDays(minutes))
        cell.setCellStyle(styles.duration(kind))
      case Count(count) =>
        val cell = row.createCell(columnIndex, CellType.NUMERIC)
        cell.setCellValue(count.toDouble)
        cell.setCellStyle(styles.count(kind))
    }

  private def writeText(
    row: Row,
    columnIndex: Int,
    value: String,
    style: CellStyle
  ): Unit = {
    val cell = row.createCell(columnIndex, CellType.STRING)
    cell.setCellValue(value)
    cell.setCellStyle(style)
  }

  private def writeBlank(
    row: Row,
    columnIndex: Int,
    style: CellStyle
  ): Unit = {
    val cell = Option(row.getCell(columnIndex)).getOrElse(row.createCell(columnIndex, CellType.BLANK))
    cell.setBlank()
    cell.setCellStyle(style)
  }

  private def fillStyledCells(
    sheet: XSSFSheet,
    firstRow: Int,
    lastRow: Int,
    firstColumn: Int,
    lastColumn: Int,
    style: CellStyle
  ): Unit =
    for {
      rowIndex    <- firstRow to lastRow
      columnIndex <- firstColumn to lastColumn
    } writeBlank(sheet.getRow(rowIndex), columnIndex, style)

  private def addMergedRegion(
    sheet: XSSFSheet,
    firstRow: Int,
    lastRow: Int,
    firstColumn: Int,
    lastColumn: Int
  ): Unit =
    if (firstRow != lastRow || firstColumn != lastColumn) {
      val _ = sheet.addMergedRegion(new CellRangeAddress(firstRow, lastRow, firstColumn, lastColumn))
    }

  private def setBoundedWidths(
    sheet: XSSFSheet,
    table: Table,
    measureLabels: Map[WorkUnitPivotTableValue, String]
  ): Unit = {
    table.rowFieldLabels.indices.foreach { index =>
      val values = table.rowFieldLabels(index) +: table.rows.iterator.flatMap(_.header.values(index)).toVector
      sheet.setColumnWidth(index, boundedWidth(values))
    }
    table.columns.indices.foreach { index =>
      val column = table.columns(index)
      val labels = column.labels :+ measureLabels(column.measure)
      val values = table.rows.iterator.flatMap(_.values(index).map(valueLength)).map(length => "0" * length).toVector
      sheet.setColumnWidth(table.rowFieldLabels.size + index, boundedWidth(labels ++ values))
    }
  }

  private def boundedWidth(values: Iterable[String]): Int = {
    val longest = values.iterator.map(codePointLength).foldLeft(0)(Math.max)
    longest.max(MinimumColumnWidth).min(MaximumColumnWidth - ColumnWidthPadding) + ColumnWidthPadding
  } * ColumnWidthUnit

  private def codePointLength(value: String): Int =
    value.codePointCount(0, value.length)

  private def valueLength(value: Value): Int =
    value match {
      case DurationMinutes(minutes) => (minutes / 60).toString.length + 3
      case Count(count)             => count.toString.length
    }

  private def headerDepth(table: Table): Int =
    table.columns.iterator.map(_.labels.size).foldLeft(0)(Math.max)

  private def headerRowCount(table: Table): Int = headerDepth(table) + 1

  private def toExcelDays(minutes: BigInt): Double = minutes.toDouble / MinutesPerDay

  private final case class HeaderGroup(
    first: Int,
    last: Int,
    key: HeaderGroupKey,
    label: String,
    labelCount: Int)

  private sealed trait HeaderGroupKey
  private object HeaderGroupKey {
    final case class PathPrefix(path: Vector[PathSegment])                     extends HeaderGroupKey
    final case class Terminal(kind: CoordinateKind, path: Vector[PathSegment]) extends HeaderGroupKey
  }

  private final case class Styles(
    header: XSSFCellStyle,
    bodyText: XSSFCellStyle,
    bodyDuration: XSSFCellStyle,
    bodyCount: XSSFCellStyle,
    subtotalText: XSSFCellStyle,
    subtotalDuration: XSSFCellStyle,
    subtotalCount: XSSFCellStyle,
    grandTotalText: XSSFCellStyle,
    grandTotalDuration: XSSFCellStyle,
    grandTotalCount: XSSFCellStyle) {

    def text(kind: CoordinateKind): XSSFCellStyle = kind match {
      case CoordinateKind.Leaf       => bodyText
      case CoordinateKind.Subtotal   => subtotalText
      case CoordinateKind.GrandTotal => grandTotalText
    }

    def duration(kind: CoordinateKind): XSSFCellStyle = kind match {
      case CoordinateKind.Leaf       => bodyDuration
      case CoordinateKind.Subtotal   => subtotalDuration
      case CoordinateKind.GrandTotal => grandTotalDuration
    }

    def count(kind: CoordinateKind): XSSFCellStyle = kind match {
      case CoordinateKind.Leaf       => bodyCount
      case CoordinateKind.Subtotal   => subtotalCount
      case CoordinateKind.GrandTotal => grandTotalCount
    }

    def numeric(kind: CoordinateKind, measure: WorkUnitPivotTableValue): XSSFCellStyle =
      measure match {
        case WorkUnitPivotTableValue.DurationSum | WorkUnitPivotTableValue.DurationAvg => duration(kind)
        case WorkUnitPivotTableValue.EntriesCount                                      => count(kind)
      }
  }

  private object Styles {
    def create(workbook: XSSFWorkbook): Styles = {
      val regularFont = createFont(workbook, bold = false)
      val boldFont    = createFont(workbook, bold = true)
      val dataFormat  = workbook.createDataFormat()
      Styles(
        header = createStyle(workbook, boldFont, IndexedColors.GREY_25_PERCENT, HorizontalAlignment.CENTER, None),
        bodyText = createStyle(workbook, regularFont, IndexedColors.WHITE, HorizontalAlignment.LEFT, None),
        bodyDuration =
          createStyle(workbook, regularFont, IndexedColors.WHITE, HorizontalAlignment.RIGHT, Some(dataFormat.getFormat(DurationDataFormat))),
        bodyCount = createStyle(workbook, regularFont, IndexedColors.WHITE, HorizontalAlignment.RIGHT, Some(dataFormat.getFormat(CountDataFormat))),
        subtotalText = createStyle(workbook, boldFont, IndexedColors.LIGHT_CORNFLOWER_BLUE, HorizontalAlignment.LEFT, None),
        subtotalDuration = createStyle(
          workbook,
          boldFont,
          IndexedColors.LIGHT_CORNFLOWER_BLUE,
          HorizontalAlignment.RIGHT,
          Some(dataFormat.getFormat(DurationDataFormat))
        ),
        subtotalCount = createStyle(
          workbook,
          boldFont,
          IndexedColors.LIGHT_CORNFLOWER_BLUE,
          HorizontalAlignment.RIGHT,
          Some(dataFormat.getFormat(CountDataFormat))
        ),
        grandTotalText = createStyle(workbook, boldFont, IndexedColors.LIGHT_ORANGE, HorizontalAlignment.LEFT, None),
        grandTotalDuration =
          createStyle(workbook, boldFont, IndexedColors.LIGHT_ORANGE, HorizontalAlignment.RIGHT, Some(dataFormat.getFormat(DurationDataFormat))),
        grandTotalCount =
          createStyle(workbook, boldFont, IndexedColors.LIGHT_ORANGE, HorizontalAlignment.RIGHT, Some(dataFormat.getFormat(CountDataFormat)))
      )
    }

    private def createFont(workbook: XSSFWorkbook, bold: Boolean): XSSFFont = {
      val font = workbook.createFont()
      font.setBold(bold)
      font
    }

    private def createStyle(
      workbook: XSSFWorkbook,
      font: XSSFFont,
      fill: IndexedColors,
      alignment: HorizontalAlignment,
      dataFormat: Option[Short]
    ): XSSFCellStyle = {
      val style = workbook.createCellStyle()
      style.setFont(font)
      style.setFillForegroundColor(fill.getIndex)
      style.setFillPattern(FillPatternType.SOLID_FOREGROUND)
      style.setAlignment(alignment)
      style.setVerticalAlignment(VerticalAlignment.CENTER)
      style.setWrapText(true)
      style.setBorderTop(BorderStyle.THIN)
      style.setBorderRight(BorderStyle.THIN)
      style.setBorderBottom(BorderStyle.THIN)
      style.setBorderLeft(BorderStyle.THIN)
      dataFormat.foreach(style.setDataFormat)
      style
    }
  }
}
