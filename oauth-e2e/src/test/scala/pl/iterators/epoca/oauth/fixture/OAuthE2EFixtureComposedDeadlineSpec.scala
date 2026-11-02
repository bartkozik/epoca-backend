package pl.iterators.epoca.oauth.fixture

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.apache.pekko.http.scaladsl.model.headers.{Authorization, OAuth2BearerToken, RawHeader}
import org.apache.pekko.http.scaladsl.model.{HttpMethods, HttpRequest, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.Specs2RouteTest
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.specs2.mutable.SpecificationLike
import pl.iterators.epoca.oauth.fixture.OAuthE2EFixtureCommandExecutor.ExecutionResult
import pl.iterators.epoca.oauth.fixture.OAuthE2EFixtureControlProtocol.{ControlCommand, ControlDeadline, ControlRequestContext, ControlResult}
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import slick.util.AsyncExecutor

import java.util.concurrent.atomic.AtomicInteger
import java.util.{Base64, Properties, UUID}
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future, Promise}
import scala.util.Try

class OAuthE2EFixtureComposedDeadlineSpec extends SpecificationLike with Specs2RouteTest {
  sequential

  "The composed OAuth fixture control deadline" should {
    "keep the HTTP response pending until the exact real-PG child rolls back or reaches its marked fatal handoff" in {
      val primary       = database("composed-primary", 2)
      val cancelDb      = database("composed-cancel", 1)
      val observer      = database("composed-observer", 2)
      val cancelLease   = Promise[Unit]()
      val cancelReady   = Promise[Unit]()
      val observedPid   = Promise[Int]()
      val handoff       = Promise[String]()
      val externalStop  = Promise[Unit]()
      val unsettledStep = Promise[Unit]()
      val unmarkedStops = new AtomicInteger(0)
      val marker        = "oauth-e2e-fixture-command-command-composed-http"
      val cancelPoolUse = cancelDb.run(
        sql"select pg_backend_pid()"
          .as[Int]
          .head
          .flatMap(_ => DBIO.successful { cancelReady.trySuccess(()); () } >> DBIO.from(cancelLease.future))
          .withPinnedSession
      )
      val supervisor = new OAuthE2EFixtureControllerSupervisor {
        override def stopControllerAndDestroyRun: IO[Unit] = IO { unmarkedStops.incrementAndGet(); () } *> IO.fromFuture(IO(externalStop.future))
        override def stopControllerAndDestroyRun(sessionMarker: String): IO[Unit] =
          IO { handoff.trySuccess(sessionMarker); () } *> IO.fromFuture(IO(externalStop.future))
      }

      try {
        prepare(primary)
        Await.result(cancelReady.future, 5.seconds)
        val commandExecutor = new OAuthE2EFixtureCommandExecutor(
          new PostgresIOTransactor(primary),
          new PostgresOAuthE2EFixtureBackendCanceller(cancelDb),
          supervisor,
          backendPidObserved = pid => { observedPid.trySuccess(pid); () },
          sessionMarker = () => marker
        )
        val handler = new OAuthE2EFixtureControlHandler {
          override def handle(
            command: ControlCommand,
            idempotencyKey: UUID,
            context: ControlRequestContext
          ): IO[ControlResult] =
            commandExecutor
              .execute(context) { transaction =>
                transaction.statement(
                  sqlu"update oauth_e2e_fixture_command_composed_probe set probe_value = probe_value + 1 where singleton_key = 1"
                ) >>
                  transaction.statement(sqlu"insert into oauth_e2e_fixture_command_composed_audit (audit_value) values ('late')") >>
                  transaction.statement(DBIO.from(unsettledStep.future))
              }
              .flatMap {
                case ExecutionResult.Succeeded(_, _) => IO.pure(ControlResult(StatusCodes.OK, "{\"result\":\"ok\"}"))
                case ExecutionResult.TimedOut(_)     => IO.pure(ControlResult(StatusCodes.InternalServerError, "{\"error\":\"timed-out\"}"))
                case ExecutionResult.Failed(error)   => IO.raiseError(error)
              }
        }
        val router = new OAuthE2EFixtureControlRouter(
          new OAuthE2EFixtureControlTokenVerifier(controlToken),
          new OAuthE2EFixtureFailedAuthLimiter(() => System.nanoTime()),
          handler,
          supervisor = supervisor,
          commandDeadline = admitted => ControlDeadline.testing(admitted, activeNanos = 150000000L, totalNanos = 500000000L)
        )
        val runId = UUID.fromString("00000000-0000-4000-8000-00000000a522")
        val request = HttpRequest(
          HttpMethods.POST,
          uri = s"/oauth-e2e-fixture/v1/runs/$runId/advance-300-seconds",
          headers = List(Authorization(OAuth2BearerToken(controlToken)), RawHeader("Idempotency-Key", "00000000-0000-4000-8000-000000000004"))
        )
        val response = Source.single(request).via(Route.toFlow(Route.seal(router.route))).runWith(Sink.head)
        val pid      = Await.result(observedPid.future, 5.seconds)
        awaitSessionCount(observer, marker, expected = 1)

        Await.result(
          Future.firstCompletedOf(Vector(response.map(_ => "response"), handoff.future.map(_ => "handoff"))),
          3.seconds
        ) shouldEqual "handoff"
        Await.result(handoff.future, 1.second) shouldEqual marker
        response.value should beNone
        unmarkedStops.get() shouldEqual 0
        sessionCount(observer, marker) shouldEqual 1

        Await.result(observer.run(sql"select pg_terminate_backend($pid)".as[Boolean].head), 2.seconds) should beTrue
        awaitSessionCount(observer, marker, expected = 0)
        unsettledStep.trySuccess(()) should beTrue
        externalStop.trySuccess(()) should beTrue
        cancelLease.trySuccess(()) should beTrue
        Await.result(response, 2.seconds).status shouldEqual StatusCodes.InternalServerError
        Await.ready(cancelPoolUse, 2.seconds)

        Await.result(observer.run(probeValue), 2.seconds) shouldEqual 0
        Await.result(observer.run(auditCount), 2.seconds) shouldEqual 0
      } finally {
        val _ = cancelLease.trySuccess(())
        val _ = unsettledStep.trySuccess(())
        val _ = externalStop.trySuccess(())
        val _ = Await.ready(cancelPoolUse, 2.seconds)
        val _ = Try(dropTables(observer))
        primary.close()
        cancelDb.close()
        observer.close()
      }
    }
  }

  private def prepare(database: Database): Unit = {
    dropTables(database)
    Await.result(
      database.run(
        DBIO.seq(
          sqlu"create table oauth_e2e_fixture_command_composed_probe (singleton_key smallint primary key, probe_value integer not null)",
          sqlu"insert into oauth_e2e_fixture_command_composed_probe (singleton_key, probe_value) values (1, 0)",
          sqlu"create table oauth_e2e_fixture_command_composed_audit (audit_value text not null)"
        )
      ),
      5.seconds
    )
  }

  private def dropTables(database: Database): Unit =
    Await.result(
      database.run(
        DBIO.seq(
          sqlu"drop table if exists oauth_e2e_fixture_command_composed_audit",
          sqlu"drop table if exists oauth_e2e_fixture_command_composed_probe"
        )
      ),
      5.seconds
    )

  private val probeValue: DBIO[Int] =
    sql"select probe_value from oauth_e2e_fixture_command_composed_probe where singleton_key = 1".as[Int].head

  private val auditCount: DBIO[Int] =
    sql"select count(*) from oauth_e2e_fixture_command_composed_audit".as[Int].head

  private def awaitSessionCount(
    database: Database,
    marker: String,
    expected: Int
  ): Unit = {
    val deadline = 3.seconds.fromNow
    var count    = -1
    while (deadline.hasTimeLeft() && count != expected) count = sessionCount(database, marker)
    if (count != expected) throw new AssertionError(s"Expected exact PostgreSQL session count $expected for $marker, observed $count")
  }

  private def sessionCount(database: Database, marker: String): Int =
    Await.result(database.run(sql"select count(*) from pg_catalog.pg_stat_activity where application_name = $marker".as[Int].head), 1.second)

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

  private val controlToken: String = {
    val bytes = Array.tabulate[Byte](32)(index => (index + 64).toByte)
    "oauth-e2e-v1." + Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)
  }
}
