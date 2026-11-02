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
import pl.iterators.epoca.workunit.domain.WorkUnitTaskOverride
import pl.iterators.epoca.workunit.services.WorkUnitDtoService.WorkUnitDto
import pl.iterators.epoca.workunit.services._

import java.util.UUID

class WorkUnitOverrideRouteSpec extends RouteDocSpec {
  override val routeRepresentation: RouteRepresentation[ReplaceWorkUnitOverrideRequest, WorkUnitDto] =
    RouteRepresentation[ReplaceWorkUnitOverrideRequest, WorkUnitDto](
      "Replace the complete desired override state. Existing projectClient/taskId remain source values; effectiveTaskId null means cleared. Stale revision is rejected before no-op.",
      "PUT",
      "/organizations/{organizationSlug}/work-units/{workUnitId}/override",
      authentication = List(RouteSecurity.Bearer())
    )
  private val auth    = allGenerators[AuthContext].normal.generate
  private val slug    = OrganizationSlug("overrides")
  private val id      = WorkUnitId(UUID.randomUUID())
  private val base    = s"/organizations/$slug/work-units/$id"
  private val request = ReplaceWorkUnitOverrideRequest(7, None, WorkUnitTaskOverride("Clear"))

  trait Logged extends BaseScope {
    override lazy val workUnitOverrideService: WorkUnitOverrideService = vmock[WorkUnitOverrideService]
    override lazy val authContextForTest: Option[AuthContext]          = Some(auth)
  }

  "Override routes" should {
    "return Ok with a full fresh work unit and an explicitly null effective task" in new Logged {
      val dto = allGenerators[WorkUnitDto].normal.generate.copy(effectiveTaskId = None)
      when(workUnitOverrideService.replace(auth, slug, id, request)).thenReturn(io(WorkUnitOverrideResult.Ok(dto)))
      Put(s"$base/override", requestJson(request.asJson.noSpaces)) ~> allRoutes ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[Json].hcursor.downField("effectiveTaskId").focus shouldEqual Some(Json.Null)
        responseAs[Json] shouldEqual WorkUnitDto.encoder(dto)
      }
      verify(workUnitOverrideService).replace(auth, slug, id, request)
    }
    "return Conflict with the fresh revision and a stable problem type" in new Logged {
      when(workUnitOverrideService.replace(auth, slug, id, request)).thenReturn(io(WorkUnitOverrideResult.StaleRevision(8)))
      Put(s"$base/override", requestJson(request.asJson.noSpaces)) ~> allRoutes ~> check {
        status shouldEqual StatusCodes.Conflict
        responseAs[Json].hcursor.get[String]("type") shouldEqual Right("result:work-unit-override:stale-revision")
        responseAs[Json].hcursor.downField("extension").get[Long]("currentRevision") shouldEqual Right(8L)
      }
      verify(workUnitOverrideService).replace(auth, slug, id, request)
    }
    "return BadRequest for incomplete desired state or invalid task mode payloads" in new Logged {
      Seq(
        """{"expectedRevision":7,"taskId":{"mode":"Clear"}}""",
        """{"expectedRevision":7,"projectId":null,"taskId":{"mode":"Clear","value":null}}""",
        """{"expectedRevision":7,"projectId":null,"taskId":{"mode":"Set"}}"""
      ).foreach { body =>
        Put(s"$base/override", requestJson(body)) ~> allRoutes ~> check(status shouldEqual StatusCodes.BadRequest)
      }
      verifyNoInteractions(workUnitOverrideService)
    }
    "use revision for delete and require a bounded history snapshot" in new Logged {
      val dto = allGenerators[WorkUnitDto].normal.generate
      when(workUnitOverrideService.delete(auth, slug, id, 7)).thenReturn(io(WorkUnitOverrideResult.Ok(dto)))
      Delete(s"$base/override?expectedRevision=7") ~> allRoutes ~> check(status shouldEqual StatusCodes.OK)
      Get(s"$base/override-history") ~> allRoutes ~> check(status shouldEqual StatusCodes.BadRequest)
      val page = WorkUnitOverrideHistoryPage(0, 1, 25, 7, Nil)
      when(workUnitOverrideService.history(auth, slug, id, 7, None, None)).thenReturn(io(Right(page)))
      Get(s"$base/override-history?upToVersion=7") ~> allRoutes ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[Json] shouldEqual page.asJson
      }
      verify(workUnitOverrideService).delete(auth, slug, id, 7)
      verify(workUnitOverrideService).history(auth, slug, id, 7, None, None)
    }
    "retire stale legacy PM writers with refresh-required without translating versions" in new Logged {
      Put(s"$base/pm-classification", requestJson("""{"expectedVersion":0,"pmProjectId":null,"pmTaskId":"OLD"}""")) ~> allRoutes ~> check {
        status shouldEqual StatusCodes.Gone
        responseAs[Json].hcursor.get[String]("type") shouldEqual Right("result:work-unit-override:refresh-required")
      }
      Delete(s"$base/pm-classification?expectedVersion=0") ~> allRoutes ~> check(status shouldEqual StatusCodes.Gone)
      verifyNoInteractions(workUnitOverrideService)
    }
  }
}
