package pl.iterators.epoca.oauth.services

import com.fasterxml.jackson.core.{JsonFactory, JsonFactoryBuilder, StreamReadFeature}
import io.circe.{Json, JsonObject}
import pl.iterators.epoca.oauth.services.OAuthConfig.OAuthConfigError

import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import java.util.Base64
import scala.util.Try

private[services] object OAuthSigningKeyRingSecret {
  final case class RawKey(
    kid: String,
    material: String,
    retired: Boolean) {
    override def toString: String = s"RawKey(kid=$kid,material=<redacted>,retired=$retired)"
  }
  final case class Document(
    version: Int,
    activeKid: String,
    keys: Vector[RawKey]) {
    override def toString: String = s"Document(version=$version,activeKid=$activeKid,keys=<redacted>)"
  }

  private val MaxDocumentBytes = 64 * 1024
  private val MaxKeyCount      = 32
  private val TopLevelFields   = Set("version", "activeKid", "keys")
  private val KeyFields        = Set("kid", "material", "retired")
  private val JsonFactoryWithDuplicateDetection: JsonFactory = {
    val builder = new JsonFactoryBuilder()
    builder.configure(StreamReadFeature.STRICT_DUPLICATE_DETECTION, true)
    builder.build()
  }

  def decode(value: String): Either[OAuthConfigError, Document] =
    for {
      bytes    <- decodeBase64Url(value)
      _        <- Either.cond(bytes.length <= MaxDocumentBytes, (), OAuthConfigError.InvalidConfiguration)
      text     <- decodeUtf8(bytes)
      _        <- validateJsonTokenStream(text)
      json     <- io.circe.parser.parse(text).left.map(_ => OAuthConfigError.InvalidConfiguration)
      document <- decodeDocument(json)
    } yield document

  private def decodeBase64Url(value: String): Either[OAuthConfigError, Array[Byte]] =
    if (value.isEmpty || value.exists(character => !isBase64UrlCharacter(character)))
      Left(OAuthConfigError.InvalidConfiguration)
    else
      Base64Decode(value).flatMap { bytes =>
        val canonical = Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)
        Either.cond(canonical == value, bytes, OAuthConfigError.InvalidConfiguration)
      }

  private def isBase64UrlCharacter(character: Char): Boolean =
    (character >= 'A' && character <= 'Z') ||
      (character >= 'a' && character <= 'z') ||
      (character >= '0' && character <= '9') ||
      character == '-' ||
      character == '_'

  private def decodeUtf8(bytes: Array[Byte]): Either[OAuthConfigError, String] =
    Try {
      StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString
    }.toEither.left.map(_ => OAuthConfigError.InvalidConfiguration)

  private def validateJsonTokenStream(value: String): Either[OAuthConfigError, Unit] =
    Try {
      val parser = JsonFactoryWithDuplicateDetection.createParser(value)
      try
        while (parser.nextToken() != null) {}
      finally
        parser.close()
    }.toEither.left.map(_ => OAuthConfigError.InvalidConfiguration)

  private def decodeDocument(json: Json): Either[OAuthConfigError, Document] =
    for {
      obj       <- exactObject(json, TopLevelFields)
      version   <- intField(obj, "version")
      _         <- Either.cond(version == 1, (), OAuthConfigError.InvalidConfiguration)
      activeKid <- stringField(obj, "activeKid")
      keys      <- keysField(obj, "keys")
    } yield Document(version, activeKid, keys)

  private def keysField(obj: JsonObject, name: String): Either[OAuthConfigError, Vector[RawKey]] =
    for {
      values <- obj(name).flatMap(_.asArray).toRight(OAuthConfigError.InvalidConfiguration)
      _      <- Either.cond(values.nonEmpty && values.size <= MaxKeyCount, (), OAuthConfigError.InvalidConfiguration)
      keys   <- sequence(values.map(decodeRawKey))
    } yield keys

  private def decodeRawKey(json: Json): Either[OAuthConfigError, RawKey] =
    for {
      obj      <- exactObject(json, KeyFields)
      kid      <- stringField(obj, "kid")
      material <- stringField(obj, "material")
      retired  <- booleanField(obj, "retired")
    } yield RawKey(kid, material, retired)

  private def exactObject(json: Json, fields: Set[String]): Either[OAuthConfigError, JsonObject] =
    json.asObject.filter(obj => obj.keys.toSet == fields).toRight(OAuthConfigError.InvalidConfiguration)

  private def intField(obj: JsonObject, name: String): Either[OAuthConfigError, Int] =
    obj(name)
      .flatMap(_.asNumber)
      .flatMap(_.toBigInt)
      .filter(_.isValidInt)
      .map(_.toInt)
      .toRight(OAuthConfigError.InvalidConfiguration)

  private def stringField(obj: JsonObject, name: String): Either[OAuthConfigError, String] =
    obj(name).flatMap(_.asString).toRight(OAuthConfigError.InvalidConfiguration)

  private def booleanField(obj: JsonObject, name: String): Either[OAuthConfigError, Boolean] =
    obj(name).flatMap(_.asBoolean).toRight(OAuthConfigError.InvalidConfiguration)

  private def sequence[A](values: Vector[Either[OAuthConfigError, A]]): Either[OAuthConfigError, Vector[A]] =
    values.foldLeft[Either[OAuthConfigError, Vector[A]]](Right(Vector.empty)) { (result, value) =>
      for {
        accumulated <- result
        current     <- value
      } yield accumulated :+ current
    }

  private object Base64Decode {
    def apply(value: String): Either[OAuthConfigError, Array[Byte]] =
      Try(Base64.getUrlDecoder.decode(value)).toEither.left.map(_ => OAuthConfigError.InvalidConfiguration)
  }
}
