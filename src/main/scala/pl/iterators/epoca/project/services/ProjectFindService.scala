package pl.iterators.epoca.project.services

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationId, OrganizationSlug}
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.project.domain.Project
import pl.iterators.epoca.project.domain.ProjectDomain.ProjectId
import pl.iterators.epoca.project.repositories.ProjectRepository.ProjectFilters
import pl.iterators.epoca.project.repositories.ProjectUserRepository.ProjectUserFilters
import pl.iterators.epoca.project.repositories.{ProjectRepository, ProjectUserRepository}
import pl.iterators.epoca.project.services.ProjectDtoService.ProjectDto
import pl.iterators.epoca.project.services.ProjectFindService.ProjectFindResult._
import pl.iterators.epoca.project.services.ProjectFindService._
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.service.SealedMonadServiceIODBIO
import pl.iterators.sealedmonad.Sealed
import pl.iterators.sealedmonad.syntax._
import slick.dbio.DBIO

class ProjectFindService(
  transactor: PostgresIOTransactor,
  organizationRepository: OrganizationRepository,
  organizationUserRepository: OrganizationUserRepository,
  projectRepository: ProjectRepository,
  projectUserRepository: ProjectUserRepository,
  projectDtoService: ProjectDtoService
)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIODBIO[ProjectFindResult](ioRuntime)
    with LoggingSupport {
  def find(
    authContext: AuthContext,
    organizationSlug: OrganizationSlug,
    projectId: ProjectId
  ): IO[ProjectFindResult] =
    transactor.execute {
      (for {
        organization <- findOrganization(organizationSlug)
        orgUser      <- findOrganizationUser(authContext, organization.id)
        _            <- validateCanAccess(authContext, orgUser, projectId)
        project      <- findProject(organization, projectId)
        projectDto   <- projectDtoService.prepareFindDto(project).seal
      } yield ProjectFindResult.Ok(projectDto)).run
    }

  private def findOrganization(slug: OrganizationSlug): StepDBIO[Organization] =
    organizationRepository
      .find(OrganizationFilters(slug = Some(slug)))
      .valueOrF {
        Logger[DBIO].error(s"organization $slug does not exist").as(OrganizationNotFound)
      }

  private def findOrganizationUser(authContext: AuthContext, id: OrganizationId): StepDBIO[Option[OrganizationUser]] =
    if (authContext.isSuperAdmin) Sealed.liftF(None)
    else
      organizationUserRepository
        .find(OrganizationUserFilters(organizationId = Some(id), userId = Some(authContext.id)))
        .valueOrF {
          Logger[DBIO]
            .warning(s"user ${authContext.id} has no access to organization $id")
            .as(NoAccessToOrganization)
        }
        .map(Option.apply)

  private def validateCanAccess(
    authContext: AuthContext,
    optOrgUser: Option[OrganizationUser],
    projectId: ProjectId
  ): StepDBIO[Unit] = {
    val isAdminOrManager: Boolean = optOrgUser.isEmpty || optOrgUser.exists(_.hasManagementRole)
    if (isAdminOrManager) Sealed.liftF(())
    else
      projectUserRepository
        .find(ProjectUserFilters(projectIds = Some(Seq(projectId)), userId = Some(authContext.id)))
        .valueOrF {
          Logger[DBIO]
            .warning(s"user ${authContext.id} has no access to project $projectId")
            .as(NoAccessToProject)
        }
        .void
  }

  private def findProject(organization: Organization, projectId: ProjectId): StepDBIO[Project] =
    projectRepository
      .find(ProjectFilters(organizationIds = Some(Seq(organization.id)), id = Some(projectId), includeArchived = true))
      .valueOr(ProjectNotFound)
}

object ProjectFindService {
  sealed trait ProjectFindResult
  object ProjectFindResult {
    final case class Ok(project: ProjectDto) extends ProjectFindResult
    final case object NoAccessToOrganization extends ProjectFindResult
    final case object NoAccessToProject      extends ProjectFindResult
    final case object OrganizationNotFound   extends ProjectFindResult
    final case object ProjectNotFound        extends ProjectFindResult
  }
}
