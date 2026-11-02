package pl.iterators.epoca.workunit.repositories

import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.utils.db.FilterParamType
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.WorkUnitDate
import pl.iterators.epoca.workunit.domain.WorkUnitOrder
import slick.dbio.DBIO

trait WorkUnitOrderRepository {
  import WorkUnitOrderRepository._

  def upsert(workUnitOrder: WorkUnitOrder): DBIO[WorkUnitOrder]
  def find(filter: WorkUnitOrderFilters): DBIO[Option[WorkUnitOrder]]
  def list(filter: WorkUnitOrderFilters): DBIO[Seq[WorkUnitOrder]]
}

object WorkUnitOrderRepository {
  case class WorkUnitOrderFilters(
    organizationId: Option[OrganizationId] = None,
    userId: Option[UserId] = None,
    dateFrom: Option[WorkUnitDate] = None,
    dateTo: Option[WorkUnitDate] = None)
      extends FilterParamType
}
