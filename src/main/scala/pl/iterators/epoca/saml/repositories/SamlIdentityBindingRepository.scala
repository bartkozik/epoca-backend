package pl.iterators.epoca.saml.repositories

import pl.iterators.epoca.saml.domain.{SamlBindingKey, SamlIdentityBinding}
import slick.dbio.DBIO

trait SamlIdentityBindingRepository {
  def find(key: SamlBindingKey): DBIO[Option[SamlIdentityBinding]]
  def lock(key: SamlBindingKey): DBIO[Option[SamlIdentityBinding]]

  /** Caller serializes creation with the target organization lock; identities are never linked by email. */
  def insert(binding: SamlIdentityBinding): DBIO[Unit]
}
