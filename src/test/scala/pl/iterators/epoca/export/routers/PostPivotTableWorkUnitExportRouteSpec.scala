package pl.iterators.epoca.`export`.routers

import cats.effect.IO
import com.typesafe.config.ConfigFactory
import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax._
import org.apache.pekko.http.scaladsl.model.headers.`Content-Disposition`
import org.apache.pekko.http.scaladsl.model.{ContentType, ContentTypes, HttpEntity, HttpMethods, HttpRequest, MediaTypes, StatusCode, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.util.ByteString
import pl.iterators.baklava.core.model.{RouteParameterRepresentation, RouteRepresentation, RouteSecurity}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.client.domain.ClientDomain.ClientId
import pl.iterators.epoca.export.ExportModule
import pl.iterators.epoca.export.domain.SpreadsheetDomain.{SpreadsheetFormat, SpreadsheetName}
import pl.iterators.epoca.export.domain.WorkUnitPivotExport.{ExportViolation, ExportViolationCode, InvalidExportRequest}
import pl.iterators.epoca.export.domain.{PivotExportArtifact, SpreadsheetDomain, WorkUnitPivotExport}
import pl.iterators.epoca.export.services.ExportWorkUnitPivotTableService
import pl.iterators.epoca.export.services.ExportWorkUnitPivotTableService.ExportResult
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.project.domain.ProjectDomain.ProjectId
import pl.iterators.epoca.stats.domain.StatsDomain.{WorkUnitPivotTableKey, WorkUnitPivotTableValue}
import pl.iterators.epoca.stats.domain.WorkUnitPivotQuery.{GlobalFilters, InvalidConfigurationError, ResultTooLargeError, Violation, ViolationCode}
import pl.iterators.epoca.stats.domain.WorkUnitPivotTableRow
import pl.iterators.epoca.stats.services.WorkUnitPivotTableService.{WorkUnitPivotTableRequest, WorkUnitPivotTableResult}
import pl.iterators.epoca.stats.services.{WorkUnitPivotQueryService, WorkUnitPivotTableService}
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.utils.RouteDocSpec
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.WorkUnitDate
import pl.iterators.epoca.workunit.repositories.WorkUnitRepository.WorkUnitFilters

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.time.{LocalDate, YearMonth}
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Promise}

class PostPivotTableWorkUnitExportRouteSpec extends RouteDocSpec {
  private val correlationId = "ct-534-pivot-export-route"
  private val auth          = allGenerators[AuthContext].normal.generate
  private val orgSlug       = OrganizationSlug("pivot-export-route")
  private val dateFrom      = WorkUnitDate(LocalDate.parse("2026-08-01"))
  private val dateTo        = WorkUnitDate(LocalDate.parse("2026-08-31"))
  private val projectIds =
    Seq(ProjectId(UUID.fromString("11111111-1111-4111-8111-111111111111")), ProjectId(UUID.fromString("22222222-2222-4222-8222-222222222222")))
  private val userIds =
    Seq(UserId(UUID.fromString("33333333-3333-4333-8333-333333333333")), UserId(UUID.fromString("44444444-4444-4444-8444-444444444444")))
  private val clientIds =
    Seq(ClientId(UUID.fromString("55555555-5555-4555-8555-555555555555")), ClientId(UUID.fromString("66666666-6666-4666-8666-666666666666")))

  private val rawRequest = Json.obj(
    "query" -> Json.obj(
      "rows"    -> Json.arr(Json.fromString("UserKey")),
      "columns" -> Json.arr(Json.fromString("MonthYearKey")),
      "values"  -> Json.arr(Json.fromString("DurationSum"), Json.fromString("EntriesCount")),
      "filters" -> Json.arr(),
      "options" -> Json.obj("rowSubtotals" -> Json.True, "columnSubtotals" -> Json.False, "grandTotals" -> Json.True)
    ),
    "projection" -> Json.obj("effectiveDateTo" -> Json.fromString(dateTo.toString)),
    "presentation" -> Json.obj(
      "fieldLabels" -> Json.obj(
        "UserKey"      -> Json.fromString("User"),
        "ProjectKey"   -> Json.fromString("Project"),
        "ClientKey"    -> Json.fromString("Client"),
        "YearKey"      -> Json.fromString("Year"),
        "MonthYearKey" -> Json.fromString("Month"),
        "DateKey"      -> Json.fromString("Date"),
        "EntryTypeKey" -> Json.fromString("Entry type"),
        "TaskKey"      -> Json.fromString("Task")
      ),
      "measureLabels" -> Json.obj(
        "EntriesCount" -> Json.fromString("Entries"),
        "DurationSum"  -> Json.fromString("Total duration"),
        "DurationAvg"  -> Json.fromString("Average duration")
      ),
      "subtotalLabel"   -> Json.fromString("Subtotal"),
      "grandTotalLabel" -> Json.fromString("Grand total"),
      "noClientLabel"   -> Json.fromString("No client"),
      "noTaskLabel"     -> Json.fromString("No task"),
      "memberLabels" -> Json.arr(
        Json.obj("key" -> Json.fromString("UserKey"), "value" -> Json.fromString(userIds.head.toString), "label" -> Json.fromString("Alice")),
        Json.obj(
          "key"   -> Json.fromString("MonthYearKey"),
          "value" -> Json.fromString(YearMonth.of(2026, 8).toString),
          "label" -> Json.fromString("August 2026")
        )
      )
    )
  )

  override val routeRepresentation: RouteRepresentation[Json, Unit] =
    RouteRepresentation[Json, Unit](
      "Export the complete configured work-unit pivot table as XLSX or CSV.",
      "POST",
      "/organizations/{organizationSlug}/pivot-tables/work-units/export",
      List(
        RouteParameterRepresentation[SpreadsheetFormat]("format", true, SpreadsheetFormat.Csv, SpreadsheetFormat.extension),
        RouteParameterRepresentation[LocalDate]("dateFrom", true, dateFrom, _.toString),
        RouteParameterRepresentation[LocalDate]("dateTo", false, dateTo, _.toString),
        RouteParameterRepresentation[Seq[UUID]]("projectId", false, projectIds.map(id => id: UUID), repeatable("projectId")),
        RouteParameterRepresentation[Seq[UUID]]("userId", false, userIds.map(id => id: UUID), repeatable("userId")),
        RouteParameterRepresentation[Seq[UUID]]("clientId", false, clientIds.map(id => id: UUID), repeatable("clientId"))
      ),
      requestPredefinedValue = Some(rawRequest),
      authentication = List(RouteSecurity.Bearer())
    )

  private val basePath     = routeRepresentation.path.replace("{organizationSlug}", orgSlug)
  private val requiredPath = s"$basePath?format=csv&dateFrom=$dateFrom"
  private val fullPath =
    s"$basePath?format=xlsx&dateFrom=$dateFrom&dateTo=$dateTo${repeated("projectId", projectIds.map(id => id: UUID))}" +
      repeated("userId", userIds.map(id => id: UUID)) + repeated("clientId", clientIds.map(id => id: UUID))

  private def repeatable(name: String)(values: Seq[UUID]): String =
    values.map(_.toString).mkString(s"&$name=")

  private def repeated(name: String, values: Seq[UUID]): String = values.map(value => s"&$name=$value").mkString

  private def exactProblem(
    status: StatusCode,
    typeTag: String,
    title: String
  ): Json =
    Json.obj(
      "type"     -> Json.fromString(typeTag),
      "status"   -> Json.fromInt(status.intValue),
      "title"    -> Json.fromString(title),
      "instance" -> Json.fromString(s"cid:$correlationId")
    )

  trait NoLoggedTestCase extends BaseScope {
    override lazy val authContextForTest: Option[AuthContext] = None
  }

  trait LoggedTestCase extends BaseScope {
    override lazy val exportWorkUnitPivotTableService: ExportWorkUnitPivotTableService = vmock[ExportWorkUnitPivotTableService]
    override lazy val workUnitPivotTableService: WorkUnitPivotTableService             = vmock[WorkUnitPivotTableService]
    override val authContextForTest: Option[AuthContext]                               = Some(auth)
  }

  routeRepresentation.name should {
    "document the required format/date and repeated global filter contract" in {
      routeRepresentation.parameters.map(parameter => parameter.name -> parameter.required) shouldEqual List(
        "format"    -> true,
        "dateFrom"  -> true,
        "dateTo"    -> false,
        "projectId" -> false,
        "userId"    -> false,
        "clientId"  -> false
      )
      routeRepresentation.parameters.head.marshall shouldEqual "csv"
    }

    "return the shared Unauthorized response for an unauthenticated request" in new NoLoggedTestCase {
      TestRequest(requiredPath, requestJson(rawRequest.noSpaces)) ~> addHeader("device-id", "ct-534-test-device") ~>
        addHeader("Correlation-Id", correlationId) ~> allRoutes ~> check {
          response.status shouldEqual StatusCodes.Unauthorized
          responseAs[Json] shouldEqual Json.obj(
            "type"     -> Json.fromString("authentication-failed"),
            "status"   -> Json.fromInt(StatusCodes.Unauthorized.intValue),
            "title"    -> Json.fromString("Could not authorize"),
            "detail"   -> Json.fromString("The resource requires authentication, which was not supplied with the request"),
            "instance" -> Json.fromString(s"cid:$correlationId")
          )
        }
    }

    "pass exact raw JSON, auth, slug, format and ordered global filters to the service" in new LoggedTestCase {
      val filters = GlobalFilters(dateFrom, Some(dateTo), projectIds.reverse, userIds.reverse, clientIds.reverse)
      when(exportWorkUnitPivotTableService.`export`(eqTo(auth), eqTo(orgSlug), eqTo(rawRequest), any[GlobalFilters], eqTo(SpreadsheetFormat.Xlsx)))
        .thenReturn(io(ExportResult.NoContent))

      TestRequest(fullPath, requestJson(rawRequest.noSpaces)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NoContent
      }

      verify(exportWorkUnitPivotTableService).`export`(auth, orgSlug, rawRequest, filters, SpreadsheetFormat.Xlsx)
    }

    "return Ok with a closed CSV artifact, exact metadata, length and bytes, then discard it" in new LoggedTestCase {
      val bytes    = "\ufeffUser,Total duration\r\nAlice,05:00\r\n".getBytes(UTF_8)
      val artifact = temporaryArtifact(bytes, SpreadsheetName("pivot_table_2026-08-01_2026-08-31.csv"), SpreadsheetFormat.Csv)
      val filters  = GlobalFilters(dateFrom, None, Seq.empty, Seq.empty, Seq.empty)
      when(exportWorkUnitPivotTableService.`export`(auth, orgSlug, rawRequest, filters, SpreadsheetFormat.Csv))
        .thenReturn(io(ExportResult.Ok(artifact)))

      TestRequest(requiredPath, requestJson(rawRequest.noSpaces)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.OK
        response.entity.contentType shouldEqual ContentTypes.`text/csv(UTF-8)`
        response.entity.contentLengthOption shouldEqual Some(bytes.length.toLong)
        response.header[`Content-Disposition`].map(_.value()) shouldEqual
          Some("attachment; filename=\"pivot_table_2026-08-01_2026-08-31.csv\"")
        val body = Await.result(response.entity.dataBytes.runWith(Sink.fold(ByteString.empty)(_ ++ _)), 3.seconds)
        body.toArray.toSeq shouldEqual bytes.toSeq
      }
      eventuallyDeleted(artifact.path) should beTrue

      verify(exportWorkUnitPivotTableService).`export`(auth, orgSlug, rawRequest, filters, SpreadsheetFormat.Csv)
    }

    "serve an XLSX artifact with the exact MIME type" in new LoggedTestCase {
      val bytes    = Array[Byte](0x50, 0x4b, 0x03, 0x04)
      val artifact = temporaryArtifact(bytes, SpreadsheetName("pivot_table_2026-08-01_open.xlsx"), SpreadsheetFormat.Xlsx)
      val filters  = GlobalFilters(dateFrom, None, Seq.empty, Seq.empty, Seq.empty)
      when(exportWorkUnitPivotTableService.`export`(auth, orgSlug, rawRequest, filters, SpreadsheetFormat.Xlsx))
        .thenReturn(io(ExportResult.Ok(artifact)))

      TestRequest(s"$basePath?format=xlsx&dateFrom=$dateFrom", requestJson(rawRequest.noSpaces)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.OK
        response.entity.contentType shouldEqual ContentType(MediaTypes.`application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`)
        val body = Await.result(response.entity.dataBytes.runWith(Sink.fold(ByteString.empty)(_ ++ _)), 3.seconds)
        body.toArray.toSeq shouldEqual bytes.toSeq
      }
      eventuallyDeleted(artifact.path) should beTrue

      verify(exportWorkUnitPivotTableService).`export`(auth, orgSlug, rawRequest, filters, SpreadsheetFormat.Xlsx)
    }

    "return an exact empty NoContent response without attachment metadata" in new LoggedTestCase {
      val filters = GlobalFilters(dateFrom, None, Seq.empty, Seq.empty, Seq.empty)
      when(exportWorkUnitPivotTableService.`export`(auth, orgSlug, rawRequest, filters, SpreadsheetFormat.Csv))
        .thenReturn(io(ExportResult.NoContent))

      TestRequest(requiredPath, requestJson(rawRequest.noSpaces)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NoContent
        response.entity.isKnownEmpty should beTrue
        response.header[`Content-Disposition`] should beNone
      }

      verify(exportWorkUnitPivotTableService).`export`(auth, orgSlug, rawRequest, filters, SpreadsheetFormat.Csv)
    }

    "return BadRequest with exact query and export validation errors" in new LoggedTestCase {
      val filters              = GlobalFilters(dateFrom, None, Seq.empty, Seq.empty, Seq.empty)
      val invalidConfiguration = InvalidConfigurationError(violations = Seq(Violation("values", ViolationCode.EMPTY_VALUES)))
      val invalidExport =
        InvalidExportRequest(violations = Seq(ExportViolation("presentation.subtotalLabel", ExportViolationCode.INVALID_PRESENTATION_VALUE)))
      val cases = Seq[(ExportResult, Json)](
        ExportResult.InvalidConfiguration(invalidConfiguration) -> invalidConfiguration.asJson,
        ExportResult.InvalidExportRequest(invalidExport)        -> invalidExport.asJson
      )

      cases.foreach { case (result, body) =>
        when(exportWorkUnitPivotTableService.`export`(auth, orgSlug, rawRequest, filters, SpreadsheetFormat.Csv))
          .thenReturn(io(result))
        TestRequest(requiredPath, requestJson(rawRequest.noSpaces)) ~> allRoutes ~> check {
          response.status shouldEqual StatusCodes.BadRequest
          responseAs[Json] shouldEqual body
        }
      }

      verify(exportWorkUnitPivotTableService, times(cases.size))
        .`export`(auth, orgSlug, rawRequest, filters, SpreadsheetFormat.Csv)
    }

    "return 422 UnprocessableEntity with the exact result-too-large error" in new LoggedTestCase {
      val filters  = GlobalFilters(dateFrom, None, Seq.empty, Seq.empty, Seq.empty)
      val tooLarge = ResultTooLargeError(estimatedDisplayValueCellCount = 10001L, maxDisplayValueCellCount = 10000L)
      when(exportWorkUnitPivotTableService.`export`(auth, orgSlug, rawRequest, filters, SpreadsheetFormat.Csv))
        .thenReturn(io(ExportResult.TooLarge(tooLarge)))

      TestRequest(requiredPath, requestJson(rawRequest.noSpaces)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.UnprocessableContent
        responseAs[Json] shouldEqual tooLarge.asJson
      }

      verify(exportWorkUnitPivotTableService).`export`(auth, orgSlug, rawRequest, filters, SpreadsheetFormat.Csv)
    }

    "return NotFound for absent and inaccessible organizations with the existing exact text body" in new LoggedTestCase {
      val filters = GlobalFilters(dateFrom, None, Seq.empty, Seq.empty, Seq.empty)
      Seq(ExportResult.OrganizationNotFound, ExportResult.NoAccessToOrganization).foreach { result =>
        when(exportWorkUnitPivotTableService.`export`(auth, orgSlug, rawRequest, filters, SpreadsheetFormat.Csv))
          .thenReturn(io(result))
        TestRequest(requiredPath, requestJson(rawRequest.noSpaces)) ~> allRoutes ~> check {
          response.status shouldEqual StatusCodes.NotFound
          response.entity.contentType shouldEqual ContentTypes.`text/plain(UTF-8)`
          response.entity.asInstanceOf[HttpEntity.Strict].data.utf8String shouldEqual
            "The requested resource could not be found but may be available again in the future."
        }
      }

      verify(exportWorkUnitPivotTableService, times(2)).`export`(auth, orgSlug, rawRequest, filters, SpreadsheetFormat.Csv)
    }

    "return Forbidden with the exact shared Problem Details response for an insufficient role" in new LoggedTestCase {
      val filters = GlobalFilters(dateFrom, None, Seq.empty, Seq.empty, Seq.empty)
      when(exportWorkUnitPivotTableService.`export`(auth, orgSlug, rawRequest, filters, SpreadsheetFormat.Csv))
        .thenReturn(io(ExportResult.InsufficientRole))

      TestRequest(requiredPath, requestJson(rawRequest.noSpaces)) ~> addHeader("Correlation-Id", correlationId) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        responseAs[Json] shouldEqual exactProblem(
          StatusCodes.Forbidden,
          "result:work-unit-pivot-query-service:result",
          "User's role doesn't allow to access pivot table"
        )
      }

      verify(exportWorkUnitPivotTableService).`export`(auth, orgSlug, rawRequest, filters, SpreadsheetFormat.Csv)
    }

    "return BadRequest Problem Details for malformed JSON and a non-object root" in new LoggedTestCase {
      Seq("{" -> "exhausted input", "[]" -> "Expected a JSON object at the request root").foreach { case (body, detail) =>
        TestRequest(requiredPath, requestJson(body)) ~> addHeader("Correlation-Id", correlationId) ~> allRoutes ~> check {
          response.status shouldEqual StatusCodes.BadRequest
          responseAs[Json] shouldEqual Json.obj(
            "type"     -> Json.fromString("malformed-request-content"),
            "status"   -> Json.fromInt(StatusCodes.BadRequest.intValue),
            "title"    -> Json.fromString("The request content was malformed"),
            "detail"   -> Json.fromString(detail),
            "instance" -> Json.fromString(s"cid:$correlationId")
          )
        }
      }
    }

    "return BadRequest Problem Details for missing and unsupported required query parameters" in new LoggedTestCase {
      Seq("format" -> s"$basePath?dateFrom=$dateFrom", "dateFrom" -> s"$basePath?format=csv").foreach { case (name, path) =>
        TestRequest(path, requestJson(rawRequest.noSpaces)) ~> addHeader("device-id", "ct-534-test-device") ~>
          addHeader("Correlation-Id", correlationId) ~> allRoutes ~> check {
            response.status shouldEqual StatusCodes.BadRequest
            responseAs[Json] shouldEqual Json.obj(
              "type"      -> Json.fromString("missing-query-param"),
              "status"    -> Json.fromInt(StatusCodes.BadRequest.intValue),
              "title"     -> Json.fromString("The request is missing a required query parameter"),
              "detail"    -> Json.fromString(name),
              "instance"  -> Json.fromString(s"cid:$correlationId"),
              "extension" -> Json.obj("queryParamName" -> Json.fromString(name))
            )
          }
      }

      TestRequest(s"$basePath?format=pdf&dateFrom=$dateFrom", requestJson(rawRequest.noSpaces)) ~>
        addHeader("device-id", "ct-534-test-device") ~> addHeader("Correlation-Id", correlationId) ~> allRoutes ~> check {
          response.status shouldEqual StatusCodes.BadRequest
          val problem = responseAs[Json]
          problem.hcursor.get[String]("type") shouldEqual Right("malformed-query-param")
          problem.hcursor.get[Int]("status") shouldEqual Right(StatusCodes.BadRequest.intValue)
          problem.hcursor.get[String]("title") shouldEqual Right("The query parameter was malformed")
          problem.hcursor.get[String]("instance") shouldEqual Right(s"cid:$correlationId")
          problem.hcursor.downField("extension").get[String]("queryParamName") shouldEqual Right("format")
        }
    }

    "return 413 PayloadTooLarge before JSON materialization or service invocation" in new LoggedTestCase {
      val oversizedEntity =
        HttpEntity.Default(ContentTypes.`application/json`, WorkUnitPivotExport.Limits.requestEntityMaxBytes + 1L, Source.single(ByteString("{}")))
      val request = HttpRequest(HttpMethods.POST, requiredPath, entity = oversizedEntity)

      request ~> addHeader("Correlation-Id", correlationId) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.ContentTooLarge
        val problem = responseAs[Json]
        problem.hcursor.get[String]("type") shouldEqual Right("malformed-request-content")
        problem.hcursor.get[Int]("status") shouldEqual Right(StatusCodes.ContentTooLarge.intValue)
        problem.hcursor.get[String]("title") shouldEqual Right("The request content was malformed")
        problem.hcursor.get[String]("instance") shouldEqual Right(s"cid:$correlationId")
      }
    }

    "return 413 PayloadTooLarge for a chunked entity and recover its admission permit" in new LoggedTestCase {
      val reusableChunk = ByteString(new Array[Byte](8 * 1024 * 1024))
      val oversized     = HttpEntity.Chunked.fromData(ContentTypes.`application/json`, Source.repeat(reusableChunk).take(9))

      HttpRequest(HttpMethods.POST, requiredPath, entity = oversized) ~> addHeader("Correlation-Id", correlationId) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.ContentTooLarge
      }

      val filters = GlobalFilters(dateFrom, None, Seq.empty, Seq.empty, Seq.empty)
      when(exportWorkUnitPivotTableService.`export`(auth, orgSlug, rawRequest, filters, SpreadsheetFormat.Csv))
        .thenReturn(io(ExportResult.NoContent))
      TestRequest(requiredPath, requestJson(rawRequest.noSpaces)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NoContent
      }

      verify(exportWorkUnitPivotTableService).`export`(auth, orgSlug, rawRequest, filters, SpreadsheetFormat.Csv)
    }

    "return 503 ServiceUnavailable when admission is exhausted and recover without queueing" in new LoggedTestCase {
      val filters = GlobalFilters(dateFrom, None, Seq.empty, Seq.empty, Seq.empty)
      val gate    = Promise[ExportResult]()
      when(exportWorkUnitPivotTableService.`export`(auth, orgSlug, rawRequest, filters, SpreadsheetFormat.Csv))
        .thenReturn(IO.fromFuture(IO.pure(gate.future)))
      val handler = Route.toFunction(allRoutes)

      val firstMaterializations    = new AtomicInteger(0)
      val secondMaterializations   = new AtomicInteger(0)
      val rejectedMaterializations = new AtomicInteger(0)
      val first                    = handler(trackedRequest(firstMaterializations))
      val second                   = handler(trackedRequest(secondMaterializations))
      awaitCount(firstMaterializations, 1)
      awaitCount(secondMaterializations, 1)

      val rejected = Await.result(handler(trackedRequest(rejectedMaterializations)), 3.seconds)
      val rejectedJson = parse(Await.result(rejected.entity.toStrict(3.seconds), 3.seconds).data.utf8String)
        .getOrElse(sys.error("invalid overload Problem Details"))

      (rejected.status shouldEqual StatusCodes.ServiceUnavailable) and
        (rejectedJson shouldEqual exactProblem(
          StatusCodes.ServiceUnavailable,
          "result:pivot-export-overloaded",
          "Pivot export capacity is exhausted"
        )) and
        (rejectedMaterializations.get() shouldEqual 0)

      gate.trySuccess(ExportResult.NoContent) should beTrue
      Await.result(first, 3.seconds).status shouldEqual StatusCodes.NoContent
      Await.result(second, 3.seconds).status shouldEqual StatusCodes.NoContent

      val recoveredMaterializations = new AtomicInteger(0)
      Await.result(handler(trackedRequest(recoveredMaterializations)), 3.seconds).status shouldEqual StatusCodes.NoContent
      recoveredMaterializations.get() shouldEqual 1

      verify(exportWorkUnitPivotTableService, times(3)).`export`(auth, orgSlug, rawRequest, filters, SpreadsheetFormat.Csv)
    }

    "keep admission slots leased until active artifact downloads terminate" in new LoggedTestCase {
      val filters = GlobalFilters(dateFrom, None, Seq.empty, Seq.empty, Seq.empty)
      val firstArtifact =
        temporaryArtifact("first-download".getBytes(UTF_8), SpreadsheetName("pivot_table_2026-08-01_open.csv"), SpreadsheetFormat.Csv)
      val secondArtifact =
        temporaryArtifact("second-download".getBytes(UTF_8), SpreadsheetName("pivot_table_2026-08-01_open.csv"), SpreadsheetFormat.Csv)
      when(exportWorkUnitPivotTableService.`export`(auth, orgSlug, rawRequest, filters, SpreadsheetFormat.Csv))
        .thenReturn(io(ExportResult.Ok(firstArtifact)), io(ExportResult.Ok(secondArtifact)), io(ExportResult.NoContent))
      val handler = Route.toFunction(allRoutes)

      val firstResponse  = Await.result(handler(trackedRequest(new AtomicInteger(0))), 3.seconds)
      val secondResponse = Await.result(handler(trackedRequest(new AtomicInteger(0))), 3.seconds)
      firstResponse.status shouldEqual StatusCodes.OK
      secondResponse.status shouldEqual StatusCodes.OK

      val rejectedMaterializations = new AtomicInteger(0)
      Await.result(handler(trackedRequest(rejectedMaterializations)), 3.seconds).status shouldEqual StatusCodes.ServiceUnavailable
      rejectedMaterializations.get() shouldEqual 0

      Await.result(firstResponse.entity.toStrict(3.seconds), 3.seconds).data.toArray shouldEqual "first-download".getBytes(UTF_8)
      eventuallyDeleted(firstArtifact.path) should beTrue

      val recoveredMaterializations = new AtomicInteger(0)
      awaitNonOverloadedResponse(handler, recoveredMaterializations).status shouldEqual StatusCodes.NoContent
      recoveredMaterializations.get() shouldEqual 1

      Await.result(secondResponse.entity.toStrict(3.seconds), 3.seconds).data.toArray shouldEqual "second-download".getBytes(UTF_8)
      eventuallyDeleted(secondArtifact.path) should beTrue
      verify(exportWorkUnitPivotTableService, times(3)).`export`(auth, orgSlug, rawRequest, filters, SpreadsheetFormat.Csv)
    }

    "release admission slots when unmaterialized artifacts expire" in new LoggedTestCase {
      val filters = GlobalFilters(dateFrom, None, Seq.empty, Seq.empty, Seq.empty)
      val firstArtifact =
        temporaryArtifact("first-expiring".getBytes(UTF_8), SpreadsheetName("pivot_table_2026-08-01_open.csv"), SpreadsheetFormat.Csv, 10.seconds)
      val secondArtifact =
        temporaryArtifact("second-expiring".getBytes(UTF_8), SpreadsheetName("pivot_table_2026-08-01_open.csv"), SpreadsheetFormat.Csv, 10.seconds)
      when(exportWorkUnitPivotTableService.`export`(auth, orgSlug, rawRequest, filters, SpreadsheetFormat.Csv))
        .thenReturn(io(ExportResult.Ok(firstArtifact)), io(ExportResult.Ok(secondArtifact)), io(ExportResult.NoContent))
      val handler = Route.toFunction(allRoutes)

      Await.result(handler(trackedRequest(new AtomicInteger(0))), 3.seconds).status shouldEqual StatusCodes.OK
      Await.result(handler(trackedRequest(new AtomicInteger(0))), 3.seconds).status shouldEqual StatusCodes.OK

      val rejectedMaterializations = new AtomicInteger(0)
      Await.result(handler(trackedRequest(rejectedMaterializations)), 3.seconds).status shouldEqual StatusCodes.ServiceUnavailable
      rejectedMaterializations.get() shouldEqual 0

      eventuallyDeleted(firstArtifact.path, 12.seconds) should beTrue
      eventuallyDeleted(secondArtifact.path, 12.seconds) should beTrue

      val recoveredMaterializations = new AtomicInteger(0)
      awaitNonOverloadedResponse(handler, recoveredMaterializations).status shouldEqual StatusCodes.NoContent
      recoveredMaterializations.get() shouldEqual 1
      verify(exportWorkUnitPivotTableService, times(3)).`export`(auth, orgSlug, rawRequest, filters, SpreadsheetFormat.Csv)
    }

    "support a trailing slash, reject other methods and keep the legacy pivot route unchanged" in new LoggedTestCase {
      val filters = GlobalFilters(dateFrom, None, Seq.empty, Seq.empty, Seq.empty)
      when(exportWorkUnitPivotTableService.`export`(auth, orgSlug, rawRequest, filters, SpreadsheetFormat.Csv))
        .thenReturn(io(ExportResult.NoContent))

      TestRequest(s"$basePath/?format=csv&dateFrom=$dateFrom", requestJson(rawRequest.noSpaces)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NoContent
      }
      HttpRequest(HttpMethods.GET, requiredPath).withHeaders(
        org.apache.pekko.http.scaladsl.model.headers.RawHeader("device-id", "ct-534-test-device")
      ) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      val legacyRequest = WorkUnitPivotTableRequest(Seq(WorkUnitPivotTableKey.UserKey), Seq(WorkUnitPivotTableValue.EntriesCount))
      val legacyRows    = Seq.empty[WorkUnitPivotTableRow]
      when(workUnitPivotTableService.buildPivotTable(auth, orgSlug, legacyRequest, WorkUnitFilters()))
        .thenReturn(io(WorkUnitPivotTableResult.Ok(legacyRows)))
      TestRequest(basePath.stripSuffix("/export"), requestJson(legacyRequest.asJson.noSpaces)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.OK
        responseAs[Json] shouldEqual legacyRows.asJson
      }

      verify(exportWorkUnitPivotTableService).`export`(auth, orgSlug, rawRequest, filters, SpreadsheetFormat.Csv)
      verify(workUnitPivotTableService).buildPivotTable(auth, orgSlug, legacyRequest, WorkUnitFilters())
    }

    "discard an artifact if response mapping fails before materialization" in new LoggedTestCase {
      val artifact =
        temporaryArtifact(Array[Byte](1), SpreadsheetName("pivot_table_2026-08-01_open.csv"), null.asInstanceOf[SpreadsheetDomain.SpreadsheetFormat])
      val filters = GlobalFilters(dateFrom, None, Seq.empty, Seq.empty, Seq.empty)
      when(exportWorkUnitPivotTableService.`export`(auth, orgSlug, rawRequest, filters, SpreadsheetFormat.Csv))
        .thenReturn(io(ExportResult.Ok(artifact)))

      TestRequest(requiredPath, requestJson(rawRequest.noSpaces)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.InternalServerError
      }
      Files.exists(artifact.path) should beFalse

      verify(exportWorkUnitPivotTableService).`export`(auth, orgSlug, rawRequest, filters, SpreadsheetFormat.Csv)
    }
  }

  "ExportModule.PivotConfig" should {
    "load the application defaults and preserve the reviewed request-size budget" in {
      val applicationConfig        = ConfigFactory.load()
      val maxDisplayValueCellCount = WorkUnitPivotQueryService.Config(applicationConfig).maxDisplayValueCellCount
      val loaded                   = ExportModule.PivotConfig(applicationConfig, maxDisplayValueCellCount)

      loaded.tempDirectory shouldEqual Paths.get("/tmp/epoca-pivot-export")
      loaded.requestMaxBytes shouldEqual WorkUnitPivotExport.Limits.requestEntityMaxBytes
      loaded.unmaterializedArtifactTimeout shouldEqual 30.seconds
      loaded.streamIdleTimeout shouldEqual 2.minutes
      loaded.maxConcurrentRequests shouldEqual 2
      WorkUnitPivotExport.Limits.canonicalBodyBudget(maxDisplayValueCellCount) must be_<=(loaded.requestMaxBytes)
    }

    "reject a changed transport limit or a cell limit whose canonical body budget no longer fits" in {
      val base = ConfigFactory.parseString("""
          |export.pivot.tempDirectory = "/tmp/epoca-pivot-export"
          |export.pivot.requestMaxBytes = 67108864
          |export.pivot.unmaterializedArtifactTimeout = 30s
          |export.pivot.streamIdleTimeout = 2m
          |export.pivot.maxConcurrentRequests = 2
          |""".stripMargin)

      ExportModule.PivotConfig(
        ConfigFactory.parseString("export.pivot.requestMaxBytes = 67108863").withFallback(base),
        maxDisplayValueCellCount = 10000L
      ) must throwA[IllegalArgumentException]
      ExportModule.PivotConfig(base, maxDisplayValueCellCount = 11000L) must throwA[IllegalArgumentException]
    }

    "reject an empty temporary directory at startup" in {
      val config = ConfigFactory.parseString("""
          |export.pivot.tempDirectory = "   "
          |export.pivot.requestMaxBytes = 67108864
          |export.pivot.unmaterializedArtifactTimeout = 30s
          |export.pivot.streamIdleTimeout = 2m
          |export.pivot.maxConcurrentRequests = 2
          |""".stripMargin)

      ExportModule.PivotConfig(config, maxDisplayValueCellCount = 10000L) must throwA[IllegalArgumentException]
    }

    "reject unsafe artifact lease timeouts and endpoint admission bounds at startup" in {
      val base = ConfigFactory.parseString("""
          |export.pivot.tempDirectory = "/tmp/epoca-pivot-export"
          |export.pivot.requestMaxBytes = 67108864
          |export.pivot.unmaterializedArtifactTimeout = 30s
          |export.pivot.streamIdleTimeout = 2m
          |export.pivot.maxConcurrentRequests = 2
          |""".stripMargin)
      val invalidOverrides = Seq(
        "export.pivot.unmaterializedArtifactTimeout = 0s",
        "export.pivot.unmaterializedArtifactTimeout = 6m",
        "export.pivot.streamIdleTimeout = 0s",
        "export.pivot.streamIdleTimeout = 31m",
        "export.pivot.maxConcurrentRequests = 0",
        "export.pivot.maxConcurrentRequests = 3",
        "export.pivot.maxConcurrentRequests = 33"
      )

      invalidOverrides.forall { overrideValue =>
        scala.util.Try(
          ExportModule.PivotConfig(ConfigFactory.parseString(overrideValue).withFallback(base), maxDisplayValueCellCount = 10000L)
        ) match {
          case scala.util.Failure(_: IllegalArgumentException) => true
          case _                                               => false
        }
      } must beTrue
    }
  }

  private def temporaryArtifact(
    bytes: Array[Byte],
    fileName: SpreadsheetName,
    format: SpreadsheetFormat,
    unmaterializedTimeout: scala.concurrent.duration.FiniteDuration = 30.seconds
  ): PivotExportArtifact = {
    val directory = Files.createTempDirectory("pivot-export-route-")
    val path      = directory.resolve(fileName)
    val _         = Files.write(path, bytes)
    PivotExportArtifact.leased(path, fileName, format, bytes.length.toLong, unmaterializedTimeout, 2.minutes)
  }

  private def eventuallyDeleted(path: Path, within: scala.concurrent.duration.FiniteDuration = 3.seconds): Boolean = {
    val deadline = within.fromNow
    while (Files.exists(path) && deadline.hasTimeLeft()) Thread.sleep(10L)
    !Files.exists(path)
  }

  private def trackedRequest(materializations: AtomicInteger): HttpRequest = {
    val bytes = ByteString(rawRequest.noSpaces)
    val entity = HttpEntity.Default(
      ContentTypes.`application/json`,
      bytes.length.toLong,
      Source.single(bytes).map { chunk =>
        val _ = materializations.incrementAndGet()
        chunk
      }
    )
    HttpRequest(HttpMethods.POST, requiredPath, entity = entity).withHeaders(
      org.apache.pekko.http.scaladsl.model.headers.RawHeader("device-id", "ct-534-test-device"),
      org.apache.pekko.http.scaladsl.model.headers.RawHeader("Correlation-Id", correlationId)
    )
  }

  private def awaitCount(counter: AtomicInteger, expected: Int): Unit = {
    val deadline = 3.seconds.fromNow
    while (counter.get() != expected && deadline.hasTimeLeft()) Thread.sleep(5L)
    if (counter.get() != expected) sys.error(s"expected $expected entity materializations, got ${counter.get()}")
  }

  private def awaitNonOverloadedResponse(
    handler: HttpRequest => scala.concurrent.Future[org.apache.pekko.http.scaladsl.model.HttpResponse],
    materializations: AtomicInteger
  ) = {
    val deadline = 3.seconds.fromNow
    var response = Await.result(handler(trackedRequest(materializations)), 3.seconds)
    while (response.status == StatusCodes.ServiceUnavailable && deadline.hasTimeLeft()) {
      Thread.sleep(5L)
      response = Await.result(handler(trackedRequest(materializations)), 3.seconds)
    }
    response
  }
}
