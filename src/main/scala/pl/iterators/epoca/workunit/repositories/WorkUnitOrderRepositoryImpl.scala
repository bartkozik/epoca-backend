package pl.iterators.epoca.workunit.repositories

import pl.iterators.epoca.utils.db.BaseRepository.driver.api._
import pl.iterators.epoca.utils.db.{FilteringRepository, UpsertRepository}
import pl.iterators.epoca.workunit.domain.WorkUnitOrder
import pl.iterators.epoca.workunit.repositories.WorkUnitOrderRepository.WorkUnitOrderFilters
import pl.iterators.epoca.workunit.repositories.tables.WorkUnitOrderTable
import slick.lifted.TableQuery

import scala.concurrent.ExecutionContext

class WorkUnitOrderRepositoryImpl(implicit e: ExecutionContext)
    extends FilteringRepository[WorkUnitOrder, WorkUnitOrderTable, WorkUnitOrderFilters]
    with UpsertRepository[WorkUnitOrder, WorkUnitOrderTable]
    with WorkUnitOrderRepository {
  implicit val ec: ExecutionContext = e

  override protected val table: TableQuery[WorkUnitOrderTable] = TableQuery[WorkUnitOrderTable]
  override protected def prepareFilterQuery(tableQuery: Query[WorkUnitOrderTable, WorkUnitOrder, Seq], params: WorkUnitOrderFilters)
    : Query[WorkUnitOrderTable, WorkUnitOrder, Seq] =
    tableQuery
      .filterOpt(params.userId)(_.userId === _)
      .filterOpt(params.organizationId)(_.organizationId === _)
      .filterOpt(params.dateFrom)(_.date >= _)
      .filterOpt(params.dateTo)(_.date <= _)
}
