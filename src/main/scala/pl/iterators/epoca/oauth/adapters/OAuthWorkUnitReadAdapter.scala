package pl.iterators.epoca.oauth.adapters

import cats.effect.IO
import pl.iterators.epoca.oauth.adapters.OAuthReadResult.{NotFound, Ok}
import pl.iterators.epoca.oauth.domain.OAuthDomain.OAuthProjectAccess
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.organization.repositories.OrganizationRepository
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.project.domain.ProjectDomain.ProjectId
import pl.iterators.epoca.user.domain.UserDomain.{UserAccountType, UserId}
import pl.iterators.epoca.user.repositories.UserRepository
import pl.iterators.epoca.user.repositories.UserRepository.UserFilters
import pl.iterators.epoca.utils.db.{PaginatedResult, PostgresIOTransactor}
import pl.iterators.epoca.workunit.domain.WorkUnit
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.WorkUnitState
import pl.iterators.epoca.workunit.repositories.WorkUnitRepository
import pl.iterators.epoca.workunit.repositories.WorkUnitRepository.WorkUnitFilters
import pl.iterators.epoca.workunit.services.WorkUnitDtoService
import pl.iterators.epoca.workunit.services.WorkUnitListService.WorkUnitListData
import slick.dbio.DBIO

import scala.concurrent.ExecutionContext

trait OAuthWorkUnitReader {
  def list(
    policy: OAuthAccessPolicy,
    slug: OrganizationSlug,
    filters: WorkUnitFilters
  ): IO[OAuthReadResult[WorkUnitListData]]
}

final class OAuthWorkUnitReadAdapter(
  transactor: PostgresIOTransactor,
  organizationRepository: OrganizationRepository,
  workUnitRepository: WorkUnitRepository,
  userRepository: UserRepository,
  workUnitDtoService: WorkUnitDtoService
)(implicit ec: ExecutionContext)
    extends OAuthWorkUnitReader {

  def list(
    policy: OAuthAccessPolicy,
    slug: OrganizationSlug,
    filters: WorkUnitFilters
  ): IO[OAuthReadResult[WorkUnitListData]] =
    transactor.executeTransactionally {
      organizationRepository
        .find(OrganizationFilters(id = Some(policy.organizationId), slug = Some(slug), includeArchived = true))
        .flatMap {
          case Some(organization) if organization.id == policy.organizationId && organization.slug == slug =>
            listVisible(policy, filters)
          case _ => DBIO.successful(NotFound)
        }
    }

  private def listVisible(policy: OAuthAccessPolicy, filters: WorkUnitFilters): DBIO[OAuthReadResult[WorkUnitListData]] =
    intersectProjectIds(policy.projectAccess, filters.projectIds) match {
      case Some(projectIds) if projectIds.isEmpty => DBIO.successful(empty)
      case _ =>
        val defaultStates = WorkUnitState.liveStates ++ Option.when(filters.includeArchived)(WorkUnitState.Archived)
        val boundedFilters = filters.copy(
          organizationId = Some(policy.organizationId),
          projectIds = filters.projectIds,
          reportAuthorizedProjectIds = policy.projectAccess match {
            case OAuthProjectAccess.AllProjects           => None
            case OAuthProjectAccess.SelectedProjects(ids) => Some(ids.toSeq.sortBy(_.toString))
          },
          states = Some(filters.states.getOrElse(defaultStates))
        )
        for {
          count     <- workUnitRepository.count(boundedFilters)
          workUnits <- workUnitRepository.list(boundedFilters)
          policyVisible =
            workUnits.filter(workUnit =>
              workUnit.organizationId == policy.organizationId && policy.allows(workUnit.projectId) && policy.allows(workUnit.effectiveProjectId)
            )
          humanIds <- listHumanUserIds(policyVisible)
          humanWorkUnits = policyVisible.filter(workUnit => humanIds.contains(workUnit.userId))
          result <-
            if (humanWorkUnits.isEmpty) DBIO.successful(Ok(WorkUnitListData(PaginatedResult(count, Seq.empty), Seq.empty)))
            else
              workUnitDtoService
                .prepareDtos(humanWorkUnits)
                .map(dtos => Ok(WorkUnitListData(PaginatedResult(count, dtos), Seq.empty)))
        } yield result
    }

  private def listHumanUserIds(workUnits: Seq[WorkUnit]): DBIO[Set[UserId]] = {
    val userIds = workUnits.map(_.userId).distinct
    if (userIds.isEmpty) DBIO.successful(Set.empty)
    else
      userRepository
        .list(UserFilters(ids = Some(userIds), includeArchived = true))
        .map(_.filter(user => userIds.contains(user.id) && user.accountType == UserAccountType.Human).map(_.id).toSet)
  }

  private def intersectProjectIds(access: OAuthProjectAccess, requestedIds: Option[Seq[ProjectId]]): Option[Set[ProjectId]] =
    access match {
      case OAuthProjectAccess.AllProjects                  => requestedIds.map(_.toSet)
      case OAuthProjectAccess.SelectedProjects(projectIds) => Some(requestedIds.fold(projectIds)(_.toSet intersect projectIds))
    }

  private val empty: OAuthReadResult[WorkUnitListData] = Ok(WorkUnitListData(PaginatedResult(0, Seq.empty), Seq.empty))
}
