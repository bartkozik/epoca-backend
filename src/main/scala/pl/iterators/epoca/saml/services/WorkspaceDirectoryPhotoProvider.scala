package pl.iterators.epoca.saml.services

import cats.effect.IO
import com.typesafe.config.{Config => TsConfig, ConfigFactory, ConfigValueType}
import io.circe.parser.parse
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId
import pl.iterators.epoca.user.domain.UserDomain.{IdentityType, UserIdentityValue}
import pl.iterators.epoca.utils.db.EmailCanonicalization
import pl.iterators.epoca.utils.log.LoggingSupport

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.{Base64, Locale, UUID}
import scala.jdk.CollectionConverters._
import scala.util.Try

/** Created only from a consumed, server-verified SAML callback. Never accepts a browser-supplied lookup email. */
final case class WorkspacePhotoProof(
  organizationId: OrganizationId,
  trustEpoch: Long,
  issuer: String,
  subject: String,
  canonicalEmail: String) {
  override def toString: String = "WorkspacePhotoProof(<redacted>)"
}

class WorkspaceDirectoryPhotoProvider(config: WorkspaceDirectoryPhotoProvider.Config, transport: WorkspaceDirectoryPhotoProvider.Transport)
    extends LoggingSupport {
  import WorkspaceDirectoryPhotoProvider._

  private def tenant(proof: WorkspacePhotoProof): Option[Tenant] =
    if (
      !config.enabled || proof.trustEpoch <= 0 || proof.subject.trim.isEmpty ||
      proof.canonicalEmail != EmailCanonicalization.canonical(proof.canonicalEmail) ||
      !UserIdentityValue.isValid(IdentityType.MailAddress, UserIdentityValue(proof.canonicalEmail))
    ) None
    else
      config.tenants.find(t =>
        t.organizationId == proof.organizationId && t.issuer == proof.issuer &&
          t.domains.contains(proof.canonicalEmail.substring(proof.canonicalEmail.lastIndexOf('@') + 1))
      )

  def configured(proof: WorkspacePhotoProof): Boolean = tenant(proof).isDefined

  def fetch(proof: WorkspacePhotoProof): IO[Option[Photo]] = IO
    .defer {
      tenant(proof).fold(IO.pure(Option.empty[Photo])) { selected =>
        transport.fetch(selected, proof.canonicalEmail).flatMap { response =>
          val decoded =
            if (response.status != 200 || response.body.length > maxResponseBytes(config.maxBytes)) None
            else
              for {
                json    <- parse(new String(response.body, UTF_8)).toOption
                mime    <- json.hcursor.get[String]("mimeType").toOption.flatMap(normalizeMime)
                encoded <- json.hcursor.get[String]("photoData").toOption if encoded.nonEmpty && encoded.length <= ((config.maxBytes + 2) / 3) * 4
                bytes   <- Try(Base64.getUrlDecoder.decode(encoded.replace('+', '-').replace('/', '_').replace('*', '=').replace('.', '='))).toOption
                if bytes.nonEmpty && bytes.length <= config.maxBytes
              } yield {
                // Include the immutable tenant and SAML principal in provenance, without persisting their raw values.
                val principal = io.circe.Json
                  .arr(
                    io.circe.Json.fromString(proof.organizationId.toString),
                    io.circe.Json.fromLong(proof.trustEpoch),
                    io.circe.Json.fromString(proof.issuer),
                    io.circe.Json.fromString(proof.subject)
                  )
                  .noSpaces
                  .getBytes(UTF_8)
                Photo(bytes, mime, s"workspace:${sha256(principal)}:${sha256(bytes)}")
              }
          if (response.status != 200 && response.status != 404)
            Logger[IO].warning(s"Workspace photo lookup returned HTTP ${response.status}").as(decoded)
          else if (response.status == 200 && decoded.isEmpty)
            Logger[IO].warning("Workspace photo response rejected").as(decoded)
          else IO.pure(decoded)
        }
      }
    }
    .handleErrorWith(_ => Logger[IO].warning("Workspace photo credentials or lookup failed").as(None))
}

object WorkspaceDirectoryPhotoProvider {
  final case class Tenant(
    organizationId: OrganizationId,
    issuer: String,
    domains: Set[String],
    credentialsFile: String,
    delegatedAdmin: String) {
    override def toString: String = "WorkspacePhotoTenant(<redacted>)"
  }
  final case class Config(
    enabled: Boolean,
    tenants: Seq[Tenant],
    maxBytes: Int = 2097152) {
    require(maxBytes > 0 && maxBytes <= 2097152, "Invalid Workspace photo size limit")
    require(tenants.size <= 256 && tenants.map(_.organizationId).distinct.size == tenants.size, "Duplicate or excessive Workspace tenants")
    require(
      tenants.forall(t =>
        t.issuer.nonEmpty && t.domains.nonEmpty && t.domains.forall(d =>
          d.matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+")
        ) &&
          java.nio.file.Paths.get(t.credentialsFile).isAbsolute &&
          UserIdentityValue.isValid(IdentityType.MailAddress, UserIdentityValue(t.delegatedAdmin))
      ),
      "Invalid Workspace tenant configuration"
    )
  }
  object Config {
    def fromConfig(config: TsConfig): Config = {
      val path = "saml.workspacePhotos"
      if (!config.hasPath(s"$path.enabled") || !config.getBoolean(s"$path.enabled")) Config(false, Seq.empty)
      else {
        val raw = config.getConfig(path)
        val value =
          if (raw.getValue("tenants").valueType() == ConfigValueType.STRING)
            ConfigFactory.parseString("tenants=" + raw.getString("tenants"))
          else raw
        val tenants = value
          .getConfigList("tenants")
          .asScala
          .map { t =>
            Tenant(
              OrganizationId(UUID.fromString(t.getString("organizationId"))),
              t.getString("issuer"),
              t.getStringList("domains").asScala.toSet,
              t.getString("credentialsFile"),
              t.getString("delegatedAdmin")
            )
          }
          .toSeq
        Config(true, tenants)
      }
    }
  }
  final case class Photo(
    bytes: Array[Byte],
    contentType: String,
    revision: String) {
    override def toString: String = "WorkspacePhoto(<redacted>)"
  }
  final case class Response(status: Int, body: Array[Byte]) {
    override def toString: String = s"WorkspacePhotoResponse($status,<redacted>)"
  }
  trait Transport { def fetch(tenant: Tenant, email: String): IO[Response] }
  private[services] def maxResponseBytes(maxBytes: Int): Int = ((maxBytes + 2) / 3) * 4 + 65536
  private def normalizeMime(value: String): Option[String] = value.trim.toLowerCase(Locale.ROOT) match {
    case "jpeg" | "jpg" | "image/jpeg" | "image/jpg" => Some("image/jpeg")
    case "png" | "image/png"                         => Some("image/png")
    case "gif" | "image/gif"                         => Some("image/gif")
    case _                                           => None
  }
  private def sha256(bytes: Array[Byte]): String = MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString
}
