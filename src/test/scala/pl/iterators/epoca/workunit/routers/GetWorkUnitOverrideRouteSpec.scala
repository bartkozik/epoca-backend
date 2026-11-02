package pl.iterators.epoca.workunit.routers

import org.apache.pekko.http.scaladsl.model.StatusCodes
import pl.iterators.baklava.core.model.{RouteRepresentation, RouteSecurity}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.utils.RouteDocSpec
import pl.iterators.epoca.workunit.services.WorkUnitDtoService.WorkUnitDto
import pl.iterators.epoca.workunit.services.{WorkUnitOverrideResult, WorkUnitOverrideService}

class GetWorkUnitOverrideRouteSpec extends RouteDocSpec {
  override val routeRepresentation: RouteRepresentation[Unit, WorkUnitDto] =
    RouteRepresentation[Unit, WorkUnitDto](
      "Read fresh source/effective values, override state, revision and current caller capabilities.",
      "GET",
      "/organizations/{organizationSlug}/work-units/{workUnitId}/override",
      authentication = List(RouteSecurity.Bearer())
    )
  "GET override" should {
    "return Ok with fresh state without changing it" in new BaseScope {
      val auth                                                           = allGenerators[AuthContext].normal.generate
      val slug                                                           = OrganizationSlug("overrides")
      val dto                                                            = WorkUnitOverrideContractSamples.current
      val id                                                             = dto.id
      override lazy val authContextForTest: Option[AuthContext]          = Some(auth)
      override lazy val workUnitOverrideService: WorkUnitOverrideService = vmock[WorkUnitOverrideService]
      when(workUnitOverrideService.get(auth, slug, id)).thenReturn(io(WorkUnitOverrideResult.Ok(dto)))
      Get(s"/organizations/$slug/work-units/$id/override") ~> allRoutes ~> check {
        if (status != StatusCodes.OK) throw new AssertionError(responseAs[Json].noSpaces)
        status shouldEqual StatusCodes.OK
        responseAs[Json] shouldEqual WorkUnitDto.encoder(dto)
        WorkUnitOverrideContractSamples.capture("get-override.json", responseAs[Json])
      }
      verify(workUnitOverrideService).get(auth, slug, id)
    }
  }
}
