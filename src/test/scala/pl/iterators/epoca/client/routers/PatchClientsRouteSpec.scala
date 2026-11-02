package pl.iterators.epoca.client.routers

import org.apache.pekko.http.scaladsl.model.StatusCodes
import pl.iterators.baklava.core.model.{RouteRepresentation, RouteSecurity}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.client.domain.ClientDomain.ClientId
import pl.iterators.epoca.client.domain.{Client, ClientDomain}
import pl.iterators.epoca.client.services.ClientUpdateService
import pl.iterators.epoca.client.services.ClientUpdateService.{ClientUpdateRequest, ClientUpdateResult}
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.utils.RouteDocSpec

class PatchClientsRouteSpec extends RouteDocSpec {
  override val routeRepresentation: RouteRepresentation[ClientUpdateRequest, Client] =
    RouteRepresentation[ClientUpdateRequest, Client](
      "Update client",
      "PATCH",
      "/organizations/{organizationSlug}/clients/{clientId}",
      authentication = List(RouteSecurity.Bearer())
    )

  val auth: AuthContext                  = allGenerators[AuthContext].normal.generate
  val organizationSlug: OrganizationSlug = allGenerators[OrganizationSlug].normal.generate
  val clientId: ClientDomain.ClientId    = allGenerators[ClientId].normal.generate
  val routePath: String = routeRepresentation.path
    .replace("{organizationSlug}", organizationSlug)
    .replace("{clientId}", clientId.toString)

  trait NoLoggedTestCase extends BaseScope {
    override lazy val authContextForTest: Option[AuthContext] = None
  }

  trait LoggedTestCase extends BaseScope {
    override lazy val clientUpdateService: ClientUpdateService = vmock[ClientUpdateService]

    override val authContextForTest: Option[AuthContext] = Some(auth)
  }

  routeRepresentation.name should {
    "be inaccessible for no-logged user" in new NoLoggedTestCase {
      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldBeInaccessible
      }
    }

    "return BadRequest when incoming request is not json" in new LoggedTestCase {
      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.BadRequest
      }
    }

    "return NotFound when service returns NoAccessToOrganization" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random
      when(clientUpdateService.update(auth, organizationSlug, clientId, requestObject.value))
        .thenReturn(io(ClientUpdateResult.NoAccessToOrganization))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(clientUpdateService).update(auth, organizationSlug, clientId, requestObject.value)
    }
    "return UnprocessableEntity when service returns DuplicatedName" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random
      when(clientUpdateService.update(auth, organizationSlug, clientId, requestObject.value))
        .thenReturn(io(ClientUpdateResult.DuplicatedName))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.UnprocessableContent
      }

      verify(clientUpdateService).update(auth, organizationSlug, clientId, requestObject.value)
    }

    "return NotFound when service returns OrganizationNotFound" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random
      when(clientUpdateService.update(auth, organizationSlug, clientId, requestObject.value))
        .thenReturn(io(ClientUpdateResult.OrganizationNotFound))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(clientUpdateService).update(auth, organizationSlug, clientId, requestObject.value)
    }

    "return Forbidden when service returns InsufficientRole" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random
      when(clientUpdateService.update(auth, organizationSlug, clientId, requestObject.value))
        .thenReturn(io(ClientUpdateResult.InsufficientRole))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
      }

      verify(clientUpdateService).update(auth, organizationSlug, clientId, requestObject.value)
    }

    "return NotFound when service returns ClientNotFound" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random
      when(clientUpdateService.update(auth, organizationSlug, clientId, requestObject.value))
        .thenReturn(io(ClientUpdateResult.ClientNotFound))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(clientUpdateService).update(auth, organizationSlug, clientId, requestObject.value)
    }

    "return OK with service data otherwise" in new LoggedTestCase {
      val requestObject  = routeRepresentation.request.random
      val responseObject = routeRepresentation.response.random
      when(clientUpdateService.update(auth, organizationSlug, clientId, requestObject.value))
        .thenReturn(io(ClientUpdateResult.Updated(responseObject.value)))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.OK
        responseAs[Json] shouldEqualJson responseObject.getJsonString
      }

      verify(clientUpdateService).update(auth, organizationSlug, clientId, requestObject.value)
    }
  }
}
