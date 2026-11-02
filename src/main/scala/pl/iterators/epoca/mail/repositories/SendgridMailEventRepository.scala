package pl.iterators.epoca.mail.repositories

import pl.iterators.epoca.mail.domain.MailDomain.SendgridMailEvent
import pl.iterators.epoca.mail.repositories.SendgridMailEventRepository.SendgridMailEventFilters
import pl.iterators.epoca.utils.db.FilterParamType
import slick.dbio.DBIO

trait SendgridMailEventRepository {

  def create(events: Seq[SendgridMailEvent]): DBIO[Seq[SendgridMailEvent]]
  def update(event: SendgridMailEvent): DBIO[Option[SendgridMailEvent]]
  def list(filters: SendgridMailEventFilters): DBIO[Seq[SendgridMailEvent]]

}

object SendgridMailEventRepository {
  case class SendgridMailEventFilters(processed: Option[Boolean] = None, environment: Option[String] = None) extends FilterParamType
}
