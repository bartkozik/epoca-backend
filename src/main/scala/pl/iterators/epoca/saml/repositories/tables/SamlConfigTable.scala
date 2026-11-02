package pl.iterators.epoca.saml.repositories.tables

import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId
import pl.iterators.epoca.saml.domain.SamlConfig
import pl.iterators.epoca.saml.domain.SamlDomain._
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import pl.iterators.epoca.utils.db.{BaseTable, IdTable}
import slick.lifted.ProvenShape

class SamlConfigTable(tag: Tag) extends BaseTable[SamlConfig](tag, "saml_config") with IdTable[SamlConfig, OrganizationId] {

  def trustEpoch: Rep[Long] = column[Long]("trust_epoch")

  def id: Rep[OrganizationId]                                         = column[OrganizationId]("organization_id")
  def idpCert: Rep[SamlIdpCert]                                       = column[SamlIdpCert]("idp_cert")
  def idpEntityId: Rep[SamlIdpEntityId]                               = column[SamlIdpEntityId]("idp_entity_id")
  def idpSsoUrl: Rep[SamlIdpSsoUrl]                                   = column[SamlIdpSsoUrl]("idp_sso_url")
  def langCode: Rep[SamlLangCode]                                     = column[SamlLangCode]("lang_code")
  def validFor: Rep[SamlValidityDuration]                             = column[SamlValidityDuration]("validity_duration")
  def allowAccountInitialization: Rep[SamlAllowAccountInitialization] = column[SamlAllowAccountInitialization]("allow_account_init")
  def isEnabled: Rep[SamlIsEnabled]                                   = column[SamlIsEnabled]("is_enabled")
  def createdAt: Rep[SamlCreatedAt]                                   = column[SamlCreatedAt]("created_at")

  override def * : ProvenShape[SamlConfig] =
    (
      id,
      idpCert,
      idpEntityId,
      idpSsoUrl,
      langCode,
      validFor,
      allowAccountInitialization,
      isEnabled,
      createdAt
    ) <> (SamlConfig.tupled, SamlConfig.unapply _)
}
