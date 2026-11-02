package pl.iterators.epoca.stats.routers

import io.circe.Json
import io.circe.syntax._
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, HttpMethods, HttpRequest, StatusCode, StatusCodes}
import org.scalacheck.{Arbitrary, Gen}
import pl.iterators.baklava.core.model.{RouteParameterRepresentation, RouteRepresentation, RouteSecurity}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.client.domain.ClientDomain.ClientId
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.project.domain.ProjectDomain.ProjectId
import pl.iterators.epoca.stats.domain.StatsDomain.{WorkUnitPivotTableKey, WorkUnitPivotTableValue}
import pl.iterators.epoca.stats.domain.WorkUnitPivotQuery._
import pl.iterators.epoca.stats.domain.{WorkUnitPivotQuery, WorkUnitPivotTableRow}
import pl.iterators.epoca.stats.services.WorkUnitPivotQueryService.Result
import pl.iterators.epoca.stats.services.WorkUnitPivotTableService.{WorkUnitPivotTableRequest, WorkUnitPivotTableResult}
import pl.iterators.epoca.stats.services.{WorkUnitPivotQueryService, WorkUnitPivotTableService}
import pl.iterators.epoca.testsupport.generators.{AllGenerators, Generator}
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.utils.RouteDocSpec
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.WorkUnitDate
import pl.iterators.epoca.workunit.repositories.WorkUnitRepository.WorkUnitFilters

import java.time.{Duration, LocalDate}
import java.util.UUID

class PostWorkUnitPivotQueryRouteSpec extends RouteDocSpec {
  private val correlationId = "ct-535-pivot-query-route"
  private val auth          = allGenerators[AuthContext].normal.generate
  private val orgSlug       = OrganizationSlug("pivot-query-route")
  private val dateFrom      = WorkUnitDate(LocalDate.parse("2026-08-01"))
  private val dateTo        = WorkUnitDate(LocalDate.parse("2026-08-31"))
  private val projectIds =
    Seq(ProjectId(UUID.fromString("11111111-1111-4111-8111-111111111111")), ProjectId(UUID.fromString("22222222-2222-4222-8222-222222222222")))
  private val userIds =
    Seq(UserId(UUID.fromString("33333333-3333-4333-8333-333333333333")), UserId(UUID.fromString("44444444-4444-4444-8444-444444444444")))
  private val clientIds =
    Seq(ClientId(UUID.fromString("55555555-5555-4555-8555-555555555555")), ClientId(UUID.fromString("66666666-6666-4666-8666-666666666666")))

  private def repeatable(name: String)(values: Seq[UUID]): String =
    values.map(_.toString).mkString(s"&$name=")

  private val pivotRequest = Request(
    rows = Seq(WorkUnitPivotTableKey.UserKey),
    columns = Seq(WorkUnitPivotTableKey.MonthYearKey),
    values = Seq(WorkUnitPivotTableValue.DurationSum, WorkUnitPivotTableValue.EntriesCount),
    filters = Seq.empty,
    options = WorkUnitPivotQuery.Options(rowSubtotals = true, columnSubtotals = true, grandTotals = true)
  )
  private val rawRequest = pivotRequest.asJson
  private val pivotResponse = Response(
    cells = Seq(
      Cell(
        row = Coordinate(CoordinateKind.Leaf, Seq(PathSegment(WorkUnitPivotTableKey.UserKey, FieldValue.Uuid(userIds.head)))),
        column = Coordinate(CoordinateKind.GrandTotal, Seq.empty),
        values = MetricValues(durationSum = Some(Duration.ofHours(2)), durationAvg = None, entriesCount = Some(3L))
      )
    ),
    filterMembers = Seq.empty,
    result = ResultMetadata(cellCount = 1L, displayValueCellCount = 4L, maxDisplayValueCellCount = 10000L)
  )

  private def constantGenerator[T](value: T): Generator[T] =
    new Generator[T] {
      override val ArbT: Arbitrary[T] = Arbitrary(Gen.const(value))
    }

  private implicit val responseGenerators: AllGenerators[Response] =
    new AllGenerators[Response] {
      override val normal: Generator[Response]  = constantGenerator(pivotResponse)
      override val minimal: Generator[Response] = constantGenerator(pivotResponse)
      override val maximal: Generator[Response] = constantGenerator(pivotResponse)
    }

  override val routeRepresentation: RouteRepresentation[Request, Response] =
    RouteRepresentation[Request, Response](
      "Query a full work-unit pivot table with Rows, Columns, Values, Filters, subtotals and grand totals.",
      "POST",
      "/organizations/{organizationSlug}/pivot-tables/work-units/query",
      List(
        RouteParameterRepresentation[LocalDate]("dateFrom", true, dateFrom, _.toString),
        RouteParameterRepresentation[LocalDate]("dateTo", false, dateTo, _.toString),
        RouteParameterRepresentation[Seq[UUID]]("projectId", false, projectIds.map(id => id: UUID), repeatable("projectId")),
        RouteParameterRepresentation[Seq[UUID]]("userId", false, userIds.map(id => id: UUID), repeatable("userId")),
        RouteParameterRepresentation[Seq[UUID]]("clientId", false, clientIds.map(id => id: UUID), repeatable("clientId"))
      ),
      requestPredefinedValue = Some(pivotRequest),
      responsePredefinedValue = Some(pivotResponse),
      authentication = List(RouteSecurity.Bearer())
    )

  private val routePath            = routeRepresentation.path.replace("{organizationSlug}", orgSlug)
  private val routePathWithFilters = routeRepresentation.routePathWithAllParameters.replace("{organizationSlug}", orgSlug)

  private def exactProblem(
    status: StatusCode,
    typeTag: String,
    title: String
  ): Json =
    Json.obj(
      "type"     -> Json.fromString(s"result:$typeTag"),
      "status"   -> Json.fromInt(status.intValue),
      "title"    -> Json.fromString(title),
      "instance" -> Json.fromString(s"cid:$correlationId")
    )

  trait NoLoggedTestCase extends BaseScope {
    override lazy val authContextForTest: Option[AuthContext] = None
  }

  trait LoggedTestCase extends BaseScope {
    override lazy val workUnitPivotQueryService: WorkUnitPivotQueryService = vmock[WorkUnitPivotQueryService]
    override lazy val workUnitPivotTableService: WorkUnitPivotTableService = vmock[WorkUnitPivotTableService]
    override val authContextForTest: Option[AuthContext]                   = Some(auth)
  }

  routeRepresentation.name should {
    "document global ID filters as repeatable UUID arrays with every fixture value" in {
      val fixtures =
        Seq("projectId" -> projectIds.map(id => id: UUID), "userId" -> userIds.map(id => id: UUID), "clientId" -> clientIds.map(id => id: UUID))

      val documented = fixtures.map { case (name, _) =>
        val parameter = routeRepresentation.parameters.find(_.name == name).getOrElse(sys.error(s"missing documented $name parameter"))
        name -> (parameter.sampleValue -> parameter.marshall)
      }

      documented shouldEqual fixtures.map { case (name, values) => name -> (values -> repeatable(name)(values)) }
    }

    "use a valid documentation response fixture without weakening the runtime metric invariant" in {
      Seq(responseGenerators.minimal, responseGenerators.normal, responseGenerators.maximal).map(_.generate) shouldEqual Seq.fill(3)(pivotResponse)
      MetricValues(durationSum = None, durationAvg = None, entriesCount = Some(-1L)) must
        throwA[IllegalArgumentException](message = "requirement failed: entriesCount must be non-negative")
    }

    "be inaccessible without authentication" in new NoLoggedTestCase {
      TestRequest(s"$routePath?dateFrom=$dateFrom", requestJson(rawRequest.noSpaces)) ~> allRoutes ~> check {
        response.status shouldBeInaccessible
      }
    }

    "return Ok and pass the exact raw JSON, slug, auth context, and ordered global filters to the query service" in new LoggedTestCase {
      val filters = GlobalFilters(dateFrom, Some(dateTo), projectIds.reverse, userIds.reverse, clientIds.reverse)
      when(workUnitPivotQueryService.query(auth, orgSlug, rawRequest, filters)).thenReturn(io(Result.Ok(pivotResponse)))

      TestRequest(routePathWithFilters, requestJson(rawRequest.noSpaces)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.OK
        responseAs[Json] shouldEqual pivotResponse.asJson
      }

      verify(workUnitPivotQueryService).query(auth, orgSlug, rawRequest, filters)
    }

    "return Ok for the required dateFrom with all other global filters absent" in new LoggedTestCase {
      val filters = GlobalFilters(dateFrom, None, Seq.empty, Seq.empty, Seq.empty)
      when(workUnitPivotQueryService.query(auth, orgSlug, rawRequest, filters)).thenReturn(io(Result.Ok(pivotResponse)))

      TestRequest(s"$routePath?dateFrom=$dateFrom", requestJson(rawRequest.noSpaces)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.OK
      }

      verify(workUnitPivotQueryService).query(auth, orgSlug, rawRequest, filters)
    }

    "return BadRequest with the exact bare invalid-configuration error" in new LoggedTestCase {
      val error   = InvalidConfigurationError(violations = Seq(Violation("values", ViolationCode.EMPTY_VALUES)))
      val filters = GlobalFilters(dateFrom, None, Seq.empty, Seq.empty, Seq.empty)
      when(workUnitPivotQueryService.query(auth, orgSlug, rawRequest, filters)).thenReturn(io(Result.InvalidConfiguration(error)))

      TestRequest(s"$routePath?dateFrom=$dateFrom", requestJson(rawRequest.noSpaces)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.BadRequest
        responseAs[Json] shouldEqual error.asJson
        responseAs[Json].asObject.map(_.keys.toSet) shouldEqual Some(Set("code", "message", "violations"))
      }

      verify(workUnitPivotQueryService).query(auth, orgSlug, rawRequest, filters)
    }

    "return 422 UnprocessableEntity with the exact bare result-too-large error" in new LoggedTestCase {
      val error   = ResultTooLargeError(estimatedDisplayValueCellCount = 24000L, maxDisplayValueCellCount = 10000L)
      val filters = GlobalFilters(dateFrom, None, Seq.empty, Seq.empty, Seq.empty)
      when(workUnitPivotQueryService.query(auth, orgSlug, rawRequest, filters)).thenReturn(io(Result.TooLarge(error)))

      TestRequest(s"$routePath?dateFrom=$dateFrom", requestJson(rawRequest.noSpaces)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.UnprocessableContent
        responseAs[Json] shouldEqual error.asJson
        responseAs[Json].asObject.map(_.keys.toSet) shouldEqual
          Some(Set("code", "message", "estimatedDisplayValueCellCount", "maxDisplayValueCellCount"))
      }

      verify(workUnitPivotQueryService).query(auth, orgSlug, rawRequest, filters)
    }

    "preserve the platform text-body 404 semantics for absent and inaccessible organizations" in new LoggedTestCase {
      val filters = GlobalFilters(dateFrom, None, Seq.empty, Seq.empty, Seq.empty)
      Seq(Result.OrganizationNotFound, Result.NoAccessToOrganization).foreach { result =>
        when(workUnitPivotQueryService.query(auth, orgSlug, rawRequest, filters)).thenReturn(io(result))

        TestRequest(s"$routePath?dateFrom=$dateFrom", requestJson(rawRequest.noSpaces)) ~> allRoutes ~> check {
          response.status shouldEqual StatusCodes.NotFound
          response.entity.contentType shouldEqual ContentTypes.`text/plain(UTF-8)`
          response.entity.isKnownEmpty shouldEqual false
          response.entity.asInstanceOf[HttpEntity.Strict].data.utf8String shouldEqual
            "The requested resource could not be found but may be available again in the future."
        }
      }

      verify(workUnitPivotQueryService, times(2)).query(auth, orgSlug, rawRequest, filters)
    }

    "return Forbidden with the exact shared Problem Details body for an insufficient role" in new LoggedTestCase {
      val filters = GlobalFilters(dateFrom, None, Seq.empty, Seq.empty, Seq.empty)
      when(workUnitPivotQueryService.query(auth, orgSlug, rawRequest, filters)).thenReturn(io(Result.InsufficientRole))

      TestRequest(s"$routePath?dateFrom=$dateFrom", requestJson(rawRequest.noSpaces)) ~>
        addHeader("Correlation-Id", correlationId) ~> allRoutes ~> check {
          response.status shouldEqual StatusCodes.Forbidden
          responseAs[Json] shouldEqual exactProblem(
            StatusCodes.Forbidden,
            "work-unit-pivot-query-service:result",
            "User's role doesn't allow to access pivot table"
          )
        }

      verify(workUnitPivotQueryService).query(auth, orgSlug, rawRequest, filters)
    }

    "reject malformed JSON through the shared rejection schema without calling the service" in new LoggedTestCase {
      TestRequest(s"$routePath?dateFrom=$dateFrom", requestJson("{")) ~> addHeader("Correlation-Id", correlationId) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.BadRequest
        responseAs[Json] shouldEqual Json.obj(
          "type"     -> Json.fromString("malformed-request-content"),
          "status"   -> Json.fromInt(StatusCodes.BadRequest.intValue),
          "title"    -> Json.fromString("The request content was malformed"),
          "detail"   -> Json.fromString("exhausted input"),
          "instance" -> Json.fromString(s"cid:$correlationId")
        )
      }
    }

    "reject a missing or malformed dateFrom through the shared query-parameter rejection schema" in new LoggedTestCase {
      val malformedMessage =
        "java.time.LocalDate cannot be parsed from not-a-date – should be in format ISO-8601 standard format e.g. 2007-12-03"
      Seq(
        (
          routePath,
          Json.obj(
            "type"      -> Json.fromString("missing-query-param"),
            "status"    -> Json.fromInt(StatusCodes.BadRequest.intValue),
            "title"     -> Json.fromString("The request is missing a required query parameter"),
            "detail"    -> Json.fromString("dateFrom"),
            "instance"  -> Json.fromString(s"cid:$correlationId"),
            "extension" -> Json.obj("queryParamName" -> Json.fromString("dateFrom"))
          )
        ),
        (
          s"$routePath?dateFrom=not-a-date",
          Json.obj(
            "type"      -> Json.fromString("malformed-query-param"),
            "status"    -> Json.fromInt(StatusCodes.BadRequest.intValue),
            "title"     -> Json.fromString("The query parameter was malformed"),
            "detail"    -> Json.fromString(s"dateFrom: $malformedMessage"),
            "instance"  -> Json.fromString(s"cid:$correlationId"),
            "extension" -> Json.obj("queryParamName" -> Json.fromString("dateFrom"), "message" -> Json.fromString(malformedMessage))
          )
        )
      ).foreach { case (path, expectedProblem) =>
        TestRequest(path, requestJson(rawRequest.noSpaces)) ~> addHeader("device-id", "ct-535-test-device") ~>
          addHeader("Correlation-Id", correlationId) ~> allRoutes ~> check {
            response.status shouldEqual StatusCodes.BadRequest
            responseAs[Json] shouldEqual expectedProblem
          }
      }
    }

    "support a trailing slash but reject other HTTP methods" in new LoggedTestCase {
      val filters = GlobalFilters(dateFrom, None, Seq.empty, Seq.empty, Seq.empty)
      when(workUnitPivotQueryService.query(auth, orgSlug, rawRequest, filters)).thenReturn(io(Result.Ok(pivotResponse)))

      TestRequest(s"$routePath/?dateFrom=$dateFrom", requestJson(rawRequest.noSpaces)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.OK
      }
      HttpRequest(HttpMethods.GET, s"$routePath?dateFrom=$dateFrom").withHeaders(
        org.apache.pekko.http.scaladsl.model.headers.RawHeader("device-id", "ct-535-test-device")
      ) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(workUnitPivotQueryService).query(auth, orgSlug, rawRequest, filters)
    }

    "keep the legacy endpoint dispatching only the legacy pivot service" in new LoggedTestCase {
      val legacyRequest  = WorkUnitPivotTableRequest(Seq(WorkUnitPivotTableKey.UserKey), Seq(WorkUnitPivotTableValue.EntriesCount))
      val legacyResponse = Seq.empty[WorkUnitPivotTableRow]
      when(workUnitPivotTableService.buildPivotTable(auth, orgSlug, legacyRequest, WorkUnitFilters()))
        .thenReturn(io(WorkUnitPivotTableResult.Ok(legacyResponse)))

      TestRequest(routePath.stripSuffix("/query"), requestJson(legacyRequest.asJson.noSpaces)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.OK
        responseAs[Json] shouldEqual legacyResponse.asJson
      }

      verify(workUnitPivotTableService).buildPivotTable(auth, orgSlug, legacyRequest, WorkUnitFilters())
    }
  }
}
