package pl.iterators.epoca.stats.domain

import enumeratum.{Enum, EnumEntry}
import io.circe.{Encoder, Json, JsonObject}
import pl.iterators.epoca.client.domain.ClientDomain.ClientId
import pl.iterators.epoca.project.domain.ProjectDomain.ProjectId
import pl.iterators.epoca.stats.domain.StatsDomain.{WorkUnitPivotTableKey, WorkUnitPivotTableValue}
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.utils.json.JsonProtocol._
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.{WorkUnitDate, WorkUnitEntryType}

import java.time.{Duration, LocalDate, YearMonth}
import java.util.UUID
import scala.collection.immutable

object WorkUnitPivotQuery {
  sealed trait FieldValue
  object FieldValue {
    final case class Uuid(value: UUID)                   extends FieldValue
    final case class Year(value: Int)                    extends FieldValue
    final case class Month(value: YearMonth)             extends FieldValue
    final case class Date(value: LocalDate)              extends FieldValue
    final case class EntryType(value: WorkUnitEntryType) extends FieldValue
    final case class TaskId(value: String)               extends FieldValue
    case object Null                                     extends FieldValue

    implicit val encoder: Encoder[FieldValue] = Encoder.instance {
      case Uuid(value)      => Json.fromString(value.toString)
      case Year(value)      => Json.fromInt(value)
      case Month(value)     => Json.fromString(value.toString)
      case Date(value)      => Json.fromString(value.toString)
      case EntryType(value) => Json.fromString(value.entryName)
      case TaskId(value)    => Json.fromString(value)
      case Null             => Json.Null
    }
  }

  sealed trait CoordinateKind extends EnumEntry
  object CoordinateKind extends Enum[CoordinateKind] {
    case object Leaf       extends CoordinateKind
    case object Subtotal   extends CoordinateKind
    case object GrandTotal extends CoordinateKind

    override val values: immutable.IndexedSeq[CoordinateKind] = findValues
  }

  final case class Filter(key: WorkUnitPivotTableKey, includedValues: Option[Seq[FieldValue]])
  final case class Options(
    rowSubtotals: Boolean,
    columnSubtotals: Boolean,
    grandTotals: Boolean)
  final case class Request(
    rows: Seq[WorkUnitPivotTableKey],
    columns: Seq[WorkUnitPivotTableKey],
    values: Seq[WorkUnitPivotTableValue],
    filters: Seq[Filter],
    options: Options)
  final case class GlobalFilters(
    dateFrom: WorkUnitDate,
    dateTo: Option[WorkUnitDate],
    projectIds: Seq[ProjectId],
    userIds: Seq[UserId],
    clientIds: Seq[ClientId],
    reportAuthorizedProjectIds: Option[Seq[ProjectId]] = None)
  final case class PathSegment(key: WorkUnitPivotTableKey, value: FieldValue)
  final case class Coordinate(kind: CoordinateKind, path: Seq[PathSegment])
  final case class MetricValues(
    durationSum: Option[Duration],
    durationAvg: Option[Option[Duration]],
    entriesCount: Option[Long]) {
    require(entriesCount.forall(_ >= 0L), "entriesCount must be non-negative")
  }
  final case class Cell(
    row: Coordinate,
    column: Coordinate,
    values: MetricValues)
  final case class FilterMember(value: FieldValue, available: Boolean)
  final case class FilterMembers(key: WorkUnitPivotTableKey, members: Seq[FilterMember])
  final case class ResultMetadata(
    cellCount: Long,
    displayValueCellCount: Long,
    maxDisplayValueCellCount: Long)
  final case class Response(
    cells: Seq[Cell],
    filterMembers: Seq[FilterMembers],
    result: ResultMetadata)

  sealed trait ViolationCode extends EnumEntry
  object ViolationCode extends Enum[ViolationCode] {
    case object MISSING_REQUIRED_PROPERTY        extends ViolationCode
    case object INVALID_PROPERTY_TYPE            extends ViolationCode
    case object UNSUPPORTED_FIELD                extends ViolationCode
    case object UNSUPPORTED_MEASURE              extends ViolationCode
    case object DUPLICATE_FIELD                  extends ViolationCode
    case object FIELD_ASSIGNED_TO_MULTIPLE_AREAS extends ViolationCode
    case object EMPTY_VALUES                     extends ViolationCode
    case object DUPLICATE_MEASURE                extends ViolationCode
    case object INVALID_FILTER_VALUE             extends ViolationCode
    case object EMPTY_SELECTION                  extends ViolationCode
    case object DUPLICATE_FILTER_VALUE           extends ViolationCode

    override val values: immutable.IndexedSeq[ViolationCode] = findValues
  }

  final case class Violation(path: String, code: ViolationCode)
  final case class InvalidConfigurationError(
    code: String = "PIVOT_INVALID_CONFIGURATION",
    message: String = "Invalid pivot configuration",
    violations: Seq[Violation])
  final case class ResultTooLargeError(
    code: String = "PIVOT_RESULT_TOO_LARGE",
    message: String = "Pivot result exceeds the configured limit",
    estimatedDisplayValueCellCount: Long,
    maxDisplayValueCellCount: Long)

  implicit lazy val filterEncoder: Encoder[Filter] =
    Encoder.forProduct2("key", "includedValues")(filter => (filter.key, filter.includedValues))
  implicit lazy val optionsEncoder: Encoder[Options] =
    Encoder.forProduct3("rowSubtotals", "columnSubtotals", "grandTotals")(options =>
      (options.rowSubtotals, options.columnSubtotals, options.grandTotals)
    )
  implicit lazy val requestEncoder: Encoder[Request] =
    Encoder.forProduct5("rows", "columns", "values", "filters", "options")(request =>
      (request.rows, request.columns, request.values, request.filters, request.options)
    )
  implicit lazy val globalFiltersEncoder: Encoder[GlobalFilters] =
    Encoder.forProduct5("dateFrom", "dateTo", "projectIds", "userIds", "clientIds")(filters =>
      (filters.dateFrom, filters.dateTo, filters.projectIds, filters.userIds, filters.clientIds)
    )
  implicit lazy val pathSegmentEncoder: Encoder[PathSegment] =
    Encoder.forProduct2("key", "value")(segment => (segment.key, segment.value))
  implicit lazy val coordinateEncoder: Encoder[Coordinate] =
    Encoder.forProduct2("kind", "path")(coordinate => (coordinate.kind, coordinate.path))
  implicit lazy val metricValuesEncoder: Encoder[MetricValues] = Encoder.instance { values =>
    val durationEncoder = implicitly[Encoder[Duration]]
    val jsonObject = JsonObject.fromIterable(
      Seq(
        values.durationSum.map(duration => "durationSum" -> durationEncoder(duration)),
        values.durationAvg.map(duration => "durationAvg" -> duration.fold[Json](Json.Null)(durationEncoder.apply)),
        values.entriesCount.map(entriesCount => "entriesCount" -> Json.fromLong(entriesCount))
      ).flatten
    )

    Json.fromJsonObject(jsonObject)
  }
  implicit lazy val cellEncoder: Encoder[Cell] =
    Encoder.forProduct3("row", "column", "values")(cell => (cell.row, cell.column, cell.values))
  implicit lazy val filterMemberEncoder: Encoder[FilterMember] =
    Encoder.forProduct2("value", "available")(member => (member.value, member.available))
  implicit lazy val filterMembersEncoder: Encoder[FilterMembers] =
    Encoder.forProduct2("key", "members")(members => (members.key, members.members))
  implicit lazy val resultMetadataEncoder: Encoder[ResultMetadata] =
    Encoder.forProduct3("cellCount", "displayValueCellCount", "maxDisplayValueCellCount")(result =>
      (result.cellCount, result.displayValueCellCount, result.maxDisplayValueCellCount)
    )
  implicit lazy val responseEncoder: Encoder[Response] =
    Encoder.forProduct3("cells", "filterMembers", "result")(response => (response.cells, response.filterMembers, response.result))
  implicit lazy val violationEncoder: Encoder[Violation] =
    Encoder.forProduct2("path", "code")(violation => (violation.path, violation.code))
  implicit lazy val invalidConfigurationErrorEncoder: Encoder[InvalidConfigurationError] =
    Encoder.forProduct3("code", "message", "violations")(error => (error.code, error.message, error.violations))
  implicit lazy val resultTooLargeErrorEncoder: Encoder[ResultTooLargeError] =
    Encoder.forProduct4("code", "message", "estimatedDisplayValueCellCount", "maxDisplayValueCellCount")(error =>
      (error.code, error.message, error.estimatedDisplayValueCellCount, error.maxDisplayValueCellCount)
    )
}
