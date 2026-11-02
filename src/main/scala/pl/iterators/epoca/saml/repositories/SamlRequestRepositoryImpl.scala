package pl.iterators.epoca.saml.repositories

import pl.iterators.epoca.saml.domain.SamlDomain.{SamlOneTimeToken, SamlRequestId, SamlRequestUsed}
import pl.iterators.epoca.saml.domain.SamlRequest
import pl.iterators.epoca.saml.repositories.tables.SamlRequestTable
import pl.iterators.epoca.utils.db.BaseRepository.driver.api._
import pl.iterators.epoca.utils.db.IdRepository
import slick.ast.BaseTypedType
import slick.lifted.TableQuery

import scala.concurrent.ExecutionContext

class SamlRequestRepositoryImpl(implicit e: ExecutionContext)
    extends IdRepository[SamlRequest, SamlRequestId, SamlRequestTable]
    with SamlRequestRepository {
  override protected def ec: ExecutionContext                          = e
  override protected val table: TableQuery[SamlRequestTable]           = TableQuery[SamlRequestTable]
  override protected def getId: SamlRequest => SamlRequestId           = _.id
  override protected def idBaseTypedType: BaseTypedType[SamlRequestId] = implicitly[BaseTypedType[SamlRequestId]]

  override def lock(id: SamlRequestId): DBIO[Option[SamlRequest]] = table.filter(_.id === id).forUpdate.result.headOption
  override def consume(id: SamlRequestId, token: SamlOneTimeToken): DBIO[Boolean] = table
    .filter(r => r.id === id && r.token === token && r.used === SamlRequestUsed(false))
    .map(_.used)
    .update(SamlRequestUsed(true))
    .map(_ == 1)

  override def find(id: SamlOneTimeToken): DBIO[Option[SamlRequest]] = table.filter(_.token === id).result.headOption
}
