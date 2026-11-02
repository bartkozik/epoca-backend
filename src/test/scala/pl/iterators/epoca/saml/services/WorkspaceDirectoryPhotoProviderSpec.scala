package pl.iterators.epoca.saml.services

import cats.effect.IO
import com.typesafe.config.ConfigFactory
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId
import pl.iterators.epoca.saml.services.WorkspaceDirectoryPhotoProvider._
import pl.iterators.epoca.utils.ServiceSpec

import java.nio.charset.StandardCharsets.UTF_8
import java.util.{Base64, UUID}

class WorkspaceDirectoryPhotoProviderSpec extends ServiceSpec {
  trait TestCase extends BaseScope {
    val organizationId: OrganizationId = OrganizationId(UUID.randomUUID())
    val proof: WorkspacePhotoProof = WorkspacePhotoProof(organizationId, 3L, "https://accounts.google.com/issuer", "subject", "member@example.com")
    val tenant: Tenant             = Tenant(organizationId, proof.issuer, Set("example.com"), "/private/key.json", "admin@example.com")
    var calls                      = 0
    var response: Response         = Response(200, """{"mimeType":"image/png","photoData":"AQID"}""".getBytes(UTF_8))
    val transport: Transport = new Transport {
      override def fetch(config: Tenant, email: String): IO[Response] = IO {
        calls += 1
        config mustEqual tenant
        email mustEqual proof.canonicalEmail
        response
      }
    }
    def provider(enabled: Boolean = true) = new WorkspaceDirectoryPhotoProvider(Config(enabled, Seq(tenant), 1024), transport)
  }
  "Directory photo lookup" should {
    "perform no IO when disabled, unmatched, or outside the exact allowed domain" in new TestCase {
      provider(false).fetch(proof).asserting(_ must beNone)
      provider().fetch(proof.copy(organizationId = OrganizationId(UUID.randomUUID()))).asserting(_ must beNone)
      provider().fetch(proof.copy(issuer = "https://evil.test")).asserting(_ must beNone)
      provider().fetch(proof.copy(canonicalEmail = "user-5134880f16b1@example.test")).asserting(_ must beNone)
      provider().fetch(proof.copy(canonicalEmail = "Member@example.com")).asserting(_ must beNone)
      provider().fetch(proof.copy(subject = "")).asserting(_ must beNone)
      provider().fetch(proof.copy(trustEpoch = 0L)).asserting(_ must beNone)
      calls mustEqual 0
    }
    "decode a Google URL-safe photo payload and derive stable content revision" in new TestCase {
      val bytes = Array[Byte](-1, -2, 3)
      response = Response(200, s"""{"mimeType":"image/png","photoData":"${Base64.getUrlEncoder.encodeToString(bytes)}"}""".getBytes(UTF_8))
      provider().fetch(proof).asserting { result =>
        result must beSome.which(photo => photo.bytes.toSeq == bytes.toSeq && photo.contentType == "image/png" && photo.revision.nonEmpty)
      }
      calls mustEqual 1
    }
    "accept Google documented asterisk and period padding" in new TestCase {
      Seq("AQ**", "AQ..", "AQ==").foreach { encoded =>
        response = Response(200, s"""{"mimeType":"image/png","photoData":"$encoded"}""".getBytes(UTF_8))
        provider().fetch(proof).asserting(_ must beSome.which(_.bytes.toSeq == Seq[Byte](1)))
      }
      calls mustEqual 3
    }
    "normalize Google documented MIME names to image content types" in new TestCase {
      Seq("JPEG" -> "image/jpeg", "jpg" -> "image/jpeg", "PNG" -> "image/png", "GIF" -> "image/gif").foreach { case (mime, expected) =>
        response = Response(200, s"""{"mimeType":"$mime","photoData":"AQID"}""".getBytes(UTF_8))
        provider().fetch(proof).asserting(_ must beSome.which(_.contentType == expected))
      }
      calls mustEqual 4
    }
    "ignore no-photo, forbidden, redirects, malformed base64, missing mime, and oversized responses" in new TestCase {
      val invalid = Seq(
        Response(404, Array.emptyByteArray),
        Response(403, Array.emptyByteArray),
        Response(302, Array.emptyByteArray),
        Response(200, "not json".getBytes(UTF_8)),
        Response(200, """{"mimeType":"image/png","photoData":"!!!"}""".getBytes(UTF_8)),
        Response(200, """{"photoData":"AQID"}""".getBytes(UTF_8)),
        Response(200, s"""{"mimeType":"image/png","photoData":"${Base64.getEncoder.encodeToString(new Array[Byte](1025))}"}""".getBytes(UTF_8)),
        Response(500, new Array[Byte](10000))
      )
      invalid.foreach { r => response = r; provider().fetch(proof).asserting(_ must beNone) }
      calls mustEqual invalid.size
    }
    "fail closed without propagating provider errors" in new TestCase {
      val failed = new Transport { def fetch(config: Tenant, email: String): IO[Response] = IO.raiseError(new RuntimeException("secret")) }
      new WorkspaceDirectoryPhotoProvider(Config(true, Seq(tenant), 1024), failed).fetch(proof).asserting(_ must beNone)
    }
    "remain disabled by default and accept tenant lists from environment-compatible HOCON strings" in new TestCase {
      Config.fromConfig(ConfigFactory.empty()).enabled must beFalse
      val value =
        s"""[{organizationId="$organizationId", issuer="${proof.issuer}", domains=["example.com"], credentialsFile="/private/key.json", delegatedAdmin="admin@example.com"}]"""
      val cfg = ConfigFactory.parseMap(java.util.Map.of("saml.workspacePhotos.enabled", Boolean.box(true), "saml.workspacePhotos.tenants", value))
      Config.fromConfig(cfg).tenants mustEqual Seq(tenant)
    }
  }
}
