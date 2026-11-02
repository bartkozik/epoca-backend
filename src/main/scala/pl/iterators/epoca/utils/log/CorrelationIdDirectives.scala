package pl.iterators.epoca.utils.log

import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.server.Directive1
import org.apache.pekko.http.scaladsl.server.Directives._

trait CorrelationIdDirectives {
  def withCorrelationId(headerName: String, correlationIdGenerator: => String): Directive1[String] =
    optionalHeaderValueByName(headerName).flatMap { correlationIdOpt =>
      val correlationId = correlationIdOpt.getOrElse(correlationIdGenerator)
      respondWithHeader(RawHeader(headerName, correlationId)).tflatMap { _ =>
        provide(correlationId)
      }
    }
}

object CorrelationIdDirectives extends CorrelationIdDirectives
