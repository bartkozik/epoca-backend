package pl.iterators.epoca.oauth.services

import pl.iterators.epoca.oauth.domain.OAuthDomain.{OAuthScope, OAuthScopeError}

object OAuthScopeService {
  def normalizeGranted(scopes: Set[OAuthScope]): Either[OAuthScopeError, Set[OAuthScope]] =
    Either.cond(scopes.nonEmpty, scopes, OAuthScopeError.EmptyGrantedScopes)

  def resolveRequested(requested: Option[String], granted: Set[OAuthScope]): Either[OAuthScopeError, Set[OAuthScope]] =
    requested match {
      case None        => Right(granted)
      case Some(value) => parseRequested(value).filterOrElse(_.subsetOf(granted), OAuthScopeError.InvalidScope)
    }

  def render(scopes: Set[OAuthScope]): String =
    OAuthScope.values.filter(scopes).map(_.value).mkString(" ")

  private def parseRequested(value: String): Either[OAuthScopeError, Set[OAuthScope]] = {
    val names  = value.split("\\s+").filter(_.nonEmpty).toVector
    val parsed = names.map(OAuthScope.fromValue)
    Either.cond(names.nonEmpty && parsed.forall(_.nonEmpty), parsed.flatten.toSet, OAuthScopeError.InvalidScope)
  }
}
