package pl.iterators.epoca.user.services

import cats.effect.unsafe.implicits.global
import cats.effect.{Deferred, IO}
import pl.iterators.epoca.storage.{FileStorage, InMemoryFileStorage}
import pl.iterators.epoca.user.domain.UserDomain.{UserAvatar, UserId}
import pl.iterators.epoca.user.domain.{User, UserAvatarSource}
import pl.iterators.epoca.user.repositories.fixtures.UserFixture
import pl.iterators.epoca.user.repositories.{UserAvatarSourceRepository, UserAvatarSourceRepositoryImpl, UserRepositoryImpl}
import pl.iterators.epoca.user.services.AvatarSyncService.AvatarSyncResult
import pl.iterators.epoca.utils.PgRepositorySpec
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import pl.iterators.epoca.utils.db.PostgresIOTransactor

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import scala.concurrent.Await
import scala.concurrent.duration._

class AvatarSyncPersistenceSpec extends PgRepositorySpec with UserFixture {
  private val users   = new UserRepositoryImpl
  private val sources = new UserAvatarSourceRepositoryImpl
  private lazy val tx = new PostgresIOTransactor(db)
  private val config  = AvatarSyncService.Config(true, 2097152, 5.seconds, List("googleusercontent.com"))
  private val image = {
    val out = new ByteArrayOutputStream()
    val _   = ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", out)
    out.toByteArray
  }
  private def run[A](action: DBIO[A]): A = Await.result(db.run(action), 15.seconds)
  private def withCommittedUser[A](test: User => A): A = {
    val user = run(withUser(allGenerators[User].normal.generate.copy(avatar = None, archivedAt = None)))
    try test(user)
    finally {
      val _ = run(sqlu"DELETE FROM user_avatar_source WHERE user_id=${user.id.toString}::uuid")
      val _ = run(sqlu"""DELETE FROM "user" WHERE id=${user.id.toString}::uuid""")
    }
  }
  private def service(storage: FileStorage, repository: UserAvatarSourceRepository = sources): AvatarSyncService =
    new AvatarSyncService(config, tx, users, repository, storage, null)

  "avatar transaction" should {
    "resolve its locked SQL before cancellation returns and never commit after the caller times out" in withCommittedUser { user =>
      val blocker = java.sql.DriverManager.getConnection(sys.env("TEST_DB_URL"), sys.env("TEST_DB_USER"), sys.env("TEST_DB_PASSWORD"))
      blocker.setAutoCommit(false)
      val statement = blocker.createStatement()
      val _         = statement.executeQuery(s"SELECT id FROM \"user\" WHERE id='${user.id}'::uuid FOR UPDATE")
      try {
        val worker = service(new InMemoryFileStorage("https://cdn.test"))
          .syncImage(user, "expired", image, "image/png", DBIO.successful(true), None)
          .start
          .unsafeRunSync()
        val waitUntil = System.nanoTime() + 5.seconds.toNanos
        var waiting   = false
        while (!waiting && System.nanoTime() < waitUntil) {
          val _ = statement.execute("SELECT pg_stat_clear_snapshot()")
          val active = statement.executeQuery(
            "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%user%')"
          )
          try { val _ = active.next(); waiting = active.getBoolean(1) }
          finally active.close()
          if (!waiting) Thread.sleep(10L)
        }
        val unlock    = (IO.sleep(2.seconds) >> IO.blocking(blocker.rollback())).start.unsafeRunSync()
        val startedAt = System.nanoTime()
        worker.cancel.unsafeRunSync()
        unlock.cancel.unsafeRunSync()
        val elapsed = (System.nanoTime() - startedAt).nanos
        blocker.rollback()
        val avatar = run(users.find(user.id)).flatMap(_.avatar)
        val source = run(sources.find(user.id))
        (waiting, avatar.isEmpty, source.isEmpty, elapsed < 5.seconds) mustEqual ((true, true, true, true))
      } finally {
        blocker.rollback()
        statement.close()
        blocker.close()
      }
    }

    "reject persistence when the propagated job deadline expires during storage" in withCommittedUser { user =>
      val memory = new InMemoryFileStorage("https://cdn.test")
      val slow = new FileStorage {
        override def publicUrl(key: String): String = memory.publicUrl(key)
        override def delete(key: String): IO[Unit]  = memory.delete(key)
        override def put(
          key: String,
          bytes: Array[Byte],
          contentType: String
        ): IO[Unit] =
          IO.sleep(100.millis) >> memory.put(key, bytes, contentType)
      }
      val result = service(slow)
        .syncImage(user, "expired-before-db", image, "image/png", DBIO.successful(true), None, Some(System.nanoTime() + 50.millis.toNanos))
        .unsafeRunSync()
      val avatar = run(users.find(user.id)).flatMap(_.avatar)
      val source = run(sources.find(user.id))
      (result.isInstanceOf[AvatarSyncResult.Failed], avatar.isEmpty, source.isEmpty) mustEqual ((true, true, true))
    }

    "discard an old job completing after another service committed a different revision" in withCommittedUser { user =>
      val result = (for {
        started <- Deferred[IO, Unit]
        release <- Deferred[IO, Unit]
        memory = new InMemoryFileStorage("https://cdn.test")
        slow = new FileStorage {
                 override def publicUrl(key: String): String = memory.publicUrl(key)
                 override def delete(key: String): IO[Unit]  = memory.delete(key)
                 override def put(
                   key: String,
                   bytes: Array[Byte],
                   contentType: String
                 ): IO[Unit] =
                   started.complete(()).void *> release.get *> memory.put(key, bytes, contentType)
               }
        older     <- service(slow).syncImage(user, "older", image, "image/png", DBIO.successful(true), None).start
        _         <- started.get
        newer     <- service(memory).syncImage(user, "newer", image, "image/png", DBIO.successful(true), None)
        _         <- release.complete(())
        oldResult <- older.joinWithNever
        stored    <- tx.execute(sources.find(user.id))
      } yield (newer, oldResult, stored)).timeout(10.seconds).unsafeRunSync()
      result._1 must beAnInstanceOf[AvatarSyncResult.Synced]
      result._2 mustEqual AvatarSyncResult.Rejected("avatar changed during synchronization")
      result._3.flatMap(_.sourceUrl) must beSome("newer")
    }

    "roll back the user change when provenance persistence fails" in withCommittedUser { user =>
      val failing = new UserAvatarSourceRepository {
        override def find(id: UserId): DBIO[Option[UserAvatarSource]] = sources.find(id)
        override def upsert(source: UserAvatarSource): DBIO[Unit]     = DBIO.failed(new IllegalStateException("synthetic write failure"))
      }
      service(new InMemoryFileStorage("https://cdn.test"), failing)
        .syncImage(user, "new", image, "image/png", DBIO.successful(true), None)
        .unsafeRunSync() must beAnInstanceOf[AvatarSyncResult.Failed]
      run(users.find(user.id)).flatMap(_.avatar) must beNone
      run(sources.find(user.id)) must beNone
    }

    "preserve a manual avatar changed after the snapshot was captured" in withCommittedUser { user =>
      val manual = UserAvatar("https://cdn.test/manual.png")
      run(users.update(user.copy(avatar = Some(manual))))
      service(new InMemoryFileStorage("https://cdn.test"))
        .syncImage(user, "older", image, "image/png", DBIO.successful(true), None)
        .unsafeRunSync() mustEqual AvatarSyncResult.ManualAvatar
      run(users.find(user.id)).flatMap(_.avatar) must beSome(manual)
      run(sources.find(user.id)) must beNone
    }
  }
}
