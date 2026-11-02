package pl.iterators.epoca.mail.services

import io.circe.JsonObject
import org.mockito.invocation.InvocationOnMock
import pl.iterators.epoca.mail.domain.MailDomain.{MailAddress, MailHistory, MailId, MailType}
import pl.iterators.epoca.mail.repositories.MailHistoryRepository
import pl.iterators.epoca.mail.services.MailerService.{Attachment, From, MailSendResult}
import pl.iterators.epoca.utils.ServiceSpec
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.{Response, WebSocketStreamBackend}
import sttp.model.StatusCode

import scala.collection.mutable.ListBuffer

class SendgridMailerServiceSpec extends ServiceSpec {
  sequential

  trait TestCase extends BaseScope {
    val config: SendgridMailer.Config                          = mock[SendgridMailer.Config]
    val templatesResolver: SendgridTemplatesResolver           = vmock[SendgridTemplatesResolver]
    val httpClient: WebSocketStreamBackend[IO, Fs2Streams[IO]] = vmock[WebSocketStreamBackend[IO, Fs2Streams[IO]]]
    val mailHistoryRepository: MailHistoryRepository           = vmock[MailHistoryRepository]

    val sendgridMailerService: SendgridMailerService = wire[SendgridMailerService]
  }

  Seq(MailType.UserOrganizationInvitation, MailType.UserOrganizationInvitationPl).foreach { kind =>
    Seq("sent", "skipped", "response-error", "status-error", "exception").foreach { outcome =>
      s"redact invitation recipient and provider content for $kind ($outcome)" in new BaseScope {
        val config                = mock[SendgridMailer.Config]
        val templatesResolver     = mock[SendgridTemplatesResolver]
        val httpClient            = mock[WebSocketStreamBackend[IO, Fs2Streams[IO]]]
        val mailHistoryRepository = mock[MailHistoryRepository]
        val sendgridMailerService = new SendgridMailerService(transactor, templatesResolver, config, mailHistoryRepository, httpClient)
        val recipient             = MailAddress("PRIVATE_RECIPIENT@example.test")
        val secret                = "https://epoca.example/invitations/accept#token=SECRET_SENTINEL " + recipient
        val logger                = org.slf4j.LoggerFactory.getLogger(classOf[SendgridMailerService]).asInstanceOf[ch.qos.logback.classic.Logger]
        val appender              = new ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]
        val oldLevel              = logger.getLevel
        appender.start()
        logger.setLevel(ch.qos.logback.classic.Level.ALL)
        logger.addAppender(appender)
        when(config.blacklistedDomains).thenReturn(if (outcome == "skipped") List("example.test") else Nil)
        when(config.sendUrl).thenReturn("https://sendgrid.example.test/mail")
        when(templatesResolver.getIdForTemplateKey(kind)).thenReturn(Some("template").asIO)
        when(mailHistoryRepository.create(any)).thenAnswer { call: InvocationOnMock => call.getArgument[MailHistory](0).asDBIO }
        when(mailHistoryRepository.updateApiSuccess(any, any)).thenReturn(1.asDBIO)
        when(httpClient.send[Either[String, String]](any)).thenReturn(outcome match {
          case "exception"      => cats.effect.IO.raiseError(new RuntimeException(secret))
          case "response-error" => Response(Left(secret), StatusCode.BadRequest, "bad").asIO
          case "status-error"   => Response(Right(secret), StatusCode.BadRequest, "bad").asIO
          case _                => Response(Right(secret), StatusCode.Accepted, "accepted").asIO
        })
        try {
          val expected = outcome match {
            case "sent"    => MailSendResult.Sent
            case "skipped" => MailSendResult.Skipped
            case _         => MailSendResult.Error
          }
          val result = sendgridMailerService.send(recipient, kind, JsonObject.empty, Nil, None).attempt.unsafeRunSync()
          import scala.jdk.CollectionConverters._
          val logs =
            appender.list.asScala.map(e => e.getFormattedMessage + Option(e.getThrowableProxy).map(_.getMessage).getOrElse("")).mkString("\n")
          result mustEqual (Right(expected): Either[Throwable, MailSendResult])
          logs must not(contain("SECRET_SENTINEL"))
          logs must not(contain("PRIVATE_RECIPIENT"))
          logs must contain(kind.toString)
          if (outcome == "response-error" || outcome == "status-error") logs must contain("400")
          else success
        } finally {
          logger.detachAppender(appender)
          logger.setLevel(oldLevel)
          appender.stop()
        }
      }
    }
  }

  "production invitation mail wiring" should {
    "avoid the general HTTP exception logger when the provider echoes an invitation link" in new BaseScope {
      val backend = mock[WebSocketStreamBackend[IO, Fs2Streams[IO]]]
      when(backend.monad).thenReturn(new sttp.client4.impl.cats.CatsMonadAsyncError[cats.effect.IO])
      val resolver = mock[SendgridTemplatesResolver]
      when(resolver.getIdForTemplateKey(MailType.UserOrganizationInvitation)).thenReturn(Some("template").asIO)
      val history = mock[MailHistoryRepository]
      when(history.create(any)).thenAnswer { call: InvocationOnMock => call.getArgument[MailHistory](0).asDBIO }
      when(history.updateApiSuccess(any, any)).thenReturn(1.asDBIO)
      val echo = "https://epoca.example/invitations/accept#token=WIRING_SECRET_SENTINEL"
      when(backend.send[Either[String, String]](any)).thenReturn(cats.effect.IO.raiseError(new RuntimeException(echo)))
      val configured = new pl.iterators.epoca.main.ApplicationLoader(
        com.typesafe.config.ConfigFactory.load(),
        ec,
        ioRuntime,
        transactor,
        backend,
        mock[pl.iterators.epoca.utils.job.TriggeredJobScheduler],
        mock[cats.effect.std.Supervisor[cats.effect.IO]]
      ) {
        override protected lazy val sendgridTemplatesResolver = resolver
        override protected lazy val mailHistoryRepository     = history
      }
      val logger   = configured.logger.asInstanceOf[ch.qos.logback.classic.Logger]
      val appender = new ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]
      val oldLevel = logger.getLevel
      appender.start()
      logger.setLevel(ch.qos.logback.classic.Level.ALL)
      logger.addAppender(appender)
      try {
        val event = pl.iterators.epoca.mail.emails.UserOrganizationInvitation
          .Event(MailAddress("a@example.test"), "Org", "WIRING_SECRET_SENTINEL", java.time.Instant.EPOCH, "en")
        val result = configured.invitationMailSender.send(event).attempt.unsafeRunSync()
        import scala.jdk.CollectionConverters._
        val logs = appender.list.asScala.map(e => e.getFormattedMessage + Option(e.getThrowableProxy).map(_.getMessage).getOrElse("")).mkString("\n")
        verify(backend).send(any)
        logs must not(contain("WIRING_SECRET_SENTINEL"))
        result mustEqual (Right(pl.iterators.epoca.mail.domain.InvitationMailResult.Error): Either[
          Throwable,
          pl.iterators.epoca.mail.domain.InvitationMailResult
        ])
      } finally {
        logger.detachAppender(appender)
        logger.setLevel(oldLevel)
        appender.stop()
      }
    }
  }

  "send" should {
    "returns Skipped if email is blacklisted" in new TestCase {
      val toEmail: MailAddress          = MailAddress("name@domain.test")
      val mailType: MailType            = MailType.UserMagicLoginLink
      val replacements: JsonObject      = JsonObject.empty
      val attachments: List[Attachment] = Nil
      val fromOverride: Option[From]    = None

      when(config.blacklistedDomains).thenReturn(List("domain.test"))

      sendgridMailerService.send(toEmail, mailType, replacements, attachments, fromOverride).asserting(_ shouldEqual MailSendResult.Skipped)
    }
    "returns TemplateNotFound if there is not template for given mail type" in new TestCase {
      val toEmail: MailAddress          = MailAddress("name@domain.test")
      val mailType: MailType            = MailType.UserMagicLoginLink
      val replacements: JsonObject      = JsonObject.empty
      val attachments: List[Attachment] = Nil
      val fromOverride: Option[From]    = None

      when(config.blacklistedDomains).thenReturn(Nil)

      when(templatesResolver.getIdForTemplateKey(mailType)).thenReturn(None.asIO)

      sendgridMailerService.send(toEmail, mailType, replacements, attachments, fromOverride).asserting(_ shouldEqual MailSendResult.TemplateNotFound)

      verify(templatesResolver).getIdForTemplateKey(mailType)
    }
    "stores a pending mail history before sending and keeps it unsuccessful when sending fails" in new TestCase {
      val toEmail: MailAddress          = MailAddress("name@domain.test")
      val mailType: MailType            = MailType.UserMagicLoginLink
      val replacements: JsonObject      = JsonObject.empty
      val attachments: List[Attachment] = Nil
      val fromOverride: Option[From]    = None
      val template                      = "template"
      val interactions                  = ListBuffer.empty[String]
      var createdHistory                = Option.empty[MailHistory]
      var updatedMailUuid               = Option.empty[MailId]
      var updatedApiSuccess             = Option.empty[Option[java.time.Instant]]

      when(config.blacklistedDomains).thenReturn(Nil)
      when(config.sendUrl).thenReturn("http://test.test.test.test")

      when(templatesResolver.getIdForTemplateKey(mailType)).thenReturn(Some(template).asIO)

      when(httpClient.send[Either[String, String]](any)).thenAnswer { (_: InvocationOnMock) =>
        interactions += "send"
        Response(Left(""), StatusCode.InternalServerError, "ok").asIO
      }

      when(mailHistoryRepository.create(any)).thenAnswer { (invocation: InvocationOnMock) =>
        val history = invocation.getArguments()(0).asInstanceOf[MailHistory]
        createdHistory = Some(history)
        interactions += "create"
        history.asDBIO
      }
      when(mailHistoryRepository.updateApiSuccess(any, any)).thenAnswer { (invocation: InvocationOnMock) =>
        updatedMailUuid = Some(invocation.getArguments()(0).asInstanceOf[MailId])
        updatedApiSuccess = Some(invocation.getArguments()(1).asInstanceOf[Option[java.time.Instant]])
        interactions += "update"
        1.asDBIO
      }

      sendgridMailerService.send(toEmail, mailType, replacements, attachments, fromOverride).asserting(_ shouldEqual MailSendResult.Error)

      verify(templatesResolver).getIdForTemplateKey(mailType)

      verify(httpClient).send(any)

      verify(mailHistoryRepository).create(any)
      verify(mailHistoryRepository).updateApiSuccess(any, any)
      verify(mailHistoryRepository, times(0)).update(any)

      interactions.toList shouldEqual List("create", "send", "update")
      createdHistory.map(_.apiSuccess) shouldEqual Some(None)
      updatedMailUuid shouldEqual createdHistory.map(_.mailUuid)
      updatedApiSuccess shouldEqual Some(None)
    }
    "stores a pending mail history before sending and marks it successful after sending succeeds" in new TestCase {
      val toEmail: MailAddress          = MailAddress("name@domain.test")
      val mailType: MailType            = MailType.UserMagicLoginLink
      val replacements: JsonObject      = JsonObject.empty
      val attachments: List[Attachment] = Nil
      val fromOverride: Option[From]    = None
      val template                      = "template"
      val interactions                  = ListBuffer.empty[String]
      var createdHistory                = Option.empty[MailHistory]
      var updatedMailUuid               = Option.empty[MailId]
      var updatedApiSuccess             = Option.empty[Option[java.time.Instant]]

      when(config.blacklistedDomains).thenReturn(Nil)
      when(config.sendUrl).thenReturn("http://test.test.test.test")

      when(templatesResolver.getIdForTemplateKey(mailType)).thenReturn(Some(template).asIO)

      when(httpClient.send[Either[String, String]](any)).thenAnswer { (_: InvocationOnMock) =>
        interactions += "send"
        Response(Right(""), StatusCode.Ok, "ok").asIO
      }

      when(mailHistoryRepository.create(any)).thenAnswer { (invocation: InvocationOnMock) =>
        val history = invocation.getArguments()(0).asInstanceOf[MailHistory]
        createdHistory = Some(history)
        interactions += "create"
        history.asDBIO
      }
      when(mailHistoryRepository.updateApiSuccess(any, any)).thenAnswer { (invocation: InvocationOnMock) =>
        updatedMailUuid = Some(invocation.getArguments()(0).asInstanceOf[MailId])
        updatedApiSuccess = Some(invocation.getArguments()(1).asInstanceOf[Option[java.time.Instant]])
        interactions += "update"
        1.asDBIO
      }

      sendgridMailerService.send(toEmail, mailType, replacements, attachments, fromOverride).asserting(_ shouldEqual MailSendResult.Sent)

      verify(templatesResolver).getIdForTemplateKey(mailType)

      verify(httpClient).send(any)

      verify(mailHistoryRepository).create(any)
      verify(mailHistoryRepository).updateApiSuccess(any, any)
      verify(mailHistoryRepository, times(0)).update(any)

      interactions.toList shouldEqual List("create", "send", "update")
      createdHistory.map(_.apiSuccess) shouldEqual Some(None)
      updatedMailUuid shouldEqual createdHistory.map(_.mailUuid)
      updatedApiSuccess.flatten should beSome
    }
    "does not send when pending mail history cannot be stored" in new TestCase {
      val toEmail: MailAddress          = MailAddress("name@domain.test")
      val mailType: MailType            = MailType.UserMagicLoginLink
      val replacements: JsonObject      = JsonObject.empty
      val attachments: List[Attachment] = Nil
      val fromOverride: Option[From]    = None
      val template                      = "template"
      val storageError                  = new RuntimeException("history unavailable")

      when(config.blacklistedDomains).thenReturn(Nil)
      when(config.sendUrl).thenReturn("http://test.test.test.test")
      when(templatesResolver.getIdForTemplateKey(mailType)).thenReturn(Some(template).asIO)
      when(httpClient.send[Either[String, String]](any)).thenReturn(Response(Right(""), StatusCode.Ok, "ok").asIO)
      when(mailHistoryRepository.create(any)).thenReturn(slick.dbio.DBIO.failed(storageError))

      sendgridMailerService.send(toEmail, mailType, replacements, attachments, fromOverride).attempt.asserting {
        _ shouldEqual Left(storageError)
      }

      verify(templatesResolver).getIdForTemplateKey(mailType)
      verify(mailHistoryRepository).create(any)
      verify(httpClient, times(0)).send(any)
    }

  }

}
