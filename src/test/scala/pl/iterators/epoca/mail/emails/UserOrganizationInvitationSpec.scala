package pl.iterators.epoca.mail.emails

import cats.effect.IO
import io.circe.JsonObject
import pl.iterators.epoca.mail.domain.InvitationMailResult
import pl.iterators.epoca.mail.domain.MailDomain._
import pl.iterators.epoca.mail.services.MailerService._
import pl.iterators.epoca.mail.services.{MailerService, SendgridMailer}
import pl.iterators.epoca.utils.ServiceSpec

import java.time.Instant

class UserOrganizationInvitationSpec extends ServiceSpec {
  Seq(
    (List("pl-template" -> "pl-id", "en-template" -> "en-id"), MailType.UserOrganizationInvitationPl, Some("pl-id")),
    (List("en-template" -> "en-id"), MailType.UserOrganizationInvitationPl, Some("en-id")),
    (List("en-template" -> "en-id"), MailType.UserOrganizationInvitation, Some("en-id")),
    (List("unrelated" -> "id"), MailType.UserOrganizationInvitation, None)
  ).foreach { case (templates, kind, expected) =>
    s"resolve bounded $kind mapping to $expected from $templates" in {
      import pl.iterators.epoca.mail.services.SendgridTemplatesResolver
      import SendgridTemplatesResolver.{ApiResult, Template}
      import sttp.client4.{Response, ResponseException, WebSocketStreamBackend}
      import sttp.capabilities.fs2.Fs2Streams
      val backend = mock[WebSocketStreamBackend[IO, Fs2Streams[IO]]]
      val body: Either[ResponseException[String, io.circe.Error], ApiResult] = Right(ApiResult(templates.map { case (name, id) =>
        Template(id, name)
      }))
      when(backend.send[Either[ResponseException[String, io.circe.Error], ApiResult]](any))
        .thenReturn(IO.pure(Response(body, sttp.model.StatusCode.Ok)))
      val config = SendgridMailer.Config(
        "",
        "https://epoca.example",
        Nil,
        "",
        "",
        "",
        "",
        "https://sendgrid.example/templates",
        "test",
        "pl-template",
        "en-template"
      )
      new SendgridTemplatesResolver(backend, config).getIdForTemplateKey(kind).map(_ mustEqual expected)
    }
  }

  "invitation mail" should {
    Seq(
      MailSendResult.Sent             -> InvitationMailResult.Sent,
      MailSendResult.Skipped          -> InvitationMailResult.Skipped,
      MailSendResult.TemplateNotFound -> InvitationMailResult.TemplateNotFound,
      MailSendResult.Error            -> InvitationMailResult.Error
    ).foreach { case (providerResult, expected) =>
      s"preserve $providerResult and only send public invitation data" in {
        var captured = Option.empty[(MailAddress, MailType, JsonObject)]
        val mailer = new MailerService {
          def send(
            to: MailAddress,
            kind: MailType,
            data: JsonObject,
            attachments: List[Attachment],
            from: Option[From]
          ): IO[MailSendResult] = IO {
            captured = Some((to, kind, data)); providerResult
          }
        }
        val config = SendgridMailer.Config("", "https://epoca.example/", Nil, "", "", "", "", "", "test")
        val sender = new UserOrganizationInvitation(mailer, config)
        val event = UserOrganizationInvitation.Event(
          MailAddress("anna@example.test"),
          "Correct organization",
          "a" * 43,
          Instant.parse("2026-10-09T10:00:00Z"),
          "pl"
        )
        sender.send(event).map { result =>
          result mustEqual expected
          val (to, kind, data) = captured.get
          to mustEqual event.email
          kind mustEqual MailType.UserOrganizationInvitationPl
          data.keys.toSet mustEqual Set("organizationName", "invitationLink", "expiresAt", "locale")
          data("invitationLink").flatMap(_.asString) must beSome("https://epoca.example/invitations/accept#token=" + "a" * 43)
          data("organizationName").flatMap(_.asString) must beSome("Correct organization")
          event.toString must not(contain("a" * 43))
        }
      }
    }
    "use English for unsupported locales" in {
      var captured = Option.empty[(MailType, JsonObject)]
      val mailer = new MailerService {
        def send(
          to: MailAddress,
          kind: MailType,
          data: JsonObject,
          attachments: List[Attachment],
          from: Option[From]
        ): IO[MailSendResult] = IO {
          captured = Some(kind -> data); MailSendResult.Sent
        }
      }
      val config = SendgridMailer.Config("", "https://epoca.example", Nil, "", "", "", "", "", "test")
      new UserOrganizationInvitation(mailer, config)
        .send(UserOrganizationInvitation.Event(MailAddress("a@b.test"), "Org", "b" * 43, Instant.EPOCH, "de"))
        .map { _ =>
          captured.get._1 mustEqual MailType.UserOrganizationInvitation
          captured.get._2("locale").flatMap(_.asString) must beSome("en")
        }
    }
  }
}
