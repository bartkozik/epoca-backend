package pl.iterators.epoca.saml.domain

import io.circe.Json
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId
import pl.iterators.epoca.user.domain.UserDomain.UserId

import java.time.Instant

final case class SamlBindingKey(
  organizationId: OrganizationId,
  trustEpoch: Long,
  issuer: String,
  subject: String)
final case class SamlIdentityBinding(
  key: SamlBindingKey,
  userId: UserId,
  canonicalEmail: String,
  verifiedAt: Instant,
  proof: Json)
