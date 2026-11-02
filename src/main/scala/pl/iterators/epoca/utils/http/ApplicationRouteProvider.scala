package pl.iterators.epoca.utils.http

import org.apache.pekko.http.scaladsl.server.directives.RouteDirectives
import org.apache.pekko.http.scaladsl.server.{Directives, Route, RouteConcatenation}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.oauth.domain.OAuthAuthContext
import pl.iterators.epoca.utils.log.TracingContext

trait ApplicationRouteProvider extends RouteProvider with AuthRouteProvider with OAuthRouteProvider with Directives {
  override def route(implicit tracingContext: TracingContext): Route = RouteDirectives.reject

  override def route(auth: AuthContext)(implicit tracingContext: TracingContext): Route = RouteDirectives.reject

  override def oauthRoute(auth: OAuthAuthContext)(implicit tracingContext: TracingContext): Route = RouteDirectives.reject
}

trait RouteProvider extends RouteConcatenation {
  def route(implicit tracingContext: TracingContext): Route
}

trait AuthRouteProvider extends RouteConcatenation {
  def route(auth: AuthContext)(implicit tracingContext: TracingContext): Route
}
