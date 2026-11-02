package pl.iterators.epoca.project.services

import cats.effect._
import cats.effect.unsafe.IORuntime
import cats.implicits._
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.organization.domain.Organization
import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationId, OrganizationSlug}
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.project.domain.ProjectDomain._
import pl.iterators.epoca.project.domain.{Project, ProjectUser}
import pl.iterators.epoca.project.repositories.ProjectRepository.ProjectFilters
import pl.iterators.epoca.project.repositories.ProjectUserRepository.ProjectUserFilters
import pl.iterators.epoca.project.repositories.{ProjectRepository, ProjectUserRepository}
import pl.iterators.epoca.project.services.ProjectDeleteService.ProjectDeleteResult
import pl.iterators.epoca.project.services.ProjectDeleteService.ProjectDeleteResult._
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.service.SealedMonadServiceIODBIO
import pl.iterators.sealedmonad.Sealed
import pl.iterators.sealedmonad.syntax._
import slick.dbio.DBIO

class ProjectDeleteService(
  transactor: PostgresIOTransactor,
  organizationRepository: OrganizationRepository,
  organizationUserRepository: OrganizationUserRepository,
  projectRepository: ProjectRepository,
  projectUserRepository: ProjectUserRepository
)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIODBIO[ProjectDeleteResult](ioRuntime)
    with LoggingSupport {

  def delete(
    authContext: AuthContext,
    organizationSlug: OrganizationSlug,
    id: ProjectId
  ): IO[ProjectDeleteResult] =
    transactor.executeTransactionally {
      (for {
        _            <- Logger[DBIO].info(s"auth.id=${authContext.id}, organization=$organizationSlug, projectId=$id").seal
        organization <- findOrganization(organizationSlug)
        _            <- checkAccessToOrganization(authContext, organization.id)
        project      <- findProject(organization, id)
        projectUsers <- listProjectUsers(id)
        softDeleted  <- prepareDeleted(authContext, project)
        _            <- softDelete(softDeleted)
        _            <- softDeleteProjectUsers(projectUsers)
      } yield Deleted).run
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
        .ensureF(
          _.exists(_.hasManagementRole),
          Logger[DBIO].warning(s"user ${authContext.id} has insufficient role in organization $id").as(NoAccessToOrganization)
        )
        .void

  private def findProject(organization: Organization, id: ProjectId): StepDBIO[Project] =
    projectRepository
      .find(ProjectFilters(organizationIds = Some(Seq(organization.id)), id = Some(id), forUpdate = true))
      .valueOrF {
        Logger[DBIO].error(s"project $id does not exist").as(ProjectNotFound)
      }

  private def listProjectUsers(projectId: ProjectId): StepDBIO[Seq[ProjectUser]] =
    projectUserRepository.list(ProjectUserFilters(projectIds = Some(Seq(projectId)))).seal

  private def prepareDeleted(authContext: AuthContext, project: Project): StepDBIO[Project] = {
    Clock[DBIO].instantNow.map { now =>
      project.copy(
        modifiedAt = ProjectModifiedAt(now),
        modifiedBy = authContext.id,
        archivedAt = Some(ProjectArchivedAt(now)),
        archivedBy = Some(authContext.id)
      )
    }: DBIO[Project]
  }.seal

  private def softDelete(project: Project): StepDBIO[Project] =
    (Logger[DBIO].info(s"successfully deleted project ${project.id}") *>
      projectRepository.update(project)).valueOr(ProjectNotFound)

  private def softDeleteProjectUsers(projectUsers: Seq[ProjectUser]): StepDBIO[Int] = {
    Clock[DBIO].instantNow.flatMap { now =>
      val ids  = projectUsers.map(_.id)
      val when = ProjectUserArchivedAt(now)
      projectUserRepository.bulkArchiveAt(ids, when)
    }: DBIO[Int]
  }.seal
}

object ProjectDeleteService {
  sealed trait ProjectDeleteResult
  object ProjectDeleteResult {
    final case object Deleted                extends ProjectDeleteResult
    final case object NoAccessToOrganization extends ProjectDeleteResult
    final case object OrganizationNotFound   extends ProjectDeleteResult
    final case object ProjectNotFound        extends ProjectDeleteResult
  }

}
