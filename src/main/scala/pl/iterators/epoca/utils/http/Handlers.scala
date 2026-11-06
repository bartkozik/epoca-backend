package pl.iterators.epoca.utils.http

import com.github.pjfanning.pekkohttpcirce.FailFastCirceSupport._
import io.circe.{DecodingFailure, Encoder}
import org.apache.pekko.http.scaladsl.model.StatusCodes._
import org.apache.pekko.http.scaladsl.model.headers._
import org.apache.pekko.http.scaladsl.model.{ContentRange, EntityStreamSizeException, HttpHeader, StatusCode}
import org.apache.pekko.http.scaladsl.server.AuthenticationFailedRejection.{CredentialsMissing, CredentialsRejected}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server._
import org.slf4j.MDC
import pl.iterators.epoca.utils.log.{LoggingSupport, TracingContext}

import java.net.URI
import java.sql.SQLException
import scala.reflect.ClassTag

trait Handlers extends LoggingSupport {
  private def handlerComplete[E: Encoder](
    status: StatusCode,
    typeTag: String,
    title: String,
    detail: Option[String],
    tracingContext: Option[TracingContext],
    additionalHeaders: Seq[HttpHeader],
    extension: E,
    loggingDirective: Directive0
  ) = {
    val error =
      Error(
        Some(new URI(typeTag)),
        Some(status),
        Some(title),
        detail,
        tracingContext.map(_.correlationId).map(cid => new URI(s"cid:$cid")),
        extension
      )
    extractRequest { request =>
      if (InvitationSensitiveRequest(request)) {
        val safe =
          Error(Some(new URI(typeTag)), Some(status), Some(title), None, tracingContext.map(_.correlationId).map(cid => new URI(s"cid:$cid")), ())
        respondWithHeaders(additionalHeaders :+ RawHeader("Cache-Control", "no-store")) & loggingDirective & complete(status -> safe)
      } else respondWithHeaders(additionalHeaders) & loggingDirective & complete(status -> error)
    }
  }

  private def typeFromRejection[T <: Rejection](implicit cls: ClassTag[T]): String =
    cls.runtimeClass.getSimpleName.replace("Rejection", "").filterNot(_ == '$').replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase

  def exceptionHandler(tracingContext: Option[TracingContext], loggingDirective: Directive0): ExceptionHandler = ExceptionHandler {
    case e: IllegalArgumentException =>
      handlerComplete(
        BadRequest,
        "rejection:static-validation-failed",
        "Validation failed",
        Some(e.getMessage),
        tracingContext,
        Seq.empty,
        (),
        loggingDirective
      )
    case e: SQLException if e.getSQLState == "55P03" =>
      handlerComplete(
        ServiceUnavailable,
        "error:service-unavailable",
        "Service unavailable",
        None,
        tracingContext,
        Seq(`Retry-After`(1)),
        (),
        loggingDirective
      )
    case e: Exception =>
      extractRequest { request =>
        tracingContext.foreach(ctx => MDC.put(ctx.contextName, ctx.context))
        if (InvitationSensitiveRequest(request)) loggingAdapter.error("Internal invitation error (details redacted)")
        else loggingAdapter.error("Internal error:\n{}\n{}", e.getMessage, e.getStackTrace.mkString("\n"))
        tracingContext.foreach(ctx => MDC.remove(ctx.contextName))
        handlerComplete(InternalServerError, "error:internal-error", "Internal error", None, tracingContext, Seq.empty, (), loggingDirective)
      }
  }

  def rejectionHandler(tracingContext: Option[TracingContext], loggingDirective: Directive0): RejectionHandler = new RejectionHandler {
    override def apply(rejections: Seq[Rejection]): Option[Route] = {
      RejectionHandler
        .newBuilder()
        .handleAll[SchemeRejection] { rejections =>
          val schemes = rejections.map(_.supported)
          handlerComplete(
            BadRequest,
            typeFromRejection[SchemeRejection],
            "Uri scheme not allowed",
            Some(s"Supported schemes: ${schemes.mkString(", ")}"),
            tracingContext,
            Seq.empty,
            Map("supportedSchemes" -> schemes),
            loggingDirective
          )
        }
        .handleAll[UnacceptedResponseContentTypeRejection] { rejections =>
          val supported = rejections.flatMap(_.supported)
          val msg       = supported.map(_.format).mkString("Resource representation is only available with these types:\n", "\n", "")
          handlerComplete(
            NotAcceptable,
            typeFromRejection[UnacceptedResponseContentTypeRejection],
            "Unaccepted response Content-Type",
            Some(msg),
            tracingContext,
            Seq.empty,
            Map("supportedTypes" -> supported.map(_.format)),
            loggingDirective
          )
        }
        .handle { case ValidationRejection(msg, _) =>
          handlerComplete(
            BadRequest,
            "rejection:static-validation-failed",
            "Validation failed",
            Some(msg),
            tracingContext,
            Seq.empty,
            (),
            loggingDirective
          )
        }
        .handle { case MalformedRequestContentRejection(msg, throwable) =>
          throwable match {
            case _: EntityStreamSizeException =>
              handlerComplete(
                ContentTooLarge,
                typeFromRejection[MalformedRequestContentRejection],
                "The request content was malformed",
                Some(msg),
                tracingContext,
                Seq.empty,
                (),
                loggingDirective
              )
            case df: DecodingFailure =>
              handlerComplete(
                BadRequest,
                "rejection:static-validation-failed",
                "Validation failed",
                df.pathToRootString.fold(Option(df.message))((p: String) => Some(s"$p: ${df.message}")),
                tracingContext,
                Seq.empty,
                df.pathToRootString.fold(Map("message" -> df.message))(p => Map("path" -> p, "message" -> df.message)),
                loggingDirective
              )
            case _ =>
              handlerComplete(
                BadRequest,
                typeFromRejection[MalformedRequestContentRejection],
                "The request content was malformed",
                Some(msg),
                tracingContext,
                Seq.empty,
                (),
                loggingDirective
              )
          }
        }
        .handle { case MalformedFormFieldRejection(name, msg, _) =>
          handlerComplete(
            BadRequest,
            typeFromRejection[MalformedFormFieldRejection],
            "The form field was malformed",
            Some(s"$name: $msg"),
            tracingContext,
            Seq.empty,
            Map("fieldName" -> name, "message" -> msg),
            loggingDirective
          )
        }
        .handle { case MalformedHeaderRejection(headerName, msg, _) =>
          handlerComplete(
            BadRequest,
            typeFromRejection[MalformedHeaderRejection],
            "The value of HTTP header was malformed",
            Some(s"$headerName: $msg"),
            tracingContext,
            Seq.empty,
            Map("headerName" -> headerName, "message" -> msg),
            loggingDirective
          )
        }
        .handle { case MalformedQueryParamRejection(name, msg, _) =>
          handlerComplete(
            BadRequest,
            typeFromRejection[MalformedQueryParamRejection],
            "The query parameter was malformed",
            Some(s"$name: $msg"),
            tracingContext,
            Seq.empty,
            Map("queryParamName" -> name, "message" -> msg),
            loggingDirective
          )
        }
        .handle { case MissingCookieRejection(cookieName) =>
          handlerComplete(
            BadRequest,
            typeFromRejection[MissingCookieRejection],
            "The request is missing a required cookie",
            Some(cookieName),
            tracingContext,
            Seq.empty,
            Map("cookieName" -> cookieName),
            loggingDirective
          )
        }
        .handle { case MissingFormFieldRejection(fieldName) =>
          handlerComplete(
            BadRequest,
            typeFromRejection[MissingFormFieldRejection],
            "The request is missing a required form field",
            Some(fieldName),
            tracingContext,
            Seq.empty,
            Map("fieldName" -> fieldName),
            loggingDirective
          )
        }
        .handle { case MissingHeaderRejection(headerName) =>
          handlerComplete(
            BadRequest,
            typeFromRejection[MissingHeaderRejection],
            "The request is missing a required HTTP header",
            Some(headerName),
            tracingContext,
            Seq.empty,
            Map("headerName" -> headerName),
            loggingDirective
          )
        }
        .handle { case MissingAttributeRejection(_) =>
          handlerComplete(InternalServerError, "error:internal-error", "Internal error", None, tracingContext, Seq.empty, (), loggingDirective)
        }
        .handle { case InvalidOriginRejection(allowedOrigins) =>
          handlerComplete(
            Forbidden,
            typeFromRejection[InvalidOriginRejection],
            "The HTTP origin header was not allowed",
            Some(s"Allowed `Origin` header values: ${allowedOrigins.mkString(", ")}"),
            tracingContext,
            Seq.empty,
            Map("allowedOrigins" -> allowedOrigins.map(_.toString())),
            loggingDirective
          )
        }
        .handle { case MissingQueryParamRejection(paramName) =>
          handlerComplete(
            BadRequest,
            typeFromRejection[MissingQueryParamRejection],
            "The request is missing a required query parameter",
            Some(paramName),
            tracingContext,
            Seq.empty,
            Map("queryParamName" -> paramName),
            loggingDirective
          )
        }
        .handle { case InvalidRequiredValueForQueryParamRejection(paramName, requiredValue, _) =>
          handlerComplete(
            BadRequest,
            typeFromRejection[InvalidRequiredValueForQueryParamRejection],
            "The request is missing a required query parameter",
            Some(s"$paramName: $requiredValue"),
            tracingContext,
            Seq.empty,
            Map("queryParamName" -> paramName, "requiredValue" -> requiredValue),
            loggingDirective
          )
        }
        .handle { case RequestEntityExpectedRejection =>
          handlerComplete(
            BadRequest,
            typeFromRejection[RequestEntityExpectedRejection.type],
            "Request entity expected but not supplied",
            None,
            tracingContext,
            Seq.empty,
            (),
            loggingDirective
          )
        }
        .handle { case TooManyRangesRejection(_) =>
          handlerComplete(
            RangeNotSatisfiable,
            typeFromRejection[TooManyRangesRejection],
            "The request contains too many ranges",
            None,
            tracingContext,
            Seq.empty,
            (),
            loggingDirective
          )
        }
        .handle { case CircuitBreakerOpenRejection(_) =>
          handlerComplete(
            ServiceUnavailable,
            "error:service-unavailable",
            "Service unavailable",
            None,
            tracingContext,
            Seq.empty,
            (),
            loggingDirective
          )
        }
        .handle { case UnsatisfiableRangeRejection(unsatisfiableRanges, actualEntityLength) =>
          handlerComplete(
            RangeNotSatisfiable,
            typeFromRejection[UnsatisfiableRangeRejection],
            "The request contains unsatisfiable ranges",
            Some(unsatisfiableRanges.mkString("None of the following requested Ranges were satisfiable:\n", "\n", "")),
            tracingContext,
            List(`Content-Range`(ContentRange.Unsatisfiable(actualEntityLength))),
            Map("unsatisfiableRanges" -> unsatisfiableRanges.mkString(", ")),
            loggingDirective
          )
        }
        .handleAll[UnacceptedResponseEncodingRejection] { rejections =>
          val supported = rejections.flatMap(_.supported)
          handlerComplete(
            NotAcceptable,
            typeFromRejection[UnacceptedResponseEncodingRejection],
            "Unaccepted response Content-Encoding",
            Some(
              "Resource representation is only available with these Content-Encodings:\n" +
                supported.map(_.value).mkString("\n")
            ),
            tracingContext,
            Seq.empty,
            Map("supportedEncodings" -> supported.map(_.value)),
            loggingDirective
          )
        }
        .handleAll[UnsupportedRequestContentTypeRejection] { rejections =>
          val unsupported = rejections.find(_.contentType.isDefined).flatMap(_.contentType).fold("")(" [" + _ + "]")
          val supported   = rejections.flatMap(_.supported)
          val expected =
            if (supported.mkString(" or ").isEmpty) ""
            else " Expected:\n" + supported.mkString(" or ")

          handlerComplete(
            UnsupportedMediaType,
            typeFromRejection[UnsupportedRequestContentTypeRejection],
            "The request's Content-Type is not supported",
            Some(s"The request's Content-Type$unsupported is not supported.$expected"),
            tracingContext,
            Seq.empty,
            Map("supportedTypes" -> supported.map(_.value)),
            loggingDirective
          )
        }
        .handleAll[UnsupportedRequestEncodingRejection] { rejections =>
          val supported = rejections.map(_.supported.value)
          handlerComplete(
            BadRequest,
            typeFromRejection[UnsupportedRequestEncodingRejection],
            "The request's Content-Encoding is not supported",
            Some(s"The request's Content-Encoding is not supported. Expected:\n" + supported.mkString(" or ")),
            tracingContext,
            Seq.empty,
            Map("supportedEncodings" -> supported),
            loggingDirective
          )
        }
        .handle { case ExpectedWebSocketRequestRejection =>
          handlerComplete(
            BadRequest,
            typeFromRejection[ExpectedWebSocketRequestRejection.type],
            "Expected WebSocket Upgrade request",
            None,
            tracingContext,
            Seq.empty,
            (),
            loggingDirective
          )
        }
        .handle { case AuthorizationFailedRejection =>
          handlerComplete(
            Unauthorized,
            typeFromRejection[AuthorizationFailedRejection.type],
            "The supplied authentication is not authorized to access this resource",
            None,
            tracingContext,
            Seq.empty,
            (),
            loggingDirective
          )
        }
        .handleAll[AuthenticationFailedRejection] { rejections =>
          val rejectionMessage = rejections.head.cause match {
            case CredentialsMissing  => "The resource requires authentication, which was not supplied with the request"
            case CredentialsRejected => "The supplied authentication is invalid"
          }
          val authenticateHeaders = rejections.map(r => `WWW-Authenticate`(r.challenge))
          handlerComplete(
            Unauthorized,
            typeFromRejection[AuthenticationFailedRejection],
            "Could not authorize",
            Some(rejectionMessage),
            tracingContext,
            authenticateHeaders,
            (),
            loggingDirective
          )
        }
        .handleAll[MethodRejection] { rejections =>
          val (methods, names) = rejections.map(r => r.supported -> r.supported.name).unzip
          handlerComplete(
            MethodNotAllowed,
            typeFromRejection[MethodRejection],
            "HTTP method not allowed",
            Some("Supported methods: " + names.mkString(", ")),
            tracingContext,
            List(Allow(methods)),
            Map("supportedMethods" -> names),
            loggingDirective
          )
        }
        .handleNotFound {
          handlerComplete(NotFound, "rejection:route-not-found", "Route not found", None, tracingContext, Seq.empty, (), loggingDirective)
        }
        .result()
        .seal(rejections)
    }
  }

}
