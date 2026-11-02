package pl.iterators.epoca.user.repositories

import pl.iterators.epoca.user.domain.UserAvatarSource
import pl.iterators.epoca.user.domain.UserAvatarSource.Source
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.utils.db.BaseTable.driver.api._

import java.time.{OffsetDateTime, ZoneOffset}
import java.util.UUID

final class UserAvatarSourceRepositoryImpl extends UserAvatarSourceRepository {

  override def find(userId: UserId): DBIO[Option[UserAvatarSource]] = SimpleDBIO { ctx =>
    val p = ctx.connection.prepareStatement("SELECT source, source_url, avatar_url, synced_at FROM user_avatar_source WHERE user_id = ?")
    try {
      p.setObject(1, userId: UUID)
      val r = p.executeQuery()
      try
        if (r.next())
          Some(
            UserAvatarSource(
              userId,
              Source.withName(r.getString("source")),
              Option(r.getString("source_url")),
              r.getString("avatar_url"),
              r.getObject("synced_at", classOf[OffsetDateTime]).toInstant
            )
          )
        else None
      finally r.close()
    } finally p.close()
  }

  override def upsert(source: UserAvatarSource): DBIO[Unit] = SimpleDBIO { ctx =>
    val p =
      ctx.connection.prepareStatement("""INSERT INTO user_avatar_source (user_id, source, source_url, avatar_url, synced_at) VALUES (?, ?, ?, ?, ?)
        |ON CONFLICT (user_id) DO UPDATE SET source = EXCLUDED.source, source_url = EXCLUDED.source_url,
        |  avatar_url = EXCLUDED.avatar_url, synced_at = EXCLUDED.synced_at""".stripMargin)
    try {
      p.setObject(1, source.userId: UUID)
      p.setString(2, source.source.entryName)
      p.setString(3, source.sourceUrl.orNull)
      p.setString(4, source.avatarUrl)
      p.setObject(5, source.syncedAt.atOffset(ZoneOffset.UTC))
      p.executeUpdate()
      ()
    } finally p.close()
  }
}
