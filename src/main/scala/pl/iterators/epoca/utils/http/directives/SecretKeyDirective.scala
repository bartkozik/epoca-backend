package pl.iterators.epoca.utils.http.directives

import com.typesafe.config.Config
import org.apache.pekko.Done
import org.apache.pekko.http.scaladsl.model.HttpHeader
import org.apache.pekko.http.scaladsl.server.directives.HeaderDirectives
import org.apache.pekko.http.scaladsl.server.{Directive1, Directives}
import pl.iterators.epoca.utils.security.SecretComparison

trait SecretKeyDirective extends HeaderDirectives {
  private def extractSecretKeyHeader(name: String, value: String): HttpHeader => Option[Done] = {
    case HttpHeader(`name`, provided) if SecretComparison.matches(provided, value) =>
      Some(Done)
    case HttpHeader(_, _) =>
      None
  }

  /** Protects a route with the configured secret header. When no secret is configured the route is disabled and every request is rejected, so the
    * route is never reachable with a public default value.
    */
  def secretKeyProtection(config: SecretKeyConfig): Directive1[Done] =
    config.value match {
      case Some(value) => headerValue(extractSecretKeyHeader(config.name, value))
      case None        => Directives.reject.toDirective[Tuple1[Done]]
    }
}

object SecretKeyDirective extends SecretKeyDirective

final case class SecretKeyConfig(name: String, value: Option[String])

object SecretKeyConfig {
  private val ValuePath = "app.secretKey.value"

  def apply(config: Config): SecretKeyConfig =
    SecretKeyConfig(
      name = config.getString("app.secretKey.name"),
      value = if (config.hasPath(ValuePath)) Option(config.getString(ValuePath)).filter(_.trim.nonEmpty) else None
    )
}
