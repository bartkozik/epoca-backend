package pl.iterators.epoca.mail.services

import cats.effect.unsafe.IORuntime
import cats.effect.{Clock, IO}
import com.typesafe.config.{Config => TsConfig}
import io.circe.JsonObject
import io.circe.syntax._
import pl.iterators.epoca.mail.domain.MailDomain._
import pl.iterators.epoca.mail.repositories.MailHistoryRepository
import pl.iterators.epoca.mail.services.MailerService.{Attachment, From, MailSendResult}
import pl.iterators.epoca.mail.services.SendgridMailer._
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.json.JsonProtocol
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.service.SealedMonadServiceIODBIO
import pl.iterators.sealedmonad.Sealed
import pl.iterators.sealedmonad.syntax._
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4._
import sttp.model._

import java.util.UUID
import scala.jdk.CollectionConverters._

class SendgridMailerService(
  transactor: PostgresIOTransactor,
  templatesResolver: SendgridTemplatesResolver,
  config: SendgridMailer.Config,
  mailHistoryRepository: MailHistoryRepository,
  httpClient: WebSocketStreamBackend[IO, Fs2Streams[IO]]
)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIODBIO[MailSendResult](ioRuntime)
    with MailerService
    with LoggingSupport
    with JsonProtocol {

  override def send(
    toEmail: MailAddress,
    mailType: MailType,
    replacements: JsonObject,
    attachments: List[Attachment],
    fromOverride: Option[From]
  ): IO[MailSendResult] =
    (for {
      _ <- checkIfShouldBeSkipped(toEmail, mailType)
      uuid = MailId(UUID.randomUUID())
      template <- getTemplate(mailType)
      data = prepareData(uuid, toEmail, template, replacements, fromOverride, Option.when(attachments.nonEmpty)(attachments))
      history <- createPendingHistory(uuid, mailType, toEmail)
      success <- send(toEmail, mailType, data)
      _       <- updateHistory(history, success)
    } yield if (success) MailSendResult.Sent else MailSendResult.Error).run.handleErrorWith { error =>
      if (invitationMail(mailType)) Logger[IO].error(s"Invitation mail $mailType request failed: MAIL_EXCEPTION").as(MailSendResult.Error)
      else IO.raiseError(error)
    }

  private def invitationMail(mailType: MailType): Boolean =
    mailType == MailType.UserOrganizationInvitation || mailType == MailType.UserOrganizationInvitationPl

  private def checkIfShouldBeSkipped(email: MailAddress, mailType: MailType): StepIO[Unit] = {
    val splitted = email.split('@')
    val shouldBeSent = splitted.lift(1).fold(true) { domain =>
      !config.blacklistedDomains.contains(domain.toLowerCase)
    }

    val skippedMessage =
      if (invitationMail(mailType)) s"Skipping email sending '$mailType'"
      else s"Skipping email sending '$mailType' to '$email'"
    Sealed
      .liftF(())
      .ensureF(_ => shouldBeSent, Logger[IO].info(skippedMessage) *> IO.pure(MailSendResult.Skipped))
  }

  private def getTemplate(mailType: MailType): StepIO[String] =
    templatesResolver
      .getIdForTemplateKey(mailType)
      .valueOrF {
        Logger[IO].error(s"Unable to find template for key $mailType") *> IO.pure(MailSendResult.TemplateNotFound)
      }

  private def prepareData(
    mailUuid: MailId,
    to: MailAddress,
    template: String,
    replacements: JsonObject,
    fromOverride: Option[From],
    attachments: Option[List[Attachment]]
  ): Data =
    Data(
      fromOverride.getOrElse(From(config.fromEmail, config.fromName)),
      List(Personalization(List(Email(to)), replacements)),
      template,
      CustomArgs(mailUuid, config.envName),
      attachments
    )

  private def send(
    to: MailAddress,
    mailType: MailType,
    objectToSend: Data
  ): StepIO[Boolean] = (for {
    _ <- Logger[IO].info(if (invitationMail(mailType)) s"Sending email $mailType" else s"Sending email $mailType to $to")
    response <- basicRequest
                  .post(uri"${config.sendUrl}")
                  .contentType(MediaType.ApplicationJson)
                  .body(objectToSend.asJson.toString)
                  .headers(Header("Authorization", s"Bearer ${config.apiKey}"))
                  .send(httpClient)
    result <- ((response.body, response.code.isSuccess) match {
                case (Right(_), true) =>
                  val message = if (invitationMail(mailType)) s"Mail $mailType sent successfully" else s"Mail $mailType sent successfully to $to"
                  Logger[IO].info(message) *> IO.pure(true)
                case (Right(_), false) =>
                  val message =
                    if (invitationMail(mailType)) s"Invitation mail $mailType request failed: MAIL_ERROR (status=${response.code.code})"
                    else s"Request finished with error code ${response.code} but with proper response"
                  Logger[IO].warning(message) *> IO.pure(false)
                case (Left(error), _) =>
                  val message =
                    if (invitationMail(mailType)) s"Invitation mail $mailType request failed: MAIL_ERROR (status=${response.code.code})"
                    else s"Unable to send email $mailType to $to. Error: $error"
                  Logger[IO].error(message) *> IO.pure(false)
              }).recoverWith { case e =>
                val message =
                  if (invitationMail(mailType)) s"Invitation mail $mailType request failed: MAIL_EXCEPTION"
                  else s"Request failed with exception ${e.getMessage}"
                Logger[IO].error(message).as(false)
              }
  } yield result).recoverWith {
    case _ if invitationMail(mailType) => Logger[IO].error(s"Invitation mail $mailType request failed: MAIL_EXCEPTION").as(false)
  }.seal

  private def createPendingHistory(
    mailUuid: MailId,
    mailType: MailType,
    email: MailAddress
  ): StepIO[MailHistory] = Clock[IO].instantNow.flatMap { now =>
    transactor.execute {
      mailHistoryRepository.create(MailHistory(mailUuid, mailType, email, now, None, None, None, None))
    }
  }.seal

  private def updateHistory(history: MailHistory, isSuccess: Boolean): StepIO[Int] =
    Clock[IO].instantNow.flatMap { now =>
      transactor.execute {
        mailHistoryRepository.updateApiSuccess(history.mailUuid, Option.when(isSuccess)(now))
      }
    }.seal
}

object SendgridMailer extends {
  case class Config(
    subjectPrefix: String,
    appUrl: String,
    blacklistedDomains: List[String],
    fromEmail: String,
    fromName: String,
    apiKey: String,
    sendUrl: String,
    templatesUrl: String,
    envName: String,
    invitationTemplatePl: String = "userorganizationinvitationpl",
    invitationTemplateEn: String = "userorganizationinvitation")

  object Config {
    def apply(config: TsConfig): Config =
      Config(
        appUrl = config.getString("appFrontEnd.url"),
        invitationTemplatePl = config.getString("mail.invitation.templates.pl"),
        invitationTemplateEn = config.getString("mail.invitation.templates.en"),
        envName = config.getString("app.environment"),
        subjectPrefix = config.getString("mail.subjectPrefix"),
        blacklistedDomains = config.getStringList("mail.blacklistedDomains").asScala.toList,
        fromEmail = config.getString("mail.fromEmail"),
        fromName = config.getString("mail.fromName"),
        apiKey = config.getString("mail.sendgrid.apiKey"),
        sendUrl = config.getString("mail.sendgrid.sendUrl"),
        templatesUrl = config.getString("mail.sendgrid.templatesUrl")
      )
  }

  case class Email(email: String)
  case class Personalization(to: List[Email], dynamic_template_data: JsonObject)

  case class CustomArgs(mailUuid: MailId, environment: String)

  case class Data(
    from: From,
    personalizations: List[Personalization],
    template_id: String,
    custom_args: CustomArgs,
    attachments: Option[List[Attachment]])
}
