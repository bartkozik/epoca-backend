package pl.iterators.epoca.oauth.adapters

import cats.effect.IO
import io.circe.Json
import pl.iterators.epoca.oauth.domain.OAuthDomain.OAuthProjectAccess
import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationId, OrganizationSlug}
import pl.iterators.epoca.organization.repositories.OrganizationRepository
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.project.domain.ProjectDomain.ProjectId
import pl.iterators.epoca.project.repositories.ProjectRepository
import pl.iterators.epoca.project.repositories.ProjectRepository.ProjectFilters
import pl.iterators.epoca.stats.domain.WorkUnitPivotQuery.GlobalFilters
import pl.iterators.epoca.stats.services.WorkUnitPivotQueryService.Result
import pl.iterators.epoca.stats.services.WorkUnitPivotTableService.{WorkUnitPivotTableRequest, WorkUnitPivotTableResult}
import pl.iterators.epoca.stats.services.{WorkUnitPivotQueryService, WorkUnitPivotTableService}
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.transactor.IsolationLevel
import pl.iterators.epoca.workunit.repositories.WorkUnitRepository.WorkUnitFilters
import slick.dbio.DBIO

import scala.concurrent.ExecutionContext

final case class OAuthReportPolicy(organizationId: OrganizationId, allowedProjectIds: Set[ProjectId]) {
  def allowsRow(sourceProjectId: ProjectId, overrideProjectId: Option[ProjectId]): Boolean =
    allowedProjectIds.contains(sourceProjectId) && overrideProjectId.forall(allowedProjectIds.contains)
}

trait OAuthReportReader {
  def legacyPivot(
    accessPolicy: OAuthAccessPolicy,
    organizationSlug: OrganizationSlug,
    request: WorkUnitPivotTableRequest,
    filters: WorkUnitFilters
  ): IO[WorkUnitPivotTableResult]
  def fullPivot(
    accessPolicy: OAuthAccessPolicy,
    organizationSlug: OrganizationSlug,
    rawRequest: Json,
    filters: GlobalFilters
  ): IO[Result]
}

final class OAuthReportAdapter(
  transactor: PostgresIOTransactor,
  organizationRepository: OrganizationRepository,
  projectRepository: ProjectRepository,
  pivotTableService: WorkUnitPivotTableService,
  pivotQueryService: WorkUnitPivotQueryService
)(implicit ec: ExecutionContext)
    extends OAuthReportReader {

  def legacyPivot(
    accessPolicy: OAuthAccessPolicy,
    organizationSlug: OrganizationSlug,
    request: WorkUnitPivotTableRequest,
    filters: WorkUnitFilters
  ): IO[WorkUnitPivotTableResult] =
    withResolvedPolicy(accessPolicy, organizationSlug, WorkUnitPivotTableResult.OrganizationNotFound: WorkUnitPivotTableResult) { policy =>
      pivotTableService.buildOAuthPivotTableAction(policy.organizationId, policy.allowedProjectIds.toSeq, request, filters)
    }

  def fullPivot(
    accessPolicy: OAuthAccessPolicy,
    organizationSlug: OrganizationSlug,
    rawRequest: Json,
    filters: GlobalFilters
  ): IO[Result] =
    withResolvedPolicy(accessPolicy, organizationSlug, Result.OrganizationNotFound: Result) { policy =>
      pivotQueryService.queryOAuthAction(policy.organizationId, policy.allowedProjectIds.toSeq, rawRequest, filters)
    }

  private def withResolvedPolicy[A](
    accessPolicy: OAuthAccessPolicy,
    slug: OrganizationSlug,
    notFound: A
  )(
    action: OAuthReportPolicy => DBIO[A]
  ): IO[A] =
    transactor.executeTransactionally(IsolationLevel.RepeatableRead) {
      OAuthReportAdapter.resolveAction(organizationRepository, projectRepository, accessPolicy, slug).flatMap {
        case None         => DBIO.successful(notFound)
        case Some(policy) => action(policy)
      }
    }
}

object OAuthReportAdapter {
  private[adapters] def resolveAction(
    organizationRepository: OrganizationRepository,
    projectRepository: ProjectRepository,
    accessPolicy: OAuthAccessPolicy,
    slug: OrganizationSlug
  )(implicit ec: ExecutionContext
  ): DBIO[Option[OAuthReportPolicy]] =
    organizationRepository
      .find(OrganizationFilters(id = Some(accessPolicy.organizationId), slug = Some(slug), includeArchived = true))
      .flatMap {
        case Some(organization) if organization.id == accessPolicy.organizationId && organization.slug == slug =>
          val filters = accessPolicy.projectAccess match {
            case OAuthProjectAccess.AllProjects =>
              ProjectFilters(organizationIds = Some(Seq(organization.id)), includeArchived = true)
            case OAuthProjectAccess.SelectedProjects(projectIds) =>
              ProjectFilters(ids = Some(projectIds.toSeq), organizationIds = Some(Seq(organization.id)), includeArchived = true)
          }
          projectRepository.list(filters).map { projects =>
            val allowed = projects.iterator.filter(_.organizationId == organization.id).map(_.id).toSet
            accessPolicy.projectAccess match {
              case OAuthProjectAccess.AllProjects                                         => Some(OAuthReportPolicy(organization.id, allowed))
              case OAuthProjectAccess.SelectedProjects(requested) if allowed == requested => Some(OAuthReportPolicy(organization.id, allowed))
              case OAuthProjectAccess.SelectedProjects(_)                                 => None
            }
          }
        case _ => DBIO.successful(None)
      }
}
