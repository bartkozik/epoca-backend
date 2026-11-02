package pl.iterators.epoca.workunit.repositories

import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.client.domain.ClientDomain.ClientId
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.project.domain.ProjectDomain.ProjectId
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.utils.db.{FilterParamType, PageSizeAndNumber}
import pl.iterators.epoca.workunit.domain.WorkUnit
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.{WorkUnitDate, WorkUnitId, WorkUnitState}
import slick.dbio.DBIO

trait WorkUnitRepository {
  import WorkUnitRepository._

  def insert(workUnit: WorkUnit): DBIO[WorkUnit]
  def update(workUnit: WorkUnit): DBIO[Option[WorkUnit]]
  def find(filter: WorkUnitFilters): DBIO[Option[WorkUnit]]
  def list(filter: WorkUnitFilters): DBIO[Seq[WorkUnit]]
  def lockOrdered(filter: WorkUnitFilters): DBIO[Seq[WorkUnit]] = list(filter.copy(forUpdate = true))
  def listWithClientId(filter: WorkUnitFilters): DBIO[Seq[WorkUnitWithClientId]]
  def count(filter: WorkUnitFilters): DBIO[Int]
}

object WorkUnitRepository {
  final case class WorkUnitWithClientId(workUnit: WorkUnit, clientId: ClientId)

  case class WorkUnitFilters(
    id: Option[WorkUnitId] = None,
    ids: Option[Seq[WorkUnitId]] = None,
    organizationId: Option[OrganizationId] = None,
    userIds: Option[Seq[UserId]] = None,
    projectIds: Option[Seq[ProjectId]] = None,
    clientIds: Option[Seq[ClientId]] = None,
    dateFrom: Option[WorkUnitDate] = None,
    dateTo: Option[WorkUnitDate] = None,
    states: Option[Seq[WorkUnitState]] = None,
    includeArchived: Boolean = false,
    forUpdate: Boolean = false,
    reportAuthorizedProjectIds: Option[Seq[ProjectId]] = None,
    pageSizeAndNumber: Option[PageSizeAndNumber] = None)
      extends FilterParamType

  object WorkUnitFilters {
    def from(
      filter: WorkUnitFilters,
      organization: Organization,
      orgUserOpt: Option[OrganizationUser],
      authContext: AuthContext
    ): WorkUnitFilters = {
      val baseFilter = if (orgUserOpt.exists(_.hasManagementRole) || orgUserOpt.isEmpty) {
        filter
      } else {
        filter.copy(userIds = Some(Seq(authContext.id)))
      }

      val isSelfQuery    = filter.userIds.contains(Seq(authContext.id))
      val draftStates    = if (isSelfQuery) Seq(WorkUnitState.Draft) else Nil
      val archivedStates = if (baseFilter.includeArchived) Seq(WorkUnitState.Archived) else Nil
      val states         = WorkUnitState.liveStates ++ draftStates ++ archivedStates

      baseFilter.copy(organizationId = Some(organization.id), states = Some(baseFilter.states.getOrElse(states)))

    }
  }
}
