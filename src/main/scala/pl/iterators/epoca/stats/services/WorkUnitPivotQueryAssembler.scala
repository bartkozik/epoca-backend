package pl.iterators.epoca.stats.services

import pl.iterators.epoca.stats.domain.StatsDomain.{WorkUnitPivotTableKey, WorkUnitPivotTableValue}
import pl.iterators.epoca.stats.domain.WorkUnitPivotQuery._
import pl.iterators.epoca.stats.repositories.WorkUnitPivotQueryRepository.AggregateRow
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.WorkUnitEntryType

import java.time.Duration
import java.util.UUID
import scala.util.control.NonFatal

class WorkUnitPivotQueryAssembler {
  import WorkUnitPivotQueryAssembler._

  def assemble(
    request: Request,
    aggregateRows: Seq[AggregateRow],
    availableFilterMembers: Map[WorkUnitPivotTableKey, Seq[FieldValue]],
    displayValueCellCount: Long,
    maxDisplayValueCellCount: Long
  ): Response = {
    val cells = aggregateRows
      .sortWith((left, right) => compareAggregateRows(request, left, right) < 0)
      .map(row =>
        Cell(
          row = coordinate(request.rows, row.rowPath),
          column = coordinate(request.columns, row.columnPath),
          values = metricValues(request.values, row)
        )
      )

    Response(
      cells = cells,
      filterMembers = request.filters.map(filterMembers(_, availableFilterMembers)),
      result = ResultMetadata(
        cellCount = cells.size.toLong,
        displayValueCellCount = displayValueCellCount,
        maxDisplayValueCellCount = maxDisplayValueCellCount
      )
    )
  }
}

private[epoca] object WorkUnitPivotQueryAssembler {
  private val NanosPerSecond = BigInt(1000000000L)

  private[stats] def durationFromNanos(nanos: BigInt): Duration = {
    if (nanos < 0) throw new IllegalStateException(s"duration nanoseconds must be non-negative: $nanos")

    val (seconds, nanoAdjustment) = nanos /% NanosPerSecond
    if (!seconds.isValidLong)
      throw new IllegalStateException(s"duration seconds exceed java.time.Duration range: $seconds")

    try Duration.ofSeconds(seconds.toLong, nanoAdjustment.toLong)
    catch {
      case NonFatal(error) => throw new IllegalStateException(s"invalid duration nanoseconds: $nanos", error)
    }
  }

  private def coordinate(axis: Seq[WorkUnitPivotTableKey], path: Seq[PathSegment]): Coordinate = {
    validatePath(axis, path)
    val kind =
      if (axis.isEmpty || path.size == axis.size) CoordinateKind.Leaf
      else if (path.isEmpty) CoordinateKind.GrandTotal
      else CoordinateKind.Subtotal

    Coordinate(kind, path)
  }

  private def metricValues(requestedValues: Seq[WorkUnitPivotTableValue], row: AggregateRow): MetricValues = {
    val requested = requestedValues.toSet
    MetricValues(
      durationSum = Option.when(requested(WorkUnitPivotTableValue.DurationSum))(durationFromNanos(row.durationSumNanos)),
      durationAvg = Option.when(requested(WorkUnitPivotTableValue.DurationAvg))(row.durationAvgNanos.map(durationFromNanos)),
      entriesCount = Option.when(requested(WorkUnitPivotTableValue.EntriesCount))(row.entriesCount)
    )
  }

  private def filterMembers(filter: Filter, availableFilterMembers: Map[WorkUnitPivotTableKey, Seq[FieldValue]]): FilterMembers = {
    implicit val ordering: Ordering[FieldValue] = fieldValueOrdering(filter.key)
    val available = availableFilterMembers
      .getOrElse(filter.key, Seq.empty)
      .distinct
      .sorted
    val availableSet = available.toSet
    val unavailable = filter.includedValues
      .getOrElse(Seq.empty)
      .distinct
      .filterNot(availableSet)
      .sorted

    FilterMembers(key = filter.key, members = available.map(FilterMember(_, available = true)) ++ unavailable.map(FilterMember(_, available = false)))
  }

  private def compareAggregateRows(
    request: Request,
    left: AggregateRow,
    right: AggregateRow
  ): Int = {
    val rows = comparePaths(request.rows, left.rowPath, right.rowPath)
    if (rows != 0) rows else comparePaths(request.columns, left.columnPath, right.columnPath)
  }

  private[epoca] def comparePaths(
    axis: Seq[WorkUnitPivotTableKey],
    left: Seq[PathSegment],
    right: Seq[PathSegment]
  ): Int = {
    validatePath(axis, left)
    validatePath(axis, right)

    val commonLength = math.min(left.size, right.size)
    var index        = 0
    while (index < commonLength) {
      val compared = fieldValueOrdering(axis(index)).compare(left(index).value, right(index).value)
      if (compared != 0) return compared
      index += 1
    }

    if (left.size == right.size) 0
    else if (left.size > right.size) -1
    else 1
  }

  private def validatePath(axis: Seq[WorkUnitPivotTableKey], path: Seq[PathSegment]): Unit = {
    if (path.size > axis.size)
      throw new IllegalStateException(s"pivot path depth ${path.size} exceeds axis depth ${axis.size}")

    path
      .zip(axis)
      .zipWithIndex
      .collectFirst {
        case ((segment, expected), index) if segment.key != expected => (index, expected, segment.key)
      }
      .foreach { case (index, expected, actual) =>
        throw new IllegalStateException(s"pivot path key at index $index is ${actual.entryName}, expected ${expected.entryName}")
      }
  }

  private def fieldValueOrdering(key: WorkUnitPivotTableKey): Ordering[FieldValue] =
    new Ordering[FieldValue] {
      override def compare(left: FieldValue, right: FieldValue): Int =
        key match {
          case WorkUnitPivotTableKey.UserKey | WorkUnitPivotTableKey.ProjectKey => compareUuidValues(key, left, right)
          case WorkUnitPivotTableKey.ClientKey                                  => compareClientValues(left, right)
          case WorkUnitPivotTableKey.YearKey                                    => compareYears(left, right)
          case WorkUnitPivotTableKey.MonthYearKey                               => compareMonths(left, right)
          case WorkUnitPivotTableKey.DateKey                                    => compareDates(left, right)
          case WorkUnitPivotTableKey.EntryTypeKey                               => compareEntryTypes(left, right)
          case WorkUnitPivotTableKey.TaskKey                                    => compareTaskValues(left, right)
        }
    }

  private def compareUuidValues(
    key: WorkUnitPivotTableKey,
    left: FieldValue,
    right: FieldValue
  ): Int =
    (left, right) match {
      case (FieldValue.Uuid(leftUuid), FieldValue.Uuid(rightUuid)) => compareUuids(leftUuid, rightUuid)
      case _                                                       => invalidValues(key, left, right)
    }

  private def compareClientValues(left: FieldValue, right: FieldValue): Int =
    (left, right) match {
      case (FieldValue.Null, FieldValue.Null)                      => 0
      case (FieldValue.Null, FieldValue.Uuid(_))                   => 1
      case (FieldValue.Uuid(_), FieldValue.Null)                   => -1
      case (FieldValue.Uuid(leftUuid), FieldValue.Uuid(rightUuid)) => compareUuids(leftUuid, rightUuid)
      case _                                                       => invalidValues(WorkUnitPivotTableKey.ClientKey, left, right)
    }

  private def compareTaskValues(left: FieldValue, right: FieldValue): Int =
    (left, right) match {
      case (FieldValue.Null, FieldValue.Null)                              => 0
      case (FieldValue.Null, FieldValue.TaskId(_))                         => 1
      case (FieldValue.TaskId(_), FieldValue.Null)                         => -1
      case (FieldValue.TaskId(leftTaskId), FieldValue.TaskId(rightTaskId)) => leftTaskId.compareTo(rightTaskId)
      case _                                                               => invalidValues(WorkUnitPivotTableKey.TaskKey, left, right)
    }

  private def compareYears(left: FieldValue, right: FieldValue): Int =
    (left, right) match {
      case (FieldValue.Year(leftYear), FieldValue.Year(rightYear)) => Integer.compare(leftYear, rightYear)
      case _                                                       => invalidValues(WorkUnitPivotTableKey.YearKey, left, right)
    }

  private def compareMonths(left: FieldValue, right: FieldValue): Int =
    (left, right) match {
      case (FieldValue.Month(leftMonth), FieldValue.Month(rightMonth)) => leftMonth.compareTo(rightMonth)
      case _                                                           => invalidValues(WorkUnitPivotTableKey.MonthYearKey, left, right)
    }

  private def compareDates(left: FieldValue, right: FieldValue): Int =
    (left, right) match {
      case (FieldValue.Date(leftDate), FieldValue.Date(rightDate)) => leftDate.compareTo(rightDate)
      case _                                                       => invalidValues(WorkUnitPivotTableKey.DateKey, left, right)
    }

  private def compareEntryTypes(left: FieldValue, right: FieldValue): Int =
    (left, right) match {
      case (FieldValue.EntryType(leftType), FieldValue.EntryType(rightType)) =>
        Integer.compare(entryTypeIndex(leftType), entryTypeIndex(rightType))
      case _ => invalidValues(WorkUnitPivotTableKey.EntryTypeKey, left, right)
    }

  private def entryTypeIndex(entryType: WorkUnitEntryType): Int =
    entryType match {
      case WorkUnitEntryType.Worklog => 0
      case WorkUnitEntryType.Note    => 1
    }

  private def compareUuids(left: UUID, right: UUID): Int = {
    val mostSignificant = java.lang.Long.compareUnsigned(left.getMostSignificantBits, right.getMostSignificantBits)
    if (mostSignificant != 0) mostSignificant
    else java.lang.Long.compareUnsigned(left.getLeastSignificantBits, right.getLeastSignificantBits)
  }

  private def invalidValues(
    key: WorkUnitPivotTableKey,
    left: FieldValue,
    right: FieldValue
  ): Nothing =
    throw new IllegalStateException(s"${key.entryName} cannot order $left and $right")
}
