package pl.iterators.epoca.utils.log

import com.typesafe.config.ConfigFactory
import org.apache.pekko.http.javadsl.model.ContentType.Binary
import org.apache.pekko.http.javadsl.server.RouteResult
import org.apache.pekko.http.scaladsl.model.HttpHeader.ParsingResult
import org.apache.pekko.http.scaladsl.model.Uri.Path
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.model.headers.ModeledCompanion
import org.apache.pekko.http.scaladsl.server.Directive0
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.RouteResult.{Complete, Rejected}
import org.apache.pekko.stream.Materializer

import scala.concurrent.ExecutionContext
import scala.util.Try
import scala.util.control.NonFatal

trait CustomLoggingDirectives {
  import CustomLoggingDirectives._

  private def headersToString(headers: Seq[HttpHeader]): String =
    headers
      .map { h =>
        // Browser credentials accompany ordinary API calls too, including streamed responses.
        val value = if (h.is("cookie") || h.is("set-cookie")) "***" else h.value
        s"${h.name()}: $value"
      }
      .mkString(", ")

  private val maxBytes =
    Try(ConfigFactory.load().getInt("pekko.http.max-log-bytes")).getOrElse(4096)

  private def paramsToString(uri: Uri): String =
    uri.query().map { case key -> value => s"$key: $value" }.mkString(", ")

  private def entityToString(entity: HttpEntity): String =
    entity match {
      case strict @ HttpEntity.Strict(contentType, data) =>
        strict.contentType match {
          case _: Binary => s"${strict.productPrefix} ${contentType.toString()} ${data.toString()}"
          case _: ContentType.WithMissingCharset =>
            s"${strict.productPrefix} ${contentType.toString()} ${data.toString()}"
          case nb: ContentType.NonBinary =>
            try
              if (data.length > maxBytes) {
                val truncatedString = data.take(maxBytes).decodeString(nb.charset.value).dropRight(1)
                s"${strict.productPrefix} ${contentType.toString()} $truncatedString ... (${data.length} bytes total)"
              } else
                s"${strict.productPrefix} ${contentType.toString()} ${data.decodeString(nb.charset.value)}"
            catch {
              case NonFatal(_) =>
                s"${strict.productPrefix} ${contentType.toString()} ${data.toString()}"
            }
        }
      case chunked @ HttpEntity.Chunked(contentType, _) => s"${chunked.productPrefix} ${contentType.toString()}"
      case closeDelimeted @ HttpEntity.CloseDelimited(contentType, _) =>
        s"${closeDelimeted.productPrefix} ${contentType.toString()}"
      case default @ HttpEntity.Default(contentType, contentLength, _) =>
        s"${default.productPrefix} ${contentType.toString()} ($contentLength bytes total)"
      case indefiniteLength @ HttpEntity.IndefiniteLength(contentType, _) =>
        s"${indefiniteLength.productPrefix} ${contentType.toString()}"
    }

  def defaultRequestPrinter(request: HttpRequest): String =
    if (pl.iterators.epoca.utils.http.InvitationSensitiveRequest(request))
      s"Request: method=[${request.method.value}] path=[<invitation>] headers=[***] params=[***] entity=[***]"
    else
      s"Request: " +
        s"protocol=[${request.protocol.value}] " +
        s"method=[${request.method.value}] " +
        s"path=[${request.uri.path}] " +
        s"headers=[${headersToString(request.headers)}] " +
        s"params=[${paramsToString(request.uri)}] " +
        s"entity=[${entityToString(request.entity)}]"

  def defaultResponsePrinter(
    request: HttpRequest,
    response: RouteResult,
    duration: Long
  ): String =
    if (pl.iterators.epoca.utils.http.InvitationSensitiveRequest(request)) response match {
      case Complete(value) =>
        s"Response: method=[${request.method.value}] path=[<invitation>] duration=[$duration] status=[${value.status.intValue}] headers=[***] entity=[***]"
      case Rejected(_) => s"Rejections: method=[${request.method.value}] path=[<invitation>] duration=[$duration] rejections=[***]"
    }
    else
      response match {
        case Complete(response) =>
          s"Response: " +
            s"protocol=[${response.protocol.value}] " +
            s"method=[${request.method.value}] " +
            s"path=[${request.uri.path}] " +
            s"duration=[$duration] -> " +
            s"status=[${response.status.value}] " +
            s"headers=[${headersToString(response.headers)}] " +
            s"entity=[${entityToString(response.entity)}]"
        case Rejected(rejections) =>
          s"Rejections: " +
            s"protocol=[${request.protocol.value}] " +
            s"method=[${request.method.value}] " +
            s"path=[${request.uri.path}] " +
            s"duration=[$duration] -> " +
            s"rejections=[${rejections.mkString(", ")}]"
      }

  def withRequestLogged(
    logAction: String => Unit,
    loggingRules: Seq[CustomLogRule] = Seq.empty[CustomLogRule],
    requestPrinter: HttpRequest => String = defaultRequestPrinter
  ): Directive0 =
    extractRequestContext.flatMap { ctx =>
      import ctx.{executionContext, materializer}
      val requestChanged = loggingRules.foldLeft(ctx.request) { (request, rule) =>
        if (rule._1(request)) {
          rule._2(request) match {
            case hr: HttpRequest => hr
            case _               => request
          }
        } else {
          request
        }
      }
      logAction(requestPrinter(requestChanged))
      pass
    }

  def withResultLogged(
    logAction: String => Unit,
    loggingRules: Seq[CustomLogRule] = Seq.empty[CustomLogRule],
    responsePrinter: (HttpRequest, RouteResult, Long) => String = defaultResponsePrinter
  ): Directive0 =
    extractRequestContext.flatMap { ctx =>
      import ctx.{executionContext, materializer}
      val start = System.currentTimeMillis()
      mapRouteResult { result =>
        val resultChanged = result match {
          case Complete(response) if !response.entity.isChunked() =>
            Complete(loggingRules.foldLeft(response) { (res, rule) =>
              if (rule._1(res, ctx.request)) {
                rule._2(res) match {
                  case hr: HttpResponse => hr
                  case _                => res
                }
              } else {
                res
              }
            })
          case res => res
        }
        val finish = System.currentTimeMillis()
        logAction(responsePrinter(ctx.request, resultChanged, finish - start))
        result
      }
    }

  def withRequestResultLogging(
    logAction: String => Unit,
    loggingRules: Seq[CustomLogRule] = Seq.empty[CustomLogRule],
    requestPrinter: HttpRequest => String = defaultRequestPrinter,
    responsePrinter: (HttpRequest, RouteResult, Long) => String = defaultResponsePrinter
  ): Directive0 =
    withRequestLogged(logAction, loggingRules, requestPrinter) & withResultLogged(logAction, loggingRules, responsePrinter)
}

object CustomLoggingDirectives extends CustomLoggingDirectives {
  trait CustomLogPredicate {
    def apply(httpRequest: HttpRequest)(implicit ec: ExecutionContext, mat: Materializer): Boolean
    def apply(httpResponse: HttpResponse, httpRequest: HttpRequest)(implicit ec: ExecutionContext, mat: Materializer): Boolean
  }
  trait CustomLogTransformation {
    def apply(httpMessage: HttpMessage)(implicit ec: ExecutionContext, mat: Materializer): HttpMessage
  }

  type CustomLogRule = (CustomLogPredicate, CustomLogTransformation)

  trait SensitiveDataPlaceHolder {
    val value: String
  }

  implicit val DefaultSensitiveDataPlaceHolder: SensitiveDataPlaceHolder = new SensitiveDataPlaceHolder {
    override val value: String = "***"
  }

  case class HideHeaders(headers: ModeledCompanion[_]*)(implicit ph: SensitiveDataPlaceHolder) extends CustomLogTransformation {
    override def apply(httpMessage: HttpMessage)(implicit ec: ExecutionContext, mat: Materializer): HttpMessage = {
      def shouldBeOmitted(header: HttpHeader): Boolean = headers.exists(h => header.is(h.lowercaseName))

      httpMessage.withHeaders(headers =
        httpMessage.headers.flatMap(h =>
          if (shouldBeOmitted(h)) {
            HttpHeader.parse(h.name, ph.value) match {
              case ParsingResult.Ok(p, e) if e.isEmpty => Some(p)
              // parsing's gone wrong, omit whole header instead of hiding it's content
              case _ =>
                None
            }
          } else {
            Some(h)
          }
        )
      )
    }
  }

  case class HideEntity(inRequest: Boolean, inResponse: Boolean)(implicit ph: SensitiveDataPlaceHolder) extends CustomLogTransformation {
    override def apply(httpMessage: HttpMessage)(implicit ec: ExecutionContext, mat: Materializer): HttpMessage =
      httpMessage match {
        case HttpRequest(_, _, _, _, _) if inRequest                              => httpMessage.withEntity(ph.value)
        case response: HttpResponse if inResponse && response.entity.isKnownEmpty => response
        case HttpResponse(_, _, _, _) if inResponse                               => httpMessage.withEntity(ph.value)
        case _                                                                    => httpMessage
      }
  }

  object AnyMessage extends CustomLogPredicate {
    override def apply(httpRequest: HttpRequest)(implicit ec: ExecutionContext, mat: Materializer): Boolean = true

    override def apply(httpResponse: HttpResponse, httpRequest: HttpRequest)(implicit ec: ExecutionContext, mat: Materializer): Boolean = true
  }

  object AnyRequest extends CustomLogPredicate {
    override def apply(httpRequest: HttpRequest)(implicit ec: ExecutionContext, mat: Materializer): Boolean = true

    override def apply(httpResponse: HttpResponse, httpRequest: HttpRequest)(implicit ec: ExecutionContext, mat: Materializer): Boolean = false
  }

  object AnyResponse extends CustomLogPredicate {
    override def apply(httpRequest: HttpRequest)(implicit ec: ExecutionContext, mat: Materializer): Boolean = false

    override def apply(httpResponse: HttpResponse, httpRequest: HttpRequest)(implicit ec: ExecutionContext, mat: Materializer): Boolean = true
  }

  case class WhenPath(path: Path) extends CustomLogPredicate {
    override def apply(httpRequest: HttpRequest)(implicit ec: ExecutionContext, mat: Materializer): Boolean =
      httpRequest.uri.path == path

    override def apply(httpResponse: HttpResponse, httpRequest: HttpRequest)(implicit ec: ExecutionContext, mat: Materializer): Boolean =
      httpRequest.uri.path == path
  }

  case class WhenPathStartsWith(path: Path) extends CustomLogPredicate {
    override def apply(httpRequest: HttpRequest)(implicit ec: ExecutionContext, mat: Materializer): Boolean =
      httpRequest.uri.path.startsWith(path)

    override def apply(httpResponse: HttpResponse, httpRequest: HttpRequest)(implicit ec: ExecutionContext, mat: Materializer): Boolean =
      httpRequest.uri.path.startsWith(path)
  }

}
