package pl.iterators.epoca.workunit.routers

import org.apache.pekko.http.scaladsl.model.StatusCodes
import pl.iterators.baklava.core.model.{RouteRepresentation, RouteSecurity}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.utils.RouteDocSpec
import pl.iterators.epoca.workunit.domain.WorkUnitOrder
import pl.iterators.epoca.workunit.services.WorkUnitOrderUpsertService
import pl.iterators.epoca.workunit.services.WorkUnitOrderUpsertService.{WorkUnitOrderUpsertRequest, WorkUnitOrderUpsertResult}

class PutWorkUnitOrderRouteSpec extends RouteDocSpec {
  override val routeRepresentation: RouteRepresentation[WorkUnitOrderUpsertRequest, WorkUnitOrder] =
    RouteRepresentation[WorkUnitOrderUpsertRequest, WorkUnitOrder](
      "Provide order of work units in a given date. Ids are NOT checked if they belong either to user / date / organization for performance.",
      "PUT",
      "/organizations/{organizationSlug}/work-units/order",
      List(),
      authentication = List(RouteSecurity.Bearer())
    )

  val auth: AuthContext         = allGenerators[AuthContext].normal.generate
  val orgSlug: OrganizationSlug = allGenerators[OrganizationSlug].normal.generate

  val routePath: String = routeRepresentation.path
    .replace("{organizationSlug}", orgSlug)

  trait NoLoggedTestCase extends BaseScope {
    override lazy val authContextForTest: Option[AuthContext] = None
  }

  trait LoggedTestCase extends BaseScope {
    override lazy val workUnitOrderUpsertService: WorkUnitOrderUpsertService = vmock[WorkUnitOrderUpsertService]

    override val authContextForTest: Option[AuthContext] = Some(auth)
  }

  routeRepresentation.name should {
    "be inaccessible for no-logged user" in new NoLoggedTestCase {
      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldBeInaccessible
      }
    }
    "return NotFound if service returns OrganizationNotFound" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random

      when(workUnitOrderUpsertService.upsert(auth, orgSlug, requestObject.value))
        .thenReturn(io(WorkUnitOrderUpsertResult.OrganizationNotFound))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(workUnitOrderUpsertService).upsert(auth, orgSlug, requestObject.value)
    }
    "return NotFound if service returns NoAccessToOrganization" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random

      when(workUnitOrderUpsertService.upsert(auth, orgSlug, requestObject.value))
        .thenReturn(io(WorkUnitOrderUpsertResult.NoAccessToOrganization))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(workUnitOrderUpsertService).upsert(auth, orgSlug, requestObject.value)
    }
    "return Ok with service data otherwise" in new LoggedTestCase {
      val requestObject  = routeRepresentation.request.random
      val responseObject = routeRepresentation.response.random

      when(workUnitOrderUpsertService.upsert(auth, orgSlug, requestObject.value))
        .thenReturn(io(WorkUnitOrderUpsertResult.Ok(responseObject.value)))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.Created
        responseAs[Json] shouldEqualJson responseObject.getJsonString
      }

      verify(workUnitOrderUpsertService).upsert(auth, orgSlug, requestObject.value)
    }
  }
}
