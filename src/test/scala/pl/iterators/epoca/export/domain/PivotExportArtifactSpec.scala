package pl.iterators.epoca.`export`.domain

import cats.effect.unsafe.implicits.global
import cats.syntax.parallel._
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.specs2.mutable.SpecificationLike
import pl.iterators.epoca.export.domain.PivotExportProjection.Value.Count
import pl.iterators.epoca.export.domain.PivotExportProjection.{ColumnHeader, DataRow, RowHeader, Table}
import pl.iterators.epoca.export.domain.SpreadsheetDomain.{SpreadsheetFormat, SpreadsheetName}
import pl.iterators.epoca.stats.domain.StatsDomain.WorkUnitPivotTableValue
import pl.iterators.epoca.stats.domain.WorkUnitPivotQuery.{Coordinate, CoordinateKind}

import java.io.{IOException, OutputStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path, StandardOpenOption}
import scala.concurrent.duration.{Duration, DurationInt}
import scala.jdk.CollectionConverters._
import scala.util.Using

class PivotExportArtifactSpec extends SpecificationLike {
  sequential

  "PivotExportArtifactStore" should {
    "pre-generate a closed artifact with exact metadata and restrictive permissions" in withTemporaryParent { parent =>
      val directory                    = parent.resolve("artifacts")
      val store                        = new PivotExportArtifactStore(PivotExportArtifactStore.Config(directory))
      val fileName                     = SpreadsheetName("pivot_table_2026-08-01_open.csv")
      val payload                      = "complete export".getBytes(UTF_8)
      var output: Option[OutputStream] = None

      val artifact = store
        .generate(fileName, SpreadsheetFormat.Csv) { stream =>
          output = Some(stream)
          stream.write(payload)
        }
        .unsafeRunSync()

      val permissionChecks =
        if (Files.getFileStore(directory).supportsFileAttributeView("posix"))
          (Files.getPosixFilePermissions(directory) shouldEqual PosixFilePermissions.fromString("rwx------")) and
            (Files.getPosixFilePermissions(artifact.path) shouldEqual PosixFilePermissions.fromString("rw-------"))
        else ok

      (artifact.path.getParent shouldEqual directory.toRealPath()) and
        (artifact.path.getFileName.toString must startWith("pivot-")) and
        (artifact.path.getFileName.toString must endWith(".csv")) and
        (artifact.fileName shouldEqual fileName) and
        (artifact.format shouldEqual SpreadsheetFormat.Csv) and
        (artifact.length shouldEqual payload.length.toLong) and
        (Files.readAllBytes(artifact.path).toSeq shouldEqual payload.toSeq) and
        (output.get.write(1) must throwA[IOException]) and
        permissionChecks
    }

    "delete a partial create-new artifact when generation fails" in withTemporaryParent { parent =>
      val directory = parent.resolve("artifacts")
      val store     = new PivotExportArtifactStore(PivotExportArtifactStore.Config(directory))
      val failure = store
        .generate(SpreadsheetName("pivot.csv"), SpreadsheetFormat.Csv) { output =>
          output.write("partial".getBytes(UTF_8))
          throw new IOException("encoder failed")
        }
        .attempt
        .unsafeRunSync()

      (failure must beLeft.like { case error => error.getMessage shouldEqual "encoder failed" }) and
        (regularFiles(directory) should beEmpty)
    }

    "never reuse or overwrite an existing path and create unique artifacts concurrently" in withTemporaryParent { parent =>
      val directory       = parent.resolve("artifacts")
      val _               = Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
      val existing        = directory.resolve("pivot-existing.csv")
      val existingPayload = "do not overwrite".getBytes(UTF_8)
      Files.write(existing, existingPayload, StandardOpenOption.CREATE_NEW)
      val store = new PivotExportArtifactStore(PivotExportArtifactStore.Config(directory))

      val artifacts = Vector
        .tabulate(32)(index => store.generate(SpreadsheetName(s"pivot-$index.csv"), SpreadsheetFormat.Csv)(_.write(index.toString.getBytes(UTF_8))))
        .parSequence
        .unsafeRunSync()

      (artifacts.map(_.path).distinct.size shouldEqual artifacts.size) and
        (artifacts.map(_.path) must not(contain(existing))) and
        (Files.readAllBytes(existing).toSeq shouldEqual existingPayload.toSeq) and
        (artifacts.forall(artifact => Files.exists(artifact.path) && artifact.length > 0L) must beTrue)
    }

    "initialize when the numeric process UID has no resolvable account name" in withTemporaryParent { parent =>
      withUserName(s"missing-epoca-user-${java.util.UUID.randomUUID()}") {
        val directory = parent.resolve("artifacts")
        val store     = new PivotExportArtifactStore(PivotExportArtifactStore.Config(directory))

        val artifact = store
          .generate(SpreadsheetName("pivot.csv"), SpreadsheetFormat.Csv)(_.write(1))
          .unsafeRunSync()

        (Files.isDirectory(directory) must beTrue) and (Files.exists(artifact.path) must beTrue)
      }
    }

    "trust the effective UID and numeric root only" in {
      (NumericOwnershipPolicy.isTrustedOwner(currentUid = 1001L, ownerUid = 1001L) must beTrue) and
        (NumericOwnershipPolicy.isTrustedOwner(currentUid = 1001L, ownerUid = 0L) must beTrue) and
        (NumericOwnershipPolicy.isTrustedOwner(currentUid = 1001L, ownerUid = 2002L) must beFalse)
    }

    "allow a shared-writable ancestor only when numeric root owns it and it is sticky" in {
      (NumericOwnershipPolicy.isSafeAncestor(1001L, 0L, sharedWritable = true, sticky = true) must beTrue) and
        (NumericOwnershipPolicy.isSafeAncestor(1001L, 0L, sharedWritable = true, sticky = false) must beFalse) and
        (NumericOwnershipPolicy.isSafeAncestor(1001L, 2002L, sharedWritable = true, sticky = true) must beFalse)
    }

    "read the filesystem UID from Linux process status without resolving an account name" in {
      val status = """
        |Name: epoca-backend
        |Uid: 1000 1001 1002 1003
        |Gid: 2000 2001 2002 2003
        |""".stripMargin

      LinuxProcessUserId.parse(status) shouldEqual 1003L
    }

    "reject malformed Linux process UID values" in {
      (LinuxProcessUserId.parse("Name:\tepoca-backend\n") must throwA[IllegalArgumentException]) and
        (LinuxProcessUserId.parse("Uid: 1000 1001 1002\n") must throwA[IllegalArgumentException]) and
        (LinuxProcessUserId.parse("Uid: 1000 1001 1002 1003 1004\n") must throwA[IllegalArgumentException]) and
        (LinuxProcessUserId.parse("Uid: 1000 1001 broken 1003\n") must throwA[IllegalArgumentException]) and
        (LinuxProcessUserId.parse("Uid: 1000 1001 1002 -1\n") must throwA[IllegalArgumentException]) and
        (LinuxProcessUserId.parse("Uid: 1000 1001 1002 9223372036854775808\n") must
          throwA[IllegalArgumentException])
    }

    "take the filesystem UID from Linux process status when the status file exists" in withTemporaryParent { parent =>
      val status = Files.writeString(parent.resolve("status"), "Name: epoca-backend\nUid: 1000 1001 1002 1003\n")

      EffectiveUserId.resolve(status, parent) shouldEqual 1003L
    }

    "fall back to the owner of a new probe file when Linux process status does not exist" in withTemporaryParent { parent =>
      val reference = Files.createFile(parent.resolve("reference"))
      val expected  = UnixFileOwnerId.read(reference)
      Files.delete(reference)

      (EffectiveUserId.resolve(parent.resolve("missing-status"), parent) shouldEqual expected) and
        (Using.resource(Files.list(parent))(_.iterator().asScala.toList) must beEmpty)
    }

    "accept surrounding whitespace in the Linux process UID row" in {
      LinuxProcessUserId.parse("  Uid:   1000 1001 1002 1003   \n") shouldEqual 1003L
    }

    "reject an existing insecure directory without changing its permissions or creating a file" in withTemporaryParent { parent =>
      val directory = Files.createDirectory(parent.resolve("shared-artifacts"))
      val insecure  = PosixFilePermissions.fromString("rwxrwxrwx")
      val _         = Files.setPosixFilePermissions(directory, insecure)

      new PivotExportArtifactStore(PivotExportArtifactStore.Config(directory)) must throwA[IllegalArgumentException]

      (Files.getPosixFilePermissions(directory) shouldEqual insecure) and (regularFiles(directory) should beEmpty)
    }

    "allow a dedicated safe directory beneath a system-style symlinked parent" in withTemporaryParent { parent =>
      val realParent   = Files.createDirectory(parent.resolve("real-parent"))
      val linkedParent = Files.createSymbolicLink(parent.resolve("linked-parent"), realParent)
      val directory    = linkedParent.resolve("artifacts")
      val store        = new PivotExportArtifactStore(PivotExportArtifactStore.Config(directory))
      val artifact     = store.generate(SpreadsheetName("pivot.csv"), SpreadsheetFormat.Csv)(_.write(1)).unsafeRunSync()

      (Files.isSymbolicLink(linkedParent) must beTrue) and
        (Files.isSymbolicLink(directory) must beFalse) and
        (Files.getPosixFilePermissions(directory) shouldEqual PosixFilePermissions.fromString("rwx------")) and
        (Files.exists(artifact.path) must beTrue)
    }

    "reject a group-or-other-writable configured ancestor without creating the target" in withTemporaryParent { parent =>
      val unsafeAncestor = Files.createDirectory(parent.resolve("unsafe-ancestor"))
      val insecure       = PosixFilePermissions.fromString("rwxrwxrwx")
      val _              = Files.setPosixFilePermissions(unsafeAncestor, insecure)
      val directory      = unsafeAncestor.resolve("artifacts")

      new PivotExportArtifactStore(PivotExportArtifactStore.Config(directory)) must throwA[IllegalArgumentException]

      (Files.getPosixFilePermissions(unsafeAncestor) shouldEqual insecure) and
        (Files.notExists(directory) must beTrue)
    }

    "reject invalid lease bounds during store construction" in withTemporaryParent { parent =>
      val directory = parent.resolve("artifacts")

      PivotExportArtifactStore.Config(directory, Duration.Zero, 1.second) must throwA[IllegalArgumentException]
      PivotExportArtifactStore.Config(directory, 1.second, Duration.Zero) must throwA[IllegalArgumentException]
      PivotExportArtifactStore.Config(directory, 10.minutes, 1.second) must throwA[IllegalArgumentException]
      PivotExportArtifactStore.Config(directory, 1.second, 1.hour) must throwA[IllegalArgumentException]
    }

    "reject an unsafe response filename before creating an artifact" in withTemporaryParent { parent =>
      val directory = parent.resolve("artifacts")
      val store     = new PivotExportArtifactStore(PivotExportArtifactStore.Config(directory))

      val attempts = Seq(
        store.generate(SpreadsheetName("../pivot.csv"), SpreadsheetFormat.Csv)(_ => ()),
        store.generate(SpreadsheetName("pivot\n.csv"), SpreadsheetFormat.Csv)(_ => ()),
        store.generate(SpreadsheetName("pivot.xlsx"), SpreadsheetFormat.Csv)(_ => ())
      ).map(_.attempt.unsafeRunSync())

      (attempts.forall(_.isLeft) && Files.isDirectory(directory) && regularFiles(directory).isEmpty) must beTrue
    }

    "dispatch CSV and XLSX generation through their streaming writers" in withTemporaryParent { parent =>
      val store = new PivotExportArtifactStore(PivotExportArtifactStore.Config(parent.resolve("artifacts")))
      val table = Table(
        rowFieldLabels = Vector("User"),
        columns = Vector(ColumnHeader(Coordinate(CoordinateKind.Leaf, Seq.empty), Vector.empty, WorkUnitPivotTableValue.EntriesCount)),
        rows = Vector(DataRow(RowHeader(Vector(Some("Alex")), CoordinateKind.Leaf), Vector(Some(Count(1L)))))
      )
      val labels: Map[WorkUnitPivotTableValue, String] = Map(WorkUnitPivotTableValue.EntriesCount -> "Entries")

      val csv = store
        .generate(SpreadsheetName("pivot.csv"), SpreadsheetFormat.Csv, table, labels)
        .unsafeRunSync()
      val xlsx = store
        .generate(SpreadsheetName("pivot.xlsx"), SpreadsheetFormat.Xlsx, table, labels)
        .unsafeRunSync()

      val csvBytes = Files.readAllBytes(csv.path)
      val xlsxCheck = Using.resource(new XSSFWorkbook(Files.newInputStream(xlsx.path))) { workbook =>
        (workbook.getSheet("Pivot").getRow(0).getCell(0).getStringCellValue shouldEqual "User") and
          (workbook.getSheet("Pivot").getRow(1).getCell(1).getNumericCellValue shouldEqual 1d)
      }

      (csvBytes.take(3).toSeq shouldEqual Seq(0xef.toByte, 0xbb.toByte, 0xbf.toByte)) and
        (new String(csvBytes.drop(3), UTF_8) shouldEqual "User,Entries\r\nAlex,1\r\n") and
        xlsxCheck
    }

    "discard an unconsumed artifact idempotently" in withTemporaryParent { parent =>
      val store = new PivotExportArtifactStore(PivotExportArtifactStore.Config(parent.resolve("artifacts")))
      val artifact = store
        .generate(SpreadsheetName("pivot.csv"), SpreadsheetFormat.Csv)(_.write(1))
        .unsafeRunSync()

      val first  = artifact.discard.unsafeRunSync()
      val second = artifact.discard.unsafeRunSync()

      (first must beTrue) and (second must beFalse) and (Files.exists(artifact.path) must beFalse)
    }
  }

  private def withTemporaryParent(result: Path => org.specs2.execute.Result): org.specs2.execute.Result = {
    val parent = Files.createTempDirectory("pivot-artifact-spec-")
    try result(parent)
    finally deleteTree(parent)
  }

  private def withUserName[A](value: String)(result: => A): A = {
    val previous = Option(System.getProperty("user.name"))
    System.setProperty("user.name", value)
    try result
    finally
      previous match {
        case Some(name) =>
          val _ = System.setProperty("user.name", name)
        case None =>
          val _ = System.clearProperty("user.name")
      }
  }

  private def regularFiles(directory: Path): Vector[Path] =
    if (Files.notExists(directory)) Vector.empty
    else Using.resource(Files.list(directory))(_.iterator().asScala.filter(Files.isRegularFile(_)).toVector)

  private def deleteTree(root: Path): Unit =
    if (Files.exists(root))
      Using.resource(Files.walk(root))(_.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists))
}
