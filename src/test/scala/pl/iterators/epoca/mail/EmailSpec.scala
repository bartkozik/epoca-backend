package pl.iterators.epoca.mail

import cats.Monad
import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import cats.effect.unsafe.IORuntime
import cats.implicits.catsStdInstancesForFuture
import com.softwaremill.macwire.wire
import org.apache.pekko.actor.ActorSystem
import org.mockito.invocation.InvocationOnMock
import org.mockito.{ArgumentMatchersSugar, MockitoSugar}
import org.specs2.mutable.SpecificationLike
import pl.iterators.epoca.mail.domain.MailDomain.{MailAddress, MailHistory}
import pl.iterators.epoca.mail.repositories.MailHistoryRepository
import pl.iterators.epoca.mail.services.{MailerService, SendgridMailer, SendgridMailerService, SendgridTemplatesResolver}
import pl.iterators.epoca.mail.utils.{CommonMappingPreparer, GenericEmailSender}
import pl.iterators.epoca.utils.Generators
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.transactor.FakeTransactor
import slick.dbio.DBIO
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.WebSocketStreamBackend
import sttp.client4.httpclient.fs2.HttpClientFs2Backend

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.Try

trait EmailSpec[T] extends SpecificationLike with MockitoSugar with ArgumentMatchersSugar with Generators with CatsEffect {

  val timeout: FiniteDuration = 300.second

  skipAllIf(getValidatedEmailOpt.isEmpty)

  implicit val system: ActorSystem                                = org.apache.pekko.actor.ActorSystem.apply()
  implicit val ec: ExecutionContextExecutor                       = system.getDispatcher
  implicit val ioRuntime: IORuntime                               = cats.effect.unsafe.implicits.global
  val transactor: PostgresIOTransactor                            = new FakeTransactor()
  lazy val httpClient: WebSocketStreamBackend[IO, Fs2Streams[IO]] = HttpClientFs2Backend.resource[IO]().allocated.unsafeRunSync()(ioRuntime)._1
  implicit val get: Monad[Future]                                 = catsStdInstancesForFuture(ec)

  val sendgridMailerConfig: SendgridMailer.Config = SendgridMailer.Config(
    subjectPrefix = sys.env.getOrElse("MAILER_SUBJECT_PREFIX", "[DEV]"),
    appUrl = "http://localhost",
    blacklistedDomains = sys.env.get("MAILER_BLACKLISTED_DOMAINS").map(_.split(',').toList).getOrElse(Nil),
    fromEmail = sys.env.getOrElse("SENDGRID_SENDER", ""),
    fromName = "Dev",
    apiKey = sys.env.getOrElse("SENDGRID_API_KEY", ""),
    sendUrl = "https://api.sendgrid.com/v3/mail/send",
    templatesUrl = "https://api.sendgrid.com/v3/templates?generations=dynamic",
    envName = "DEV"
  )
  val templateResolver: SendgridTemplatesResolver = wire[SendgridTemplatesResolver]

  val mailHistoryRepository: MailHistoryRepository = mock[MailHistoryRepository]
  when(mailHistoryRepository.create(any)).thenAnswer((invocation: InvocationOnMock) =>
    DBIO.successful(invocation.getArguments()(0).asInstanceOf[MailHistory])
  )

  val commonMappingPreparer: CommonMappingPreparer = wire[CommonMappingPreparer]
  val mailer: MailerService                        = wire[SendgridMailerService]

  def getValidatedEmailOpt: Option[MailAddress] =
    Try(MailAddress(sys.env.getOrElse("SENDGRID_TEST_EMAIL_RECEIVER", ""))).toOption
  def getValidatedEmail = getValidatedEmailOpt.get

  val sut: GenericEmailSender[T]
  val event: T

  "sending request" should {
    "work without exception" in {
      sut.send(event).unsafeRunSync() shouldEqual ()
    }
  }

}
