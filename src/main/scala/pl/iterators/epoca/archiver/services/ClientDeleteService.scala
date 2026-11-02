package pl.iterators.epoca.archiver.services

import cats.effect._
import cats.effect.unsafe.IORuntime
import pl.iterators.epoca.archiver.services.ClientDeleteService.ClientDeleteResult
import pl.iterators.epoca.archiver.services.ClientDeleteService.ClientDeleteResult._
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.client.domain.Client
import pl.iterators.epoca.client.domain.ClientDomain._
import pl.iterators.epoca.client.repositories.ClientRepository
import pl.iterators.epoca.client.repositories.ClientRepository.ClientFilters
import pl.iterators.epoca.organization.domain.Organization
import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationId, OrganizationSlug}
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.project.domain.ProjectDomain.{ProjectArchivedAt, ProjectUserArchivedAt}
import pl.iterators.epoca.project.domain.{Project, ProjectUser}
import pl.iterators.epoca.project.repositories.ProjectRepository.ProjectFilters
import pl.iterators.epoca.project.repositories.ProjectUserRepository.ProjectUserFilters
import pl.iterators.epoca.project.repositories.{ProjectRepository, ProjectUserRepository}
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.service.SealedMonadServiceIODBIO
import pl.iterators.sealedmonad.Sealed
import pl.iterators.sealedmonad.syntax._
import slick.dbio.DBIO

import scala.collection.immutable.Seq

class ClientDeleteService(
  transactor: PostgresIOTransactor,
  projectRepository: ProjectRepository,
  projectUserRepository: ProjectUserRepository,
  organizationRepository: OrganizationRepository,
  organizationUserRepository: OrganizationUserRepository,
  clientRepository: ClientRepository
)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIODBIO[ClientDeleteResult](ioRuntime)
    with LoggingSupport {

  def delete(
    authContext: AuthContext,
    organizationSlug: OrganizationSlug,
    id: ClientId
  ): IO[ClientDeleteResult] = {
    val query = for {
      _                <- Logger[DBIO].info(s"auth.id=${authContext.id}, organization=$organizationSlug, clientId=$id").seal
      organization     <- findOrganization(organizationSlug)
      _                <- checkAccessToOrganization(authContext, organization.id)
      _                <- clientRepository.lockForMutation(id).seal
      projects         <- listProjects(organization.id, id)
      client           <- findClient(organization, id)
      projectUsers     <- listProjectUsers(projects)
      softDeleted      <- prepareDeleted(authContext, client)
      _                <- softDelete(softDeleted)
      projectCount     <- softDeleteRelatedProjects(organization.id, id, authContext.id)
      projectUserCount <- softDeleteProjectUsers(projectUsers)
      _                <- successLog(id, projectCount, projectUserCount)
    } yield Deleted
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
      .find(ClientFilters(organizationIds = Some(Seq(organization.id)), id = Some(id), forUpdate = true))
      .valueOrF {
        Logger[DBIO].error(s"client $id does not exist").as(ClientNotFound)
      }

  private def prepareDeleted(authContext: AuthContext, client: Client): StepDBIO[Client] = {
    val res: DBIO[Client] = Clock[DBIO].instantNow.map { now =>
      client.copy(archivedAt = Some(ClientArchivedAt(now)), archivedBy = Some(authContext.id))
    }
    res.seal
  }

  private def listProjects(organizationId: OrganizationId, clientId: ClientId): StepDBIO[Seq[Project]] =
    projectRepository
      .lockOrdered(ProjectFilters(organizationIds = Some(Seq(organizationId)), clientIds = Some(Seq(clientId))))
      .seal

  private def listProjectUsers(projects: Seq[Project]): StepDBIO[Seq[ProjectUser]] =
    projectUserRepository.lockOrdered(ProjectUserFilters(projectIds = Some(projects.map(_.id)))).seal

  private def softDelete(client: Client): StepDBIO[Client] =
    clientRepository.update(client).valueOr(ClientNotFound)

  private def softDeleteRelatedProjects(
    organizationId: OrganizationId,
    clientId: ClientId,
    userId: UserId
  ): StepDBIO[Int] = {
    val res: DBIO[Int] = Clock[DBIO].instantNow.flatMap { now =>
      val projectFilters = ProjectFilters(organizationIds = Some(Seq(organizationId)), clientIds = Some(Seq(clientId)))
      val when           = ProjectArchivedAt(now)
      projectRepository.archive(projectFilters, when, userId)
    }
    res.seal
  }

  private def softDeleteProjectUsers(projectUsers: Seq[ProjectUser]): StepDBIO[Int] = {
    val res: DBIO[Int] = Clock[DBIO].instantNow.flatMap { now =>
      val ids  = projectUsers.map(_.id)
      val when = ProjectUserArchivedAt(now)
      projectUserRepository.bulkArchiveAt(ids, when)
    }
    res.seal
  }

  private def successLog(
    clientId: ClientId,
    projectCount: Int,
    projectUserCount: Int
  ): StepDBIO[Unit] =
    Logger[DBIO].info(s"successfully archived client $clientId with $projectCount projects and $projectUserCount projectUsers").seal
}

object ClientDeleteService {
  sealed trait ClientDeleteResult
  object ClientDeleteResult {
    final case object Deleted                extends ClientDeleteResult
    final case object NoAccessToOrganization extends ClientDeleteResult
    final case object OrganizationNotFound   extends ClientDeleteResult
    final case object InsufficientRole       extends ClientDeleteResult
    final case object ClientNotFound         extends ClientDeleteResult
  }

}
