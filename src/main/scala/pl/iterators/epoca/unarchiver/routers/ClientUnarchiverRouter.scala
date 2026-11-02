package pl.iterators.epoca.unarchiver.routers

import cats.effect.unsafe.IORuntime
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Route
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.client.domain.ClientDomain.ClientId
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.unarchiver.services.ClientUnarchiveService
import pl.iterators.epoca.unarchiver.services.ClientUnarchiveService.ClientUnarchiveResult
import pl.iterators.epoca.utils.http.BaseRouter
import pl.iterators.epoca.utils.log.TracingContext

class ClientUnarchiverRouter(clientUnarchiveService: ClientUnarchiveService)(implicit runtime: IORuntime) extends BaseRouter {
  import ClientUnarchiverRouter._

  def authRoutes(auth: AuthContext)(implicit tracingContext: TracingContext): Route =
    pathPrefix(organizationsPath / Segment.as[OrganizationSlug]) { organizationSlug =>
      pathPrefix(clientsPath / JavaUUID.as[ClientId] / unarchivePath) { clientId =>
        (post & pathEndOrSingleSlash) {
          complete(clientUnarchiveService.unarchive(auth, organizationSlug, clientId)) {
            case ClientUnarchiveResult.Unarchived(client)     => StatusCodes.Created  -> client
            case ClientUnarchiveResult.NoAccessToOrganization => StatusCodes.NotFound
            case ClientUnarchiveResult.OrganizationNotFound   => StatusCodes.NotFound
            case ClientUnarchiveResult.ClientNotFound         => StatusCodes.NotFound
            case ClientUnarchiveResult.InsufficientRole       => StatusCodes.Forbidden
            case ClientUnarchiveResult.NameNotUnique          => StatusCodes.Conflict -> "Project name is already in use"
          }
        }
      }
    }
}

object ClientUnarchiverRouter {
  val organizationsPath = "organizations"
  val clientsPath       = "clients"
  val unarchivePath     = "unarchive"
}
