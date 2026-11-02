package pl.iterators.epoca.workunit.repositories

import pl.iterators.epoca.project.repositories.tables.ProjectTable
import pl.iterators.epoca.user.domain.UserDomain.UserAccountType
import pl.iterators.epoca.user.repositories.tables.UserTable
import pl.iterators.epoca.utils.db.BaseRepository.driver.api._
import pl.iterators.epoca.utils.db.{FilteringRepository, IdRepository}
import pl.iterators.epoca.workunit.domain.WorkUnit
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.WorkUnitId
import pl.iterators.epoca.workunit.repositories.WorkUnitRepository.{WorkUnitFilters, WorkUnitWithClientId}
import pl.iterators.epoca.workunit.repositories.tables.WorkUnitTable
import slick.ast.BaseTypedType
import slick.lifted.TableQuery

import scala.concurrent.ExecutionContext

class WorkUnitRepositoryImpl(implicit e: ExecutionContext)
    extends IdRepository[WorkUnit, WorkUnitId, WorkUnitTable]
    with FilteringRepository[WorkUnit, WorkUnitTable, WorkUnitFilters]
    with WorkUnitRepository {
  override protected def ec: ExecutionContext                       = e
  override protected val table: TableQuery[WorkUnitTable]           = TableQuery[WorkUnitTable]
  private lazy val projects                                         = TableQuery[ProjectTable]
  private lazy val users                                            = TableQuery[UserTable]
  override protected def getId: WorkUnit => WorkUnitId              = _.id
  override protected def idBaseTypedType: BaseTypedType[WorkUnitId] = implicitly[BaseTypedType[WorkUnitId]]

  override def update(value: WorkUnit): DBIO[Option[WorkUnit]] =
    table.filter(_.id === value.id).update(value).flatMap {
      case 0 => DBIO.successful(None)
      case _ => table.filter(_.id === value.id).result.headOption
    }

  override def count(filter: WorkUnitFilters): DBIO[Int] =
    prepareFilterQuery(table, filter.copy(pageSizeAndNumber = None)).length.result

  override def lockOrdered(filter: WorkUnitFilters): DBIO[Seq[WorkUnit]] =
    prepareFilterQuery(table, filter.copy(forUpdate = false)).sortBy(_.id).forUpdate.result

  override def listWithClientId(filter: WorkUnitFilters): DBIO[Seq[WorkUnitWithClientId]] =
    prepareFilterQuery(table, filter)
      .join(projects)
      .on(_.effectiveProjectId === _.id)
      .map { case (unit, project) => (unit, project.clientId) }
      .result
      .map(_.map(WorkUnitWithClientId.tupled))

  override protected def prepareFilterQuery(tableQuery: Query[WorkUnitTable, WorkUnit, Seq], params: WorkUnitFilters)
    : Query[WorkUnitTable, WorkUnit, Seq] = {
    val filtered = tableQuery
      .filter(row => row.userId in users.filter(_.accountType === LiteralColumn(UserAccountType.Human: UserAccountType)).map(_.id))
      .filterOpt(params.id)(_.id === _)
      .filterOpt(params.ids)(_.id inSet _)
      .filterOpt(params.organizationId)(_.organizationId === _)
      .filterOpt(params.userIds)(_.userId inSet _)
      .filterOpt(params.dateFrom)(_.date >= _)
      .filterOpt(params.dateTo)(_.date <= _)
      .filterOpt(params.states)(_.state inSet _)
      .filterIf(!params.includeArchived)(_.notDeletedNow)
      // Authorization is independent of user-selected filters and precedes count/pagination.
      .filterOpt(params.reportAuthorizedProjectIds)((row, ids) => (row.projectId inSet ids) && (row.effectiveProjectId inSet ids))
      .filterOpt(params.projectIds)((row, ids) => row.effectiveProjectId inSet ids)
      .filterOpt(params.clientIds)((row, ids) => row.effectiveProjectId in projects.filter(_.clientId inSet ids).map(_.id))
      .sortBy(_.id)
    val paginated = params.pageSizeAndNumber.fold(filtered)(page => filtered.drop(page.offset).take(page.size))
    if (params.forUpdate) paginated.forUpdate else paginated
  }
}
