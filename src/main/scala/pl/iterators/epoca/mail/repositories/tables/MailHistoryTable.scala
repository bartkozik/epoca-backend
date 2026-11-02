package pl.iterators.epoca.mail.repositories.tables

import pl.iterators.epoca.mail.domain.MailDomain._
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import pl.iterators.epoca.utils.db.{BaseTable, IdTable}
import slick.lifted.ProvenShape

import java.time.Instant

class MailHistoryTable(tag: Tag) extends BaseTable[MailHistory](tag, "mail_history") with IdTable[MailHistory, MailId] {

  def id = mailUuid

  def mailUuid: Rep[MailId]            = column[MailId]("mail_uuid")
  def mailType: Rep[MailType]          = column[MailType]("mail_type")
  def email: Rep[MailAddress]          = column[MailAddress]("email")
  def created: Rep[Instant]            = column[Instant]("created")
  def apiSuccess: Rep[Option[Instant]] = column[Option[Instant]]("api_success")
  def delivered: Rep[Option[Instant]]  = column[Option[Instant]]("delivered")
  def clicked: Rep[Option[Instant]]    = column[Option[Instant]]("clicked")
  def opened: Rep[Option[Instant]]     = column[Option[Instant]]("opened")

  override def * : ProvenShape[MailHistory] =
    (mailUuid, mailType, email, created, apiSuccess, delivered, clicked, opened) <>
      ((MailHistory.apply _).tupled, MailHistory.unapply)
}
