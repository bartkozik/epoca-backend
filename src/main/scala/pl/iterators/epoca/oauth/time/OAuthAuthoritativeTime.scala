package pl.iterators.epoca.oauth.time

import pl.iterators.epoca.utils.db.BaseRepository.driver.api.DBIO

import java.time.Instant

trait OAuthAuthoritativeTime {
  def now: DBIO[Instant]
}
