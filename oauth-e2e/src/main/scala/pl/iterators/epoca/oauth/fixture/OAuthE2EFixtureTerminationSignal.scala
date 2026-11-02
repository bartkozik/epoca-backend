package pl.iterators.epoca.oauth.fixture

import cats.effect.IO
import com.sun.security.auth.module.UnixSystem

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.{CREATE_NEW, READ, WRITE}
import java.nio.file.attribute.{PosixFileAttributes, PosixFilePermissions, UserPrincipal}
import java.nio.file.{Files, Path}
import java.util.UUID
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.control.NonFatal

final case class OAuthE2EFixtureTerminationRequired(sessionMarker: Option[String])

object OAuthE2EFixtureTerminationRequired {
  def apply(sessionMarker: String): OAuthE2EFixtureTerminationRequired =
    OAuthE2EFixtureTerminationRequired(Some(sessionMarker))

  val withoutMarkedSession: OAuthE2EFixtureTerminationRequired = OAuthE2EFixtureTerminationRequired(None)

  private[fixture] def decode(bytes: Array[Byte]): Either[IllegalArgumentException, OAuthE2EFixtureTerminationRequired] = {
    val value = new String(bytes, UTF_8)
    if (value == AtomicFileOAuthE2EFixtureTerminationPublisher.EncodedSignalWithoutMarker) Right(withoutMarkedSession)
    else if (
      value.startsWith(AtomicFileOAuthE2EFixtureTerminationPublisher.EncodedSignalPrefix) &&
      value.endsWith(AtomicFileOAuthE2EFixtureTerminationPublisher.EncodedSignalSuffix)
    ) {
      val marker = value.substring(
        AtomicFileOAuthE2EFixtureTerminationPublisher.EncodedSignalPrefix.length,
        value.length - AtomicFileOAuthE2EFixtureTerminationPublisher.EncodedSignalSuffix.length
      )
      AtomicFileOAuthE2EFixtureTerminationPublisher
        .validatedMarker(marker)
        .map(value => OAuthE2EFixtureTerminationRequired(Some(value)))
        .toRight(new IllegalArgumentException("Fixture termination session marker is invalid"))
    } else Left(new IllegalArgumentException("Fixture termination signal is malformed"))
  }
}

trait OAuthE2EFixtureTerminationPublisher {

  /** The disposable process supervisor MUST arm an independent watchdog before the fatal control request begins. If an exact signal cannot be
    * confirmed within [[OAuthE2EFixtureTerminationPublisher.RequiredExternalWatchdogTimeout]], the supervisor stops only that exact disposable child
    * process, waits for its marked PostgreSQL session to disappear and roll back, and only then destroys the disposable volume. The backend never
    * exits its own process from a control endpoint.
    */
  def publish(signal: OAuthE2EFixtureTerminationRequired): IO[Unit]
}

object OAuthE2EFixtureTerminationPublisher {
  val RequiredExternalWatchdogTimeout: FiniteDuration = 2.seconds
}

object AtomicFileOAuthE2EFixtureTerminationPublisher {
  val ProductionPath: Path = Path.of("/run/oauth-e2e-fixture-supervisor/termination-required.v1")

  def production: OAuthE2EFixtureTerminationPublisher = testing(ProductionPath)

  private[fixture] def testing(path: Path): OAuthE2EFixtureTerminationPublisher = {
    val normalized      = path.toAbsolutePath.normalize()
    val parent          = Option(normalized.getParent).getOrElse(throw new IllegalArgumentException("Fixture termination signal parent is absent"))
    val trustedOwnerUid = currentProcessOwnerUid()
    validateTrustedDirectory(parent, trustedOwnerUid)
    new AtomicFileOAuthE2EFixtureTerminationPublisher(normalized)
  }

  private[fixture] def validateTrustedDirectory(parent: Path, trustedOwnerUid: Long): Unit = {
    val attributes = validatedDirectoryAttributes(parent)
    val actualUid =
      try Files.getAttribute(parent, "unix:uid", NOFOLLOW_LINKS).asInstanceOf[Number].longValue()
      catch {
        case NonFatal(error) => throw new IllegalArgumentException("Fixture termination signal parent owner is unavailable", error)
      }
    if (actualUid != trustedOwnerUid)
      throw new IllegalArgumentException("Fixture termination signal parent owner is not trusted")
    validateOwnerOnlyMode(attributes)
  }

  private def currentProcessOwnerUid(): Long =
    try {
      val procSelf = Path.of("/proc/self")
      if (Files.exists(procSelf)) Files.getAttribute(procSelf, "unix:uid").asInstanceOf[Number].longValue()
      else new UnixSystem().getUid
    } catch {
      case NonFatal(error) => throw new IllegalArgumentException("Fixture termination signal trusted owner is unavailable", error)
    }

  private[fixture] def validateTrustedDirectory(parent: Path, trustedOwner: UserPrincipal): Unit = {
    val attributes = validatedDirectoryAttributes(parent)
    if (attributes.owner() != trustedOwner)
      throw new IllegalArgumentException("Fixture termination signal parent owner is not trusted")
    validateOwnerOnlyMode(attributes)
  }

  private def validatedDirectoryAttributes(parent: Path): PosixFileAttributes = {
    if (!Files.isDirectory(parent, NOFOLLOW_LINKS) || Files.isSymbolicLink(parent) || parent.toRealPath(NOFOLLOW_LINKS) != parent)
      throw new IllegalArgumentException("Fixture termination signal parent is not a real directory")
    try Files.readAttributes(parent, classOf[PosixFileAttributes], NOFOLLOW_LINKS)
    catch {
      case NonFatal(error) => throw new IllegalArgumentException("Fixture termination signal parent attributes are unavailable", error)
    }
  }

  private def validateOwnerOnlyMode(attributes: PosixFileAttributes): Unit =
    if (attributes.permissions() != PosixFilePermissions.fromString("rwx------"))
      throw new IllegalArgumentException("Fixture termination signal parent mode is not owner-only")

  private val MarkerPattern = "oauth-e2e-fixture-command-([0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})".r
  private[fixture] val EncodedSignalPrefix =
    "{\"contract\":\"oauth-e2e-fixture-termination-required-v1\",\"sessionMarker\":\""
  private[fixture] val EncodedSignalSuffix = "\"}\n"
  private[fixture] val EncodedSignalWithoutMarker =
    "{\"contract\":\"oauth-e2e-fixture-termination-required-v1\",\"sessionMarker\":null}\n"

  private[fixture] def validatedMarker(value: String): Option[String] = value match {
    case MarkerPattern(uuid) if UUID.fromString(uuid).toString == uuid => Some(value)
    case _                                                             => None
  }

  private def encoded(signal: OAuthE2EFixtureTerminationRequired): Array[Byte] = {
    val markerJson = signal.sessionMarker match {
      case Some(value) if validatedMarker(value).nonEmpty => s"\"$value\""
      case Some(_)                                        => throw new IllegalArgumentException("Fixture termination session marker is invalid")
      case None                                           => "null"
    }
    s"{\"contract\":\"oauth-e2e-fixture-termination-required-v1\",\"sessionMarker\":$markerJson}\n".getBytes(UTF_8)
  }
}

private final class AtomicFileOAuthE2EFixtureTerminationPublisher(path: Path) extends OAuthE2EFixtureTerminationPublisher {
  import AtomicFileOAuthE2EFixtureTerminationPublisher._

  override def publish(signal: OAuthE2EFixtureTerminationRequired): IO[Unit] = IO.blocking {
    val expected = encoded(signal)
    if (Files.exists(path, NOFOLLOW_LINKS)) verifyExisting(expected)
    else publishNew(expected)
  }

  private def publishNew(expected: Array[Byte]): Unit = {
    val temporary = path.resolveSibling(s".${path.getFileName}.tmp-${UUID.randomUUID()}")
    var linked    = false
    try {
      val channel = FileChannel.open(temporary, CREATE_NEW, WRITE, NOFOLLOW_LINKS)
      try {
        val buffer = ByteBuffer.wrap(expected)
        while (buffer.hasRemaining) channel.write(buffer)
        channel.force(true)
      } finally channel.close()

      try {
        Files.createLink(path, temporary)
        linked = true
      } catch {
        case _: java.nio.file.FileAlreadyExistsException => verifyExisting(expected)
      }
      if (linked) verifyExisting(expected)
    } finally
      try { val _ = Files.deleteIfExists(temporary) }
      catch { case NonFatal(_) => () }
  }

  private def verifyExisting(expected: Array[Byte]): Unit = {
    if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || Files.isSymbolicLink(path))
      throw new IllegalStateException("Fixture termination signal is not a regular file")
    val channel = FileChannel.open(path, READ, NOFOLLOW_LINKS)
    val actual =
      try
        if (channel.size() != expected.length.toLong) Array.emptyByteArray
        else {
          val bytes  = Array.ofDim[Byte](expected.length)
          val buffer = ByteBuffer.wrap(bytes)
          while (buffer.hasRemaining && channel.read(buffer) >= 0) ()
          bytes
        }
      finally channel.close()
    if (!java.security.MessageDigest.isEqual(actual, expected))
      throw new IllegalStateException("Fixture termination signal conflicts with the terminal record")
  }
}
