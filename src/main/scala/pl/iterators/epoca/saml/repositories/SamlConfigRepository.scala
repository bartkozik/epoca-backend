package pl.iterators.epoca.saml.repositories

import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId
import pl.iterators.epoca.saml.domain.SamlConfig
import slick.dbio.DBIO

trait SamlConfigRepository {
  def upsert(samlConfig: SamlConfig): DBIO[SamlConfig]
  def find(id: OrganizationId): DBIO[Option[SamlConfig]]
  def trustEpoch(id: OrganizationId): DBIO[Option[Long]]
}
