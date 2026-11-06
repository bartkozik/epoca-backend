package pl.iterators.epoca.workunit.routers

import org.apache.pekko.http.scaladsl.model.{HttpMethods, HttpRequest, StatusCodes}
import org.mockito.Mockito.verifyNoInteractions
import org.specs2.specification.core.Fragment
import pl.iterators.baklava.core.model.{RouteRepresentation, RouteSecurity}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.utils.RouteDocSpec
import pl.iterators.epoca.workunit.services.WorkUnitOverrideService

import java.util.UUID

trait LegacyWorkUnitPmRouteCases { self: RouteDocSpec =>
  protected def retiredRoute(httpMethodName: String, suffix: String): RouteRepresentation[Unit, Unit] =
    RouteRepresentation[Unit, Unit](
      "Retired PM classification endpoint. Always returns 410 for authenticated human callers; refresh the worklog and use the override API. Legacy versions are never translated into current revisions.",
      httpMethodName,
      s"/organizations/{organizationSlug}/work-units/{workUnitId}/pm-classification$suffix",
      authentication = List(RouteSecurity.Bearer())
    )

  protected def checkRetiredRoute(httpMethodName: String, suffix: String): Fragment = "Retired PM classification endpoint" should {
    "return Gone 410 refresh-required without reading or changing a worklog" in new BaseScope {
      override lazy val authContextForTest: Option[AuthContext]          = Some(allGenerators[AuthContext].normal.generate)
      override lazy val workUnitOverrideService: WorkUnitOverrideService = vmock[WorkUnitOverrideService]
      val request = HttpRequest(
        method = HttpMethods.getForKey(httpMethodName).get,
        uri = s"/organizations/overrides/work-units/${UUID.randomUUID()}/pm-classification$suffix"
      )
      request ~> allRoutes ~> check {
        status shouldEqual StatusCodes.Gone
        responseAs[Json].hcursor.get[String]("type") shouldEqual Right("result:work-unit-override:refresh-required")
      }
      verifyNoInteractions(workUnitOverrideService)
    }
  }
}

class GetLegacyWorkUnitPmRouteSpec extends RouteDocSpec with LegacyWorkUnitPmRouteCases {
  override val routeRepresentation: RouteRepresentation[Unit, Unit] = retiredRoute("GET", "")
  checkRetiredRoute("GET", "")
}
class PutLegacyWorkUnitPmRouteSpec extends RouteDocSpec with LegacyWorkUnitPmRouteCases {
  override val routeRepresentation: RouteRepresentation[Unit, Unit] = retiredRoute("PUT", "")
  checkRetiredRoute("PUT", "")
}
class DeleteLegacyWorkUnitPmRouteSpec extends RouteDocSpec with LegacyWorkUnitPmRouteCases {
  override val routeRepresentation: RouteRepresentation[Unit, Unit] = retiredRoute("DELETE", "")
  checkRetiredRoute("DELETE", "")
}
class GetLegacyWorkUnitPmHistoryRouteSpec extends RouteDocSpec with LegacyWorkUnitPmRouteCases {
  override val routeRepresentation: RouteRepresentation[Unit, Unit] = retiredRoute("GET", "/history")
  checkRetiredRoute("GET", "/history")
}
