package pl.iterators.epoca.saml.repositories

import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId
import pl.iterators.epoca.saml.domain.SamlConfig
import pl.iterators.epoca.saml.repositories.tables.SamlConfigTable
import pl.iterators.epoca.utils.db.BaseRepository.driver.api._
import pl.iterators.epoca.utils.db.IdRepository
import slick.ast.BaseTypedType
import slick.lifted.TableQuery

import scala.concurrent.ExecutionContext

class SamlConfigRepositoryImpl(implicit e: ExecutionContext)
    extends IdRepository[SamlConfig, OrganizationId, SamlConfigTable]
    with SamlConfigRepository {

  override protected def ec: ExecutionContext                           = e
  override protected val table: TableQuery[SamlConfigTable]             = TableQuery[SamlConfigTable]
  override protected def getId: SamlConfig => OrganizationId            = _.organizationId
  override protected def idBaseTypedType: BaseTypedType[OrganizationId] = implicitly[BaseTypedType[OrganizationId]]
  override def trustEpoch(id: OrganizationId): DBIO[Option[Long]]       = table.filter(_.id === id).map(_.trustEpoch).result.headOption

  /** The organization lock serializes creation, replacement and SAML use even when no config row exists. */
  override def upsert(value: SamlConfig): DBIO[SamlConfig] = (for {
    _            <- sql"SELECT id::text FROM organization WHERE id=${value.organizationId.toString}::uuid FOR UPDATE".as[String]
    previous     <- find(value.organizationId)
    currentEpoch <- trustEpoch(value.organizationId)
    next <- (previous, currentEpoch) match {
              case (Some(old), Some(epoch)) =>
                DBIO.successful(
                  if (old.idpCert == value.idpCert && old.idpEntityId == value.idpEntityId && old.idpSsoUrl == value.idpSsoUrl) epoch
                  else Math.addExact(epoch, 1L)
                )
              case _ =>
                sql"SELECT GREATEST(COALESCE((SELECT max(trust_epoch) FROM saml_identity_binding WHERE organization_id=${value.organizationId.toString}::uuid),0), COALESCE((SELECT max(trust_epoch) FROM saml_request WHERE organization_id=${value.organizationId.toString}::uuid),0))"
                  .as[Long]
                  .head
                  .map(epoch => Math.addExact(epoch, 1L))
            }
    saved <- super.upsert(value)
    _     <- table.filter(_.id === value.organizationId).map(_.trustEpoch).update(next)
  } yield saved).transactionally
}
