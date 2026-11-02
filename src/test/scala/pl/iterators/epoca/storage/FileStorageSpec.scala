package pl.iterators.epoca.storage

import cats.effect.unsafe.implicits.global
import com.typesafe.config.ConfigFactory
import org.specs2.mutable.Specification

class FileStorageSpec extends Specification {
  private val base = S3FileStorage.Config("bkt", "eu-north-1", None, pathStyle = false, None, None)

  "S3FileStorage.publicUrl" should {
    "use the CDN domain when configured" in {
      S3FileStorage.publicUrl(base.copy(cdnDomain = Some("cdn.test")), "a/b.png") shouldEqual "https://cdn.test/a/b.png"
    }
    "accept a CDN domain with a scheme or trailing slash, preserving http" in {
      S3FileStorage.publicUrl(base.copy(cdnDomain = Some("https://cdn.test/")), "a.png") shouldEqual "https://cdn.test/a.png"
      S3FileStorage.publicUrl(base.copy(cdnDomain = Some("http://cdn.test")), "a.png") shouldEqual "http://cdn.test/a.png"
      S3FileStorage.publicUrl(base.copy(cdnDomain = Some("cdn.test/")), "a.png") shouldEqual "https://cdn.test/a.png"
    }
    "keep a path in the CDN domain (e.g. a reverse proxy serving the bucket under /assets)" in {
      S3FileStorage.publicUrl(base.copy(cdnDomain = Some("epoca-staging.test/assets")), "avatars/u/h.png") shouldEqual
        "https://epoca-staging.test/assets/avatars/u/h.png"
      S3FileStorage.publicUrl(base.copy(cdnDomain = Some("https://epoca-staging.test/assets/")), "a.png") shouldEqual
        "https://epoca-staging.test/assets/a.png"
    }
    "use the path-style AWS URL for dotted bucket names" in {
      S3FileStorage.publicUrl(base.copy(bucket = "my.bucket"), "a.png") shouldEqual "https://s3.eu-north-1.amazonaws.com/my.bucket/a.png"
    }
    "use path style for dotted bucket names on a custom endpoint" in {
      S3FileStorage.publicUrl(base.copy(bucket = "my.bucket", endpoint = Some("https://silo.test")), "a.png") shouldEqual
        "https://silo.test/my.bucket/a.png"
    }
    "use the AWS virtual-hosted URL without an endpoint" in {
      S3FileStorage.publicUrl(base, "a/b.png") shouldEqual "https://bkt.s3.eu-north-1.amazonaws.com/a/b.png"
    }
    "use path style URLs for a custom endpoint" in {
      S3FileStorage.publicUrl(
        base.copy(endpoint = Some("https://silo.test/"), pathStyle = true),
        "a/b.png"
      ) shouldEqual "https://silo.test/bkt/a/b.png"
    }
    "use virtual-hosted URLs for a custom endpoint without path style" in {
      S3FileStorage.publicUrl(base.copy(endpoint = Some("https://silo.test")), "a/b.png") shouldEqual "https://bkt.silo.test/a/b.png"
    }
  }

  "InMemoryFileStorage.delete" should {
    "remove an existing key and leave the other keys untouched" in {
      val storage = new InMemoryFileStorage("http://x")
      val result = for {
        _ <- storage.put("a.png", Array[Byte](1), "image/png")
        _ <- storage.put("b.png", Array[Byte](2), "image/png")
        _ <- storage.delete("a.png")
      } yield ()
      result.unsafeRunSync()
      storage.keys shouldEqual Set("b.png")
      storage.get("a.png") must beNone
    }
    "succeed when the key does not exist" in {
      val storage = new InMemoryFileStorage("http://x")
      storage.delete("missing.png").unsafeRunSync() must not(throwA[Throwable])
      storage.keys must beEmpty
    }
    "allow putting the key again after it was deleted" in {
      val storage = new InMemoryFileStorage("http://x")
      val result = for {
        _ <- storage.put("a.png", Array[Byte](1), "image/png")
        _ <- storage.delete("a.png")
        _ <- storage.put("a.png", Array[Byte](3), "image/jpeg")
      } yield ()
      result.unsafeRunSync()
      storage.get("a.png").map { case (bytes, contentType) => (bytes.toSeq, contentType) } must beSome((Seq[Byte](3), "image/jpeg"))
    }
  }

  "FileStorageFactory" should {
    "create the in-memory storage by default" in {
      val config = ConfigFactory.parseString("assets { storage = memory, memory.publicBaseUrl = \"http://x/assets/\" }")
      FileStorageFactory(config).publicUrl("k.png") shouldEqual "http://x/assets/k.png"
    }
    "fail fast when avatar sync is enabled with the in-memory storage" in {
      val config = ConfigFactory.parseString("assets { storage = memory, memory.publicBaseUrl = \"http://x\" }, user.avatarSync.enabled = true")
      FileStorageFactory(config) must throwAn[IllegalArgumentException]
    }
    "allow the in-memory storage when avatar sync is disabled" in {
      val config = ConfigFactory.parseString("assets { storage = memory, memory.publicBaseUrl = \"http://x\" }, user.avatarSync.enabled = false")
      FileStorageFactory(config) must not(throwA[Throwable])
    }
    "fail fast on an S3 configuration without a bucket" in {
      val config = ConfigFactory.parseString("assets { storage = s3, s3 { bucket = \"\", region = r, pathStyle = false } }")
      FileStorageFactory(config) must throwAn[IllegalArgumentException]
    }
    "fail on an unknown storage" in {
      FileStorageFactory(ConfigFactory.parseString("assets.storage = foo")) must throwAn[IllegalArgumentException]
    }
  }
}
