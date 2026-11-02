package pl.iterators.epoca.mail.repositories

import pl.iterators.epoca.mail.domain.MailDomain.SendgridMailEvent
import pl.iterators.epoca.mail.repositories.SendgridMailEventRepository.SendgridMailEventFilters
import pl.iterators.epoca.mail.repositories.tables.SendgridMailEventTable
import pl.iterators.epoca.utils.db.BaseRepository.driver.api._
import slick.dbio.DBIO
import slick.lifted.TableQuery

import scala.concurrent.ExecutionContext

class SendgridMailEventRepositoryImpl(implicit ec: ExecutionContext) extends SendgridMailEventRepository {

  override def create(events: Seq[SendgridMailEvent]): DBIO[Seq[SendgridMailEvent]] =
    (table ++= events).map(_ => events)

  override def update(event: SendgridMailEvent): DBIO[Option[SendgridMailEvent]] =
    table
      .filter(r => (r.mailUuid === event.mailUuid) && (r.email === event.email))
      .update(event)
      .map {
        case 0 => None
        case _ => Option(event)
      }

  override def list(filters: SendgridMailEventFilters): DBIO[Seq[SendgridMailEvent]] =
    query(filters)

  private def query(filters: SendgridMailEventFilters) =
    table
      .filterOpt(filters.processed) { (row, param) =>
        if (param) row.processed
        else row.processed.isEmpty || !row.processed
      }
      .result

  private val table = TableQuery[SendgridMailEventTable]

}
