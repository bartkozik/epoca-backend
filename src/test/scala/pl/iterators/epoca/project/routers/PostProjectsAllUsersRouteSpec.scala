package pl.iterators.epoca.project.routers

import org.apache.pekko.http.scaladsl.model.StatusCodes
import pl.iterators.baklava.core.model.{RouteRepresentation, RouteSecurity}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.project.services.ProjectBulkAssignService
import pl.iterators.epoca.project.services.ProjectBulkAssignService.{ProjectBulkAssignRequest, ProjectBulkAssignResponse, ProjectBulkAssignResult}
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.utils.RouteDocSpec

class PostProjectsAllUsersRouteSpec extends RouteDocSpec {
  override val routeRepresentation: RouteRepresentation[ProjectBulkAssignRequest, ProjectBulkAssignResponse] =
    RouteRepresentation[ProjectBulkAssignRequest, ProjectBulkAssignResponse](
      "Assign user to multiple projects as Member. Returns number of added projects",
      "POST",
      "/organizations/{organizationSlug}/projects/all/users/{userId}",
      authentication = List(RouteSecurity.Bearer())
    )

  val auth: AuthContext                  = allGenerators[AuthContext].normal.generate
  val organizationSlug: OrganizationSlug = allGenerators[OrganizationSlug].normal.generate
  val userId: UserId                     = allGenerators[UserId].normal.generate
  val routePath: String = routeRepresentation.path
    .replace("{organizationSlug}", organizationSlug)
    .replace("{userId}", userId.toString)

  trait NoLoggedTestCase extends BaseScope {
    override lazy val authContextForTest: Option[AuthContext] = None
  }

  trait LoggedTestCase extends BaseScope {
    override lazy val projectBulkAssignService: ProjectBulkAssignService = vmock[ProjectBulkAssignService]

    override val authContextForTest: Option[AuthContext] = Some(auth)
  }

  routeRepresentation.name should {
    "be inaccessible for no-logged user" in new NoLoggedTestCase {
      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldBeInaccessible
      }
    }

    "return NotFound when service returns OrganizationNotFound" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random
      when(projectBulkAssignService.assign(auth, organizationSlug, userId, requestObject.value))
        .thenReturn(io(ProjectBulkAssignResult.OrganizationNotFound))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(projectBulkAssignService).assign(auth, organizationSlug, userId, requestObject.value)
    }

    "return NotFound when service returns NoAccessToOrganization" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random
      when(projectBulkAssignService.assign(auth, organizationSlug, userId, requestObject.value))
        .thenReturn(io(ProjectBulkAssignResult.NoAccessToOrganization))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(projectBulkAssignService).assign(auth, organizationSlug, userId, requestObject.value)
    }

    "return Forbidden when service returns NotAMember" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random
      when(projectBulkAssignService.assign(auth, organizationSlug, userId, requestObject.value))
        .thenReturn(io(ProjectBulkAssignResult.NotAMember))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
      }

      verify(projectBulkAssignService).assign(auth, organizationSlug, userId, requestObject.value)
    }

    "return OK otherwise" in new LoggedTestCase {
      val requestObject  = routeRepresentation.request.random
      val responseObject = routeRepresentation.response.random

      when(projectBulkAssignService.assign(auth, organizationSlug, userId, requestObject.value))
        .thenReturn(io(ProjectBulkAssignResult.Assigned(responseObject.value)))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.OK
        responseAs[Json] shouldEqualJson responseObject.getJsonString
      }

      verify(projectBulkAssignService).assign(auth, organizationSlug, userId, requestObject.value)
    }
  }
}
