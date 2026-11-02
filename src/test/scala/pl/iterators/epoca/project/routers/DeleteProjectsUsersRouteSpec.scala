package pl.iterators.epoca.project.routers

import org.apache.pekko.http.scaladsl.model.StatusCodes
import pl.iterators.baklava.core.model.{RouteRepresentation, RouteSecurity}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.project.domain.ProjectDomain.ProjectId
import pl.iterators.epoca.project.services.ProjectDeassignService
import pl.iterators.epoca.project.services.ProjectDeassignService.ProjectDeassignResult
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.utils.RouteDocSpec

class DeleteProjectsUsersRouteSpec extends RouteDocSpec {
  override val routeRepresentation: RouteRepresentation[Unit, Unit] =
    RouteRepresentation[Unit, Unit](
      "Deassign user from project",
      "DELETE",
      "/organizations/{organizationSlug}/projects/{projectId}/users/{userId}",
      authentication = List(RouteSecurity.Bearer())
    )

  val auth: AuthContext                  = allGenerators[AuthContext].normal.generate
  val organizationSlug: OrganizationSlug = allGenerators[OrganizationSlug].normal.generate
  val projectId: ProjectId               = allGenerators[ProjectId].normal.generate
  val userId: UserId                     = allGenerators[UserId].normal.generate
  val routePath: String = routeRepresentation.path
    .replace("{organizationSlug}", organizationSlug)
    .replace("{projectId}", projectId.toString)
    .replace("{userId}", userId.toString)

  trait NoLoggedTestCase extends BaseScope {
    override lazy val authContextForTest: Option[AuthContext] = None
  }

  trait LoggedTestCase extends BaseScope {
    override lazy val projectDeassignService: ProjectDeassignService = vmock[ProjectDeassignService]

    override val authContextForTest: Option[AuthContext] = Some(auth)
  }

  routeRepresentation.name should {
    "be inaccessible for no-logged user" in new NoLoggedTestCase {
      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldBeInaccessible
      }
    }

    "return NotFound when service returns OrganizationNotFound" in new LoggedTestCase {
      when(projectDeassignService.deassign(auth, organizationSlug, projectId, userId))
        .thenReturn(io(ProjectDeassignResult.OrganizationNotFound))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(projectDeassignService).deassign(auth, organizationSlug, projectId, userId)
    }

    "return NotFound when service returns NoAccessToOrganization" in new LoggedTestCase {
      when(projectDeassignService.deassign(auth, organizationSlug, projectId, userId))
        .thenReturn(io(ProjectDeassignResult.NoAccessToOrganization))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(projectDeassignService).deassign(auth, organizationSlug, projectId, userId)
    }

    "return Forbidden when service returns OperationNotPermitted" in new LoggedTestCase {
      when(projectDeassignService.deassign(auth, organizationSlug, projectId, userId))
        .thenReturn(io(ProjectDeassignResult.OperationNotPermitted))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
      }

      verify(projectDeassignService).deassign(auth, organizationSlug, projectId, userId)
    }

    "return NotFound when service returns ProjectNotFound" in new LoggedTestCase {
      when(projectDeassignService.deassign(auth, organizationSlug, projectId, userId))
        .thenReturn(io(ProjectDeassignResult.ProjectNotFound))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(projectDeassignService).deassign(auth, organizationSlug, projectId, userId)
    }

    "return NotFound when service returns UserNotFound" in new LoggedTestCase {
      when(projectDeassignService.deassign(auth, organizationSlug, projectId, userId))
        .thenReturn(io(ProjectDeassignResult.UserNotFound))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(projectDeassignService).deassign(auth, organizationSlug, projectId, userId)
    }

    "return BadRequest when service returns UserAlreadyUnassigned" in new LoggedTestCase {
      when(projectDeassignService.deassign(auth, organizationSlug, projectId, userId))
        .thenReturn(io(ProjectDeassignResult.UserAlreadyUnassigned))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.BadRequest
      }

      verify(projectDeassignService).deassign(auth, organizationSlug, projectId, userId)
    }

    "return Conflict when service returns OwnerNotDeassignable" in new LoggedTestCase {
      when(projectDeassignService.deassign(auth, organizationSlug, projectId, userId))
        .thenReturn(io(ProjectDeassignResult.CreatorNotDeassignable))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.Conflict
      }

      verify(projectDeassignService).deassign(auth, organizationSlug, projectId, userId)
    }

    "return NoContent otherwise" in new LoggedTestCase {
      when(projectDeassignService.deassign(auth, organizationSlug, projectId, userId))
        .thenReturn(io(ProjectDeassignResult.Deassigned))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NoContent
      }

      verify(projectDeassignService).deassign(auth, organizationSlug, projectId, userId)
    }
  }
}
