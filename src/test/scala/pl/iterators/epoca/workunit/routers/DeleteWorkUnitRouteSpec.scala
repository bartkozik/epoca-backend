package pl.iterators.epoca.workunit.routers

import org.apache.pekko.http.scaladsl.model.StatusCodes
import pl.iterators.baklava.core.model.{RouteRepresentation, RouteSecurity}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.utils.RouteDocSpec
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.WorkUnitId
import pl.iterators.epoca.workunit.services.WorkUnitDeleteService
import pl.iterators.epoca.workunit.services.WorkUnitDeleteService.WorkUnitDeleteResult

import java.util.UUID

class DeleteWorkUnitRouteSpec extends RouteDocSpec {
  override val routeRepresentation: RouteRepresentation[Unit, Unit] =
    RouteRepresentation[Unit, Unit](
      "Delete work unit by id.",
      "DELETE",
      "/organizations/{organizationSlug}/work-units/{workUnitId}",
      List(),
      authentication = List(RouteSecurity.Bearer())
    )

  val auth: AuthContext         = allGenerators[AuthContext].normal.generate
  val orgSlug: OrganizationSlug = allGenerators[OrganizationSlug].normal.generate
  val workUnitId: WorkUnitId    = WorkUnitId(UUID.randomUUID())

  val routePath: String = routeRepresentation.path
    .replace("{organizationSlug}", orgSlug)
    .replace("{workUnitId}", workUnitId.toString)

  trait NoLoggedTestCase extends BaseScope {
    override lazy val authContextForTest: Option[AuthContext] = None
  }

  trait LoggedTestCase extends BaseScope {
    override lazy val workUnitDeleteService: WorkUnitDeleteService = vmock[WorkUnitDeleteService]

    override val authContextForTest: Option[AuthContext] = Some(auth)
  }

  routeRepresentation.name should {
    "be inaccessible for no-logged user" in new NoLoggedTestCase {
      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldBeInaccessible
      }
    }
    "return NotFound if service returns WorkUnitNotFound" in new LoggedTestCase {
      when(workUnitDeleteService.delete(auth, orgSlug, workUnitId))
        .thenReturn(io(WorkUnitDeleteResult.WorkUnitNotFound))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(workUnitDeleteService).delete(auth, orgSlug, workUnitId)
    }

    "return NotFound if service returns OrganizationNotFound" in new LoggedTestCase {
      when(workUnitDeleteService.delete(auth, orgSlug, workUnitId))
        .thenReturn(io(WorkUnitDeleteResult.OrganizationNotFound))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(workUnitDeleteService).delete(auth, orgSlug, workUnitId)
    }

    "return NotFound if service returns NoAccessToOrganization" in new LoggedTestCase {
      when(workUnitDeleteService.delete(auth, orgSlug, workUnitId))
        .thenReturn(io(WorkUnitDeleteResult.NoAccessToOrganization))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(workUnitDeleteService).delete(auth, orgSlug, workUnitId)
    }

    "return Forbidden if service returns NoAccessToWorkUnit" in new LoggedTestCase {
      when(workUnitDeleteService.delete(auth, orgSlug, workUnitId))
        .thenReturn(io(WorkUnitDeleteResult.NoAccessToWorkUnit))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
      }

      verify(workUnitDeleteService).delete(auth, orgSlug, workUnitId)
    }

    "return NoContent otherwise" in new LoggedTestCase {
      when(workUnitDeleteService.delete(auth, orgSlug, workUnitId))
        .thenReturn(io(WorkUnitDeleteResult.Deleted))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NoContent
      }

      verify(workUnitDeleteService).delete(auth, orgSlug, workUnitId)
    }
  }
}
