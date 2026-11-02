package pl.iterators.epoca.mail.services

import cats.effect.IO
import pl.iterators.epoca.mail.domain.MailDomain.SendgridMailEvent
import pl.iterators.epoca.mail.repositories.SendgridMailEventRepository
import pl.iterators.epoca.utils.db.PostgresIOTransactor

class SendgridMailEventCreateService(transactor: PostgresIOTransactor, sendgridMailEventRepository: SendgridMailEventRepository) {
  def create(events: Seq[SendgridMailEvent]): IO[Seq[SendgridMailEvent]] =
    transactor.execute(sendgridMailEventRepository.create(events))
}
