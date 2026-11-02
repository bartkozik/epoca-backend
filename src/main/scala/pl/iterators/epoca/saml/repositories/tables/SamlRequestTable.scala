package pl.iterators.epoca.saml.repositories.tables

import pl.iterators.epoca.auth.domain.AccountAuthentication
import pl.iterators.epoca.invitation.domain.InvitationDomain.InvitationAccessId
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId
import pl.iterators.epoca.saml.domain.SamlDomain._
import pl.iterators.epoca.saml.domain.SamlRequest
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import pl.iterators.epoca.utils.db.{BaseTable, IdTable}
import slick.lifted.ProvenShape

import java.time.Instant

class SamlRequestTable(tag: Tag) extends BaseTable[SamlRequest](tag, "saml_request") with IdTable[SamlRequest, SamlRequestId] {

  def id: Rep[SamlRequestId]               = column[SamlRequestId]("id")
  def token: Rep[Option[SamlOneTimeToken]] = column[Option[SamlOneTimeToken]]("one_time_token")
  def userId: Rep[Option[UserId]]          = column[Option[UserId]]("user_id")
  def used: Rep[SamlRequestUsed]           = column[SamlRequestUsed]("used")
  def createdAt: Rep[SamlRequestCreatedAt] = column[SamlRequestCreatedAt]("created_at")

  def accountAuthentication: Rep[Option[AccountAuthentication]] = column[Option[AccountAuthentication]]("account_authentication")

  private implicit val nonceColumnType: BaseColumnType[Vector[Byte]] =
    MappedColumnType.base[Vector[Byte], Array[Byte]](_.toArray, _.toVector)
  def organizationId: Rep[Option[OrganizationId]]         = column[Option[OrganizationId]]("organization_id")
  def trustEpoch: Rep[Option[Long]]                       = column[Option[Long]]("trust_epoch")
  def purpose: Rep[Option[String]]                        = column[Option[String]]("purpose")
  def browserNonceHash: Rep[Option[Vector[Byte]]]         = column[Option[Vector[Byte]]]("browser_nonce_hash")
  def invitationAccessId: Rep[Option[InvitationAccessId]] = column[Option[InvitationAccessId]]("invitation_access_id")
  def verifiedIssuer: Rep[Option[String]]                 = column[Option[String]]("verified_issuer")
  def verifiedSubject: Rep[Option[String]]                = column[Option[String]]("verified_subject")
  def verifiedCanonicalEmail: Rep[Option[String]]         = column[Option[String]]("verified_canonical_email")
  def verifiedAt: Rep[Option[Instant]]                    = column[Option[Instant]]("verified_at")
  override def * : ProvenShape[SamlRequest] =
    (
      id,
      token,
      userId,
      used,
      createdAt,
      accountAuthentication,
      organizationId,
      trustEpoch,
      purpose,
      browserNonceHash,
      invitationAccessId,
      verifiedIssuer,
      verifiedSubject,
      verifiedCanonicalEmail,
      verifiedAt
    ) <>
      (SamlRequest.tupled, SamlRequest.unapply _)
}
