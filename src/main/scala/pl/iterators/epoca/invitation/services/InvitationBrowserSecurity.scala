package pl.iterators.epoca.invitation.services

import pl.iterators.epoca.invitation.domain.InvitationOnboarding.Digest

import java.net.URI

/** Router adapter: use only a trusted socket peer with TrustedClientIpService; never extractClientIP as the peer. */
final class InvitationBrowserSecurity(
  allowedOrigins: Set[String],
  apiPath: String,
  localHttp: Boolean,
  crossSite: Boolean,
  secrets: InvitationSecretService,
  cookieName: String = "epoca_invitation_access") {
  require(cookieName.matches("[A-Za-z0-9_]+"), "Invalid cookie name")
  require(
    allowedOrigins.nonEmpty && allowedOrigins.forall { value =>
      val uri = URI.create(value)
      Set("https", "http")(uri.getScheme) && uri.getHost != null && uri.getRawUserInfo == null && uri.getRawQuery == null &&
      uri.getRawFragment == null && Option(uri.getRawPath).forall(_.isEmpty) &&
      (uri.getScheme == "https" || (localHttp && Set("localhost", "127.0.0.1", "[::1]")(uri.getHost)))
    },
    "Exact browser origins are required"
  )
  require(apiPath.matches("/[A-Za-z0-9/_-]*"), "Invalid cookie path")
  require(!crossSite || !localHttp, "Cross-site cookies require HTTPS")
  require(
    !localHttp || allowedOrigins.forall(value => Set("localhost", "127.0.0.1", "[::1]")(URI.create(value).getHost)),
    "Insecure cookies require local development origins"
  )

  def originAllowed(origin: Option[String]): Boolean = origin.exists(allowedOrigins)
  def exchangeAllowed(origin: Option[String], contentType: Option[String]): Boolean =
    originAllowed(origin) && contentType.exists(_.takeWhile(_ != ';').trim.equalsIgnoreCase("application/json"))
  def mutationAllowed(
    origin: Option[String],
    csrf: Option[String],
    expected: Digest
  ): Boolean =
    originAllowed(origin) && csrf.exists(value => secrets.matches(value, expected.toArray))
  def cookie(secret: String, maxAgeSeconds: Long): String = {
    require(secret.matches("[A-Za-z0-9_-]+"), "Invalid cookie value")
    s"$cookieName=$secret; Path=$apiPath; Max-Age=${math.max(0L, maxAgeSeconds)}; HttpOnly" +
      (if (localHttp) "" else "; Secure") + (if (crossSite) "; SameSite=None" else "; SameSite=Lax")
  }
  def clearCookie: String = cookie("deleted", 0)
}
