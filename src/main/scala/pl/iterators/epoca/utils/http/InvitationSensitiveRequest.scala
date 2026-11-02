package pl.iterators.epoca.utils.http

import org.apache.pekko.http.scaladsl.model.HttpRequest

object InvitationSensitiveRequest {
  def apply(request: HttpRequest): Boolean = {
    val parts = request.uri.path.toString.split('/').filter(_.nonEmpty).toVector
    parts.contains("invitation-access") || parts.sliding(3).exists {
      case Vector("organizations", _, "invitations" | "saml") => true
      case _                                                  => false
    }
  }
}
