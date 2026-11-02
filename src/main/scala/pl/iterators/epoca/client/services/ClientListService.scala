package pl.iterators.epoca.client.services

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.client.domain.Client
import pl.iterators.epoca.client.domain.ClientDomain.ClientId
import pl.iterators.epoca.client.repositories.ClientRepository
import pl.iterators.epoca.client.repositories.ClientRepository.ClientFilters
import pl.iterators.epoca.client.services.ClientDtoService.ClientDto
import pl.iterators.epoca.client.services.ClientListService._
import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationId, OrganizationSlug}
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.project.repositories.ProjectRepository
import pl.iterators.epoca.project.repositories.ProjectRepository.ProjectFilters
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.utils.db.{PaginatedResult, PostgresIOTransactor}
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.service.SealedMonadServiceIODBIO
import pl.iterators.epoca.utils.transactor.IsolationLevel
import pl.iterators.sealedmonad.Sealed
import pl.iterators.sealedmonad.syntax._
import slick.dbio.DBIO

class ClientListService(
  transactor: PostgresIOTransactor,
  organizationRepository: OrganizationRepository,
  organizationUserRepository: OrganizationUserRepository,
  projectRepository: ProjectRepository,
  clientRepository: ClientRepository,
  clientDtoService: ClientDtoService
)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIODBIO[ClientListResult](ioRuntime)
    with LoggingSupport {

  def list(
    authContext: AuthContext,
    organizationSlug: OrganizationSlug,
    filter: ClientFilters,
    includeArchivedConnections: Boolean = false
  ): IO[ClientListResult] =
    transactor.executeTransactionally(IsolationLevel.RepeatableRead) {
      (for {
        organization     <- findOrganization(organizationSlug)
        orgUser          <- checkAccessToOrganization(authContext, organization.id)
        allowedClientIds <- listAllowedClientIds(orgUser, authContext, organization, includeArchivedConnections, filter.includeArchived)
        restrictedFilter = filter.copy(ids = allowedClientIds)
        clients      <- listClients(organization, restrictedFilter)
        clientsCount <- countClients(organization, restrictedFilter)
        userIdOpt = extractUserIdUnlessManagement(orgUser, authContext)
        clientListDtos <- clientDtoService.prepareDtos(clients, userIdOpt).seal
      } yield ClientListResult.Ok(PaginatedResult(clientsCount, clientListDtos))).run
    }

  private def findOrganization(slug: OrganizationSlug): StepDBIO[Organization] =
    organizationRepository
      .find(OrganizationFilters(slug = Some(slug)))
      .valueOrF {
        Logger[DBIO].error(s"organization $slug does not exist").as(ClientListResult.OrganizationNotFound)
      }

  private def checkAccessToOrganization(authContext: AuthContext, id: OrganizationId): StepDBIO[Option[OrganizationUser]] =
    if (authContext.isSuperAdmin) Sealed.liftF(None)
    else
      organizationUserRepository
        .find(OrganizationUserFilters(organizationId = Some(id), userId = Some(authContext.id)))
        .valueOrF {
          Logger[DBIO]
            .warning(s"user ${authContext.id} has no access to organization $id")
            .as(ClientListResult.NoAccessToOrganization)
        }
        .map(Option.apply)

  private def listAllowedClientIds(
    orgUser: Option[OrganizationUser],
    authContext: AuthContext,
    organization: Organization,
    includeArchivedConnections: Boolean,
    includeArchived: Boolean
  ): StepDBIO[Option[Seq[ClientId]]] =
    if (orgUser.exists(!_.hasManagementRole)) {
      val filters = if (includeArchivedConnections) {
        ProjectFilters(archivedConnectionsUserId = Some(authContext.id), organizationIds = Some(Seq(organization.id)))
      } else {
        ProjectFilters(userId = Some(authContext.id), organizationIds = Some(Seq(organization.id)))
      }
      val result: DBIO[Option[Seq[ClientId]]] = projectRepository
        .list(filters.copy(includeArchived = includeArchived))
        .map(projects => Some(projects.map(_.clientId).distinct))
      result.seal
    } else {
      Sealed.liftF(None)
    }

  private def listClients(organization: Organization, filter: ClientFilters): StepDBIO[Seq[Client]] =
    clientRepository.list(filter.copy(organizationIds = Some(Seq(organization.id)))).seal

  private def countClients(organization: Organization, filter: ClientFilters): StepDBIO[Int] =
    clientRepository.count(filter.copy(organizationIds = Some(Seq(organization.id)))).seal

  private def extractUserIdUnlessManagement(orgUser: Option[OrganizationUser], authContext: AuthContext): Option[UserId] =
    if (orgUser.exists(!_.hasManagementRole)) Some(authContext.id) else None
}

object ClientListService {
  sealed trait ClientListResult
  object ClientListResult {
    final case class Ok(data: PaginatedResult[ClientDto]) extends ClientListResult
    final case object NoAccessToOrganization              extends ClientListResult
    final case object OrganizationNotFound                extends ClientListResult
  }
}
