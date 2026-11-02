package pl.iterators.epoca.organization.routers

import org.apache.pekko.http.scaladsl.model.StatusCodes
import pl.iterators.baklava.core.model.{RouteParameterRepresentation, RouteRepresentation, RouteSecurity}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId
import pl.iterators.epoca.organization.repositories.OrganizationRepository.{OrganizationFilters, OrganizationSortBy}
import pl.iterators.epoca.organization.services.OrganizationListService
import pl.iterators.epoca.organization.services.OrganizationListService.{OrganizationDto, OrganizationListResult}
import pl.iterators.epoca.utils.db.{PageSizeAndNumber, PaginatedResult}
import pl.iterators.epoca.utils.{AuthContextProvider, RouteDocSpec}

import java.util.UUID
import scala.util.Random

class GetOrganizationsRouteSpec extends RouteDocSpec {
  override val routeRepresentation: RouteRepresentation[Unit, PaginatedResult[OrganizationDto]] =
    RouteRepresentation[Unit, PaginatedResult[OrganizationDto]](
      "List organizations",
      "GET",
      "/organizations",
      List(
        RouteParameterRepresentation[UUID]("id", false, UUID.randomUUID(), _.toString),
        RouteParameterRepresentation[Int]("pageSize", false, Random.nextInt(100) + 1, _.toString),
        RouteParameterRepresentation[Int]("pageNumber", false, Random.nextInt(100) + 1, _.toString),
        RouteParameterRepresentation[OrganizationSortBy]("sortBy", false, Random.shuffle(OrganizationSortBy.values).head, _.toString)
      ),
      authentication = List(RouteSecurity.Bearer())
    )

  val auth: AuthContext = AuthContextProvider.user
  val routePath         = routeRepresentation.path

  trait NoLoggedTestCase extends BaseScope {
    override lazy val authContextForTest: Option[AuthContext] = None
  }

  trait LoggedTestCase extends BaseScope {
    override lazy val organizationListService: OrganizationListService = vmock[OrganizationListService]

    override val authContextForTest: Option[AuthContext] = Some(auth)
  }

  routeRepresentation.name should {
    "be inaccessible for no-logged user" in new NoLoggedTestCase {
      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldBeInaccessible
      }
    }

    "return Ok with service data otherwise" in new LoggedTestCase {
      val responseObject = routeRepresentation.response.random
      val filter         = OrganizationFilters()
      when(organizationListService.list(auth, filter))
        .thenReturn(io(OrganizationListResult.Ok(responseObject.value)))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.OK
        responseAs[Json] shouldEqualJson responseObject.getJsonString
      }

      verify(organizationListService).list(auth, filter)
    }

    "return Ok with service data when all optional params provided" in new LoggedTestCase {
      val responseObject = routeRepresentation.response.random
      val filter = OrganizationFilters(
        id = routeRepresentation.getParamValue[OrganizationId]("id"),
        sortBy = routeRepresentation.getParamValue[OrganizationSortBy]("sortBy"),
        pageSizeAndNumber =
          PageSizeAndNumber.fromOptions(routeRepresentation.getParamValue[Int]("pageSize"), routeRepresentation.getParamValue[Int]("pageNumber"))
      )
      when(organizationListService.list(auth, filter))
        .thenReturn(io(OrganizationListResult.Ok(responseObject.value)))

      TestRequest(routeRepresentation.routePathWithAllParameters, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.OK
        responseAs[Json] shouldEqualJson responseObject.getJsonString
      }

      verify(organizationListService).list(auth, filter)
    }
  }
}
