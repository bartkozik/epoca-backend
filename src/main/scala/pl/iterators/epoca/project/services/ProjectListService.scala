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
import pl.iterators.epoca.project.repositories.ProjectRepository
import pl.iterators.epoca.project.repositories.ProjectRepository.ProjectFilters
import pl.iterators.epoca.project.services.ProjectDtoService.ProjectListDto
import pl.iterators.epoca.project.services.ProjectListService._
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.utils.db.{PaginatedResult, PostgresIOTransactor}
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.service.SealedMonadServiceIODBIO
import pl.iterators.epoca.utils.transactor.IsolationLevel
import pl.iterators.sealedmonad.Sealed
import pl.iterators.sealedmonad.syntax._
import slick.dbio.DBIO

class ProjectListService(
  transactor: PostgresIOTransactor,
  organizationRepository: OrganizationRepository,
  organizationUserRepository: OrganizationUserRepository,
  projectRepository: ProjectRepository,
  projectDtoService: ProjectDtoService
)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIODBIO[ProjectListResult](ioRuntime)
    with LoggingSupport {

  def list(
    authContext: AuthContext,
    organizationSlug: OrganizationSlug,
    filter: ProjectFilters,
    includeArchivedConnections: Boolean = false
  ): IO[ProjectListResult] =
    transactor.executeTransactionally(IsolationLevel.RepeatableRead) {
      (for {
        organization <- findOrganization(organizationSlug)
        orgUser      <- findOrganizationUser(authContext, organization.id)
        restrictedFilter = restrictFilterForRegularUsers(authContext, orgUser, filter, includeArchivedConnections)
        projects        <- listProjects(organization, restrictedFilter)
        projectsCount   <- countProjects(organization, restrictedFilter)
        projectListDtos <- projectDtoService.prepareListDtos(projects).seal
      } yield ProjectListResult.Ok(PaginatedResult(projectsCount, projectListDtos))).run
    }

  private def findOrganization(slug: OrganizationSlug): StepDBIO[Organization] =
    organizationRepository
      .find(OrganizationFilters(slug = Some(slug)))
      .valueOrF {
        Logger[DBIO].error(s"organization $slug does not exist").as(ProjectListResult.OrganizationNotFound)
      }

  private def findOrganizationUser(authContext: AuthContext, id: OrganizationId): StepDBIO[Option[OrganizationUser]] =
    if (authContext.isSuperAdmin) Sealed.liftF(None)
    else
      organizationUserRepository
        .find(OrganizationUserFilters(organizationId = Some(id), userId = Some(authContext.id)))
        .valueOrF {
          Logger[DBIO]
            .warning(s"user ${authContext.id} has no access to organization $id")
            .as(ProjectListResult.NoAccessToOrganization)
        }
        .map(Option.apply)

  private def restrictFilterForRegularUsers(
    authContext: AuthContext,
    orgUserOpt: Option[OrganizationUser],
    filter: ProjectFilters,
    includeArchivedConnections: Boolean
  ): ProjectFilters = {
    val isAdminOrManager       = orgUserOpt.isEmpty || orgUserOpt.exists(_.hasManagementRole)
    val userId: Option[UserId] = if (isAdminOrManager) None else Some(authContext.id)
    if (includeArchivedConnections) filter.copy(archivedConnectionsUserId = userId) else filter.copy(userId = userId)
  }

  private def listProjects(organization: Organization, filter: ProjectFilters): StepDBIO[Seq[Project]] =
    projectRepository.list(filter.copy(organizationIds = Some(Seq(organization.id)))).seal

  private def countProjects(organization: Organization, filter: ProjectFilters): StepDBIO[Int] =
    projectRepository.count(filter.copy(organizationIds = Some(Seq(organization.id)))).seal
}

object ProjectListService {
  sealed trait ProjectListResult
  object ProjectListResult {
    final case class Ok(data: PaginatedResult[ProjectListDto]) extends ProjectListResult
    final case object NoAccessToOrganization                   extends ProjectListResult
    final case object OrganizationNotFound                     extends ProjectListResult
  }
}
