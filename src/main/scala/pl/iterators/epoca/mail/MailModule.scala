package pl.iterators.epoca.mail

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import com.softwaremill.macwire.wire
import com.typesafe.config.Config
import org.apache.pekko.http.scaladsl.server.Route
import pl.iterators.epoca.mail.emails._
import pl.iterators.epoca.mail.jobs.SendgridMailSyncScheduledJob
import pl.iterators.epoca.mail.repositories._
import pl.iterators.epoca.mail.routers.SendgridWebhookRouter
import pl.iterators.epoca.mail.services._
import pl.iterators.epoca.mail.utils.CommonMappingPreparer
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.http.RouteProvider
import pl.iterators.epoca.utils.job._
import pl.iterators.epoca.utils.log.TracingContext
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.WebSocketStreamBackend

import scala.concurrent.ExecutionContext

trait MailModule extends RouteProvider with SchedulableJobProvider {

  implicit val executor: ExecutionContext
  implicit val runtime: IORuntime
  val config: Config
  val transactor: PostgresIOTransactor
  val httpClient: WebSocketStreamBackend[IO, Fs2Streams[IO]]
  val invitationHttpClient: WebSocketStreamBackend[IO, Fs2Streams[IO]]

  protected lazy val mailHistoryRepository: MailHistoryRepository             = wire[MailHistoryRepositoryImpl]
  protected lazy val sendgridMailEventRepository: SendgridMailEventRepository = wire[SendgridMailEventRepositoryImpl]

  protected lazy val sendgridTemplatesResolver: SendgridTemplatesResolver =
    new SendgridTemplatesResolver(httpClient, sendgridMailerConfig)

  protected lazy val sendgridMailEventCreateService: SendgridMailEventCreateService = wire[SendgridMailEventCreateService]

  lazy val sendgridMailerConfig: SendgridMailer.Config = SendgridMailer.Config(config)

  lazy val mailerService: MailerService =
    new SendgridMailerService(transactor, sendgridTemplatesResolver, sendgridMailerConfig, mailHistoryRepository, httpClient)

  protected lazy val sendgridMailSyncService: SendgridMailSyncService =
    wire[SendgridMailSyncService]

  protected lazy val sendgridMailSyncScheduledJobConfig: SchedulableJobConfig = SchedulableJobConfig(config.getConfig("mail.sync"))

  protected lazy val sendgridMailSyncScheduledJob: SendgridMailSyncScheduledJob = wire[SendgridMailSyncScheduledJob]

  private lazy val commonMappingPreparer = wire[CommonMappingPreparer]

  // Avoid the general STTP exception logger: provider exceptions can echo secret template data.
  lazy val invitationMailSender: InvitationMailSender = new UserOrganizationInvitation(
    new SendgridMailerService(transactor, sendgridTemplatesResolver, sendgridMailerConfig, mailHistoryRepository, invitationHttpClient),
    sendgridMailerConfig
  )

  lazy val mailUserMagicLoginLink: UserMagicLoginLink                         = wire[UserMagicLoginLink]
  lazy val mailUserIdentityConfirmationCode: UserIdentityConfirmationCodeMail = wire[UserIdentityConfirmationCodeMail]

  private lazy val sendgridWebhookVerifier: SendgridWebhookVerifier =
    new SendgridWebhookVerifier(SendgridWebhookVerifier.Config(config))

  private lazy val sendgridWebhookRouter: SendgridWebhookRouter = wire[SendgridWebhookRouter]

  abstract override def schedulableJobs: List[SchedulableJob] = sendgridMailSyncScheduledJob :: super.schedulableJobs

  abstract override def route(implicit tracingContext: TracingContext): Route = super.route ~ sendgridWebhookRouter.routes
}
