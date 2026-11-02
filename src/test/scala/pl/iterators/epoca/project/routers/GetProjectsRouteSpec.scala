package pl.iterators.epoca.project.routers

import org.apache.pekko.http.scaladsl.model.{HttpRequest, StatusCodes, Uri}
import pl.iterators.baklava.core.model.{RouteParameterRepresentation, RouteRepresentation, RouteSecurity}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.client.domain.ClientDomain.ClientId
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.project.domain.ProjectDomain.ProjectId
import pl.iterators.epoca.project.repositories.ProjectRepository.ProjectFilters
import pl.iterators.epoca.project.services.ProjectDtoService.ProjectListDto
import pl.iterators.epoca.project.services.ProjectListService
import pl.iterators.epoca.project.services.ProjectListService.ProjectListResult
import pl.iterators.epoca.utils.RouteDocSpec
import pl.iterators.epoca.utils.UriLengthTestSupport.{FormerMaxUriLength, SupportedMaxUriLength, withQuery}
import pl.iterators.epoca.utils.db.{PageSizeAndNumber, PaginatedResult}

import java.util.UUID
import scala.util.Random

class GetProjectsRouteSpec extends RouteDocSpec {
  override val routeRepresentation: RouteRepresentation[Unit, PaginatedResult[ProjectListDto]] =
    RouteRepresentation[Unit, PaginatedResult[ProjectListDto]](
      "List projects",
      "GET",
      "/organizations/{organizationSlug}/projects",
      List(
        RouteParameterRepresentation[UUID]("id", false, UUID.randomUUID(), _.toString),
        RouteParameterRepresentation[UUID]("clientId", false, UUID.randomUUID(), _.toString),
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
    override lazy val projectListService: ProjectListService = vmock[ProjectListService]

    override val authContextForTest: Option[AuthContext] = Some(auth)
  }

  routeRepresentation.name should {
    "be inaccessible for no-logged user" in new NoLoggedTestCase {
      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldBeInaccessible
      }
    }

    "return NotFound when service returns OrganizationNotFound" in new LoggedTestCase {
      val filter = ProjectFilters()
      when(projectListService.list(auth, organizationSlug, filter))
        .thenReturn(io(ProjectListResult.OrganizationNotFound))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(projectListService).list(auth, organizationSlug, filter)
    }

    "return NotFound when service returns NoAccessToOrganization" in new LoggedTestCase {
      val filter = ProjectFilters()
      when(projectListService.list(auth, organizationSlug, filter))
        .thenReturn(io(ProjectListResult.NoAccessToOrganization))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(projectListService).list(auth, organizationSlug, filter)
    }

    "return Ok with service data otherwise" in new LoggedTestCase {
      val responseObject = routeRepresentation.response.random
      val filter         = ProjectFilters()
      when(projectListService.list(auth, organizationSlug, filter))
        .thenReturn(io(ProjectListResult.Ok(responseObject.value)))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.OK
        responseAs[Json] shouldEqualJson responseObject.getJsonString
      }

      verify(projectListService).list(auth, organizationSlug, filter)
    }

    "return Ok with service data when all optional params provided" in new LoggedTestCase {
      val responseObject = routeRepresentation.response.random
      val filter = ProjectFilters(
        id = routeRepresentation.getParamValue[ProjectId]("id"),
        clientIds = routeRepresentation.getParamValue[ClientId]("clientId").map(Seq(_)),
        includeArchived = routeRepresentation.getParamValue[Boolean]("includeArchived").getOrElse(false),
        pageSizeAndNumber =
          PageSizeAndNumber.fromOptions(routeRepresentation.getParamValue[Int]("pageSize"), routeRepresentation.getParamValue[Int]("pageNumber"))
      )
      val includeArchivedConnections = routeRepresentation.getParamValue[Boolean]("includeArchivedConnections").getOrElse(false)

      when(projectListService.list(auth, organizationSlug, filter, includeArchivedConnections))
        .thenReturn(io(ProjectListResult.Ok(responseObject.value)))

      TestRequest(routePathWithParams, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.OK
        responseAs[Json] shouldEqualJson responseObject.getJsonString
      }

      verify(projectListService).list(auth, organizationSlug, filter, includeArchivedConnections)
    }

    "preserve repeated client filters from a URI above the former limit" in new LoggedTestCase {
      val responseObject = routeRepresentation.response.random
      val clientIds      = Seq.fill(50)(ClientId(UUID.randomUUID()))
      val longRoutePath  = withQuery(routePath, "clientId" -> clientIds.map(_.toString))
      val longRouteUri   = Uri(path = Uri.Path(routePath), queryString = Some(longRoutePath.drop(routePath.length + 1)))
      val filter         = ProjectFilters(clientIds = Some(clientIds.reverse))

      (longRoutePath.length > FormerMaxUriLength) shouldEqual true
      (longRoutePath.length <= SupportedMaxUriLength) shouldEqual true
      when(projectListService.list(auth, organizationSlug, filter, includeArchivedConnections = false))
        .thenReturn(io(ProjectListResult.Ok(responseObject.value)))

      HttpRequest(uri = longRouteUri) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.OK
      }

      verify(projectListService).list(auth, organizationSlug, filter, includeArchivedConnections = false)
    }
  }
}
