package pl.iterators.epoca.project.routers

import org.apache.pekko.http.scaladsl.model.StatusCodes
import pl.iterators.baklava.core.model.{RouteRepresentation, RouteSecurity}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.project.services.ProjectBulkDeassignService
import pl.iterators.epoca.project.services.ProjectBulkDeassignService.{
  ProjectBulkDeassignRequest,
  ProjectBulkDeassignResponse,
  ProjectBulkDeassignResult
}
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.utils.RouteDocSpec

class PatchProjectsAllUsersRouteSpec extends RouteDocSpec {
  override val routeRepresentation: RouteRepresentation[ProjectBulkDeassignRequest, ProjectBulkDeassignResponse] =
    RouteRepresentation[ProjectBulkDeassignRequest, ProjectBulkDeassignResponse](
      "Deassign user from multiple projects as Member. Returns number of added projects",
      "PATCH",
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
    override lazy val projectBulkDeassignService: ProjectBulkDeassignService = vmock[ProjectBulkDeassignService]

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

      when(projectBulkDeassignService.deassign(auth, organizationSlug, userId, requestObject.value))
        .thenReturn(io(ProjectBulkDeassignResult.OrganizationNotFound))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(projectBulkDeassignService).deassign(auth, organizationSlug, userId, requestObject.value)
    }

    "return NotFound when service returns NoAccessToOrganization" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random
      when(projectBulkDeassignService.deassign(auth, organizationSlug, userId, requestObject.value))
        .thenReturn(io(ProjectBulkDeassignResult.NoAccessToOrganization))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(projectBulkDeassignService).deassign(auth, organizationSlug, userId, requestObject.value)
    }

    "return OK otherwise" in new LoggedTestCase {
      val responseObject = routeRepresentation.response.random
      val requestObject  = routeRepresentation.request.random

      when(projectBulkDeassignService.deassign(auth, organizationSlug, userId, requestObject.value))
        .thenReturn(io(ProjectBulkDeassignResult.Deassigned(responseObject.value)))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.OK
        responseAs[Json] shouldEqualJson responseObject.getJsonString
      }

      verify(projectBulkDeassignService).deassign(auth, organizationSlug, userId, requestObject.value)
    }

  }
}
