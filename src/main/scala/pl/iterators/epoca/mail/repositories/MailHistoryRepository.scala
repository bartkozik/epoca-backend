package pl.iterators.epoca.mail.repositories

import pl.iterators.epoca.mail.domain.MailDomain._
import pl.iterators.epoca.mail.repositories.MailHistoryRepository.MailHistoryFilters
import pl.iterators.epoca.utils.db.FilterParamType
import slick.dbio.DBIO

import java.time.Instant

trait MailHistoryRepository {

  def create(mail: MailHistory): DBIO[MailHistory]
  def update(mail: MailHistory): DBIO[Option[MailHistory]]
  def updateApiSuccess(mailUuid: MailId, apiSuccess: Option[Instant]): DBIO[Int]
  def updateWebhookTimestamps(
    mailUuid: MailId,
    delivered: Option[Instant],
    opened: Option[Instant],
    clicked: Option[Instant]
  ): DBIO[Int]
  def find(filters: MailHistoryFilters): DBIO[Option[MailHistory]]
  def list(filters: MailHistoryFilters): DBIO[Seq[MailHistory]]

}

object MailHistoryRepository {
  final case class MailHistoryFilters(mailUuid: Option[MailId] = None) extends FilterParamType
}
