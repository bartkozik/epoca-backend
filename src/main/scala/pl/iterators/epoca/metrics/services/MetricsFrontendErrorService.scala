package pl.iterators.epoca.metrics.services

import cats.effect.IO
import com.typesafe.config.Config
import io.circe.Json
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.metrics.services.MetricsFrontendErrorService.{
  MetricsFrontendErrorConfig,
  MetricsFrontendErrorRequest,
  MetricsFrontendErrorResponse
}
import pl.iterators.epoca.utils.log.LoggingSupport

class MetricsFrontendErrorService(config: MetricsFrontendErrorConfig) extends LoggingSupport {
  def passToLogger(authContext: AuthContext, request: MetricsFrontendErrorRequest): IO[MetricsFrontendErrorResponse] = IO {
    val truncatedPayload = request.payload.toString().take(config.truncateLength)
    if (config.truncateLength < request.payload.toString().length) {
      logger.warn("Logged error is too large - truncated")
    }
    logger.error(s"AuthId: ${authContext.id}")
    splitStringIntoChunks(truncatedPayload, config.maxLogBytes).foreach(logger.error)
    MetricsFrontendErrorResponse.Ok
  }

  private def splitStringIntoChunks(input: String, chunkSize: Int): Iterator[String] =
    input.grouped(chunkSize)
}

object MetricsFrontendErrorService {
  case class MetricsFrontendErrorConfig(truncateLength: Int, maxLogBytes: Int)
  object MetricsFrontendErrorConfig {
    def apply(config: Config): MetricsFrontendErrorConfig =
      MetricsFrontendErrorConfig(
        maxLogBytes = config.getInt("pekko.http.max-log-bytes"),
        truncateLength = config.getInt("metrics.frontEndErrors.truncateLength")
      )
  }
  case class MetricsFrontendErrorRequest(payload: Json)

  sealed trait MetricsFrontendErrorResponse
  object MetricsFrontendErrorResponse {
    case object Ok extends MetricsFrontendErrorResponse
  }
}
