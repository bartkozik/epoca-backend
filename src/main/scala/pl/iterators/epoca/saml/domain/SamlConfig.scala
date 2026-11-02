package pl.iterators.epoca.saml.domain

import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId
import pl.iterators.epoca.saml.domain.SamlDomain._

case class SamlConfig(
  organizationId: OrganizationId,
  idpCert: SamlIdpCert,
  idpEntityId: SamlIdpEntityId,
  idpSsoUrl: SamlIdpSsoUrl,
  langCode: SamlLangCode,
  validFor: SamlValidityDuration,
  allowAccountInit: SamlAllowAccountInitialization,
  isEnabled: SamlIsEnabled,
  createdAt: SamlCreatedAt)
