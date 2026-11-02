package pl.iterators.epoca.user.services

import cats.effect.IO
import cats.syntax.all._
import org.mockito.stubbing.ScalaOngoingStubbing
import org.specs2.matcher.MatchResult
import pl.iterators.epoca.storage.InMemoryFileStorage
import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.user.domain.UserAvatarSource.Source
import pl.iterators.epoca.user.domain.UserDomain.UserAvatar
import pl.iterators.epoca.user.domain.{User, UserAvatarSource}
import pl.iterators.epoca.user.repositories.UserRepository.UserFilters
import pl.iterators.epoca.user.repositories.{UserAvatarSourceRepository, UserRepository}
import pl.iterators.epoca.user.services.AvatarSyncService.AvatarSyncResult
import pl.iterators.epoca.utils.ServiceSpec
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.{Response, WebSocketStreamBackend}
import sttp.model.{Header, StatusCode}

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.security.MessageDigest
import java.time.Instant
import javax.imageio.ImageIO
import scala.concurrent.duration._

class AvatarSyncServiceSpec extends ServiceSpec {
  val userGenerator: AllGenerators[User] = allGenerators[User]

  private val pictureUrl                                 = "https://lh3.googleusercontent.com/a/abc=s96-c"
  private val maxBytes                                   = 100000
  private def withMagic(magic: Array[Byte]): Array[Byte] = magic ++ Array.tabulate[Byte](100)(_.toByte)
  private val png                                        = withMagic(Array(0x89, 0x50, 0x4e, 0x47).map(_.toByte))

  // real images: flat colours keep them tiny; a red band on both sides of a blue centre shows what the centre crop keeps
  private def image(
    width: Int,
    height: Int,
    alpha: Boolean = false
  ): BufferedImage = {
    val img = new BufferedImage(width, height, if (alpha) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
    val g   = img.createGraphics()
    g.setColor(Color.RED)
    g.fillRect(0, 0, width, height)
    g.setColor(if (alpha) new Color(0, 0, 255, 128) else Color.BLUE)
    if (alpha) g.setComposite(java.awt.AlphaComposite.Src)
    val side = math.min(width, height)
    g.fillRect((width - side) / 2, (height - side) / 2, side, side)
    g.dispose()
    img
  }
  private def encode(img: BufferedImage, format: String): Array[Byte] = {
    val out = new ByteArrayOutputStream()
    ImageIO.write(img, format, out)
    out.toByteArray
  }
  private def decoded(bytes: Array[Byte]): BufferedImage = ImageIO.read(new ByteArrayInputStream(bytes))

  private val largeJpeg  = encode(image(1200, 800), "jpg")
  private val largePng   = encode(image(1200, 800, alpha = true), "png")
  private val smallPng   = encode(image(100, 60), "png")
  private val smallGif   = encode(image(40, 40), "gif")
  private val hugePng    = encode(image(9000, 10), "png")
  private val undecoding = png // valid magic bytes, but not a decodable image

  private def sha256(bytes: Array[Byte]): String = MessageDigest.getInstance("SHA-256").digest(bytes).map("%02x".format(_)).mkString

  trait TestCase extends BaseScope {
    val config: AvatarSyncService.Config = AvatarSyncService.Config(enabled = true, maxBytes, 5.seconds, List("googleusercontent.com"))
    val userRepository: UserRepository   = vmock[UserRepository]
    val userAvatarSourceRepository: UserAvatarSourceRepository = vmock[UserAvatarSourceRepository]
    val httpClient: WebSocketStreamBackend[IO, Fs2Streams[IO]] = vmock[WebSocketStreamBackend[IO, Fs2Streams[IO]]]
    val fileStorage: InMemoryFileStorage                       = new InMemoryFileStorage("https://cdn.test")

    lazy val avatarSyncService: AvatarSyncService = wire[AvatarSyncService]

    val userWithoutAvatar: User = userGenerator.maximal.generate.copy(avatar = None)

    def respondWith(
      body: Either[String, Array[Byte]],
      contentType: Option[String],
      code: StatusCode = StatusCode.Ok,
      extra: Seq[Header] = Nil
    ): ScalaOngoingStubbing[IO[Response[Either[String, Array[Byte]]]]] =
      when(httpClient.send[Either[String, Array[Byte]]](any))
        .thenReturn(Response(body, code, "", contentType.map(Header("Content-Type", _)).toList ++ extra).asIO)

    def verifyDownloaded(): IO[Response[Either[String, Array[Byte]]]] = verify(httpClient).send[Either[String, Array[Byte]]](any)

    def expectNothingStored(): MatchResult[Set[String]] = fileStorage.keys must beEmpty

    def stubUpdate(user: User): ScalaOngoingStubbing[DBIO[Unit]] = {
      when(userRepository.find(UserFilters(id = Some(user.id), forUpdate = true))).thenReturn(Some(user).asDBIO)
      when(userRepository.update(any)).thenReturn(Some(user).asDBIO)
      when(userAvatarSourceRepository.upsert(any)).thenReturn(().asDBIO)
    }

    def verifyUpdated(user: User): DBIO[Unit] = {
      verify(userAvatarSourceRepository, times(2)).find(user.id)
      verify(userRepository).find(UserFilters(id = Some(user.id), forUpdate = true))
      verify(userRepository).update(any)
      verify(userAvatarSourceRepository).upsert(any)
    }

    def noSourceRow(user: User): ScalaOngoingStubbing[DBIO[Option[UserAvatarSource]]] =
      when(userAvatarSourceRepository.find(user.id)).thenReturn(None.asDBIO)
    def sourceRow(user: User, source: UserAvatarSource): ScalaOngoingStubbing[DBIO[Option[UserAvatarSource]]] =
      when(userAvatarSourceRepository.find(user.id)).thenReturn(Some(source).asDBIO)
  }

  "background scheduling" should {
    "retain a bounded cooldown and release admission when constructing the job fails" in new TestCase {
      val calls = new java.util.concurrent.atomic.AtomicInteger()
      val service = new AvatarSyncService(config, transactor, userRepository, userAvatarSourceRepository, fileStorage, httpClient) {
        override def sync(user: User, pictureUrl: String): IO[AvatarSyncResult] = {
          val _ = calls.incrementAndGet()
          throw new IllegalStateException("repository unavailable")
        }
      }
      val users = List.fill(6)(userGenerator.maximal.generate.copy(avatar = None))
      cats.effect.testkit.TestControl
        .executeEmbed {
          users.traverse_(u => service.schedule(u, pictureUrl) >> IO.sleep(1.second)) >>
            service.schedule(users.head, pictureUrl) >> IO.sleep(1.second) >> IO(calls.get())
        }
        .asserting(_ mustEqual 6)
    }
  }

  "Directory image synchronization" should {
    "validate and store bytes without making another HTTP request" in new TestCase {
      noSourceRow(userWithoutAvatar)
      stubUpdate(userWithoutAvatar)
      avatarSyncService
        .syncImage(userWithoutAvatar, "workspace:revision", smallPng, "image/png", true.asDBIO, None)
        .asserting(_ must beAnInstanceOf[AvatarSyncResult.Synced])
      verify(userAvatarSourceRepository).find(userWithoutAvatar.id)
      verify(userRepository).find(UserFilters(id = Some(userWithoutAvatar.id), forUpdate = true))
      verify(userRepository).update(any)
      verify(userAvatarSourceRepository).upsert(any)
      fileStorage.keys.size mustEqual 2
    }
    "discard a photo after its SAML authorization is revoked" in new TestCase {
      noSourceRow(userWithoutAvatar)
      stubUpdate(userWithoutAvatar)
      avatarSyncService
        .syncImage(userWithoutAvatar, "workspace:revision", smallPng, "image/png", false.asDBIO, None)
        .asserting(_ mustEqual AvatarSyncResult.Rejected("identity is no longer authorized"))
      verify(userAvatarSourceRepository).find(userWithoutAvatar.id)
      verify(userRepository).find(UserFilters(id = Some(userWithoutAvatar.id), forUpdate = true))
    }
    "reject mismatched content and oversized decoded photo bytes before storage" in new TestCase {
      avatarSyncService
        .syncImage(userWithoutAvatar, "workspace:revision", smallPng, "image/jpeg", true.asDBIO, None)
        .asserting(_ must beAnInstanceOf[AvatarSyncResult.Rejected])
      avatarSyncService
        .syncImage(userWithoutAvatar, "workspace:revision", new Array[Byte](maxBytes + 1), "image/png", true.asDBIO, None)
        .asserting(_ mustEqual AvatarSyncResult.Rejected("image too large"))
      expectNothingStored()
    }
    "compare the snapshot taken before the Directory fetch with the final provenance" in new TestCase {
      val avatar  = UserAvatar("https://cdn.test/new.png")
      val current = userWithoutAvatar.copy(avatar = Some(avatar))
      sourceRow(current, UserAvatarSource(current.id, Source.Google, Some("workspace:newer"), avatar, Instant.now()))
      when(userRepository.find(UserFilters(id = Some(current.id), forUpdate = true))).thenReturn(Some(current).asDBIO)
      avatarSyncService
        .syncImage(userWithoutAvatar, "workspace:older", smallPng, "image/png", true.asDBIO, None)
        .asserting(_ mustEqual AvatarSyncResult.Rejected("avatar changed during synchronization"))
      verify(userAvatarSourceRepository).find(current.id)
      verify(userRepository).find(UserFilters(id = Some(current.id), forUpdate = true))
    }
  }

  "sync" should {
    "reject URLs outside of the host allowlist without any request" in new TestCase {
      val urls = List(
        "https://evil.example.com/a.png",
        "https://googleusercontent.com.evil.com/a.png",
        "https://evilgoogleusercontent.com/a.png",
        "http://lh3.googleusercontent.com/a.png",
        "https://user-154a1c99ea38@example.test/a.png",
        "https://lh3.googleusercontent.com:8443/a.png",
        "not a url"
      )
      urls.traverse(avatarSyncService.sync(userWithoutAvatar, _)).asserting(_ must contain(beAnInstanceOf[AvatarSyncResult.Rejected]).forall)
      verifyNoMoreInteractions(httpClient)
      expectNothingStored()
    }

    "accept a mixed-case host (it is normalized before the allowlist check)" in new TestCase {
      noSourceRow(userWithoutAvatar)
      respondWith(Right(png), Some("text/html"))

      avatarSyncService
        .sync(userWithoutAvatar, "https://LH3.GoogleUserContent.COM/a/abc")
        .asserting(_ shouldEqual AvatarSyncResult.Rejected("unsupported content type 'text/html'")) // got past host validation

      verify(userAvatarSourceRepository).find(userWithoutAvatar.id)
      verifyDownloaded()
    }

    "do nothing when disabled" in new TestCase {
      override val config: AvatarSyncService.Config = AvatarSyncService.Config(enabled = false, maxBytes, 5.seconds, List("googleusercontent.com"))
      avatarSyncService.sync(userWithoutAvatar, pictureUrl).asserting(_ shouldEqual AvatarSyncResult.Disabled)
    }

    "reject non-image content types" in new TestCase {
      noSourceRow(userWithoutAvatar)
      respondWith(Right(png), Some("text/html"))

      avatarSyncService.sync(userWithoutAvatar, pictureUrl).asserting(_ must beAnInstanceOf[AvatarSyncResult.Rejected])

      verify(userAvatarSourceRepository).find(userWithoutAvatar.id)
      verifyDownloaded()
      expectNothingStored()
    }

    "reject SVG images" in new TestCase {
      noSourceRow(userWithoutAvatar)
      respondWith(Right(png), Some("image/svg+xml"))

      avatarSyncService.sync(userWithoutAvatar, pictureUrl).asserting(_ must beAnInstanceOf[AvatarSyncResult.Rejected])

      verify(userAvatarSourceRepository).find(userWithoutAvatar.id)
      verifyDownloaded()
      expectNothingStored()
    }

    "reject WebP images (the JDK cannot decode them, so no web version could be made)" in new TestCase {
      val webp = "RIFF".getBytes("US-ASCII") ++ Array[Byte](1, 2, 3, 4) ++ "WEBP".getBytes("US-ASCII") ++ Array.fill[Byte](20)(0)
      noSourceRow(userWithoutAvatar)
      respondWith(Right(webp), Some("image/webp"))

      avatarSyncService.sync(userWithoutAvatar, pictureUrl).asserting(_ must beAnInstanceOf[AvatarSyncResult.Rejected])

      verify(userAvatarSourceRepository).find(userWithoutAvatar.id)
      verifyDownloaded()
      expectNothingStored()
    }

    "reject a response without content type" in new TestCase {
      noSourceRow(userWithoutAvatar)
      respondWith(Right(png), None)

      avatarSyncService.sync(userWithoutAvatar, pictureUrl).asserting(_ must beAnInstanceOf[AvatarSyncResult.Rejected])

      verify(userAvatarSourceRepository).find(userWithoutAvatar.id)
      verifyDownloaded()
    }

    "reject images larger than the limit" in new TestCase {
      noSourceRow(userWithoutAvatar)
      respondWith(Right(new Array[Byte](maxBytes + 1)), Some("image/png"))

      avatarSyncService.sync(userWithoutAvatar, pictureUrl).asserting(_ shouldEqual AvatarSyncResult.Rejected("image too large"))

      verify(userAvatarSourceRepository).find(userWithoutAvatar.id)
      verifyDownloaded()
      expectNothingStored()
    }

    "reject images whose declared content length exceeds the limit" in new TestCase {
      noSourceRow(userWithoutAvatar)
      respondWith(Right(png), Some("image/png"), extra = Seq(Header.contentLength((maxBytes + 1).toLong)))

      avatarSyncService.sync(userWithoutAvatar, pictureUrl).asserting(_ shouldEqual AvatarSyncResult.Rejected("image too large"))

      verify(userAvatarSourceRepository).find(userWithoutAvatar.id)
      verifyDownloaded()
    }

    "reject a body larger than the limit without Content-Length" in new TestCase {
      noSourceRow(userWithoutAvatar)
      respondWith(Right(png ++ new Array[Byte](maxBytes)), Some("image/png"))

      avatarSyncService.sync(userWithoutAvatar, pictureUrl).asserting(_ shouldEqual AvatarSyncResult.Rejected("image too large"))

      verify(userAvatarSourceRepository).find(userWithoutAvatar.id)
      verifyDownloaded()
      expectNothingStored()
    }

    "not follow redirects" in new TestCase {
      noSourceRow(userWithoutAvatar)
      respondWith(Left(""), None, StatusCode.Found, Seq(Header.location("https://evil.example.com/a.png")))

      avatarSyncService.sync(userWithoutAvatar, pictureUrl).asserting(_ shouldEqual AvatarSyncResult.Failed("download returned HTTP 302"))

      verify(userAvatarSourceRepository).find(userWithoutAvatar.id)
      verifyDownloaded() // exactly one request, the redirect target is never requested
      expectNothingStored()
    }

    "reject content whose magic bytes do not match any supported image" in new TestCase {
      noSourceRow(userWithoutAvatar)
      respondWith(Right("<html>not an image</html>".getBytes("UTF-8")), Some("image/png"))

      avatarSyncService
        .sync(userWithoutAvatar, pictureUrl)
        .asserting(_ shouldEqual AvatarSyncResult.Rejected("content is not a supported image"))

      verify(userAvatarSourceRepository).find(userWithoutAvatar.id)
      verifyDownloaded()
      expectNothingStored()
    }

    "reject content whose magic bytes contradict the declared content type" in new TestCase {
      noSourceRow(userWithoutAvatar)
      respondWith(Right(png), Some("image/jpeg"))

      avatarSyncService.sync(userWithoutAvatar, pictureUrl).asserting(_ must beAnInstanceOf[AvatarSyncResult.Rejected])

      verify(userAvatarSourceRepository).find(userWithoutAvatar.id)
      verifyDownloaded()
      expectNothingStored()
    }

    "derive the extension from the sniffed bytes for every supported type" in new TestCase {
      noSourceRow(userWithoutAvatar)
      stubUpdate(userWithoutAvatar)

      val cases = List((largeJpeg, "image/jpeg", "jpg"), (smallPng, "image/png", "png"), (smallGif, "image/gif", "gif"))
      val expected = cases.map { case (bytes, _, ext) =>
        AvatarSyncResult.Synced(s"https://cdn.test/avatars/${userWithoutAvatar.id}/${sha256(bytes)}-512.png") -> ext
      }
      cases
        .traverse { case (bytes, contentType, _) =>
          cats.effect.IO.delay(respondWith(Right(bytes), Some(contentType))) *> avatarSyncService.sync(userWithoutAvatar, pictureUrl)
        }
        .asserting(_ shouldEqual expected.map(_._1))

      cases.map { case (bytes, _, ext) => fileStorage.get(s"avatars/${userWithoutAvatar.id}/${sha256(bytes)}.$ext").map(_._1.toSeq) } must
        beEqualTo(cases.map { case (bytes, _, _) => Some(bytes.toSeq) })
      verify(userAvatarSourceRepository, times(2 * cases.size)).find(userWithoutAvatar.id)
      verify(userAvatarSourceRepository, times(cases.size)).upsert(any)
      verify(userRepository, times(cases.size)).find(UserFilters(id = Some(userWithoutAvatar.id), forUpdate = true))
      verify(userRepository, times(cases.size)).update(any)
      verify(httpClient, times(cases.size)).send[Either[String, Array[Byte]]](any)
    }

    "report the loser of a concurrent sync as Unchanged instead of Synced" in new TestCase {
      val avatar = UserAvatar("https://cdn.test/avatars/winner.png")
      val winner = userWithoutAvatar.copy(avatar = Some(avatar))
      // first read (before download): nothing synced yet; second read (under lock): a concurrent sync stored this very picture
      val synced: Option[UserAvatarSource] =
        Some(UserAvatarSource(userWithoutAvatar.id, Source.Google, Some(pictureUrl + "#sha256=" + sha256(smallPng)), avatar, Instant.now()))
      when(userAvatarSourceRepository.find(userWithoutAvatar.id)).thenReturn(Option.empty[UserAvatarSource].asDBIO, synced.asDBIO)
      respondWith(Right(smallPng), Some("image/png"))
      when(userRepository.find(UserFilters(id = Some(userWithoutAvatar.id), forUpdate = true))).thenReturn(Some(winner).asDBIO)

      avatarSyncService.sync(userWithoutAvatar, pictureUrl).asserting(_ shouldEqual AvatarSyncResult.Unchanged)

      verify(userAvatarSourceRepository, times(2)).find(userWithoutAvatar.id)
      verifyDownloaded()
      verify(userRepository).find(UserFilters(id = Some(userWithoutAvatar.id), forUpdate = true))
    }

    "discard an older picture after a different concurrent sync committed" in new TestCase {
      val avatar = UserAvatar("https://cdn.test/avatars/newer.png")
      val winner = userWithoutAvatar.copy(avatar = Some(avatar))
      val synced = Some(UserAvatarSource(userWithoutAvatar.id, Source.Google, Some("https://lh3.googleusercontent.com/newer"), avatar, Instant.now()))
      when(userAvatarSourceRepository.find(userWithoutAvatar.id)).thenReturn(Option.empty[UserAvatarSource].asDBIO, synced.asDBIO)
      respondWith(Right(smallPng), Some("image/png"))
      when(userRepository.find(UserFilters(id = Some(userWithoutAvatar.id), forUpdate = true))).thenReturn(Some(winner).asDBIO)
      when(userRepository.update(any)).thenReturn(Some(winner).asDBIO)
      when(userAvatarSourceRepository.upsert(any)).thenReturn(().asDBIO)

      avatarSyncService.sync(userWithoutAvatar, pictureUrl).asserting(_ must beAnInstanceOf[AvatarSyncResult.Rejected])
      verify(userRepository, never).update(any)
      verify(userAvatarSourceRepository, never).upsert(any)
      verify(userAvatarSourceRepository, times(2)).find(userWithoutAvatar.id)
      verify(userRepository).find(UserFilters(id = Some(userWithoutAvatar.id), forUpdate = true))
      verifyDownloaded()
    }

    "return Failed instead of throwing when the download fails" in new TestCase {
      noSourceRow(userWithoutAvatar)
      when(httpClient.send[Either[String, Array[Byte]]](any)).thenReturn(cats.effect.IO.raiseError(new RuntimeException("boom")))

      avatarSyncService.sync(userWithoutAvatar, pictureUrl).asserting(_ shouldEqual AvatarSyncResult.Failed("image synchronization failed"))

      verify(userAvatarSourceRepository).find(userWithoutAvatar.id)
      verifyDownloaded()
    }

    "return Failed for a non-200 response" in new TestCase {
      noSourceRow(userWithoutAvatar)
      respondWith(Left("error"), Some("text/plain"), StatusCode.InternalServerError)

      avatarSyncService.sync(userWithoutAvatar, pictureUrl).asserting(_ must beAnInstanceOf[AvatarSyncResult.Failed])

      verify(userAvatarSourceRepository).find(userWithoutAvatar.id)
      verifyDownloaded()
    }

    "store the original and a 512px PNG web version and point the avatar at the web version" in new TestCase {
      val hash        = sha256(largeJpeg)
      val originalKey = s"avatars/${userWithoutAvatar.id}/$hash.jpg"
      val webKey      = s"avatars/${userWithoutAvatar.id}/$hash-512.png"
      val url         = s"https://cdn.test/$webKey"

      noSourceRow(userWithoutAvatar)
      respondWith(Right(largeJpeg), Some("image/jpeg; charset=binary"))
      stubUpdate(userWithoutAvatar)

      avatarSyncService.sync(userWithoutAvatar, pictureUrl).asserting(_ shouldEqual AvatarSyncResult.Synced(url))

      fileStorage.keys shouldEqual Set(originalKey, webKey)
      fileStorage.get(originalKey).map(c => (c._1.toSeq, c._2)) must beSome((largeJpeg.toSeq, "image/jpeg"))
      fileStorage.get(webKey).map(_._2) must beSome("image/png")
      val web = decoded(fileStorage.get(webKey).get._1)
      (web.getWidth, web.getHeight, web.getColorModel.hasAlpha) shouldEqual ((512, 512, false))
      verify(userAvatarSourceRepository, times(2)).find(userWithoutAvatar.id)
      verifyDownloaded()
      verify(userRepository).find(UserFilters(id = Some(userWithoutAvatar.id), forUpdate = true))
      verify(userRepository).update(argThat((u: User) => u.avatar.contains(UserAvatar(url)) && u.id == userWithoutAvatar.id))
      verify(userAvatarSourceRepository).upsert(
        argThat((s: UserAvatarSource) =>
          s.source == Source.Google && s.sourceUrl.contains(pictureUrl + "#sha256=" + sha256(largeJpeg)) && s.avatarUrl == url
        )
      )
    }

    "keep the alpha channel of a transparent source" in new TestCase {
      val webKey = s"avatars/${userWithoutAvatar.id}/${sha256(largePng)}-512.png"
      noSourceRow(userWithoutAvatar)
      respondWith(Right(largePng), Some("image/png"))
      stubUpdate(userWithoutAvatar)

      avatarSyncService.sync(userWithoutAvatar, pictureUrl).asserting(_ shouldEqual AvatarSyncResult.Synced(s"https://cdn.test/$webKey"))

      fileStorage.keys shouldEqual Set(s"avatars/${userWithoutAvatar.id}/${sha256(largePng)}.png", webKey)
      fileStorage.get(s"avatars/${userWithoutAvatar.id}/${sha256(largePng)}.png").map(_._1.toSeq) must beSome(largePng.toSeq)
      val web = decoded(fileStorage.get(webKey).get._1)
      (web.getWidth, web.getHeight, web.getColorModel.hasAlpha) shouldEqual ((512, 512, true))
      (web.getRGB(256, 256) >>> 24) must beBetween(120, 136)
      verifyDownloaded()
      verifyUpdated(userWithoutAvatar)
    }

    "centre-crop a small image to a square without upscaling it" in new TestCase {
      val webKey = s"avatars/${userWithoutAvatar.id}/${sha256(smallPng)}-512.png"
      noSourceRow(userWithoutAvatar)
      respondWith(Right(smallPng), Some("image/png"))
      stubUpdate(userWithoutAvatar)

      avatarSyncService.sync(userWithoutAvatar, pictureUrl).asserting(_ shouldEqual AvatarSyncResult.Synced(s"https://cdn.test/$webKey"))

      val web = decoded(fileStorage.get(webKey).get._1)
      (web.getWidth, web.getHeight) shouldEqual ((60, 60))
      (web.getRGB(0, 0) & 0xffffff, web.getRGB(59, 59) & 0xffffff) shouldEqual ((0x0000ff, 0x0000ff)) // the red side bands are cropped away
      verifyDownloaded()
      verifyUpdated(userWithoutAvatar)
    }

    "reject images with oversized dimensions and store nothing" in new TestCase {
      noSourceRow(userWithoutAvatar)
      respondWith(Right(hugePng), Some("image/png"))

      avatarSyncService.sync(userWithoutAvatar, pictureUrl).asserting(_ shouldEqual AvatarSyncResult.Rejected("image dimensions too large"))

      verify(userAvatarSourceRepository).find(userWithoutAvatar.id)
      verifyDownloaded()
      expectNothingStored()
    }

    "reject undecodable bytes with a valid magic header and store nothing" in new TestCase {
      noSourceRow(userWithoutAvatar)
      respondWith(Right(undecoding), Some("image/png"))

      avatarSyncService.sync(userWithoutAvatar, pictureUrl).asserting(_ shouldEqual AvatarSyncResult.Rejected("cannot decode image"))

      verify(userAvatarSourceRepository).find(userWithoutAvatar.id)
      verifyDownloaded()
      expectNothingStored()
    }

    "refresh bytes even when Google keeps the same picture URL" in new TestCase {
      val avatar = UserAvatar("https://cdn.test/avatars/x/y.png")
      val user   = userWithoutAvatar.copy(avatar = Some(avatar))
      sourceRow(user, UserAvatarSource(user.id, Source.Google, Some(pictureUrl), avatar, Instant.now()))
      respondWith(Right(largeJpeg), Some("image/jpeg"))
      stubUpdate(user)
      avatarSyncService.sync(user, pictureUrl).asserting(_ must beAnInstanceOf[AvatarSyncResult.Synced])
      verifyUpdated(user)
      verifyDownloaded()
    }

    "download but avoid storage when the picture bytes are unchanged" in new TestCase {
      val avatar = UserAvatar("https://cdn.test/existing.png")
      val user   = userWithoutAvatar.copy(avatar = Some(avatar))
      sourceRow(user, UserAvatarSource(user.id, Source.Google, Some(pictureUrl + "#sha256=" + sha256(smallPng)), avatar, Instant.now()))
      respondWith(Right(smallPng), Some("image/png"))
      avatarSyncService.sync(user, pictureUrl).asserting(_ mustEqual AvatarSyncResult.Unchanged)
      verify(userAvatarSourceRepository).find(user.id)
      verifyDownloaded()
      expectNothingStored()
    }

    "download again when the Google picture URL changed" in new TestCase {
      val avatar = UserAvatar("https://cdn.test/avatars/x/y.png")
      val user   = userWithoutAvatar.copy(avatar = Some(avatar))
      val source = UserAvatarSource(user.id, Source.Google, Some("https://lh3.googleusercontent.com/a/old"), avatar, Instant.now())
      sourceRow(user, source)
      respondWith(Right(largeJpeg), Some("image/jpeg"))
      stubUpdate(user)

      avatarSyncService
        .sync(user, pictureUrl)
        .asserting(_ shouldEqual AvatarSyncResult.Synced(s"https://cdn.test/avatars/${user.id}/${sha256(largeJpeg)}-512.png"))

      verify(userAvatarSourceRepository, times(2)).find(user.id)
      verifyDownloaded()
      verify(userRepository).find(UserFilters(id = Some(user.id), forUpdate = true))
      verify(userRepository).update(any)
      verify(userAvatarSourceRepository).upsert(any)
    }

    "not overwrite an avatar of unknown origin (no source row)" in new TestCase {
      val user = userWithoutAvatar.copy(avatar = Some(UserAvatar("https://elsewhere.test/me.png")))
      noSourceRow(user)

      avatarSyncService.sync(user, pictureUrl).asserting(_ shouldEqual AvatarSyncResult.ManualAvatar)

      verify(userAvatarSourceRepository).find(user.id)
      expectNothingStored()
    }

    "not overwrite an avatar marked as manual" in new TestCase {
      val avatar = UserAvatar("https://cdn.test/avatars/x/y.png")
      val user   = userWithoutAvatar.copy(avatar = Some(avatar))
      sourceRow(user, UserAvatarSource(user.id, Source.Manual, None, avatar, Instant.now()))

      avatarSyncService.sync(user, pictureUrl).asserting(_ shouldEqual AvatarSyncResult.ManualAvatar)

      verify(userAvatarSourceRepository).find(user.id)
      expectNothingStored()
    }

    "not overwrite an avatar the user changed after the last sync" in new TestCase {
      val user = userWithoutAvatar.copy(avatar = Some(UserAvatar("https://elsewhere.test/me.png")))
      sourceRow(
        user,
        UserAvatarSource(
          user.id,
          Source.Google,
          Some("https://lh3.googleusercontent.com/a/old"),
          UserAvatar("https://cdn.test/old.png"),
          Instant.now()
        )
      )

      avatarSyncService.sync(user, pictureUrl).asserting(_ shouldEqual AvatarSyncResult.ManualAvatar)

      verify(userAvatarSourceRepository).find(user.id)
      expectNothingStored()
    }
  }
}
