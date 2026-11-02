package pl.iterators.epoca.oauth.domain

import pl.iterators.epoca.oauth.domain.OAuthDomain.{OAuthClientId, OAuthProjectAccess, OAuthScope}
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId
import pl.iterators.epoca.user.domain.UserDomain.UserId

import java.util.UUID

final case class OAuthAuthContext(
  actorId: UserId,
  clientId: OAuthClientId,
  organizationId: OrganizationId,
  scopes: Set[OAuthScope],
  projectAccess: OAuthProjectAccess,
  tokenId: UUID,
  policyVersion: Long)
