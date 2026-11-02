package pl.iterators.epoca.oauth.fixture

import cats.effect.unsafe.IORuntime
import cats.effect.{IO, Resource}
import com.typesafe.config.{Config, ConfigFactory}
import pl.iterators.epoca.utils.db.BaseTable.driver.api._

final class PostgresOAuthE2EFixtureBackendCanceller(database: Database)(implicit runtime: IORuntime) {
  import PostgresOAuthE2EFixtureBackendCanceller.BackendCancellation

  def cancelBackend(backendPid: Int, expectedSessionMarker: String): IO[BackendCancellation] =
    IO.fromFuture(
      IO(
        database.run(
          sql"""with target as materialized (
                   select pid
                   from pg_catalog.pg_stat_activity
                   where pid = $backendPid and application_name = $expectedSessionMarker
                 )
                 select
                   pg_backend_pid(),
                   exists(select 1 from target),
                   coalesce((select pg_cancel_backend(pid) from target), false)"""
            .as[(Int, Boolean, Boolean)]
            .head
            .map { case (cancellerPid, matchedSession, cancelled) =>
              BackendCancellation(cancellerPid, matchedSession, cancelled)
            }(runtime.compute)
        )
      )
    )
}

object PostgresOAuthE2EFixtureBackendCanceller {
  def resource(rootConfig: Config)(implicit runtime: IORuntime): Resource[IO, PostgresOAuthE2EFixtureBackendCanceller] = {
    val dedicatedConfig = ConfigFactory
      .parseString("""
        db.numThreads = 1
        db.minConnections = 1
        db.maxConnections = 1
      """)
      .withFallback(rootConfig)
    Resource
      .make(IO.blocking(Database.forConfig("db", dedicatedConfig)))(database => IO.blocking(database.close()))
      .map(new PostgresOAuthE2EFixtureBackendCanceller(_))
  }

  private[fixture] final case class BackendCancellation(
    cancellerPid: Int,
    matchedSession: Boolean,
    cancelled: Boolean)
}
