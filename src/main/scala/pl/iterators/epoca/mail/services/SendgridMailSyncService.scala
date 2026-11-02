package pl.iterators.epoca.mail.services

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import cats.implicits._
import pl.iterators.epoca.mail.domain.MailDomain._
import pl.iterators.epoca.mail.repositories.SendgridMailEventRepository.SendgridMailEventFilters
import pl.iterators.epoca.mail.repositories.{MailHistoryRepository, SendgridMailEventRepository}
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.service.SealedMonadServiceIODBIO
import slick.dbio.DBIO

import java.time.Instant

case class OperationHolder(
  mailId: MailId,
  email: MailAddress,
  delivered: Option[Instant],
  opened: Option[Instant],
  clicked: Option[Instant])

class SendgridMailSyncService(
  transactor: PostgresIOTransactor,
  mailHistoryPersister: MailHistoryRepository,
  eventPersister: SendgridMailEventRepository,
  config: SendgridMailer.Config
)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIODBIO[Unit](ioRuntime)
    with LoggingSupport {

  def synchronize(): IO[Unit] =
    transactor.executeTransactionally(for {
      _      <- Logger[DBIO].debug("Synchronizing sendgrid emails")
      events <- listEvents
      _      <- markEventsAsProcessed(events)
      operations = transformEventsToOperations(events)
      _ <- performOperations(operations)
      _ <- Logger[DBIO].debug("Synchronizing sendgrid emails done")
    } yield ())

  private def listEvents: DBIO[Seq[SendgridMailEvent]] =
    eventPersister.list(SendgridMailEventFilters(processed = Some(false), environment = Some(config.envName)))

  private def markEventsAsProcessed(events: Seq[SendgridMailEvent]): DBIO[List[Option[SendgridMailEvent]]] =
    events.toList
      .traverse(event => eventPersister.update(event.copy(processed = Some(true))))

  private def transformEventsToOperations(events: Seq[SendgridMailEvent]): Seq[OperationHolder] =
    events.groupBy(event => (event.mailUuid, event.email)).map(toOperationHolder).toSeq

  private def toOperationHolder(group: ((MailId, MailAddress), Seq[SendgridMailEvent])): OperationHolder =
    OperationHolder(
      group._1._1,
      group._1._2,
      delivered = group._2.find(event => event.event.forall(_ == "delivered")).flatMap(_.timestamp.map(Instant.ofEpochSecond)),
      opened = group._2.find(event => event.event.forall(_ == "open")).flatMap(_.timestamp.map(Instant.ofEpochSecond)),
      clicked = group._2.find(event => event.event.forall(_ == "click")).flatMap(_.timestamp.map(Instant.ofEpochSecond))
    )

  private def performOperations(operations: Seq[OperationHolder]): DBIO[List[Int]] =
    operations.toList.traverse(update)

  private def update(oh: OperationHolder): DBIO[Int] =
    Logger[DBIO].info(s"Synchronizing mail history timestamps for mail ID: ${oh.mailId}") *>
      mailHistoryPersister.updateWebhookTimestamps(oh.mailId, oh.delivered, oh.opened, oh.clicked)
}
