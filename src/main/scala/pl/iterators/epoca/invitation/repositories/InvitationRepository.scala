package pl.iterators.epoca.invitation.repositories

import pl.iterators.epoca.invitation.domain.Invitation
import pl.iterators.epoca.invitation.domain.InvitationDomain._
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId
import pl.iterators.epoca.user.domain.UserDomain.{UserId, UserIdentityValue}
import pl.iterators.epoca.utils.db.{FilterParamType, PageSizeAndNumber}
import slick.dbio.DBIO

import java.time.Instant

trait InvitationRepository {
  import InvitationRepository._

  /** Legacy phone reservations never share the canonical email namespace. */
  def lockPhone(value: UserIdentityValue): DBIO[Unit]
  def phoneCandidates(value: UserIdentityValue): DBIO[Vector[UserId]]
  def pendingPhone(organizationId: OrganizationId, value: UserIdentityValue): DBIO[Boolean]
  def insert(invitation: Invitation): DBIO[Invitation]
  def update(invitation: Invitation): DBIO[Option[Invitation]]
  def find(filter: InvitationFilters): DBIO[Option[Invitation]]
  def list(filter: InvitationFilters): DBIO[Seq[Invitation]]
  def count(filter: InvitationFilters): DBIO[Int]
}

object InvitationRepository {

  case class InvitationFilters(
    id: Option[InvitationId] = None,
    organizationId: Option[OrganizationId] = None,
    userId: Option[UserId] = None,
    includeArchived: Boolean = false,
    acceptableOn: Option[Instant] = None,
    pageSizeAndNumber: Option[PageSizeAndNumber] = None,
    protocolVersion: Option[Int] = None,
    boundSaml: Option[pl.iterators.epoca.auth.domain.AccountAuthentication.BoundSaml] = None)
      extends FilterParamType
}
