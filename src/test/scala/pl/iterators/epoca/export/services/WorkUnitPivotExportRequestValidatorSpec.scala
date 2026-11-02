package pl.iterators.epoca.`export`.services

import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax._
import org.specs2.mutable.SpecificationLike
import pl.iterators.epoca.export.domain.WorkUnitPivotExport
import pl.iterators.epoca.export.domain.WorkUnitPivotExport._
import pl.iterators.epoca.stats.domain.StatsDomain.{WorkUnitPivotTableKey, WorkUnitPivotTableValue}
import pl.iterators.epoca.stats.domain.WorkUnitPivotQuery
import pl.iterators.epoca.stats.domain.WorkUnitPivotQuery.{FieldValue, GlobalFilters, InvalidConfigurationError, Violation, ViolationCode}
import pl.iterators.epoca.stats.services.WorkUnitPivotQueryRequestValidator
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.{WorkUnitDate, WorkUnitEntryType}

import java.time.{LocalDate, YearMonth}
import java.util.UUID

class WorkUnitPivotExportRequestValidatorSpec extends SpecificationLike {
  private val userId    = UUID.fromString("11111111-1111-4111-8111-111111111111")
  private val projectId = UUID.fromString("22222222-2222-4222-8222-222222222222")
  private val clientId  = UUID.fromString("33333333-3333-4333-8333-333333333333")

  private val validQuery: Json = Json.obj(
    "rows"    -> Json.arr(Json.fromString("UserKey")),
    "columns" -> Json.arr(Json.fromString("MonthYearKey")),
    "values"  -> Json.arr(Json.fromString("DurationSum"), Json.fromString("EntriesCount")),
    "filters" -> Json.arr(),
    "options" -> Json.obj("rowSubtotals" -> Json.True, "columnSubtotals" -> Json.False, "grandTotals" -> Json.True)
  )

  private val taskGroupedQuery: Json = validQuery.mapObject(_.add("rows", Json.arr(Json.fromString("TaskKey"))))

  private val validFieldLabels: Json = Json.obj(
    "UserKey"      -> Json.fromString("User"),
    "ProjectKey"   -> Json.fromString("Project"),
    "ClientKey"    -> Json.fromString("Client"),
    "TaskKey"      -> Json.fromString("Task"),
    "YearKey"      -> Json.fromString("Year"),
    "MonthYearKey" -> Json.fromString("Month"),
    "DateKey"      -> Json.fromString("Date"),
    "EntryTypeKey" -> Json.fromString("Entry type")
  )

  private val validMeasureLabels: Json = Json.obj(
    "EntriesCount" -> Json.fromString("Entries"),
    "DurationSum"  -> Json.fromString("Total duration"),
    "DurationAvg"  -> Json.fromString("Average duration")
  )

  private def member(
    key: String,
    value: Json,
    label: String
  ): Json =
    Json.obj("key" -> Json.fromString(key), "value" -> value, "label" -> Json.fromString(label))

  private val validMembers: Vector[Json] = Vector(
    member("UserKey", Json.fromString(userId.toString), "Alice"),
    member("ProjectKey", Json.fromString(projectId.toString), "Epoca"),
    member("ClientKey", Json.fromString(clientId.toString), "ACME"),
    member("ClientKey", Json.Null, "No client"),
    member("YearKey", Json.fromInt(2026), "2026"),
    member("MonthYearKey", Json.fromString("2026-08"), "August 2026"),
    member("DateKey", Json.fromString("2026-08-13"), "13 August 2026"),
    member("EntryTypeKey", Json.fromString("Worklog"), "Worklog")
  )

  private val validPresentation: Json = Json.obj(
    "fieldLabels"     -> validFieldLabels,
    "measureLabels"   -> validMeasureLabels,
    "subtotalLabel"   -> Json.fromString("Subtotal"),
    "grandTotalLabel" -> Json.fromString("Grand total"),
    "noClientLabel"   -> Json.fromString("No client"),
    "noTaskLabel"     -> Json.fromString("No task"),
    "memberLabels"    -> Json.fromValues(validMembers)
  )

  private val closedFilters: GlobalFilters = GlobalFilters(
    dateFrom = WorkUnitDate(LocalDate.parse("2026-08-01")),
    dateTo = Some(WorkUnitDate(LocalDate.parse("2026-08-31"))),
    projectIds = Seq.empty,
    userIds = Seq.empty,
    clientIds = Seq.empty
  )
  private val openFilters: GlobalFilters = closedFilters.copy(dateTo = None)

  private def projection(effectiveDateTo: Json): Json = Json.obj("effectiveDateTo" -> effectiveDateTo)

  private def envelope(
    query: Json = validQuery,
    presentation: Json = validPresentation,
    projection: Option[Json] = None
  ): Json =
    projection.fold(Json.obj("query" -> query, "presentation" -> presentation))(value =>
      Json.obj("query" -> query, "projection" -> value, "presentation" -> presentation)
    )

  private def presentationWith(name: String, value: Json): Json =
    validPresentation.mapObject(_.add(name, value))

  private def fieldLabelsWith(name: String, value: Json): Json =
    validPresentation.mapObject(_.add("fieldLabels", validFieldLabels.mapObject(_.add(name, value))))

  private def measureLabelsWith(name: String, value: Json): Json =
    validPresentation.mapObject(_.add("measureLabels", validMeasureLabels.mapObject(_.add(name, value))))

  private def configured(maxDisplayValueCellCount: Long = 10000L): WorkUnitPivotExportRequestValidator =
    new WorkUnitPivotExportRequestValidator(
      new WorkUnitPivotQueryRequestValidator,
      WorkUnitPivotExportRequestValidator.Config(maxDisplayValueCellCount)
    )

  private def validate(
    json: Json,
    filters: GlobalFilters = closedFilters,
    maxDisplayValueCellCount: Long = 10000L
  ): Either[ValidationError, ValidatedRequest] =
    configured(maxDisplayValueCellCount).validate(json, filters)

  private def exportViolations(json: Json, maxDisplayValueCellCount: Long = 10000L): Seq[ExportViolation] =
    validate(json, maxDisplayValueCellCount = maxDisplayValueCellCount) match {
      case Left(ValidationError.InvalidPresentation(error)) => error.violations
      case _                                                => Seq.empty
    }

  "WorkUnitPivotExportRequestValidator" should {
    "return the exact typed query and presentation for a complete valid envelope" in {
      validate(envelope()) shouldEqual Right(
        ValidatedRequest(
          query = WorkUnitPivotQuery.Request(
            rows = Seq(WorkUnitPivotTableKey.UserKey),
            columns = Seq(WorkUnitPivotTableKey.MonthYearKey),
            values = Seq(WorkUnitPivotTableValue.DurationSum, WorkUnitPivotTableValue.EntriesCount),
            filters = Seq.empty,
            options = WorkUnitPivotQuery.Options(rowSubtotals = true, columnSubtotals = false, grandTotals = true)
          ),
          projection = None,
          presentation = Presentation(
            fieldLabels = Map(
              WorkUnitPivotTableKey.UserKey      -> "User",
              WorkUnitPivotTableKey.ProjectKey   -> "Project",
              WorkUnitPivotTableKey.ClientKey    -> "Client",
              WorkUnitPivotTableKey.TaskKey      -> "Task",
              WorkUnitPivotTableKey.YearKey      -> "Year",
              WorkUnitPivotTableKey.MonthYearKey -> "Month",
              WorkUnitPivotTableKey.DateKey      -> "Date",
              WorkUnitPivotTableKey.EntryTypeKey -> "Entry type"
            ),
            measureLabels = Map(
              WorkUnitPivotTableValue.EntriesCount -> "Entries",
              WorkUnitPivotTableValue.DurationSum  -> "Total duration",
              WorkUnitPivotTableValue.DurationAvg  -> "Average duration"
            ),
            subtotalLabel = "Subtotal",
            grandTotalLabel = "Grand total",
            noClientLabel = "No client",
            noTaskLabel = "No task",
            memberLabels = Seq(
              MemberLabel(WorkUnitPivotTableKey.UserKey, FieldValue.Uuid(userId), "Alice"),
              MemberLabel(WorkUnitPivotTableKey.ProjectKey, FieldValue.Uuid(projectId), "Epoca"),
              MemberLabel(WorkUnitPivotTableKey.ClientKey, FieldValue.Uuid(clientId), "ACME"),
              MemberLabel(WorkUnitPivotTableKey.ClientKey, FieldValue.Null, "No client"),
              MemberLabel(WorkUnitPivotTableKey.YearKey, FieldValue.Year(2026), "2026"),
              MemberLabel(WorkUnitPivotTableKey.MonthYearKey, FieldValue.Month(YearMonth.of(2026, 8)), "August 2026"),
              MemberLabel(WorkUnitPivotTableKey.DateKey, FieldValue.Date(LocalDate.of(2026, 8, 13)), "13 August 2026"),
              MemberLabel(WorkUnitPivotTableKey.EntryTypeKey, FieldValue.EntryType(WorkUnitEntryType.Worklog), "Worklog")
            )
          )
        )
      )
    }

    "accept a strict optional projection for matching closed and open global ranges" in {
      val augustEnd = Projection(LocalDate.parse("2026-08-31"))
      validate(envelope(projection = Some(projection(Json.fromString("2026-08-31"))))) must beRight.like { case request =>
        request.projection shouldEqual Some(augustEnd)
      }
      validate(envelope(projection = Some(projection(Json.fromString("2026-09-15")))), filters = openFilters) must beRight.like { case request =>
        request.projection shouldEqual Some(Projection(LocalDate.parse("2026-09-15")))
      }
    }

    "validate projection shape and values at deterministic exact paths" in {
      val invalidObject = Json.obj("effectiveDateTo" -> Json.fromInt(20260831), "zUnknown" -> Json.True, "aUnknown" -> Json.True)
      val cases = Seq(
        Json.fromString("2026-08-31") -> Seq(ExportViolation("projection", ExportViolationCode.INVALID_PROPERTY_TYPE)),
        Json.obj()                    -> Seq(ExportViolation("projection.effectiveDateTo", ExportViolationCode.MISSING_REQUIRED_PROPERTY)),
        invalidObject -> Seq(
          ExportViolation("projection.effectiveDateTo", ExportViolationCode.INVALID_PROPERTY_TYPE),
          ExportViolation("projection.aUnknown", ExportViolationCode.UNSUPPORTED_FIELD),
          ExportViolation("projection.zUnknown", ExportViolationCode.UNSUPPORTED_FIELD)
        ),
        projection(Json.fromString("2026-8-31"))  -> Seq(ExportViolation("projection.effectiveDateTo", ExportViolationCode.INVALID_PROJECTION_VALUE)),
        projection(Json.fromString("2026-02-30")) -> Seq(ExportViolation("projection.effectiveDateTo", ExportViolationCode.INVALID_PROJECTION_VALUE))
      )

      cases.foreach { case (rawProjection, expected) =>
        exportViolations(envelope(projection = Some(rawProjection))) shouldEqual expected
      }
      ok
    }

    "reject projection dates that diverge from a closed end or precede an open start" in {
      exportViolations(envelope(projection = Some(projection(Json.fromString("2026-08-30"))))) shouldEqual Seq(
        ExportViolation("projection.effectiveDateTo", ExportViolationCode.INVALID_PROJECTION_VALUE)
      )
      configured().validate(envelope(projection = Some(projection(Json.fromString("2026-07-31")))), openFilters) shouldEqual Left(
        ValidationError.InvalidPresentation(
          InvalidExportRequest(violations = Seq(ExportViolation("projection.effectiveDateTo", ExportViolationCode.INVALID_PROJECTION_VALUE)))
        )
      )
    }

    "pass every raw query JSON type unchanged to the query validator and preserve its exact error" in {
      Seq(Json.Null, Json.fromInt(7), Json.arr()).foreach { rawQuery =>
        val expected = new WorkUnitPivotQueryRequestValidator().validate(rawQuery).left.toOption.get

        validate(envelope(query = rawQuery)) shouldEqual Left(ValidationError.InvalidQuery(expected))
        expected.asJson.noSpaces shouldEqual InvalidConfigurationError(violations =
          Seq(
            Violation("rows", ViolationCode.MISSING_REQUIRED_PROPERTY),
            Violation("columns", ViolationCode.MISSING_REQUIRED_PROPERTY),
            Violation("values", ViolationCode.MISSING_REQUIRED_PROPERTY),
            Violation("filters", ViolationCode.MISSING_REQUIRED_PROPERTY),
            Violation("options", ViolationCode.MISSING_REQUIRED_PROPERTY)
          )
        ).asJson.noSpaces
      }
      ok
    }

    "treat a non-object root as the shared malformed envelope" in {
      Seq(Json.Null, Json.fromString("request"), Json.arr()).foreach { root =>
        validate(root) shouldEqual Left(ValidationError.MalformedEnvelope)
      }
      ok
    }

    "report missing root properties and sorted unknown root properties without type-checking present properties" in {
      val root = Json.obj("query" -> Json.fromInt(7), "zRoot" -> Json.True, "aRoot" -> Json.arr())

      exportViolations(root) shouldEqual Seq(
        ExportViolation("presentation", ExportViolationCode.MISSING_REQUIRED_PROPERTY),
        ExportViolation("aRoot", ExportViolationCode.UNSUPPORTED_FIELD),
        ExportViolation("zRoot", ExportViolationCode.UNSUPPORTED_FIELD)
      )
    }

    "validate presentation only after query validation succeeds" in {
      val invalidQuery = Json.obj()
      val expected     = new WorkUnitPivotQueryRequestValidator().validate(invalidQuery).left.toOption.get

      validate(envelope(query = invalidQuery, presentation = Json.fromInt(1))) shouldEqual Left(ValidationError.InvalidQuery(expected))
      validate(envelope(presentation = Json.fromInt(1))) shouldEqual Left(
        ValidationError
          .InvalidPresentation(InvalidExportRequest(violations = Seq(ExportViolation("presentation", ExportViolationCode.INVALID_PROPERTY_TYPE))))
      )
    }

    "reject a presentation missing noTaskLabel when the query groups by TaskKey" in {
      exportViolations(envelope(query = taskGroupedQuery, presentation = validPresentation.mapObject(_.remove("noTaskLabel")))) must contain(
        ExportViolation("presentation.noTaskLabel", ExportViolationCode.MISSING_REQUIRED_PROPERTY)
      )
    }

    "reject a presentation missing fieldLabels.TaskKey when the query groups by TaskKey" in {
      val presentation = validPresentation.mapObject(_.add("fieldLabels", validFieldLabels.mapObject(_.remove("TaskKey"))))
      exportViolations(envelope(query = taskGroupedQuery, presentation = presentation)) must contain(
        ExportViolation("presentation.fieldLabels.TaskKey", ExportViolationCode.MISSING_REQUIRED_PROPERTY)
      )
    }

    "accept a presentation missing noTaskLabel and fieldLabels.TaskKey when the query does not group by TaskKey" in {
      val presentation = validPresentation.mapObject(_.remove("noTaskLabel").add("fieldLabels", validFieldLabels.mapObject(_.remove("TaskKey"))))
      exportViolations(envelope(presentation = presentation)) shouldEqual Seq.empty
    }

    "report presentation known-property failures in traversal order and unknown properties sorted last" in {
      val presentation = Json.obj(
        "fieldLabels"     -> Json.arr(),
        "subtotalLabel"   -> Json.True,
        "grandTotalLabel" -> Json.fromString("Grand total"),
        "noClientLabel"   -> Json.fromString("No client"),
        "noTaskLabel"     -> Json.fromString("No task"),
        "memberLabels"    -> Json.obj(),
        "zUnknown"        -> Json.True,
        "aUnknown"        -> Json.True
      )

      exportViolations(envelope(presentation = presentation)) shouldEqual Seq(
        ExportViolation("presentation.fieldLabels", ExportViolationCode.INVALID_PROPERTY_TYPE),
        ExportViolation("presentation.measureLabels", ExportViolationCode.MISSING_REQUIRED_PROPERTY),
        ExportViolation("presentation.subtotalLabel", ExportViolationCode.INVALID_PROPERTY_TYPE),
        ExportViolation("presentation.memberLabels", ExportViolationCode.INVALID_PROPERTY_TYPE),
        ExportViolation("presentation.aUnknown", ExportViolationCode.UNSUPPORTED_FIELD),
        ExportViolation("presentation.zUnknown", ExportViolationCode.UNSUPPORTED_FIELD)
      )
    }

    "require every field label and reject unknown labels after known fields" in {
      val labels =
        validFieldLabels.mapObject(_.remove("ProjectKey").add("UserKey", Json.fromInt(1)).add("zField", Json.True).add("aField", Json.True))
      val presentation = validPresentation.mapObject(_.add("fieldLabels", labels))

      exportViolations(envelope(presentation = presentation)) shouldEqual Seq(
        ExportViolation("presentation.fieldLabels.UserKey", ExportViolationCode.INVALID_PROPERTY_TYPE),
        ExportViolation("presentation.fieldLabels.ProjectKey", ExportViolationCode.MISSING_REQUIRED_PROPERTY),
        ExportViolation("presentation.fieldLabels.aField", ExportViolationCode.UNSUPPORTED_FIELD),
        ExportViolation("presentation.fieldLabels.zField", ExportViolationCode.UNSUPPORTED_FIELD)
      )
    }

    "require every measure label and reject unknown labels after known measures" in {
      val labels = validMeasureLabels.mapObject(
        _.remove("DurationSum").add("EntriesCount", Json.fromInt(1)).add("zMeasure", Json.True).add("aMeasure", Json.True)
      )
      val presentation = validPresentation.mapObject(_.add("measureLabels", labels))

      exportViolations(envelope(presentation = presentation)) shouldEqual Seq(
        ExportViolation("presentation.measureLabels.EntriesCount", ExportViolationCode.INVALID_PROPERTY_TYPE),
        ExportViolation("presentation.measureLabels.DurationSum", ExportViolationCode.MISSING_REQUIRED_PROPERTY),
        ExportViolation("presentation.measureLabels.aMeasure", ExportViolationCode.UNSUPPORTED_FIELD),
        ExportViolation("presentation.measureLabels.zMeasure", ExportViolationCode.UNSUPPORTED_FIELD)
      )
    }

    "normalize CR LF and tab runs to one space in every label family" in {
      val normalizedPresentation = validPresentation
        .mapObject(
          _.add("fieldLabels", validFieldLabels.mapObject(_.add("UserKey", Json.fromString("User\r\n\tname"))))
            .add("measureLabels", validMeasureLabels.mapObject(_.add("EntriesCount", Json.fromString("Entry\t\tcount"))))
            .add("subtotalLabel", Json.fromString("Sub\r\rtotal"))
            .add("grandTotalLabel", Json.fromString("Grand\ntotal"))
            .add("noClientLabel", Json.fromString("No\tclient"))
            .add("memberLabels", Json.arr(member("YearKey", Json.fromInt(2026), "Year\r\n\t2026")))
        )

      val validated = validate(envelope(presentation = normalizedPresentation)).toOption.get.presentation
      validated.fieldLabels(WorkUnitPivotTableKey.UserKey) shouldEqual "User name"
      validated.measureLabels(WorkUnitPivotTableValue.EntriesCount) shouldEqual "Entry count"
      validated.subtotalLabel shouldEqual "Sub total"
      validated.grandTotalLabel shouldEqual "Grand total"
      validated.noClientLabel shouldEqual "No client"
      validated.memberLabels.head.label shouldEqual "Year 2026"
    }

    "reject blank labels remaining controls and labels beyond either exact length boundary" in {
      val cases = Seq(
        fieldLabelsWith("UserKey", Json.fromString(" \u2003 "))                -> "presentation.fieldLabels.UserKey",
        measureLabelsWith("EntriesCount", Json.fromString("bad\u0001label"))   -> "presentation.measureLabels.EntriesCount",
        presentationWith("subtotalLabel", Json.fromString("bad\u0085label"))   -> "presentation.subtotalLabel",
        presentationWith("grandTotalLabel", Json.fromString("x" * 141))        -> "presentation.grandTotalLabel",
        presentationWith("noClientLabel", Json.fromString(("😀" * 140) + "x")) -> "presentation.noClientLabel"
      )

      cases.foreach { case (presentation, path) =>
        exportViolations(envelope(presentation = presentation)) shouldEqual Seq(ExportViolation(path, ExportViolationCode.INVALID_PRESENTATION_VALUE))
      }
      ok
    }

    "accept labels at 140 Unicode code points and 560 UTF-8 bytes" in {
      val boundary = "😀" * 140
      validate(envelope(presentation = presentationWith("subtotalLabel", Json.fromString(boundary)))) must beRight
    }

    "reject lone UTF-16 surrogates decoded from raw JSON strings" in {
      Seq("d800", "dc00").foreach { surrogate =>
        val unicodeEscape = 92.toChar.toString + "u" + surrogate
        val rawEnvelope   = envelope().noSpaces.replace("\"Subtotal\"", "\"" + unicodeEscape + "\"")
        val parsed        = parse(rawEnvelope).fold(throw _, identity)

        exportViolations(parsed) shouldEqual Seq(ExportViolation("presentation.subtotalLabel", ExportViolationCode.INVALID_PRESENTATION_VALUE))
      }
      ok
    }

    "validate member shape in known-property order and sorted unknown-property order" in {
      val invalidMember = Json.obj("key" -> Json.fromInt(1), "label" -> Json.True, "zUnknown" -> Json.True, "aUnknown" -> Json.True)
      val presentation  = presentationWith("memberLabels", Json.arr(Json.fromString("member"), invalidMember))

      exportViolations(envelope(presentation = presentation)) shouldEqual Seq(
        ExportViolation("presentation.memberLabels[0]", ExportViolationCode.INVALID_PROPERTY_TYPE),
        ExportViolation("presentation.memberLabels[1].key", ExportViolationCode.INVALID_PROPERTY_TYPE),
        ExportViolation("presentation.memberLabels[1].value", ExportViolationCode.MISSING_REQUIRED_PROPERTY),
        ExportViolation("presentation.memberLabels[1].label", ExportViolationCode.INVALID_PROPERTY_TYPE),
        ExportViolation("presentation.memberLabels[1].aUnknown", ExportViolationCode.UNSUPPORTED_FIELD),
        ExportViolation("presentation.memberLabels[1].zUnknown", ExportViolationCode.UNSUPPORTED_FIELD)
      )
    }

    "reject unsupported member keys and values that do not match the typed key" in {
      val mismatches = Seq(
        member("UnknownKey", Json.fromString(userId.toString), "Unknown"),
        member("UserKey", Json.Null, "User"),
        member("ProjectKey", Json.fromString("not-a-uuid"), "Project"),
        member("ClientKey", Json.fromInt(1), "Client"),
        member("YearKey", Json.fromString("2026"), "Year"),
        member("MonthYearKey", Json.fromString("2026-13"), "Month"),
        member("DateKey", Json.fromString("2026-02-30"), "Date"),
        member("EntryTypeKey", Json.fromString("worklog"), "Entry type")
      )
      val presentation = presentationWith("memberLabels", Json.fromValues(mismatches))

      exportViolations(envelope(presentation = presentation)) shouldEqual Seq(
        ExportViolation("presentation.memberLabels[0].key", ExportViolationCode.UNSUPPORTED_FIELD)
      ) ++ (1 until mismatches.size).map(index =>
        ExportViolation(s"presentation.memberLabels[$index].value", ExportViolationCode.INVALID_PRESENTATION_VALUE)
      )
    }

    "report a later duplicate value before label failures and sorted unknown properties" in {
      val duplicate = Json.obj(
        "key"      -> Json.fromString("ClientKey"),
        "value"    -> Json.Null,
        "label"    -> Json.fromString(" \u2003 "),
        "zUnknown" -> Json.True,
        "aUnknown" -> Json.True
      )
      val presentation = presentationWith("memberLabels", Json.fromValues(validMembers :+ duplicate))

      exportViolations(envelope(presentation = presentation)) shouldEqual Seq(
        ExportViolation("presentation.memberLabels[8].value", ExportViolationCode.DUPLICATE_PRESENTATION_MEMBER),
        ExportViolation("presentation.memberLabels[8].label", ExportViolationCode.INVALID_PRESENTATION_VALUE),
        ExportViolation("presentation.memberLabels[8].aUnknown", ExportViolationCode.UNSUPPORTED_FIELD),
        ExportViolation("presentation.memberLabels[8].zUnknown", ExportViolationCode.UNSUPPORTED_FIELD)
      )
    }

    "reject a canonical member entry beyond 768 bytes as an invalid presentation value" in {
      val oversized    = member("UserKey", Json.fromString(userId.toString), "😀" * 180)
      val presentation = presentationWith("memberLabels", Json.arr(oversized))

      exportViolations(envelope(presentation = presentation)) shouldEqual Seq(
        ExportViolation("presentation.memberLabels[0].label", ExportViolationCode.INVALID_PRESENTATION_VALUE),
        ExportViolation("presentation.memberLabels[0]", ExportViolationCode.INVALID_PRESENTATION_VALUE)
      )
    }

    "accept quotes and backslashes at the maximum valid label code-point boundary" in {
      val escapedBoundary = Seq.fill(46)("\"" + "\\" + "😀").mkString + "\"" + "\\"
      escapedBoundary.codePointCount(0, escapedBoundary.length) shouldEqual 140

      validate(
        envelope(presentation = presentationWith("memberLabels", Json.arr(member("YearKey", Json.fromInt(2026), escapedBoundary))))
      ) must beRight
    }

    "reject an oversized member array before traversing any member" in {
      val invalidMembers = Vector.fill(1000)(Json.fromString("not-a-member"))
      val presentation   = presentationWith("memberLabels", Json.fromValues(invalidMembers))

      exportViolations(envelope(presentation = presentation), maxDisplayValueCellCount = 1L) shouldEqual Seq(
        ExportViolation("presentation.memberLabels", ExportViolationCode.PRESENTATION_LIMIT_EXCEEDED)
      )
    }

    "parse a member array exactly at the derived count boundary" in {
      val boundaryMembers = (1 to 8).map(year => member("YearKey", Json.fromInt(2000 + year), s"Year $year"))
      val presentation    = presentationWith("memberLabels", Json.fromValues(boundaryMembers))

      validate(envelope(presentation = presentation), maxDisplayValueCellCount = 1L) must beRight
    }

    "expose saturated reusable export-contract limits and the canonical request budget" in {
      WorkUnitPivotExport.Limits.memberLabelLimit(10000L) shouldEqual 80000L
      WorkUnitPivotExport.Limits.canonicalBodyBudget(10000L) shouldEqual 69440000L
      WorkUnitPivotExport.Limits.memberLabelLimit(Long.MaxValue) shouldEqual Long.MaxValue
      WorkUnitPivotExport.Limits.canonicalBodyBudget(Long.MaxValue) shouldEqual Long.MaxValue
      WorkUnitPivotExport.Limits.memberLabelLimit(Int.MaxValue.toLong) shouldEqual 17179869176L
      WorkUnitPivotExport.Limits.requestEntityMaxBytes shouldEqual 64L * 1024L * 1024L
    }

    "encode the exact export problem body with the projection violation code" in {
      ExportViolationCode.values.toSet shouldEqual Set(
        ExportViolationCode.MISSING_REQUIRED_PROPERTY,
        ExportViolationCode.INVALID_PROPERTY_TYPE,
        ExportViolationCode.UNSUPPORTED_FIELD,
        ExportViolationCode.INVALID_PROJECTION_VALUE,
        ExportViolationCode.INVALID_PRESENTATION_VALUE,
        ExportViolationCode.DUPLICATE_PRESENTATION_MEMBER,
        ExportViolationCode.PRESENTATION_LIMIT_EXCEEDED
      )
      InvalidExportRequest(violations =
        Seq(
          ExportViolation("presentation.subtotalLabel", ExportViolationCode.INVALID_PRESENTATION_VALUE),
          ExportViolation("presentation.memberLabels", ExportViolationCode.PRESENTATION_LIMIT_EXCEEDED)
        )
      ).asJson.noSpaces shouldEqual
        "{\"code\":\"PIVOT_INVALID_EXPORT_REQUEST\",\"message\":\"Invalid pivot export request\",\"violations\":[{\"path\":\"presentation.subtotalLabel\",\"code\":\"INVALID_PRESENTATION_VALUE\"},{\"path\":\"presentation.memberLabels\",\"code\":\"PRESENTATION_LIMIT_EXCEEDED\"}]}"
    }
  }
}
