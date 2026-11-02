package pl.iterators.epoca.utils.http

import cats.effect.unsafe.IORuntime
import org.apache.pekko.http.scaladsl.model.headers.`Content-Disposition`
import org.apache.pekko.http.scaladsl.model.{ContentTypes, MediaTypes}
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.util.ByteString
import org.specs2.execute.Result
import org.specs2.mutable.SpecificationLike
import pl.iterators.epoca.export.domain.SpreadsheetDomain.{SpreadsheetFormat, SpreadsheetName}
import pl.iterators.epoca.export.domain.{PivotExportArtifact, PivotExportArtifactStore}
import pl.iterators.epoca.utils.RouteSpec

import java.io.IOException
import java.nio.file.{Files, Path, StandardOpenOption}
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters._
import scala.util.Using

class BaseRouterSpec extends RouteSpec with SpecificationLike {
  sequential

  private object router extends BaseRouter
  private implicit val runtime: IORuntime = cats.effect.unsafe.implicits.global

  "BaseRouter.toPivotArtifactResponse" should {
    "expose exact CSV metadata, stream all bytes, and delete only after completion" in withArtifact(SpreadsheetFormat.Csv) {
      case (artifact, payload, sentinel) =>
        val response = router.toPivotArtifactResponse(artifact)
        val before   = Files.exists(artifact.path)
        val bytes    = Await.result(response.entity.dataBytes.runFold(ByteString.empty)(_ ++ _), 5.seconds)

        (before must beTrue) and
          (response.entity.contentType shouldEqual ContentTypes.`text/csv(UTF-8)`) and
          (response.entity.contentLengthOption shouldEqual Some(payload.length.toLong)) and
          (response.header[`Content-Disposition`].map(_.value) must beSome(contain(artifact.fileName))) and
          (bytes.toSeq shouldEqual payload.toSeq) and
          (eventuallyDeleted(artifact.path) must beTrue) and
          (Files.exists(sentinel) must beTrue)
    }

    "expose the exact XLSX content type and length" in withArtifact(SpreadsheetFormat.Xlsx) { case (artifact, payload, _) =>
      val response = router.toPivotArtifactResponse(artifact)
      val bytes    = Await.result(response.entity.dataBytes.runFold(ByteString.empty)(_ ++ _), 5.seconds)

      (response.entity.contentType.mediaType shouldEqual MediaTypes.`application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`) and
        (response.entity.contentLengthOption shouldEqual Some(payload.length.toLong)) and
        (bytes.toSeq shouldEqual payload.toSeq) and
        (eventuallyDeleted(artifact.path) must beTrue)
    }

    "delete the exact artifact when downstream cancels" in withArtifact(SpreadsheetFormat.Csv, payloadSize = 1024 * 1024) {
      case (artifact, _, sentinel) =>
        val response = router.toPivotArtifactResponse(artifact)
        val _        = response.entity.dataBytes.runWith(Sink.cancelled)

        (eventuallyDeleted(artifact.path) must beTrue) and (Files.exists(sentinel) must beTrue)
    }

    "delete the exact artifact when downstream fails" in withArtifact(SpreadsheetFormat.Csv, payloadSize = 1024 * 1024) {
      case (artifact, _, sentinel) =>
        val response = router.toPivotArtifactResponse(artifact)
        val failed = response.entity.dataBytes.runWith(Sink.foreach[ByteString] { _ =>
          throw new IOException("downstream failed")
        })
        val error = Await.result(failed.failed, 5.seconds)

        (error.isInstanceOf[IOException] must beTrue) and
          (error.getMessage shouldEqual "downstream failed") and
          (eventuallyDeleted(artifact.path) must beTrue) and
          (Files.exists(sentinel) must beTrue)
    }

    "delete the exact artifact when the file source fails" in {
      val parent   = Files.createTempDirectory("pivot-router-failed-source-spec-")
      val path     = parent.resolve("broken.csv")
      val _        = Files.createSymbolicLink(path, path.getFileName)
      val sentinel = Files.write(parent.resolve("sentinel"), Array[Byte](1), StandardOpenOption.CREATE_NEW)
      val artifact = PivotExportArtifact(path, SpreadsheetName("pivot.csv"), SpreadsheetFormat.Csv, 1L)
      try {
        val response     = router.toPivotArtifactResponse(artifact)
        val failed       = response.entity.dataBytes.runFold(ByteString.empty)(_ ++ _)
        val _: Throwable = Await.result(failed.failed, 5.seconds)

        (eventuallyDeleted(path) must beTrue) and
          (Files.exists(sentinel) must beTrue)
      } finally deleteTree(parent)
    }

    "expire an abandoned response artifact when its source is never materialized" in withArtifact(
      SpreadsheetFormat.Csv,
      unmaterializedTimeout = 100.millis
    ) { case (artifact, _, sentinel) =>
      val response = router.toPivotArtifactResponse(artifact)

      (response.entity.contentLengthOption shouldEqual Some(artifact.length)) and
        (eventuallyDeleted(artifact.path) must beTrue) and
        (Files.exists(sentinel) must beTrue)
    }

    "atomically claim a materialized response so its expiry cannot delete an active download" in withArtifact(
      SpreadsheetFormat.Csv,
      payloadSize = 16 * 1024 * 1024,
      unmaterializedTimeout = 2.seconds,
      streamIdleTimeout = 5.seconds
    ) { case (artifact, _, sentinel) =>
      val response = router.toPivotArtifactResponse(artifact)
      val queue    = response.entity.dataBytes.runWith(Sink.queue[ByteString]())

      Await.result(queue.pull(), 5.seconds) must beSome
      Thread.sleep(2250L)
      val existsWhileClaimed = Files.exists(artifact.path)
      queue.cancel()

      (existsWhileClaimed must beTrue) and
        (eventuallyDeleted(artifact.path) must beTrue) and
        (Files.exists(sentinel) must beTrue)
    }

    "reject a second materialization without deleting the first active download" in withArtifact(
      SpreadsheetFormat.Csv,
      payloadSize = 16 * 1024 * 1024,
      unmaterializedTimeout = 2.seconds,
      streamIdleTimeout = 2.seconds
    ) { case (artifact, _, sentinel) =>
      val firstQueue = router.toPivotArtifactResponse(artifact).entity.dataBytes.runWith(Sink.queue[ByteString]())
      Await.result(firstQueue.pull(), 5.seconds) must beSome

      val secondFailure = router.toPivotArtifactResponse(artifact).entity.dataBytes.runWith(Sink.ignore).failed
      Await.result(secondFailure, 5.seconds) must beAnInstanceOf[IllegalStateException]
      val existsForFirstDownload = Files.exists(artifact.path)
      firstQueue.cancel()

      (existsForFirstDownload must beTrue) and
        (eventuallyDeleted(artifact.path) must beTrue) and
        (Files.exists(sentinel) must beTrue)
    }

    "terminate and delete a claimed artifact when a materialized download stalls" in withArtifact(
      SpreadsheetFormat.Csv,
      payloadSize = 16 * 1024 * 1024,
      unmaterializedTimeout = 2.seconds,
      streamIdleTimeout = 100.millis
    ) { case (artifact, _, sentinel) =>
      val response = router.toPivotArtifactResponse(artifact)
      val queue    = response.entity.dataBytes.runWith(Sink.queue[ByteString]())

      Await.result(queue.pull(), 5.seconds) must beSome

      (eventuallyDeleted(artifact.path) must beTrue) and (Files.exists(sentinel) must beTrue)
    }

    "pin generation and streaming to the canonical parent when the configured symlink is retargeted" in {
      val root       = Files.createTempDirectory("pivot-router-pinned-directory-spec-")
      val realA      = Files.createDirectory(root.resolve("real-a"))
      val realB      = Files.createDirectory(root.resolve("real-b"))
      val bArtifacts = Files.createDirectory(realB.resolve("artifacts"))
      val sentinel   = Files.write(bArtifacts.resolve("sentinel"), Array[Byte](1), StandardOpenOption.CREATE_NEW)
      val link       = Files.createSymbolicLink(root.resolve("configured-parent"), realA)
      val payload    = ByteString("pinned artifact")
      val store      = new PivotExportArtifactStore(PivotExportArtifactStore.Config(link.resolve("artifacts")))
      try {
        val _ = Files.delete(link)
        Files.createSymbolicLink(link, realB)
        val artifact = store
          .generate(SpreadsheetName("pivot.csv"), SpreadsheetFormat.Csv)(_.write(payload.toArray))
          .unsafeRunSync()
        val artifactParent = artifact.path.getParent.toRealPath()
        val bytes          = Await.result(router.toPivotArtifactResponse(artifact).entity.dataBytes.runFold(ByteString.empty)(_ ++ _), 5.seconds)

        (artifactParent shouldEqual realA.resolve("artifacts").toRealPath()) and
          (bytes shouldEqual payload) and
          (eventuallyDeleted(artifact.path) must beTrue) and
          (Files.exists(sentinel) must beTrue) and
          (regularFiles(bArtifacts) shouldEqual Vector(sentinel))
      } finally deleteTree(root)
    }
  }

  private def withArtifact(
    format: SpreadsheetFormat,
    payloadSize: Int = 32,
    unmaterializedTimeout: scala.concurrent.duration.FiniteDuration = 5.seconds,
    streamIdleTimeout: scala.concurrent.duration.FiniteDuration = 5.seconds
  )(
    result: ((PivotExportArtifact, Array[Byte], Path)) => Result
  ): Result = {
    val parent   = Files.createTempDirectory("pivot-router-spec-")
    val suffix   = "." + SpreadsheetFormat.extension(format)
    val path     = Files.createTempFile(parent, "pivot-", suffix)
    val sentinel = Files.write(parent.resolve("sentinel"), Array[Byte](1), StandardOpenOption.CREATE_NEW)
    val payload  = Array.tabulate(payloadSize)(index => (index % 251).toByte)
    Files.write(path, payload, StandardOpenOption.TRUNCATE_EXISTING)
    val artifact = PivotExportArtifact.leased(
      path,
      SpreadsheetName("pivot_table_2026-08-01_open" + suffix),
      format,
      payload.length.toLong,
      unmaterializedTimeout,
      streamIdleTimeout
    )
    try result((artifact, payload, sentinel))
    finally deleteTree(parent)
  }

  private def eventuallyDeleted(path: Path): Boolean = {
    val deadline = 5.seconds.fromNow
    while (Files.exists(path) && deadline.hasTimeLeft()) Thread.`yield`()
    Files.notExists(path)
  }

  private def regularFiles(directory: Path): Vector[Path] =
    Using.resource(Files.list(directory))(_.iterator().asScala.toVector.sortBy(_.toString))

  private def deleteTree(root: Path): Unit =
    if (Files.exists(root))
      Using.resource(Files.walk(root))(_.sorted(java.util.Comparator.reverseOrder()).forEach { path =>
        val _ = Files.deleteIfExists(path)
      })
}
