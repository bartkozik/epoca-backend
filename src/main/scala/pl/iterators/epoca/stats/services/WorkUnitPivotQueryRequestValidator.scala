package pl.iterators.epoca.stats.services

import io.circe.Json
import pl.iterators.epoca.stats.domain.StatsDomain.{WorkUnitPivotTableKey, WorkUnitPivotTableValue}
import pl.iterators.epoca.stats.domain.WorkUnitPivotQuery._
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.WorkUnitEntryType

import java.time.{LocalDate, YearMonth}
import java.util.UUID
import scala.collection.mutable.ArrayBuffer
import scala.util.Try

class WorkUnitPivotQueryRequestValidator {
  private val StrictUuid      = """^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$""".r
  private val StrictInt       = """^-?(0|[1-9]\d*)$""".r
  private val StrictYearMonth = """^\d{4}-\d{2}$""".r
  private val StrictDate      = """^\d{4}-\d{2}-\d{2}$""".r

  def validate(json: Json): Either[InvalidConfigurationError, Request] = {
    val violations     = ArrayBuffer.empty[Violation]
    val assignedFields = scala.collection.mutable.Set.empty[WorkUnitPivotTableKey]

    def violation(path: String, code: ViolationCode): Unit = violations += Violation(path, code)

    def rejectUnknownProperties(
      objectValue: io.circe.JsonObject,
      allowed: Set[String],
      prefix: String
    ): Unit =
      objectValue.keys.filterNot(allowed).toSeq.sorted.foreach { name =>
        val path = if (prefix.isEmpty) name else s"$prefix.$name"
        violation(path, ViolationCode.UNSUPPORTED_FIELD)
      }

    def registerField(key: WorkUnitPivotTableKey, path: String): Unit =
      if (assignedFields(key)) violation(path, ViolationCode.FIELD_ASSIGNED_TO_MULTIPLE_AREAS)
      else assignedFields += key

    def requiredArray(objectValue: Option[io.circe.JsonObject], name: String): Option[Vector[Json]] =
      objectValue.flatMap(_.apply(name)) match {
        case None =>
          violation(name, ViolationCode.MISSING_REQUIRED_PROPERTY)
          None
        case Some(value) =>
          value.asArray.map(_.toVector).orElse {
            violation(name, ViolationCode.INVALID_PROPERTY_TYPE)
            None
          }
      }

    def parseFields(values: Option[Vector[Json]], property: String): Option[Seq[(WorkUnitPivotTableKey, String)]] =
      values.map { fields =>
        val seen = scala.collection.mutable.Set.empty[WorkUnitPivotTableKey]
        fields.zipWithIndex.flatMap { case (value, index) =>
          val path = s"$property[$index]"
          value.asString match {
            case None =>
              violation(path, ViolationCode.INVALID_PROPERTY_TYPE)
              None
            case Some(name) =>
              WorkUnitPivotTableKey.withNameOption(name) match {
                case None =>
                  violation(path, ViolationCode.UNSUPPORTED_FIELD)
                  None
                case Some(key) if seen(key) =>
                  violation(path, ViolationCode.DUPLICATE_FIELD)
                  None
                case Some(key) =>
                  seen += key
                  registerField(key, path)
                  Some(key -> path)
              }
          }
        }
      }

    def parseValues(values: Option[Vector[Json]]): Option[Seq[WorkUnitPivotTableValue]] =
      values.map { measures =>
        if (measures.isEmpty) violation("values", ViolationCode.EMPTY_VALUES)

        val seen = scala.collection.mutable.Set.empty[WorkUnitPivotTableValue]
        measures.zipWithIndex.flatMap { case (value, index) =>
          val path = s"values[$index]"
          value.asString match {
            case None =>
              violation(path, ViolationCode.INVALID_PROPERTY_TYPE)
              None
            case Some(name) =>
              WorkUnitPivotTableValue.withNameOption(name) match {
                case None =>
                  violation(path, ViolationCode.UNSUPPORTED_MEASURE)
                  None
                case Some(measure) if seen(measure) =>
                  violation(path, ViolationCode.DUPLICATE_MEASURE)
                  None
                case Some(measure) =>
                  seen += measure
                  Some(measure)
              }
          }
        }
      }

    def parseUuid(value: Json): Option[FieldValue] = value.asString.collect { case uuid @ StrictUuid() =>
      FieldValue.Uuid(UUID.fromString(uuid))
    }

    def parseFilterValue(key: WorkUnitPivotTableKey, value: Json): Option[FieldValue] = key match {
      case WorkUnitPivotTableKey.UserKey | WorkUnitPivotTableKey.ProjectKey => parseUuid(value)
      case WorkUnitPivotTableKey.ClientKey if value.isNull                  => Some(FieldValue.Null)
      case WorkUnitPivotTableKey.ClientKey                                  => parseUuid(value)
      case WorkUnitPivotTableKey.TaskKey if value.isNull                    => Some(FieldValue.Null)
      case WorkUnitPivotTableKey.TaskKey                                    => value.asString.map(FieldValue.TaskId)
      case WorkUnitPivotTableKey.YearKey =>
        value.asNumber
          .filter(_ => StrictInt.pattern.matcher(value.noSpaces).matches())
          .flatMap(_.toInt)
          .map(FieldValue.Year)
      case WorkUnitPivotTableKey.MonthYearKey =>
        value.asString.collect { case month @ StrictYearMonth() => Try(YearMonth.parse(month)).toOption.map(FieldValue.Month) }.flatten
      case WorkUnitPivotTableKey.DateKey =>
        value.asString.collect { case date @ StrictDate() => Try(LocalDate.parse(date)).toOption.map(FieldValue.Date) }.flatten
      case WorkUnitPivotTableKey.EntryTypeKey =>
        value.asString.flatMap(WorkUnitEntryType.withNameOption).map(FieldValue.EntryType)
    }

    def parseFilters(values: Option[Vector[Json]]): Option[Seq[(Filter, String)]] = values.map { filters =>
      val seenKeys = scala.collection.mutable.Set.empty[WorkUnitPivotTableKey]
      filters.zipWithIndex.flatMap { case (value, index) =>
        val path = s"filters[$index]"
        value.asObject match {
          case None =>
            violation(path, ViolationCode.INVALID_PROPERTY_TYPE)
            None
          case Some(objectValue) =>
            val key = objectValue("key") match {
              case None =>
                violation(s"$path.key", ViolationCode.MISSING_REQUIRED_PROPERTY)
                None
              case Some(keyValue) =>
                keyValue.asString match {
                  case None =>
                    violation(s"$path.key", ViolationCode.INVALID_PROPERTY_TYPE)
                    None
                  case Some(name) =>
                    WorkUnitPivotTableKey.withNameOption(name).orElse {
                      violation(s"$path.key", ViolationCode.UNSUPPORTED_FIELD)
                      None
                    }
                }
            }
            val uniqueKey = key.filter { parsedKey =>
              if (seenKeys(parsedKey)) {
                violation(s"$path.key", ViolationCode.DUPLICATE_FIELD)
                false
              } else {
                seenKeys += parsedKey
                registerField(parsedKey, s"$path.key")
                true
              }
            }
            val includedValues = objectValue("includedValues") match {
              case None =>
                violation(s"$path.includedValues", ViolationCode.MISSING_REQUIRED_PROPERTY)
                None
              case Some(included) if included.isNull => Some(None)
              case Some(included) =>
                included.asArray.map(_.toVector) match {
                  case None =>
                    violation(s"$path.includedValues", ViolationCode.INVALID_PROPERTY_TYPE)
                    None
                  case Some(members) if members.isEmpty =>
                    violation(s"$path.includedValues", ViolationCode.EMPTY_SELECTION)
                    Some(Some(Seq.empty))
                  case Some(members) =>
                    val parsed = key.map { field =>
                      val seen = scala.collection.mutable.Set.empty[FieldValue]
                      members.zipWithIndex.flatMap { case (member, memberIndex) =>
                        parseFilterValue(field, member) match {
                          case None =>
                            violation(s"$path.includedValues[$memberIndex]", ViolationCode.INVALID_FILTER_VALUE)
                            None
                          case Some(parsedMember) if seen(parsedMember) =>
                            violation(s"$path.includedValues[$memberIndex]", ViolationCode.DUPLICATE_FILTER_VALUE)
                            None
                          case Some(parsedMember) =>
                            seen += parsedMember
                            Some(parsedMember)
                        }
                      }
                    }
                    Some(parsed.map(Some(_)).getOrElse(None))
                }
            }
            rejectUnknownProperties(objectValue, Set("key", "includedValues"), path)
            for {
              parsedKey    <- uniqueKey
              parsedValues <- includedValues
            } yield Filter(parsedKey, parsedValues) -> s"$path.key"
        }
      }
    }

    def parseOptions(objectValue: Option[io.circe.JsonObject]): Option[Options] =
      objectValue.flatMap(_.apply("options")) match {
        case None =>
          violation("options", ViolationCode.MISSING_REQUIRED_PROPERTY)
          None
        case Some(value) =>
          value.asObject
            .map { options =>
              def boolean(name: String): Option[Boolean] = options(name) match {
                case None =>
                  violation(s"options.$name", ViolationCode.MISSING_REQUIRED_PROPERTY)
                  None
                case Some(option) =>
                  option.asBoolean.orElse {
                    violation(s"options.$name", ViolationCode.INVALID_PROPERTY_TYPE)
                    None
                  }
              }
              val parsed = Options(
                rowSubtotals = boolean("rowSubtotals").getOrElse(false),
                columnSubtotals = boolean("columnSubtotals").getOrElse(false),
                grandTotals = boolean("grandTotals").getOrElse(false)
              )
              rejectUnknownProperties(options, Set("rowSubtotals", "columnSubtotals", "grandTotals"), "options")
              parsed
            }
            .orElse {
              violation("options", ViolationCode.INVALID_PROPERTY_TYPE)
              None
            }
      }

    val objectValue = json.asObject
    val rows        = parseFields(requiredArray(objectValue, "rows"), "rows")
    val columns     = parseFields(requiredArray(objectValue, "columns"), "columns")
    val values      = parseValues(requiredArray(objectValue, "values"))
    val filters     = parseFilters(requiredArray(objectValue, "filters"))
    val options     = parseOptions(objectValue)
    objectValue.foreach(rejectUnknownProperties(_, Set("rows", "columns", "values", "filters", "options"), ""))

    if (violations.nonEmpty) Left(InvalidConfigurationError(violations = violations.toSeq))
    else
      Right(
        Request(
          rows = rows.getOrElse(Seq.empty).map(_._1),
          columns = columns.getOrElse(Seq.empty).map(_._1),
          values = values.getOrElse(Seq.empty),
          filters = filters.getOrElse(Seq.empty).map(_._1),
          options = options.getOrElse(Options(false, false, false))
        )
      )
  }
}
