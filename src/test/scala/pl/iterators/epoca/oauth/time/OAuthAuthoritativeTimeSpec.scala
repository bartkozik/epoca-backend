package pl.iterators.epoca.oauth.time

import pl.iterators.epoca.utils.PgRepositorySpec
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import slick.util.AsyncExecutor

import java.time.Clock
import java.util.{Properties, UUID}
import scala.concurrent.Await
import scala.concurrent.duration._

class OAuthAuthoritativeTimeSpec extends PgRepositorySpec {

  "PostgresOAuthAuthoritativeTime" should {
    "read increasing authoritative instants from PostgreSQL across independent providers" in {
      val firstDatabase  = independentDatabase("oauth-time-first")
      val secondDatabase = independentDatabase("oauth-time-second")

      try {
        val firstProvider  = new PostgresOAuthAuthoritativeTime()
        val secondProvider = new PostgresOAuthAuthoritativeTime()
        val first          = Await.result(firstDatabase.run(firstProvider.now), 10.seconds)
        Await.result(firstDatabase.run(sql"select pg_sleep(0.01)".as[Unit].head), 10.seconds)
        val second = Await.result(secondDatabase.run(secondProvider.now), 10.seconds)

        val constructorTypes = classOf[PostgresOAuthAuthoritativeTime].getConstructors.flatMap(_.getParameterTypes).toSeq
        val fieldTypes       = classOf[PostgresOAuthAuthoritativeTime].getDeclaredFields.map(_.getType).toSeq

        (second.isAfter(first) should beTrue) and
          (!constructorTypes.contains(classOf[Clock]) should beTrue) and
          (!fieldTypes.contains(classOf[Clock]) should beTrue)
      } finally {
        firstDatabase.close()
        secondDatabase.close()
      }
    }
  }

  private def independentDatabase(name: String): Database =
    Database.forURL(
      url = sys.env.getOrElse("TEST_DB_URL", "epoca"),
      user = sys.env.getOrElse("TEST_DB_USER", "postgres"),
      password = sys.env.getOrElse("TEST_DB_PASSWORD", "postgres"),
      driver = "org.postgresql.Driver",
      executor = AsyncExecutor(s"$name-${UUID.randomUUID()}", minThreads = 1, maxThreads = 1, queueSize = 20, maxConnections = 1),
      prop = new Properties()
    )
}
