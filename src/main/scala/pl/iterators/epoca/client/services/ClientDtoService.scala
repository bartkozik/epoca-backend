package pl.iterators.epoca.client.services

import cats.effect.unsafe.IORuntime
import io.scalaland.chimney.dsl._
import pl.iterators.epoca.client.domain.Client
import pl.iterators.epoca.client.domain.ClientDomain._
import pl.iterators.epoca.client.services.ClientDtoService.ClientDto
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId
import pl.iterators.epoca.project.domain.Project
import pl.iterators.epoca.project.repositories.ProjectRepository
import pl.iterators.epoca.project.repositories.ProjectRepository.ProjectFilters
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.service.SealedMonadServiceIODBIO
import pl.iterators.sealedmonad.syntax._
import slick.dbio.DBIO

class ClientDtoService(projectRepository: ProjectRepository)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIODBIO(ioRuntime)
    with LoggingSupport {

  def prepareDtos(clients: Seq[Client], userId: Option[UserId]): DBIO[Seq[ClientDto]] =
    (for {
      projectCountMap <- getProjectCountMap(clients, userId)
    } yield prepareClientDtos(clients, projectCountMap)).run

  private def getProjectCountMap(clients: Seq[Client], userId: Option[UserId]): StepDBIO[Map[ClientId, ClientProjectCount]] =
    listProjects(clients, userId).map(_.groupBy(_.clientId).map { case (clientId, projects) => (clientId, ClientProjectCount(projects.length)) })

  private def listProjects(clients: Seq[Client], userId: Option[UserId]): StepDBIO[Seq[Project]] =
    projectRepository.list(ProjectFilters(clientIds = Some(clients.map(_.id)), userId = userId)).seal

  private def prepareClientDtos(clients: Seq[Client], projectCountMap: Map[ClientId, ClientProjectCount]): Seq[ClientDto] =
    clients.map(client => ClientDto.from(client, projectCountMap))
}

object ClientDtoService {
  final case class ClientDto(
    id: ClientId,
    name: ClientName,
    color: ClientColor,
    texture: ClientTexture,
    logo: ClientLogo,
    organizationId: OrganizationId,
    `type`: ClientType,
    projectCount: ClientProjectCount,
    createdAt: ClientCreatedAt,
    createdBy: UserId,
    modifiedAt: ClientModifiedAt,
    modifiedBy: UserId,
    archivedAt: Option[ClientArchivedAt])

  object ClientDto {
    def from(client: Client, projectCountMap: Map[ClientId, ClientProjectCount]): ClientDto =
      client
        .into[ClientDto]
        .withFieldComputed(_.projectCount, client => projectCountMap.getOrElse(client.id, ClientProjectCount(0)))
        .transform
  }
}
