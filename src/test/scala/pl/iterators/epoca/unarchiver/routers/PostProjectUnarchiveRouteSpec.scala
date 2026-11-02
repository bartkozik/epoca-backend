package pl.iterators.epoca.unarchiver.routers

import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshaller._
import pl.iterators.baklava.core.model.{RouteRepresentation, RouteSecurity}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.project.domain.Project
import pl.iterators.epoca.project.domain.ProjectDomain.ProjectId
import pl.iterators.epoca.unarchiver.services.ProjectUnarchiveService
import pl.iterators.epoca.unarchiver.services.ProjectUnarchiveService.ProjectUnarchiveResult
import pl.iterators.epoca.utils.RouteDocSpec

class PostProjectUnarchiveRouteSpec extends RouteDocSpec {
  override val routeRepresentation: RouteRepresentation[Unit, Project] =
    RouteRepresentation[Unit, Project](
      "Unarchive previously archived project - if related client is archived, it is also unarchived",
      "POST",
      "/organizations/{organizationSlug}/projects/{projectId}/unarchive",
      authentication = List(RouteSecurity.Bearer())
    )

  val auth: AuthContext                  = allGenerators[AuthContext].normal.generate
  val organizationSlug: OrganizationSlug = allGenerators[OrganizationSlug].normal.generate
  val projectId: ProjectId               = allGenerators[ProjectId].normal.generate
  val routePath: String = routeRepresentation.path
    .replace("{organizationSlug}", organizationSlug)
    .replace("{projectId}", projectId.toString)

  trait NoLoggedTestCase extends BaseScope {
    override lazy val authContextForTest: Option[AuthContext] = None
  }

  trait LoggedTestCase extends BaseScope {
    override lazy val projectUnarchiveService: ProjectUnarchiveService = vmock[ProjectUnarchiveService]

    override val authContextForTest: Option[AuthContext] = Some(auth)
  }

  routeRepresentation.name should {
    "be inaccessible for no-logged user" in new NoLoggedTestCase {
      TestRequest(routePath) ~> allRoutes ~> check {
        response.status shouldBeInaccessible
      }
    }

    "return NotFound when service returns OrganizationNotFound" in new LoggedTestCase {
      when(projectUnarchiveService.unarchive(auth, organizationSlug, projectId))
        .thenReturn(io(ProjectUnarchiveResult.OrganizationNotFound))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(projectUnarchiveService).unarchive(auth, organizationSlug, projectId)
    }

    "return Conflict when service returns ProjectNameNotUnique" in new LoggedTestCase {
      when(projectUnarchiveService.unarchive(auth, organizationSlug, projectId))
        .thenReturn(io(ProjectUnarchiveResult.ProjectNameNotUnique))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.Conflict
      }

      verify(projectUnarchiveService).unarchive(auth, organizationSlug, projectId)
    }

    "return Conflict when service returns ClientNameNotUnique" in new LoggedTestCase {
      when(projectUnarchiveService.unarchive(auth, organizationSlug, projectId))
        .thenReturn(io(ProjectUnarchiveResult.ClientNameNotUnique))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.Conflict
      }

      verify(projectUnarchiveService).unarchive(auth, organizationSlug, projectId)
    }

    "return NotFound when service returns NoAccessToOrganization" in new LoggedTestCase {
      when(projectUnarchiveService.unarchive(auth, organizationSlug, projectId))
        .thenReturn(io(ProjectUnarchiveResult.NoAccessToOrganization))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(projectUnarchiveService).unarchive(auth, organizationSlug, projectId)
    }

    "return NotFound when service returns ProjectNotFound" in new LoggedTestCase {
      when(projectUnarchiveService.unarchive(auth, organizationSlug, projectId))
        .thenReturn(io(ProjectUnarchiveResult.ProjectNotFound))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(projectUnarchiveService).unarchive(auth, organizationSlug, projectId)
    }

    "return NotFound when service returns ClientNotFound" in new LoggedTestCase {
      when(projectUnarchiveService.unarchive(auth, organizationSlug, projectId))
        .thenReturn(io(ProjectUnarchiveResult.ClientNotFound))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(projectUnarchiveService).unarchive(auth, organizationSlug, projectId)
    }

    "return Forbidden when service returns InsufficientRole" in new LoggedTestCase {
      when(projectUnarchiveService.unarchive(auth, organizationSlug, projectId))
        .thenReturn(io(ProjectUnarchiveResult.InsufficientRole))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
      }

      verify(projectUnarchiveService).unarchive(auth, organizationSlug, projectId)
    }

    "return Unarchived with service data otherwise" in new LoggedTestCase {
      val responseObject = routeRepresentation.response.random
      when(projectUnarchiveService.unarchive(auth, organizationSlug, projectId))
        .thenReturn(io(ProjectUnarchiveResult.Unarchived(responseObject.value)))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.Created
        responseAs[Json] shouldEqualJson responseObject.getJsonString
      }

      verify(projectUnarchiveService).unarchive(auth, organizationSlug, projectId)
    }
  }
}
