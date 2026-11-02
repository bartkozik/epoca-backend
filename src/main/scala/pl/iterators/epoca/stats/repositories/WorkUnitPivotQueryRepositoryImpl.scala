package pl.iterators.epoca.stats.repositories

import io.circe.parser.parse
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId
import pl.iterators.epoca.stats.domain.StatsDomain.WorkUnitPivotTableKey
import pl.iterators.epoca.stats.domain.WorkUnitPivotQuery.{FieldValue, GlobalFilters, PathSegment, Request}
import pl.iterators.epoca.stats.repositories.WorkUnitPivotQueryRepository.{AggregateRow, CoordinateCounts}
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.WorkUnitEntryType
import slick.dbio.DBIO
import slick.jdbc.GetResult

import java.time.{LocalDate, YearMonth}
import java.util.UUID
import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.util.Try

class WorkUnitPivotQueryRepositoryImpl(implicit ec: ExecutionContext) extends WorkUnitPivotQueryRepository {
  import WorkUnitPivotQueryRepositoryImpl._

  override def coordinateCounts(
    organizationId: OrganizationId,
    request: Request,
    filters: GlobalFilters
  ): DBIO[CoordinateCounts] = {
    implicit val getRawCoordinateCounts: GetResult[RawCoordinateCounts] = GetResult { result =>
      RawCoordinateCounts(
        result.nextLongOption().getOrElse(throw new IllegalStateException("row coordinate count was SQL NULL")),
        result.nextLongOption().getOrElse(throw new IllegalStateException("column coordinate count was SQL NULL"))
      )
    }

    WorkUnitPivotQuerySql
      .coordinateCounts(organizationId, request, filters)
      .as[RawCoordinateCounts]
      .map(decodeCoordinateCounts)
  }

  override def aggregate(
    organizationId: OrganizationId,
    request: Request,
    filters: GlobalFilters
  ): DBIO[Seq[AggregateRow]] = {
    implicit val getRawAggregateRow: GetResult[RawAggregateRow] = GetResult { result =>
      RawAggregateRow(
        rowPathJson = result.nextString(),
        columnPathJson = result.nextString(),
        entriesCount = result.nextLongOption().getOrElse(throw new IllegalStateException("entriesCount was SQL NULL")),
        durationSumNanos = result
          .nextBigDecimalOption()
          .map(_.bigDecimal)
          .getOrElse(throw new IllegalStateException("durationSumNanos was SQL NULL")),
        durationAvgNanos = result.nextBigDecimalOption().map(_.bigDecimal)
      )
    }

    WorkUnitPivotQuerySql
      .aggregate(organizationId, request, filters)
      .as[RawAggregateRow]
      .map(decodeAggregateRows(request, _))
  }

  override def availableFilterMembers(
    organizationId: OrganizationId,
    request: Request,
    filters: GlobalFilters
  ): DBIO[Map[WorkUnitPivotTableKey, Seq[FieldValue]]] =
    WorkUnitPivotQuerySql.availableFilterMembers(organizationId, request, filters) match {
      case None => DBIO.successful(Map.empty)
      case Some(builder) =>
        implicit val getRawFilterMember: GetResult[RawFilterMember] = GetResult { result =>
          RawFilterMember(
            requestIndex = result.nextIntOption().getOrElse(throw new IllegalStateException("facet requestIndex was SQL NULL")),
            rawValue = result.nextStringOption()
          )
        }
        builder.as[RawFilterMember].map(decodeFilterMembers(request, _))
    }
}

private[stats] object WorkUnitPivotQueryRepositoryImpl {
  private[stats] final case class RawCoordinateCounts(row: Long, column: Long)
  private[stats] final case class RawAggregateRow(
    rowPathJson: String,
    columnPathJson: String,
    entriesCount: Long,
    durationSumNanos: java.math.BigDecimal,
    durationAvgNanos: Option[java.math.BigDecimal])
  private[stats] final case class RawFilterMember(requestIndex: Int, rawValue: Option[String])

  private[stats] def decodeCoordinateCounts(rows: Seq[RawCoordinateCounts]): CoordinateCounts =
    rows match {
      case Seq(RawCoordinateCounts(row, column)) if row >= 0L && column >= 0L => CoordinateCounts(row, column)
      case Seq(RawCoordinateCounts(row, column)) =>
        throw new IllegalStateException(s"coordinate counts must be non-negative: row=$row, column=$column")
      case other =>
        throw new IllegalStateException(s"coordinate count query returned ${other.size} rows instead of exactly one")
    }

  private[stats] def decodeAggregateRow(request: Request, raw: RawAggregateRow): AggregateRow = {
    val entriesCount =
      if (raw.entriesCount >= 0L) raw.entriesCount
      else throw new IllegalStateException(s"entriesCount must be non-negative: ${raw.entriesCount}")

    AggregateRow(
      rowPath = decodePath("rowPath", request.rows, raw.rowPathJson),
      columnPath = decodePath("columnPath", request.columns, raw.columnPathJson),
      entriesCount = entriesCount,
      durationSumNanos = decodeNanos("durationSumNanos", raw.durationSumNanos),
      durationAvgNanos = raw.durationAvgNanos.map(decodeNanos("durationAvgNanos", _))
    )
  }

  private[stats] def decodeAggregateRows(request: Request, rows: Seq[RawAggregateRow]): Seq[AggregateRow] = {
    val decoded = rows.map(decodeAggregateRow(request, _))
    decoded.groupBy(row => row.rowPath -> row.columnPath).collectFirst {
      case (coordinate, duplicates) if duplicates.size > 1 => coordinate
    } match {
      case Some((rowPath, columnPath)) =>
        throw new IllegalStateException(s"aggregate query returned duplicate coordinate rowPath=$rowPath, columnPath=$columnPath")
      case None => decoded
    }
  }

  private[stats] def decodeNanos(field: String, value: java.math.BigDecimal): BigInt =
    if (value == null) throw new IllegalStateException(s"$field was SQL NULL")
    else
      try {
        val result = BigInt(value.toBigIntegerExact)
        if (result >= 0) result
        else throw new IllegalStateException(s"$field must be non-negative: $value")
      } catch {
        case error: ArithmeticException =>
          throw new IllegalStateException(s"$field must be an exact integer: $value", error)
      }

  private[stats] def decodeFilterMembers(request: Request, rows: Seq[RawFilterMember]): Map[WorkUnitPivotTableKey, Seq[FieldValue]] = {
    val requestedKeys = request.filters.map(_.key)
    if (requestedKeys.distinct.size != requestedKeys.size)
      throw new IllegalStateException("facet request contains duplicate filter keys")

    val membersByKey = requestedKeys.iterator.map(_ -> Vector.newBuilder[FieldValue]).toMap
    rows.foreach { raw =>
      val key = request.filters
        .lift(raw.requestIndex)
        .map(_.key)
        .getOrElse(
          throw new IllegalStateException(s"facet requestIndex ${raw.requestIndex} is outside request.filters bounds [0, ${request.filters.size})")
        )
      val value = raw.rawValue match {
        case None if key == WorkUnitPivotTableKey.ClientKey || key == WorkUnitPivotTableKey.TaskKey => FieldValue.Null
        case None => throw new IllegalStateException(s"facet ${key.entryName} was SQL NULL")
        case Some(rawJson) =>
          val json =
            parse(rawJson).fold(error => throw new IllegalStateException(s"facet ${key.entryName} is malformed JSON: ${error.message}"), identity)
          decodeFieldValue("facet", key, json)
      }
      membersByKey(key) += value
    }

    requestedKeys.iterator.map(key => key -> distinctPreservingFirst(membersByKey(key).result())).toMap
  }

  private[stats] def distinctPreservingFirst[A](values: IterableOnce[A]): Seq[A] = {
    val seen   = mutable.HashSet.empty[A]
    val result = Vector.newBuilder[A]
    values.iterator.foreach(value => if (seen.add(value)) result += value)
    result.result()
  }

  private[stats] def decodePath(
    field: String,
    keys: Seq[WorkUnitPivotTableKey],
    rawJson: String
  ): Seq[PathSegment] = {
    val jsonObject = Option(rawJson)
      .toRight(s"$field was SQL NULL")
      .flatMap(value => parse(value).left.map(error => s"$field is malformed JSON: ${error.message}"))
      .flatMap(_.asObject.toRight(s"$field must be a JSON object"))
      .fold(message => throw new IllegalStateException(message), identity)
    val requestedNames = keys.map(_.entryName).toSet
    val unknownNames   = jsonObject.keys.toSet -- requestedNames
    if (unknownNames.nonEmpty)
      throw new IllegalStateException(s"$field contains unknown keys: ${unknownNames.toSeq.sorted.mkString(", ")}")

    val (path, _) = keys.foldLeft((Vector.empty[PathSegment], false)) { case ((segments, missingPrefix), key) =>
      jsonObject(key.entryName) match {
        case None => segments -> true
        case Some(_) if missingPrefix =>
          throw new IllegalStateException(s"$field is not a prefix: ${key.entryName} appears after a grouped-out key")
        case Some(value) =>
          (segments :+ PathSegment(key, decodeFieldValue(field, key, value))) -> false
      }
    }
    path
  }

  private[stats] def decodeFieldValue(
    field: String,
    key: WorkUnitPivotTableKey,
    value: io.circe.Json
  ): FieldValue =
    key match {
      case WorkUnitPivotTableKey.ClientKey if value.isNull => FieldValue.Null
      case WorkUnitPivotTableKey.TaskKey if value.isNull   => FieldValue.Null
      case WorkUnitPivotTableKey.UserKey | WorkUnitPivotTableKey.ProjectKey | WorkUnitPivotTableKey.ClientKey =>
        value.asString
          .filter(_.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
          .flatMap(raw => Try(UUID.fromString(raw)).toOption)
          .map(FieldValue.Uuid)
          .getOrElse(throw new IllegalStateException(s"$field.${key.entryName} must be a UUID string"))
      case WorkUnitPivotTableKey.TaskKey =>
        value.asString
          .map(FieldValue.TaskId)
          .getOrElse(throw new IllegalStateException(s"$field.${key.entryName} must be a string"))
      case WorkUnitPivotTableKey.YearKey =>
        value.asNumber
          .flatMap(_.toInt)
          .map(FieldValue.Year)
          .getOrElse(throw new IllegalStateException(s"$field.${key.entryName} must be an integer"))
      case WorkUnitPivotTableKey.MonthYearKey =>
        value.asString
          .filter(_.matches("[0-9]{4}-(0[1-9]|1[0-2])"))
          .flatMap(raw => Try(YearMonth.parse(raw)).toOption)
          .map(FieldValue.Month)
          .getOrElse(throw new IllegalStateException(s"$field.${key.entryName} must use canonical YYYY-MM format"))
      case WorkUnitPivotTableKey.DateKey =>
        value.asString
          .filter(_.matches("[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])"))
          .flatMap(raw => Try(LocalDate.parse(raw)).toOption)
          .map(FieldValue.Date)
          .getOrElse(throw new IllegalStateException(s"$field.${key.entryName} must use canonical YYYY-MM-DD format"))
      case WorkUnitPivotTableKey.EntryTypeKey =>
        value.asString
          .flatMap(WorkUnitEntryType.withNameOption)
          .map(FieldValue.EntryType)
          .getOrElse(
            throw new IllegalStateException(
              s"$field.${key.entryName} must be exactly one of ${WorkUnitEntryType.values.map(_.entryName).mkString(", ")}"
            )
          )
    }
}
