package pl.iterators.epoca.mail.utils

import cats.effect.IO
import io.circe.JsonObject
import pl.iterators.epoca.mail.domain.MailDomain.{MailAddress, MailType}
import pl.iterators.epoca.mail.services.MailerService
import pl.iterators.epoca.mail.services.MailerService.{Attachment, From}
import pl.iterators.epoca.utils.log.LoggingSupport

abstract class GenericEmailSender[T](mailer: MailerService, mailType: MailType) extends LoggingSupport {
  def send(event: T): IO[Unit] =
    for {
      _       <- Logger[IO].info(s"Sending $mailType for ${emailGetter(event)}")
      mapping <- mapper(event)
      res     <- mailer.send(emailGetter(event), mailType, mapping, attachments(event), fromOverride(event))
      _ = Logger[IO].info(s"Sending result $res")
    } yield ()
  protected def mapper: T => IO[JsonObject]
  protected def emailGetter: T => MailAddress
  protected def attachments: T => List[Attachment] = _ => List.empty[Attachment]
  protected def fromOverride: T => Option[From]    = _ => None
}
