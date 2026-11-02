package pl.iterators.epoca.project.routers

import org.apache.pekko.http.scaladsl.model.StatusCodes
import pl.iterators.baklava.core.model.{RouteRepresentation, RouteSecurity}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.project.domain.ProjectDomain.ProjectId
import pl.iterators.epoca.project.services.ProjectAssignService
import pl.iterators.epoca.project.services.ProjectAssignService.{ProjectAssignRequest, ProjectAssignResult}
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.utils.RouteDocSpec

class PostProjectsUsersRouteSpec extends RouteDocSpec {
  override val routeRepresentation: RouteRepresentation[ProjectAssignRequest, Unit] =
    RouteRepresentation[ProjectAssignRequest, Unit](
      "Assign user to project",
      "POST",
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
    override lazy val projectAssignService: ProjectAssignService = vmock[ProjectAssignService]

    override val authContextForTest: Option[AuthContext] = Some(auth)
  }

  routeRepresentation.name should {
    "be inaccessible for no-logged user" in new NoLoggedTestCase {
      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldBeInaccessible
      }
    }

    "be BadRequest on empty json" in new LoggedTestCase {
      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.BadRequest
      }
    }

    "return NotFound when service returns OrganizationNotFound" in new LoggedTestCase {
      val request = routeRepresentation.request.normal

      when(projectAssignService.assign(auth, organizationSlug, projectId, userId, request.value))
        .thenReturn(io(ProjectAssignResult.OrganizationNotFound))

      TestRequest(routePath, requestJson(request.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(projectAssignService).assign(auth, organizationSlug, projectId, userId, request.value)
    }

    "return NotFound when service returns NoAccessToOrganization" in new LoggedTestCase {
      val request = routeRepresentation.request.normal

      when(projectAssignService.assign(auth, organizationSlug, projectId, userId, request.value))
        .thenReturn(io(ProjectAssignResult.NoAccessToOrganization))

      TestRequest(routePath, requestJson(request.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(projectAssignService).assign(auth, organizationSlug, projectId, userId, request.value)
    }

    "return Forbidden when service returns OperationNotPermitted" in new LoggedTestCase {
      val request = routeRepresentation.request.normal

      when(projectAssignService.assign(auth, organizationSlug, projectId, userId, request.value))
        .thenReturn(io(ProjectAssignResult.OperationNotPermitted))

      TestRequest(routePath, requestJson(request.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
      }

      verify(projectAssignService).assign(auth, organizationSlug, projectId, userId, request.value)
    }

    "return NotFound when service returns ProjectNotFound" in new LoggedTestCase {
      val request = routeRepresentation.request.normal

      when(projectAssignService.assign(auth, organizationSlug, projectId, userId, request.value))
        .thenReturn(io(ProjectAssignResult.ProjectNotFound))

      TestRequest(routePath, requestJson(request.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(projectAssignService).assign(auth, organizationSlug, projectId, userId, request.value)
    }
    "return Forbidden when service returns NotAMember" in new LoggedTestCase {
      val request = routeRepresentation.request.normal

      when(projectAssignService.assign(auth, organizationSlug, projectId, userId, request.value))
        .thenReturn(io(ProjectAssignResult.NotAMember))

      TestRequest(routePath, requestJson(request.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
      }

      verify(projectAssignService).assign(auth, organizationSlug, projectId, userId, request.value)
    }

    "return NotFound when service returns UserNotFound" in new LoggedTestCase {
      val request = routeRepresentation.request.normal

      when(projectAssignService.assign(auth, organizationSlug, projectId, userId, request.value))
        .thenReturn(io(ProjectAssignResult.UserNotFound))

      TestRequest(routePath, requestJson(request.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(projectAssignService).assign(auth, organizationSlug, projectId, userId, request.value)
    }

    "return Conflict when service returns UserAlreadyAssigned" in new LoggedTestCase {
      val request = routeRepresentation.request.normal

      when(projectAssignService.assign(auth, organizationSlug, projectId, userId, request.value))
        .thenReturn(io(ProjectAssignResult.UserAlreadyAssigned))

      TestRequest(routePath, requestJson(request.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.Conflict
      }

      verify(projectAssignService).assign(auth, organizationSlug, projectId, userId, request.value)
    }

    "return Conflict when service returns CreatorNotDeassignable" in new LoggedTestCase {
      val request = routeRepresentation.request.normal

      when(projectAssignService.assign(auth, organizationSlug, projectId, userId, request.value))
        .thenReturn(io(ProjectAssignResult.CreatorNotDeassignable))

      TestRequest(routePath, requestJson(request.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.Conflict
      }

      verify(projectAssignService).assign(auth, organizationSlug, projectId, userId, request.value)
    }

    "return OK otherwise" in new LoggedTestCase {
      val request = routeRepresentation.request.normal

      when(projectAssignService.assign(auth, organizationSlug, projectId, userId, request.value))
        .thenReturn(io(ProjectAssignResult.Assigned))

      TestRequest(routePath, requestJson(request.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.OK
      }

      verify(projectAssignService).assign(auth, organizationSlug, projectId, userId, request.value)
    }
  }
}
