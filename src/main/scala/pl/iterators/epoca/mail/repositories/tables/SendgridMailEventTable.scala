package pl.iterators.epoca.mail.repositories.tables

import pl.iterators.epoca.mail.domain.MailDomain._
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import slick.lifted.ProvenShape

class SendgridMailEventTable(tag: Tag) extends Table[SendgridMailEvent](tag, "sendgrid_mail_event") {

  def mailUuid: Rep[MailId]                    = column[MailId]("mail_uuid")
  def environment: Rep[String]                 = column[String]("environment")
  def email: Rep[MailAddress]                  = column[MailAddress]("email")
  def event: Rep[Option[String]]               = column[Option[String]]("event")
  def ip: Rep[Option[String]]                  = column[Option[String]]("ip")
  def mc_stats: Rep[Option[String]]            = column[Option[String]]("mc_stats")
  def phase_id: Rep[Option[String]]            = column[Option[String]]("phase_id")
  def send_at: Rep[Option[Long]]               = column[Option[Long]]("send_at")
  def sg_content_type: Rep[Option[String]]     = column[Option[String]]("sg_content_type")
  def sg_event_id: Rep[Option[String]]         = column[Option[String]]("sg_event_id")
  def sg_message_id: Rep[Option[String]]       = column[Option[String]]("sg_message_id")
  def sg_template_id: Rep[Option[String]]      = column[Option[String]]("sg_template_id")
  def sg_template_name: Rep[Option[String]]    = column[Option[String]]("sg_template_name")
  def singlesend_id: Rep[Option[String]]       = column[Option[String]]("singlesend_id")
  def singlesend_name: Rep[Option[String]]     = column[Option[String]]("singlesend_name")
  def template_hash: Rep[Option[String]]       = column[Option[String]]("template_hash")
  def template_id: Rep[Option[String]]         = column[Option[String]]("template_id")
  def template_version_id: Rep[Option[String]] = column[Option[String]]("template_version_id")
  def timestamp: Rep[Option[Long]]             = column[Option[Long]]("timestamp")
  def useragent: Rep[Option[String]]           = column[Option[String]]("useragent")
  def processed: Rep[Option[Boolean]]          = column[Option[Boolean]]("processed")
  def reason: Rep[Option[String]]              = column[Option[String]]("reason")

  override def * : ProvenShape[SendgridMailEvent] =
    (
      mailUuid,
      environment,
      email,
      event,
      ip,
      mc_stats,
      phase_id,
      send_at,
      sg_content_type,
      sg_event_id,
      sg_message_id,
      sg_template_id,
      sg_template_name,
      singlesend_id,
      singlesend_name,
      template_hash,
      template_id,
      template_version_id,
      timestamp,
      useragent,
      processed,
      reason
    ) <>
      ((SendgridMailEvent.apply _).tupled, SendgridMailEvent.unapply)
}
