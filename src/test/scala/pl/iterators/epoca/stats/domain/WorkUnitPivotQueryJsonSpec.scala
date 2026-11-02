package pl.iterators.epoca.stats.domain

import io.circe.Json
import io.circe.syntax._
import org.specs2.mutable.SpecificationLike
import pl.iterators.epoca.stats.domain.StatsDomain.WorkUnitPivotTableKey
import pl.iterators.epoca.utils.json.JsonProtocol
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.WorkUnitEntryType

import java.time.{Duration, LocalDate, YearMonth}
import java.util.UUID

class WorkUnitPivotQueryJsonSpec extends SpecificationLike with JsonProtocol {
  import WorkUnitPivotQuery._

  "pivot query JSON" should {
    "encode raw values, structural coordinates, and requested metric nulls" in {
      val response = Response(
        cells = Seq(
          Cell(
            row = Coordinate(
              CoordinateKind.Leaf,
              Seq(PathSegment(WorkUnitPivotTableKey.UserKey, FieldValue.Uuid(UUID.fromString("11111111-1111-4111-8111-111111111111"))))
            ),
            column = Coordinate(CoordinateKind.Leaf, Seq(PathSegment(WorkUnitPivotTableKey.YearKey, FieldValue.Year(2026)))),
            values = MetricValues(durationSum = Some(Duration.ofHours(2)), durationAvg = Some(None), entriesCount = None)
          )
        ),
        filterMembers = Seq(FilterMembers(WorkUnitPivotTableKey.ClientKey, Seq(FilterMember(FieldValue.Null, available = true)))),
        result = ResultMetadata(cellCount = 1, displayValueCellCount = 2, maxDisplayValueCellCount = 10000)
      )

      val cell          = response.asJson.hcursor.downField("cells").downArray
      val emptyAxisJson = Coordinate(CoordinateKind.Leaf, Seq.empty).asJson

      (cell.downField("row").get[String]("kind") must beRight("Leaf")) and
        (cell.downField("row").downField("path").downArray.get[String]("key") must beRight("UserKey")) and
        (cell.downField("row").downField("path").downArray.get[String]("value") must beRight("11111111-1111-4111-8111-111111111111")) and
        (cell.downField("column").downField("path").downArray.get[Int]("value") must beRight(2026)) and
        (cell.downField("values").get[String]("durationSum") must beRight("PT2H")) and
        (cell.downField("values").downField("durationAvg").focus shouldEqual Some(Json.Null)) and
        (cell.downField("values").downField("entriesCount").succeeded must beFalse) and
        (response.asJson.hcursor.downField("filterMembers").downArray.downField("members").downArray.downField("value").focus must beSome(
          Json.Null
        )) and
        (emptyAxisJson.hcursor.get[String]("kind") must beRight("Leaf")) and
        (emptyAxisJson.hcursor.get[Seq[Json]]("path") must beRight(Seq.empty[Json]))
    }

    "encode every string FieldValue as its raw primitive" in {
      val month: FieldValue     = FieldValue.Month(YearMonth.of(2026, 8))
      val date: FieldValue      = FieldValue.Date(LocalDate.of(2026, 8, 7))
      val entryType: FieldValue = FieldValue.EntryType(WorkUnitEntryType.Worklog)
      val taskId: FieldValue    = FieldValue.TaskId("PROJ-123")

      (month.asJson shouldEqual Json.fromString("2026-08")) and
        (date.asJson shouldEqual Json.fromString("2026-08-07")) and
        (entryType.asJson shouldEqual Json.fromString("Worklog")) and
        (taskId.asJson shouldEqual Json.fromString("PROJ-123"))
    }

    "encode stable error bodies with exact codes" in {
      val invalid  = InvalidConfigurationError(violations = Seq(Violation("rows[0]", ViolationCode.UNSUPPORTED_FIELD)))
      val tooLarge = ResultTooLargeError(estimatedDisplayValueCellCount = 10001, maxDisplayValueCellCount = 10000)

      (invalid.asJson shouldEqual Json.obj(
        "code"       -> Json.fromString("PIVOT_INVALID_CONFIGURATION"),
        "message"    -> Json.fromString("Invalid pivot configuration"),
        "violations" -> Json.arr(Json.obj("path" -> Json.fromString("rows[0]"), "code" -> Json.fromString("UNSUPPORTED_FIELD")))
      )) and
        (tooLarge.asJson shouldEqual Json.obj(
          "code"                           -> Json.fromString("PIVOT_RESULT_TOO_LARGE"),
          "message"                        -> Json.fromString("Pivot result exceeds the configured limit"),
          "estimatedDisplayValueCellCount" -> Json.fromLong(10001),
          "maxDisplayValueCellCount"       -> Json.fromLong(10000)
        ))
    }

    "reject negative entries counts" in {
      MetricValues(durationSum = None, durationAvg = None, entriesCount = Some(-1L)) must throwA[IllegalArgumentException]
    }

    "leave metadata and limit counts outside the metric construction guard" in {
      (ResultMetadata(-1L, -2L, -3L).asJson shouldEqual Json.obj(
        "cellCount"                -> Json.fromLong(-1L),
        "displayValueCellCount"    -> Json.fromLong(-2L),
        "maxDisplayValueCellCount" -> Json.fromLong(-3L)
      )) and
        (ResultTooLargeError(estimatedDisplayValueCellCount = -1L, maxDisplayValueCellCount = -2L).asJson shouldEqual Json.obj(
          "code"                           -> Json.fromString("PIVOT_RESULT_TOO_LARGE"),
          "message"                        -> Json.fromString("Pivot result exceeds the configured limit"),
          "estimatedDisplayValueCellCount" -> Json.fromLong(-1L),
          "maxDisplayValueCellCount"       -> Json.fromLong(-2L)
        ))
    }
  }
}
