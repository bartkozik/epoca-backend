package pl.iterators.epoca.workunit.routers

import cats.effect.{Clock, IO}
import io.circe.Json
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, HttpHeader, StatusCode, StatusCodes}
import org.specs2.matcher.MatchResult
import pl.iterators.baklava.core.model.{RouteHeaderRepresentation, RouteRepresentation, RouteSecurity}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.project.domain.ProjectDomain.ProjectId
import pl.iterators.epoca.utils.RouteDocSpec
import pl.iterators.epoca.utils.db.{DBIOInstances, PostgresIOTransactor}
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.{WorkUnitDate, WorkUnitDescription, WorkUnitDuration, WorkUnitEntryType}
import pl.iterators.epoca.workunit.idempotency.WorkUnitIdempotencyDomain.WorkUnitIdempotencyKey
import pl.iterators.epoca.workunit.idempotency.WorkUnitIdempotencyResult.{KeyConflict, Response, ResultExpired, VersionUnsupported}
import pl.iterators.epoca.workunit.idempotency.{WorkUnitIdempotencyResult, WorkUnitWriteOutcome}
import pl.iterators.epoca.workunit.repositories.WorkUnitIdempotencyRepository
import pl.iterators.epoca.workunit.services.WorkUnitCreateService.{WorkUnitCreateRequest, WorkUnitCreateResult}
import pl.iterators.epoca.workunit.services.WorkUnitDtoService.WorkUnitDto
import pl.iterators.epoca.workunit.services.WorkUnitIdempotencyService.WorkUnitIdempotencyConfig
import pl.iterators.epoca.workunit.services.{
  WorkUnitCreateService,
  WorkUnitIdempotencyPreflightService,
  WorkUnitIdempotencyService,
  WorkUnitWriteOutcomeMapper
}
import slick.dbio.DBIO

import java.time.{Duration, LocalDate}
import java.util.UUID

class PostWorkUnitRouteSpec extends RouteDocSpec {
  override val routeRepresentation: RouteRepresentation[WorkUnitCreateRequest, WorkUnitDto] =
    RouteRepresentation[WorkUnitCreateRequest, WorkUnitDto](
      "Create a Worklog or Note. duration is required and positive for Worklog; for Note it may be omitted/null and defaults to PT0S. " +
        "entryType defaults to Worklog and userId defaults to the authenticated user.",
      "POST",
      "/organizations/{organizationSlug}/work-units",
      headers = List(RouteHeaderRepresentation("Idempotency-Key", false)),
      authentication = List(RouteSecurity.Bearer())
    )

  val auth: AuthContext         = allGenerators[AuthContext].normal.generate
  val orgSlug: OrganizationSlug = allGenerators[OrganizationSlug].normal.generate

  val routePath: String = routeRepresentation.path
    .replace("{organizationSlug}", orgSlug)

  private val correlationId = "ct-528-post-correlation"
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
    var result: WorkUnitIdempotencyResult = Response(WorkUnitWriteOutcome(201, Json.obj(), None), replayed = false)
    var createCalls: Vector[(AuthContext, OrganizationSlug, WorkUnitIdempotencyKey, WorkUnitCreateRequest, String)] = Vector.empty

    override def create(
      authContext: AuthContext,
      organizationSlug: OrganizationSlug,
      key: WorkUnitIdempotencyKey,
      request: WorkUnitCreateRequest,
      correlationId: String
    )(
      domainAction: => DBIO[WorkUnitCreateResult]
    ): IO[WorkUnitIdempotencyResult] = {
      createCalls :+= ((authContext, organizationSlug, key, request, correlationId))
      IO.pure(result)
    }
  }

  trait NoLoggedTestCase extends BaseScope {
    override lazy val authContextForTest: Option[AuthContext] = None
  }

  trait LoggedTestCase extends BaseScope {
    override lazy val workUnitCreateService: WorkUnitCreateService = vmock[WorkUnitCreateService]
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
    "decode legacy missing and explicit null entryType/userId as defaultable fields" in new LoggedTestCase {
      val projectId   = ProjectId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
      val date        = WorkUnitDate(LocalDate.parse("2026-07-29"))
      val duration    = WorkUnitDuration(Duration.ofHours(1L))
      val description = WorkUnitDescription("legacy")
      val expected =
        WorkUnitCreateRequest(projectId, date, Some(duration), description, taskId = None, isDraft = false, entryType = None, userId = None)
      val responseDto = routeRepresentation.response.random.value.copy(duration = duration, entryType = WorkUnitEntryType.Worklog)
      val legacyJson =
        s"""{"projectId":"$projectId","date":"$date","duration":"PT1H","description":"legacy","taskId":null,"isDraft":false}"""
      val explicitNullsJson =
        s"""{"projectId":"$projectId","date":"$date","duration":"PT1H","description":"legacy","taskId":null,"isDraft":false,"entryType":null,"userId":null}"""

      when(workUnitCreateService.create(auth, orgSlug, expected)).thenReturn(io(WorkUnitCreateResult.Created(responseDto)))

      Seq(legacyJson, explicitNullsJson).foreach { rawJson =>
        TestRequest(routePath, requestJson(rawJson)) ~> allRoutes ~> check {
          response.status shouldEqual StatusCodes.Created
          responseAs[Json].hcursor.get[String]("entryType") shouldEqual Right("Worklog")
        }
      }

      verify(workUnitCreateService, times(2)).create(auth, orgSlug, expected)
    }
    "decode future Note with missing or null duration and return PT0S plus entryType" in new LoggedTestCase {
      val projectId   = ProjectId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
      val date        = WorkUnitDate(LocalDate.parse("2026-08-05"))
      val description = WorkUnitDescription("leave")
      val expected = WorkUnitCreateRequest(
        projectId,
        date,
        duration = None,
        description = description,
        taskId = None,
        isDraft = false,
        entryType = Some(WorkUnitEntryType.Note),
        userId = None
      )
      val responseDto = routeRepresentation.response.random.value.copy(duration = WorkUnitDuration(Duration.ZERO), entryType = WorkUnitEntryType.Note)
      val missingDurationJson =
        s"""{"projectId":"$projectId","date":"$date","description":"leave","taskId":null,"isDraft":false,"entryType":"Note"}"""
      val nullDurationAndUserJson =
        s"""{"projectId":"$projectId","date":"$date","duration":null,"description":"leave","taskId":null,"isDraft":false,"entryType":"Note","userId":null}"""

      when(workUnitCreateService.create(auth, orgSlug, expected)).thenReturn(io(WorkUnitCreateResult.Created(responseDto)))

      Seq(missingDurationJson, nullDurationAndUserJson).foreach { rawJson =>
        TestRequest(routePath, requestJson(rawJson)) ~> allRoutes ~> check {
          response.status shouldEqual StatusCodes.Created
          responseAs[Json].hcursor.get[String]("entryType") shouldEqual Right("Note")
          responseAs[Json].hcursor.get[String]("duration") shouldEqual Right("PT0S")
        }
      }

      verify(workUnitCreateService, times(2)).create(auth, orgSlug, expected)
    }
    "reject unknown and mis-cased entryType with the existing static validation problem" in new LoggedTestCase {
      val projectId = ProjectId(UUID.fromString("11111111-1111-1111-1111-111111111111"))

      Seq("Memo", "note").foreach { invalidEntryType =>
        val rawJson =
          s"""{"projectId":"$projectId","date":"2026-07-29","duration":"PT1H","description":"legacy","taskId":null,"isDraft":false,"entryType":"$invalidEntryType"}"""

        TestRequest(routePath, requestJson(rawJson)) ~> allRoutes ~> check {
          response.status shouldEqual StatusCodes.BadRequest
          responseAs[Json].hcursor.get[String]("type") shouldEqual Right("rejection:static-validation-failed")
        }
      }
    }
    "return NotFound if service returns OrganizationNotFound" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random

      when(workUnitCreateService.create(auth, orgSlug, requestObject.value))
        .thenReturn(io(WorkUnitCreateResult.OrganizationNotFound))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(workUnitCreateService).create(auth, orgSlug, requestObject.value)
    }
    "return BadRequest if service returns NonPositiveDuration" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random

      when(workUnitCreateService.create(auth, orgSlug, requestObject.value))
        .thenReturn(io(WorkUnitCreateResult.NonPositiveDuration))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.BadRequest
      }

      verify(workUnitCreateService).create(auth, orgSlug, requestObject.value)
    }
    "return BadRequest if service returns NonNeutralNoteDuration" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random

      when(workUnitCreateService.create(auth, orgSlug, requestObject.value))
        .thenReturn(io(WorkUnitCreateResult.NonNeutralNoteDuration))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.BadRequest
      }

      verify(workUnitCreateService).create(auth, orgSlug, requestObject.value)
    }
    "return BadRequest if service returns FutureWorklogDate" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random

      when(workUnitCreateService.create(auth, orgSlug, requestObject.value))
        .thenReturn(io(WorkUnitCreateResult.FutureWorklogDate))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.BadRequest
      }

      verify(workUnitCreateService).create(auth, orgSlug, requestObject.value)
    }
    "return Forbidden if service returns NoAccessToTargetUser" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random

      when(workUnitCreateService.create(auth, orgSlug, requestObject.value))
        .thenReturn(io(WorkUnitCreateResult.NoAccessToTargetUser))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
      }

      verify(workUnitCreateService).create(auth, orgSlug, requestObject.value)
    }
    "return Forbidden if service returns TargetUserNotAssignedToProject" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random

      when(workUnitCreateService.create(auth, orgSlug, requestObject.value))
        .thenReturn(io(WorkUnitCreateResult.TargetUserNotAssignedToProject))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
      }

      verify(workUnitCreateService).create(auth, orgSlug, requestObject.value)
    }
    "return NotFound if service returns TargetUserNotFound" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random

      when(workUnitCreateService.create(auth, orgSlug, requestObject.value))
        .thenReturn(io(WorkUnitCreateResult.TargetUserNotFound))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(workUnitCreateService).create(auth, orgSlug, requestObject.value)
    }
    "return NotFound if service returns NoAccessToOrganization" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random

      when(workUnitCreateService.create(auth, orgSlug, requestObject.value))
        .thenReturn(io(WorkUnitCreateResult.NoAccessToOrganization))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(workUnitCreateService).create(auth, orgSlug, requestObject.value)
    }
    "return NotFound if service returns ProjectNotFound" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random

      when(workUnitCreateService.create(auth, orgSlug, requestObject.value))
        .thenReturn(io(WorkUnitCreateResult.ProjectNotFound))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(workUnitCreateService).create(auth, orgSlug, requestObject.value)
    }
    "return Forbidden if service returns NoAccessToProject" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random

      when(workUnitCreateService.create(auth, orgSlug, requestObject.value))
        .thenReturn(io(WorkUnitCreateResult.NoAccessToProject))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
      }

      verify(workUnitCreateService).create(auth, orgSlug, requestObject.value)
    }
    "return identical problem status and type with and without Idempotency-Key" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random
      val outcomeMapper = new WorkUnitWriteOutcomeMapper()
      val cases = Seq(
        WorkUnitCreateResult.NonPositiveDuration            -> "result:work-unit-create:non-positive-duration",
        WorkUnitCreateResult.NonNeutralNoteDuration         -> "result:work-unit-create:non-neutral-note-duration",
        WorkUnitCreateResult.FutureWorklogDate              -> "result:work-unit-create:future-worklog-date",
        WorkUnitCreateResult.NoAccessToTargetUser           -> "result:work-unit-create:no-access-to-target-user",
        WorkUnitCreateResult.TargetUserNotAssignedToProject -> "result:work-unit-create:target-user-not-assigned-to-project",
        WorkUnitCreateResult.TargetUserNotFound             -> "result:work-unit-create:target-user-not-found"
      )

      cases.foreach { case (result, expectedType) =>
        val expectedOutcome = outcomeMapper.create(result, correlationId)
        when(workUnitCreateService.create(auth, orgSlug, requestObject.value)).thenReturn(io(result))

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

      verify(workUnitCreateService, times(cases.size)).create(auth, orgSlug, requestObject.value)
    }
    "without Idempotency-Key return the legacy Created response and call create once" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random
      val responseObject =
        routeRepresentation.response.random.value.copy(duration = WorkUnitDuration(Duration.ZERO), entryType = WorkUnitEntryType.Note)

      when(workUnitCreateService.create(auth, orgSlug, requestObject.value))
        .thenReturn(io(WorkUnitCreateResult.Created(responseObject)))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.Created
        responseAs[Json].hcursor.get[String]("entryType") shouldEqual Right("Note")
        responseAs[Json].hcursor.get[String]("duration") shouldEqual Right("PT0S")
      }

      verify(workUnitCreateService, times(1)).create(auth, orgSlug, requestObject.value)
      recordingWorkUnitIdempotencyService.createCalls should beEmpty
    }
    "document Idempotency-Key as optional" in {
      routeRepresentation.headers shouldEqual List(RouteHeaderRepresentation("Idempotency-Key", false))
    }
    "with a canonical UUID call the idempotency facade and return its first exact response" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random
      val body          = Json.obj("created" -> Json.fromString("exact snapshot"))
      recordingWorkUnitIdempotencyService.result = Response(WorkUnitWriteOutcome(StatusCodes.Created.intValue, body, None), replayed = false)

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> withIdempotencyHeaders ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.Created
        response.entity.contentType shouldEqual ContentTypes.`application/json`
        response.entity shouldEqual HttpEntity(ContentTypes.`application/json`, body.noSpaces)
        assertNoReplayHeader()
      }

      recordingWorkUnitIdempotencyService.createCalls shouldEqual
        Vector((auth, orgSlug, key, requestObject.value, correlationId))
    }
    "replay a stored custom status 299 and exact body with one replay header" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random
      val body          = Json.obj("replayed" -> Json.arr(Json.fromInt(1), Json.fromString("stored")))
      recordingWorkUnitIdempotencyService.result = Response(WorkUnitWriteOutcome(299, body, None), replayed = true)

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> withIdempotencyHeaders ~> allRoutes ~> check {
        response.status.intValue shouldEqual 299
        response.entity.contentType shouldEqual ContentTypes.`application/json`
        response.entity shouldEqual HttpEntity(ContentTypes.`application/json`, body.noSpaces)
        assertReplayedHeader()
      }

      recordingWorkUnitIdempotencyService.createCalls shouldEqual
        Vector((auth, orgSlug, key, requestObject.value, correlationId))
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

      recordingWorkUnitIdempotencyService.createCalls shouldEqual
        Vector((auth, orgSlug, key, requestObject.value, correlationId))
    }
    "reject an invalid Idempotency-Key before invoking either service" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> addHeader("Idempotency-Key", "not-a-uuid") ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.BadRequest
      }

      recordingWorkUnitIdempotencyService.createCalls should beEmpty
    }
    "reject a noncanonical UUID accepted leniently by UUID.fromString" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> addHeader("Idempotency-Key", "1-1-1-1-1") ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.BadRequest
      }

      recordingWorkUnitIdempotencyService.createCalls should beEmpty
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

      recordingWorkUnitIdempotencyService.createCalls shouldEqual
        Vector((auth, orgSlug, key, requestObject.value, correlationId))
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

      recordingWorkUnitIdempotencyService.createCalls shouldEqual
        Vector((auth, orgSlug, key, requestObject.value, correlationId))
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

      recordingWorkUnitIdempotencyService.createCalls shouldEqual
        Vector((auth, orgSlug, key, requestObject.value, correlationId))
    }
  }
}
