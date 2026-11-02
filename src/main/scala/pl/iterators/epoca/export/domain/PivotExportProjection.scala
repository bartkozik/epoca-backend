package pl.iterators.epoca.`export`.domain

import pl.iterators.epoca.export.domain.WorkUnitPivotExport.{Presentation, Projection}
import pl.iterators.epoca.stats.domain.StatsDomain.{WorkUnitPivotTableKey, WorkUnitPivotTableValue}
import pl.iterators.epoca.stats.domain.WorkUnitPivotQuery
import pl.iterators.epoca.stats.domain.WorkUnitPivotQuery.{Cell, Coordinate, CoordinateKind, FieldValue, PathSegment, ResultTooLargeError}
import pl.iterators.epoca.stats.services.WorkUnitPivotQueryAssembler.comparePaths

import java.time.temporal.ChronoUnit
import java.time.{Duration, LocalDate, YearMonth}

object PivotExportProjection {
  final case class ColumnHeader(
    coordinate: Coordinate,
    labels: Vector[String],
    measure: WorkUnitPivotTableValue)
  final case class RowHeader(values: Vector[Option[String]], kind: CoordinateKind)

  sealed trait Value
  object Value {
    final case class DurationMinutes(value: BigInt) extends Value
    final case class Count(value: Long)             extends Value
  }

  final case class DataRow(header: RowHeader, values: Vector[Option[Value]])
  final case class Table(
    rowFieldLabels: Vector[String],
    columns: Vector[ColumnHeader],
    rows: Vector[DataRow])

  private val NanosPerSecond = BigInt(1000000000L)
  private val NanosPerMinute = BigInt(60L) * NanosPerSecond
  private val HalfMinute     = BigInt(30L) * NanosPerSecond

  def build(
    response: WorkUnitPivotQuery.Response,
    request: WorkUnitPivotQuery.Request,
    presentation: Presentation
  ): Table = {
    val rowCoordinates    = orderedCoordinates(response.cells.iterator.map(_.row), request.rows)
    val columnCoordinates = orderedCoordinates(response.cells.iterator.map(_.column), request.columns)
    buildTable(response, request, presentation, rowCoordinates, columnCoordinates)
  }

  def build(
    response: WorkUnitPivotQuery.Response,
    request: WorkUnitPivotQuery.Request,
    projection: Option[Projection],
    dateFrom: LocalDate,
    presentation: Presentation
  ): Either[ResultTooLargeError, Table] =
    projection match {
      case Some(value) if request.columns == Seq(WorkUnitPivotTableKey.MonthYearKey) =>
        val effectiveDateTo = value.effectiveDateTo
        require(!effectiveDateTo.isBefore(dateFrom), s"projection effectiveDateTo $effectiveDateTo is before dateFrom $dateFrom")
        val rowCoordinates       = orderedCoordinates(response.cells.iterator.map(_.row), request.rows)
        val sparseCoordinates    = orderedCoordinates(response.cells.iterator.map(_.column), request.columns)
        val startMonth           = YearMonth.from(dateFrom)
        val endMonth             = YearMonth.from(effectiveDateTo)
        val monthCount           = ChronoUnit.MONTHS.between(startMonth, endMonth) + 1L
        val grandTotal           = sparseCoordinates.find(_.kind == CoordinateKind.GrandTotal)
        val completedColumnCount = saturatingAdd(monthCount, if (grandTotal.isDefined) 1L else 0L)
        val estimated            = saturatingDisplayValueCellCount(rowCoordinates.size.toLong, completedColumnCount, request.values.size.toLong)
        val maximum              = response.result.maxDisplayValueCellCount

        if (estimated > maximum)
          Left(ResultTooLargeError(estimatedDisplayValueCellCount = estimated, maxDisplayValueCellCount = maximum))
        else {
          val existingMonths = sparseCoordinates.iterator.collect {
            case coordinate @ Coordinate(CoordinateKind.Leaf, Seq(PathSegment(WorkUnitPivotTableKey.MonthYearKey, FieldValue.Month(month)))) =>
              month -> coordinate
          }.toMap
          val completedMonths = Vector.tabulate(monthCount.toInt) { offset =>
            val month = startMonth.plusMonths(offset.toLong)
            existingMonths.getOrElse(
              month,
              Coordinate(CoordinateKind.Leaf, Seq(PathSegment(WorkUnitPivotTableKey.MonthYearKey, FieldValue.Month(month))))
            )
          }
          val columnCoordinates = grandTotal.fold(completedMonths)(completedMonths :+ _)
          Right(buildTable(response, request, presentation, rowCoordinates, columnCoordinates))
        }
      case _ => Right(build(response, request, presentation))
    }

  private[domain] def saturatingDisplayValueCellCount(
    first: Long,
    second: Long,
    third: Long
  ): Long = {
    require(first >= 0L && second >= 0L && third >= 0L, "display value cell count factors must be non-negative")
    val result = BigInt(first) * BigInt(second) * BigInt(third)
    result.min(BigInt(Long.MaxValue)).toLong
  }

  private def saturatingAdd(first: Long, second: Long): Long =
    (BigInt(first) + BigInt(second)).min(BigInt(Long.MaxValue)).toLong

  private def buildTable(
    response: WorkUnitPivotQuery.Response,
    request: WorkUnitPivotQuery.Request,
    presentation: Presentation,
    rowCoordinates: Vector[Coordinate],
    columnCoordinates: Vector[Coordinate]
  ): Table = {
    val indexedCells = indexCells(response.cells)
    val memberLabels = presentation.memberLabels.iterator.map(label => (label.key -> label.value) -> label.label).toMap

    val columns = columnCoordinates.flatMap { coordinate =>
      val labels = columnLabels(coordinate, presentation, memberLabels)
      request.values.iterator.map(measure => ColumnHeader(coordinate, labels, measure))
    }
    val rows = rowCoordinates.map { rowCoordinate =>
      val values = columnCoordinates.flatMap { columnCoordinate =>
        val cell = indexedCells.get(rowCoordinate -> columnCoordinate)
        request.values.iterator.map(measure => cell.flatMap(projectValue(_, measure)))
      }
      DataRow(rowHeader(rowCoordinate, request.rows, presentation, memberLabels), values)
    }

    Table(rowFieldLabels = request.rows.iterator.map(presentation.fieldLabels).toVector, columns = columns, rows = rows)
  }

  private def orderedCoordinates(coordinates: Iterator[Coordinate], fields: Seq[WorkUnitPivotTableKey]): Vector[Coordinate] =
    distinctPreservingFirst(coordinates)
      .sortWith((left, right) => comparePaths(fields, left.path, right.path) < 0)

  def roundedMinutes(duration: Duration): BigInt = {
    require(!duration.isNegative, s"duration must be non-negative: $duration")
    val totalNanos = BigInt(duration.getSeconds) * NanosPerSecond + BigInt(duration.getNano)
    (totalNanos + HalfMinute) / NanosPerMinute
  }

  private def distinctPreservingFirst(coordinates: Iterator[Coordinate]): Vector[Coordinate] = {
    val (_, result) = coordinates.foldLeft(Set.empty[Coordinate] -> Vector.empty[Coordinate]) { case ((seen, result), coordinate) =>
      if (seen(coordinate)) seen -> result
      else (seen + coordinate)   -> (result :+ coordinate)
    }
    result
  }

  private def indexCells(cells: Seq[Cell]): Map[(Coordinate, Coordinate), Cell] =
    cells.foldLeft(Map.empty[(Coordinate, Coordinate), Cell]) { (indexed, cell) =>
      val key = cell.row -> cell.column
      if (indexed.contains(key))
        throw new IllegalStateException(s"duplicate pivot cell coordinates: row=${cell.row}, column=${cell.column}")
      indexed.updated(key, cell)
    }

  private def rowHeader(
    coordinate: Coordinate,
    fields: Seq[WorkUnitPivotTableKey],
    presentation: Presentation,
    memberLabels: Map[(WorkUnitPivotTableKey, FieldValue), String]
  ): RowHeader = {
    val pathValues = coordinate.path.iterator.map(segment => Some(memberLabel(segment, presentation, memberLabels))).toVector
    val empty      = Vector.fill[Option[String]](fields.size)(None)
    val values = coordinate.kind match {
      case CoordinateKind.Leaf => empty.patch(0, pathValues, pathValues.size)
      case CoordinateKind.Subtotal =>
        empty.patch(0, pathValues :+ Some(presentation.subtotalLabel), pathValues.size + 1)
      case CoordinateKind.GrandTotal =>
        if (fields.isEmpty) empty else empty.updated(0, Some(presentation.grandTotalLabel))
    }
    RowHeader(values, coordinate.kind)
  }

  private def columnLabels(
    coordinate: Coordinate,
    presentation: Presentation,
    memberLabels: Map[(WorkUnitPivotTableKey, FieldValue), String]
  ): Vector[String] = {
    val pathLabels = coordinate.path.iterator.map(memberLabel(_, presentation, memberLabels)).toVector
    coordinate.kind match {
      case CoordinateKind.Leaf       => pathLabels
      case CoordinateKind.Subtotal   => pathLabels :+ presentation.subtotalLabel
      case CoordinateKind.GrandTotal => Vector(presentation.grandTotalLabel)
    }
  }

  private def projectValue(cell: Cell, measure: WorkUnitPivotTableValue): Option[Value] =
    measure match {
      case WorkUnitPivotTableValue.DurationSum =>
        cell.values.durationSum.map(duration => Value.DurationMinutes(roundedMinutes(duration)))
      case WorkUnitPivotTableValue.DurationAvg =>
        cell.values.durationAvg.flatten.map(duration => Value.DurationMinutes(roundedMinutes(duration)))
      case WorkUnitPivotTableValue.EntriesCount =>
        cell.values.entriesCount.map(Value.Count)
    }

  private def memberLabel(
    segment: PathSegment,
    presentation: Presentation,
    memberLabels: Map[(WorkUnitPivotTableKey, FieldValue), String]
  ): String =
    memberLabels.getOrElse(segment.key -> segment.value, fallbackLabel(segment.key, segment.value, presentation))

  private def fallbackLabel(
    key: WorkUnitPivotTableKey,
    value: FieldValue,
    presentation: Presentation
  ): String =
    (key, value) match {
      case (WorkUnitPivotTableKey.ClientKey, FieldValue.Null)                => presentation.noClientLabel
      case (WorkUnitPivotTableKey.TaskKey, FieldValue.Null)                  => presentation.noTaskLabel
      case (WorkUnitPivotTableKey.TaskKey, FieldValue.TaskId(value))         => value
      case (WorkUnitPivotTableKey.UserKey, FieldValue.Uuid(uuid))            => uuid.toString
      case (WorkUnitPivotTableKey.ProjectKey, FieldValue.Uuid(uuid))         => uuid.toString
      case (WorkUnitPivotTableKey.ClientKey, FieldValue.Uuid(uuid))          => uuid.toString
      case (WorkUnitPivotTableKey.YearKey, FieldValue.Year(year))            => year.toString
      case (WorkUnitPivotTableKey.MonthYearKey, FieldValue.Month(month))     => month.toString
      case (WorkUnitPivotTableKey.DateKey, FieldValue.Date(date))            => date.toString
      case (WorkUnitPivotTableKey.EntryTypeKey, FieldValue.EntryType(entry)) => entry.entryName
      case _ => throw new IllegalStateException(s"invalid ${key.entryName} member value: $value")
    }
}
