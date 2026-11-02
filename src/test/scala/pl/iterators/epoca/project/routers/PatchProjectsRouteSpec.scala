package pl.iterators.epoca.project.routers

import org.apache.pekko.http.scaladsl.model.StatusCodes
import pl.iterators.baklava.core.model.{RouteRepresentation, RouteSecurity}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.project.domain.Project
import pl.iterators.epoca.project.domain.ProjectDomain.ProjectId
import pl.iterators.epoca.project.services.ProjectUpdateService
import pl.iterators.epoca.project.services.ProjectUpdateService.{ProjectUpdateRequest, ProjectUpdateResult}
import pl.iterators.epoca.utils.RouteDocSpec

class PatchProjectsRouteSpec extends RouteDocSpec {
  override val routeRepresentation: RouteRepresentation[ProjectUpdateRequest, Project] =
    RouteRepresentation[ProjectUpdateRequest, Project](
      "Update project",
      "PATCH",
      "/organizations/{organizationSlug}/projects/{projectId}",
      authentication = List(RouteSecurity.Bearer())
    )

  val auth: AuthContext                  = allGenerators[AuthContext].normal.generate
  val projectId: ProjectId               = allGenerators[ProjectId].normal.generate
  val organizationSlug: OrganizationSlug = allGenerators[OrganizationSlug].normal.generate
  val routePath: String = routeRepresentation.path.replace("{organizationSlug}", organizationSlug).replace("{projectId}", projectId.toString)

  trait NoLoggedTestCase extends BaseScope {
    override lazy val authContextForTest: Option[AuthContext] = None
  }

  trait LoggedTestCase extends BaseScope {
    override lazy val projectUpdateService: ProjectUpdateService = vmock[ProjectUpdateService]

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

    "return NotFound when service returns NoAccessToOrganization" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random
      when(projectUpdateService.update(auth, organizationSlug, projectId, requestObject.value))
        .thenReturn(io(ProjectUpdateResult.NoAccessToOrganization))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(projectUpdateService).update(auth, organizationSlug, projectId, requestObject.value)
    }

    "return Conflict when service returns ProjectNameNotUnique" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random
      when(projectUpdateService.update(auth, organizationSlug, projectId, requestObject.value))
        .thenReturn(io(ProjectUpdateResult.ProjectNameNotUnique))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.Conflict
      }

      verify(projectUpdateService).update(auth, organizationSlug, projectId, requestObject.value)
    }

    "return Forbidden when service returns NotProjectOwner" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random
      when(projectUpdateService.update(auth, organizationSlug, projectId, requestObject.value))
        .thenReturn(io(ProjectUpdateResult.NotProjectOwner))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
      }

      verify(projectUpdateService).update(auth, organizationSlug, projectId, requestObject.value)
    }

    "return NotFound when service returns OrganizationNotFound" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random
      when(projectUpdateService.update(auth, organizationSlug, projectId, requestObject.value))
        .thenReturn(io(ProjectUpdateResult.OrganizationNotFound))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(projectUpdateService).update(auth, organizationSlug, projectId, requestObject.value)
    }

    "return NotFound when service returns ProjectNotFound" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random
      when(projectUpdateService.update(auth, organizationSlug, projectId, requestObject.value))
        .thenReturn(io(ProjectUpdateResult.ProjectNotFound))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(projectUpdateService).update(auth, organizationSlug, projectId, requestObject.value)
    }

    "return OK with service data otherwise" in new LoggedTestCase {
      val requestObject  = routeRepresentation.request.random
      val responseObject = routeRepresentation.response.random
      when(projectUpdateService.update(auth, organizationSlug, projectId, requestObject.value))
        .thenReturn(io(ProjectUpdateResult.Updated(responseObject.value)))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.OK
        responseAs[Json] shouldEqualJson responseObject.getJsonString
      }

      verify(projectUpdateService).update(auth, organizationSlug, projectId, requestObject.value)
    }
  }
}
