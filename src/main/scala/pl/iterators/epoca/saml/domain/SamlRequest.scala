package pl.iterators.epoca.saml.domain

import pl.iterators.epoca.auth.domain.AccountAuthentication
import pl.iterators.epoca.invitation.domain.InvitationDomain.InvitationAccessId
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId
import pl.iterators.epoca.saml.domain.SamlDomain._
import pl.iterators.epoca.user.domain.UserDomain.UserId

import java.time.{Duration, Instant}
import java.util.UUID

case class SamlRequest(
  id: SamlRequestId,
  token: Option[SamlOneTimeToken],
  userId: Option[UserId],
  used: SamlRequestUsed,
  createdAt: SamlRequestCreatedAt,
  accountAuthentication: Option[AccountAuthentication] = None,
  organizationId: Option[OrganizationId] = None,
  trustEpoch: Option[Long] = None,
  purpose: Option[String] = None,
  browserNonceHash: Option[Vector[Byte]] = None,
  invitationAccessId: Option[InvitationAccessId] = None,
  verifiedIssuer: Option[String] = None,
  verifiedSubject: Option[String] = None,
  verifiedCanonicalEmail: Option[String] = None,
  verifiedAt: Option[Instant] = None) {
  def isValid(now: Instant, validityDuration: Duration): Boolean = now.isBefore(createdAt.plus(validityDuration))
  def withTokenForUser(userId: UserId, uuid: UUID): SamlRequest  = copy(userId = Some(userId), token = Some(SamlOneTimeToken(uuid)))
  def markUsed: SamlRequest                                      = copy(used = SamlRequestUsed(true))
}
