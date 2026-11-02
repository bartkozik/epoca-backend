package pl.iterators.epoca.client.routers

import org.apache.pekko.http.scaladsl.model.StatusCodes
import pl.iterators.baklava.core.model.{RouteParameterRepresentation, RouteRepresentation, RouteSecurity}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.client.domain.ClientDomain.ClientId
import pl.iterators.epoca.client.repositories.ClientRepository.ClientFilters
import pl.iterators.epoca.client.services.ClientDtoService.ClientDto
import pl.iterators.epoca.client.services.ClientListService
import pl.iterators.epoca.client.services.ClientListService.ClientListResult
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.utils.RouteDocSpec
import pl.iterators.epoca.utils.db.{PageSizeAndNumber, PaginatedResult}

import java.util.UUID
import scala.util.Random

class GetClientsRouteSpec extends RouteDocSpec {
  override val routeRepresentation: RouteRepresentation[Unit, PaginatedResult[ClientDto]] =
    RouteRepresentation[Unit, PaginatedResult[ClientDto]](
      "List clients",
      "GET",
      "/organizations/{organizationSlug}/clients",
      List(
        RouteParameterRepresentation[UUID]("id", false, UUID.randomUUID(), _.toString),
        RouteParameterRepresentation[Boolean]("includeArchived", false, Random.nextBoolean(), _.toString),
        RouteParameterRepresentation[Boolean]("includeArchivedConnections", false, Random.nextBoolean(), _.toString),
        RouteParameterRepresentation[Int]("pageSize", false, Random.nextInt(100) + 1, _.toString),
        RouteParameterRepresentation[Int]("pageNumber", false, Random.nextInt(100) + 1, _.toString)
      ),
      authentication = List(RouteSecurity.Bearer())
    )

  val auth: AuthContext                  = allGenerators[AuthContext].normal.generate
  val organizationSlug: OrganizationSlug = allGenerators[OrganizationSlug].normal.generate
  val routePath: String                  = routeRepresentation.path.replace("{organizationSlug}", organizationSlug)
  val routePathWithParams: String        = routeRepresentation.routePathWithAllParameters.replace("{organizationSlug}", organizationSlug)

  trait NoLoggedTestCase extends BaseScope {
    override lazy val authContextForTest: Option[AuthContext] = None
  }

  trait LoggedTestCase extends BaseScope {
    override lazy val clientListService: ClientListService = vmock[ClientListService]

    override val authContextForTest: Option[AuthContext] = Some(auth)
  }

  routeRepresentation.name should {
    "be inaccessible for no-logged user" in new NoLoggedTestCase {
      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldBeInaccessible
      }
    }

    "return NotFound when service returns OrganizationNotFound" in new LoggedTestCase {
      val filter = ClientFilters()
      when(clientListService.list(auth, organizationSlug, filter))
        .thenReturn(io(ClientListResult.OrganizationNotFound))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(clientListService).list(auth, organizationSlug, filter)
    }

    "return NotFound when service returns NoAccessToOrganization" in new LoggedTestCase {
      val filter = ClientFilters()
      when(clientListService.list(auth, organizationSlug, filter))
        .thenReturn(io(ClientListResult.NoAccessToOrganization))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(clientListService).list(auth, organizationSlug, filter)
    }

    "return Ok with service data otherwise" in new LoggedTestCase {
      val responseObject = routeRepresentation.response.random
      val filter         = ClientFilters()
      when(clientListService.list(auth, organizationSlug, filter))
        .thenReturn(io(ClientListResult.Ok(responseObject.value)))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.OK
        responseAs[Json] shouldEqualJson responseObject.getJsonString
      }

      verify(clientListService).list(auth, organizationSlug, filter)
    }

    "return Ok with service data when all optional params provided" in new LoggedTestCase {
      val responseObject = routeRepresentation.response.random
      val filter = ClientFilters(
        id = routeRepresentation.getParamValue[ClientId]("id"),
        includeArchived = routeRepresentation.getParamValue[Boolean]("includeArchived").getOrElse(false),
        pageSizeAndNumber =
          PageSizeAndNumber.fromOptions(routeRepresentation.getParamValue[Int]("pageSize"), routeRepresentation.getParamValue[Int]("pageNumber"))
      )
      val includeArchivedConnections = routeRepresentation.getParamValue[Boolean]("includeArchivedConnections").getOrElse(false)
      when(clientListService.list(auth, organizationSlug, filter, includeArchivedConnections))
        .thenReturn(io(ClientListResult.Ok(responseObject.value)))

      TestRequest(routePathWithParams, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.OK
        responseAs[Json] shouldEqualJson responseObject.getJsonString
      }

      verify(clientListService).list(auth, organizationSlug, filter, includeArchivedConnections)
    }
  }
}
