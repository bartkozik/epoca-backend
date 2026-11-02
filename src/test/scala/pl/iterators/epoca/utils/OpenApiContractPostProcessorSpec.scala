package pl.iterators.epoca.utils

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.{ArrayNode, ObjectNode}
import io.circe.parser.{parse => parseJson}
import io.swagger.v3.core.util.Yaml
import org.specs2.mutable.SpecificationLike
import pl.iterators.epoca.export.services.WorkUnitPivotExportRequestValidator
import pl.iterators.epoca.oauth.policy.OAuthOperationRegistry
import pl.iterators.epoca.stats.domain.WorkUnitPivotQuery.GlobalFilters
import pl.iterators.epoca.stats.services.WorkUnitPivotQueryRequestValidator
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.WorkUnitDate

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.LocalDate
import scala.jdk.CollectionConverters._

class OpenApiContractPostProcessorSpec extends SpecificationLike {
  private val reportPath                          = "/organizations/{organizationSlug}/work-units"
  private val pivotPath                           = "/organizations/{organizationSlug}/pivot-tables/work-units/query"
  private val pivotExportPath                     = "/organizations/{organizationSlug}/pivot-tables/work-units/export"
  private val currentPath                         = "/organizations/{organizationSlug}/work-units/{workUnitId}/override"
  private val historyPath                         = "/organizations/{organizationSlug}/work-units/{workUnitId}/override-history"
  private val buildInfoSchemaName                 = OpenApiContractPostProcessor.BuildInfoSchemaRef.stripPrefix("#/components/schemas/")
  private val pivotProjectIds                     = Seq("11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222")
  private val pivotUserIds                        = Seq("33333333-3333-4333-8333-333333333333", "44444444-4444-4444-8444-444444444444")
  private val pivotClientIds                      = Seq("55555555-5555-4555-8555-555555555555", "66666666-6666-4666-8666-666666666666")
  private val requiredPivotResponseStatuses       = Seq("200", "400", "401", "403", "404", "422")
  private val requiredPivotExportResponseStatuses = Seq("200", "204", "400", "401", "403", "404", "413", "422", "503")
  private val pivotRequestSchemaRef               = "#/components/schemas/WorkUnitPivotQueryRequest"
  private val pivotResponseSchemaRef              = "#/components/schemas/WorkUnitPivotQueryResponse"
  private val invalidConfigurationSchemaRef       = "#/components/schemas/WorkUnitPivotInvalidConfigurationError"
  private val resultTooLargeSchemaRef             = "#/components/schemas/WorkUnitPivotResultTooLargeError"
  private val problemDetailsSchemaRef             = "#/components/schemas/WorkUnitPivotProblemDetails"
  private val pivotExportRequestSchemaRef         = "#/components/schemas/WorkUnitPivotExportRequest"
  private val pivotExportPresentationSchemaRef    = "#/components/schemas/WorkUnitPivotExportPresentation"
  private val invalidExportRequestSchemaRef       = "#/components/schemas/WorkUnitPivotInvalidExportRequest"
  private val refPointerSegment                   = "$" + "ref"

  private def parse(value: String): JsonNode = Yaml.mapper().readTree(value)

  private def parameter(
    root: JsonNode,
    path: String,
    method: String,
    name: String
  ): JsonNode =
    root.at(s"/paths/${escape(path)}/$method/parameters").elements().asScala.find(_.path("name").asText() == name).get

  private def example(
    root: JsonNode,
    pointer: String,
    name: String
  ): JsonNode = {
    val value = rawExample(root, pointer, name)
    if (value.isTextual) Yaml.mapper().readTree(value.asText()) else value
  }

  private def rawExample(
    root: JsonNode,
    pointer: String,
    name: String
  ): JsonNode = root.at(s"$pointer/examples/$name/value")

  private def escape(value: String): String = value.replace("~", "~0").replace("/", "~1")

  private def responseStatuses(
    root: JsonNode,
    path: String,
    method: String
  ): Seq[String] =
    root.at(s"/paths/${escape(path)}/$method/responses").fieldNames().asScala.toSeq.sorted

  "process" should {
    "include the human-only bearer scheme in protected-operation inventory" in {
      val root  = Yaml.mapper().createObjectNode()
      val paths = root.putObject("paths")
      OAuthOperationRegistry.default.classifiedProtectedOperations.foreach { registered =>
        val path =
          if (paths.has(registered.pathTemplate)) paths.path(registered.pathTemplate).asInstanceOf[ObjectNode]
          else paths.putObject(registered.pathTemplate)
        val operation = path.putObject(registered.method.value.toLowerCase)
        val scheme =
          if (registered.pathTemplate == s"$currentPath/undo") "humanBearer" else "bearerAuth"
        operation.putArray("security").addObject().putArray(scheme)
      }

      OpenApiContractPostProcessor.verifyOAuthProtectedOperationRegistry(Yaml.pretty().writeValueAsString(root))
      success
    }
    "publish strict undo request, response metadata and provenance-specific history contracts" in {
      val root    = parse(OpenApiContractPostProcessor.process(Fixture))
      val schemas = root.at("/components/schemas")
      val request = schemas.path("UndoWorkUnitOverrideRequest")
      request.at("/properties/expectedRevision/type").asText() shouldEqual "integer"
      request.at("/properties/expectedRevision/minimum").asInt() shouldEqual 0
      request.at("/properties/targetEventId/format").asText() shouldEqual "uuid"
      request.path("required").elements().asScala.map(_.asText()).toSet shouldEqual Set("expectedRevision", "targetEventId")
      schemas.at("/WorkUnitOverride/properties/undo/nullable").asBoolean() should beTrue
      schemas.at("/WorkUnitOverride/required").elements().asScala.map(_.asText()).toSet must not contain "undo"
      val undoStates = schemas.at("/WorkUnitOverride/properties/undo/anyOf")
      undoStates.size() shouldEqual 2
      undoStates.at("/0/properties/canUndo/enum/0").asBoolean() should beTrue
      undoStates.at("/0/properties/candidateEventId/nullable").asBoolean() should beFalse
      undoStates.at("/0/properties/reason/enum/0").isNull should beTrue
      undoStates.at("/1/properties/canUndo/enum/0").asBoolean() should beFalse
      undoStates.at("/1/properties/candidateEventId/nullable").asBoolean() should beTrue
      undoStates.at("/1/properties/reason/enum").elements().asScala.map(_.asText()).toSet shouldEqual
        Set("NoCorrection", "LegacyBoundary", "Forbidden", "TargetUnavailable")
      val history = schemas.path("WorkUnitOverrideHistoryEvent")
      Seq("parentCorrectionId", "revertsEventId", "revertedBy").foreach { field =>
        history.at(s"/properties/$field/nullable").asBoolean() should beTrue
      }
      history.at("/oneOf/0/properties/operation/enum").elements().asScala.map(_.asText()).toSet shouldEqual Set("Replace", "Clear", "Undo")
      history.at("/oneOf/1/properties/operation/enum").elements().asScala.map(_.asText()).toSet shouldEqual Set("Replace", "Clear")
      responseStatuses(root, s"$currentPath/undo", "post") shouldEqual Seq("200", "400", "401", "403", "404", "409")
      root.at(s"/paths/${escape(s"$currentPath/undo")}/post/security/0/humanBearer").isArray should beTrue
    }
    "replace generated undo examples with deterministic schema-safe values" in {
      val root            = parse(OpenApiContractPostProcessor.process(Fixture))
      val undoPath        = s"$currentPath/undo"
      val requestMedia    = s"/paths/${escape(undoPath)}/post/requestBody/content/application~1json"
      val responseMedia   = s"/paths/${escape(undoPath)}/post/responses/200/content/application~1json"
      val expectedEventId = "55555555-5555-4555-8555-555555555555"

      Seq("minimal", "maximal").foreach { name =>
        val request = example(root, requestMedia, name)
        request.fieldNames().asScala.toSet shouldEqual Set("expectedRevision", "targetEventId")
        request.path("expectedRevision").asLong() shouldEqual 5L
        request.path("expectedRevision").asLong() must beBetween(0L, 9007199254740991L)
        request.path("targetEventId").asText() shouldEqual expectedEventId

        val response = example(root, responseMedia, name)
        response.path("entryType").asText() shouldEqual "Worklog"
        response.path("state").asText() shouldEqual "Active"
        response.path("revision").asLong() shouldEqual 6L
        response.at("/override/version").asLong() shouldEqual 6L
        response.at("/override/version").asLong() must beBetween(0L, 9007199254740991L)
        response.at("/override/taskId/mode").asText() shouldEqual "Inherit"
        response.at("/override/undo/candidateEventId").isNull should beTrue
        response.at("/override/undo/canUndo").asBoolean() should beFalse
        response.at("/override/undo/reason").asText() shouldEqual "LegacyBoundary"
        response.at("/override/undo").fieldNames().asScala.toSet shouldEqual Set("candidateEventId", "canUndo", "reason")
      }
      success
    }
    "reject an undo operation missing a documented conflict response" in {
      val root = parse(Fixture).deepCopy[ObjectNode]()
      root.at(s"/paths/${escape(s"$currentPath/undo")}/post/responses").asInstanceOf[ObjectNode].remove("409")
      OpenApiContractPostProcessor.process(Yaml.pretty().writeValueAsString(root)) must throwA[IllegalStateException]
    }
    "publish effective report filters and deterministic source, override and audit examples" in {
      val output = OpenApiContractPostProcessor.process(Fixture)
      val root   = parse(output)

      Seq("projectId" -> OpenApiContractPostProcessor.OverrideProjectIds, "clientId" -> OpenApiContractPostProcessor.OverrideClientIds).foreach {
        case (name, expectedExamples) =>
          val param = parameter(root, reportPath, "get", name)

          param.path("style").asText() shouldEqual "form"
          param.path("explode").asBoolean() should beTrue
          param.at("/schema/type").asText() shouldEqual "array"
          param.at("/schema/items/type").asText() shouldEqual "string"
          param.at("/schema/items/format").asText() shouldEqual "uuid"
          param.at("/schema/example").elements().asScala.map(_.asText()).toSeq shouldEqual expectedExamples
      }

      Seq("projectId" -> pivotProjectIds, "userId" -> pivotUserIds, "clientId" -> pivotClientIds).foreach { case (name, expectedExamples) =>
        val param = parameter(root, pivotPath, "post", name)

        param.path("style").asText() shouldEqual "form"
        param.path("explode").asBoolean() should beTrue
        param.at("/schema/type").asText() shouldEqual "array"
        param.at("/schema/items/type").asText() shouldEqual "string"
        param.at("/schema/items/format").asText() shouldEqual "uuid"
        param.at("/schema/example").elements().asScala.map(_.asText()).toSeq shouldEqual expectedExamples
      }

      responseStatuses(root, pivotPath, "post") shouldEqual requiredPivotResponseStatuses

      val dateFrom = parameter(root, pivotPath, "post", "dateFrom")
      dateFrom.path("required").asBoolean() should beTrue
      dateFrom.at("/schema/type").asText() shouldEqual "string"
      dateFrom.at("/schema/format").asText() shouldEqual "date"

      val pivotOperation = root.at(s"/paths/${escape(pivotPath)}/post")
      pivotOperation.at(s"/requestBody/content/application~1json/schema/$refPointerSegment").asText() shouldEqual pivotRequestSchemaRef
      pivotOperation.at(s"/responses/200/content/application~1json/schema/$refPointerSegment").asText() shouldEqual pivotResponseSchemaRef
      pivotOperation.at(s"/responses/400/content/application~1json/schema/oneOf/0/$refPointerSegment").asText() shouldEqual
        invalidConfigurationSchemaRef
      pivotOperation.at(s"/responses/400/content/application~1json/schema/oneOf/1/$refPointerSegment").asText() shouldEqual problemDetailsSchemaRef
      pivotOperation.at(s"/responses/403/content/application~1json/schema/$refPointerSegment").asText() shouldEqual problemDetailsSchemaRef
      pivotOperation.at(s"/responses/422/content/application~1json/schema/$refPointerSegment").asText() shouldEqual resultTooLargeSchemaRef

      val requestMedia = s"/paths/${escape(pivotPath)}/post/requestBody/content/application~1json"
      val validator    = new WorkUnitPivotQueryRequestValidator()
      Seq("minimal", "maximal").foreach { name =>
        rawExample(root, requestMedia, name).isObject should beTrue
        val request = example(root, requestMedia, name)
        validator.validate(parseJson(request.toString).toOption.get) must beRight
      }

      val responseMedia = s"/paths/${escape(pivotPath)}/post/responses/200/content/application~1json"
      Seq("minimal", "maximal").foreach(name => rawExample(root, responseMedia, name).isObject should beTrue)
      example(root, responseMedia, "maximal") shouldEqual
        Yaml.mapper().readTree(Files.readString(OpenApiContractPostProcessor.PivotCanonicalResponsePath, StandardCharsets.UTF_8))
      Seq("400", "403", "422").foreach { status =>
        val media = s"/paths/${escape(pivotPath)}/post/responses/$status/content/application~1json"
        Seq("minimal", "maximal").foreach(name => rawExample(root, media, name).isObject should beTrue)
      }

      val components = root.at("/components/schemas")
      components.at("/WorkUnitPivotFieldValue/oneOf").size() should be_>=(6)
      components.at("/WorkUnitPivotMetricValues/properties/durationAvg/nullable").asBoolean() should beTrue
      components.at("/WorkUnitPivotMetricValues/properties/entriesCount/type").asText() shouldEqual "integer"
      components.at("/WorkUnitPivotMetricValues/properties/entriesCount/format").asText() shouldEqual "int64"
      components.at("/WorkUnitPivotMetricValues/properties/entriesCount/minimum").asLong() shouldEqual 0L
      components.at("/WorkUnitPivotQueryRequest/properties/values/minItems").asInt() shouldEqual 1
      components.at("/WorkUnitPivotQueryRequest/properties/values/uniqueItems").asBoolean() should beTrue
      components.at("/WorkUnitPivotQueryRequest/description").asText() must contain("JSON Schema and runtime validation are authoritative")
      components.at("/WorkUnitPivotInvalidConfigurationError/properties/violations/items/properties/code/enum").size() shouldEqual 11
      val problemType = components.at("/WorkUnitPivotProblemDetails/properties/type")
      problemType.path("type").asText() shouldEqual "string"
      problemType.path("format").isMissingNode should beTrue
      problemType.path("pattern").isMissingNode should beTrue
      problemType.path("description").asText() must contain("relative or absolute")
      Seq("malformed-request-content", "missing-query-param", "malformed-query-param").foreach { relativeIdentifier =>
        relativeIdentifier must not(beMatching("^[A-Za-z][A-Za-z0-9+.-]*:.*"))
      }
      components.at("/WorkUnitPivotFilterMembers/oneOf").size() shouldEqual 8
      components.at(s"/WorkUnitPivotUserFilterMembers/properties/members/items/properties/value/$refPointerSegment").asText() shouldEqual
        "#/components/schemas/WorkUnitPivotUuid"
      components.at(s"/WorkUnitPivotProjectFilterMembers/properties/members/items/properties/value/$refPointerSegment").asText() shouldEqual
        "#/components/schemas/WorkUnitPivotUuid"
      components.at(s"/WorkUnitPivotClientFilterMembers/properties/members/items/properties/value/$refPointerSegment").asText() shouldEqual
        "#/components/schemas/WorkUnitPivotNullableUuid"
      components.at(s"/WorkUnitPivotYearFilterMembers/properties/members/items/properties/value/$refPointerSegment").asText() shouldEqual
        "#/components/schemas/WorkUnitPivotYear"
      components.at(s"/WorkUnitPivotMonthFilterMembers/properties/members/items/properties/value/$refPointerSegment").asText() shouldEqual
        "#/components/schemas/WorkUnitPivotMonth"
      components.at(s"/WorkUnitPivotDateFilterMembers/properties/members/items/properties/value/$refPointerSegment").asText() shouldEqual
        "#/components/schemas/WorkUnitPivotDate"
      components.at(s"/WorkUnitPivotEntryTypeFilterMembers/properties/members/items/properties/value/$refPointerSegment").asText() shouldEqual
        "#/components/schemas/WorkUnitPivotEntryType"
      components.at(s"/WorkUnitPivotTaskFilterMembers/properties/members/items/properties/value/$refPointerSegment").asText() shouldEqual
        "#/components/schemas/WorkUnitPivotTaskId"

      val notFound = pivotOperation.at("/responses/404/content/text~1plain")
      notFound.at("/schema/type").asText() shouldEqual "string"
      rawExample(notFound, "", "minimal").isTextual should beTrue
      rawExample(notFound, "", "minimal").asText() shouldEqual
        "The requested resource could not be found but may be available again in the future."
      pivotOperation.at("/responses/401/content").isMissingNode should beTrue

      output must not(contain("enum:\n                  - 'Null'"))
      output must not(contain("enum:\n                  - None"))

      root
        .at(s"/paths/${escape("/build-info")}/get/responses/200/content/application~1json/schema/$$ref")
        .asText() shouldEqual OpenApiContractPostProcessor.BuildInfoSchemaRef
      val buildInfoSchema = root.at(s"/components/schemas/${escape(buildInfoSchemaName)}")
      buildInfoSchema.path("type").asText() shouldEqual "object"
      buildInfoSchema.path("required").elements().asScala.map(_.asText()).toSeq shouldEqual Seq("commitSha")
      buildInfoSchema.at("/properties/commitSha/type").asText() shouldEqual "string"
      buildInfoSchema.at("/properties/commitSha/pattern").asText() shouldEqual "^[0-9a-f]{40}$"
      Seq("minimal", "maximal").foreach { name =>
        val buildInfo = example(root, s"/paths/${escape("/build-info")}/get/responses/200/content/application~1json", name)
        buildInfo.path("commitSha").asText() shouldEqual OpenApiContractPostProcessor.CommitSha
        buildInfo.path("commitSha").asText() must beMatching("[0-9a-f]{40}")
      }

      val currentJson = example(root, s"/paths/${escape(currentPath)}/get/responses/200/content/application~1json", "minimal")
      currentJson.path("override").path("active").asBoolean() should beFalse
      currentJson.path("override").path("version").asLong() shouldEqual 0L
      currentJson.path("projectClient").path("projectName").asText() shouldEqual "Original Project"
      currentJson.path("taskId").asText() shouldEqual "ORIGINAL-1"
      currentJson.path("override").path("lastChangedBy").isNull should beTrue
      currentJson.path("override").path("lastChangedAt").isNull should beTrue

      val currentMaximal = example(root, s"/paths/${escape(currentPath)}/get/responses/200/content/application~1json", "maximal")
      currentMaximal.path("override").path("active").asBoolean() should beTrue
      currentMaximal.path("revision").asLong() must be_>(0L)
      currentMaximal.path("taskId").asText() shouldEqual "ORIGINAL-1"
      currentMaximal.path("effectiveTaskId").asText() shouldEqual "CT-567"
      currentMaximal.path("effectiveProjectClient").path("projectName").asText() shouldEqual "Corrected Project"
      currentMaximal.has("pmClassification") should beFalse
      currentMaximal.path("override").path("version").asLong() must be_>=(0L)
      currentMaximal.path("override").path("lastChangedAt").asText() shouldEqual OpenApiContractPostProcessor.ChangedAt

      Seq("minimal", "maximal").foreach { name =>
        val request = example(root, s"/paths/${escape(currentPath)}/put/requestBody/content/application~1json", name)
        request.path("expectedRevision").asLong() must be_>=(0L)
        request.path("projectId").asText() shouldEqual OpenApiContractPostProcessor.OverrideProjectIds.head

        val replacement = example(root, s"/paths/${escape(currentPath)}/put/responses/200/content/application~1json", name)
        replacement.path("override").path("active").asBoolean() should beTrue
        replacement.path("override").path("version").asLong() must be_>=(0L)
        replacement.path("override").path("lastChangedAt").asText() shouldEqual OpenApiContractPostProcessor.ChangedAt
      }

      Seq("minimal", "maximal").foreach { name =>
        val deleted = example(root, s"/paths/${escape(currentPath)}/delete/responses/200/content/application~1json", name)
        deleted.path("override").path("active").asBoolean() should beFalse
        deleted.path("override").path("hasHistory").asBoolean() should beTrue
        deleted.path("effectiveTaskId").asText() shouldEqual "ORIGINAL-1"
        deleted.path("override").path("version").asLong() must be_>=(0L)
      }

      Seq("minimal", "maximal").foreach { name =>
        val history = example(root, s"/paths/${escape(historyPath)}/get/responses/200/content/application~1json", name)
        history.path("count").asInt() must be_>=(0)
        history.path("pageNumber").asInt() must be_>(0)
        history.path("pageSize").asInt() must be_>(0)
        history.path("upToVersion").asLong() must be_>=(0L)
        history.path("results").elements().asScala.foreach { item =>
          item.path("version").asLong() must be_>=(0L)
          item.path("changedAt").asText() shouldEqual OpenApiContractPostProcessor.ChangedAt
          item.path("provenance").asText() shouldEqual "WorkUnitOverride"
          item.at("/before/source/taskId").asText() shouldEqual "ORIGINAL-1"
          item.at("/after/effective/taskId").asText() shouldEqual "CT-567"
          item.path("legacyBefore").isNull should beTrue
        }
      }

      output must not(beEmpty)
    }

    "keep reset permission in eligible inactive override examples" in {
      val root      = parse(OpenApiContractPostProcessor.process(Fixture))
      val untouched = example(root, s"/paths/${escape(currentPath)}/get/responses/200/content/application~1json", "minimal")
      val reset     = example(root, s"/paths/${escape(currentPath)}/delete/responses/200/content/application~1json", "minimal")
      (untouched.at("/override/permissions/canDelete").asBoolean() should beTrue) and
        (reset.at("/override/permissions/canDelete").asBoolean() should beTrue)
    }

    "show only the project and client fields actually stored in audit snapshots" in {
      val root           = parse(OpenApiContractPostProcessor.process(Fixture))
      val history        = example(root, s"/paths/${escape(historyPath)}/get/responses/200/content/application~1json", "maximal")
      val event          = history.path("results").get(0)
      val expectedFields = Set("projectId", "projectName", "clientId", "clientName")
      Seq("before", "after").foreach { side =>
        Seq("source", "effective").foreach { values =>
          event.at(s"/$side/$values/projectClient").fieldNames().asScala.toSet shouldEqual expectedFields
        }
      }
      success
    }

    "be idempotent and fail fast when the expected report parameter structure disappears" in {
      val processed = OpenApiContractPostProcessor.process(Fixture)

      OpenApiContractPostProcessor.process(processed) shouldEqual processed
      OpenApiContractPostProcessor.process(Fixture.replace("clientId", "removedClientId")) must
        throwA[IllegalStateException](message = "Expected exactly one clientId query parameter")
      OpenApiContractPostProcessor.process(Fixture.replace("        - name: clientId", "        - name: removedClientId")) must
        throwA[IllegalStateException](message = "Expected exactly one clientId query parameter")
      OpenApiContractPostProcessor.process(Fixture.replace("        - name: format", "        - name: removedFormat")) must
        throwA[IllegalStateException](message = "Expected exactly one format query parameter")
    }

    "publish the authoritative full-pivot export request, binary responses, errors and limits" in {
      val output    = OpenApiContractPostProcessor.process(Fixture)
      val root      = parse(output)
      val operation = root.at(s"/paths/${escape(pivotExportPath)}/post")

      responseStatuses(root, pivotExportPath, "post") shouldEqual requiredPivotExportResponseStatuses

      Seq("projectId" -> pivotProjectIds, "userId" -> pivotUserIds, "clientId" -> pivotClientIds).foreach { case (name, expectedExamples) =>
        val param = parameter(root, pivotExportPath, "post", name)
        param.path("style").asText() shouldEqual "form"
        param.path("explode").asBoolean() should beTrue
        param.at("/schema/type").asText() shouldEqual "array"
        param.at("/schema/items/type").asText() shouldEqual "string"
        param.at("/schema/items/format").asText() shouldEqual "uuid"
        param.at("/schema/example").elements().asScala.map(_.asText()).toSeq shouldEqual expectedExamples
      }

      val format = parameter(root, pivotExportPath, "post", "format")
      format.path("required").asBoolean() should beTrue
      format.at("/schema/type").asText() shouldEqual "string"
      format.at("/schema/enum").elements().asScala.map(_.asText()).toSeq shouldEqual Seq("csv", "xlsx")
      Seq("dateFrom" -> true, "dateTo" -> false).foreach { case (name, required) =>
        val date = parameter(root, pivotExportPath, "post", name)
        date.path("required").asBoolean() shouldEqual required
        date.at("/schema/type").asText() shouldEqual "string"
        date.at("/schema/format").asText() shouldEqual "date"
      }

      operation.at(s"/requestBody/content/application~1json/schema/$refPointerSegment").asText() shouldEqual pivotExportRequestSchemaRef
      val requestMedia = s"/paths/${escape(pivotExportPath)}/post/requestBody/content/application~1json"
      val validator = new WorkUnitPivotExportRequestValidator(
        new WorkUnitPivotQueryRequestValidator(),
        WorkUnitPivotExportRequestValidator.Config(maxDisplayValueCellCount = 10000L)
      )
      val exampleFilters =
        GlobalFilters(WorkUnitDate(LocalDate.parse("2026-08-01")), Some(WorkUnitDate(LocalDate.parse("2026-08-31"))), Seq.empty, Seq.empty, Seq.empty)
      Seq("minimal", "maximal").foreach { name =>
        rawExample(root, requestMedia, name).isObject should beTrue
        val request = parseJson(example(root, requestMedia, name).toString).toOption.get
        request.hcursor.downField("projection").get[String]("effectiveDateTo") shouldEqual Right("2026-08-31")
        validator.validate(request, exampleFilters) must beRight
      }

      val components = root.at("/components/schemas")
      components.at(s"/WorkUnitPivotExportRequest/properties/query/$refPointerSegment").asText() shouldEqual pivotRequestSchemaRef
      components.at(s"/WorkUnitPivotExportRequest/properties/projection/$refPointerSegment").asText() shouldEqual
        "#/components/schemas/WorkUnitPivotExportProjection"
      components.at(s"/WorkUnitPivotExportRequest/properties/presentation/$refPointerSegment").asText() shouldEqual
        pivotExportPresentationSchemaRef
      components.at("/WorkUnitPivotExportRequest/required").elements().asScala.map(_.asText()).toSeq shouldEqual Seq("query", "presentation")
      components.at("/WorkUnitPivotExportProjection/type").asText() shouldEqual "object"
      components.at("/WorkUnitPivotExportProjection/additionalProperties").asBoolean() should beFalse
      components.at("/WorkUnitPivotExportProjection/required").elements().asScala.map(_.asText()).toSeq shouldEqual Seq("effectiveDateTo")
      components.at("/WorkUnitPivotExportProjection/properties/effectiveDateTo/type").asText() shouldEqual "string"
      components.at("/WorkUnitPivotExportProjection/properties/effectiveDateTo/format").asText() shouldEqual "date"
      components.at(s"/WorkUnitPivotExportPresentation/properties/memberLabels/items/$refPointerSegment").asText() shouldEqual
        "#/components/schemas/WorkUnitPivotExportMemberLabel"
      components.at("/WorkUnitPivotExportPresentation/properties/memberLabels/maxItems").asInt() shouldEqual 72000
      components.at("/WorkUnitPivotExportPresentation/properties/memberLabels/description").asText() must contain("maxDisplayValueCellCount x 8")
      components.at(s"/WorkUnitPivotExportMemberLabel/properties/value/$refPointerSegment").asText() shouldEqual
        "#/components/schemas/WorkUnitPivotFieldValue"
      components.at("/WorkUnitPivotExportViolationCode/enum").size() shouldEqual 7

      Seq("text~1csv", "application~1vnd.openxmlformats-officedocument.spreadsheetml.sheet").foreach { mediaType =>
        operation.at(s"/responses/200/content/$mediaType/schema/type").asText() shouldEqual "string"
        operation.at(s"/responses/200/content/$mediaType/schema/format").asText() shouldEqual "binary"
      }
      operation.at("/responses/204/content").isMissingNode should beTrue
      operation.at(s"/responses/400/content/application~1json/schema/oneOf/0/$refPointerSegment").asText() shouldEqual
        invalidConfigurationSchemaRef
      operation.at(s"/responses/400/content/application~1json/schema/oneOf/1/$refPointerSegment").asText() shouldEqual
        invalidExportRequestSchemaRef
      operation.at(s"/responses/400/content/application~1json/schema/oneOf/2/$refPointerSegment").asText() shouldEqual problemDetailsSchemaRef
      Seq("401", "403", "413", "503").foreach { status =>
        operation.at(s"/responses/$status/content/application~1json/schema/$refPointerSegment").asText() shouldEqual problemDetailsSchemaRef
      }
      operation.at(s"/responses/422/content/application~1json/schema/$refPointerSegment").asText() shouldEqual resultTooLargeSchemaRef
      operation.at("/responses/404/content/text~1plain/schema/type").asText() shouldEqual "string"

      val badRequestMedia = s"/paths/${escape(pivotExportPath)}/post/responses/400/content/application~1json"
      Seq("invalidConfiguration", "invalidExportRequest", "malformedRequest").foreach { name =>
        rawExample(root, badRequestMedia, name).isObject should beTrue
      }
      Seq("401", "403", "413", "422", "503").foreach { status =>
        val media = s"/paths/${escape(pivotExportPath)}/post/responses/$status/content/application~1json"
        Seq("minimal", "maximal").foreach(name => rawExample(root, media, name).isObject should beTrue)
      }

      components.has("pl.iterators.epoca.export.services.ExportWorkUnitPivotTableService.ExportWorkUnitPivotTableRequest") should beFalse
      components.has("pl.iterators.epoca.export.services.ExportWorkUnitPivotTableService.ExportWorkUnitPivotTableColumnNames") should beFalse
      operation.toString must not(contain("columnNames"))
      operation.toString must not(contain("pivotRequest"))
      operation.toString must not(contain("KeysRequired"))
      operation.toString must not(contain("ValuesRequired"))
    }

    "fail fast when the production BuildInfo component is absent or is not an object" in {
      val component = s"    $buildInfoSchemaName:\n      type: string\n"

      OpenApiContractPostProcessor.process(Fixture.replace(component, "")) must
        throwA[IllegalStateException](message = s"Missing BuildInfo OpenAPI schema component: $buildInfoSchemaName")
      OpenApiContractPostProcessor.process(Fixture.replace(component, s"    $buildInfoSchemaName: invalid\n")) must
        throwA[IllegalStateException](message = "Expected BuildInfo OpenAPI schema to be an object")
    }

    "fail fast when the pivot response contract is incomplete or contains a non-HTTP status" in {
      val invalid = Fixture.replace(PivotResponses, InvalidPivotResponses)

      OpenApiContractPostProcessor.process(invalid) must throwA[IllegalStateException](message =
        java.util.regex.Pattern.quote(
          "Expected post /organizations/{organizationSlug}/pivot-tables/work-units/query response statuses [200, 400, 401, 403, 404, 422], but found [401, 404, 9457]"
        )
      )
    }

    "publish all seven OAuth management operations with wire schemas and success responses" in {
      val output = OpenApiContractPostProcessor.process(OAuthFixture)
      val root   = parse(output)
      val operations = Seq(
        ("/organizations/{organizationSlug}/oauth-clients", "get", "200", OAuthClientPageSchemaName),
        ("/organizations/{organizationSlug}/oauth-clients", "post", "201", OAuthSecretSchemaName),
        ("/organizations/{organizationSlug}/oauth-clients/{clientId}", "get", "200", OAuthViewSchemaName),
        ("/organizations/{organizationSlug}/oauth-clients/{clientId}", "patch", "200", OAuthViewSchemaName),
        ("/organizations/{organizationSlug}/oauth-clients/{clientId}/audit-events", "get", "200", OAuthAuditPageSchemaName),
        ("/organizations/{organizationSlug}/oauth-clients/{clientId}/revoke", "post", "200", OAuthViewSchemaName),
        ("/organizations/{organizationSlug}/oauth-clients/{clientId}/rotate-secret", "post", "200", OAuthSecretSchemaName)
      )

      operations.foreach { case (path, method, status, schemaName) =>
        val operation = root.at(s"/paths/${escape(path)}/$method")
        operation.isObject should beTrue
        operation.at(s"/responses/$status/content/application~1json/schema/$refPointerSegment").asText() shouldEqual
          s"#/components/schemas/$schemaName"
        operation.path("security") shouldEqual parse("[{ bearerAuth: [] }]")
      }

      val schemas = root.at("/components/schemas")
      schemas.at(s"/${escape(OAuthCreateSchemaName)}/properties/scopes/items/enum").elements().asScala.map(_.asText()).toSet shouldEqual Set(
        "organization:read",
        "users:read",
        "clients:read",
        "projects:read",
        "work-units:read",
        "work-units:write",
        "reports:read",
        "reports:export"
      )
      val projectAccess = schemas.at(s"/${escape(OAuthCreateSchemaName)}/properties/projectAccess")
      projectAccess.path("type").asText() shouldEqual "object"
      projectAccess.path("oneOf").size() shouldEqual 2
      schemas.at(s"/${escape(OAuthViewSchemaName)}/properties/clientSecret").isMissingNode should beTrue
      schemas.at(s"/${escape(OAuthVersionSchemaName)}/type").asText() shouldEqual "object"
      schemas.at(s"/${escape(OAuthVersionSchemaName)}/required/0").asText() shouldEqual "expectedVersion"

      val publicView = schemas.at(s"/${escape(OAuthViewSchemaName)}")
      publicView.path("required").elements().asScala.map(_.asText()).toSet shouldEqual Set(
        "clientId",
        "name",
        "status",
        "scopes",
        "projectAccess",
        "version",
        "policyVersion",
        "secretVersion",
        "serviceAccount",
        "createdAt",
        "createdBy",
        "modifiedAt",
        "modifiedBy",
        "secretRotatedAt",
        "secretRotatedBy",
        "expiresAt",
        "revokedAt",
        "revokedBy",
        "lastUsedAt"
      )
      publicView.at("/properties/status/enum").elements().asScala.map(_.asText()).toSeq shouldEqual Seq("Active", "Expired", "Revoked")
      Seq("expiresAt", "lastUsedAt", "revokedAt").foreach { property =>
        publicView.at(s"/properties/$property/type").asText() shouldEqual "string"
        publicView.at(s"/properties/$property/format").asText() shouldEqual "date-time"
        publicView.at(s"/properties/$property/nullable").asBoolean() should beTrue
      }
      publicView.at("/properties/revokedBy/nullable").asBoolean() should beTrue
      Seq("serviceAccount", "createdBy", "modifiedBy", "secretRotatedBy", "revokedBy").foreach { property =>
        val summary = publicView.at(s"/properties/$property")
        summary.path("required").elements().asScala.map(_.asText()).toSet shouldEqual Set("userId", "displayName")
        Set("email", "login", "roles", "isSuperAdmin", "technicalIdentity").foreach { forbidden =>
          summary.at(s"/properties/$forbidden").isMissingNode should beTrue
        }
      }

      val createExpiration = schemas.at(s"/${escape(OAuthCreateSchemaName)}/properties/expiresAt")
      createExpiration.path("type").asText() shouldEqual "string"
      createExpiration.path("format").asText() shouldEqual "date-time"
      createExpiration.path("nullable").asBoolean() should beTrue
      createExpiration.path("pattern").isMissingNode should beTrue
      createExpiration.path("description").asText() must contain("offset")
      val updateSchema     = schemas.at(s"/${escape(OAuthUpdateSchemaName)}")
      val updateExpiration = updateSchema.at("/properties/expiresAt")
      updateSchema.path("required").elements().asScala.map(_.asText()).toSeq shouldEqual Seq("expectedVersion")
      updateExpiration.path("type").asText() shouldEqual "string"
      updateExpiration.path("format").asText() shouldEqual "date-time"
      updateExpiration.path("nullable").asBoolean() should beTrue
      updateExpiration.path("pattern").isMissingNode should beTrue
      updateExpiration.path("description").asText() must contain("Omit")
      updateExpiration.path("description").asText() must contain("null")
      updateExpiration.path("description").asText() must contain("offset")
      publicView.at("/properties/expiresAt/pattern").asText() shouldEqual "^.*Z$"

      val statusParameter = parameter(root, "/organizations/{organizationSlug}/oauth-clients", "get", "status")
      statusParameter.at("/schema/enum").elements().asScala.map(_.asText()).toSeq shouldEqual Seq("Active", "Expired", "Revoked")
      root.path(OAuthPostprocessedMarker).asBoolean() should beTrue
      output.linesIterator.count(_.startsWith(s"$OAuthPostprocessedMarker:")) shouldEqual 1

      val auditItem = schemas.at(s"/${escape(OAuthAuditPageSchemaName)}/properties/results/items")
      auditItem.path("required").elements().asScala.map(_.asText()).toSet should contain(allOf("actorUserId", "actor", "metadata"))
      auditItem.at("/properties/actor/nullable").asBoolean() should beTrue
      auditItem.at("/properties/actor/required").elements().asScala.map(_.asText()).toSet shouldEqual Set("userId", "displayName")
      Set("email", "login", "roles", "isSuperAdmin", "technicalIdentity").foreach { forbidden =>
        auditItem.at(s"/properties/actor/properties/$forbidden").isMissingNode should beTrue
      }
      val auditMetadata = auditItem.at("/properties/metadata")
      auditMetadata.path("additionalProperties").asBoolean() should beFalse
      Seq("beforeExpiresAt", "afterExpiresAt").foreach { property =>
        auditMetadata.at(s"/properties/$property/format").asText() shouldEqual "date-time"
        auditMetadata.at(s"/properties/$property/nullable").asBoolean() should beTrue
      }
      val auditExample = root.at(
        s"/paths/${escape("/organizations/{organizationSlug}/oauth-clients/{clientId}/audit-events")}/get/responses/200/content/application~1json/examples/maximal/value/results/0"
      )
      auditExample.path("actor").fieldNames().asScala.toSet shouldEqual Set("userId", "displayName")
      auditExample.at("/metadata/beforeExpiresAt").isNull should beTrue
      auditExample.at("/metadata/afterExpiresAt").asText() shouldEqual "2026-12-31T22:59:00Z"
      auditExample.path("metadata").fieldNames().asScala.toSet shouldEqual Set("beforeExpiresAt", "afterExpiresAt")

      val invalidExpiration = root.at(
        s"/paths/${escape("/organizations/{organizationSlug}/oauth-clients")}/post/responses/400/content/application~1json/examples/invalidExpiration/value/type"
      )
      invalidExpiration.asText() shouldEqual "result:oauth-client:invalid-expiration"
      val patchConflict = root.at(
        s"/paths/${escape("/organizations/{organizationSlug}/oauth-clients/{clientId}")}/patch/responses/409/content/application~1json/examples"
      )
      patchConflict.path("staleVersion").at("/value/type").asText() shouldEqual "result:oauth-client:stale-version"
      patchConflict.path("notActive").at("/value/type").asText() shouldEqual "result:oauth-client:not-active"
      patchConflict.path("nameConflict").at("/value/type").asText() shouldEqual "result:oauth-client:name-conflict"
      val createConflict = root.at(
        s"/paths/${escape("/organizations/{organizationSlug}/oauth-clients")}/post/responses/409/content/application~1json/examples/nameConflict/value/type"
      )
      createConflict.asText() shouldEqual "result:oauth-client:name-conflict"
      schemas
        .at(s"/${escape("OAuthClientProblemDetails")}/properties/type/enum")
        .elements()
        .asScala
        .map(_.asText())
        .toSet should contain("result:oauth-client:name-conflict")

      val ordinarySuccessSchemas = operations.collect {
        case (path, method, _, schema) if schema != OAuthSecretSchemaName =>
          root.at(s"/paths/${escape(path)}/$method/responses").toString -> schema
      }
      ordinarySuccessSchemas.map(_._2).toSet should not(contain(OAuthSecretSchemaName))
      schemas.at(s"/${escape(OAuthSecretSchemaName)}/required").elements().asScala.map(_.asText()).toSet shouldEqual Set("client", "clientSecret")
      val oneTimeSecret = schemas.at(s"/${escape(OAuthSecretSchemaName)}/properties/clientSecret")
      oneTimeSecret.path("readOnly").asBoolean() should beTrue
      oneTimeSecret.path("writeOnly").isMissingNode should beTrue
      Set("secretHash", "accessToken", "authorization").foreach { forbidden =>
        publicView.at(s"/properties/$forbidden").isMissingNode should beTrue
        auditItem.at(s"/properties/$forbidden").isMissingNode should beTrue
      }
      success
    }

    "publish request policy schemas that accept exactly the runtime-valid shapes" in {
      val root      = parse(OpenApiContractPostProcessor.process(OAuthFixture))
      val schemas   = root.at("/components/schemas")
      val create    = schemas.path(OAuthCreateSchemaName)
      val patch     = schemas.path(OAuthUpdateSchemaName)
      val projectId = "11111111-1111-4111-8111-111111111111"

      Seq(create, patch).foreach { requestSchema =>
        requestSchema.at("/properties/scopes/minItems").asInt() shouldEqual 1
      }
      val createProjectAccess = create.at("/properties/projectAccess")
      val patchProjectAccess  = patch.at("/properties/projectAccess")
      createProjectAccess.path("type").asText() shouldEqual "object"
      createProjectAccess.path("nullable").isMissingNode should beTrue
      createProjectAccess.path("oneOf").size() shouldEqual 2
      patchProjectAccess.path("type").isMissingNode should beTrue
      patchProjectAccess.path("nullable").isMissingNode should beTrue
      patchProjectAccess.path("oneOf").size() shouldEqual 3

      val nullProjectAccess = patchProjectAccess.at("/oneOf/0")
      nullProjectAccess.fieldNames().asScala.toSet shouldEqual Set("type", "nullable", "enum")
      nullProjectAccess.path("type").asText() shouldEqual "object"
      nullProjectAccess.path("nullable").asBoolean() should beTrue
      nullProjectAccess.path("enum").size() shouldEqual 1
      nullProjectAccess.at("/enum/0").isNull should beTrue

      Seq(createProjectAccess -> (0, 1), patchProjectAccess -> (1, 2)).foreach { case (projectAccess, (allIndex, selectedIndex)) =>
        projectAccess.at(s"/oneOf/$allIndex/properties/mode/enum/0").asText() shouldEqual "AllProjects"
        projectAccess.at(s"/oneOf/$allIndex/not/required/0").asText() shouldEqual "projectIds"
        projectAccess.at(s"/oneOf/$allIndex/additionalProperties").isMissingNode should beTrue
        projectAccess.at(s"/oneOf/$selectedIndex/properties/mode/enum/0").asText() shouldEqual "SelectedProjects"
        projectAccess.at(s"/oneOf/$selectedIndex/required").elements().asScala.map(_.asText()).toSet shouldEqual
          Set("mode", "projectIds")
        projectAccess.at(s"/oneOf/$selectedIndex/properties/projectIds/minItems").asInt() shouldEqual 1
        projectAccess.at(s"/oneOf/$selectedIndex/properties/projectIds/items/format").asText() shouldEqual "uuid"
      }
      patch.at("/properties/scopes/nullable").asBoolean() should beTrue

      val allProjects      = parse("""{"mode":"AllProjects","ignoredByRuntime":"allowed"}""")
      val selectedProjects = parse(s"""{"mode":"SelectedProjects","projectIds":["$projectId"]}""")
      val invalidProjectAccess = Seq(
        parse(s"""{"mode":"AllProjects","projectIds":["$projectId"]}"""),
        parse("""{"mode":"SelectedProjects"}"""),
        parse("""{"mode":"SelectedProjects","projectIds":[]}"""),
        parse("""{"mode":"SelectedProjects","projectIds":["not-a-uuid"]}""")
      )
      val nullValue = parse("null")
      matchingOneOfAlternatives(createProjectAccess, nullValue) shouldEqual 0
      matchingOneOfAlternatives(createProjectAccess, allProjects) shouldEqual 1
      matchingOneOfAlternatives(createProjectAccess, selectedProjects) shouldEqual 1
      matchingOneOfAlternatives(patchProjectAccess, nullValue) shouldEqual 1
      matchingOneOfAlternatives(patchProjectAccess, allProjects) shouldEqual 1
      matchingOneOfAlternatives(patchProjectAccess, selectedProjects) shouldEqual 1
      invalidProjectAccess.foreach { value =>
        matchingOneOfAlternatives(createProjectAccess, value) shouldEqual 0
        matchingOneOfAlternatives(patchProjectAccess, value) shouldEqual 0
      }

      val createCases = Seq(
        "AllProjects" -> parse("""{"name":"valid","scopes":["projects:read"],"projectAccess":{"mode":"AllProjects","ignoredByRuntime":"allowed"}}"""),
        "SelectedProjects" -> parse(
          s"""{"name":"valid","scopes":["projects:read"],"projectAccess":{"mode":"SelectedProjects","projectIds":["$projectId"]}}"""
        ),
        "empty scopes" -> parse("""{"name":"invalid","scopes":[],"projectAccess":{"mode":"AllProjects"}}"""),
        "AllProjects with projectIds" -> parse(
          s"""{"name":"invalid","scopes":["projects:read"],"projectAccess":{"mode":"AllProjects","projectIds":["$projectId"]}}"""
        ),
        "SelectedProjects without projectIds" -> parse(
          """{"name":"invalid","scopes":["projects:read"],"projectAccess":{"mode":"SelectedProjects"}}"""
        ),
        "SelectedProjects with empty projectIds" -> parse(
          """{"name":"invalid","scopes":["projects:read"],"projectAccess":{"mode":"SelectedProjects","projectIds":[]}}"""
        ),
        "SelectedProjects with invalid UUID" -> parse(
          """{"name":"invalid","scopes":["projects:read"],"projectAccess":{"mode":"SelectedProjects","projectIds":["not-a-uuid"]}}"""
        )
      )
      createCases.map { case (name, request) => name -> schemaAccepts(create, request) }.toMap shouldEqual Map(
        "AllProjects"                            -> true,
        "SelectedProjects"                       -> true,
        "empty scopes"                           -> false,
        "AllProjects with projectIds"            -> false,
        "SelectedProjects without projectIds"    -> false,
        "SelectedProjects with empty projectIds" -> false,
        "SelectedProjects with invalid UUID"     -> false
      )

      val patchCases = Seq(
        "AllProjects" -> parse(
          """{"expectedVersion":4,"scopes":["projects:read"],"projectAccess":{"mode":"AllProjects","ignoredByRuntime":"allowed"}}"""
        ),
        "SelectedProjects" -> parse(
          s"""{"expectedVersion":4,"scopes":["projects:read"],"projectAccess":{"mode":"SelectedProjects","projectIds":["$projectId"]}}"""
        ),
        "nullable omitted policy" -> parse("""{"expectedVersion":4,"scopes":null,"projectAccess":null}"""),
        "empty scopes"            -> parse("""{"expectedVersion":4,"scopes":[],"projectAccess":{"mode":"AllProjects"}}"""),
        "AllProjects with projectIds" -> parse(
          s"""{"expectedVersion":4,"scopes":["projects:read"],"projectAccess":{"mode":"AllProjects","projectIds":["$projectId"]}}"""
        ),
        "SelectedProjects without projectIds" -> parse(
          """{"expectedVersion":4,"scopes":["projects:read"],"projectAccess":{"mode":"SelectedProjects"}}"""
        ),
        "SelectedProjects with empty projectIds" -> parse(
          """{"expectedVersion":4,"scopes":["projects:read"],"projectAccess":{"mode":"SelectedProjects","projectIds":[]}}"""
        ),
        "SelectedProjects with invalid UUID" -> parse(
          """{"expectedVersion":4,"scopes":["projects:read"],"projectAccess":{"mode":"SelectedProjects","projectIds":["not-a-uuid"]}}"""
        )
      )
      patchCases.map { case (name, request) => name -> schemaAccepts(patch, request) }.toMap shouldEqual Map(
        "AllProjects"                            -> true,
        "SelectedProjects"                       -> true,
        "nullable omitted policy"                -> true,
        "empty scopes"                           -> false,
        "AllProjects with projectIds"            -> false,
        "SelectedProjects without projectIds"    -> false,
        "SelectedProjects with empty projectIds" -> false,
        "SelectedProjects with invalid UUID"     -> false
      )
    }

    "publish lifecycle-consistent examples independently for every success operation" in {
      val root       = parse(OpenApiContractPostProcessor.process(OAuthFixture))
      val collection = "/organizations/{organizationSlug}/oauth-clients"
      val client     = s"$collection/{clientId}"
      val listClient = oauthResponseExample(root, collection, "get", "200").at("/results/0")
      val created    = oauthResponseExample(root, collection, "post", "201")
      val fetched    = oauthResponseExample(root, client, "get", "200")
      val patched    = oauthResponseExample(root, client, "patch", "200")
      val revoked    = oauthResponseExample(root, s"$client/revoke", "post", "200")
      val rotated    = oauthResponseExample(root, s"$client/rotate-secret", "post", "200")

      listClient.path("status").asText() shouldEqual "Expired"
      fetched.path("status").asText() shouldEqual "Expired"
      created.at("/client/status").asText() shouldEqual "Active"
      created.at("/client/expiresAt").asText() shouldEqual "2026-12-31T22:59:00Z"
      created.at("/client/version").asLong() shouldEqual 1L
      created.at("/client/policyVersion").asLong() shouldEqual 1L
      created.at("/client/secretVersion").asLong() shouldEqual 1L
      patched.path("status").asText() shouldEqual "Active"
      patched.path("expiresAt").asText() shouldEqual "2026-12-31T22:59:00Z"
      patched.path("version").asLong() shouldEqual 5L
      patched.path("policyVersion").asLong() shouldEqual 4L
      rotated.at("/client/status").asText() shouldEqual "Active"
      rotated.at("/client/secretVersion").asLong() shouldEqual 3L
      rotated.at("/client/secretRotatedAt").asText() shouldEqual "2026-08-20T09:00:00Z"
      revoked.path("status").asText() shouldEqual "Revoked"
      revoked.path("revokedAt").asText() shouldEqual "2026-08-20T09:30:00Z"
      revoked.path("revokedBy").fieldNames().asScala.toSet shouldEqual Set("userId", "displayName")
      created.path("clientSecret").asText() shouldEqual "returned-once-after-create"
      rotated.path("clientSecret").asText() shouldEqual "returned-once-after-rotation"
      Seq(listClient, fetched, patched, revoked).foreach(_.path("clientSecret").isMissingNode should beTrue)
      success
    }

    "reject every non-exact OAuth bearer security requirement before normalization" in {
      val operation = s"/paths/${escape("/organizations/{organizationSlug}/oauth-clients")}/get"
      val expectedMessage =
        "Expected get /organizations/{organizationSlug}/oauth-clients security to equal exactly one bearerAuth requirement with empty scopes"
      val invalidSecurity = Seq(
        "anonymous"             -> parse("[{}]"),
        "anonymous alternative" -> parse("[{}, { bearerAuth: [] }]"),
        "extra scheme"          -> parse("[{ bearerAuth: [], apiKey: [] }]"),
        "non-empty scopes"      -> parse("[{ bearerAuth: [oauth-admin] }]"),
        "wrong-typed scopes"    -> parse("[{ bearerAuth: oauth-admin }]")
      )
      val rawMessages = invalidSecurity.map { case (name, security) =>
        val fixture = oauthFixtureReplacing(s"$operation/security", security)
        name -> scala.util.Try(OpenApiContractPostProcessor.process(fixture)).failed.toOption.map(_.getMessage)
      }.toMap

      rawMessages shouldEqual invalidSecurity.map(_._1 -> Some(expectedMessage)).toMap

      val published = OpenApiContractPostProcessor.process(OAuthFixture)
      val publishedWithAnonymousAlternative = fixtureMutated(published) { root =>
        root.at(operation).asInstanceOf[ObjectNode].set[JsonNode]("security", parse("[{}, { bearerAuth: [] }]"))
        ()
      }
      scala.util.Try(OpenApiContractPostProcessor.process(publishedWithAnonymousAlternative)).failed.toOption.map(_.getMessage) shouldEqual
        Some(expectedMessage)
    }

    "reject raw OAuth list status parameter drift before normalization" in {
      val listOperation = s"/paths/${escape("/organizations/{organizationSlug}/oauth-clients")}/get"
      val statusSchema  = s"$listOperation/parameters/0/schema"

      OpenApiContractPostProcessor.process(oauthFixtureWithout(listOperation, "parameters")) must
        throwA[IllegalStateException](message = "Expected exactly one raw Baklava OAuth list status parameter, but found none")
      OpenApiContractPostProcessor.process(oauthFixtureWithDuplicatedStatusParameter) must
        throwA[IllegalStateException](message = "Expected exactly one raw Baklava OAuth list status parameter, but found 2")
      OpenApiContractPostProcessor.process(oauthFixtureWithField(s"$listOperation/parameters/0", "in", parse("'header'"))) must
        throwA[IllegalStateException](message = "Expected raw Baklava OAuth list status parameter in to be query, but found header")
      OpenApiContractPostProcessor.process(oauthFixtureWithField(s"$listOperation/parameters/0", "required", parse("true"))) must
        throwA[IllegalStateException](message = "Expected raw Baklava OAuth list status parameter required to be false")
      OpenApiContractPostProcessor.process(oauthFixtureWithField(statusSchema, "type", parse("'integer'"))) must
        throwA[IllegalStateException](message = "Expected raw Baklava OAuth list status parameter schema type to be string, but found integer")
      OpenApiContractPostProcessor.process(
        oauthFixtureReplacing(statusSchema, parse(s"{ '$refPointerSegment': '#/components/schemas/WrongStatus' }"))
      ) must throwA[IllegalStateException](message =
        java.util.regex.Pattern.quote("Expected raw Baklava OAuth list status parameter schema to define an inline string type without $ref")
      )
    }

    "use an explicit marker for raw preflight and idempotent published verification" in {
      val viewSchema = s"/components/schemas/${escape(OAuthViewSchemaName)}"
      val rawWithClosedView = oauthFixtureMutated { root =>
        root.at(viewSchema).asInstanceOf[ObjectNode].put("additionalProperties", false)
        ()
      }
      val processed = OpenApiContractPostProcessor.process(rawWithClosedView)
      val root      = parse(processed)

      root.path(OAuthPostprocessedMarker).isBoolean should beTrue
      root.path(OAuthPostprocessedMarker).asBoolean() should beTrue
      root.fieldNames().asScala.count(_ == OAuthPostprocessedMarker) shouldEqual 1
      processed.linesIterator.count(_.startsWith(s"$OAuthPostprocessedMarker:")) shouldEqual 1
      OpenApiContractPostProcessor.process(processed) shouldEqual processed

      val publishedWithStatusDrift = fixtureMutated(processed) { published =>
        published
          .at(s"/paths/${escape("/organizations/{organizationSlug}/oauth-clients")}/get/parameters/0/schema/enum")
          .asInstanceOf[ArrayNode]
          .remove(2)
        ()
      }
      OpenApiContractPostProcessor.process(publishedWithStatusDrift) must
        throwA[IllegalStateException](message = "Published OAuth list status parameter drifted before idempotent normalization")

      Seq(parse("false"), parse("'true'")).foreach { invalidMarker =>
        val invalidlyMarkedRaw = oauthFixtureMutated { raw =>
          raw.at(viewSchema).asInstanceOf[ObjectNode].put("additionalProperties", false)
          raw.set[JsonNode](OAuthPostprocessedMarker, invalidMarker)
          ()
        }
        parse(OpenApiContractPostProcessor.process(invalidlyMarkedRaw)).path(OAuthPostprocessedMarker).asBoolean() should beTrue
      }

      val malformedClosedRaw = oauthFixtureMutated { raw =>
        val view = raw.at(viewSchema).asInstanceOf[ObjectNode]
        view.put("additionalProperties", false)
        view.path("properties").asInstanceOf[ObjectNode].remove("clientId")
        ()
      }
      val failure = scala.util.Try(OpenApiContractPostProcessor.process(malformedClosedRaw)).failed.get
      failure.getMessage must contain("raw Baklava OAuth client view properties fields")
      failure.getMessage must contain("clientId")
      failure.getMessage must not(contain("Published OAuth schemas drifted"))
    }

    "reject drift in every raw Baklava OAuth shape before deterministic normalization" in {
      val rawViewProperties = s"/components/schemas/${escape(OAuthViewSchemaName)}/properties"
      val rawClientPage     = s"/components/schemas/${escape(OAuthClientPageSchemaName)}"
      val rawAuditItem      = s"/components/schemas/${escape(OAuthAuditPageSchemaName)}/properties/results/items/properties"
      val rawSecret         = s"/components/schemas/${escape(OAuthSecretSchemaName)}/properties"
      val rawCreate         = s"/components/schemas/${escape(OAuthCreateSchemaName)}/properties"
      val rawVersion        = s"/components/schemas/${escape(OAuthVersionSchemaName)}/properties"
      val rawUpdate         = s"/components/schemas/${escape(OAuthUpdateSchemaName)}"
      val createOperation   = s"/paths/${escape("/organizations/{organizationSlug}/oauth-clients")}/post/requestBody/content/application~1json/schema"

      val drifts = Seq(
        "view field removed"       -> oauthFixtureWithout(rawViewProperties, "clientId"),
        "list wrapper renamed"     -> oauthFixtureWithout(rawClientPage, "properties"),
        "audit field removed"      -> oauthFixtureWithout(rawAuditItem, "eventType"),
        "one-time field removed"   -> oauthFixtureWithout(rawSecret, "clientSecret"),
        "create field removed"     -> oauthFixtureWithout(rawCreate, "name"),
        "version field removed"    -> oauthFixtureWithout(rawVersion, "expectedVersion"),
        "PATCH limitation changed" -> oauthFixtureWithField(rawUpdate, "properties", parse("{ unexpected: { type: string } }")),
        "request ref changed"      -> oauthFixtureWithField(createOperation, refPointerSegment, parse("'#/components/schemas/WrongCreate'"))
      )

      drifts.map { case (name, fixture) =>
        name -> scala.util.Try(OpenApiContractPostProcessor.process(fixture)).failed.toOption.exists(_.isInstanceOf[IllegalStateException])
      }.toMap shouldEqual drifts.map(_._1 -> true).toMap
    }

    "fail fast when the pivot export response contract is incomplete or contains a non-HTTP status" in {
      val invalid = Fixture.replace(PivotExportResponses, InvalidPivotExportResponses)

      OpenApiContractPostProcessor.process(invalid) must throwA[IllegalStateException](message =
        java.util.regex.Pattern.quote(
          "Expected post /organizations/{organizationSlug}/pivot-tables/work-units/export response statuses [200, 204, 400, 401, 403, 404, 413, 422, 503], but found [200, 204, 401, 404, 999]"
        )
      )
    }

    "fail fast on dangling local component references after all normalizations" in {
      val dangling = Fixture.replace(
        "    pl.iterators.epoca.Unrelated:\n      type: object",
        "    pl.iterators.epoca.Unrelated:\n      $ref: '#/components/schemas/RemovedComponent'"
      )

      OpenApiContractPostProcessor.process(dangling) must throwA[IllegalStateException](message =
        java.util.regex.Pattern
          .quote("Dangling local OpenAPI reference #/components/schemas/RemovedComponent at /components/schemas/pl.iterators.epoca.Unrelated")
      )
    }
  }

  private val OAuthCreateSchemaName  = "pl.iterators.epoca.oauth.services.OAuthClientManagementService.OAuthClientCreateRequest"
  private val OAuthUpdateSchemaName  = "pl.iterators.epoca.oauth.services.OAuthClientManagementService.OAuthClientUpdateRequest"
  private val OAuthVersionSchemaName = "pl.iterators.epoca.oauth.services.OAuthClientManagementService.OAuthClientVersionRequest"
  private val OAuthViewSchemaName    = "pl.iterators.epoca.oauth.services.OAuthClientManagementService.OAuthClientView"
  private val OAuthSecretSchemaName  = "pl.iterators.epoca.oauth.services.OAuthClientManagementService.OAuthClientSecretView"
  private val OAuthClientPageSchemaName =
    "pl.iterators.epoca.utils.db.PaginatedResult_pl.iterators.epoca.oauth.services.OAuthClientManagementService.OAuthClientView"
  private val OAuthAuditPageSchemaName =
    "pl.iterators.epoca.utils.db.PaginatedResult_pl.iterators.epoca.oauth.services.OAuthClientManagementService.OAuthAuditEventView"
  private val OAuthPostprocessedMarker = "x-epoca-oauth-admin-contract-postprocessed"

  private def oauthResponseExample(
    root: JsonNode,
    path: String,
    method: String,
    status: String
  ): JsonNode = example(root, s"/paths/${escape(path)}/$method/responses/$status/content/application~1json", "maximal")

  private def schemaAccepts(schema: JsonNode, value: JsonNode): Boolean = {
    val expectedType = Option(schema.get("type")).filter(_.isTextual).map(_.asText())
    val typeMatches =
      if (value.isNull) expectedType.isEmpty || schema.path("nullable").asBoolean(false)
      else
        expectedType.forall {
          case "object"  => value.isObject
          case "array"   => value.isArray
          case "string"  => value.isTextual
          case "integer" => value.isIntegralNumber
          case "number"  => value.isNumber
          case _         => false
        }
    val enumMatches = Option(schema.get("enum"))
      .filter(_.isArray)
      .forall(_.elements().asScala.exists(_ == value))
    val requiredMatches = Option(schema.get("required"))
      .filter(_.isArray)
      .forall(required => !value.isObject || required.elements().asScala.forall(field => value.has(field.asText())))
    val propertiesMatch =
      if (!value.isObject) true
      else
        Option(schema.get("properties"))
          .filter(_.isObject)
          .forall(properties =>
            value.properties().asScala.forall(entry => !properties.has(entry.getKey) || schemaAccepts(properties.path(entry.getKey), entry.getValue))
          )
    val minItemsMatches =
      !value.isArray || !schema.path("minItems").isIntegralNumber || value.size() >= schema.path("minItems").asInt()
    val uniqueItemsMatch =
      !value.isArray || !schema.path("uniqueItems").asBoolean(false) || value.elements().asScala.toSeq.distinct.size == value.size()
    val itemsMatch =
      !value.isArray || !schema.has("items") || value.elements().asScala.forall(item => schemaAccepts(schema.path("items"), item))
    val formatMatches =
      !value.isTextual || !schema.path("format").isTextual || schema.path("format").asText() != "uuid" ||
        scala.util.Try(java.util.UUID.fromString(value.asText())).toOption.exists(_.toString == value.asText())
    val oneOfMatches = Option(schema.get("oneOf"))
      .filter(_.isArray)
      .forall(alternatives => alternatives.elements().asScala.count(alternative => schemaAccepts(alternative, value)) == 1)
    val notMatches = Option(schema.get("not")).forall(forbidden => !schemaAccepts(forbidden, value))

    typeMatches && enumMatches && requiredMatches && propertiesMatch && minItemsMatches && uniqueItemsMatch && itemsMatch &&
    formatMatches && oneOfMatches && notMatches
  }

  private def matchingOneOfAlternatives(schema: JsonNode, value: JsonNode): Int =
    schema.path("oneOf").elements().asScala.count(alternative => schemaAccepts(alternative, value))

  private def fixtureMutated(input: String)(mutate: ObjectNode => Unit): String = {
    val root = parse(input).deepCopy[ObjectNode]()
    mutate(root)
    Yaml.pretty().writeValueAsString(root)
  }

  private def oauthFixtureMutated(mutate: ObjectNode => Unit): String = fixtureMutated(OAuthFixture)(mutate)

  private def oauthFixtureWithout(pointer: String, field: String): String = {
    val root = parse(OAuthFixture).deepCopy[ObjectNode]()
    root.at(pointer).asInstanceOf[ObjectNode].remove(field)
    Yaml.pretty().writeValueAsString(root)
  }

  private def oauthFixtureWithField(
    pointer: String,
    field: String,
    value: JsonNode
  ): String = {
    val root = parse(OAuthFixture).deepCopy[ObjectNode]()
    root.at(pointer).asInstanceOf[ObjectNode].set[JsonNode](field, value)
    Yaml.pretty().writeValueAsString(root)
  }

  private def oauthFixtureReplacing(pointer: String, value: JsonNode): String =
    oauthFixtureMutated { root =>
      val parentPointer = pointer.substring(0, pointer.lastIndexOf('/'))
      val field         = pointer.substring(pointer.lastIndexOf('/') + 1)
      root.at(parentPointer).asInstanceOf[ObjectNode].set[JsonNode](field, value)
      ()
    }

  private def oauthFixtureWithDuplicatedStatusParameter: String =
    oauthFixtureMutated { root =>
      val parameters = root
        .at(s"/paths/${escape("/organizations/{organizationSlug}/oauth-clients")}/get/parameters")
        .asInstanceOf[ArrayNode]
      parameters.add(parameters.get(0).deepCopy[JsonNode]())
      ()
    }

  private val PivotResponses: String =
    """      responses:
      |        "200": { description: Ok }
      |        "400": { description: BadRequest }
      |        "401": { description: Unauthorized }
      |        "403": { description: Forbidden }
      |        "404": { description: NotFound }
      |        "422": { description: UnprocessableEntity }
      |""".stripMargin

  private val InvalidPivotResponses: String =
    """      responses:
      |        "401": { description: Unauthorized }
      |        "404": { description: NotFound }
      |        "9457": { description: RFC problem }
      |""".stripMargin

  private val RawOAuthSummaryJson =
    """{"required":["displayName","userId"],"type":"object","properties":{"userId":{"type":"string","format":"uuid"},"displayName":{"type":"string"}}}"""
  private val RawOAuthProjectAccessJson =
    """{"required":["mode"],"type":"object","properties":{"mode":{"type":"string","enum":["AllProjects","SelectedProjects"]},"projectIds":{"type":"array","items":{"type":"string","format":"uuid"}}}}"""
  private val RawOAuthClientViewJson =
    s"""{"required":["clientId","createdAt","createdBy","modifiedAt","modifiedBy","name","policyVersion","projectAccess","scopes","secretRotatedAt","secretRotatedBy","secretVersion","serviceAccount","status","version"],"type":"object","properties":{"clientId":{"type":"string"},"name":{"type":"string"},"status":{"type":"string","enum":["Active","Expired","Revoked"]},"scopes":{"type":"array","items":{"type":"string","enum":["users:read","projects:read","reports:read","work-units:read","work-units:write","clients:read","organization:read","reports:export"]}},"projectAccess":$RawOAuthProjectAccessJson,"version":{"type":"number"},"policyVersion":{"type":"number"},"secretVersion":{"type":"number"},"serviceAccount":$RawOAuthSummaryJson,"createdAt":{"type":"string","format":"date-time"},"createdBy":$RawOAuthSummaryJson,"modifiedAt":{"type":"string","format":"date-time"},"modifiedBy":$RawOAuthSummaryJson,"secretRotatedAt":{"type":"string","format":"date-time"},"secretRotatedBy":$RawOAuthSummaryJson,"expiresAt":{"type":"string","format":"date-time"},"revokedAt":{"type":"string","format":"date-time"},"revokedBy":$RawOAuthSummaryJson,"lastUsedAt":{"type":"string","format":"date-time"}}}"""
  private val RawOAuthAuditEventJson =
    s"""{"required":["actorType","eventType","id","metadata","occurredAt","outcome"],"type":"object","properties":{"id":{"type":"string","format":"uuid"},"occurredAt":{"type":"string","format":"date-time"},"actorType":{"type":"string","enum":["Anonymous","Human","ServiceAccount","System"]},"actorUserId":{"type":"string","format":"uuid"},"actor":$RawOAuthSummaryJson,"eventType":{"type":"string","enum":["RateLimit","Update","InvalidScope","TokenIssued","Revoke","Rotate","InvalidClient","Create"]},"outcome":{"type":"string","enum":["Failure","Success"]},"metadata":{"type":"object"}}}"""
  private val OAuthRawComponentsJson =
    s"""{
      "$OAuthClientPageSchemaName":{"required":["count","results"],"type":"object","properties":{"count":{"type":"integer","format":"int32"},"results":{"type":"array","items":$RawOAuthClientViewJson}}},
      "$OAuthCreateSchemaName":{"required":["name","projectAccess","scopes"],"type":"object","properties":{"name":{"type":"string"},"scopes":{"type":"array","items":{"type":"string","enum":["users:read","projects:read","reports:read","work-units:read","work-units:write","clients:read","organization:read","reports:export"]}},"projectAccess":$RawOAuthProjectAccessJson,"expiresAt":{"type":"string","format":"date-time"}}},
      "$OAuthSecretSchemaName":{"required":["client","clientSecret"],"type":"object","properties":{"client":$RawOAuthClientViewJson,"clientSecret":{"type":"string"}}},
      "$OAuthViewSchemaName":$RawOAuthClientViewJson,
      "$OAuthUpdateSchemaName":{"type":"object"},
      "$OAuthAuditPageSchemaName":{"required":["count","results"],"type":"object","properties":{"count":{"type":"integer","format":"int32"},"results":{"type":"array","items":$RawOAuthAuditEventJson}}},
      "$OAuthVersionSchemaName":{"required":["expectedVersion"],"type":"object","properties":{"expectedVersion":{"type":"number"}}}
    }"""
  private val OAuthRawPathsJson =
    s"""{
      "/organizations/{organizationSlug}/oauth-clients":{
        "get":{"parameters":[{"name":"status","in":"query","required":false,"schema":{"type":"string","example":"Active"}}],"responses":{"200":{"content":{"application/json":{"schema":{"$refPointerSegment":"#/components/schemas/$OAuthClientPageSchemaName"}}}}},"security":[{"bearerAuth":[]}]},
        "post":{"requestBody":{"required":true,"content":{"application/json":{"schema":{"$refPointerSegment":"#/components/schemas/$OAuthCreateSchemaName"}}}},"responses":{"201":{"content":{"application/json":{"schema":{"$refPointerSegment":"#/components/schemas/$OAuthSecretSchemaName"}}}}},"security":[{"bearerAuth":[]}]}
      },
      "/organizations/{organizationSlug}/oauth-clients/{clientId}":{
        "get":{"responses":{"200":{"content":{"application/json":{"schema":{"$refPointerSegment":"#/components/schemas/$OAuthViewSchemaName"}}}}},"security":[{"bearerAuth":[]}]},
        "patch":{"requestBody":{"required":true,"content":{"application/json":{"schema":{"$refPointerSegment":"#/components/schemas/$OAuthUpdateSchemaName"}}}},"responses":{"200":{"content":{"application/json":{"schema":{"$refPointerSegment":"#/components/schemas/$OAuthViewSchemaName"}}}}},"security":[{"bearerAuth":[]}]}
      },
      "/organizations/{organizationSlug}/oauth-clients/{clientId}/audit-events":{"get":{"responses":{"200":{"content":{"application/json":{"schema":{"$refPointerSegment":"#/components/schemas/$OAuthAuditPageSchemaName"}}}}},"security":[{"bearerAuth":[]}]}},
      "/organizations/{organizationSlug}/oauth-clients/{clientId}/revoke":{"post":{"requestBody":{"required":true,"content":{"application/json":{"schema":{"$refPointerSegment":"#/components/schemas/$OAuthVersionSchemaName"}}}},"responses":{"200":{"content":{"application/json":{"schema":{"$refPointerSegment":"#/components/schemas/$OAuthViewSchemaName"}}}}},"security":[{"bearerAuth":[]}]}},
      "/organizations/{organizationSlug}/oauth-clients/{clientId}/rotate-secret":{"post":{"requestBody":{"required":true,"content":{"application/json":{"schema":{"$refPointerSegment":"#/components/schemas/$OAuthVersionSchemaName"}}}},"responses":{"200":{"content":{"application/json":{"schema":{"$refPointerSegment":"#/components/schemas/$OAuthSecretSchemaName"}}}}},"security":[{"bearerAuth":[]}]}}
    }"""

  private lazy val OAuthFixture: String = {
    val root       = parse(Fixture).deepCopy[ObjectNode]()
    val schemas    = root.at("/components/schemas").asInstanceOf[ObjectNode]
    val rawSchemas = parse(OAuthRawComponentsJson)
    rawSchemas.properties().asScala.foreach(entry => schemas.set[JsonNode](entry.getKey, entry.getValue))
    val paths    = root.path("paths").asInstanceOf[ObjectNode]
    val rawPaths = parse(OAuthRawPathsJson)
    rawPaths.properties().asScala.foreach(entry => paths.set[JsonNode](entry.getKey, entry.getValue))
    Yaml.pretty().writeValueAsString(root)
  }

  private val PivotExportResponses: String =
    """      responses:
      |        "200": { description: Ok }
      |        "204": { description: NoContent }
      |        "400": { description: BadRequest }
      |        "401": { description: Unauthorized }
      |        "403": { description: Forbidden }
      |        "404": { description: NotFound }
      |        "413": { description: PayloadTooLarge }
      |        "422": { description: UnprocessableEntity }
      |        "503": { description: ServiceUnavailable }
      |""".stripMargin

  private val InvalidPivotExportResponses: String =
    """      responses:
      |        "200": { description: Ok }
      |        "204": { description: NoContent }
      |        "401": { description: Unauthorized }
      |        "404": { description: NotFound }
      |        "999": { description: Invalid }
      |""".stripMargin

  private val Fixture: String =
    s"""openapi: 3.0.1
       |components:
       |  schemas:
       |    $buildInfoSchemaName:
       |      type: string
       |    pl.iterators.epoca.Unrelated:
       |      type: object
       |    pl.iterators.epoca.stats.domain.WorkUnitPivotQuery.Request:
       |      type: object
       |    pl.iterators.epoca.stats.domain.WorkUnitPivotQuery.Response:
       |      type: object
       |    pl.iterators.epoca.export.services.ExportWorkUnitPivotTableService.ExportWorkUnitPivotTableRequest:
       |      type: object
       |      properties:
       |        columnNames: { $$ref: '#/components/schemas/pl.iterators.epoca.export.services.ExportWorkUnitPivotTableService.ExportWorkUnitPivotTableColumnNames' }
       |        pivotRequest: { type: object }
       |    pl.iterators.epoca.export.services.ExportWorkUnitPivotTableService.ExportWorkUnitPivotTableColumnNames:
       |      type: object
       |paths:
       |  /build-info:
       |    get:
       |      responses:
       |        "200":
       |          content:
       |            application/json:
       |              schema:
       |                $$ref: '${OpenApiContractPostProcessor.BuildInfoSchemaRef}'
       |              examples:
       |                minimal: { value: '{}' }
       |                maximal: { value: '{}' }
       |  $reportPath:
       |    get:
       |      parameters:
       |        - name: projectId
       |          in: query
       |          schema: { type: string, example: random }
       |        - name: clientId
       |          in: query
       |          schema: { type: string, example: random }
       |  $pivotPath:
       |    post:
       |      parameters:
       |        - name: projectId
       |          in: query
       |          schema: { type: string, example: random }
       |        - name: userId
       |          in: query
       |          schema: { type: string, example: random }
       |        - name: clientId
       |          in: query
       |          schema: { type: string, example: random }
       |        - name: dateFrom
       |          in: query
       |          required: true
       |          schema: { type: string, example: 2026-08-01 }
       |        - name: dateTo
       |          in: query
       |          required: false
       |          schema: { type: string, example: 2026-08-31 }
       |      requestBody:
       |        content:
       |          application/json:
       |            schema:
       |              $$ref: '#/components/schemas/pl.iterators.epoca.stats.domain.WorkUnitPivotQuery.Request'
       |            examples:
       |              minimal: { value: '{"rows":[],"columns":[],"values":[],"filters":[],"options":{"rowSubtotals":true,"columnSubtotals":false,"grandTotals":true}}' }
       |              maximal: { value: '{"rows":["DateKey"],"columns":["EntryTypeKey"],"values":["DurationSum","DurationSum"],"filters":[{"key":"EntryTypeKey","includedValues":[null]}],"options":{"rowSubtotals":false,"columnSubtotals":false,"grandTotals":true}}' }
       |$PivotResponses
       |  $pivotExportPath:
       |    post:
       |      parameters:
       |        - name: format
       |          in: query
       |          required: true
       |          schema: { type: string, example: Csv }
       |        - name: projectId
       |          in: query
       |          schema: { type: string, example: random }
       |        - name: userId
       |          in: query
       |          schema: { type: string, example: random }
       |        - name: clientId
       |          in: query
       |          schema: { type: string, example: random }
       |        - name: dateFrom
       |          in: query
       |          required: true
       |          schema: { type: string, example: 2026-08-01 }
       |        - name: dateTo
       |          in: query
       |          required: false
       |          schema: { type: string, example: 2026-08-31 }
       |      requestBody:
       |        content:
       |          application/json:
       |            schema:
       |              $$ref: '#/components/schemas/pl.iterators.epoca.export.services.ExportWorkUnitPivotTableService.ExportWorkUnitPivotTableRequest'
       |$PivotExportResponses
       |  $currentPath:
       |    get:
       |      responses:
       |        "200":
       |          content:
       |            application/json:
       |              examples:
       |                minimal: { value: '{}' }
       |                maximal: { value: '{}' }
       |    put:
       |      requestBody:
       |        content:
       |          application/json:
       |            examples:
       |              minimal: { value: '{}' }
       |              maximal: { value: '{}' }
       |      responses:
       |        "200":
       |          content:
       |            application/json:
       |              examples:
       |                minimal: { value: '{}' }
       |                maximal: { value: '{}' }
       |    delete:
       |      responses:
       |        "200":
       |          content:
       |            application/json:
       |              examples:
       |                minimal: { value: '{}' }
       |                maximal: { value: '{}' }
       |  $currentPath/undo:
       |    post:
       |      requestBody:
       |        content:
       |          application/json:
       |            schema: { type: object }
       |      security: [{ humanBearer: [] }]
       |      responses:
       |        "200": { description: Ok, content: { application/json: { schema: { type: object } } } }
       |        "400": { description: BadRequest }
       |        "401": { description: Unauthorized }
       |        "403": { description: Forbidden }
       |        "404": { description: NotFound }
       |        "409": { description: Conflict }
       |  $historyPath:
       |    get:
       |      responses:
       |        "200":
       |          content:
       |            application/json:
       |              examples:
       |                minimal: { value: '{}' }
       |                maximal: { value: '{}' }
       |""".stripMargin
}
