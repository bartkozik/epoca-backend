package pl.iterators.epoca.workunit.routers

import org.apache.pekko.http.scaladsl.model.StatusCodes
import pl.iterators.baklava.core.model.{RouteParameterRepresentation, RouteRepresentation, RouteSecurity}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.utils.RouteDocSpec
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.WorkUnitId
import pl.iterators.epoca.workunit.services.WorkUnitDtoService.WorkUnitDto
import pl.iterators.epoca.workunit.services.{WorkUnitOverrideResult, WorkUnitOverrideService}

import java.util.UUID

class DeleteWorkUnitOverrideRouteSpec extends RouteDocSpec {
  override val routeRepresentation: RouteRepresentation[Unit, WorkUnitDto] =
    RouteRepresentation[Unit, WorkUnitDto](
      "Reset all overrides to inheritance, retaining audit history. Requires the fresh work-unit revision.",
      "DELETE",
      "/organizations/{organizationSlug}/work-units/{workUnitId}/override",
      List(RouteParameterRepresentation[Long]("expectedRevision", true, 7L, _.toString)),
      authentication = List(RouteSecurity.Bearer())
    )
  "DELETE override" should {
    "return Ok with fresh inherited values" in new BaseScope {
      val auth                                                           = allGenerators[AuthContext].normal.generate
      val slug                                                           = OrganizationSlug("overrides")
      val id                                                             = WorkUnitId(UUID.randomUUID())
      val dto                                                            = allGenerators[WorkUnitDto].normal.generate
      override lazy val authContextForTest: Option[AuthContext]          = Some(auth)
      override lazy val workUnitOverrideService: WorkUnitOverrideService = vmock[WorkUnitOverrideService]
      when(workUnitOverrideService.delete(auth, slug, id, 7L)).thenReturn(io(WorkUnitOverrideResult.Ok(dto)))
      Delete(s"/organizations/$slug/work-units/$id/override?expectedRevision=7") ~> allRoutes ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[Json] shouldEqual WorkUnitDto.encoder(dto)
      }
      verify(workUnitOverrideService).delete(auth, slug, id, 7L)
    }
  }
}
