package pl.iterators.epoca.oauth.fixture

import cats.effect.unsafe.IORuntime
import org.specs2.mutable.SpecificationLike

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.attribute.{PosixFilePermissions, UserPrincipal}
import java.nio.file.{Files, Path}
import scala.util.Try

class OAuthE2EFixtureTerminationSignalSpec extends SpecificationLike {
  sequential

  private implicit val runtime: IORuntime = IORuntime.global

  "OAuth E2E fixture termination signal" should {
    "publish one closed atomic termination-required record with the exact marked PostgreSQL session" in withTemporaryDirectory { directory =>
      val path      = directory.resolve("termination-required.v1")
      val publisher = AtomicFileOAuthE2EFixtureTerminationPublisher.testing(path)
      val signal    = OAuthE2EFixtureTerminationRequired("oauth-e2e-fixture-command-00000000-0000-4000-8000-000000000522")

      publisher.publish(signal).unsafeRunSync()
      publisher.publish(signal).unsafeRunSync()

      Files.readString(path, UTF_8) shouldEqual
        "{\"contract\":\"oauth-e2e-fixture-termination-required-v1\",\"sessionMarker\":\"oauth-e2e-fixture-command-00000000-0000-4000-8000-000000000522\"}\n"
      val entries = Files.list(directory)
      try entries.toArray.map(_.toString).toSeq shouldEqual Seq(path.toString)
      finally entries.close()
      OAuthE2EFixtureTerminationRequired.decode(Files.readAllBytes(path)) shouldEqual Right(signal)
    }

    "reject malformed markers, conflicting terminal records and symlinked supervisor directories" in withTemporaryDirectory { directory =>
      val path      = directory.resolve("termination-required.v1")
      val publisher = AtomicFileOAuthE2EFixtureTerminationPublisher.testing(path)

      publisher.publish(OAuthE2EFixtureTerminationRequired("attacker-marker")).unsafeRunSync() must throwA[IllegalArgumentException]
      publisher
        .publish(OAuthE2EFixtureTerminationRequired("oauth-e2e-fixture-command-00000000-0000-4000-8000-000000000001"))
        .unsafeRunSync()
      publisher
        .publish(OAuthE2EFixtureTerminationRequired("oauth-e2e-fixture-command-00000000-0000-4000-8000-000000000002"))
        .unsafeRunSync() must throwA[IllegalStateException]

      val realDirectory = Files.createDirectory(directory.resolve("real"))
      val symlink       = Files.createSymbolicLink(directory.resolve("link"), realDirectory)
      AtomicFileOAuthE2EFixtureTerminationPublisher.testing(symlink.resolve("termination-required.v1")) must
        throwA[IllegalArgumentException]
      OAuthE2EFixtureTerminationRequired.decode("{}\n".getBytes(UTF_8)) must beLeft
    }

    "reject a supervisor directory whose mode is broader than owner-only access" in withTemporaryDirectory { directory =>
      val originalPermissions = Files.getPosixFilePermissions(directory)
      try {
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxr-x---"))
        AtomicFileOAuthE2EFixtureTerminationPublisher.testing(directory.resolve("termination-required.v1")) must
          throwA[IllegalArgumentException]
      } finally {
        val _ = Files.setPosixFilePermissions(directory, originalPermissions)
      }
    }

    "reject a supervisor directory whose owner is not the trusted process owner" in withTemporaryDirectory { directory =>
      val untrustedOwner = new UserPrincipal {
        override def getName: String = "synthetic-untrusted-owner"
      }

      AtomicFileOAuthE2EFixtureTerminationPublisher.validateTrustedDirectory(directory, untrustedOwner) must
        throwA[IllegalArgumentException]
    }

    "derive the trusted owner from the operating-system identity rather than the mutable JVM user property" in withTemporaryDirectory { directory =>
      val originalUserName = System.getProperty("user.name")
      try {
        val _ = System.setProperty("user.name", "synthetic-untrusted-owner")
        Try(AtomicFileOAuthE2EFixtureTerminationPublisher.testing(directory.resolve("termination-required.v1"))).isSuccess should beTrue
      } finally {
        val _ = System.setProperty("user.name", originalUserName)
      }
    }

    "accept an exact numeric owner even when no username lookup is available" in withTemporaryDirectory { directory =>
      val ownerUid = Files.getAttribute(directory, "unix:uid").asInstanceOf[Number].longValue()
      AtomicFileOAuthE2EFixtureTerminationPublisher.validateTrustedDirectory(directory, ownerUid)
      success
    }
  }

  private def withTemporaryDirectory(result: Path => org.specs2.execute.Result): org.specs2.execute.Result = {
    val directory = Files.createTempDirectory("oauth-e2e-termination-signal-")
    try result(directory)
    finally deleteRecursively(directory)
  }

  private def deleteRecursively(path: Path): Unit =
    if (Files.exists(path) && Files.isDirectory(path) && !Files.isSymbolicLink(path)) {
      val children = Files.list(path)
      try children.forEach(deleteRecursively)
      finally children.close()
      val _ = Files.deleteIfExists(path)
    } else {
      val _ = Files.deleteIfExists(path)
    }
}
