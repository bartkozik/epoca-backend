package pl.iterators.epoca.mail.repositories

import pl.iterators.epoca.mail.domain.MailDomain.{MailHistory, MailId}
import pl.iterators.epoca.mail.repositories.MailHistoryRepository.MailHistoryFilters
import pl.iterators.epoca.mail.repositories.tables.MailHistoryTable
import pl.iterators.epoca.utils.db.BaseRepository.driver.api._
import slick.dbio.DBIO
import slick.jdbc.SetParameter
import slick.lifted.TableQuery

import java.sql.{Timestamp, Types}
import java.time.Instant
import java.util.UUID
import scala.concurrent.ExecutionContext

class MailHistoryRepositoryImpl(implicit ec: ExecutionContext) extends MailHistoryRepository {

  private implicit val setUuid: SetParameter[UUID] = SetParameter((value, parameters) => parameters.setObject(value, Types.OTHER))
  private implicit val setInstantOption: SetParameter[Option[Instant]] = SetParameter { (value, parameters) =>
    value match {
      case Some(instant) => parameters.setTimestamp(Timestamp.from(instant))
      case None          => parameters.setNull(Types.TIMESTAMP)
    }
  }

  override def create(mail: MailHistory): DBIO[MailHistory] =
    (table returning table) += mail

  override def update(mail: MailHistory): DBIO[Option[MailHistory]] =
    table
      .filter(r => r.mailUuid === mail.mailUuid)
      .update(mail)
      .map(r => Option.when(r > 0)(mail))

  override def updateApiSuccess(mailUuid: MailId, apiSuccess: Option[Instant]): DBIO[Int] =
    table
      .filter(_.mailUuid === mailUuid)
      .map(_.apiSuccess)
      .update(apiSuccess)

  override def updateWebhookTimestamps(
    mailUuid: MailId,
    delivered: Option[Instant],
    opened: Option[Instant],
    clicked: Option[Instant]
  ): DBIO[Int] = {
    val uuid = mailUuid.asInstanceOf[UUID]
    sqlu"""
      UPDATE mail_history
      SET delivered = COALESCE(delivered, $delivered, $opened, $clicked),
          opened = COALESCE(opened, $opened, $clicked),
          clicked = COALESCE(clicked, $clicked)
      WHERE mail_uuid = $uuid
    """
  }

  override def find(filters: MailHistoryFilters): DBIO[Option[MailHistory]] =
    query(filters).map(_.headOption)

  override def list(filters: MailHistoryFilters): DBIO[Seq[MailHistory]] =
    query(filters)

  private def query(filters: MailHistoryFilters) =
    table
      .filterOpt(filters.mailUuid)(_.mailUuid === _)
      .sortBy(_.created)
      .result

  private val table = TableQuery[MailHistoryTable]
}
