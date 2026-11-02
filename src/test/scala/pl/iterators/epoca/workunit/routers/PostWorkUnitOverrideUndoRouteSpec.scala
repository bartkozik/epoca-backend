package pl.iterators.epoca.workunit.routers

import io.circe.Json
import io.circe.syntax._
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.mockito.Mockito.verifyNoInteractions
import pl.iterators.baklava.core.model.{RouteRepresentation, RouteSecurity}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.utils.RouteDocSpec
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.WorkUnitId
import pl.iterators.epoca.workunit.services.WorkUnitDtoService.WorkUnitDto
import pl.iterators.epoca.workunit.services._

import java.util.UUID

class PostWorkUnitOverrideUndoRouteSpec extends RouteDocSpec {
  override val routeRepresentation: RouteRepresentation[UndoWorkUnitOverrideRequest, WorkUnitDto] =
    RouteRepresentation[UndoWorkUnitOverrideRequest, WorkUnitDto](
      "Undo exactly the latest eligible correction using its event ID and fresh revision. Human Bearer authentication only; OAuth clients cannot call this operation.",
      "POST",
      "/organizations/{organizationSlug}/work-units/{workUnitId}/override/undo",
      authentication = List(RouteSecurity.Bearer("humanBearer"))
    )
  private val auth        = allGenerators[AuthContext].normal.generate
  private val slug        = OrganizationSlug("overrides")
  private val id          = WorkUnitId(UUID.fromString("44444444-4444-4444-8444-444444444444"))
  private val request     = WorkUnitOverrideContractSamples.request
  private val routePath   = s"/organizations/$slug/work-units/$id/override/undo"
  private val correlation = "ct567-undo-test"

  trait Logged extends BaseScope {
    override lazy val workUnitOverrideService: WorkUnitOverrideService = vmock[WorkUnitOverrideService]
    override lazy val authContextForTest: Option[AuthContext]          = Some(auth)
  }

  private def problem(
    code: Int,
    kind: String,
    title: String,
    extension: Option[Json] = None
  ): Json =
    Json
      .obj(
        "type"     -> s"result:work-unit-override:$kind".asJson,
        "status"   -> code.asJson,
        "title"    -> title.asJson,
        "instance" -> s"cid:$correlation".asJson
      )
      .deepMerge(extension.fold(Json.obj())(value => Json.obj("extension" -> value)))

  "POST undo" should {
    "return Ok with the full fresh work unit" in new Logged {
      val dto = WorkUnitOverrideContractSamples.afterUndo
      when(workUnitOverrideService.undo(auth, slug, id, request)).thenReturn(io(WorkUnitOverrideResult.Ok(dto)))
      Post(routePath, requestJson(request.asJson.noSpaces)) ~> allRoutes ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[Json] shouldEqual WorkUnitDto.encoder(dto)
        WorkUnitOverrideContractSamples.capture("post-undo.json", responseAs[Json])
      }
      verify(workUnitOverrideService).undo(auth, slug, id, request)
    }
    "return Conflict for a stale revision" in new Logged {
      when(workUnitOverrideService.undo(auth, slug, id, request)).thenReturn(io(WorkUnitOverrideResult.StaleRevision(8)))
      Post(routePath, requestJson(request.asJson.noSpaces)) ~> addHeader("Correlation-Id", correlation) ~> allRoutes ~> check {
        status shouldEqual StatusCodes.Conflict
        responseAs[Json] shouldEqual problem(409, "stale-revision", "Work unit revision is stale", Some(Json.obj("currentRevision" -> 8.asJson)))
      }
      verify(workUnitOverrideService).undo(auth, slug, id, request)
    }
    "return Conflict for a stale undo target" in new Logged {
      when(workUnitOverrideService.undo(auth, slug, id, request)).thenReturn(io(WorkUnitOverrideResult.StaleUndoTarget(7)))
      Post(routePath, requestJson(request.asJson.noSpaces)) ~> addHeader("Correlation-Id", correlation) ~> allRoutes ~> check {
        status shouldEqual StatusCodes.Conflict
        val body = responseAs[Json]
        body shouldEqual problem(409, "stale-undo-target", "Correction is no longer the undo target", Some(Json.obj("currentRevision" -> 7.asJson)))
        WorkUnitOverrideContractSamples.capture("post-undo-stale-target.json", body)
      }
      verify(workUnitOverrideService).undo(auth, slug, id, request)
    }
    "return BadRequest for a negative revision" in new Logged {
      val invalid = request.copy(expectedRevision = -1)
      when(workUnitOverrideService.undo(auth, slug, id, invalid)).thenReturn(io(WorkUnitOverrideResult.InvalidRequest("expectedRevision")))
      Post(routePath, requestJson(invalid.asJson.noSpaces)) ~> addHeader("Correlation-Id", correlation) ~> allRoutes ~> check {
        status shouldEqual StatusCodes.BadRequest
        responseAs[Json] shouldEqual problem(400, "invalid-request", "Invalid override request", Some(Json.obj("field" -> "expectedRevision".asJson)))
      }
      verify(workUnitOverrideService).undo(auth, slug, id, invalid)
    }
    "return BadRequest for absent or malformed required fields" in new Logged {
      Seq(
        "empty"            -> Json.obj(),
        "missing-target"   -> request.asJson.mapObject(_.remove("targetEventId")),
        "missing-revision" -> request.asJson.mapObject(_.remove("expectedRevision")),
        "bad-target"       -> request.asJson.deepMerge(Json.obj("targetEventId" -> "bad".asJson)),
        "bad-revision"     -> request.asJson.deepMerge(Json.obj("expectedRevision" -> "bad".asJson))
      ).foreach { case (name, body) =>
        Post(routePath, requestJson(body.noSpaces)) ~> allRoutes ~> check {
          status shouldEqual StatusCodes.BadRequest
          val response = responseAs[Json]
          response.hcursor.get[String]("type") shouldEqual Right("rejection:static-validation-failed")
          if (name == "empty") WorkUnitOverrideContractSamples.capture("post-undo-malformed.json", response)
        }
      }
      verifyNoInteractions(workUnitOverrideService)
    }
    "return Forbidden when correction access is denied" in new Logged {
      when(workUnitOverrideService.undo(auth, slug, id, request)).thenReturn(io(WorkUnitOverrideResult.Forbidden))
      Post(routePath, requestJson(request.asJson.noSpaces)) ~> addHeader("Correlation-Id", correlation) ~> allRoutes ~> check {
        status shouldEqual StatusCodes.Forbidden
        responseAs[Json] shouldEqual problem(403, "forbidden", "Override access forbidden")
      }
      verify(workUnitOverrideService).undo(auth, slug, id, request)
    }
    "return NotFound for a work unit in a different organization" in new Logged {
      val foreign = OrganizationSlug("other-org")
      when(workUnitOverrideService.undo(auth, foreign, id, request)).thenReturn(io(WorkUnitOverrideResult.WorkUnitNotFound))
      Post(s"/organizations/$foreign/work-units/$id/override/undo", requestJson(request.asJson.noSpaces)) ~> addHeader(
        "Correlation-Id",
        correlation
      ) ~> allRoutes ~> check {
        status shouldEqual StatusCodes.NotFound
        responseAs[Json] shouldEqual problem(404, "work-unit-not-found", "Work unit not found")
      }
      verify(workUnitOverrideService).undo(auth, foreign, id, request)
    }
    "return Unauthorized without human authentication" in new BaseScope {
      override val authContextForTest: Option[AuthContext] = None
      Post(routePath, requestJson(request.asJson.noSpaces)) ~> allRoutes ~> check {
        status shouldEqual StatusCodes.Unauthorized
        val response = responseAs[Json]
        response.hcursor.get[Int]("status") shouldEqual Right(401)
        WorkUnitOverrideContractSamples.capture("post-undo-unauthorized.json", response)
      }
    }
  }
}
