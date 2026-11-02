package pl.iterators.epoca.archiver.routers

import cats.effect.unsafe.IORuntime
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Route
import pl.iterators.epoca.archiver.services.ClientDeleteService
import pl.iterators.epoca.archiver.services.ClientDeleteService.ClientDeleteResult
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.client.domain.ClientDomain.ClientId
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.utils.http.BaseRouter
import pl.iterators.epoca.utils.log.TracingContext

class ClientArchiverRouter(clientDeleteService: ClientDeleteService)(implicit runtime: IORuntime) extends BaseRouter {
  import ClientArchiverRouter._

  def authRoutes(auth: AuthContext)(implicit tracingContext: TracingContext): Route =
    pathPrefix(organizationsPath / Segment.as[OrganizationSlug]) { organizationSlug =>
      pathPrefix(clientsPath / JavaUUID.as[ClientId]) { clientId =>
        (delete & pathEndOrSingleSlash) {
          complete(clientDeleteService.delete(auth, organizationSlug, clientId)) {
            case ClientDeleteResult.Deleted                => StatusCodes.NoContent
            case ClientDeleteResult.NoAccessToOrganization => StatusCodes.NotFound
            case ClientDeleteResult.OrganizationNotFound   => StatusCodes.NotFound
            case ClientDeleteResult.ClientNotFound         => StatusCodes.NotFound
            case ClientDeleteResult.InsufficientRole       => StatusCodes.Forbidden
          }
        }
      }
    }
}

object ClientArchiverRouter {
  val organizationsPath = "organizations"
  val clientsPath       = "clients"

}
