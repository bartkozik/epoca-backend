package pl.iterators.epoca.main

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import com.typesafe.config.{Config, ConfigFactory, ConfigResolveOptions}
import org.mockito.MockitoSugar
import org.specs2.mutable.SpecificationLike
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.job.TriggeredJobScheduler
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.WebSocketStreamBackend

import java.net.URL
import java.nio.file.{Files, Path}
import scala.concurrent.ExecutionContext

class MainSpec extends SpecificationLike with MockitoSugar {
  private implicit val executor: ExecutionContext = ExecutionContext.global
  private implicit val runtime: IORuntime         = IORuntime.global

  private val productionResources = Main.getClass.getProtectionDomain.getCodeSource.getLocation
  private val applicationConfig = ConfigFactory
    .parseString("""
      db {
        url = "jdbc:postgresql://unused"
        user = "unused"
        password = "unused"
      }
    """)
    .withFallback(ConfigFactory.parseURL(new URL(productionResources, "application.conf")))
    .resolve(ConfigResolveOptions.defaults().setUseSystemEnvironment(false))

  private def loader(config: Config): ApplicationLoader =
    new ApplicationLoader(
      config,
      executor,
      runtime,
      mock[PostgresIOTransactor],
      mock[WebSocketStreamBackend[IO, Fs2Streams[IO]]],
      mock[TriggeredJobScheduler],
      mock[cats.effect.std.Supervisor[IO]]
    )

  private def config(overrides: String): Config = ConfigFactory.parseString(overrides).withFallback(applicationConfig)

  "production OAuth time" should {
    "use PostgreSQL even when fixture settings are supplied" in {
      val configured = loader(config("oauth.e2eFixture.timeEnabled = true"))
      configured.oauthAuthoritativeTime must beAnInstanceOf[pl.iterators.epoca.oauth.time.PostgresOAuthAuthoritativeTime]
    }

    "omit fixture configuration from production defaults" in {
      applicationConfig.hasPath("oauth.e2eFixture") must beFalse
    }
  }

  "initializeClientModule" should {
    "reject an invalid client mutation timeout before serving requests" in {
      Main.initializeClientModule(loader(config("client.mutation.lockTimeout = 0ms"))).unsafeRunSync() must throwA[IllegalArgumentException]
    }

    "complete for a valid client mutation timeout" in {
      Main.initializeClientModule(loader(config("client.mutation.lockTimeout = 5s"))).unsafeRunSync() shouldEqual ()
    }
  }

  "initializeExportModule" should {
    "reject invalid export storage before serving requests" in {
      val invalid = config("export.pivot.tempDirectory = \"/dev/null/artifacts\"")

      Main.initializeExportModule(loader(invalid)).unsafeRunSync() must throwA[IllegalArgumentException]
    }

    "complete for valid private export storage" in withTemporaryDirectory { directory =>
      val valid = config(s"export.pivot.tempDirectory = \"${directory.resolve("artifacts")}\"")

      Main.initializeExportModule(loader(valid)).unsafeRunSync() shouldEqual ()
    }
  }

  private def withTemporaryDirectory(result: Path => org.specs2.execute.Result): org.specs2.execute.Result = {
    val directory = Files.createTempDirectory("main-export-module-spec-")
    try result(directory)
    finally {
      val _ = Files.deleteIfExists(directory.resolve("artifacts"))
      val _ = Files.deleteIfExists(directory)
    }
  }
}
