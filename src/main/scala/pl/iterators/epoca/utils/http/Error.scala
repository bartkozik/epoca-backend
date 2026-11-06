package pl.iterators.epoca.utils.http

import io.circe.{Encoder, Json}
import org.apache.pekko.http.scaladsl.model.StatusCode

import java.net.URI

case class Error[T](
  `type`: Option[URI] = Some(URI.create("about:blank")),
  status: Option[StatusCode] = None,
  title: Option[String] = None,
  detail: Option[String] = None,
  instance: Option[URI] = None,
  extension: T = ()
)(implicit val encoder: Encoder[T])

object Error {
  implicit def encoder[T]: Encoder[Error[T]] = (obj: Error[T]) =>
    Json
      .obj(
        "type"      -> obj.`type`.fold(Json.Null)(uri => Json.fromString(uri.toString)),
        "status"    -> obj.status.fold(Json.Null)(status => Json.fromInt(status.intValue())),
        "title"     -> obj.title.fold(Json.Null)(Json.fromString),
        "detail"    -> obj.detail.fold(Json.Null)(Json.fromString),
        "instance"  -> obj.instance.fold(Json.Null)(uri => Json.fromString(uri.toString)),
        "extension" -> obj.encoder(obj.extension)
      )
      .dropNullValues
      .dropEmptyValues
}
