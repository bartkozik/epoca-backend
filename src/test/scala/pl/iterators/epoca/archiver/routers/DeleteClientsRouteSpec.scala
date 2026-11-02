package pl.iterators.epoca.archiver.routers

import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshaller._
import pl.iterators.baklava.core.model.{RouteRepresentation, RouteSecurity}
import pl.iterators.epoca.archiver.services.ClientDeleteService
import pl.iterators.epoca.archiver.services.ClientDeleteService.ClientDeleteResult
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.client.domain.ClientDomain.ClientId
import pl.iterators.epoca.client.domain.{Client, ClientDomain}
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.utils.RouteDocSpec

class DeleteClientsRouteSpec extends RouteDocSpec {
  override val routeRepresentation: RouteRepresentation[Unit, Client] =
    RouteRepresentation[Unit, Client](
      "Delete client",
      "DELETE",
      "/organizations/{organizationSlug}/clients/{clientId}",
      authentication = List(RouteSecurity.Bearer())
    )

  val auth: AuthContext                  = allGenerators[AuthContext].normal.generate
  val organizationSlug: OrganizationSlug = allGenerators[OrganizationSlug].normal.generate
  val clientId: ClientDomain.ClientId    = allGenerators[ClientId].normal.generate
  val routePath: String = routeRepresentation.path
    .replace("{organizationSlug}", organizationSlug)
    .replace("{clientId}", clientId.toString)

  trait NoLoggedTestCase extends BaseScope {
    override lazy val authContextForTest: Option[AuthContext] = None
  }

  trait LoggedTestCase extends BaseScope {
    override lazy val clientDeleteService: ClientDeleteService = vmock[ClientDeleteService]

    override val authContextForTest: Option[AuthContext] = Some(auth)
  }

  routeRepresentation.name should {
    "be inaccessible for no-logged user" in new NoLoggedTestCase {
      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldBeInaccessible
      }
    }

    "return NotFound when service returns NoAccessToOrganization" in new LoggedTestCase {
      when(clientDeleteService.delete(auth, organizationSlug, clientId))
        .thenReturn(io(ClientDeleteResult.NoAccessToOrganization))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(clientDeleteService).delete(auth, organizationSlug, clientId)
    }

    "return NotFound when service returns OrganizationNotFound" in new LoggedTestCase {
      when(clientDeleteService.delete(auth, organizationSlug, clientId))
        .thenReturn(io(ClientDeleteResult.OrganizationNotFound))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(clientDeleteService).delete(auth, organizationSlug, clientId)
    }

    "return NotFound when service returns ClientNotFound" in new LoggedTestCase {
      when(clientDeleteService.delete(auth, organizationSlug, clientId))
        .thenReturn(io(ClientDeleteResult.ClientNotFound))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(clientDeleteService).delete(auth, organizationSlug, clientId)
    }

    "return Forbidden when service returns InsufficientRole" in new LoggedTestCase {
      when(clientDeleteService.delete(auth, organizationSlug, clientId))
        .thenReturn(io(ClientDeleteResult.InsufficientRole))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
      }

      verify(clientDeleteService).delete(auth, organizationSlug, clientId)
    }

    "return ServiceUnavailable with retry guidance when the database lock times out" in new LoggedTestCase {
      when(clientDeleteService.delete(auth, organizationSlug, clientId))
        .thenReturn(cats.effect.IO.raiseError[ClientDeleteResult](new java.sql.SQLException("sensitive database detail", "55P03")))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.ServiceUnavailable
        header("Retry-After").map(_.value) shouldEqual Some("1")
        responseAs[String].contains("sensitive database detail") shouldEqual false
      }

      verify(clientDeleteService).delete(auth, organizationSlug, clientId)
    }

    "return NoContent otherwise" in new LoggedTestCase {
      when(clientDeleteService.delete(auth, organizationSlug, clientId))
        .thenReturn(io(ClientDeleteResult.Deleted))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NoContent
      }

      verify(clientDeleteService).delete(auth, organizationSlug, clientId)
    }
  }
}
