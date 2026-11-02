package pl.iterators.epoca.client.services

import cats.effect._
import cats.effect.unsafe.IORuntime
import io.scalaland.chimney.dsl._
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.client.domain.Client
import pl.iterators.epoca.client.domain.ClientDomain._
import pl.iterators.epoca.client.repositories.ClientRepository
import pl.iterators.epoca.client.repositories.ClientRepository.ClientFilters
import pl.iterators.epoca.client.services.ClientCreateService.ClientCreateResult._
import pl.iterators.epoca.client.services.ClientCreateService.{ClientCreateRequest, ClientCreateResult}
import pl.iterators.epoca.organization.domain.Organization
import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationId, OrganizationSlug}
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.random.IdGenerator
import pl.iterators.epoca.utils.service.SealedMonadServiceIODBIO
import pl.iterators.sealedmonad.Sealed
import pl.iterators.sealedmonad.syntax._

class ClientCreateService(
  transactor: PostgresIOTransactor,
  organizationRepository: OrganizationRepository,
  organizationUserRepository: OrganizationUserRepository,
  clientRepository: ClientRepository
)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIODBIO[ClientCreateResult](ioRuntime)
    with LoggingSupport {

  def create(
    authContext: AuthContext,
    organizationSlug: OrganizationSlug,
    request: ClientCreateRequest
  ): IO[ClientCreateResult] =
    (for {
      _            <- Logger[IO].info(s"auth.id=${authContext.id}, organization=$organizationSlug").seal
      organization <- findOrganization(organizationSlug)
      _            <- checkAccessToOrganization(authContext, organization.id)
      _            <- verifyClientNameUnique(request, organization)
      client       <- prepareClient(authContext.id, organization, request)
      result       <- insertClient(client)
    } yield Created(result)).run

  private def findOrganization(slug: OrganizationSlug): StepIO[Organization] =
    transactor
      .execute {
        organizationRepository
          .find(OrganizationFilters(slug = Some(slug)))
      }
      .valueOrF {
        Logger[IO].error(s"organization $slug does not exist").as(OrganizationNotFound)
      }

  private def checkAccessToOrganization(authContext: AuthContext, id: OrganizationId): StepIO[Unit] =
    if (authContext.isSuperAdmin) Sealed.liftF(())
    else {
      val query = organizationUserRepository
        .find(OrganizationUserFilters(organizationId = Some(id), userId = Some(authContext.id)))

      transactor
        .execute(query)
        .valueOrF {
          Logger[IO].warning(s"user ${authContext.id} has no access to organization $id").as(NoAccessToOrganization)
        }
        .ensureF(_.hasManagementRole, Logger[IO].warning(s"user ${authContext.id} has insufficient roles").as(InsufficientRole))
        .void
    }

  private def verifyClientNameUnique(request: ClientCreateRequest, organization: Organization): StepIO[Unit] = {
    val query = clientRepository.find(ClientFilters(name = Some(request.name), organizationIds = Some(Seq(organization.id))))
    transactor
      .execute(query)
      .ensureF(_.isEmpty, Logger[IO].info(s"Client name duplication org ${organization.id}, name ${request.name}").as(DuplicatedName))
      .void
  }

  private def prepareClient(
    userId: UserId,
    organization: Organization,
    request: ClientCreateRequest
  ): StepIO[Client] =
    Clock[IO].instantNow.flatMap { now =>
      IdGenerator.nextId[IO, ClientId].map { clientId =>
        request
          .into[Client]
          .withFieldConst(_.id, clientId)
          .withFieldConst(_.organizationId, organization.id)
          .withFieldConst(_.createdAt, ClientCreatedAt(now))
          .withFieldConst(_.createdBy, userId)
          .withFieldConst(_.modifiedAt, ClientModifiedAt(now))
          .withFieldConst(_.modifiedBy, userId)
          .withFieldConst(_.archivedAt, None)
          .withFieldConst(_.archivedBy, None)
          .transform
      }
    }.seal

  private def insertClient(client: Client): StepIO[Client] =
    (Logger[IO].info(s"successfully created client ${client.id} in organization ${client.organizationId}") *>
      transactor.execute {
        clientRepository.insert(client)
      }).seal
}

object ClientCreateService {
  final case class ClientCreateRequest(
    name: ClientName,
    color: ClientColor,
    logo: ClientLogo,
    texture: ClientTexture,
    `type`: ClientType)

  sealed trait ClientCreateResult
  object ClientCreateResult {
    final case class Created(client: Client) extends ClientCreateResult
    final case object NoAccessToOrganization extends ClientCreateResult
    final case object InsufficientRole       extends ClientCreateResult
    final case object DuplicatedName         extends ClientCreateResult
    final case object OrganizationNotFound   extends ClientCreateResult
  }

}
