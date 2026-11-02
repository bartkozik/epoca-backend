package pl.iterators.epoca.project.routers

import org.apache.pekko.http.scaladsl.model.StatusCodes
import pl.iterators.baklava.core.model.{RouteRepresentation, RouteSecurity}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.project.domain.ProjectDomain.ProjectId
import pl.iterators.epoca.project.services.ProjectDtoService.ProjectDto
import pl.iterators.epoca.project.services.ProjectFindService
import pl.iterators.epoca.project.services.ProjectFindService.ProjectFindResult
import pl.iterators.epoca.utils.RouteDocSpec

class GetProjectRouteSpec extends RouteDocSpec {
  override val routeRepresentation: RouteRepresentation[Unit, ProjectDto] =
    RouteRepresentation[Unit, ProjectDto](
      "Find project",
      "GET",
      "/organizations/{organizationSlug}/projects/{projectId}",
      authentication = List(RouteSecurity.Bearer())
    )

  val auth: AuthContext                  = allGenerators[AuthContext].normal.generate
  val organizationSlug: OrganizationSlug = allGenerators[OrganizationSlug].normal.generate
  val projectId: ProjectId               = allGenerators[ProjectId].normal.generate

  val routePath: String =
    routeRepresentation.path
      .replace("{organizationSlug}", organizationSlug)
      .replace("{projectId}", projectId.toString)
  val routePathWithParams: String =
    routeRepresentation.routePathWithAllParameters
      .replace("{organizationSlug}", organizationSlug)
      .replace("{projectId}", projectId.toString)

  trait NoLoggedTestCase extends BaseScope {
    override lazy val authContextForTest: Option[AuthContext] = None
  }

  trait LoggedTestCase extends BaseScope {
    override lazy val projectFindService: ProjectFindService = vmock[ProjectFindService]

    override val authContextForTest: Option[AuthContext] = Some(auth)
  }

  routeRepresentation.name should {
    "be inaccessible for no-logged user" in new NoLoggedTestCase {
      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldBeInaccessible
      }
    }

    "return NotFound when service returns OrganizationNotFound" in new LoggedTestCase {
      when(projectFindService.find(auth, organizationSlug, projectId))
        .thenReturn(io(ProjectFindResult.OrganizationNotFound))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(projectFindService).find(auth, organizationSlug, projectId)
    }

    "return NotFound when service returns NoAccessToOrganization" in new LoggedTestCase {
      when(projectFindService.find(auth, organizationSlug, projectId))
        .thenReturn(io(ProjectFindResult.NoAccessToOrganization))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(projectFindService).find(auth, organizationSlug, projectId)
    }

    "return NotFound when service returns ProjectNotFound" in new LoggedTestCase {
      when(projectFindService.find(auth, organizationSlug, projectId))
        .thenReturn(io(ProjectFindResult.ProjectNotFound))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(projectFindService).find(auth, organizationSlug, projectId)
    }

    "return NotFound when service returns NoAccessToProject" in new LoggedTestCase {
      when(projectFindService.find(auth, organizationSlug, projectId))
        .thenReturn(io(ProjectFindResult.NoAccessToProject))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(projectFindService).find(auth, organizationSlug, projectId)
    }

    "return Ok with service data otherwise" in new LoggedTestCase {
      val responseObject = routeRepresentation.response.random
      when(projectFindService.find(auth, organizationSlug, projectId))
        .thenReturn(io(ProjectFindResult.Ok(responseObject.value)))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.OK
        responseAs[Json] shouldEqualJson responseObject.getJsonString
      }

      verify(projectFindService).find(auth, organizationSlug, projectId)
    }

    "return Ok with service data when all optional params provided" in new LoggedTestCase {
      val responseObject = routeRepresentation.response.random
      when(projectFindService.find(auth, organizationSlug, projectId))
        .thenReturn(io(ProjectFindResult.Ok(responseObject.value)))

      TestRequest(routePathWithParams, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.OK
        responseAs[Json] shouldEqualJson responseObject.getJsonString
      }

      verify(projectFindService).find(auth, organizationSlug, projectId)
    }
  }
}
