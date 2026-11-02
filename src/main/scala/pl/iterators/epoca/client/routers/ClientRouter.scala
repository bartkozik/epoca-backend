package pl.iterators.epoca.client.routers

import cats.effect.unsafe.IORuntime
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Route
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.client.domain.ClientDomain.{ClientId, ClientType}
import pl.iterators.epoca.client.repositories.ClientRepository.ClientFilters
import pl.iterators.epoca.client.services.ClientCreateService.{ClientCreateRequest, ClientCreateResult}
import pl.iterators.epoca.client.services.ClientListService.ClientListResult
import pl.iterators.epoca.client.services.ClientUpdateService.{ClientUpdateRequest, ClientUpdateResult}
import pl.iterators.epoca.client.services._
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.utils.http.BaseRouter
import pl.iterators.epoca.utils.log.TracingContext

class ClientRouter(
  clientCreateService: ClientCreateService,
  clientListService: ClientListService,
  clientUpdateService: ClientUpdateService
)(implicit runtime: IORuntime)
    extends BaseRouter {

  import ClientRouter._
  def authRoutes(auth: AuthContext)(implicit tracingContext: TracingContext): Route =
    pathPrefix(organizationsPath / Segment.as[OrganizationSlug] / clientsPath) { organizationSlug =>
      (post & pathEndOrSingleSlash & entity(as[ClientCreateRequest])) { request =>
        complete(clientCreateService.create(auth, organizationSlug, request)) {
          case ClientCreateResult.Created(client)        => StatusCodes.Created              -> client
          case ClientCreateResult.NoAccessToOrganization => StatusCodes.NotFound
          case ClientCreateResult.DuplicatedName         => StatusCodes.UnprocessableContent -> "Client with provided name already exists"
          case ClientCreateResult.OrganizationNotFound   => StatusCodes.NotFound
          case ClientCreateResult.InsufficientRole       => StatusCodes.Forbidden
        }
      } ~ (get & pathEndOrSingleSlash & pagination & parameters(
        "id".as[ClientId].?,
        "type".as[ClientType].?,
        "includeArchived".as[Boolean].?(false),
        "includeArchivedConnections".as[Boolean].?(false)
      )) {
        (
          pagination,
          id,
          `type`,
          includeArchived,
          includeArchivedConnections
        ) =>
          val filters = ClientFilters(id = id, `type` = `type`, pageSizeAndNumber = pagination, includeArchived = includeArchived)
          complete(clientListService.list(auth, organizationSlug, filters, includeArchivedConnections)) {
            case ClientListResult.Ok(clientListDtos)     => StatusCodes.OK -> clientListDtos
            case ClientListResult.NoAccessToOrganization => StatusCodes.NotFound
            case ClientListResult.OrganizationNotFound   => StatusCodes.NotFound
          }
      } ~ pathPrefix(JavaUUID.as[ClientId]) { id =>
        (patch & pathEndOrSingleSlash & entity(as[ClientUpdateRequest])) { request =>
          complete(clientUpdateService.update(auth, organizationSlug, id, request)) {
            case ClientUpdateResult.Updated(client)        => StatusCodes.OK                   -> client
            case ClientUpdateResult.NoAccessToOrganization => StatusCodes.NotFound
            case ClientUpdateResult.DuplicatedName         => StatusCodes.UnprocessableContent -> "Client with provided name already exists"
            case ClientUpdateResult.OrganizationNotFound   => StatusCodes.NotFound
            case ClientUpdateResult.ClientNotFound         => StatusCodes.NotFound
            case ClientUpdateResult.InsufficientRole       => StatusCodes.Forbidden
          }
        }
      }
    }
}

object ClientRouter {
  val organizationsPath = "organizations"
  val clientsPath       = "clients"
}
