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
import pl.iterators.epoca.project.domain.Project
import pl.iterators.epoca.project.domain.ProjectDomain.{ProjectId, ProjectModifiedAt}
import pl.iterators.epoca.project.repositories.ProjectRepository
import pl.iterators.epoca.project.repositories.ProjectRepository.ProjectFilters
import pl.iterators.epoca.unarchiver.services.ProjectUnarchiveService.ProjectUnarchiveResult
import pl.iterators.epoca.unarchiver.services.ProjectUnarchiveService.ProjectUnarchiveResult._
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.service.SealedMonadServiceIODBIO
import pl.iterators.sealedmonad.Sealed
import pl.iterators.sealedmonad.syntax._
import slick.dbio.DBIO

class ProjectUnarchiveService(
  transactor: PostgresIOTransactor,
  organizationRepository: OrganizationRepository,
  organizationUserRepository: OrganizationUserRepository,
  projectRepository: ProjectRepository,
  clientRepository: ClientRepository
)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIODBIO[ProjectUnarchiveResult](ioRuntime)
    with LoggingSupport {

  def unarchive(
    authContext: AuthContext,
    organizationSlug: OrganizationSlug,
    id: ProjectId
  ): IO[ProjectUnarchiveResult] = {
    val query = for {
      _                 <- Logger[DBIO].info(s"auth.id=${authContext.id}, organization=$organizationSlug, projectId=$id").seal
      organization      <- findOrganization(organizationSlug)
      _                 <- checkAccessToOrganization(authContext, organization.id)
      project           <- findProject(organization.id, id)
      client            <- findClient(organization.id, project.clientId)
      _                 <- validateUniqueProjectName(organization, project)
      _                 <- validateUniqueClientName(organization, client)
      _                 <- unarchiveClientIfArchived(authContext, client)
      unarchivedProject <- prepareUnarchivedProject(authContext, project)
      unarchivedProject <- unarchiveProject(unarchivedProject)
    } yield Unarchived(unarchivedProject)
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

  private def findProject(orgId: OrganizationId, id: ProjectId): StepDBIO[Project] =
    projectRepository
      .find(ProjectFilters(id = Some(id), organizationIds = Some(Seq(orgId)), forUpdate = true, includeArchived = true))
      .valueOrF {
        Logger[DBIO].error(s"project $id does not exist").as(ProjectNotFound)
      }

  private def validateUniqueProjectName(organization: Organization, project: Project): StepDBIO[Unit] =
    projectRepository
      .find(ProjectFilters(organizationIds = Some(Seq(organization.id)), name = Some(project.name)))
      .ensureF(
        _.isEmpty,
        Logger[DBIO].error(s"project ${project.id} can't be unarchived - another project with the same name is active").as(ProjectNameNotUnique)
      )
      .void

  private def findClient(orgId: OrganizationId, clientId: ClientId): StepDBIO[Client] =
    clientRepository
      .find(ClientFilters(id = Some(clientId), organizationIds = Some(Seq(orgId)), forUpdate = true, includeArchived = true))
      .valueOrF {
        Logger[DBIO].warning(s"client ${clientId} not found or is archived").as(ClientNotFound)
      }

  private def validateUniqueClientName(organization: Organization, client: Client): StepDBIO[Unit] =
    clientRepository
      .find(ClientFilters(organizationIds = Some(Seq(organization.id)), name = Some(client.name)))
      .ensureF(
        _.forall(_.id == client.id),
        Logger[DBIO].error(s"client ${client.id} can't be unarchived - another client with the same name is active").as(ClientNameNotUnique)
      )
      .void

  private def prepareUnarchivedProject(authContext: AuthContext, project: Project): StepDBIO[Project] = {
    val res: DBIO[Project] = Clock[DBIO].instantNow.map { now =>
      val when = ProjectModifiedAt(now)
      project.copy(archivedAt = None, archivedBy = None, modifiedAt = when, modifiedBy = authContext.id)
    }
    res.seal
  }

  private def unarchiveClientIfArchived(authContext: AuthContext, client: Client): StepDBIO[Unit] =
    if (client.archivedAt.isDefined) {
      for {
        unarchivedClient <- prepareUnarchivedClient(authContext, client)
        _                <- unarchiveClient(unarchivedClient)
      } yield ()
    } else {
      Sealed.liftF(())
    }

  private def prepareUnarchivedClient(authContext: AuthContext, client: Client): StepDBIO[Client] = {
    val res: DBIO[Client] = Clock[DBIO].instantNow.map { now =>
      val when = ClientModifiedAt(now)
      client.copy(archivedAt = None, archivedBy = None, modifiedAt = when, modifiedBy = authContext.id)
    }
    res.seal
  }

  private def unarchiveClient(client: Client): StepDBIO[Client] =
    clientRepository.update(client).valueOr(ClientNotFound)

  private def unarchiveProject(project: Project): StepDBIO[Project] =
    projectRepository.update(project).valueOr(ProjectNotFound)
}

object ProjectUnarchiveService {
  sealed trait ProjectUnarchiveResult
  object ProjectUnarchiveResult {
    final case class Unarchived(project: Project) extends ProjectUnarchiveResult
    final case object NoAccessToOrganization      extends ProjectUnarchiveResult
    final case object OrganizationNotFound        extends ProjectUnarchiveResult
    final case object InsufficientRole            extends ProjectUnarchiveResult
    final case object ProjectNotFound             extends ProjectUnarchiveResult
    final case object ClientNotFound              extends ProjectUnarchiveResult
    final case object ClientNameNotUnique         extends ProjectUnarchiveResult
    final case object ProjectNameNotUnique        extends ProjectUnarchiveResult
  }

}
