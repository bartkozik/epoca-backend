package pl.iterators.epoca.client.services

import cats.effect._
import cats.effect.unsafe.IORuntime
import cats.implicits._
import io.scalaland.chimney.dsl._
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.client.domain.Client
import pl.iterators.epoca.client.domain.ClientDomain._
import pl.iterators.epoca.client.repositories.ClientRepository
import pl.iterators.epoca.client.repositories.ClientRepository.ClientFilters
import pl.iterators.epoca.client.services.ClientUpdateService.{ClientUpdateRequest, ClientUpdateResult}
import pl.iterators.epoca.organization.domain.Organization
import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationId, OrganizationSlug}
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.service.SealedMonadServiceIODBIO
import pl.iterators.sealedmonad.Sealed
import pl.iterators.sealedmonad.syntax._
import slick.dbio.DBIO

class ClientUpdateService(
  transactor: PostgresIOTransactor,
  organizationRepository: OrganizationRepository,
  organizationUserRepository: OrganizationUserRepository,
  clientRepository: ClientRepository
)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIODBIO[ClientUpdateResult](ioRuntime)
    with LoggingSupport {

  def update(
    authContext: AuthContext,
    organizationSlug: OrganizationSlug,
    id: ClientId,
    request: ClientUpdateRequest
  ): IO[ClientUpdateResult] =
    transactor.execute {
      (for {
        _            <- Logger[DBIO].info(s"auth.id=${authContext.id}, organization=$organizationSlug, clientId=$id").seal
        organization <- findOrganization(organizationSlug)
        _            <- checkAccessToOrganization(authContext, organization.id)
        client       <- findClient(organization, id)
        _            <- verifyClientNameUnique(request, organization, client)
        toUpdate     <- prepareClient(authContext, client, request)
        result       <- updateClient(toUpdate)
      } yield ClientUpdateResult.Updated(result)).run
    }

  private def findOrganization(slug: OrganizationSlug): StepDBIO[Organization] =
    organizationRepository
      .find(OrganizationFilters(slug = Some(slug)))
      .valueOrF(Logger[DBIO].error(s"organization $slug does not exist").as(ClientUpdateResult.OrganizationNotFound))

  private def checkAccessToOrganization(authContext: AuthContext, id: OrganizationId): StepDBIO[Unit] =
    if (authContext.isSuperAdmin) Sealed.liftF(())
    else
      organizationUserRepository
        .find(OrganizationUserFilters(organizationId = Some(id), userId = Some(authContext.id)))
        .valueOrF(
          Logger[DBIO]
            .warning(s"user ${authContext.id} has no access to organization $id")
            .as(ClientUpdateResult.NoAccessToOrganization)
        )
        .ensureF(
          _.hasManagementRole,
          Logger[DBIO]
            .warning(s"user ${authContext.id} has insufficient role in organization $id")
            .as(ClientUpdateResult.InsufficientRole)
        )
        .void

  private def findClient(organization: Organization, id: ClientId): StepDBIO[Client] =
    clientRepository
      .find(ClientFilters(organizationIds = Some(Seq(organization.id)), id = Some(id)))
      .valueOrF(Logger[DBIO].error(s"client $id does not exist").as(ClientUpdateResult.ClientNotFound))

  private def verifyClientNameUnique(
    request: ClientUpdateRequest,
    organization: Organization,
    client: Client
  ): StepDBIO[Unit] =
    request.name match {
      case None => Sealed.liftF(())
      case Some(name) =>
        clientRepository
          .find(ClientFilters(name = Some(name), organizationIds = Some(Seq(organization.id))))
          .ensureF(
            c => c.isEmpty || c.exists(_.id == client.id),
            Logger[DBIO].info(s"Client name duplication org ${organization.id}, name ${request.name}").as(ClientUpdateResult.DuplicatedName)
          )
          .void
    }

  private def prepareClient(
    authContext: AuthContext,
    client: Client,
    request: ClientUpdateRequest
  ): StepDBIO[Client] =
    Clock[DBIO].instantNow.seal.map { now =>
      client
        .patchUsing(request)
        .copy(modifiedAt = ClientModifiedAt(now), modifiedBy = authContext.id)
    }

  private def updateClient(client: Client): StepDBIO[Client] =
    (Logger[DBIO].info(s"successfully updated client ${client.id}") *>
      clientRepository
        .update(client))
      .valueOr(ClientUpdateResult.ClientNotFound)
}

object ClientUpdateService {
  final case class ClientUpdateRequest(
    name: Option[ClientName],
    color: Option[ClientColor],
    logo: Option[ClientLogo],
    texture: Option[ClientTexture],
    `type`: Option[ClientType])

  sealed trait ClientUpdateResult
  object ClientUpdateResult {
    final case class Updated(client: Client) extends ClientUpdateResult
    final case object NoAccessToOrganization extends ClientUpdateResult
    final case object OrganizationNotFound   extends ClientUpdateResult
    final case object DuplicatedName         extends ClientUpdateResult
    final case object ClientNotFound         extends ClientUpdateResult
    final case object InsufficientRole       extends ClientUpdateResult
  }

}
