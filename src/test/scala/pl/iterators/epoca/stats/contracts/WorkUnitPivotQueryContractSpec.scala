package pl.iterators.epoca.stats.contracts

import io.circe.parser.parse
import io.circe.syntax._
import io.circe.{Json, JsonObject}
import org.specs2.mutable.SpecificationLike
import pl.iterators.epoca.stats.domain.StatsDomain.{WorkUnitPivotTableKey, WorkUnitPivotTableValue}
import pl.iterators.epoca.stats.domain.WorkUnitPivotQuery._
import pl.iterators.epoca.stats.services.WorkUnitPivotQueryRequestValidator

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.time.{Duration, YearMonth}
import java.util.UUID

class WorkUnitPivotQueryContractSpec extends SpecificationLike {
  private val schemaPath    = Path.of("docs/contracts/work-unit-pivot-query.schema.json")
  private val canonicalPath = Path.of("docs/contracts/work-unit-pivot-query-canonical-response.json")

  private def json(path: Path): Json =
    parse(Files.readString(path, StandardCharsets.UTF_8)).fold(error => throw new IllegalStateException(s"Invalid JSON at $path", error), identity)

  "published work-unit pivot query contract" should {
    "publish a strict Draft 2020-12 schema for the /query request, response, and errors" in {
      val schema = json(schemaPath)
      val cursor = schema.hcursor

      cursor.get[String]("$" + "schema") shouldEqual Right("https://json-schema.org/draft/2020-12/schema")
      cursor.get[String]("$" + "id") shouldEqual Right("https://api.company-6818d5a431.example.invalid/contracts/work-unit-pivot-query.schema.json")
      cursor.downField("x-route").get[String]("method") shouldEqual Right("POST")
      cursor.downField("x-route").get[String]("path") shouldEqual
        Right("/organizations/{organizationSlug}/pivot-tables/work-units/query")

      val definitions = cursor.downField("$" + "defs").focus.flatMap(_.asObject).getOrElse(JsonObject.empty)
      Seq("request", "response", "invalidConfigurationError", "resultTooLargeError", "coordinate", "pathSegment", "metricValues", "filterMembers")
        .forall(definitions.contains) should beTrue

      val pivotKeys = definitions("pivotKey").toSeq.flatMap(_.hcursor.downField("enum").focus.flatMap(_.asArray).toSeq.flatten.flatMap(_.asString))
      pivotKeys should containTheSameElementsAs(WorkUnitPivotTableKey.values.map(_.entryName))
      val measures = definitions("pivotValue").toSeq.flatMap(_.hcursor.downField("enum").focus.flatMap(_.asArray).toSeq.flatten.flatMap(_.asString))
      measures should containTheSameElementsAs(WorkUnitPivotTableValue.values.map(_.entryName))
      val kinds = definitions("coordinateKind").toSeq.flatMap(_.hcursor.downField("enum").focus.flatMap(_.asArray).toSeq.flatten.flatMap(_.asString))
      kinds should containTheSameElementsAs(CoordinateKind.values.map(_.entryName))
      val violationCodes =
        definitions("violationCode").toSeq.flatMap(_.hcursor.downField("enum").focus.flatMap(_.asArray).toSeq.flatten.flatMap(_.asString))
      violationCodes should containTheSameElementsAs(ViolationCode.values.map(_.entryName))

      definitions("request").flatMap(_.hcursor.downField("additionalProperties").focus.flatMap(_.asBoolean)) should beSome(false)
      definitions("response").flatMap(_.hcursor.downField("additionalProperties").focus.flatMap(_.asBoolean)) should beSome(false)
      val values = definitions("request").map(_.hcursor.downField("properties").downField("values"))
      values.flatMap(_.get[Int]("minItems").toOption) should beSome(1)
      values.flatMap(_.get[Boolean]("uniqueItems").toOption) should beSome(true)

      val schemaText = schema.noSpaces
      schemaText must contain("^[0-9]{4}-(0[1-9]|1[0-2])$")
      schemaText must contain("format\":\"uuid")
      schemaText must contain("format\":\"date")
      schemaText must contain("minimum\":0")
      schemaText must contain("additionalProperties\":false")
    }

    "type every filter-member facet and publish all 24 cross-area uniqueness constraints" in {
      val definitions = json(schemaPath).hcursor.downField("$" + "defs").focus.flatMap(_.asObject).getOrElse(JsonObject.empty)
      val filterMemberVariants = definitions("filterMembers")
        .flatMap(_.hcursor.downField("oneOf").focus.flatMap(_.asArray))
        .getOrElse(Vector.empty)

      filterMemberVariants.size shouldEqual 8
      filterMemberVariants.flatMap(_.hcursor.get[String]("$" + "ref").toOption).toSet shouldEqual Set(
        "#/$defs/userFilterMembers",
        "#/$defs/projectFilterMembers",
        "#/$defs/clientFilterMembers",
        "#/$defs/yearFilterMembers",
        "#/$defs/monthFilterMembers",
        "#/$defs/dateFilterMembers",
        "#/$defs/entryTypeFilterMembers",
        "#/$defs/taskFilterMembers"
      )

      typedFacetValueRef(definitions, "userFilterMembers") shouldEqual "#/$defs/uuidValue"
      typedFacetValueRef(definitions, "projectFilterMembers") shouldEqual "#/$defs/uuidValue"
      typedFacetValueRef(definitions, "clientFilterMembers") shouldEqual "#/$defs/clientValue"
      typedFacetValueRef(definitions, "yearFilterMembers") shouldEqual "#/$defs/yearValue"
      typedFacetValueRef(definitions, "monthFilterMembers") shouldEqual "#/$defs/monthValue"
      typedFacetValueRef(definitions, "dateFilterMembers") shouldEqual "#/$defs/dateValue"
      typedFacetValueRef(definitions, "entryTypeFilterMembers") shouldEqual "#/$defs/entryTypeValue"
      typedFacetValueRef(definitions, "taskFilterMembers") shouldEqual "#/$defs/taskValue"

      Seq(
        "userFilterMembers"      -> "UserKey",
        "projectFilterMembers"   -> "ProjectKey",
        "clientFilterMembers"    -> "ClientKey",
        "yearFilterMembers"      -> "YearKey",
        "monthFilterMembers"     -> "MonthYearKey",
        "dateFilterMembers"      -> "DateKey",
        "entryTypeFilterMembers" -> "EntryTypeKey",
        "taskFilterMembers"      -> "TaskKey"
      ).foreach { case (definition, key) =>
        definitions(definition).toSeq.flatMap(_.hcursor.downField("properties").downField("key").get[String]("const").toOption) shouldEqual Seq(key)
      }

      val constraints = definitions("request")
        .flatMap(_.hcursor.downField("allOf").focus.flatMap(_.asArray))
        .getOrElse(Vector.empty)
      val expected = for {
        key  <- WorkUnitPivotTableKey.values.map(_.entryName)
        pair <- Seq("rows-columns", "rows-filters", "columns-filters")
      } yield s"$key:$pair"
      constraints.flatMap(_.hcursor.get[String]("$comment").toOption) should containTheSameElementsAs(expected)
      constraints.size shouldEqual 24
      constraints.forall(_.hcursor.downField("not").succeeded) should beTrue
      constraints.count(_.noSpaces.contains("\"contains\"")) shouldEqual 24
      expected.foreach { identifier =>
        val parts      = identifier.split(":")
        val key        = parts(0)
        val pair       = parts(1)
        val constraint = constraints.find(_.hcursor.get[String]("$comment").contains(identifier)).getOrElse(Json.Null)
        val serialized = constraint.noSpaces
        occurrences(serialized, s"\"const\":\"$key\"") shouldEqual 2
        pair.split("-").foreach(area => serialized must contain(s"\"$area\""))
      }

      val validator = new WorkUnitPivotQueryRequestValidator()
      WorkUnitPivotTableKey.values.foreach { key =>
        val name = Json.fromString(key.entryName)
        Seq(
          rawRequest(rows = Json.arr(name), columns = Json.arr(name)),
          rawRequest(rows = Json.arr(name), filters = Json.arr(rawFilter(name))),
          rawRequest(columns = Json.arr(name), filters = Json.arr(rawFilter(name)))
        ).foreach { invalid =>
          validator
            .validate(invalid)
            .left
            .toOption
            .toSeq
            .flatMap(_.violations.map(_.code))
            .contains(ViolationCode.FIELD_ASSIGNED_TO_MULTIPLE_AREAS) should beTrue
        }
      }
      validator.validate(
        rawRequest(
          rows = Json.arr(Json.fromString("UserKey")),
          columns = Json.arr(Json.fromString("ProjectKey")),
          filters = Json.arr(rawFilter(Json.fromString("ClientKey")))
        )
      ) must beRight
    }

    "publish the complete canonical sparse 11-cell fixture as the exact domain encoding" in {
      val canonical = json(canonicalPath)
      canonical shouldEqual canonicalResponse.asJson
      canonical.hcursor.downField("cells").focus.flatMap(_.asArray).map(_.size) should beSome(11)
      canonical.hcursor.downField("result").get[Long]("cellCount") shouldEqual Right(11L)
      canonical.hcursor.downField("result").get[Long]("displayValueCellCount") shouldEqual Right(4L * 3L * 3L)
      canonical.hcursor.downField("result").get[Long]("maxDisplayValueCellCount") shouldEqual Right(9600L)

      val cells = canonical.hcursor.downField("cells").focus.flatMap(_.asArray).getOrElse(Vector.empty)
      val pairs = cells.map(cell => cell.hcursor.downField("row").focus -> cell.hcursor.downField("column").focus)
      pairs.distinct.size shouldEqual pairs.size
      cells.forall(pathKindsAreValid) should beTrue
      cells.exists { cell =>
        val rowProject = cell.hcursor.downField("row").downField("path").focus.exists(_.noSpaces.contains("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"))
        val august     = cell.hcursor.downField("column").downField("path").focus.exists(_.noSpaces.contains("2026-08"))
        rowProject && august
      } should beFalse
    }
  }

  private def pathKindsAreValid(cell: Json): Boolean =
    coordinateIsValid(cell.hcursor.downField("row").focus, Seq("UserKey", "ProjectKey")) &&
      coordinateIsValid(cell.hcursor.downField("column").focus, Seq("MonthYearKey"))

  private def typedFacetValueRef(definitions: JsonObject, definition: String): String =
    definitions(definition).toSeq
      .flatMap(
        _.hcursor
          .downField("properties")
          .downField("members")
          .downField("items")
          .downField("properties")
          .downField("value")
          .get[String]("$" + "ref")
          .toOption
      )
      .headOption
      .getOrElse("")

  private def rawFilter(key: Json): Json = Json.obj("key" -> key, "includedValues" -> Json.Null)

  private def occurrences(value: String, pattern: String): Int =
    value.sliding(pattern.length).count(_ == pattern)

  private def rawRequest(
    rows: Json = Json.arr(),
    columns: Json = Json.arr(),
    filters: Json = Json.arr()
  ): Json =
    Json.obj(
      "rows"    -> rows,
      "columns" -> columns,
      "values"  -> Json.arr(Json.fromString("EntriesCount")),
      "filters" -> filters,
      "options" -> Json.obj("rowSubtotals" -> Json.False, "columnSubtotals" -> Json.False, "grandTotals" -> Json.True)
    )

  private def coordinateIsValid(coordinate: Option[Json], axis: Seq[String]): Boolean = coordinate.exists { value =>
    val cursor = value.hcursor
    val kind   = cursor.get[String]("kind").toOption
    val path = cursor
      .downField("path")
      .focus
      .flatMap(_.asArray)
      .toSeq
      .flatten
      .flatMap(_.hcursor.get[String]("key").toOption)
    val prefix = path == axis.take(path.size)
    val matchingKind = kind.contains(
      if (path.size == axis.size) "Leaf"
      else if (path.isEmpty) "GrandTotal"
      else "Subtotal"
    )
    prefix && matchingKind
  }

  private val user     = FieldValue.Uuid(UUID.fromString("11111111-1111-4111-8111-111111111111"))
  private val projectA = FieldValue.Uuid(UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"))
  private val projectB = FieldValue.Uuid(UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"))
  private val july     = FieldValue.Month(YearMonth.of(2026, 7))
  private val august   = FieldValue.Month(YearMonth.of(2026, 8))

  private val userPath     = Seq(PathSegment(WorkUnitPivotTableKey.UserKey, user))
  private val projectAPath = userPath :+ PathSegment(WorkUnitPivotTableKey.ProjectKey, projectA)
  private val projectBPath = userPath :+ PathSegment(WorkUnitPivotTableKey.ProjectKey, projectB)
  private val julyPath     = Seq(PathSegment(WorkUnitPivotTableKey.MonthYearKey, july))
  private val augustPath   = Seq(PathSegment(WorkUnitPivotTableKey.MonthYearKey, august))

  private def coordinate(kind: CoordinateKind, path: Seq[PathSegment]): Coordinate = Coordinate(kind, path)

  private def cell(
    rowKind: CoordinateKind,
    rowPath: Seq[PathSegment],
    columnKind: CoordinateKind,
    columnPath: Seq[PathSegment],
    sum: Duration,
    average: Duration,
    count: Long
  ): Cell =
    Cell(coordinate(rowKind, rowPath), coordinate(columnKind, columnPath), MetricValues(Some(sum), Some(Some(average)), Some(count)))

  private val canonicalResponse = Response(
    cells = Seq(
      cell(CoordinateKind.Leaf, projectAPath, CoordinateKind.Leaf, julyPath, Duration.ofHours(2), Duration.ofHours(2), 1),
      cell(CoordinateKind.Leaf, projectAPath, CoordinateKind.Leaf, augustPath, Duration.ofHours(3), Duration.ofHours(3), 1),
      cell(CoordinateKind.Leaf, projectAPath, CoordinateKind.GrandTotal, Seq.empty, Duration.ofHours(5), Duration.ofMinutes(150), 2),
      cell(CoordinateKind.Leaf, projectBPath, CoordinateKind.Leaf, julyPath, Duration.ofHours(1), Duration.ofHours(1), 1),
      cell(CoordinateKind.Leaf, projectBPath, CoordinateKind.GrandTotal, Seq.empty, Duration.ofHours(1), Duration.ofHours(1), 1),
      cell(CoordinateKind.Subtotal, userPath, CoordinateKind.Leaf, julyPath, Duration.ofHours(3), Duration.ofMinutes(90), 2),
      cell(CoordinateKind.Subtotal, userPath, CoordinateKind.Leaf, augustPath, Duration.ofHours(3), Duration.ofHours(3), 1),
      cell(CoordinateKind.Subtotal, userPath, CoordinateKind.GrandTotal, Seq.empty, Duration.ofHours(6), Duration.ofHours(2), 3),
      cell(CoordinateKind.GrandTotal, Seq.empty, CoordinateKind.Leaf, julyPath, Duration.ofHours(3), Duration.ofMinutes(90), 2),
      cell(CoordinateKind.GrandTotal, Seq.empty, CoordinateKind.Leaf, augustPath, Duration.ofHours(3), Duration.ofHours(3), 1),
      cell(CoordinateKind.GrandTotal, Seq.empty, CoordinateKind.GrandTotal, Seq.empty, Duration.ofHours(6), Duration.ofHours(2), 3)
    ),
    filterMembers = Seq.empty,
    result = ResultMetadata(cellCount = 11L, displayValueCellCount = 36L, maxDisplayValueCellCount = 9600L)
  )
}
