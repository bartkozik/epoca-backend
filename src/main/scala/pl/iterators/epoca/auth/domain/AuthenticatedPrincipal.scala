package pl.iterators.epoca.auth.domain

import pl.iterators.epoca.oauth.domain.OAuthAuthContext

sealed trait AuthenticatedPrincipal
object AuthenticatedPrincipal {
  final case class HumanPrincipal(context: AuthContext)      extends AuthenticatedPrincipal
  final case class OAuthPrincipal(context: OAuthAuthContext) extends AuthenticatedPrincipal
}
