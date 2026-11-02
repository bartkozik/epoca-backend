package pl.iterators.epoca.workunit.routers

import cats.effect.{Clock, IO}
import io.circe.Json
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, HttpHeader, StatusCode, StatusCodes}
import org.specs2.matcher.MatchResult
import pl.iterators.baklava.core.model.{RouteHeaderRepresentation, RouteRepresentation, RouteSecurity}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.utils.RouteDocSpec
import pl.iterators.epoca.utils.db.{DBIOInstances, PostgresIOTransactor}
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.{WorkUnitDuration, WorkUnitEntryType, WorkUnitId}
import pl.iterators.epoca.workunit.idempotency.WorkUnitIdempotencyDomain.WorkUnitIdempotencyKey
import pl.iterators.epoca.workunit.idempotency.WorkUnitIdempotencyResult.{KeyConflict, Response, ResultExpired, VersionUnsupported}
import pl.iterators.epoca.workunit.idempotency.{WorkUnitIdempotencyResult, WorkUnitWriteOutcome}
import pl.iterators.epoca.workunit.repositories.WorkUnitIdempotencyRepository
import pl.iterators.epoca.workunit.services.WorkUnitDtoService.WorkUnitDto
import pl.iterators.epoca.workunit.services.WorkUnitIdempotencyService.WorkUnitIdempotencyConfig
import pl.iterators.epoca.workunit.services.WorkUnitUpdateService.{WorkUnitUpdateRequest, WorkUnitUpdateResult}
import pl.iterators.epoca.workunit.services.{
  WorkUnitIdempotencyPreflightService,
  WorkUnitIdempotencyService,
  WorkUnitUpdateService,
  WorkUnitWriteOutcomeMapper
}
import slick.dbio.DBIO

import java.time.Duration
import java.util.UUID

class PatchWorkUnitRouteSpec extends RouteDocSpec {
  override val routeRepresentation: RouteRepresentation[WorkUnitUpdateRequest, WorkUnitDto] =
    RouteRepresentation[WorkUnitUpdateRequest, WorkUnitDto](
      "Update work unit",
      "PATCH",
      "/organizations/{organizationSlug}/work-units/{workUnitId}",
      headers = List(RouteHeaderRepresentation("Idempotency-Key", false)),
      authentication = List(RouteSecurity.Bearer())
    )

  val auth: AuthContext         = allGenerators[AuthContext].normal.generate
  val orgSlug: OrganizationSlug = allGenerators[OrganizationSlug].normal.generate
  val workUnitId: WorkUnitId    = WorkUnitId(UUID.randomUUID())

  val routePath: String = routeRepresentation.path
    .replace("{organizationSlug}", orgSlug)
    .replace("{workUnitId}", workUnitId.toString)

  private val correlationId = "ct-528-patch-correlation"
  private val keyUuid       = UUID.fromString("AAAAAAAA-BBBB-4CCC-8DDD-EEEEEEEEEEEE")
  private val key           = WorkUnitIdempotencyKey(keyUuid)

  final class RecordingWorkUnitIdempotencyService(
    transactor: PostgresIOTransactor,
    preflightService: WorkUnitIdempotencyPreflightService,
    repository: WorkUnitIdempotencyRepository,
    outcomeMapper: WorkUnitWriteOutcomeMapper,
    clock: Clock[DBIO])
      extends WorkUnitIdempotencyService(transactor, preflightService, repository, outcomeMapper, WorkUnitIdempotencyConfig())(
        cats.effect.unsafe.IORuntime.global,
        clock
      ) {
    var result: WorkUnitIdempotencyResult = Response(WorkUnitWriteOutcome(200, Json.obj(), None), replayed = false)
    var updateCalls: Vector[(AuthContext, OrganizationSlug, WorkUnitId, WorkUnitIdempotencyKey, WorkUnitUpdateRequest, String)] =
      Vector.empty

    override def update(
      authContext: AuthContext,
      organizationSlug: OrganizationSlug,
      workUnitId: WorkUnitId,
      key: WorkUnitIdempotencyKey,
      request: WorkUnitUpdateRequest,
      correlationId: String
    )(
      domainAction: => DBIO[WorkUnitUpdateResult]
    ): IO[WorkUnitIdempotencyResult] = {
      updateCalls :+= ((authContext, organizationSlug, workUnitId, key, request, correlationId))
      IO.pure(result)
    }
  }

  trait NoLoggedTestCase extends BaseScope {
    override lazy val authContextForTest: Option[AuthContext] = None
  }

  trait LoggedTestCase extends BaseScope {
    override lazy val workUnitUpdateService: WorkUnitUpdateService = vmock[WorkUnitUpdateService]
    lazy val recordingWorkUnitIdempotencyService = new RecordingWorkUnitIdempotencyService(
      vmock[PostgresIOTransactor],
      vmock[WorkUnitIdempotencyPreflightService],
      vmock[WorkUnitIdempotencyRepository],
      vmock[WorkUnitWriteOutcomeMapper],
      DBIOInstances.dbioInstance(executor)
    )
    override lazy val workUnitIdempotencyService: WorkUnitIdempotencyService = recordingWorkUnitIdempotencyService

    override val authContextForTest: Option[AuthContext] = Some(auth)

    def withIdempotencyHeaders: RequestTransformer =
      addHeader("Idempotency-Key", keyUuid.toString.toUpperCase) ~> addHeader("Correlation-Id", correlationId)

    def expectedProblem(
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

    def assertNoReplayHeader(): MatchResult[Seq[HttpHeader]] =
      response.headers.filter(_.is("idempotency-replayed")) should beEmpty

    def assertReplayedHeader(): MatchResult[Any] =
      response.headers.filter(_.is("idempotency-replayed")).map(_.value()) shouldEqual List("true")
  }

  routeRepresentation.name should {
    "be inaccessible for no-logged user" in new NoLoggedTestCase {
      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldBeInaccessible
      }
    }
    "return NotFound if service returns OrganizationNotFound" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random

      when(workUnitUpdateService.update(auth, orgSlug, workUnitId, requestObject.value))
        .thenReturn(io(WorkUnitUpdateResult.OrganizationNotFound))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(workUnitUpdateService).update(auth, orgSlug, workUnitId, requestObject.value)
    }
    "return BadRequest if service returns NonPositiveDuration" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random

      when(workUnitUpdateService.update(auth, orgSlug, workUnitId, requestObject.value))
        .thenReturn(io(WorkUnitUpdateResult.NonPositiveDuration))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.BadRequest
      }

      verify(workUnitUpdateService).update(auth, orgSlug, workUnitId, requestObject.value)
    }
    "return BadRequest problem if service returns NonNeutralNoteDuration" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random

      when(workUnitUpdateService.update(auth, orgSlug, workUnitId, requestObject.value))
        .thenReturn(io(WorkUnitUpdateResult.NonNeutralNoteDuration))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.BadRequest
      }

      verify(workUnitUpdateService).update(auth, orgSlug, workUnitId, requestObject.value)
    }
    "return BadRequest problem if service returns FutureWorklogDate" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random

      when(workUnitUpdateService.update(auth, orgSlug, workUnitId, requestObject.value))
        .thenReturn(io(WorkUnitUpdateResult.FutureWorklogDate))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.BadRequest
      }

      verify(workUnitUpdateService).update(auth, orgSlug, workUnitId, requestObject.value)
    }
    "return NotFound if service returns WorkUnitNotFound" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random

      when(workUnitUpdateService.update(auth, orgSlug, workUnitId, requestObject.value))
        .thenReturn(io(WorkUnitUpdateResult.WorkUnitNotFound))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(workUnitUpdateService).update(auth, orgSlug, workUnitId, requestObject.value)
    }
    "return NotFound if service returns NoAccessToOrganization" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random

      when(workUnitUpdateService.update(auth, orgSlug, workUnitId, requestObject.value))
        .thenReturn(io(WorkUnitUpdateResult.NoAccessToOrganization))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(workUnitUpdateService).update(auth, orgSlug, workUnitId, requestObject.value)
    }
    "return NotFound if service returns ProjectNotFound" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random

      when(workUnitUpdateService.update(auth, orgSlug, workUnitId, requestObject.value))
        .thenReturn(io(WorkUnitUpdateResult.ProjectNotFound))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(workUnitUpdateService).update(auth, orgSlug, workUnitId, requestObject.value)
    }
    "return Forbidden if service returns NoAccessToProject" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random

      when(workUnitUpdateService.update(auth, orgSlug, workUnitId, requestObject.value))
        .thenReturn(io(WorkUnitUpdateResult.NoAccessToProject))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
      }

      verify(workUnitUpdateService).update(auth, orgSlug, workUnitId, requestObject.value)
    }
    "return Forbidden if service returns NoAccessToWorkUnit" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random

      when(workUnitUpdateService.update(auth, orgSlug, workUnitId, requestObject.value))
        .thenReturn(io(WorkUnitUpdateResult.NoAccessToWorkUnit))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
      }

      verify(workUnitUpdateService).update(auth, orgSlug, workUnitId, requestObject.value)
    }
    "return identical problem status and type with and without Idempotency-Key" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random
      val outcomeMapper = new WorkUnitWriteOutcomeMapper()
      val cases = Seq(
        WorkUnitUpdateResult.NonPositiveDuration    -> "result:work-unit-update:non-positive-duration",
        WorkUnitUpdateResult.NonNeutralNoteDuration -> "result:work-unit-update:non-neutral-note-duration",
        WorkUnitUpdateResult.FutureWorklogDate      -> "result:work-unit-update:future-worklog-date"
      )

      cases.foreach { case (result, expectedType) =>
        val expectedOutcome = outcomeMapper.update(result, correlationId)
        when(workUnitUpdateService.update(auth, orgSlug, workUnitId, requestObject.value)).thenReturn(io(result))

        var legacyStatus = 0
        var legacyBody   = Json.Null
        TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> addHeader("Correlation-Id", correlationId) ~> allRoutes ~> check {
          legacyStatus = response.status.intValue
          response.entity.contentType shouldEqual ContentTypes.`application/json`
          legacyBody = responseAs[Json]
        }

        recordingWorkUnitIdempotencyService.result = Response(expectedOutcome, replayed = false)
        TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> withIdempotencyHeaders ~> allRoutes ~> check {
          response.status.intValue shouldEqual legacyStatus
          responseAs[Json] shouldEqual legacyBody
          responseAs[Json].hcursor.get[String]("type") shouldEqual Right(expectedType)
          responseAs[Json].hcursor.get[String]("instance") shouldEqual Right(s"cid:$correlationId")
        }
      }

      verify(workUnitUpdateService, times(cases.size)).update(auth, orgSlug, workUnitId, requestObject.value)
    }
    "without Idempotency-Key return the legacy Ok response and call update once" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random
      val responseObject =
        routeRepresentation.response.random.value.copy(duration = WorkUnitDuration(Duration.ZERO), entryType = WorkUnitEntryType.Note)

      when(workUnitUpdateService.update(auth, orgSlug, workUnitId, requestObject.value))
        .thenReturn(io(WorkUnitUpdateResult.Updated(responseObject)))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.OK
        responseAs[Json].hcursor.get[String]("entryType") shouldEqual Right("Note")
        responseAs[Json].hcursor.get[String]("duration") shouldEqual Right("PT0S")
      }

      verify(workUnitUpdateService, times(1)).update(auth, orgSlug, workUnitId, requestObject.value)
      recordingWorkUnitIdempotencyService.updateCalls should beEmpty
    }
    "document Idempotency-Key as optional" in {
      routeRepresentation.headers shouldEqual List(RouteHeaderRepresentation("Idempotency-Key", false))
    }
    "with a canonical UUID call the idempotency facade and return its first exact response" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random
      val body          = Json.obj("updated" -> Json.fromString("exact snapshot"))
      recordingWorkUnitIdempotencyService.result = Response(WorkUnitWriteOutcome(StatusCodes.OK.intValue, body, None), replayed = false)

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> withIdempotencyHeaders ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.OK
        response.entity.contentType shouldEqual ContentTypes.`application/json`
        response.entity shouldEqual HttpEntity(ContentTypes.`application/json`, body.noSpaces)
        assertNoReplayHeader()
      }

      recordingWorkUnitIdempotencyService.updateCalls shouldEqual
        Vector((auth, orgSlug, workUnitId, key, requestObject.value, correlationId))
    }
    "replay a stored custom status 299 and exact body with one replay header" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random
      val body          = Json.obj("replayed" -> Json.arr(Json.fromInt(2), Json.fromString("stored")))
      recordingWorkUnitIdempotencyService.result = Response(WorkUnitWriteOutcome(299, body, None), replayed = true)

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> withIdempotencyHeaders ~> allRoutes ~> check {
        response.status.intValue shouldEqual 299
        response.entity.contentType shouldEqual ContentTypes.`application/json`
        response.entity shouldEqual HttpEntity(ContentTypes.`application/json`, body.noSpaces)
        assertReplayedHeader()
      }

      recordingWorkUnitIdempotencyService.updateCalls shouldEqual
        Vector((auth, orgSlug, workUnitId, key, requestObject.value, correlationId))
    }
    "fail with InternalServerError for an invalid persisted status 600 without a silent fallback" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random
      val body          = Json.obj("mustNot" -> Json.fromString("be returned"))
      recordingWorkUnitIdempotencyService.result = Response(WorkUnitWriteOutcome(600, body, None), replayed = false)

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> withIdempotencyHeaders ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.InternalServerError
        response.entity.contentType shouldEqual ContentTypes.`application/json`
        responseAs[Json] shouldNotEqual body
        assertNoReplayHeader()
      }

      recordingWorkUnitIdempotencyService.updateCalls shouldEqual
        Vector((auth, orgSlug, workUnitId, key, requestObject.value, correlationId))
    }
    "reject an invalid Idempotency-Key before invoking either service" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> addHeader("Idempotency-Key", "not-a-uuid") ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.BadRequest
      }

      recordingWorkUnitIdempotencyService.updateCalls should beEmpty
    }
    "reject a noncanonical UUID accepted leniently by UUID.fromString" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> addHeader("Idempotency-Key", "1-1-1-1-1") ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.BadRequest
      }

      recordingWorkUnitIdempotencyService.updateCalls should beEmpty
    }
    "return Conflict (409) for an idempotency key conflict with the exact problem response" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random
      recordingWorkUnitIdempotencyService.result = KeyConflict

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> withIdempotencyHeaders ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.Conflict
        response.entity.contentType shouldEqual ContentTypes.`application/json`
        responseAs[Json] shouldEqual expectedProblem(StatusCodes.Conflict, "work-unit:idempotency-key-conflict", "Idempotency key conflict")
        assertNoReplayHeader()
      }

      recordingWorkUnitIdempotencyService.updateCalls shouldEqual
        Vector((auth, orgSlug, workUnitId, key, requestObject.value, correlationId))
    }
    "return Conflict (409) for an unsupported idempotency version with the exact problem response" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random
      recordingWorkUnitIdempotencyService.result = VersionUnsupported

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> withIdempotencyHeaders ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.Conflict
        response.entity.contentType shouldEqual ContentTypes.`application/json`
        responseAs[Json] shouldEqual expectedProblem(
          StatusCodes.Conflict,
          "work-unit:idempotency-version-unsupported",
          "Idempotency version unsupported"
        )
        assertNoReplayHeader()
      }

      recordingWorkUnitIdempotencyService.updateCalls shouldEqual
        Vector((auth, orgSlug, workUnitId, key, requestObject.value, correlationId))
    }
    "return 410 Gone for an expired idempotency result with the exact problem response" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random
      recordingWorkUnitIdempotencyService.result = ResultExpired

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> withIdempotencyHeaders ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.Gone
        response.entity.contentType shouldEqual ContentTypes.`application/json`
        responseAs[Json] shouldEqual expectedProblem(StatusCodes.Gone, "work-unit:idempotency-result-expired", "Idempotency result expired")
        assertNoReplayHeader()
      }

      recordingWorkUnitIdempotencyService.updateCalls shouldEqual
        Vector((auth, orgSlug, workUnitId, key, requestObject.value, correlationId))
    }
  }
}
