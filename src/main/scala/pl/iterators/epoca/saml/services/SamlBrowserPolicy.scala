package pl.iterators.epoca.saml.services

import com.typesafe.config.Config
import pl.iterators.epoca.invitation.services.{InvitationBrowserSecurity, InvitationSecretService}

import java.net.URI
import scala.util.Try

/** Browser policy is independent of invitation rollout. Invalid policy disables SAML browser routes only. */
final case class SamlBrowserPolicy(security: InvitationBrowserSecurity, callback: String) {
  def nonceCookie(nonce: String): String = security.cookie(nonce, 900L)
  def clearNonceCookie: String           = security.clearCookie
}
object SamlBrowserPolicy {
  val nonceCookieName: String = "epoca_saml_nonce"
  def fromConfig(config: Config, secrets: InvitationSecretService): Option[SamlBrowserPolicy] = Try {
    val callback = config.getString("saml.setTokenUrl")
    val uri      = URI.create(callback)
    require(uri.isAbsolute && uri.getHost != null && uri.getRawUserInfo == null && uri.getRawQuery == null && uri.getRawFragment == null)
    val origin    = new URI(uri.getScheme, null, uri.getHost, uri.getPort, null, null, null).toString
    val localHttp = config.hasPath("saml.browser.localHttp") && config.getBoolean("saml.browser.localHttp")
    val crossSite = config.hasPath("saml.browser.crossSite") && config.getBoolean("saml.browser.crossSite")
    val apiPath   = if (config.hasPath("saml.browser.cookiePath")) config.getString("saml.browser.cookiePath") else "/api"
    SamlBrowserPolicy(new InvitationBrowserSecurity(Set(origin), apiPath, localHttp, crossSite, secrets, nonceCookieName), callback)
  }.toOption
}
