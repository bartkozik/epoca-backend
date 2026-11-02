package pl.iterators.epoca.`export`.domain

import cats.effect.IO
import pl.iterators.epoca.export.domain.PivotExportProjection.Table
import pl.iterators.epoca.export.domain.SpreadsheetDomain.{SpreadsheetFormat, SpreadsheetName}
import pl.iterators.epoca.stats.domain.StatsDomain.WorkUnitPivotTableValue

import java.io.{IOException, OutputStream}
import java.nio.file.attribute.PosixFilePermission.{GROUP_WRITE, OTHERS_WRITE}
import java.nio.file.attribute.{PosixFileAttributeView, PosixFileAttributes, PosixFilePermissions}
import java.nio.file.{FileAlreadyExistsException, Files, LinkOption, Path, Paths, StandardOpenOption}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}
import java.util.concurrent.{ScheduledFuture, ScheduledThreadPoolExecutor, ThreadFactory, TimeUnit}
import scala.concurrent.duration.{DurationInt, FiniteDuration}

final class PivotExportArtifact private (
  val path: Path,
  val fileName: SpreadsheetName,
  val format: SpreadsheetFormat,
  val length: Long,
  private val lifecycle: PivotExportArtifact.Lifecycle) {

  require(length >= 0L, s"artifact length must be non-negative: $length")

  private[epoca] def claimForStreaming(): Boolean                          = lifecycle.claim()
  private[epoca] def streamIdleTimeout: FiniteDuration                     = lifecycle.streamIdleTimeout
  private[epoca] def discardIfUnclaimed: IO[Boolean]                       = IO.blocking(lifecycle.discardIfAvailable())
  private[epoca] def releaseAdmissionOnTerminal(release: () => Unit): Unit = lifecycle.onTerminal(release)
  def discard: IO[Boolean]                                                 = IO.blocking(lifecycle.discard())
}

object PivotExportArtifact {
  def apply(
    path: Path,
    fileName: SpreadsheetName,
    format: SpreadsheetFormat,
    length: Long
  ): PivotExportArtifact =
    leased(
      path,
      fileName,
      format,
      length,
      PivotExportArtifactStore.Config.DefaultUnmaterializedTimeout,
      PivotExportArtifactStore.Config.DefaultStreamIdleTimeout
    )

  private[epoca] def leased(
    path: Path,
    fileName: SpreadsheetName,
    format: SpreadsheetFormat,
    length: Long,
    unmaterializedTimeout: FiniteDuration,
    streamIdleTimeout: FiniteDuration
  ): PivotExportArtifact = {
    require(unmaterializedTimeout.length > 0L, "pivot export unmaterialized timeout must be positive")
    require(streamIdleTimeout.length > 0L, "pivot export stream idle timeout must be positive")
    new PivotExportArtifact(path, fileName, format, length, new Lifecycle(path, unmaterializedTimeout, streamIdleTimeout))
  }

  private final class Lifecycle(
    path: Path,
    unmaterializedTimeout: FiniteDuration,
    val streamIdleTimeout: FiniteDuration) {
    import Lifecycle._

    private val state                   = new AtomicInteger(Available)
    private val terminalCallback        = new AtomicReference[() => Unit](null)
    private val terminalCallbackInvoked = new AtomicBoolean(false)
    private val expiry: ScheduledFuture[_] = Scheduler.schedule(
      new Runnable {
        override def run(): Unit =
          if (state.compareAndSet(Available, Expired)) {
            try {
              val _ = Files.deleteIfExists(path)
            } catch {
              case _: Throwable => ()
            } finally
              notifyTerminal()
          }
      },
      unmaterializedTimeout.toNanos,
      TimeUnit.NANOSECONDS
    )

    def claim(): Boolean = {
      val claimed = state.compareAndSet(Available, Claimed)
      if (claimed) {
        val _ = expiry.cancel(false)
      }
      claimed
    }

    def onTerminal(callback: () => Unit): Unit = {
      require(callback != null, "pivot export terminal callback must not be null")
      require(terminalCallback.compareAndSet(null, callback), "pivot export terminal callback is already registered")
      notifyTerminal()
    }

    def discard(): Boolean = {
      val _ = state.getAndSet(Discarded)
      val _ = expiry.cancel(false)
      try Files.deleteIfExists(path)
      finally notifyTerminal()
    }

    def discardIfAvailable(): Boolean =
      if (state.compareAndSet(Available, Discarded)) {
        val _ = expiry.cancel(false)
        try Files.deleteIfExists(path)
        finally notifyTerminal()
      } else false

    private def notifyTerminal(): Unit = {
      val callback = terminalCallback.get()
      if (callback != null && isTerminal(state.get()) && terminalCallbackInvoked.compareAndSet(false, true)) callback()
    }
  }

  private object Lifecycle {
    private final val Available = 0
    private final val Claimed   = 1
    private final val Expired   = 2
    private final val Discarded = 3

    private def isTerminal(state: Int): Boolean = state == Expired || state == Discarded

    private val Scheduler = {
      val executor = new ScheduledThreadPoolExecutor(
        1,
        new ThreadFactory {
          override def newThread(runnable: Runnable): Thread = {
            val thread = new Thread(runnable, "pivot-export-artifact-expiry")
            thread.setDaemon(true)
            thread
          }
        }
      )
      executor.setRemoveOnCancelPolicy(true)
      executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
      executor
    }
  }
}

class PivotExportArtifactStore(config: PivotExportArtifactStore.Config) {
  import PivotExportArtifactStore._

  private val configuredDirectory = config.directory.toAbsolutePath.normalize()
  private val currentUid          = EffectiveUserId.current
  private val directory           = canonicalDirectory(configuredDirectory)
  prepareDirectory()

  def generate(fileName: SpreadsheetName, format: SpreadsheetFormat)(write: OutputStream => Unit): IO[PivotExportArtifact] =
    IO.blocking {
      validateFileName(fileName, format)
      val path = createRestrictedTempFile(format)
      try {
        val output = Files.newOutputStream(path, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
        try write(output)
        finally output.close()
        PivotExportArtifact.leased(path, fileName, format, Files.size(path), config.unmaterializedTimeout, config.streamIdleTimeout)
      } catch {
        case error: Throwable =>
          try {
            val _ = Files.deleteIfExists(path)
          } catch {
            case cleanupError: Throwable => error.addSuppressed(cleanupError)
          }
          throw error
      }
    }

  def generate(
    fileName: SpreadsheetName,
    format: SpreadsheetFormat,
    table: Table,
    measureLabels: Map[WorkUnitPivotTableValue, String]
  ): IO[PivotExportArtifact] =
    generate(fileName, format) { output =>
      format match {
        case SpreadsheetFormat.Csv  => PivotCsvEncoder.write(table, measureLabels, output)
        case SpreadsheetFormat.Xlsx => PivotXlsxEncoder.write(table, measureLabels, output)
      }
    }

  private def validateFileName(fileName: SpreadsheetName, format: SpreadsheetFormat): Unit = {
    val expectedSuffix = "." + SpreadsheetFormat.extension(format)
    require(SafeFileName.pattern.matcher(fileName).matches(), s"unsafe spreadsheet filename: $fileName")
    require(fileName.endsWith(expectedSuffix), s"spreadsheet filename must end with $expectedSuffix")
  }

  private def prepareDirectory(): Unit = {
    if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) {
      try {
        val _ = Files.createDirectory(directory, OwnerDirectoryPermissions)
      } catch {
        case _: FileAlreadyExistsException => ()
      }
    }
    validateExistingDirectory()
  }

  private def canonicalDirectory(configured: Path): Path = {
    val parent = Option(configured.getParent).getOrElse(throw new IllegalArgumentException(s"pivot export directory must have a parent: $configured"))
    val fileName = Option(configured.getFileName).getOrElse(
      throw new IllegalArgumentException(s"pivot export directory must have a final path component: $configured")
    )
    val realParent =
      try parent.toRealPath()
      catch {
        case error: java.io.IOException =>
          throw new IllegalArgumentException(s"pivot export parent is not an existing real directory: $parent", error)
      }
    validateAncestors(realParent)
    realParent.resolve(fileName).normalize()
  }

  private def validateAncestors(realParent: Path): Unit = {
    @scala.annotation.tailrec
    def loop(ancestor: Path): Unit = {
      require(!Files.isSymbolicLink(ancestor), s"pivot export ancestor must not be a symbolic link: $ancestor")
      require(Files.isDirectory(ancestor, LinkOption.NOFOLLOW_LINKS), s"pivot export ancestor is not a directory: $ancestor")
      require(
        Files.getFileStore(ancestor).supportsFileAttributeView(classOf[PosixFileAttributeView]),
        s"pivot export ancestor requires POSIX permissions: $ancestor"
      )
      val attributes = Files.readAttributes(ancestor, classOf[PosixFileAttributes], LinkOption.NOFOLLOW_LINKS)
      val ownerUid   = readOwnerUid(ancestor)
      require(
        NumericOwnershipPolicy.isTrustedOwner(currentUid, ownerUid),
        s"pivot export ancestor must be owned by the current process UID or numeric root: $ancestor"
      )
      val sharedWritable = attributes.permissions().contains(GROUP_WRITE) || attributes.permissions().contains(OTHERS_WRITE)
      val sticky         = sharedWritable && hasStickyBit(ancestor)
      require(
        NumericOwnershipPolicy.isSafeAncestor(currentUid, ownerUid, sharedWritable, sticky),
        s"pivot export ancestor must not be group/other writable unless it is root-owned and sticky: $ancestor"
      )

      Option(ancestor.getParent) match {
        case Some(next) => loop(next)
        case None       => ()
      }
    }

    loop(realParent)
  }

  private def readOwnerUid(path: Path): Long = UnixFileOwnerId.read(path)

  private def hasStickyBit(path: Path): Boolean = {
    val mode = Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS).asInstanceOf[Integer].intValue()
    (mode & StickyBitMask) != 0
  }

  private def validateExistingDirectory(): Unit = {
    require(!Files.isSymbolicLink(directory), s"pivot export directory must not be a symbolic link: $directory")
    require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS), s"pivot export path is not a directory: $directory")
    val attributes = Files.readAttributes(directory, classOf[PosixFileAttributes], LinkOption.NOFOLLOW_LINKS)
    require(readOwnerUid(directory) == currentUid, s"pivot export directory must be owned by the current process UID: $directory")
    require(attributes.permissions() == OwnerDirectoryPermissionSet, s"pivot export directory permissions must be rwx------: $directory")
  }

  private def createRestrictedTempFile(format: SpreadsheetFormat): Path = {
    val suffix = "." + SpreadsheetFormat.extension(format)
    val path   = Files.createTempFile(directory, "pivot-", suffix, OwnerFilePermissions).toAbsolutePath.normalize()
    require(
      Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS) == OwnerFilePermissionSet,
      s"pivot export artifact permissions must be rw-------: $path"
    )
    path
  }
}

private[domain] object EffectiveUserId {
  private val ProcessStatus = Paths.get("/proc/self/status")

  def current: Long = resolve(ProcessStatus, Paths.get(System.getProperty("java.io.tmpdir")))

  /** Linux reports the filesystem UID, which decides the owner of created files, in `/proc/self/status`. Systems without procfs, such as macOS for
    * local development, have no separate filesystem UID; there the owner the kernel assigns to a newly created probe file is the UID that will own
    * the pivot export artifacts.
    */
  private[domain] def resolve(processStatus: Path, probeDirectory: Path): Long =
    if (Files.exists(processStatus)) fromLinuxProcessStatus(processStatus)
    else NewFileOwnerId.read(probeDirectory)

  private def fromLinuxProcessStatus(processStatus: Path): Long =
    try LinuxProcessUserId.parse(Files.readString(processStatus))
    catch {
      case error: IllegalArgumentException =>
        throw new IllegalArgumentException(s"unable to determine pivot export filesystem UID from: $processStatus", error)
      case error: IOException =>
        throw new IllegalArgumentException(s"unable to read pivot export process status: $processStatus", error)
      case error: SecurityException =>
        throw new IllegalArgumentException(s"not permitted to read pivot export process status: $processStatus", error)
    }
}

private[domain] object NewFileOwnerId {
  def read(directory: Path): Long = {
    val probe =
      try Files.createTempFile(directory, "epoca-uid-", ".probe")
      catch {
        case error: IOException =>
          throw new IllegalArgumentException(s"unable to create a pivot export UID probe file in: $directory", error)
      }
    try UnixFileOwnerId.read(probe)
    finally {
      Files.deleteIfExists(probe)
      ()
    }
  }
}

private[domain] object LinuxProcessUserId {
  private val UidPrefix = "Uid:"

  def parse(status: String): Long = {
    require(status != null, "Linux process status must not be null")
    val uidLine = status.linesIterator
      .map(_.trim)
      .find(_.startsWith(UidPrefix))
      .getOrElse(throw new IllegalArgumentException("Linux process status does not contain Uid"))
    val rawUids = uidLine.substring(UidPrefix.length).trim.split("\\s+").toVector
    require(rawUids.size == 4, s"Linux process status must contain real, effective, saved, and filesystem UIDs: $uidLine")
    val uids = rawUids.map { rawUid =>
      try rawUid.toLong
      catch {
        case error: NumberFormatException =>
          throw new IllegalArgumentException(s"Linux process status contains a non-numeric UID: $uidLine", error)
      }
    }
    require(uids.forall(_ >= 0L), s"Linux process status contains a negative UID: $uidLine")
    uids.last
  }
}

private[domain] object UnixFileOwnerId {
  def read(path: Path): Long =
    try
      Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS) match {
        case number: Number => number.longValue()
        case value =>
          throw new IllegalArgumentException(
            s"pivot export path returned a non-numeric unix:uid attribute: $path (${Option(value).fold("null")(_.getClass.getName)})"
          )
      }
    catch {
      case error: IllegalArgumentException => throw error
      case error: UnsupportedOperationException =>
        throw new IllegalArgumentException(s"pivot export path requires the unix:uid file attribute: $path", error)
      case error: IOException =>
        throw new IllegalArgumentException(s"unable to read pivot export path owner UID: $path", error)
      case error: SecurityException =>
        throw new IllegalArgumentException(s"not permitted to read pivot export path owner UID: $path", error)
    }
}

private[domain] object NumericOwnershipPolicy {
  private final val RootUid = 0L

  def isTrustedOwner(currentUid: Long, ownerUid: Long): Boolean =
    ownerUid == currentUid || ownerUid == RootUid

  def isSafeAncestor(
    currentUid: Long,
    ownerUid: Long,
    sharedWritable: Boolean,
    sticky: Boolean
  ): Boolean =
    isTrustedOwner(currentUid, ownerUid) && (!sharedWritable || (ownerUid == RootUid && sticky))
}

object PivotExportArtifactStore {
  final case class Config(
    directory: Path,
    unmaterializedTimeout: FiniteDuration = Config.DefaultUnmaterializedTimeout,
    streamIdleTimeout: FiniteDuration = Config.DefaultStreamIdleTimeout) {
    require(unmaterializedTimeout.length > 0L, "export.pivot.unmaterializedArtifactTimeout must be positive")
    require(
      unmaterializedTimeout <= Config.MaximumUnmaterializedTimeout,
      s"export.pivot.unmaterializedArtifactTimeout must not exceed ${Config.MaximumUnmaterializedTimeout}"
    )
    require(streamIdleTimeout.length > 0L, "export.pivot.streamIdleTimeout must be positive")
    require(
      streamIdleTimeout <= Config.MaximumStreamIdleTimeout,
      s"export.pivot.streamIdleTimeout must not exceed ${Config.MaximumStreamIdleTimeout}"
    )
  }

  object Config {
    final val DefaultUnmaterializedTimeout: FiniteDuration = 30.seconds
    final val DefaultStreamIdleTimeout: FiniteDuration     = 2.minutes
    final val MaximumUnmaterializedTimeout: FiniteDuration = 5.minutes
    final val MaximumStreamIdleTimeout: FiniteDuration     = 30.minutes
  }

  private val SafeFileName                = "[A-Za-z0-9][A-Za-z0-9._-]{0,199}".r
  private val OwnerDirectoryPermissionSet = PosixFilePermissions.fromString("rwx------")
  private val OwnerFilePermissionSet      = PosixFilePermissions.fromString("rw-------")
  private val OwnerDirectoryPermissions   = PosixFilePermissions.asFileAttribute(OwnerDirectoryPermissionSet)
  private val OwnerFilePermissions        = PosixFilePermissions.asFileAttribute(OwnerFilePermissionSet)
  private final val StickyBitMask         = 512
}
