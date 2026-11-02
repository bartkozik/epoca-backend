package pl.iterators.epoca.unarchiver.routers

import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshaller._
import pl.iterators.baklava.core.model.{RouteRepresentation, RouteSecurity}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.client.domain.Client
import pl.iterators.epoca.client.domain.ClientDomain.ClientId
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.unarchiver.services.ClientUnarchiveService
import pl.iterators.epoca.unarchiver.services.ClientUnarchiveService.ClientUnarchiveResult
import pl.iterators.epoca.utils.RouteDocSpec

class PostClientUnarchiveRouteSpec extends RouteDocSpec {
  override val routeRepresentation: RouteRepresentation[Unit, Client] =
    RouteRepresentation[Unit, Client](
      "Unarchive previously archived client",
      "POST",
      "/organizations/{organizationSlug}/clients/{clientId}/unarchive",
      authentication = List(RouteSecurity.Bearer())
    )

  val auth: AuthContext                  = allGenerators[AuthContext].normal.generate
  val organizationSlug: OrganizationSlug = allGenerators[OrganizationSlug].normal.generate
  val clientId: ClientId                 = allGenerators[ClientId].normal.generate
  val routePath: String = routeRepresentation.path
    .replace("{organizationSlug}", organizationSlug)
    .replace("{clientId}", clientId.toString)

  trait NoLoggedTestCase extends BaseScope {
    override lazy val authContextForTest: Option[AuthContext] = None
  }

  trait LoggedTestCase extends BaseScope {
    override lazy val clientUnarchiveService: ClientUnarchiveService = vmock[ClientUnarchiveService]

    override val authContextForTest: Option[AuthContext] = Some(auth)
  }

  routeRepresentation.name should {
    "be inaccessible for no-logged user" in new NoLoggedTestCase {
      TestRequest(routePath) ~> allRoutes ~> check {
        response.status shouldBeInaccessible
      }
    }

    "return NotFound when service returns OrganizationNotFound" in new LoggedTestCase {
      when(clientUnarchiveService.unarchive(auth, organizationSlug, clientId))
        .thenReturn(io(ClientUnarchiveResult.OrganizationNotFound))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(clientUnarchiveService).unarchive(auth, organizationSlug, clientId)
    }

    "return NotFound when service returns NoAccessToOrganization" in new LoggedTestCase {
      when(clientUnarchiveService.unarchive(auth, organizationSlug, clientId))
        .thenReturn(io(ClientUnarchiveResult.NoAccessToOrganization))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(clientUnarchiveService).unarchive(auth, organizationSlug, clientId)
    }

    "return NotFound when service returns ClientNotFound" in new LoggedTestCase {
      when(clientUnarchiveService.unarchive(auth, organizationSlug, clientId))
        .thenReturn(io(ClientUnarchiveResult.ClientNotFound))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(clientUnarchiveService).unarchive(auth, organizationSlug, clientId)
    }

    "return Conflict when service returns NameNotUnique" in new LoggedTestCase {
      when(clientUnarchiveService.unarchive(auth, organizationSlug, clientId))
        .thenReturn(io(ClientUnarchiveResult.NameNotUnique))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.Conflict
      }

      verify(clientUnarchiveService).unarchive(auth, organizationSlug, clientId)
    }

    "return Forbidden when service returns InsufficientRole" in new LoggedTestCase {
      when(clientUnarchiveService.unarchive(auth, organizationSlug, clientId))
        .thenReturn(io(ClientUnarchiveResult.InsufficientRole))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
      }

      verify(clientUnarchiveService).unarchive(auth, organizationSlug, clientId)
    }

    "return Unarchived with service data otherwise" in new LoggedTestCase {
      val responseObject = routeRepresentation.response.random
      when(clientUnarchiveService.unarchive(auth, organizationSlug, clientId))
        .thenReturn(io(ClientUnarchiveResult.Unarchived(responseObject.value)))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.Created
        responseAs[Json] shouldEqualJson responseObject.getJsonString
      }

      verify(clientUnarchiveService).unarchive(auth, organizationSlug, clientId)
    }
  }
}
