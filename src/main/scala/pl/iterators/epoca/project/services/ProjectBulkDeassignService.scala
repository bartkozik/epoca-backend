package pl.iterators.epoca.project.services

import cats.Monad
import cats.effect.unsafe.IORuntime
import cats.effect.{Clock, IO}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.client.domain.ClientDomain.{ClientId, ClientType}
import pl.iterators.epoca.client.repositories.ClientRepository
import pl.iterators.epoca.client.repositories.ClientRepository.ClientFilters
import pl.iterators.epoca.organization.domain.Organization
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.project.domain.ProjectDomain._
import pl.iterators.epoca.project.domain.ProjectUser
import pl.iterators.epoca.project.repositories.ProjectRepository.ProjectFilters
import pl.iterators.epoca.project.repositories.ProjectUserRepository.ProjectUserFilters
import pl.iterators.epoca.project.repositories._
import pl.iterators.epoca.project.services.ProjectBulkDeassignService.ProjectBulkDeassignResult._
import pl.iterators.epoca.project.services.ProjectBulkDeassignService._
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.service.SealedMonadServiceIODBIO
import pl.iterators.sealedmonad.syntax._
import slick.dbio.DBIO

import scala.collection.immutable.Seq

class ProjectBulkDeassignService(
  transactor: PostgresIOTransactor,
  clientRepository: ClientRepository,
  projectRepository: ProjectRepository,
  projectUserRepository: ProjectUserRepository,
  organizationRepository: OrganizationRepository,
  organizationUserRepository: OrganizationUserRepository
)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIODBIO[ProjectBulkDeassignResult](ioRuntime)
    with LoggingSupport {

  def deassign(
    authContext: AuthContext,
    organizationSlug: OrganizationSlug,
    userId: UserId,
    request: ProjectBulkDeassignRequest
  ): IO[ProjectBulkDeassignResult] = transactor.executeTransactionally {
    (for {
      organization         <- findOrganization(organizationSlug)
      _                    <- validateAuthHasRoles(authContext, organization)
      clientIdsOpt         <- listClientIdsOpt(request, organization)
      projectIds           <- listProjectIds(organization, clientIdsOpt, userId)
      projectUsersToRemove <- listCurrentProjectUsers(projectIds, userId)
      _                    <- archiveProjectUsers(projectUsersToRemove)
    } yield Deassigned(ProjectBulkDeassignResponse(projectUsersToRemove.map(_.projectId)))).run
  }

  private def findOrganization(slug: OrganizationSlug): StepDBIO[Organization] =
    organizationRepository
      .find(OrganizationFilters(slug = Some(slug)))
      .valueOrF(Logger[DBIO].error(s"organization $slug does not exist").as(OrganizationNotFound))

  private def validateAuthHasRoles(authContext: AuthContext, organization: Organization): StepDBIO[Unit] =
    organizationUserRepository
      .find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authContext.id)))
      .ensureF(
        orgUser => authContext.isSuperAdmin || authContext.id == organization.createdBy || orgUser.exists(_.hasManagementRole),
        Logger[DBIO].warning(s"user ${authContext.id} doesn't have proper roles in organization ${organization.id}").as(NoAccessToOrganization)
      )
      .void

  private def listClientIdsOpt(request: ProjectBulkDeassignRequest, organization: Organization): StepDBIO[Option[Seq[ClientId]]] =
    if (request.clientId.isEmpty && request.clientType.isEmpty) {
      Monad[DBIO].pure(Option.empty[Seq[ClientId]]).seal
    } else {
      val clientFilters = ClientFilters(`type` = request.clientType, id = request.clientId, organizationIds = Some(Seq(organization.id)))
      val result: DBIO[Option[Seq[ClientId]]] = clientRepository
        .list(clientFilters)
        .map(res => Some(res.map(_.id)))
      result.seal
    }

  private def listProjectIds(
    organization: Organization,
    clientIdsOpt: Option[Seq[ClientId]],
    userId: UserId
  ): StepDBIO[Seq[ProjectId]] = {
    val filter = ProjectFilters(organizationIds = Some(Seq(organization.id)), clientIds = clientIdsOpt, notCreatedBy = Some(userId))
    val result: DBIO[Seq[ProjectId]] = projectRepository.list(filter).map(_.map(_.id))
    result.seal
  }

  private def listCurrentProjectUsers(projectIds: Seq[ProjectId], userId: UserId): StepDBIO[Seq[ProjectUser]] = {
    val result: DBIO[Seq[ProjectUser]] = projectUserRepository
      .lockOrdered(ProjectUserFilters(projectIds = Some(projectIds), userId = Some(userId)))
    result.seal
  }

  private def archiveProjectUsers(projectUsers: Seq[ProjectUser]): StepDBIO[Int] =
    (Clock[DBIO].instantNow.flatMap { now =>
      projectUserRepository.bulkArchiveAt(projectUsers.map(_.id), ProjectUserArchivedAt(now))
    }: DBIO[Int]).seal
}

object ProjectBulkDeassignService {
  case class ProjectBulkDeassignRequest(clientType: Option[ClientType], clientId: Option[ClientId])
  case class ProjectBulkDeassignResponse(changedProjectIds: Seq[ProjectId])
  sealed trait ProjectBulkDeassignResult
  object ProjectBulkDeassignResult {
    final case class Deassigned(result: ProjectBulkDeassignResponse) extends ProjectBulkDeassignResult
    final case object OrganizationNotFound                           extends ProjectBulkDeassignResult
    final case object NoAccessToOrganization                         extends ProjectBulkDeassignResult
  }
}
