package pl.iterators.epoca.mail.services

import cats.effect.{IO, Sync}
import pl.iterators.epoca.mail.domain.MailDomain.MailType
import pl.iterators.epoca.utils.json.JsonProtocol
import pl.iterators.epoca.utils.log.LoggingSupport
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.circe.asJson
import sttp.client4.{WebSocketStreamBackend, basicRequest, _}
import sttp.model._

class SendgridTemplatesResolver(httpClient: WebSocketStreamBackend[IO, Fs2Streams[IO]], config: SendgridMailer.Config)
    extends LoggingSupport
    with JsonProtocol {

  import SendgridTemplatesResolver._

  def getIdForTemplateKey(key: MailType): IO[Option[String]] =
    templatesFuture.map { templates =>
      key match {
        case MailType.UserOrganizationInvitationPl => templates.get(config.invitationTemplatePl).orElse(templates.get(config.invitationTemplateEn))
        case MailType.UserOrganizationInvitation   => templates.get(config.invitationTemplateEn)
        case _                                     => templates.get(key.toString)
      }
    }

  private def getTemplates: IO[Map[String, String]] =
    basicRequest
      .get(uri"${config.templatesUrl}")
      .response(asJson[ApiResult])
      .headers(Header("Authorization", s"Bearer ${config.apiKey}"))
      .send(httpClient)
      .flatMap[Map[String, String]] { response =>
        (response.body, response.code.isSuccess) match {
          case (Right(apiResult), true) =>
            Logger[IO].info(s"Loaded SendGrid templates succesfully") *> Sync[IO].pure(apiResult.templates.map(t => t.name -> t.id).toMap)
          case (Right(apiResult), false) =>
            Logger[IO]
              .warning(s"Fetching SendGrid templates finished with error code ${response.code} but with proper response") *> IO.pure(
              apiResult.templates.map(t => t.name -> t.id).toMap
            )
          case (Left(error), _) =>
            Logger[IO]
              .error(s"Unable to load SendGrid templates from ${config.templatesUrl}. Message: $error") *> IO.pure(Map.empty[String, String])
        }
      }
      .recoverWith { case e =>
        Logger[IO].error(s"Fetching templates failed with exception ${e.getMessage}").as(Map.empty[String, String])
      }

  private lazy val templatesFuture: IO[Map[String, String]] = getTemplates
}

object SendgridTemplatesResolver {
  case class Template(id: String, name: String)
  case class ApiResult(templates: List[Template])
}
