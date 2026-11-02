package pl.iterators.epoca.client.routers

import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshaller._
import pl.iterators.baklava.core.model.{RouteRepresentation, RouteSecurity}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.client.domain.Client
import pl.iterators.epoca.client.services.ClientCreateService
import pl.iterators.epoca.client.services.ClientCreateService.{ClientCreateRequest, ClientCreateResult}
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.utils.RouteDocSpec

class PostClientsRouteSpec extends RouteDocSpec {
  override val routeRepresentation: RouteRepresentation[ClientCreateRequest, Client] =
    RouteRepresentation[ClientCreateRequest, Client](
      "Create new client",
      "POST",
      "/organizations/{organizationSlug}/clients",
      authentication = List(RouteSecurity.Bearer())
    )

  val auth: AuthContext                  = allGenerators[AuthContext].normal.generate
  val organizationSlug: OrganizationSlug = allGenerators[OrganizationSlug].normal.generate
  val routePath: String                  = routeRepresentation.path.replace("{organizationSlug}", organizationSlug)

  trait NoLoggedTestCase extends BaseScope {
    override lazy val authContextForTest: Option[AuthContext] = None
  }

  trait LoggedTestCase extends BaseScope {
    override lazy val clientCreateService: ClientCreateService = vmock[ClientCreateService]

    override val authContextForTest: Option[AuthContext] = Some(auth)
  }

  routeRepresentation.name should {
    "be inaccessible for no-logged user" in new NoLoggedTestCase {
      TestRequest(routePath) ~> allRoutes ~> check {
        response.status shouldBeInaccessible
      }
    }

    "return BadRequest when incoming request is not json" in new LoggedTestCase {
      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.BadRequest
      }
    }

    "return NotFound when service returns OrganizationNotFound" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random

      when(clientCreateService.create(auth, organizationSlug, requestObject.value))
        .thenReturn(io(ClientCreateResult.OrganizationNotFound))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(clientCreateService).create(auth, organizationSlug, requestObject.value)
    }

    "return UnprocessableEntity when service returns DuplicatedName" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random

      when(clientCreateService.create(auth, organizationSlug, requestObject.value))
        .thenReturn(io(ClientCreateResult.DuplicatedName))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.UnprocessableContent
      }

      verify(clientCreateService).create(auth, organizationSlug, requestObject.value)
    }

    "return NotFound when service returns NoAccessToOrganization" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random
      when(clientCreateService.create(auth, organizationSlug, requestObject.value))
        .thenReturn(io(ClientCreateResult.NoAccessToOrganization))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(clientCreateService).create(auth, organizationSlug, requestObject.value)
    }
    "return Forbidden when service returns InsufficientRole" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random
      when(clientCreateService.create(auth, organizationSlug, requestObject.value))
        .thenReturn(io(ClientCreateResult.InsufficientRole))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
      }

      verify(clientCreateService).create(auth, organizationSlug, requestObject.value)
    }

    "return Created with service data otherwise" in new LoggedTestCase {
      val requestObject  = routeRepresentation.request.random
      val responseObject = routeRepresentation.response.random
      when(clientCreateService.create(auth, organizationSlug, requestObject.value))
        .thenReturn(io(ClientCreateResult.Created(responseObject.value)))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.Created
        responseAs[Json] shouldEqualJson responseObject.getJsonString
      }

      verify(clientCreateService).create(auth, organizationSlug, requestObject.value)
    }
  }
}
