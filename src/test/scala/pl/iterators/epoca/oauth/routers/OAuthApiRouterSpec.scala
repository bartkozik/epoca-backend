package pl.iterators.epoca.oauth.routers

import cats.effect.unsafe.IORuntime
import io.circe.Json
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Route
import org.mockito.Mockito.verifyNoInteractions
import pl.iterators.epoca.export.domain.SpreadsheetDomain.SpreadsheetFormat
import pl.iterators.epoca.export.services.ExportWorkUnitPivotTableService.ExportResult
import pl.iterators.epoca.export.services.ExportWorkUnitService.ExportWorkUnitResult
import pl.iterators.epoca.oauth.adapters.OAuthReadResult.{NotFound, Ok}
import pl.iterators.epoca.oauth.adapters.{
  OAuthAccessPolicy,
  OAuthClientReader,
  OAuthExporter,
  OAuthOrganizationReader,
  OAuthProjectReader,
  OAuthReportReader,
  OAuthUserReader,
  OAuthWorkUnitReader,
  OAuthWorkUnitWriteAdapter,
  OAuthWorkUnitWriter
}
import pl.iterators.epoca.oauth.domain.OAuthAuthContext
import pl.iterators.epoca.oauth.domain.OAuthDomain.{OAuthClientId, OAuthProjectAccess, OAuthScope}
import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationId, OrganizationSlug}
import pl.iterators.epoca.project.domain.ProjectDomain.ProjectId
import pl.iterators.epoca.stats.domain.WorkUnitPivotQuery.{GlobalFilters, InvalidConfigurationError, Violation, ViolationCode}
import pl.iterators.epoca.stats.services.WorkUnitPivotQueryService.Result
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.utils.RouteSpec
import pl.iterators.epoca.utils.db.PaginatedResult
import pl.iterators.epoca.utils.log.TracingContext
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.WorkUnitId
import pl.iterators.epoca.workunit.idempotency.WorkUnitIdempotencyDomain.WorkUnitIdempotencyKey
import pl.iterators.epoca.workunit.idempotency.WorkUnitIdempotencyResult.Response
import pl.iterators.epoca.workunit.idempotency.WorkUnitWriteOutcome
import pl.iterators.epoca.workunit.services.WorkUnitListService.WorkUnitListData
import pl.iterators.epoca.workunit.services.WorkUnitUpdateService.WorkUnitUpdateRequest

import java.util.UUID

class OAuthApiRouterSpec extends RouteSpec {
  private implicit val runtime: IORuntime             = IORuntime.global
  private val correlationId                           = "ct-520-oauth-api-route"
  private implicit val tracingContext: TracingContext = TracingContext("context", correlationId, Map.empty)

  private val organizationId   = OrganizationId(UUID.randomUUID())
  private val organizationSlug = OrganizationSlug("oauth-org")
  private val projectId        = ProjectId(UUID.randomUUID())
  private val userId           = UserId(UUID.randomUUID())
  private val workUnitId       = WorkUnitId(UUID.randomUUID())
  private val actorId          = UserId(UUID.randomUUID())
  private val keyUuid          = UUID.fromString("11111111-2222-4333-8444-555555555555")
  private val key              = WorkUnitIdempotencyKey(keyUuid)
  private val auth = OAuthAuthContext(
    actorId,
    OAuthClientId(UUID.randomUUID()),
    organizationId,
    Set(OAuthScope.WorkUnitsWrite),
    OAuthProjectAccess.SelectedProjects(Set(projectId)),
    UUID.randomUUID(),
    policyVersion = 1L
  )

  private def router(adapter: OAuthWorkUnitWriter): OAuthApiRouter =
    new OAuthApiRouter(
      mock[OAuthOrganizationReader],
      mock[OAuthUserReader],
      mock[OAuthClientReader],
      mock[OAuthProjectReader],
      mock[OAuthWorkUnitReader],
      adapter,
      mock[OAuthReportReader],
      mock[OAuthExporter]
    )

  "OAuthApiRouter registered reads" should {
    "dispatch every OAuth-enabled read operation through its policy-aware port" in {
      val organizations = mock[OAuthOrganizationReader]
      val users         = mock[OAuthUserReader]
      val clients       = mock[OAuthClientReader]
      val projects      = mock[OAuthProjectReader]
      val workUnits     = mock[OAuthWorkUnitReader]
      val writer        = mock[OAuthWorkUnitWriter]
      val reports       = mock[OAuthReportReader]
      val exporter      = mock[OAuthExporter]
      val policy        = OAuthAccessPolicy.from(auth)
      val route         = Route.seal(new OAuthApiRouter(organizations, users, clients, projects, workUnits, writer, reports, exporter).routes(auth))

      when(organizations.list(any, any)).thenReturn(io(PaginatedResult(0, Seq.empty)))
      when(organizations.find(any, any)).thenReturn(io(None))
      when(users.list(any, any, any)).thenReturn(io(Ok(Seq.empty)))
      when(users.details(any, any, any)).thenReturn(io(Ok(Seq.empty)))
      when(clients.list(any, any, any)).thenReturn(io(Ok(PaginatedResult(0, Seq.empty))))
      when(projects.list(any, any, any)).thenReturn(io(Ok(PaginatedResult(0, Seq.empty))))
      when(projects.find(any, any, any)).thenReturn(io(NotFound))
      when(workUnits.list(any, any, any)).thenReturn(io(Ok(WorkUnitListData(PaginatedResult(0, Seq.empty), Seq.empty))))

      Get("/organizations") ~> route ~> check(response.status shouldEqual StatusCodes.OK)
      Get(s"/organizations/$organizationSlug") ~> route ~> check(response.status shouldEqual StatusCodes.NotFound)
      Get(s"/organizations/$organizationSlug/users") ~> route ~> check(response.status shouldEqual StatusCodes.OK)
      Get(s"/organizations/$organizationSlug/users/$userId/details") ~> route ~> check(response.status shouldEqual StatusCodes.OK)
      Get(s"/organizations/$organizationSlug/clients") ~> route ~> check(response.status shouldEqual StatusCodes.OK)
      Get(s"/organizations/$organizationSlug/projects") ~> route ~> check(response.status shouldEqual StatusCodes.OK)
      Get(s"/organizations/$organizationSlug/projects/$projectId") ~> route ~> check(response.status shouldEqual StatusCodes.NotFound)
      Get(s"/organizations/$organizationSlug/work-units") ~> route ~> check(response.status shouldEqual StatusCodes.OK)

      verify(organizations).list(eqTo(policy), any)
      verify(organizations).find(policy, organizationSlug)
      verify(users).list(policy, organizationSlug, includeArchived = false)
      verify(users).details(policy, organizationSlug, userId)
      verify(clients).list(eqTo(policy), eqTo(organizationSlug), any)
      verify(projects).list(eqTo(policy), eqTo(organizationSlug), any)
      verify(projects).find(policy, organizationSlug, projectId)
      verify(workUnits).list(eqTo(policy), eqTo(organizationSlug), any)
      verifyNoInteractions(writer, reports, exporter)
      1 shouldEqual 1
    }
  }

  "OAuthApiRouter work-unit writes" should {
    "require an explicit userId before invoking the adapter" in {
      val adapter = mock[OAuthWorkUnitWriter]
      val route   = Route.seal(router(adapter).routes(auth))
      val missingUser =
        s"""{"projectId":"$projectId","date":"2026-08-13","duration":"PT1H","description":"missing user","taskId":null}"""

      Post(s"/organizations/$organizationSlug/work-units", requestJson(missingUser)) ~> route ~> check {
        response.status shouldEqual StatusCodes.BadRequest
      }

      verifyNoInteractions(adapter)
      1 shouldEqual 1
    }

    "preserve the existing canonical idempotency and replay contract on create" in {
      val adapter = mock[OAuthWorkUnitWriter]
      val outcome = WorkUnitWriteOutcome(201, Json.obj("id" -> Json.fromString(workUnitId.toString)), Some(workUnitId))
      when(adapter.create(any, any, any, any, any, any)).thenReturn(io(Response(outcome, replayed = true)))
      val route = Route.seal(router(adapter).routes(auth))
      val body =
        s"""{"userId":"$userId","projectId":"$projectId","date":"2026-08-13","duration":"PT1H","description":"created","taskId":null}"""

      Post(s"/organizations/$organizationSlug/work-units", requestJson(body)) ~>
        addHeader("Idempotency-Key", keyUuid.toString.toUpperCase) ~> route ~> check {
          response.status shouldEqual StatusCodes.Created
          responseAs[Json] shouldEqual outcome.body
          header("Idempotency-Replayed").map(_.value()) shouldEqual Some("true")
        }

      verify(adapter).create(
        OAuthAccessPolicy.from(auth),
        actorId,
        organizationSlug,
        OAuthWorkUnitWriteAdapter.OAuthWorkUnitCreateRequest(
          userId,
          projectId,
          pl.iterators.epoca.workunit.domain.WorkUnitDomain.WorkUnitDate(java.time.LocalDate.parse("2026-08-13")),
          pl.iterators.epoca.workunit.domain.WorkUnitDomain.WorkUnitDuration(java.time.Duration.ofHours(1L)),
          pl.iterators.epoca.workunit.domain.WorkUnitDomain.WorkUnitDescription("created"),
          None
        ),
        Some(key),
        correlationId
      )
      1 shouldEqual 1
    }

    "dispatch patch and reject invalid idempotency keys before the adapter" in {
      val adapter = mock[OAuthWorkUnitWriter]
      val outcome = WorkUnitWriteOutcome(200, Json.obj("id" -> Json.fromString(workUnitId.toString)), Some(workUnitId))
      when(adapter.update(any, any, any, any, any, any, any)).thenReturn(io(Response(outcome, replayed = false)))
      val route = Route.seal(router(adapter).routes(auth))
      val body =
        """{"projectId":null,"duration":"PT1H","description":null,"taskId":null,"date":null,"shouldFinalize":false}"""

      Patch(s"/organizations/$organizationSlug/work-units/$workUnitId", requestJson(body)) ~> route ~> check {
        response.status shouldEqual StatusCodes.OK
        header("Idempotency-Replayed") should beNone
      }

      Patch(s"/organizations/$organizationSlug/work-units/$workUnitId", requestJson(body)) ~>
        addHeader("Idempotency-Key", "not-a-uuid") ~> route ~> check {
          response.status shouldEqual StatusCodes.BadRequest
        }

      verify(adapter).update(
        eqTo(OAuthAccessPolicy.from(auth)),
        eqTo(actorId),
        eqTo(organizationSlug),
        eqTo(workUnitId),
        any[WorkUnitUpdateRequest],
        eqTo(None),
        eqTo(correlationId)
      )
      verifyNoMoreInteractions(adapter)
      1 shouldEqual 1
    }

    "expose no delete, ordering, or PM mutation route" in {
      val adapter = mock[OAuthWorkUnitWriter]
      val route   = Route.seal(router(adapter).routes(auth))

      Delete(s"/organizations/$organizationSlug/work-units/$workUnitId") ~> route ~> check(response.status.shouldBeInaccessible)
      Put(s"/organizations/$organizationSlug/work-units/order", requestJson("{}")) ~> route ~> check(response.status.shouldBeInaccessible)
      Put(s"/organizations/$organizationSlug/work-units/$workUnitId/pm-classification", requestJson("{}")) ~> route ~> check(
        response.status.shouldBeInaccessible
      )

      verifyNoInteractions(adapter)
      1 shouldEqual 1
    }
  }

  "OAuthApiRouter reports and exports" should {
    "expose no retired PM filter-options route" in {
      val writer   = mock[OAuthWorkUnitWriter]
      val reports  = mock[OAuthReportReader]
      val exporter = mock[OAuthExporter]
      val route    = Route.seal(router(writer, reports, exporter).routes(auth))
      Get(s"/organizations/$organizationSlug/work-units/pm-classification/filter-options") ~> route ~> check(response.status.shouldBeInaccessible)
      verifyNoInteractions(writer, reports, exporter)
      1 shouldEqual 1
    }

    "dispatch the full pivot query without granting export implicitly" in {
      val writer   = mock[OAuthWorkUnitWriter]
      val reports  = mock[OAuthReportReader]
      val exporter = mock[OAuthExporter]
      val route    = Route.seal(router(writer, reports, exporter).routes(auth))
      val raw      = Json.obj("rows" -> Json.arr())
      val dateFrom = pl.iterators.epoca.workunit.domain.WorkUnitDomain.WorkUnitDate(java.time.LocalDate.parse("2026-08-01"))
      val filters  = GlobalFilters(dateFrom, None, Seq(projectId), Seq.empty, Seq.empty)
      val error    = InvalidConfigurationError(violations = Seq(Violation("$.values", ViolationCode.EMPTY_VALUES)))
      when(reports.fullPivot(any, any, any, any)).thenReturn(io(Result.InvalidConfiguration(error)))

      Post(s"/organizations/$organizationSlug/pivot-tables/work-units/query?dateFrom=2026-08-01&projectId=$projectId", requestJson(raw.noSpaces)) ~>
        route ~> check(response.status shouldEqual StatusCodes.BadRequest)

      verify(reports).fullPivot(OAuthAccessPolicy.from(auth), organizationSlug, raw, filters)
      verifyNoInteractions(writer, exporter)
      1 shouldEqual 1
    }

    "dispatch work-unit and pivot exports only through the export port" in {
      val writer   = mock[OAuthWorkUnitWriter]
      val reports  = mock[OAuthReportReader]
      val exporter = mock[OAuthExporter]
      val route    = Route.seal(router(writer, reports, exporter).routes(auth))
      when(exporter.exportWorkUnits(any, any, any, any)).thenReturn(io(ExportWorkUnitResult.OrganizationNotFound))
      when(exporter.exportPivot(any, any, any, any, any)).thenReturn(io(ExportResult.NoContent))
      val workUnitRequest =
        """{"columnNames":{},"ids":[],"dateFrom":null,"dateTo":null,"projectIds":[],"userIds":[],"clientIds":[]}"""
      val pivotRequest = Json.obj(
        "query" -> Json.obj(
          "rows"    -> Json.arr(Json.fromString("ProjectKey")),
          "columns" -> Json.arr(),
          "values"  -> Json.arr(Json.fromString("EntriesCount")),
          "filters" -> Json.arr(),
          "options" -> Json.obj("rowSubtotals" -> Json.False, "columnSubtotals" -> Json.False, "grandTotals" -> Json.False)
        ),
        "presentation" -> Json.obj()
      )
      val dateFrom     = pl.iterators.epoca.workunit.domain.WorkUnitDomain.WorkUnitDate(java.time.LocalDate.parse("2026-08-01"))
      val pivotFilters = GlobalFilters(dateFrom, None, Seq.empty, Seq.empty, Seq.empty)

      Post(s"/organizations/$organizationSlug/work-units/export?format=Csv", requestJson(workUnitRequest)) ~> route ~> check(
        response.status shouldEqual StatusCodes.NotFound
      )
      Post(s"/organizations/$organizationSlug/pivot-tables/work-units/export?format=Xlsx&dateFrom=$dateFrom", requestJson(pivotRequest.noSpaces)) ~>
        route ~> check {
          response.status shouldEqual StatusCodes.NoContent
          response.entity.isKnownEmpty should beTrue
        }

      verify(exporter).exportWorkUnits(eqTo(OAuthAccessPolicy.from(auth)), eqTo(organizationSlug), any, eqTo(SpreadsheetFormat.Csv))
      verify(exporter).exportPivot(
        eqTo(OAuthAccessPolicy.from(auth)),
        eqTo(organizationSlug),
        eqTo(pivotRequest),
        eqTo(pivotFilters),
        eqTo(SpreadsheetFormat.Xlsx)
      )
      verifyNoInteractions(writer, reports)
      1 shouldEqual 1
    }
  }

  private def router(
    writer: OAuthWorkUnitWriter,
    reports: OAuthReportReader,
    exporter: OAuthExporter
  ): OAuthApiRouter =
    new OAuthApiRouter(
      mock[OAuthOrganizationReader],
      mock[OAuthUserReader],
      mock[OAuthClientReader],
      mock[OAuthProjectReader],
      mock[OAuthWorkUnitReader],
      writer,
      reports,
      exporter
    )
}
