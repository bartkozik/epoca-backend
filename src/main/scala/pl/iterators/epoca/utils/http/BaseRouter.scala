package pl.iterators.epoca.utils.http

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import fs2.Stream
import io.circe.Encoder
import org.apache.pekko.NotUsed
import org.apache.pekko.http.scaladsl.marshalling.ToResponseMarshallable
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.model.headers.{ContentDispositionTypes, `Content-Disposition`}
import org.apache.pekko.http.scaladsl.server.Directives
import org.apache.pekko.stream.scaladsl.{FileIO, Source}
import org.apache.pekko.util.ByteString
import pl.iterators.epoca.export.domain.PivotExportArtifact
import pl.iterators.epoca.export.domain.SpreadsheetDomain.{SpreadsheetFormat, SpreadsheetName}
import pl.iterators.epoca.utils.http.directives.{
  CompleteDirectives,
  ExtractDeviceIdDirective,
  ExtractHttpRequestContextDirective,
  PaginationDirective
}
import pl.iterators.epoca.utils.json.JsonProtocol
import pl.iterators.epoca.utils.log.TracingContext
import pl.iterators.epoca.utils.streams.Fs2PekkoStreamConverterDsl
import pl.iterators.kebs.pekkohttp.matchers.KebsPekkoHttpMatchers
import pl.iterators.kebs.pekkohttp.unmarshallers.{KebsPekkoHttpEnumUnmarshallers, KebsPekkoHttpUnmarshallers}

import java.net.URI
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt
import scala.reflect.ClassTag

trait BaseRouter
    extends JsonProtocol
    with Directives
    with CompleteDirectives
    with ExtractDeviceIdDirective
    with ExtractHttpRequestContextDirective
    with KebsPekkoHttpMatchers
    with KebsPekkoHttpUnmarshallers
    with KebsPekkoHttpEnumUnmarshallers
    with PaginationDirective
    with Fs2PekkoStreamConverterDsl {

  def error[E: Encoder](
    status: StatusCode,
    typeTag: String,
    title: String,
    detail: Option[String] = None,
    extension: E = ()
  )(implicit tracingContext: TracingContext
  ): ToResponseMarshallable = {
    val error =
      Error(Some(new URI(s"result:$typeTag")), Some(status), Some(title), detail, Some(new URI(s"cid:${tracingContext.correlationId}")), extension)
    status -> Error.encoder(error)
  }

  def typeFromResult[T](implicit cls: ClassTag[T]): String = {
    val leading =
      Option(cls.runtimeClass.getEnclosingClass)
        .map(_.getSimpleName.filterNot(_ == '$').replace("Result", "").replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase + ":")
    leading.getOrElse("") +
      cls.runtimeClass.getSimpleName.filterNot(_ == '$').replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase // kebabify
  }

  def toSpreadsheetResponse(
    fileName: SpreadsheetName,
    stream: Stream[IO, Byte],
    format: SpreadsheetFormat
  ): ToResponseMarshallable =
    format match {
      case SpreadsheetFormat.Csv  => toSpreadsheetResponseCSV(fileName, stream)
      case SpreadsheetFormat.Xlsx => toSpreadsheetResponseXLSX(fileName, stream)
    }

  def toPivotArtifactResponse(artifact: PivotExportArtifact)(implicit runtime: IORuntime): HttpResponse = {
    val contentType = artifact.format match {
      case SpreadsheetFormat.Csv  => ContentTypes.`text/csv(UTF-8)`
      case SpreadsheetFormat.Xlsx => ContentType(MediaTypes.`application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`)
    }
    val source = Source
      .lazySource { () =>
        if (artifact.claimForStreaming())
          FileIO.fromPath(artifact.path).idleTimeout(artifact.streamIdleTimeout).watchTermination() { case (_, termination) =>
            termination.onComplete(_ => artifact.discard.unsafeRunAndForget())(ExecutionContext.parasitic)
            NotUsed
          }
        else Source.failed(new IllegalStateException("pivot export artifact is no longer available"))
      }
      .mapMaterializedValue(_ => NotUsed)
      .watchTermination() { case (_, termination) =>
        termination.onComplete(_ => artifact.discardIfUnclaimed.unsafeRunAndForget())(ExecutionContext.parasitic)
        NotUsed
      }
    HttpResponse(
      headers = List(`Content-Disposition`(ContentDispositionTypes.attachment, Map("filename" -> artifact.fileName))),
      entity = HttpEntity(contentType, artifact.length, source)
    )
  }

  private def toSpreadsheetResponseCSV(fileName: SpreadsheetName, stream: Stream[IO, Byte]): ToResponseMarshallable =
    HttpResponse(
      headers = List(`Content-Disposition`(ContentDispositionTypes.attachment, Map("filename" -> fileName))),
      entity = HttpEntity.Chunked
        .fromData(ContentTypes.`text/csv(UTF-8)`, fs2ToAkkaStream(stream))
    )

  private def toSpreadsheetResponseXLSX(fileName: SpreadsheetName, stream: Stream[IO, Byte]): ToResponseMarshallable =
    HttpResponse(
      headers = List(`Content-Disposition`(ContentDispositionTypes.attachment, Map("filename" -> fileName))),
      entity = HttpEntity.Chunked
        .fromData(ContentType(MediaTypes.`application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`), fs2ToAkkaStream(stream))
    )

  private def fs2ToAkkaStream(fs2Stream: Stream[IO, Byte]): Source[ByteString, NotUsed] = {
    val fs2StreamWithEndSignal: Stream[IO, ByteString] = fs2Stream
      .groupWithin(1024, 100.millis)
      .map(bytesChunk => ByteString(bytesChunk.toArray[Byte])) ++
      Stream.emit(ByteString.empty) // Add empty ByteString to indicate end of stream explicitly. Fs2 does not signal akka stream about end of stream.

    Source
      .fromGraph(fs2StreamWithEndSignal.toSource)
      .takeWhile(_.nonEmpty, inclusive = false)
  }
}
