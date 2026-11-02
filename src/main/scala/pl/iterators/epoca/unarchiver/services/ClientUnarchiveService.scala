package pl.iterators.epoca.unarchiver.services

import cats.effect.unsafe.IORuntime
import cats.effect.{Clock, IO}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.client.domain.Client
import pl.iterators.epoca.client.domain.ClientDomain.{ClientId, ClientModifiedAt}
import pl.iterators.epoca.client.repositories.ClientRepository
import pl.iterators.epoca.client.repositories.ClientRepository.ClientFilters
import pl.iterators.epoca.organization.domain.Organization
import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationId, OrganizationSlug}
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.unarchiver.services.ClientUnarchiveService.ClientUnarchiveResult
import pl.iterators.epoca.unarchiver.services.ClientUnarchiveService.ClientUnarchiveResult._
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.service.SealedMonadServiceIODBIO
import pl.iterators.sealedmonad.Sealed
import pl.iterators.sealedmonad.syntax._
import slick.dbio.DBIO

class ClientUnarchiveService(
  transactor: PostgresIOTransactor,
  organizationRepository: OrganizationRepository,
  organizationUserRepository: OrganizationUserRepository,
  clientRepository: ClientRepository
)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIODBIO[ClientUnarchiveResult](ioRuntime)
    with LoggingSupport {

  def unarchive(
    authContext: AuthContext,
    organizationSlug: OrganizationSlug,
    id: ClientId
  ): IO[ClientUnarchiveResult] = {
    val query = for {
      _                <- Logger[DBIO].info(s"auth.id=${authContext.id}, organization=$organizationSlug, clientId=$id").seal
      organization     <- findOrganization(organizationSlug)
      _                <- checkAccessToOrganization(authContext, organization.id)
      client           <- findClient(organization, id)
      _                <- validateUniqueClientName(organization, client)
      unarchivedClient <- prepareUnarchived(authContext, client)
      unarchivedClient <- unarchiveClient(unarchivedClient)
    } yield Unarchived(unarchivedClient)
    transactor.executeTransactionally(query.run)
  }

  private def findOrganization(slug: OrganizationSlug): StepDBIO[Organization] =
    organizationRepository
      .find(OrganizationFilters(slug = Some(slug)))
      .valueOrF {
        Logger[DBIO].error(s"organization $slug does not exist").as(OrganizationNotFound)
      }

  private def checkAccessToOrganization(authContext: AuthContext, id: OrganizationId): StepDBIO[Unit] =
    if (authContext.isSuperAdmin) Sealed.liftF(())
    else
      organizationUserRepository
        .find(OrganizationUserFilters(organizationId = Some(id), userId = Some(authContext.id)))
        .valueOrF {
          Logger[DBIO].warning(s"user ${authContext.id} has no access to organization $id").as(NoAccessToOrganization)
        }
        .ensureF(
          _.hasManagementRole,
          Logger[DBIO]
            .warning(s"user ${authContext.id} roles are insufficient to access organization $id")
            .as(InsufficientRole)
        )
        .void

  private def findClient(organization: Organization, id: ClientId): StepDBIO[Client] =
    clientRepository
      .find(ClientFilters(id = Some(id), organizationIds = Some(Seq(organization.id)), forUpdate = true, includeArchived = true))
      .valueOrF {
        Logger[DBIO].error(s"client $id does not exist").as(ClientNotFound)
      }

  private def validateUniqueClientName(organization: Organization, client: Client): StepDBIO[Unit] =
    clientRepository
      .find(ClientFilters(organizationIds = Some(Seq(organization.id)), name = Some(client.name)))
      .ensureF(
        _.isEmpty,
        Logger[DBIO].error(s"client ${client.id} can't be unarchived - another client with the same name is active").as(NameNotUnique)
      )
      .void

  private def prepareUnarchived(authContext: AuthContext, client: Client): StepDBIO[Client] = {
    val res: DBIO[Client] = Clock[DBIO].instantNow.map { now =>
      val when = ClientModifiedAt(now)
      client.copy(archivedAt = None, archivedBy = None, modifiedAt = when, modifiedBy = authContext.id)
    }
    res.seal
  }

  private def unarchiveClient(client: Client): StepDBIO[Client] =
    clientRepository.update(client).valueOr(ClientNotFound)
}

object ClientUnarchiveService {
  sealed trait ClientUnarchiveResult
  object ClientUnarchiveResult {
    final case class Unarchived(client: Client) extends ClientUnarchiveResult
    final case object NoAccessToOrganization    extends ClientUnarchiveResult
    final case object OrganizationNotFound      extends ClientUnarchiveResult
    final case object InsufficientRole          extends ClientUnarchiveResult
    final case object ClientNotFound            extends ClientUnarchiveResult
    final case object NameNotUnique             extends ClientUnarchiveResult
  }

}
