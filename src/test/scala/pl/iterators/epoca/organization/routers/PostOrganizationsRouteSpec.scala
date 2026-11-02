package pl.iterators.epoca.organization.routers

import org.apache.pekko.http.scaladsl.model.StatusCodes
import pl.iterators.baklava.core.model.{RouteRepresentation, RouteSecurity}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.organization.domain.Organization
import pl.iterators.epoca.organization.services.OrganizationCreateService
import pl.iterators.epoca.organization.services.OrganizationCreateService.{OrganizationCreateRequest, OrganizationCreateResult}
import pl.iterators.epoca.utils.RouteDocSpec

class PostOrganizationsRouteSpec extends RouteDocSpec {
  override val routeRepresentation: RouteRepresentation[OrganizationCreateRequest, Organization] =
    RouteRepresentation[OrganizationCreateRequest, Organization](
      "Create new organization",
      "POST",
      "/organizations",
      authentication = List(RouteSecurity.Bearer())
    )

  val auth: AuthContext = allGenerators[AuthContext].normal.generate
  val routePath         = routeRepresentation.path

  trait NoLoggedTestCase extends BaseScope {
    override lazy val authContextForTest: Option[AuthContext] = None
  }

  trait LoggedTestCase extends BaseScope {
    override lazy val organizationCreateService: OrganizationCreateService = vmock[OrganizationCreateService]

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

    "return Conflict when service returns UserHasOrganization" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random
      when(organizationCreateService.create(auth, requestObject.value))
        .thenReturn(io(OrganizationCreateResult.UserOrganizationsLimitExceeded(3, 3)))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.Conflict
      }

      verify(organizationCreateService).create(auth, requestObject.value)
    }

    "return Conflict when service return SlugIsAlreadyTaken" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random
      when(organizationCreateService.create(auth, requestObject.value))
        .thenReturn(io(OrganizationCreateResult.SlugIsAlreadyTaken))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.Conflict
      }

      verify(organizationCreateService).create(auth, requestObject.value)
    }

    "return NotFound when service returns OperationNotPermitted" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random
      when(organizationCreateService.create(auth, requestObject.value))
        .thenReturn(io(OrganizationCreateResult.OperationNotPermitted))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(organizationCreateService).create(auth, requestObject.value)
    }

    "return Created with service data otherwise" in new LoggedTestCase {
      val requestObject  = routeRepresentation.request.random
      val responseObject = routeRepresentation.response.random
      when(organizationCreateService.create(auth, requestObject.value))
        .thenReturn(io(OrganizationCreateResult.Created(responseObject.value)))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.Created
        responseAs[Json] shouldEqualJson responseObject.getJsonString
      }

      verify(organizationCreateService).create(auth, requestObject.value)
    }
  }
}
