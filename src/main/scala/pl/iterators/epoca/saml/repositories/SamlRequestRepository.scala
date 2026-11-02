package pl.iterators.epoca.saml.repositories

import pl.iterators.epoca.saml.domain.SamlDomain.{SamlOneTimeToken, SamlRequestId}
import pl.iterators.epoca.saml.domain.SamlRequest
import slick.dbio.DBIO

trait SamlRequestRepository {
  def insert(samlConfig: SamlRequest): DBIO[SamlRequest]
  def update(samlConfig: SamlRequest): DBIO[Option[SamlRequest]]
  def lock(id: SamlRequestId): DBIO[Option[SamlRequest]]
  def consume(id: SamlRequestId, token: SamlOneTimeToken): DBIO[Boolean]
  def find(id: SamlRequestId): DBIO[Option[SamlRequest]]
  def find(id: SamlOneTimeToken): DBIO[Option[SamlRequest]]
}
