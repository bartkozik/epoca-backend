package pl.iterators.epoca.oauth.domain

import pl.iterators.epoca.oauth.domain.OAuthDomain._
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId
import pl.iterators.epoca.user.domain.UserDomain.UserId

final case class OAuthClient(
  id: OAuthClientId,
  clientId: OAuthClientPublicId,
  organizationId: OrganizationId,
  serviceAccountUserId: UserId,
  name: OAuthClientName,
  status: OAuthClientStatus,
  projectAccessMode: OAuthProjectAccessMode,
  secretHash: OAuthClientSecretHash,
  secretVersion: OAuthClientSecretVersion,
  version: OAuthClientVersion,
  policyVersion: OAuthClientPolicyVersion,
  createdAt: OAuthClientCreatedAt,
  createdBy: UserId,
  modifiedAt: OAuthClientModifiedAt,
  modifiedBy: UserId,
  secretRotatedAt: OAuthClientSecretRotatedAt,
  secretRotatedBy: UserId,
  revokedAt: Option[OAuthClientRevokedAt],
  revokedBy: Option[UserId],
  expiresAt: Option[OAuthClientExpiresAt] = None,
  lastUsedAt: Option[OAuthClientLastUsedAt] = None) {
  override def toString: String =
    s"OAuthClient(id=$id,clientId=<redacted>,organizationId=$organizationId,serviceAccountUserId=$serviceAccountUserId," +
      s"name=$name,status=$status,projectAccessMode=$projectAccessMode,secretHash=<redacted>,secretVersion=$secretVersion," +
      s"version=$version,policyVersion=$policyVersion,createdAt=$createdAt,createdBy=$createdBy,modifiedAt=$modifiedAt," +
      s"modifiedBy=$modifiedBy,secretRotatedAt=$secretRotatedAt,secretRotatedBy=$secretRotatedBy,revokedAt=$revokedAt," +
      s"revokedBy=$revokedBy,expiresAt=$expiresAt,lastUsedAt=$lastUsedAt)"
}
