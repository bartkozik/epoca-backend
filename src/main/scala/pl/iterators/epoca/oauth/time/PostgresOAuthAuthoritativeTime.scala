package pl.iterators.epoca.oauth.time

import pl.iterators.epoca.utils.db.BaseRepository.driver.api._

import java.time.Instant
import scala.concurrent.ExecutionContext

final class PostgresOAuthAuthoritativeTime(implicit ec: ExecutionContext) extends OAuthAuthoritativeTime {
  override def now: DBIO[Instant] =
    sql"select extract(epoch from clock_timestamp())".as[BigDecimal].head.map { epochSeconds =>
      val seconds = epochSeconds.longValue
      val nanos   = ((epochSeconds - BigDecimal(seconds)) * BigDecimal(1000000000L)).longValue
      Instant.ofEpochSecond(seconds, nanos)
    }
}
