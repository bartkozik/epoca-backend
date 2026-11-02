package pl.iterators.epoca.client.repositories

import com.typesafe.config.{Config => TsConfig}
import pl.iterators.epoca.client.domain.Client
import pl.iterators.epoca.client.domain.ClientDomain.ClientId
import pl.iterators.epoca.client.repositories.ClientRepository.ClientFilters
import pl.iterators.epoca.client.repositories.tables.ClientTable
import pl.iterators.epoca.organization.repositories.tables.OrganizationUserTable
import pl.iterators.epoca.project.repositories.tables.ProjectTable
import pl.iterators.epoca.utils.db.BaseRepository.driver.api._
import pl.iterators.epoca.utils.db.{FilteringRepository, IdRepository}
import slick.ast.BaseTypedType
import slick.jdbc.SetParameter
import slick.lifted.TableQuery

import java.sql.Types
import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{Duration, FiniteDuration, _}

class ClientRepositoryImpl(config: ClientRepositoryImpl.Config = ClientRepositoryImpl.Config.Default)(implicit e: ExecutionContext)
    extends IdRepository[Client, ClientId, ClientTable]
    with FilteringRepository[Client, ClientTable, ClientFilters]
    with ClientRepository {

  private implicit val setUuid: SetParameter[UUID] =
    SetParameter((value, parameters) => parameters.setObject(value, Types.OTHER))

  override protected def ec: ExecutionContext                     = e
  override protected val table: TableQuery[ClientTable]           = TableQuery[ClientTable]
  override protected def getId: Client => ClientId                = _.id
  override protected def idBaseTypedType: BaseTypedType[ClientId] = implicitly[BaseTypedType[ClientId]]

  private lazy val orgUserTable: TableQuery[OrganizationUserTable] = TableQuery[OrganizationUserTable]
  private lazy val projectTable: TableQuery[ProjectTable]          = TableQuery[ProjectTable]

  override def count(filter: ClientFilters): DBIO[Int] =
    prepareFilterQuery(table, filter.copy(pageSizeAndNumber = None)).length.result

  private def setTransactionLockTimeout: DBIO[Unit] =
    sql"""SELECT pg_catalog.set_config('lock_timeout', ${config.postgresValue}, true)"""
      .as[String]
      .head
      .map(_ => ())

  override def lockForMutation(clientId: ClientId): DBIO[Unit] = {
    val rawClientId = clientId.asInstanceOf[UUID]
    setTransactionLockTimeout >>
      sql"""SELECT pg_catalog.pg_advisory_xact_lock(
             pg_catalog.hashtextextended('epoca:client-mutation:' || CAST($rawClientId AS UUID)::TEXT, 0)
           )::TEXT""".as[String].head.map(_ => ())
  }

  override protected def prepareFilterQuery(tableQuery: Query[ClientTable, Client, Seq], params: ClientFilters): Query[ClientTable, Client, Seq] = {
    val query = tableQuery
      .filterOpt(params.id)(_.id === _)
      .filterOpt(params.ids)(_.id inSet _)
      .filterOpt(params.organizationIds)(_.organizationId inSet _)
      .filterOpt(params.`type`)(_.`type` === _)
      .filterOpt(params.name)(_.name === _)
      .filterOpt(params.userId) { (row, userId) =>
        row.organizationId in orgUserTable.filter(_.userId === userId).map(_.organizationId)
      }
      .filterIf(!params.includeArchived)(_.notDeletedNow)

    val projectScopedQuery =
      if (params.requireActiveProject || params.projectIds.isDefined) {
        val activeProjects = projectTable
          .filterOpt(params.organizationIds)(_.organizationId inSet _)
          .filterOpt(params.projectIds)(_.id inSet _)
          .filter(_.notDeletedNow)
        query.filter(_.id in activeProjects.map(_.clientId))
      } else query

    val paginatedQuery = params.pageSizeAndNumber.fold(projectScopedQuery) { page =>
      projectScopedQuery.drop(page.offset).take(page.size)
    }

    if (params.forUpdate) paginatedQuery.forUpdate else paginatedQuery
  }
}

object ClientRepositoryImpl {
  final case class Config(lockTimeout: FiniteDuration) {
    require(lockTimeout.toMillis > 0, "client.mutation.lockTimeout must be at least 1ms")
    val postgresValue: String = s"${lockTimeout.toMillis}ms"
  }

  object Config {
    val Default: Config = Config(5.seconds)

    def apply(config: TsConfig): Config =
      Config(Duration.fromNanos(config.getDuration("client.mutation.lockTimeout").toNanos))
  }
}
