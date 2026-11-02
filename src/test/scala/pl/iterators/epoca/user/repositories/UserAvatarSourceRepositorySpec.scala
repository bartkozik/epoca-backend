package pl.iterators.epoca.user.repositories

import pl.iterators.epoca.user.domain.UserAvatarSource
import pl.iterators.epoca.user.domain.UserAvatarSource.Source
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.user.repositories.fixtures.UserFixture
import pl.iterators.epoca.utils.PgRepositorySpec
import pl.iterators.epoca.utils.db.BaseTable.driver.api._

import java.time.Instant
import java.util.UUID

class UserAvatarSourceRepositorySpec extends PgRepositorySpec with UserFixture {
  private val sources = new UserAvatarSourceRepositoryImpl
  private val now     = Instant.parse("2026-10-12T10:00:00Z")

  "avatar provenance" should {
    "persist and replace provenance without duplicating a user's row" in withDatabase {
      for {
        user <- withUser()
        first = UserAvatarSource(user.id, Source.Google, Some("google:revision-1"), "https://cdn.test/first.png", now)
        _     <- sources.upsert(first)
        found <- sources.find(user.id)
        manual = first.copy(source = Source.Manual, sourceUrl = None, avatarUrl = "https://cdn.test/manual.png", syncedAt = now.plusSeconds(1))
        _       <- sources.upsert(manual)
        updated <- sources.find(user.id)
        count   <- sql"SELECT count(*) FROM user_avatar_source WHERE user_id=${user.id.toString}::uuid".as[Int].head
      } yield {
        found must beSome(first)
        updated must beSome(manual)
        count mustEqual 1
      }
    }

    "enforce the owning user foreign key" in withDatabase {
      sources
        .upsert(UserAvatarSource(UserId(UUID.randomUUID()), Source.Google, Some("revision"), "https://cdn.test/a.png", now))
        .asTry
        .map(_.failed.get.asInstanceOf[org.postgresql.util.PSQLException].getSQLState mustEqual "23503")
    }

    "reject unsupported source values at the database boundary" in withDatabase {
      for {
        user <- withUser()
        result <-
          sqlu"INSERT INTO user_avatar_source(user_id,source,avatar_url,synced_at) VALUES (${user.id.toString}::uuid,'untrusted','https://cdn.test/a.png',now())".asTry
      } yield result.failed.get.asInstanceOf[org.postgresql.util.PSQLException].getSQLState mustEqual "23514"
    }
  }
}
