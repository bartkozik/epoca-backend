package pl.iterators.epoca.saml.domain

import pl.iterators.kebs.tag.meta.tagged
import pl.iterators.kebs.tagged._

import java.time.{Duration, Instant}
import java.util.UUID

@tagged trait Domain {
  trait SamlSpCertTag
  type SamlSpCert = String @@ SamlSpCertTag

  trait SamlSpKeyTag
  type SamlSpKey = String @@ SamlSpKeyTag

  trait SamlIdpCertTag
  type SamlIdpCert = String @@ SamlIdpCertTag

  trait SamlIdpEntityIdTag
  type SamlIdpEntityId = String @@ SamlIdpEntityIdTag

  trait SamlIdpSsoUrlTag
  type SamlIdpSsoUrl = String @@ SamlIdpSsoUrlTag

  trait SamlLangCodeTag
  type SamlLangCode = String @@ SamlLangCodeTag

  trait SamlValidityDurationTag
  type SamlValidityDuration = Duration @@ SamlValidityDurationTag

  trait SamlAllowAccountInitializationTag
  type SamlAllowAccountInitialization = Boolean @@ SamlAllowAccountInitializationTag

  trait SamlIsEnabledTag
  type SamlIsEnabled = Boolean @@ SamlIsEnabledTag

  trait SamlCreatedAtTag
  type SamlCreatedAt = Instant @@ SamlCreatedAtTag

  trait SamlRequestIdTag
  type SamlRequestId = String @@ SamlRequestIdTag

  trait SamlOneTimeTokenTag
  type SamlOneTimeToken = UUID @@ SamlOneTimeTokenTag

  trait SamlRequestUsedTag
  type SamlRequestUsed = Boolean @@ SamlRequestUsedTag

  trait SamlRequestCreatedAtTag
  type SamlRequestCreatedAt = Instant @@ SamlRequestCreatedAtTag
}

object SamlDomain extends Domain {}
