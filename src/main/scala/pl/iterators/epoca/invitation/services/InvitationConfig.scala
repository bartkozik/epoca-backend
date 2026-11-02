package pl.iterators.epoca.invitation.services

import com.typesafe.config.Config

import java.nio.charset.StandardCharsets
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

final case class InvitationConfig(
  enabled: Boolean,
  browser: Option[InvitationBrowserSecurity],
  rateKey: Option[Array[Byte]]) {
  override def toString: String = s"InvitationConfig(enabled=$enabled,<redacted>)"
}
object InvitationConfig {
  def fromConfig(config: Config): InvitationConfig = {
    val enabled = config.hasPath("invitation.featureEnabled") && config.getBoolean("invitation.featureEnabled")
    if (!enabled) InvitationConfig(false, None, None)
    else
      try {
        val origins = config.getStringList("invitation.allowedOrigins").asScala.toSet
        val key     = config.getString("invitation.rateKey").getBytes(StandardCharsets.UTF_8)
        require(key.length >= 32)
        val path      = "/" + config.getString("app.apiPrefix") + "/" + config.getString("app.apiVersion")
        val local     = config.hasPath("invitation.localHttp") && config.getBoolean("invitation.localHttp")
        val crossSite = config.hasPath("invitation.crossSite") && config.getBoolean("invitation.crossSite")
        val browser   = new InvitationBrowserSecurity(origins, path, local, crossSite, new InvitationSecretService)
        InvitationConfig(true, Some(browser), Some(key))
      } catch {
        case NonFatal(_) =>
          throw new IllegalArgumentException(
            "Invalid invitation configuration: require exact HTTPS origins, a 32-byte rate key, and valid API cookie path; HTTP is allowed only for explicit loopback development"
          )
      }
  }
}
