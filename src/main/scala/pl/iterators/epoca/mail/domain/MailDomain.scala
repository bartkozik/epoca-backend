package pl.iterators.epoca.mail.domain

import enumeratum.EnumEntry.Lowercase
import enumeratum.{Enum, EnumEntry}
import pl.iterators.kebs.tag.meta.tagged
import pl.iterators.kebs.tagged._

import java.time.Instant
import java.util.UUID
import scala.collection.immutable

@tagged trait Domain {
  trait MailIdTag
  type MailId = UUID @@ MailIdTag

  trait MailAddressTag
  type MailAddress = String @@ MailAddressTag
}

object MailDomain extends Domain {

  sealed trait MailType extends EnumEntry with Lowercase
  object MailType extends Enum[MailType] {
    case object UserOrganizationInvitation   extends MailType
    case object UserOrganizationInvitationPl extends MailType
    case object UserMagicLoginLink           extends MailType
    case object UserIdentityConfirmationCode extends MailType

    override def values: immutable.IndexedSeq[MailType] = findValues
  }

  case class MailHistory(
    mailUuid: MailId,
    mailType: MailType,
    email: MailAddress,
    created: Instant,
    apiSuccess: Option[Instant],
    delivered: Option[Instant],
    clicked: Option[Instant],
    opened: Option[Instant])

  case class SendgridMailEvent(
    mailUuid: MailId,
    environment: String,
    email: MailAddress,
    event: Option[String],
    ip: Option[String],
    mc_stats: Option[String],
    phase_id: Option[String],
    send_at: Option[Long],
    sg_content_type: Option[String],
    sg_event_id: Option[String],
    sg_message_id: Option[String],
    sg_template_id: Option[String],
    sg_template_name: Option[String],
    singlesend_id: Option[String],
    singlesend_name: Option[String],
    template_hash: Option[String],
    template_id: Option[String],
    template_version_id: Option[String],
    timestamp: Option[Long],
    useragent: Option[String],
    processed: Option[Boolean],
    reason: Option[String])

}
