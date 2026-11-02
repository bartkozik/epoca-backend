package pl.iterators.epoca.utils.http

import org.apache.pekko.http.scaladsl.server.{Route, RouteConcatenation}
import pl.iterators.epoca.oauth.domain.OAuthAuthContext
import pl.iterators.epoca.utils.log.TracingContext

trait OAuthRouteProvider extends RouteConcatenation {
  def oauthRoute(auth: OAuthAuthContext)(implicit tracingContext: TracingContext): Route
}
