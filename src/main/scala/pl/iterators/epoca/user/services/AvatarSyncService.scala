package pl.iterators.epoca.user.services

import cats.effect.{Clock, IO}
import com.typesafe.config.{Config => TsConfig}
import pl.iterators.epoca.storage.FileStorage
import pl.iterators.epoca.user.domain.UserAvatarSource.Source
import pl.iterators.epoca.user.domain.UserDomain.{UserAvatar, UserModifiedAt}
import pl.iterators.epoca.user.domain.{User, UserAvatarSource}
import pl.iterators.epoca.user.repositories.UserRepository.UserFilters
import pl.iterators.epoca.user.repositories.{UserAvatarSourceRepository, UserRepository}
import pl.iterators.epoca.user.services.AvatarSyncService._
import pl.iterators.epoca.utils.clock.ClockExtension
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.log.LoggingSupport
import slick.dbio.DBIO
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4._
import sttp.model.{StatusCode, Uri}

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import javax.imageio.stream.ImageInputStream
import javax.imageio.{IIOImage, ImageIO, ImageWriteParam}
import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.Try
import scala.util.control.NonFatal

class AvatarSyncService(
  config: AvatarSyncService.Config,
  transactor: PostgresIOTransactor,
  userRepository: UserRepository,
  userAvatarSourceRepository: UserAvatarSourceRepository,
  fileStorage: FileStorage,
  httpClient: WebSocketStreamBackend[IO, Fs2Streams[IO]]
)(implicit ec: ExecutionContext)
    extends LoggingSupport
    with ClockExtension {

  private[epoca] val admission = new AvatarSyncAdmission(4, 4096, 5.minutes)

  def schedule(user: User, pictureUrl: String): IO[Unit] = IO
    .defer {
      if (!config.enabled) IO.unit
      else
        IO.uncancelable { _ =>
          IO(admission.acquire(user.id, System.nanoTime())).flatMap {
            case false => IO.unit
            case true =>
              IO.defer(sync(user, pictureUrl))
                .void
                .timeoutTo(30.seconds, Logger[IO].warning("Google photo job deadline exceeded"))
                .handleErrorWith(_ => Logger[IO].warning("Google photo job failed"))
                .guarantee(IO(admission.release()))
                .start
                .void
                .handleErrorWith(_ => IO(admission.release()) >> Logger[IO].warning("Google photo dispatch failed"))
          }
        }
    }
    .handleErrorWith(_ => Logger[IO].warning("Google photo dispatch failed"))

  def sync(user: User, pictureUrl: String): IO[AvatarSyncResult] = IO
    .defer {
      val deadlineNanos = System.nanoTime() + 30.seconds.toNanos
      if (!config.enabled) IO.pure(AvatarSyncResult.Disabled)
      else
        validateUrl(pictureUrl) match {
          case Left(reason) => IO.pure(AvatarSyncResult.Rejected(reason))
          case Right(uri) =>
            transactor.executeBoundedTransactionally(deadlineNanos)(userAvatarSourceRepository.find(user.id)).flatMap { stored =>
              decide(user, stored, pictureUrl, skipUnchanged = false) match {
                case Decision.Skip(result) => IO.pure(result)
                case Decision.Download =>
                  download(uri).flatMap(
                    _.fold(
                      r => IO.pure(r),
                      image => {
                        val revision =
                          pictureUrl + "#sha256=" + MessageDigest.getInstance("SHA-256").digest(image.bytes).map("%02x".format(_)).mkString
                        decide(user, stored, revision) match {
                          case Decision.Skip(result) => IO.pure(result)
                          case Decision.Download     => store(user, revision, image, stored, DBIO.successful(true), deadlineNanos)
                        }
                      }
                    )
                  )
              }
            }
        }
    }
    .handleErrorWith(t =>
      Logger[IO]
        .warning(s"Avatar sync failed for user ${user.id}: ${t.getClass.getSimpleName}")
        .as(AvatarSyncResult.Failed("image synchronization failed"))
    )

  def syncImage(
    user: User,
    sourceRevision: String,
    bytes: Array[Byte],
    contentType: String,
    stillAuthorized: DBIO[Boolean],
    expectedSource: Option[UserAvatarSource],
    deadlineNanos: Option[Long] = None
  ): IO[AvatarSyncResult] = IO
    .defer {
      val deadline = deadlineNanos.getOrElse(System.nanoTime() + 30.seconds.toNanos)
      if (!config.enabled) IO.pure(AvatarSyncResult.Disabled)
      else
        decide(user, expectedSource, sourceRevision) match {
          case Decision.Skip(result) => IO.pure(result)
          case Decision.Download =>
            validateImage(bytes, contentType).fold(
              result => IO.pure(result),
              image => store(user, sourceRevision, image, expectedSource, stillAuthorized, deadline)
            )
        }
    }
    .timeout(30.seconds)
    .handleErrorWith(t =>
      Logger[IO]
        .warning(s"Avatar sync failed for user ${user.id}: ${t.getClass.getSimpleName}")
        .as(AvatarSyncResult.Failed("image synchronization failed"))
    )

  private def validateImage(bytes: Array[Byte], contentType: String): Either[AvatarSyncResult, DownloadedImage] =
    if (bytes.length > config.maxBytes) Left(AvatarSyncResult.Rejected("image too large"))
    else if (bytes.isEmpty) Left(AvatarSyncResult.Rejected("empty image"))
    else
      for {
        _        <- extensions.get(contentType).toRight(AvatarSyncResult.Rejected(s"unsupported content type '$contentType'"))
        detected <- sniff(bytes).toRight(AvatarSyncResult.Rejected("content is not a supported image"))
        _        <- Either.cond(detected._1 == contentType, (), AvatarSyncResult.Rejected(s"content does not match content type '$contentType'"))
      } yield DownloadedImage(bytes, detected._1, detected._2)

  private sealed trait Decision
  private object Decision {
    case class Skip(result: AvatarSyncResult) extends Decision
    case object Download                      extends Decision
  }

  private def decide(
    user: User,
    stored: Option[UserAvatarSource],
    pictureUrl: String,
    skipUnchanged: Boolean = true
  ): Decision =
    stored match {
      case None if user.avatar.isDefined => Decision.Skip(AvatarSyncResult.ManualAvatar)
      case None                          => Decision.Download
      case Some(s) if s.source == Source.Manual || !user.avatar.contains(UserAvatar(s.avatarUrl)) =>
        Decision.Skip(AvatarSyncResult.ManualAvatar)
      case Some(s) if skipUnchanged && s.sourceUrl.contains(pictureUrl) => Decision.Skip(AvatarSyncResult.Unchanged)
      case Some(_)                                                      => Decision.Download
    }

  private def validateUrl(url: String): Either[String, Uri] =
    Uri.parse(url).left.map(_ => "invalid URL").flatMap { uri =>
      val host = uri.host.map(_.toLowerCase).getOrElse("")
      if (!uri.scheme.contains("https")) Left("only https is allowed")
      else if (uri.userInfo.isDefined || uri.port.exists(_ != 443)) Left("userinfo and custom ports are not allowed")
      else if (!config.allowedHosts.exists(a => host == a || host.endsWith("." + a))) Left(s"host '$host' is not allowed")
      else Right(uri)
    }

  private def download(uri: Uri): IO[Either[AvatarSyncResult, DownloadedImage]] =
    basicRequest
      .get(uri)
      .followRedirects(false) // a redirect could point outside the allowlist
      .readTimeout(config.timeout)
      .response(
        asStreamAlways(Fs2Streams[IO])(_.take(config.maxBytes.toLong + 1).compile.to(Array)).map(bytes => Right(bytes): Either[String, Array[Byte]])
      )
      .send(httpClient)
      .timeout(config.timeout)
      .map { response =>
        val contentType = response.contentType.map(_.split(';').head.trim.toLowerCase).getOrElse("")
        if (response.code != StatusCode.Ok) Left(AvatarSyncResult.Failed(s"download returned HTTP ${response.code.code}"))
        else if (response.contentLength.exists(_ > config.maxBytes)) Left(AvatarSyncResult.Rejected("image too large"))
        else
          response.body match {
            case Left(_)                                        => Left(AvatarSyncResult.Failed("download failed"))
            case Right(bytes) if bytes.length > config.maxBytes => Left(AvatarSyncResult.Rejected("image too large"))
            case Right(bytes) if bytes.isEmpty                  => Left(AvatarSyncResult.Rejected("empty image"))
            case Right(bytes)                                   => validateImage(bytes, contentType)
          }
      }

  private def store(
    user: User,
    pictureUrl: String,
    image: DownloadedImage,
    expectedSource: Option[UserAvatarSource],
    stillAuthorized: DBIO[Boolean],
    deadlineNanos: Long
  ): IO[AvatarSyncResult] =
    IO.blocking(createWebVersion(image.bytes)).flatMap {
      case Left(reason) => IO.pure(AvatarSyncResult.Rejected(reason))
      case Right(web) =>
        val hash        = MessageDigest.getInstance("SHA-256").digest(image.bytes).map("%02x".format(_)).mkString
        val originalKey = s"avatars/${user.id}/$hash.${image.extension}"
        val webKey      = s"avatars/${user.id}/$hash-$WebSize.png"
        val url         = fileStorage.publicUrl(webKey)
        for {
          _   <- fileStorage.put(originalKey, image.bytes, image.contentType)
          _   <- fileStorage.put(webKey, web, "image/png")
          now <- Clock[IO].instantNow
          updated <- transactor.executeBoundedTransactionally(deadlineNanos)(
                       updateUser(user, pictureUrl, url, now, expectedSource, stillAuthorized, deadlineNanos)
                     )
        } yield updated
    }

  private def updateUser(
    user: User,
    pictureUrl: String,
    avatarUrl: String,
    now: java.time.Instant,
    expectedSource: Option[UserAvatarSource],
    stillAuthorized: DBIO[Boolean],
    deadlineNanos: Long
  ): DBIO[AvatarSyncResult] =
    for {
      _          <- PostgresIOTransactor.requireBefore(deadlineNanos)
      current    <- userRepository.find(UserFilters(id = Some(user.id), forUpdate = true))
      _          <- PostgresIOTransactor.requireBefore(deadlineNanos)
      stored     <- userAvatarSourceRepository.find(user.id)
      _          <- PostgresIOTransactor.requireBefore(deadlineNanos)
      authorized <- stillAuthorized
      _          <- PostgresIOTransactor.requireBefore(deadlineNanos)
      result <- current match {
                  case Some(_) if !authorized => DBIO.successful(AvatarSyncResult.Rejected("identity is no longer authorized"))
                  case Some(u) =>
                    decide(u, stored, pictureUrl) match {
                      case Decision.Skip(skipped) => DBIO.successful(skipped)
                      case Decision.Download if stored != expectedSource || u.avatar != user.avatar =>
                        DBIO.successful(AvatarSyncResult.Rejected("avatar changed during synchronization"))
                      case Decision.Download =>
                        for {
                          _ <- PostgresIOTransactor.requireBefore(deadlineNanos)
                          _ <- userRepository.update(u.copy(avatar = Some(UserAvatar(avatarUrl)), modifiedAt = UserModifiedAt(now)))
                          _ <- PostgresIOTransactor.requireBefore(deadlineNanos)
                          _ <- userAvatarSourceRepository.upsert(UserAvatarSource(user.id, Source.Google, Some(pictureUrl), avatarUrl, now))
                        } yield AvatarSyncResult.Synced(avatarUrl)
                    }
                  case None => DBIO.successful(AvatarSyncResult.Failed("user no longer exists"))
                }
    } yield result
}

object AvatarSyncService {

  sealed trait AvatarSyncResult
  object AvatarSyncResult {
    final case class Synced(avatarUrl: String) extends AvatarSyncResult
    case object Unchanged                      extends AvatarSyncResult
    case object ManualAvatar                   extends AvatarSyncResult
    case object Disabled                       extends AvatarSyncResult
    final case class Rejected(reason: String)  extends AvatarSyncResult
    final case class Failed(reason: String)    extends AvatarSyncResult
  }

  final case class Config(
    enabled: Boolean,
    maxBytes: Int,
    timeout: FiniteDuration,
    allowedHosts: List[String])

  object Config {
    def apply(config: TsConfig): Config = {
      val c = config.getConfig("user.avatarSync")
      Config(
        enabled = c.getBoolean("enabled"),
        maxBytes = c.getInt("maxBytes"),
        timeout = c.getDuration("timeout").toMillis.millis,
        allowedHosts = c.getStringList("allowedHosts").asScala.toList.map(_.toLowerCase)
      )
    }
  }

  private final case class DownloadedImage(
    bytes: Array[Byte],
    contentType: String,
    extension: String)

  private val WebSize      = 512
  private val MaxDimension = 8000
  private val MaxPixels    = 25000000L

  private def createWebVersion(bytes: Array[Byte]): Either[String, Array[Byte]] =
    decode(bytes).map { source =>
      val side    = math.min(source.getWidth, source.getHeight)
      val cropped = source.getSubimage((source.getWidth - side) / 2, (source.getHeight - side) / 2, side, side)
      val argb    = source.getColorModel.hasAlpha
      val scaled  = scaleDown(cropped, math.min(side, WebSize), argb)
      encodePng(scaled)
    }

  private def decode(bytes: Array[Byte]): Either[String, BufferedImage] = {
    val cannotDecode             = "cannot decode image"
    val stream: ImageInputStream = ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(bytes))
    try {
      val readers = ImageIO.getImageReaders(stream)
      if (!readers.hasNext) Left(cannotDecode)
      else {
        val reader = readers.next()
        try {
          reader.setInput(stream, true, true)
          // dimensions come from the header, so a decompression bomb is rejected before any pixel is decoded
          val (width, height) = (reader.getWidth(0), reader.getHeight(0))
          if (width > MaxDimension || height > MaxDimension || width.toLong * height > MaxPixels) Left("image dimensions too large")
          else Try(reader.read(0)).toEither.left.map(_ => cannotDecode)
        } catch { case NonFatal(_) => Left(cannotDecode) }
        finally reader.dispose()
      }
    } finally stream.close()
  }

  @tailrec
  private def scaleDown(
    image: BufferedImage,
    target: Int,
    argb: Boolean
  ): BufferedImage =
    if (image.getWidth > target * 2) scaleDown(draw(image, image.getWidth / 2, argb), target, argb)
    else draw(image, target, argb)

  private def draw(
    image: BufferedImage,
    size: Int,
    argb: Boolean
  ): BufferedImage = {
    val out = new BufferedImage(size, size, if (argb) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
    val g   = out.createGraphics()
    try {
      g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
      g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      val _ = g.drawImage(image, 0, 0, size, size, null)
    } finally g.dispose()
    out
  }

  private def encodePng(image: BufferedImage): Array[Byte] = {
    val writer = ImageIO.getImageWritersByFormatName("png").next()
    val param  = writer.getDefaultWriteParam
    if (param.canWriteCompressed) {
      param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT)
      param.setCompressionQuality(0f)
    }
    val out = new ByteArrayOutputStream()
    val ios = ImageIO.createImageOutputStream(out)
    try {
      writer.setOutput(ios)
      writer.write(null, new IIOImage(image, null, null), param)
    } finally {
      ios.close()
      writer.dispose()
    }
    out.toByteArray
  }

  private def sniff(bytes: Array[Byte]): Option[(String, String)] = {
    def startsWith(prefix: Int*): Boolean = bytes.length >= prefix.length && prefix.zipWithIndex.forall { case (b, i) => (bytes(i) & 0xff) == b }
    def ascii(offset: Int, text: String): Boolean =
      bytes.length >= offset + text.length && text.zipWithIndex.forall { case (c, i) => bytes(offset + i) == c.toByte }
    if (startsWith(0xff, 0xd8, 0xff)) Some("image/jpeg" -> "jpg")
    else if (startsWith(0x89, 0x50, 0x4e, 0x47)) Some("image/png" -> "png")
    else if (ascii(0, "GIF87a") || ascii(0, "GIF89a")) Some("image/gif" -> "gif")
    else None
  }

  // SVG is deliberately absent: it can carry scripts. WebP is absent too: the JDK cannot decode it, so no web version could be made.
  private val extensions: Map[String, String] =
    Map("image/jpeg" -> "jpg", "image/png" -> "png", "image/gif" -> "gif")
}
