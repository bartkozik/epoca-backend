package pl.iterators.epoca.organization.gateways

import cats.effect.IO
import com.typesafe.config.{Config => TsConfig}
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId
import pl.iterators.epoca.organization.gateways.OrganizationNotifyCreationGateway.{
  OrganizationNotifyCreateRequest,
  OrganizationNotifyCreateResult,
  OrganizationNotifyCreateResultData
}
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.utils.json.JsonProtocol
import pl.iterators.epoca.utils.log.LoggingSupport
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4._
import sttp.client4.circe._

import java.time.Instant

trait OrganizationNotifyCreationGateway {
  def notify(request: OrganizationNotifyCreateRequest): IO[OrganizationNotifyCreateResult]
}
class HttpOrganizationNotifyCreationGateway(httpClient: WebSocketStreamBackend[IO, Fs2Streams[IO]], config: OrganizationNotifyCreationGateway.Config)
    extends OrganizationNotifyCreationGateway
    with LoggingSupport
    with JsonProtocol {
  override def notify(request: OrganizationNotifyCreateRequest): IO[OrganizationNotifyCreateResult] = {
    val uri = uri"${config.origin}/api/v1/organization"
    Logger[IO].debug(s"sending request: $request to uri: $uri") *>
      basicRequest
        .body(request)
        .post(uri)
        .response(asJson[OrganizationNotifyCreateResultData])
        .send(httpClient)
        .flatMap[OrganizationNotifyCreateResult] { response =>
          response.code match {
            case code if code.isSuccess =>
              response.body match {
                case Left(parseError) =>
                  Logger[IO].error(s"Unable to parse response body - probably we got invalid mappings -> $parseError") *>
                    IO.pure(OrganizationNotifyCreateResult.Error(s"Unable to parse response body - probably we got invalid mappings -> $parseError"))
                case Right(data) =>
                  Logger[IO].debug("Request finished successfully") *>
                    IO.pure(OrganizationNotifyCreateResult.Ok(data))
              }

            case noOkCode =>
              Logger[IO].error(s"Request finished with code $noOkCode") *>
                IO.pure(OrganizationNotifyCreateResult.Error(s"Request finished with code $noOkCode"))
          }
        }
        .recoverWith { case e =>
          Logger[IO].error(s"Request failed with exception ${e.getMessage}").as(OrganizationNotifyCreateResult.Error(e.getMessage))
        }
  }
}
object OrganizationNotifyCreationGateway {
  final case class Config(origin: String)
  object Config {
    def apply(config: TsConfig): Config = Config(config.getString("organization.external-origin"))
  }
  final case class OrganizationNotifyCreateRequest(createdBy: UserId, organizationId: OrganizationId)
  final case class OrganizationNotifyCreateResultData(instant: Instant, notifyId: String)
  sealed trait OrganizationNotifyCreateResult
  object OrganizationNotifyCreateResult {
    case class Ok(data: OrganizationNotifyCreateResultData) extends OrganizationNotifyCreateResult
    case class Error(message: String)                       extends OrganizationNotifyCreateResult
  }
}
