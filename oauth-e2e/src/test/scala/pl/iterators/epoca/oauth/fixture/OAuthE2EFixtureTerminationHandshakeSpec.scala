package pl.iterators.epoca.oauth.fixture

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import com.typesafe.config.{Config, ConfigFactory, ConfigResolveOptions}
import org.specs2.mutable.SpecificationLike
import pl.iterators.epoca.oauth.fixture.OAuthE2EFixtureControlProtocol.ControlDeadline
import pl.iterators.epoca.oauth.fixture.OAuthE2EFixtureDomain.ControllerLeaseState.Terminal
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import slick.util.AsyncExecutor

import java.io.File
import java.net.URLClassLoader
import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit
import java.util.{Base64, Properties, UUID}
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.util.Try

class OAuthE2EFixtureTerminationHandshakeSpec extends SpecificationLike {
  sequential

  private implicit val ec: ExecutionContext = ExecutionContext.global

  "The disposable OAuth fixture termination handshake" should {
    "stop the exact controller process, await its marked PostgreSQL rollback, then permit volume destruction" in {
      val observer    = database("termination-observer", 2)
      val repository  = new OAuthE2EFixtureRepositoryImpl()
      val signalDir   = Files.createTempDirectory("oauth-e2e-command-supervisor-")
      val signalPath  = signalDir.resolve("termination-required.v1")
      val readyPath   = signalDir.resolve("controller-ready")
      val triggerPath = signalDir.resolve("fatal-request")
      val marker      = OAuthE2EFixtureTerminationRuntimeChild.SessionMarker
      val events      = ArrayBuffer.empty[String]
      var child       = Option.empty[Process]

      try {
        prepare(observer)
        val process = startControllerRuntime(signalPath, readyPath, triggerPath, "atomic")
        child = Some(process)

        awaitFile(readyPath, process)
        Files.writeString(triggerPath, "start")
        awaitSessionCount(observer, marker, expected = 1, process)
        awaitSignal(signalPath, process)
        events += "termination-required"

        OAuthE2EFixtureTerminationRequired.decode(Files.readAllBytes(signalPath)) shouldEqual
          Right(OAuthE2EFixtureTerminationRequired(marker))
        process.isAlive should beTrue
        Await.result(observer.run(repository.ledger), 5.seconds).map(_.controllerLease) must beSome(beAnInstanceOf[Terminal])
        sessionCount(observer, marker) shouldEqual 1

        events += "external-controller-stop"
        stopControllerProcess(process)
        child = None
        awaitSessionCount(observer, marker, expected = 0, process, requireProcessAlive = false)
        events += "marked-session-absent"

        Await.result(
          observer.run(sql"select probe_value from oauth_e2e_fixture_command_termination_probe where singleton_key = 1".as[Int].head),
          5.seconds
        ) shouldEqual 0
        Await.result(observer.run(sql"select count(*) from oauth_e2e_fixture_command_termination_audit".as[Int].head), 5.seconds) shouldEqual 0

        events += "destroy-volume"
        events.toVector shouldEqual Vector("termination-required", "external-controller-stop", "marked-session-absent", "destroy-volume")
      } finally {
        child.foreach(stopControllerProcess)
        val _ = Try(dropProbeAndFixtureTables(observer))
        val _ = Try(observer.close())
        val _ = Try(deleteDirectory(signalDir))
      }
    }

    "let an already-armed external watchdog stop a child whose termination publisher hangs" in {
      exerciseExternalWatchdog("hanging")
    }

    "let an already-armed external watchdog stop a child whose termination publisher fails" in {
      exerciseExternalWatchdog("failing")
    }
  }

  private def exerciseExternalWatchdog(publisherMode: String) = {
    val observer    = database(s"termination-watchdog-$publisherMode", 2)
    val repository  = new OAuthE2EFixtureRepositoryImpl()
    val signalDir   = Files.createTempDirectory(s"oauth-e2e-command-watchdog-$publisherMode-")
    val signalPath  = signalDir.resolve("termination-required.v1")
    val readyPath   = signalDir.resolve("controller-ready")
    val triggerPath = signalDir.resolve("fatal-request")
    val marker      = OAuthE2EFixtureTerminationRuntimeChild.SessionMarker
    val events      = ArrayBuffer.empty[String]
    var child       = Option.empty[Process]

    try {
      prepare(observer)
      val process = startControllerRuntime(signalPath, readyPath, triggerPath, publisherMode)
      child = Some(process)
      awaitFile(readyPath, process)

      val watchdogSubmittedAt   = System.nanoTime()
      val watchdogDeadlineNanos = watchdogSubmittedAt + OAuthE2EFixtureTerminationPublisher.RequiredExternalWatchdogTimeout.toNanos
      val watchdogArmed         = Promise[Long]()
      val watchdog = Future {
        Thread.sleep(750L)
        val _ = watchdogArmed.trySuccess(watchdogDeadlineNanos)
        while (System.nanoTime() < watchdogDeadlineNanos && !Files.isRegularFile(signalPath) && process.isAlive) Thread.sleep(5L)
        if (Files.isRegularFile(signalPath)) throw new AssertionError(s"$publisherMode publisher unexpectedly confirmed the signal")
        events.synchronized(events += "external-watchdog-expired")
        events.synchronized(events += "external-controller-stop")
        stopControllerProcess(process)
        awaitSessionCount(observer, marker, expected = 0, process, requireProcessAlive = false)
        events.synchronized(events += "marked-session-absent")
        events.synchronized(events += "destroy-volume")
        System.nanoTime()
      }

      Await.result(watchdogArmed.future, 2.seconds) shouldEqual watchdogDeadlineNanos
      events.synchronized(events += "external-watchdog-armed")
      Files.writeString(triggerPath, "start")
      awaitSessionCount(observer, marker, expected = 1, process)
      val watchdogCompletedAt = Await.result(watchdog, 5.seconds)
      child = None

      (watchdogCompletedAt - watchdogSubmittedAt) must be_<=(
        OAuthE2EFixtureTerminationPublisher.RequiredExternalWatchdogTimeout.toNanos + 250.millis.toNanos
      )

      Await.result(
        observer.run(sql"select probe_value from oauth_e2e_fixture_command_termination_probe where singleton_key = 1".as[Int].head),
        5.seconds
      ) shouldEqual 0
      Await.result(observer.run(sql"select count(*) from oauth_e2e_fixture_command_termination_audit".as[Int].head), 5.seconds) shouldEqual 0
      events.toVector shouldEqual Vector(
        "external-watchdog-armed",
        "external-watchdog-expired",
        "external-controller-stop",
        "marked-session-absent",
        "destroy-volume"
      )
      Await.result(observer.run(repository.auditRows), 5.seconds) should beEmpty
    } finally {
      child.foreach(stopControllerProcess)
      val _ = Try(dropProbeAndFixtureTables(observer))
      val _ = Try(observer.close())
      val _ = Try(deleteDirectory(signalDir))
    }
  }

  private def startControllerRuntime(
    signalPath: Path,
    readyPath: Path,
    triggerPath: Path,
    publisherMode: String
  ): Process = {
    val java = Path.of(System.getProperty("java.home"), "bin", "java").toString
    val builder = new ProcessBuilder(
      java,
      "-cp",
      testRuntimeClasspath,
      "pl.iterators.epoca.oauth.fixture.OAuthE2EFixtureTerminationRuntimeChild",
      signalPath.toString,
      readyPath.toString,
      triggerPath.toString,
      publisherMode
    )
    val environment = builder.environment()
    environment.clear()
    environment.put("TEST_DB_URL", sys.env.getOrElse("TEST_DB_URL", "epoca"))
    environment.put("TEST_DB_USER", sys.env.getOrElse("TEST_DB_USER", "postgres"))
    environment.put("TEST_DB_PASSWORD", sys.env.getOrElse("TEST_DB_PASSWORD", "postgres"))
    builder.redirectOutput(ProcessBuilder.Redirect.DISCARD)
    builder.redirectError(ProcessBuilder.Redirect.DISCARD)
    builder.start()
  }

  private def testRuntimeClasspath: String = {
    val loaders = Iterator
      .iterate(Option(Thread.currentThread().getContextClassLoader))(_.flatMap(loader => Option(loader.getParent)))
      .takeWhile(_.nonEmpty)
      .flatten
    val loaderEntries = loaders.flatMap {
      case loader: URLClassLoader => loader.getURLs.iterator.map(url => Path.of(url.toURI).toString)
      case _                      => Iterator.empty
    }
    val propertyEntries = System.getProperty("java.class.path").split(File.pathSeparator).iterator
    val entries         = (loaderEntries ++ propertyEntries).filter(_.nonEmpty).toVector.distinct
    if (!entries.exists(_.contains("test-classes")) || !entries.exists(_.contains("postgresql")))
      throw new AssertionError("Closed child runtime classpath is incomplete")
    entries.mkString(File.pathSeparator)
  }

  private def stopControllerProcess(process: Process): Unit =
    if (process.isAlive) {
      process.destroy()
      if (!process.waitFor(5L, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        if (!process.waitFor(5L, TimeUnit.SECONDS)) throw new AssertionError("Disposable controller process did not terminate")
      }
    }

  private def prepare(database: Database): Unit = {
    dropProbeAndFixtureTables(database)
    Await.result(
      database.run(
        DBIO.seq(
          sqlu"create table oauth_e2e_fixture_command_termination_probe (singleton_key smallint primary key, probe_value integer not null)",
          sqlu"insert into oauth_e2e_fixture_command_termination_probe (singleton_key, probe_value) values (1, 0)",
          sqlu"create table oauth_e2e_fixture_command_termination_audit (audit_value text not null)"
        )
      ),
      5.seconds
    )
  }

  private def dropProbeAndFixtureTables(database: Database): Unit =
    Await.result(
      database.run(
        DBIO.seq(
          sqlu"drop table if exists oauth_e2e_fixture_command_termination_audit",
          sqlu"drop table if exists oauth_e2e_fixture_command_termination_probe",
          sqlu"drop table if exists oauth_e2e_fixture_ownership",
          sqlu"drop table if exists oauth_e2e_fixture_audit",
          sqlu"drop table if exists oauth_e2e_fixture_run"
        )
      ),
      5.seconds
    )

  private def awaitSignal(path: Path, process: Process): Unit =
    awaitCondition(s"termination signal $path", process)(Files.isRegularFile(path))

  private def awaitFile(path: Path, process: Process): Unit =
    awaitCondition(s"child readiness file $path", process, timeout = 30.seconds)(Files.isRegularFile(path))

  private def awaitSessionCount(
    database: Database,
    marker: String,
    expected: Int,
    process: Process,
    requireProcessAlive: Boolean = true
  ): Unit =
    awaitCondition(s"exact PostgreSQL session count $expected for $marker", process, requireProcessAlive)(sessionCount(database, marker) == expected)

  private def sessionCount(database: Database, marker: String): Int =
    Await.result(database.run(sql"select count(*) from pg_catalog.pg_stat_activity where application_name = $marker".as[Int].head), 2.seconds)

  private def awaitCondition(
    description: String,
    process: Process,
    requireProcessAlive: Boolean = true,
    timeout: scala.concurrent.duration.FiniteDuration = 12.seconds
  )(
    condition: => Boolean
  ): Unit = {
    val deadline = timeout.fromNow
    while (deadline.hasTimeLeft() && !condition && (!requireProcessAlive || process.isAlive)) Thread.sleep(10L)
    if (requireProcessAlive && !process.isAlive && !condition)
      throw new AssertionError(s"Disposable controller exited before $description (exit=${process.exitValue()})")
    if (!condition) throw new AssertionError(s"Timed out awaiting $description")
  }

  private def deleteDirectory(directory: Path): Unit = {
    val entries = Files.list(directory)
    try
      entries.forEach { path =>
        val _ = Files.deleteIfExists(path)
      }
    finally entries.close()
    val _ = Files.deleteIfExists(directory)
  }

  private def database(name: String, maximumConnections: Int): Database =
    Database.forURL(
      url = sys.env.getOrElse("TEST_DB_URL", "epoca"),
      user = sys.env.getOrElse("TEST_DB_USER", "postgres"),
      password = sys.env.getOrElse("TEST_DB_PASSWORD", "postgres"),
      driver = "org.postgresql.Driver",
      executor = AsyncExecutor(
        s"command-$name-${UUID.randomUUID()}",
        minThreads = maximumConnections,
        maxThreads = maximumConnections,
        queueSize = 20,
        maxConnections = maximumConnections
      ),
      prop = new Properties()
    )
}

object OAuthE2EFixtureTerminationRuntimeChild {
  val SessionMarker = "oauth-e2e-fixture-command-00000000-0000-4000-8000-000000000522"

  def main(arguments: Array[String]): Unit = {
    require(arguments.length == 4, "Expected exact signal, ready, trigger paths and publisher mode")
    implicit val runtime: IORuntime   = IORuntime.global
    implicit val ec: ExecutionContext = ExecutionContext.global
    implicit val actorSystem: org.apache.pekko.actor.ActorSystem =
      org.apache.pekko.actor.ActorSystem("oauth-e2e-command-disposable-controller", ConfigFactory.parseString("pekko.loglevel=OFF"))
    implicit val materializer: org.apache.pekko.stream.Materializer = org.apache.pekko.stream.SystemMaterializer(actorSystem).materializer

    val primary              = database("child-primary", 3)
    val cancel               = database("child-cancel", 1)
    val repository           = new OAuthE2EFixtureRepositoryImpl()
    val transactor           = new PostgresIOTransactor(primary)
    val cancelLease          = scala.concurrent.Promise[Unit]()
    val cancelReady          = scala.concurrent.Promise[Unit]()
    val unsettledTransaction = scala.concurrent.Promise[Unit]()
    @volatile var supervisor = Option.empty[OAuthE2EFixtureControllerSupervisor]

    val terminationPublisher: OAuthE2EFixtureTerminationPublisher = arguments(3) match {
      case "atomic"  => AtomicFileOAuthE2EFixtureTerminationPublisher.testing(Path.of(arguments(0)))
      case "hanging" => (_: OAuthE2EFixtureTerminationRequired) => IO.never
      case "failing" => (_: OAuthE2EFixtureTerminationRequired) => IO.raiseError(new IllegalStateException("synthetic publisher failure"))
      case _         => throw new IllegalArgumentException("Unsupported termination publisher mode")
    }
    val allocated = OAuthE2EFixtureControlServer
      .resource(
        controllerConfig,
        Some(repository),
        transactor,
        Some(new PostgresOAuthE2EFixtureBackendCanceller(cancel)),
        Some(terminationPublisher),
        (value, _) => {
          supervisor = Some(value)
          OAuthE2EFixtureControlHandler.unavailable
        },
        new OAuthE2EFixtureControlBinder {
          override def bind(
            interface: String,
            port: Int,
            route: org.apache.pekko.http.scaladsl.server.Route
          ): cats.effect.Resource[cats.effect.IO, Unit] = cats.effect.Resource.unit
        }
      )
      .allocated
      .unsafeRunSync()

    val _ = cancel.run(
      sql"select pg_backend_pid()"
        .as[Int]
        .head
        .flatMap(_ => DBIO.successful { cancelReady.trySuccess(()); () } >> DBIO.from(cancelLease.future))
        .withPinnedSession
    )
    Await.result(cancelReady.future, 5.seconds)
    Files.writeString(Path.of(arguments(1)), "ready")
    val triggerDeadline = 10.seconds.fromNow
    while (triggerDeadline.hasTimeLeft() && !Files.isRegularFile(Path.of(arguments(2)))) Thread.sleep(5L)
    if (!Files.isRegularFile(Path.of(arguments(2)))) throw new IllegalStateException("Fatal request trigger was not received")

    val deadline = ControlDeadline.testing(System.nanoTime(), activeNanos = 150000000L, totalNanos = 500000000L)
    try {
      val _ = new OAuthE2EFixtureCommandExecutor(
        transactor,
        new PostgresOAuthE2EFixtureBackendCanceller(cancel),
        supervisor.getOrElse(throw new IllegalStateException("Controller supervisor was not projected")),
        sessionMarker = () => SessionMarker
      ).execute(deadline) { transaction =>
        transaction.statement(sqlu"update oauth_e2e_fixture_command_termination_probe set probe_value = probe_value + 1 where singleton_key = 1") >>
          transaction.statement(sqlu"insert into oauth_e2e_fixture_command_termination_audit (audit_value) values ('late')") >>
          transaction.statement(DBIO.from(unsettledTransaction.future))
      }.unsafeRunSync()
    } finally {
      val _ = cancelLease.trySuccess(())
      allocated._2.attempt.unsafeRunSync()
      primary.close()
      cancel.close()
      val _ = Await.result(actorSystem.terminate(), 5.seconds)
    }
  }

  private def database(name: String, maximumConnections: Int): Database =
    Database.forURL(
      url = sys.env("TEST_DB_URL"),
      user = sys.env("TEST_DB_USER"),
      password = sys.env("TEST_DB_PASSWORD"),
      driver = "org.postgresql.Driver",
      executor = AsyncExecutor(
        s"command-$name-${UUID.randomUUID()}",
        minThreads = maximumConnections,
        maxThreads = maximumConnections,
        queueSize = 20,
        maxConnections = maximumConnections
      ),
      prop = new Properties()
    )

  private val applicationConfig = ConfigFactory
    .parseResources("oauth-e2e.conf")
    .withFallback(ConfigFactory.parseResources("application.conf"))
    .resolve(ConfigResolveOptions.defaults().setAllowUnresolved(true))
  private val signingKeyA  = encodedBytes(1)
  private val signingKeyB  = encodedBytes(33)
  private val controlToken = s"oauth-e2e-v1.${encodedBytes(65)}"

  private lazy val controllerConfig: OAuthE2EFixtureConfig = {
    val config: Config = ConfigFactory
      .parseString(s"""
        app.environment = "oauth-e2e"
        db.url = "jdbc:postgresql://127.0.0.1:55422/oauth_e2e_repository"
        db.password = "oauth-e2e-command-database-secret"
        login.jwtSecret = "oauth-e2e-command-login-secret"
        oauth.enabled = true
        oauth.signing.activeKid = "oauth-a"
        oauth.signing.keys = [
          { kid = "oauth-a", material = "$signingKeyA", retired = false },
          { kid = "oauth-b", material = "$signingKeyB", retired = false }
        ]
        oauth.e2eFixture.timeEnabled = true
        oauth.e2eFixture.controlListenerEnabled = true
        oauth.e2eFixture.disposableAck = "oauth-e2e-disposable-v1"
        oauth.e2eFixture.deploymentTier = "disposable"
        oauth.e2eFixture.listenerInterface = "127.0.0.1"
        oauth.e2eFixture.listenerPort = 19042
        oauth.e2eFixture.controlToken = "$controlToken"
      """)
      .withFallback(applicationConfig)
      .resolve(ConfigResolveOptions.defaults().setAllowUnresolved(true))
    OAuthE2EFixtureConfig.load(config).fold(error => throw new IllegalStateException(s"Invalid fixture config: $error"), identity)
  }

  private def encodedBytes(start: Int): String =
    Base64.getUrlEncoder.withoutPadding().encodeToString(Array.tabulate[Byte](32)(index => (start + index).toByte))
}
