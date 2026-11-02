package pl.iterators.epoca.workunit.routers

import io.circe.syntax._
import org.apache.pekko.http.scaladsl.model.StatusCodes
import pl.iterators.baklava.core.model.{RouteParameterRepresentation, RouteRepresentation, RouteSecurity}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.utils.RouteDocSpec
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.WorkUnitId
import pl.iterators.epoca.workunit.services.{WorkUnitOverrideHistoryPage, WorkUnitOverrideService}

import java.util.UUID

class GetWorkUnitOverrideHistoryRouteSpec extends RouteDocSpec {
  override val routeRepresentation: RouteRepresentation[Unit, WorkUnitOverrideHistoryPage] =
    RouteRepresentation[Unit, WorkUnitOverrideHistoryPage](
      "Read a newest-first audit snapshot bounded by the current override version. LegacyPmSnapshot has only legacyBefore/legacyAfter; new WorkUnitOverride events have complete before/after snapshots.",
      "GET",
      "/organizations/{organizationSlug}/work-units/{workUnitId}/override-history",
      List(
        RouteParameterRepresentation[Long]("upToVersion", true, 7L, _.toString),
        RouteParameterRepresentation[Int]("pageSize", false, 25, _.toString),
        RouteParameterRepresentation[Int]("pageNumber", false, 1, _.toString)
      ),
      authentication = List(RouteSecurity.Bearer())
    )
  "GET override history" should {
    "return Ok with serialized Undo ancestry and bounded reversal metadata" in new BaseScope {
      val auth                                                           = allGenerators[AuthContext].normal.generate
      val slug                                                           = OrganizationSlug("overrides")
      val id                                                             = WorkUnitOverrideContractSamples.current.id
      val page                                                           = WorkUnitOverrideContractSamples.history
      override lazy val authContextForTest: Option[AuthContext]          = Some(auth)
      override lazy val workUnitOverrideService: WorkUnitOverrideService = vmock[WorkUnitOverrideService]
      when(workUnitOverrideService.history(auth, slug, id, 8L, Some(25), Some(1))).thenReturn(io(Right(page)))
      Get(s"/organizations/$slug/work-units/$id/override-history?upToVersion=8&pageSize=25&pageNumber=1") ~> allRoutes ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[Json] shouldEqual page.asJson
        WorkUnitOverrideContractSamples.capture("history.json", responseAs[Json])
      }
      verify(workUnitOverrideService).history(auth, slug, id, 8L, Some(25), Some(1))
    }
    "return Ok with a bounded empty history page" in new BaseScope {
      val auth                                                           = allGenerators[AuthContext].normal.generate
      val slug                                                           = OrganizationSlug("overrides")
      val id                                                             = WorkUnitId(UUID.randomUUID())
      val page                                                           = WorkUnitOverrideHistoryPage(0, 1, 25, 7, Nil)
      override lazy val authContextForTest: Option[AuthContext]          = Some(auth)
      override lazy val workUnitOverrideService: WorkUnitOverrideService = vmock[WorkUnitOverrideService]
      when(workUnitOverrideService.history(auth, slug, id, 7L, Some(25), Some(1))).thenReturn(io(Right(page)))
      Get(s"/organizations/$slug/work-units/$id/override-history?upToVersion=7&pageSize=25&pageNumber=1") ~> allRoutes ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[Json] shouldEqual page.asJson
      }
      verify(workUnitOverrideService).history(auth, slug, id, 7L, Some(25), Some(1))
    }
  }
}
