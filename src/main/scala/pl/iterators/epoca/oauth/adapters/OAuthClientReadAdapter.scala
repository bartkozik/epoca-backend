package pl.iterators.epoca.oauth.adapters

import cats.effect.IO
import pl.iterators.epoca.client.domain.Client
import pl.iterators.epoca.client.domain.ClientDomain.ClientId
import pl.iterators.epoca.client.repositories.ClientRepository
import pl.iterators.epoca.client.repositories.ClientRepository.ClientFilters
import pl.iterators.epoca.client.services.ClientDtoService.ClientDto
import pl.iterators.epoca.oauth.adapters.OAuthReadResult.{NotFound, Ok}
import pl.iterators.epoca.oauth.domain.OAuthDomain.OAuthProjectAccess
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.organization.repositories.OrganizationRepository
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.project.domain.Project
import pl.iterators.epoca.project.domain.ProjectDomain.ProjectId
import pl.iterators.epoca.project.repositories.ProjectRepository
import pl.iterators.epoca.project.repositories.ProjectRepository.ProjectFilters
import pl.iterators.epoca.utils.db.{PaginatedResult, PostgresIOTransactor}
import slick.dbio.DBIO

import scala.concurrent.ExecutionContext

trait OAuthClientReader {
  def list(
    policy: OAuthAccessPolicy,
    slug: OrganizationSlug,
    filters: ClientFilters
  ): IO[OAuthReadResult[PaginatedResult[ClientDto]]]
}

final class OAuthClientReadAdapter(
  transactor: PostgresIOTransactor,
  organizationRepository: OrganizationRepository,
  clientRepository: ClientRepository,
  projectRepository: ProjectRepository
)(implicit ec: ExecutionContext)
    extends OAuthClientReader {

  def list(
    policy: OAuthAccessPolicy,
    slug: OrganizationSlug,
    filters: ClientFilters
  ): IO[OAuthReadResult[PaginatedResult[ClientDto]]] =
    transactor.executeTransactionally {
      organizationRepository
        .find(OrganizationFilters(id = Some(policy.organizationId), slug = Some(slug), includeArchived = true))
        .flatMap {
          case Some(organization) if organization.id == policy.organizationId && organization.slug == slug =>
            listVisibleClients(policy, filters)
          case _ => DBIO.successful(NotFound)
        }
    }

  private def listVisibleClients(policy: OAuthAccessPolicy, filters: ClientFilters): DBIO[OAuthReadResult[PaginatedResult[ClientDto]]] = {
    val requestedIds = intersectClientIds(filters.id, filters.ids)
    val allowedProjectIds = policy.projectAccess match {
      case OAuthProjectAccess.AllProjects                  => None
      case OAuthProjectAccess.SelectedProjects(projectIds) => Some(projectIds.toSeq)
    }

    if (requestedIds.exists(_.isEmpty) || allowedProjectIds.exists(_.isEmpty)) DBIO.successful(Ok(PaginatedResult(0, Seq.empty)))
    else {
      val boundedFilter = filters.copy(
        id = None,
        ids = requestedIds.map(_.toSeq),
        organizationIds = Some(Seq(policy.organizationId)),
        userId = None,
        projectIds = allowedProjectIds,
        requireActiveProject = true
      )
      for {
        count   <- clientRepository.count(boundedFilter)
        clients <- clientRepository.list(boundedFilter)
        boundedClients =
          clients.filter(client => client.organizationId == policy.organizationId && requestedIds.forall(_.contains(client.id))).distinctBy(_.id)
        projects <- listVisibleProjects(policy, boundedClients)
        visibleClientIds = projects.map(_.clientId).toSet
        counts = projects.groupBy(_.clientId).map { case (clientId, clientProjects) =>
                   clientId -> pl.iterators.epoca.client.domain.ClientDomain.ClientProjectCount(clientProjects.size)
                 }
        dtos = boundedClients.filter(client => visibleClientIds.contains(client.id)).map(ClientDto.from(_, counts))
      } yield Ok(PaginatedResult(count, dtos))
    }
  }

  private def listVisibleProjects(policy: OAuthAccessPolicy, clients: Seq[Client]): DBIO[Seq[Project]] = {
    val clientIds = clients.map(_.id)
    if (clientIds.isEmpty) DBIO.successful(Seq.empty)
    else {
      val projectIds: Option[Seq[ProjectId]] = policy.projectAccess match {
        case OAuthProjectAccess.AllProjects                  => None
        case OAuthProjectAccess.SelectedProjects(allowedIds) => Some(allowedIds.toSeq)
      }
      projectRepository
        .list(ProjectFilters(ids = projectIds, organizationIds = Some(Seq(policy.organizationId)), clientIds = Some(clientIds)))
        .map(
          _.filter(project => project.organizationId == policy.organizationId && policy.allows(project.id) && clientIds.contains(project.clientId))
            .distinctBy(_.id)
        )
    }
  }

  private def intersectClientIds(id: Option[ClientId], ids: Option[Seq[ClientId]]): Option[Set[ClientId]] =
    (id.map(Set(_)), ids.map(_.toSet)) match {
      case (Some(single), Some(many)) => Some(single intersect many)
      case (Some(single), None)       => Some(single)
      case (None, Some(many))         => Some(many)
      case (None, None)               => None
    }
}
