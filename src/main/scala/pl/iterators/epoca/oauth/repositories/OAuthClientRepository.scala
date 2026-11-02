package pl.iterators.epoca.oauth.repositories

import pl.iterators.epoca.oauth.domain.OAuthClient
import pl.iterators.epoca.oauth.domain.OAuthDomain._
import pl.iterators.epoca.oauth.repositories.OAuthClientRepository.OAuthClientSnapshot
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId
import pl.iterators.epoca.project.domain.ProjectDomain.ProjectId
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.utils.db.BaseRepository.driver.api.DBIO
import pl.iterators.epoca.utils.db.PageSizeAndNumber

import java.time.Instant
import scala.concurrent.duration.FiniteDuration

trait OAuthClientRepository {

  /** Caller must compose this with technical-identity creation and audit in one transaction. */
  def insert(snapshot: OAuthClientSnapshot): DBIO[OAuthClientSnapshot]
  private[oauth] def insertPreparedClientRow(client: OAuthClient): DBIO[Int]
  private[oauth] def insertPreparedScopeRow(id: OAuthClientId, scope: OAuthScope): DBIO[Int]
  private[oauth] def insertPreparedProjectRow(id: OAuthClientId, projectId: ProjectId): DBIO[Int]
  def findPublic(clientId: OAuthClientPublicId): DBIO[Option[OAuthClientSnapshot]]
  def lock(id: OAuthClientId): DBIO[Option[OAuthClientSnapshot]]
  def list(filters: OAuthClientRepository.OAuthClientFilters, at: Instant): DBIO[Seq[OAuthClientSnapshot]]
  def count(filters: OAuthClientRepository.OAuthClientFilters, at: Instant): DBIO[Int]
  def replacePolicy(
    id: OAuthClientId,
    scopes: Set[OAuthScope],
    access: OAuthProjectAccess
  ): DBIO[OAuthClientSnapshot]
  def replacePolicyAtVersion(
    id: OAuthClientId,
    expectedVersion: Long,
    scopes: Set[OAuthScope],
    access: OAuthProjectAccess
  ): DBIO[OAuthClientRepository.OAuthPolicyReplaceResult]
  def replaceSecret(id: OAuthClientId, hash: String): DBIO[OAuthClientSnapshot]

  /** Caller-owned transaction boundary for management update plus technical-user rename and audit. */
  def updateAtVersion(
    id: OAuthClientId,
    expectedVersion: Long,
    name: OAuthClientName,
    scopes: Set[OAuthScope],
    access: OAuthProjectAccess,
    expiresAt: Option[OAuthClientExpiresAt],
    at: Instant,
    by: UserId
  ): DBIO[OAuthClientRepository.OAuthClientMutationResult]

  def touchLastUsedAt(
    clientId: OAuthClientId,
    now: Instant,
    minInterval: FiniteDuration
  ): DBIO[Boolean]

  /** Caller-owned transaction boundary for actor-aware rotation and its required audit. */
  def rotateSecretAtVersion(
    id: OAuthClientId,
    expectedVersion: Long,
    hash: String,
    at: Instant,
    by: UserId
  ): DBIO[OAuthClientRepository.OAuthClientMutationResult]
  def revoke(
    id: OAuthClientId,
    at: Instant,
    by: UserId
  ): DBIO[OAuthClientSnapshot]

  /** Caller-owned transaction boundary for optimistic revoke, identity archival, and audit. */
  def revokeAtVersion(
    id: OAuthClientId,
    expectedVersion: Long,
    at: Instant,
    by: UserId
  ): DBIO[OAuthClientRepository.OAuthClientMutationResult]
}

object OAuthClientRepository {
  final case class OAuthClientSnapshot(
    client: OAuthClient,
    scopes: Set[OAuthScope],
    projectAccess: OAuthProjectAccess)

  final case class OAuthClientFilters(
    organizationId: OrganizationId,
    status: Option[OAuthClientEffectiveStatus] = None,
    pageSizeAndNumber: Option[PageSizeAndNumber] = None)

  final case class OAuthClientNotFound(id: OAuthClientId) extends RuntimeException(s"OAuth client not found: $id")
  final case class OAuthClientRevoked(id: OAuthClientId)  extends RuntimeException(s"OAuth client is revoked: $id")

  sealed trait OAuthClientMutationResult
  object OAuthClientMutationResult {
    final case class Applied(snapshot: OAuthClientSnapshot) extends OAuthClientMutationResult
    final case class Stale(currentVersion: Long)            extends OAuthClientMutationResult
  }

  sealed trait OAuthPolicyReplaceResult
  object OAuthPolicyReplaceResult {
    final case class Applied(snapshot: OAuthClientSnapshot) extends OAuthPolicyReplaceResult
    final case class Stale(currentVersion: Long)            extends OAuthPolicyReplaceResult
  }
}
