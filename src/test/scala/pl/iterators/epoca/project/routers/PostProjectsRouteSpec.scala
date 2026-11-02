package pl.iterators.epoca.project.routers

import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshaller._
import pl.iterators.baklava.core.model.{RouteRepresentation, RouteSecurity}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.project.services.ProjectCreateService
import pl.iterators.epoca.project.services.ProjectCreateService.{ProjectCreateRequest, ProjectCreateResult}
import pl.iterators.epoca.project.services.ProjectDtoService.ProjectDto
import pl.iterators.epoca.utils.RouteDocSpec

class PostProjectsRouteSpec extends RouteDocSpec {
  override val routeRepresentation: RouteRepresentation[ProjectCreateRequest, ProjectDto] =
    RouteRepresentation[ProjectCreateRequest, ProjectDto](
      "Create new project",
      "POST",
      "/organizations/{organizationSlug}/projects",
      authentication = List(RouteSecurity.Bearer())
    )

  val auth: AuthContext                  = allGenerators[AuthContext].normal.generate
  val organizationSlug: OrganizationSlug = allGenerators[OrganizationSlug].normal.generate
  val routePath: String                  = routeRepresentation.path.replace("{organizationSlug}", organizationSlug)

  trait NoLoggedTestCase extends BaseScope {
    override lazy val authContextForTest: Option[AuthContext] = None
  }

  trait LoggedTestCase extends BaseScope {
    override lazy val projectCreateService: ProjectCreateService = vmock[ProjectCreateService]

    override val authContextForTest: Option[AuthContext] = Some(auth)
  }

  routeRepresentation.name should {
    "be inaccessible for no-logged user" in new NoLoggedTestCase {
      TestRequest(routePath) ~> allRoutes ~> check {
        response.status shouldBeInaccessible
      }
    }

    "return BadRequest when incoming request is not json" in new LoggedTestCase {
      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.BadRequest
      }
    }

    "return NotFound when service returns OrganizationNotFound" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random

      when(projectCreateService.create(auth, organizationSlug, requestObject.value))
        .thenReturn(io(ProjectCreateResult.OrganizationNotFound))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(projectCreateService).create(auth, organizationSlug, requestObject.value)
    }

    "return Conflict when service returns ProjectNameNotUnique" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random

      when(projectCreateService.create(auth, organizationSlug, requestObject.value))
        .thenReturn(io(ProjectCreateResult.ProjectNameNotUnique))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.Conflict
      }

      verify(projectCreateService).create(auth, organizationSlug, requestObject.value)
    }

    "return NotFound when service returns NoAccessToOrganization" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random
      when(projectCreateService.create(auth, organizationSlug, requestObject.value))
        .thenReturn(io(ProjectCreateResult.NoAccessToOrganization))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(projectCreateService).create(auth, organizationSlug, requestObject.value)
    }

    "return ServiceUnavailable with retry guidance when the database lock times out" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random
      when(projectCreateService.create(auth, organizationSlug, requestObject.value))
        .thenReturn(cats.effect.IO.raiseError[ProjectCreateResult](new java.sql.SQLException("sensitive database detail", "55P03")))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.ServiceUnavailable
        header("Retry-After").map(_.value) shouldEqual Some("1")
        responseAs[String].contains("sensitive database detail") shouldEqual false
      }

      verify(projectCreateService).create(auth, organizationSlug, requestObject.value)
    }

    "return InternalServerError without retry guidance for another database failure" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random
      when(projectCreateService.create(auth, organizationSlug, requestObject.value))
        .thenReturn(cats.effect.IO.raiseError[ProjectCreateResult](new java.sql.SQLException("sensitive database detail", "XX000")))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.InternalServerError
        header("Retry-After") shouldEqual None
        responseAs[String].contains("sensitive database detail") shouldEqual false
      }

      verify(projectCreateService).create(auth, organizationSlug, requestObject.value)
    }

    "return Created with service data otherwise" in new LoggedTestCase {
      val requestObject  = routeRepresentation.request.random
      val responseObject = routeRepresentation.response.random
      when(projectCreateService.create(auth, organizationSlug, requestObject.value))
        .thenReturn(io(ProjectCreateResult.Created(responseObject.value)))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.Created
        responseAs[Json] shouldEqualJson responseObject.getJsonString
      }

      verify(projectCreateService).create(auth, organizationSlug, requestObject.value)
    }
  }
}
