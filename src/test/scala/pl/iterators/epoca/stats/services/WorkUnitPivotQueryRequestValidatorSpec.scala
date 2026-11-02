package pl.iterators.epoca.stats.services

import io.circe.Json
import org.specs2.mutable.SpecificationLike
import pl.iterators.epoca.stats.domain.StatsDomain.{WorkUnitPivotTableKey, WorkUnitPivotTableValue}
import pl.iterators.epoca.stats.domain.WorkUnitPivotQuery._
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.WorkUnitEntryType

import java.time.LocalDate
import java.util.UUID

class WorkUnitPivotQueryRequestValidatorSpec extends SpecificationLike {
  private val uuid = "11111111-1111-4111-8111-111111111111"

  private def requestJson(
    rows: Json = Json.arr(),
    columns: Json = Json.arr(),
    values: Json = Json.arr(Json.fromString("EntriesCount")),
    filters: Json = Json.arr(),
    options: Json =
      Json.obj("rowSubtotals" -> Json.fromBoolean(false), "columnSubtotals" -> Json.fromBoolean(false), "grandTotals" -> Json.fromBoolean(true))
  ): Json = Json.obj("rows" -> rows, "columns" -> columns, "values" -> values, "filters" -> filters, "options" -> options)

  private def violations(json: Json): Seq[Violation] =
    new WorkUnitPivotQueryRequestValidator().validate(json) match {
      case Left(error) => error.violations
      case Right(_)    => Seq.empty
    }

  private def filter(key: Json, includedValues: Json): Json =
    Json.obj("key" -> key, "includedValues" -> includedValues)

  "WorkUnitPivotQueryRequestValidator" should {
    "report every missing required top-level property in traversal order" in {
      new WorkUnitPivotQueryRequestValidator().validate(Json.obj()) shouldEqual Left(
        pl.iterators.epoca.stats.domain.WorkUnitPivotQuery.InvalidConfigurationError(violations =
          Seq(
            Violation("rows", ViolationCode.MISSING_REQUIRED_PROPERTY),
            Violation("columns", ViolationCode.MISSING_REQUIRED_PROPERTY),
            Violation("values", ViolationCode.MISSING_REQUIRED_PROPERTY),
            Violation("filters", ViolationCode.MISSING_REQUIRED_PROPERTY),
            Violation("options", ViolationCode.MISSING_REQUIRED_PROPERTY)
          )
        )
      )
    }

    "reject a non-array rows property" in {
      violations(requestJson(rows = Json.obj())) shouldEqual Seq(Violation("rows", ViolationCode.INVALID_PROPERTY_TYPE))
    }

    "report unsupported fields and measures in top-level traversal order" in {
      violations(requestJson(rows = Json.arr(Json.fromString("UnknownKey")), values = Json.arr(Json.fromString("UnknownMeasure")))) shouldEqual Seq(
        Violation("rows[0]", ViolationCode.UNSUPPORTED_FIELD),
        Violation("values[0]", ViolationCode.UNSUPPORTED_MEASURE)
      )
    }

    "report cross-area field conflicts at the later field position" in {
      violations(
        requestJson(
          rows = Json.arr(Json.fromString("UserKey"), Json.fromString("UserKey")),
          columns = Json.arr(Json.fromString("UserKey")),
          values = Json.arr()
        )
      ) shouldEqual Seq(
        Violation("rows[1]", ViolationCode.DUPLICATE_FIELD),
        Violation("columns[0]", ViolationCode.FIELD_ASSIGNED_TO_MULTIPLE_AREAS),
        Violation("values", ViolationCode.EMPTY_VALUES)
      )
    }

    "reject duplicate measures at later occurrences" in {
      violations(requestJson(values = Json.arr(Json.fromString("DurationSum"), Json.fromString("DurationSum")))) shouldEqual Seq(
        Violation("values[1]", ViolationCode.DUPLICATE_MEASURE)
      )
    }

    "reject an explicitly empty filter selection" in {
      violations(requestJson(filters = Json.arr(filter(Json.fromString("UserKey"), Json.arr())))) shouldEqual Seq(
        Violation("filters[0].includedValues", ViolationCode.EMPTY_SELECTION)
      )
    }

    "detect duplicate typed client filter members including null" in {
      violations(
        requestJson(filters = Json.arr(filter(Json.fromString("ClientKey"), Json.arr(Json.Null, Json.fromString(uuid), Json.Null))))
      ) shouldEqual Seq(Violation("filters[0].includedValues[2]", ViolationCode.DUPLICATE_FILTER_VALUE))
    }

    "construct the exact typed request for a complete valid payload" in {
      val clientId = UUID.fromString("22222222-2222-4222-8222-222222222222")
      val json = requestJson(
        rows = Json.arr(Json.fromString("UserKey"), Json.fromString("YearKey")),
        columns = Json.arr(Json.fromString("MonthYearKey")),
        values = Json.arr(Json.fromString("DurationSum"), Json.fromString("EntriesCount")),
        filters = Json.arr(
          filter(Json.fromString("ProjectKey"), Json.arr(Json.fromString(uuid))),
          filter(Json.fromString("ClientKey"), Json.arr(Json.Null, Json.fromString(clientId.toString))),
          filter(Json.fromString("DateKey"), Json.arr(Json.fromString("2026-08-07"))),
          filter(Json.fromString("EntryTypeKey"), Json.arr(Json.fromString("Worklog")))
        ),
        options =
          Json.obj("rowSubtotals" -> Json.fromBoolean(true), "columnSubtotals" -> Json.fromBoolean(false), "grandTotals" -> Json.fromBoolean(true))
      )

      new WorkUnitPivotQueryRequestValidator().validate(json) shouldEqual Right(
        Request(
          rows = Seq(WorkUnitPivotTableKey.UserKey, WorkUnitPivotTableKey.YearKey),
          columns = Seq(WorkUnitPivotTableKey.MonthYearKey),
          values = Seq(WorkUnitPivotTableValue.DurationSum, WorkUnitPivotTableValue.EntriesCount),
          filters = Seq(
            Filter(WorkUnitPivotTableKey.ProjectKey, Some(Seq(FieldValue.Uuid(UUID.fromString(uuid))))),
            Filter(WorkUnitPivotTableKey.ClientKey, Some(Seq(FieldValue.Null, FieldValue.Uuid(clientId)))),
            Filter(WorkUnitPivotTableKey.DateKey, Some(Seq(FieldValue.Date(LocalDate.of(2026, 8, 7))))),
            Filter(WorkUnitPivotTableKey.EntryTypeKey, Some(Seq(FieldValue.EntryType(WorkUnitEntryType.Worklog))))
          ),
          options = Options(rowSubtotals = true, columnSubtotals = false, grandTotals = true)
        )
      )
    }

    "accept a nullable TaskKey filter with a free-text string value" in {
      val json = requestJson(filters = Json.arr(filter(Json.fromString("TaskKey"), Json.arr(Json.Null, Json.fromString("PROJ-123")))))

      new WorkUnitPivotQueryRequestValidator().validate(json) shouldEqual Right(
        Request(
          rows = Seq.empty,
          columns = Seq.empty,
          values = Seq(WorkUnitPivotTableValue.EntriesCount),
          filters = Seq(Filter(WorkUnitPivotTableKey.TaskKey, Some(Seq(FieldValue.Null, FieldValue.TaskId("PROJ-123"))))),
          options = Options(rowSubtotals = false, columnSubtotals = false, grandTotals = true)
        )
      )
    }

    "reject a non-string, non-null TaskKey filter value" in {
      val json = requestJson(filters = Json.arr(filter(Json.fromString("TaskKey"), Json.arr(Json.fromInt(1)))))

      violations(json) shouldEqual Seq(Violation("filters[0].includedValues[0]", ViolationCode.INVALID_FILTER_VALUE))
    }

    "accept every typed filter type with empty axes and boolean options" in {
      val json = requestJson(filters =
        Json.arr(
          filter(Json.fromString("UserKey"), Json.arr(Json.fromString(uuid))),
          filter(Json.fromString("ProjectKey"), Json.arr(Json.fromString(uuid))),
          filter(Json.fromString("ClientKey"), Json.arr(Json.Null)),
          filter(Json.fromString("YearKey"), Json.arr(Json.fromInt(2026))),
          filter(Json.fromString("MonthYearKey"), Json.arr(Json.fromString("2026-08"))),
          filter(Json.fromString("DateKey"), Json.arr(Json.fromString("2026-08-07"))),
          filter(Json.fromString("EntryTypeKey"), Json.arr(Json.fromString("Note")))
        )
      )

      new WorkUnitPivotQueryRequestValidator().validate(json) must beRight
    }

    "accept a null includedValues selection with empty axes and options" in {
      new WorkUnitPivotQueryRequestValidator().validate(requestJson(filters = Json.arr(filter(Json.fromString("ClientKey"), Json.Null)))) must beRight
    }

    "reject invalid typed filter values" in {
      val cases = Seq(
        "non-client null"        -> filter(Json.fromString("UserKey"), Json.arr(Json.Null)),
        "malformed UUID"         -> filter(Json.fromString("ProjectKey"), Json.arr(Json.fromString("not-a-uuid"))),
        "decimal year"           -> filter(Json.fromString("YearKey"), Json.arr(Json.fromDoubleOrNull(2026.5))),
        "decimal-looking year"   -> filter(Json.fromString("YearKey"), Json.arr(Json.fromDoubleOrNull(2026.0))),
        "overflow year"          -> filter(Json.fromString("YearKey"), Json.arr(Json.fromLong(Int.MaxValue.toLong + 1L))),
        "malformed month"        -> filter(Json.fromString("MonthYearKey"), Json.arr(Json.fromString("2026-13"))),
        "noncanonical month"     -> filter(Json.fromString("MonthYearKey"), Json.arr(Json.fromString("2026-8"))),
        "malformed date"         -> filter(Json.fromString("DateKey"), Json.arr(Json.fromString("2026-02-30"))),
        "noncanonical date"      -> filter(Json.fromString("DateKey"), Json.arr(Json.fromString("2026-8-07"))),
        "unsupported entry type" -> filter(Json.fromString("EntryTypeKey"), Json.arr(Json.fromString("worklog")))
      )

      cases.foreach { case (_, invalidFilter) =>
        violations(requestJson(filters = Json.arr(invalidFilter))) shouldEqual Seq(
          Violation("filters[0].includedValues[0]", ViolationCode.INVALID_FILTER_VALUE)
        )
      }
      ok
    }

    "validate filter shape without descending into a non-object filter" in {
      violations(requestJson(filters = Json.arr(Json.fromString("not-a-filter")))) shouldEqual Seq(
        Violation("filters[0]", ViolationCode.INVALID_PROPERTY_TYPE)
      )
    }

    "report missing and incorrectly typed filter properties at their property paths" in {
      violations(requestJson(filters = Json.arr(Json.obj(), filter(Json.fromInt(1), Json.fromString("not-an-array"))))) shouldEqual Seq(
        Violation("filters[0].key", ViolationCode.MISSING_REQUIRED_PROPERTY),
        Violation("filters[0].includedValues", ViolationCode.MISSING_REQUIRED_PROPERTY),
        Violation("filters[1].key", ViolationCode.INVALID_PROPERTY_TYPE),
        Violation("filters[1].includedValues", ViolationCode.INVALID_PROPERTY_TYPE)
      )
    }

    "validate options and their boolean properties" in {
      violations(
        requestJson(options = Json.obj("rowSubtotals" -> Json.fromString("true"), "columnSubtotals" -> Json.fromBoolean(false)))
      ) shouldEqual Seq(
        Violation("options.rowSubtotals", ViolationCode.INVALID_PROPERTY_TYPE),
        Violation("options.grandTotals", ViolationCode.MISSING_REQUIRED_PROPERTY)
      )
    }

    "reject a non-object options property" in {
      violations(requestJson(options = Json.arr())) shouldEqual Seq(Violation("options", ViolationCode.INVALID_PROPERTY_TYPE))
    }

    "reject non-string axis and measure members" in {
      violations(requestJson(rows = Json.arr(Json.fromInt(1)), values = Json.arr(Json.fromBoolean(true)))) shouldEqual Seq(
        Violation("rows[0]", ViolationCode.INVALID_PROPERTY_TYPE),
        Violation("values[0]", ViolationCode.INVALID_PROPERTY_TYPE)
      )
    }

    "reject duplicate filter fields and later cross-area filter assignments" in {
      violations(
        requestJson(
          rows = Json.arr(Json.fromString("YearKey")),
          filters = Json.arr(
            filter(Json.fromString("UserKey"), Json.Null),
            filter(Json.fromString("UserKey"), Json.Null),
            filter(Json.fromString("YearKey"), Json.Null)
          )
        )
      ) shouldEqual Seq(
        Violation("filters[1].key", ViolationCode.DUPLICATE_FIELD),
        Violation("filters[2].key", ViolationCode.FIELD_ASSIGNED_TO_MULTIPLE_AREAS)
      )
    }

    "report duplicate filter keys even when a later selection is structurally invalid" in {
      violations(
        requestJson(filters =
          Json.arr(filter(Json.fromString("UserKey"), Json.Null), filter(Json.fromString("UserKey"), Json.fromString("not-an-array")))
        )
      ) shouldEqual Seq(
        Violation("filters[1].key", ViolationCode.DUPLICATE_FIELD),
        Violation("filters[1].includedValues", ViolationCode.INVALID_PROPERTY_TYPE)
      )
    }

    "report cross-area filter assignments despite an invalid selection" in {
      violations(
        requestJson(
          rows = Json.arr(Json.fromString("UserKey")),
          filters = Json.arr(filter(Json.fromString("UserKey"), Json.fromString("not-an-array")))
        )
      ) shouldEqual Seq(
        Violation("filters[0].key", ViolationCode.FIELD_ASSIGNED_TO_MULTIPLE_AREAS),
        Violation("filters[0].includedValues", ViolationCode.INVALID_PROPERTY_TYPE)
      )
    }

    "reject unknown request, filter, and options properties deterministically after their known properties" in {
      val optionsWithExtras = requestJson().hcursor.downField("options").focus.get.mapObject(_.add("zOption", Json.True).add("aOption", Json.True))
      val filterWithExtras  = filter(Json.fromString("ClientKey"), Json.Null).mapObject(_.add("zFilter", Json.True).add("aFilter", Json.True))
      val requestWithExtras =
        requestJson(filters = Json.arr(filterWithExtras), options = optionsWithExtras).mapObject(_.add("zArea", Json.arr()).add("aArea", Json.arr()))

      violations(requestWithExtras) shouldEqual Seq(
        Violation("filters[0].aFilter", ViolationCode.UNSUPPORTED_FIELD),
        Violation("filters[0].zFilter", ViolationCode.UNSUPPORTED_FIELD),
        Violation("options.aOption", ViolationCode.UNSUPPORTED_FIELD),
        Violation("options.zOption", ViolationCode.UNSUPPORTED_FIELD),
        Violation("aArea", ViolationCode.UNSUPPORTED_FIELD),
        Violation("zArea", ViolationCode.UNSUPPORTED_FIELD)
      )
    }
  }
}
