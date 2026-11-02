package pl.iterators.epoca.project.repositories

import pl.iterators.epoca.project.domain.Project
import pl.iterators.epoca.project.domain.ProjectDomain.{ProjectArchivedAt, ProjectId}
import pl.iterators.epoca.project.repositories.ProjectRepository.ProjectFilters
import pl.iterators.epoca.project.repositories.tables.{ProjectTable, ProjectUserTable}
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.utils.db.BaseRepository.driver.api._
import pl.iterators.epoca.utils.db.{FilteringRepository, IdRepository}
import slick.ast.BaseTypedType
import slick.lifted.TableQuery

import scala.concurrent.ExecutionContext

class ProjectRepositoryImpl(implicit e: ExecutionContext)
    extends IdRepository[Project, ProjectId, ProjectTable]
    with FilteringRepository[Project, ProjectTable, ProjectFilters]
    with ProjectRepository {

  override protected def ec: ExecutionContext                      = e
  override protected val table: TableQuery[ProjectTable]           = TableQuery[ProjectTable]
  override protected def getId: Project => ProjectId               = _.id
  override protected def idBaseTypedType: BaseTypedType[ProjectId] = implicitly[BaseTypedType[ProjectId]]

  private lazy val projectUserTable: TableQuery[ProjectUserTable] = TableQuery[ProjectUserTable]

  override def count(filter: ProjectFilters): DBIO[Int] =
    prepareFilterQuery(table, filter.copy(pageSizeAndNumber = None)).length.result

  override def lockTableForReference(): DBIO[Unit] =
    sqlu"LOCK TABLE project IN ROW SHARE MODE".map(_ => ())

  override def lockOrdered(filter: ProjectFilters): DBIO[Seq[Project]] =
    prepareFilterQuery(table, filter.copy(forUpdate = false)).sortBy(_.id).forUpdate.result

  override protected def prepareFilterQuery(tableQuery: Query[ProjectTable, Project, Seq], params: ProjectFilters)
    : Query[ProjectTable, Project, Seq] = {
    val query = tableQuery
      .filterOpt(params.id)(_.id === _)
      .filterOpt(params.ids)(_.id inSet _)
      .filterOpt(params.organizationIds)(_.organizationId inSet _)
      .filterOpt(params.name)(_.name === _)
      .filterOpt(params.clientIds)(_.clientId inSet _)
      .filterOpt(params.userId) { (row, userId) =>
        row.id in projectUserTable.filter(r => r.userId === userId && r.notDeletedNow).map(_.projectId)
      }
      .filterOpt(params.archivedConnectionsUserId) { (row, userId) =>
        row.id in projectUserTable.filter(r => r.userId === userId).map(_.projectId)
      }
      .filterOpt(params.notCreatedBy)(_.createdBy =!= _)
      .filterIf(!params.includeArchived)(_.notDeletedNow)

    val ordered = query.sortBy(_.id)
    val paginated = params.pageSizeAndNumber.fold(ordered) { page =>
      ordered.drop(page.offset).take(page.size)
    }

    if (params.forUpdate) paginated.forUpdate else paginated
  }

  override def archive(
    filter: ProjectFilters,
    when: ProjectArchivedAt,
    who: UserId
  ): DBIO[Int] =
    prepareFilterQuery(table, filter).map(row => (row.archivedAt, row.archivedBy)).update((Some(when), Some(who)))
}
