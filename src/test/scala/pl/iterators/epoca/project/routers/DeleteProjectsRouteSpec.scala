package pl.iterators.epoca.project.routers

import org.apache.pekko.http.scaladsl.model.StatusCodes
import pl.iterators.baklava.core.model.{RouteRepresentation, RouteSecurity}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.project.domain.ProjectDomain.ProjectId
import pl.iterators.epoca.project.domain.{Project, ProjectDomain}
import pl.iterators.epoca.project.services.ProjectDeleteService
import pl.iterators.epoca.project.services.ProjectDeleteService.ProjectDeleteResult
import pl.iterators.epoca.utils.RouteDocSpec

class DeleteProjectsRouteSpec extends RouteDocSpec {
  override val routeRepresentation: RouteRepresentation[Unit, Project] =
    RouteRepresentation[Unit, Project](
      "Delete project",
      "DELETE",
      "/organizations/{organizationSlug}/projects/{projectId}",
      authentication = List(RouteSecurity.Bearer())
    )

  val auth: AuthContext                  = allGenerators[AuthContext].normal.generate
  val organizationSlug: OrganizationSlug = allGenerators[OrganizationSlug].normal.generate
  val projectId: ProjectDomain.ProjectId = allGenerators[ProjectId].normal.generate
  val routePath: String =
    routeRepresentation.path
      .replace("{organizationSlug}", organizationSlug)
      .replace("{projectId}", projectId.toString)

  trait NoLoggedTestCase extends BaseScope {
    override lazy val authContextForTest: Option[AuthContext] = None
  }

  trait LoggedTestCase extends BaseScope {
    override lazy val projectDeleteService: ProjectDeleteService = vmock[ProjectDeleteService]

    override val authContextForTest: Option[AuthContext] = Some(auth)
  }

  routeRepresentation.name should {
    "be inaccessible for no-logged user" in new NoLoggedTestCase {
      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldBeInaccessible
      }
    }

    "return NotFound when service returns NoAccessToOrganization" in new LoggedTestCase {
      when(projectDeleteService.delete(auth, organizationSlug, projectId)).thenReturn(io(ProjectDeleteResult.NoAccessToOrganization))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(projectDeleteService).delete(auth, organizationSlug, projectId)
    }

    "return NotFound when service returns OrganizationNotFound" in new LoggedTestCase {
      when(projectDeleteService.delete(auth, organizationSlug, projectId))
        .thenReturn(io(ProjectDeleteResult.OrganizationNotFound))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(projectDeleteService).delete(auth, organizationSlug, projectId)
    }

    "return NotFound when service returns ProjectNotFound" in new LoggedTestCase {
      when(projectDeleteService.delete(auth, organizationSlug, projectId))
        .thenReturn(io(ProjectDeleteResult.ProjectNotFound))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(projectDeleteService).delete(auth, organizationSlug, projectId)
    }

    "return NoContent otherwise" in new LoggedTestCase {
      when(projectDeleteService.delete(auth, organizationSlug, projectId))
        .thenReturn(io(ProjectDeleteResult.Deleted))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NoContent
      }

      verify(projectDeleteService).delete(auth, organizationSlug, projectId)
    }
  }
}
