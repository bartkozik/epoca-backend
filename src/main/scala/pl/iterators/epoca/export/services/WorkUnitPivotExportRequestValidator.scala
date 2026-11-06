package pl.iterators.epoca.`export`.services

import io.circe.{Json, JsonObject, Printer}
import pl.iterators.epoca.export.domain.WorkUnitPivotExport._
import pl.iterators.epoca.stats.domain.StatsDomain.{WorkUnitPivotTableKey, WorkUnitPivotTableValue}
import pl.iterators.epoca.stats.domain.WorkUnitPivotQuery.{FieldValue, GlobalFilters, Request}
import pl.iterators.epoca.stats.services.WorkUnitPivotQueryRequestValidator
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.WorkUnitEntryType

import java.nio.charset.StandardCharsets
import java.time.{LocalDate, YearMonth}
import java.util.UUID
import scala.collection.mutable
import scala.util.Try

class WorkUnitPivotExportRequestValidator(queryValidator: WorkUnitPivotQueryRequestValidator, config: WorkUnitPivotExportRequestValidator.Config) {
  private val StrictUuid              = """^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$""".r
  private val StrictInt               = """^-?(0|[1-9]\d*)$""".r
  private val StrictYearMonth         = """^\d{4}-\d{2}$""".r
  private val StrictDate              = """^\d{4}-\d{2}-\d{2}$""".r
  private val NormalizedWhitespaceRun = "[\r\n\t]+".r
  private val CanonicalPrinter        = Printer.noSpaces.copy(escapeNonAscii = false)

  def validate(json: Json): Either[ValidationError, ValidatedRequest] =
    json.asObject match {
      case None => Left(ValidationError.MalformedEnvelope)
      case Some(envelope) =>
        validateEnvelope(envelope) match {
          case Left(error) => Left(ValidationError.InvalidPresentation(error))
          case Right((rawQuery, rawProjection, rawPresentation)) =>
            queryValidator.validate(rawQuery) match {
              case Left(error) => Left(ValidationError.InvalidQuery(error))
              case Right(query) =>
                for {
                  projection   <- validateProjection(rawProjection).left.map(ValidationError.InvalidPresentation)
                  presentation <- validatePresentation(rawPresentation, usesTaskKey(query)).left.map(ValidationError.InvalidPresentation)
                } yield ValidatedRequest(query, projection, presentation)
            }
        }
    }

  final def validate(json: Json, filters: GlobalFilters): Either[ValidationError, ValidatedRequest] =
    validate(json).flatMap(validateProjectionRelation(_, filters))

  private def validateEnvelope(envelope: JsonObject): Either[InvalidExportRequest, (Json, Option[Json], Json)] = {
    val violations = mutable.ArrayBuffer.empty[ExportViolation]

    def required(name: String): Option[Json] = envelope(name).orElse {
      violations += ExportViolation(name, ExportViolationCode.MISSING_REQUIRED_PROPERTY)
      None
    }

    val query        = required("query")
    val presentation = required("presentation")
    val projection   = envelope("projection")
    rejectUnknownProperties(envelope, Set("query", "projection", "presentation"), "", violations)

    if (violations.nonEmpty) Left(InvalidExportRequest(violations = violations.toSeq))
    else Right((query.get, projection, presentation.get))
  }

  private def validateProjection(json: Option[Json]): Either[InvalidExportRequest, Option[Projection]] =
    json match {
      case None => Right(None)
      case Some(value) =>
        value.asObject match {
          case None =>
            Left(InvalidExportRequest(violations = Seq(ExportViolation("projection", ExportViolationCode.INVALID_PROPERTY_TYPE))))
          case Some(projection) =>
            val violations = mutable.ArrayBuffer.empty[ExportViolation]
            val effectiveDateTo = projection("effectiveDateTo") match {
              case None =>
                violations += ExportViolation("projection.effectiveDateTo", ExportViolationCode.MISSING_REQUIRED_PROPERTY)
                None
              case Some(rawDate) => parseProjectionDate(rawDate, violations)
            }
            rejectUnknownProperties(projection, Set("effectiveDateTo"), "projection", violations)

            if (violations.nonEmpty) Left(InvalidExportRequest(violations = violations.toSeq))
            else Right(effectiveDateTo.map(Projection))
        }
    }

  private def validateProjectionRelation(request: ValidatedRequest, filters: GlobalFilters): Either[ValidationError, ValidatedRequest] = {
    val invalid = request.projection.exists { projection =>
      filters.dateTo match {
        case Some(dateTo) => projection.effectiveDateTo != dateTo
        case None         => projection.effectiveDateTo.isBefore(filters.dateFrom)
      }
    }
    if (invalid)
      Left(
        ValidationError.InvalidPresentation(
          InvalidExportRequest(violations = Seq(ExportViolation("projection.effectiveDateTo", ExportViolationCode.INVALID_PROJECTION_VALUE)))
        )
      )
    else Right(request)
  }

  private def parseProjectionDate(json: Json, violations: mutable.ArrayBuffer[ExportViolation]): Option[LocalDate] =
    json.asString match {
      case None =>
        violations += ExportViolation("projection.effectiveDateTo", ExportViolationCode.INVALID_PROPERTY_TYPE)
        None
      case Some(raw @ StrictDate()) =>
        Try(LocalDate.parse(raw)).toOption.orElse {
          violations += ExportViolation("projection.effectiveDateTo", ExportViolationCode.INVALID_PROJECTION_VALUE)
          None
        }
      case Some(_) =>
        violations += ExportViolation("projection.effectiveDateTo", ExportViolationCode.INVALID_PROJECTION_VALUE)
        None
    }

  // TaskKey labels are required only when grouping by TaskKey, preserving older frontend export requests.
  private def usesTaskKey(query: Request): Boolean =
    query.rows.contains(WorkUnitPivotTableKey.TaskKey) || query.columns.contains(WorkUnitPivotTableKey.TaskKey)

  private def validatePresentation(json: Json, usesTaskKey: Boolean): Either[InvalidExportRequest, Presentation] =
    json.asObject match {
      case None =>
        Left(InvalidExportRequest(violations = Seq(ExportViolation("presentation", ExportViolationCode.INVALID_PROPERTY_TYPE))))
      case Some(presentation) =>
        val violations = mutable.ArrayBuffer.empty[ExportViolation]
        val (requiredFieldLabelKeys, optionalFieldLabelKeys) =
          if (usesTaskKey) (WorkUnitPivotTableKey.values, Seq.empty)
          else (WorkUnitPivotTableKey.values.filterNot(_ == WorkUnitPivotTableKey.TaskKey), Seq(WorkUnitPivotTableKey.TaskKey))
        val fieldLabels     = parseLabelMap(presentation, "fieldLabels", requiredFieldLabelKeys, violations, optionalFieldLabelKeys)
        val measureLabels   = parseLabelMap(presentation, "measureLabels", WorkUnitPivotTableValue.values, violations)
        val subtotalLabel   = parseLabelProperty(presentation, "subtotalLabel", violations)
        val grandTotalLabel = parseLabelProperty(presentation, "grandTotalLabel", violations)
        val noClientLabel   = parseLabelProperty(presentation, "noClientLabel", violations)
        val noTaskLabel =
          if (usesTaskKey) parseLabelProperty(presentation, "noTaskLabel", violations)
          else parseOptionalLabelProperty(presentation, "noTaskLabel", violations)
        val memberLabels = parseMemberLabels(presentation, violations)
        rejectUnknownProperties(
          presentation,
          Set("fieldLabels", "measureLabels", "subtotalLabel", "grandTotalLabel", "noClientLabel", "noTaskLabel", "memberLabels"),
          "presentation",
          violations
        )

        if (violations.nonEmpty) Left(InvalidExportRequest(violations = violations.toSeq))
        else
          Right(
            Presentation(
              fieldLabels = fieldLabels.get,
              measureLabels = measureLabels.get,
              subtotalLabel = subtotalLabel.get,
              grandTotalLabel = grandTotalLabel.get,
              noClientLabel = noClientLabel.get,
              noTaskLabel = noTaskLabel.get,
              memberLabels = memberLabels.get
            )
          )
    }

  private def parseLabelMap[K <: enumeratum.EnumEntry](
    presentation: JsonObject,
    name: String,
    requiredKeys: Seq[K],
    violations: mutable.ArrayBuffer[ExportViolation],
    optionalKeys: Seq[K] = Seq.empty
  ): Option[Map[K, String]] = {
    val path = s"presentation.$name"
    presentation(name) match {
      case None =>
        violations += ExportViolation(path, ExportViolationCode.MISSING_REQUIRED_PROPERTY)
        None
      case Some(value) =>
        value.asObject match {
          case None =>
            violations += ExportViolation(path, ExportViolationCode.INVALID_PROPERTY_TYPE)
            None
          case Some(labels) =>
            val required = requiredKeys.flatMap { key =>
              val keyName = key.entryName
              labels(keyName) match {
                case None =>
                  violations += ExportViolation(s"$path.$keyName", ExportViolationCode.MISSING_REQUIRED_PROPERTY)
                  None
                case Some(label) => parseLabel(label, s"$path.$keyName", violations).map(key -> _)
              }
            }
            val optional = optionalKeys.flatMap { key =>
              labels(key.entryName).flatMap(label => parseLabel(label, s"$path.${key.entryName}", violations).map(key -> _))
            }
            rejectUnknownProperties(labels, (requiredKeys ++ optionalKeys).map(_.entryName).toSet, path, violations)
            Some((required ++ optional).toMap)
        }
    }
  }

  private def parseLabelProperty(
    presentation: JsonObject,
    name: String,
    violations: mutable.ArrayBuffer[ExportViolation]
  ): Option[String] = {
    val path = s"presentation.$name"
    presentation(name) match {
      case None =>
        violations += ExportViolation(path, ExportViolationCode.MISSING_REQUIRED_PROPERTY)
        None
      case Some(value) => parseLabel(value, path, violations)
    }
  }

  private def parseOptionalLabelProperty(
    presentation: JsonObject,
    name: String,
    violations: mutable.ArrayBuffer[ExportViolation]
  ): Option[String] = {
    val path = s"presentation.$name"
    presentation(name) match {
      case None        => Some("")
      case Some(value) => parseLabel(value, path, violations)
    }
  }

  private def parseLabel(
    json: Json,
    path: String,
    violations: mutable.ArrayBuffer[ExportViolation]
  ): Option[String] =
    json.asString match {
      case None =>
        violations += ExportViolation(path, ExportViolationCode.INVALID_PROPERTY_TYPE)
        None
      case Some(raw) =>
        val normalized = normalizeLabel(raw)
        if (!validLabel(normalized)) {
          violations += ExportViolation(path, ExportViolationCode.INVALID_PRESENTATION_VALUE)
          None
        } else Some(normalized)
    }

  private def normalizeLabel(label: String): String = NormalizedWhitespaceRun.replaceAllIn(label, " ")

  private def validLabel(label: String): Boolean = {
    val codePoints = label.codePoints().toArray
    val blank      = codePoints.forall(codePoint => Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint))
    val control    = codePoints.exists(codePoint => codePoint <= 0x1f || (codePoint >= 0x7f && codePoint <= 0x9f))
    val surrogate  = codePoints.exists(codePoint => codePoint >= 0xd800 && codePoint <= 0xdfff)
    !blank && !control && !surrogate && codePoints.length <= Limits.maxLabelCodePoints && utf8Length(label) <= Limits.maxLabelUtf8Bytes
  }

  private def parseMemberLabels(presentation: JsonObject, violations: mutable.ArrayBuffer[ExportViolation]): Option[Seq[MemberLabel]] = {
    val path = "presentation.memberLabels"
    presentation("memberLabels") match {
      case None =>
        violations += ExportViolation(path, ExportViolationCode.MISSING_REQUIRED_PROPERTY)
        None
      case Some(value) =>
        value.asArray match {
          case None =>
            violations += ExportViolation(path, ExportViolationCode.INVALID_PROPERTY_TYPE)
            None
          case Some(members) if members.size.toLong > Limits.memberLabelLimit(config.maxDisplayValueCellCount) =>
            violations += ExportViolation(path, ExportViolationCode.PRESENTATION_LIMIT_EXCEEDED)
            Some(Seq.empty)
          case Some(members) =>
            val seen = mutable.HashSet.empty[(WorkUnitPivotTableKey, FieldValue)]
            val parsed = members.zipWithIndex.flatMap { case (member, index) =>
              parseMember(member, index, seen, violations)
            }
            Some(parsed)
        }
    }
  }

  private def parseMember(
    json: Json,
    index: Int,
    seen: mutable.HashSet[(WorkUnitPivotTableKey, FieldValue)],
    violations: mutable.ArrayBuffer[ExportViolation]
  ): Option[MemberLabel] = {
    val path = s"presentation.memberLabels[$index]"
    json.asObject match {
      case None =>
        violations += ExportViolation(path, ExportViolationCode.INVALID_PROPERTY_TYPE)
        None
      case Some(member) =>
        val key   = parseMemberKey(member, path, violations)
        val value = parseMemberValue(member, path, key, violations)
        (key, value) match {
          case (Some(parsedKey), Some(parsedValue)) if !seen.add(parsedKey -> parsedValue) =>
            violations += ExportViolation(s"$path.value", ExportViolationCode.DUPLICATE_PRESENTATION_MEMBER)
          case _ => ()
        }

        val normalizedLabel = member("label").flatMap(_.asString).map(normalizeLabel)
        val label = member("label") match {
          case None =>
            violations += ExportViolation(s"$path.label", ExportViolationCode.MISSING_REQUIRED_PROPERTY)
            None
          case Some(rawLabel) => parseLabel(rawLabel, s"$path.label", violations)
        }

        (key, value, normalizedLabel) match {
          case (Some(parsedKey), Some(parsedValue), Some(normalized))
              if canonicalMemberEntrySize(parsedKey, parsedValue, normalized) > Limits.maxMemberEntryBytes =>
            violations += ExportViolation(path, ExportViolationCode.INVALID_PRESENTATION_VALUE)
          case _ => ()
        }
        rejectUnknownProperties(member, Set("key", "value", "label"), path, violations)

        for {
          parsedKey   <- key
          parsedValue <- value
          parsedLabel <- label
        } yield MemberLabel(parsedKey, parsedValue, parsedLabel)
    }
  }

  private def parseMemberKey(
    member: JsonObject,
    path: String,
    violations: mutable.ArrayBuffer[ExportViolation]
  ): Option[WorkUnitPivotTableKey] =
    member("key") match {
      case None =>
        violations += ExportViolation(s"$path.key", ExportViolationCode.MISSING_REQUIRED_PROPERTY)
        None
      case Some(value) =>
        value.asString match {
          case None =>
            violations += ExportViolation(s"$path.key", ExportViolationCode.INVALID_PROPERTY_TYPE)
            None
          case Some(name) =>
            WorkUnitPivotTableKey.withNameOption(name).orElse {
              violations += ExportViolation(s"$path.key", ExportViolationCode.UNSUPPORTED_FIELD)
              None
            }
        }
    }

  private def parseMemberValue(
    member: JsonObject,
    path: String,
    key: Option[WorkUnitPivotTableKey],
    violations: mutable.ArrayBuffer[ExportViolation]
  ): Option[FieldValue] =
    member("value") match {
      case None =>
        violations += ExportViolation(s"$path.value", ExportViolationCode.MISSING_REQUIRED_PROPERTY)
        None
      case Some(value) =>
        key.flatMap(parseFieldValue(_, value)) match {
          case parsed @ Some(_) => parsed
          case None if key.nonEmpty =>
            violations += ExportViolation(s"$path.value", ExportViolationCode.INVALID_PRESENTATION_VALUE)
            None
          case None => None
        }
    }

  private def parseFieldValue(key: WorkUnitPivotTableKey, value: Json): Option[FieldValue] = {
    def uuid: Option[FieldValue] = value.asString.collect { case raw @ StrictUuid() => FieldValue.Uuid(UUID.fromString(raw)) }

    key match {
      case WorkUnitPivotTableKey.UserKey | WorkUnitPivotTableKey.ProjectKey => uuid
      case WorkUnitPivotTableKey.ClientKey if value.isNull                  => Some(FieldValue.Null)
      case WorkUnitPivotTableKey.ClientKey                                  => uuid
      case WorkUnitPivotTableKey.TaskKey if value.isNull                    => Some(FieldValue.Null)
      case WorkUnitPivotTableKey.TaskKey                                    => value.asString.map(FieldValue.TaskId)
      case WorkUnitPivotTableKey.YearKey =>
        value.asNumber.filter(_ => StrictInt.pattern.matcher(value.noSpaces).matches()).flatMap(_.toInt).map(FieldValue.Year)
      case WorkUnitPivotTableKey.MonthYearKey =>
        value.asString.collect { case raw @ StrictYearMonth() => Try(YearMonth.parse(raw)).toOption.map(FieldValue.Month) }.flatten
      case WorkUnitPivotTableKey.DateKey =>
        value.asString.collect { case raw @ StrictDate() => Try(LocalDate.parse(raw)).toOption.map(FieldValue.Date) }.flatten
      case WorkUnitPivotTableKey.EntryTypeKey =>
        value.asString.flatMap(WorkUnitEntryType.withNameOption).map(FieldValue.EntryType)
    }
  }

  private def canonicalMemberEntrySize(
    key: WorkUnitPivotTableKey,
    value: FieldValue,
    label: String
  ): Long = {
    val json = Json.obj("key" -> Json.fromString(key.entryName), "value" -> fieldValueJson(value), "label" -> Json.fromString(label))
    utf8Length(CanonicalPrinter.print(json)).toLong
  }

  private def fieldValueJson(value: FieldValue): Json = value match {
    case FieldValue.Uuid(uuid)       => Json.fromString(uuid.toString)
    case FieldValue.Year(year)       => Json.fromInt(year)
    case FieldValue.Month(month)     => Json.fromString(month.toString)
    case FieldValue.Date(date)       => Json.fromString(date.toString)
    case FieldValue.EntryType(entry) => Json.fromString(entry.entryName)
    case FieldValue.TaskId(value)    => Json.fromString(value)
    case FieldValue.Null             => Json.Null
  }

  private def rejectUnknownProperties(
    objectValue: JsonObject,
    allowed: Set[String],
    prefix: String,
    violations: mutable.ArrayBuffer[ExportViolation]
  ): Unit =
    objectValue.keys.filterNot(allowed).toSeq.sorted.foreach { name =>
      val path = if (prefix.isEmpty) name else s"$prefix.$name"
      violations += ExportViolation(path, ExportViolationCode.UNSUPPORTED_FIELD)
    }

  private def utf8Length(value: String): Int = value.getBytes(StandardCharsets.UTF_8).length
}

object WorkUnitPivotExportRequestValidator {
  final case class Config(maxDisplayValueCellCount: Long) {
    require(maxDisplayValueCellCount > 0L, "stats.pivot.maxDisplayValueCellCount must be positive")
  }
}
