package pl.iterators.epoca.organization.repositories

import pl.iterators.epoca.organization.domain.Organization
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId
import pl.iterators.epoca.organization.repositories.OrganizationRepository.{OrganizationFilters, OrganizationSortBy}
import pl.iterators.epoca.organization.repositories.tables.{OrganizationTable, OrganizationUserTable}
import pl.iterators.epoca.utils.db.BaseRepository.driver.api._
import pl.iterators.epoca.utils.db.{FilteringRepository, IdRepository}
import slick.ast.BaseTypedType
import slick.lifted.TableQuery

import scala.concurrent.ExecutionContext

class OrganizationRepositoryImpl(implicit e: ExecutionContext)
    extends IdRepository[Organization, OrganizationId, OrganizationTable]
    with FilteringRepository[Organization, OrganizationTable, OrganizationFilters]
    with OrganizationRepository {

  override protected def ec: ExecutionContext                           = e
  override protected val table: TableQuery[OrganizationTable]           = TableQuery[OrganizationTable]
  override protected def getId: Organization => OrganizationId          = _.id
  override protected def idBaseTypedType: BaseTypedType[OrganizationId] = implicitly[BaseTypedType[OrganizationId]]

  private lazy val orgUserTable: TableQuery[OrganizationUserTable] = TableQuery[OrganizationUserTable]

  override def count(filter: OrganizationFilters): DBIO[Int] =
    prepareFilterQuery(table, filter.copy(pageSizeAndNumber = None)).length.result

  override protected def prepareFilterQuery(tableQuery: Query[OrganizationTable, Organization, Seq], params: OrganizationFilters)
    : Query[OrganizationTable, Organization, Seq] = {
    val query = tableQuery
      .filterOpt(params.id)(_.id === _)
      .filterOpt(params.ids)(_.id inSet _)
      .filterOpt(params.creatorId)(_.createdBy === _)
      .filterOpt(params.userId) { (row, userId) =>
        row.id in orgUserTable.filter(_.userId === userId).map(_.organizationId)
      }
      .filterOpt(params.slug)(_.slug === _)
      .filterIf(!params.includeArchived)(_.notDeletedNow)
      .sortBy(entity =>
        params.sortBy.getOrElse(OrganizationSortBy.default) match {
          case OrganizationSortBy.OrganizationNameAsc  => entity.name.asc
          case OrganizationSortBy.OrganizationNameDesc => entity.name.desc
          case OrganizationSortBy.CreatedAtAsc         => entity.createdAt.asc
          case OrganizationSortBy.CreatedAtDesc        => entity.createdAt.desc
        }
      )

    val paginedQuery = params.pageSizeAndNumber.fold(query) { page =>
      query.drop(page.offset).take(page.size)
    }

    if (params.forUpdate) paginedQuery.forUpdate else paginedQuery
  }
}
