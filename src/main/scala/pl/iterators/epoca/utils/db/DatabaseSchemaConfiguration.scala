package pl.iterators.epoca.utils.db

import com.typesafe.config.{Config, ConfigValueFactory}
import org.postgresql.Driver

import java.util.Properties

object DatabaseSchemaConfiguration {

  /** Both Slick and Quartz receive this URL, including an explicit public default. */
  def apply(config: Config): Config = {
    val url        = config.getString("db.url")
    val properties = Driver.parseURL(url, new Properties())
    require(properties != null, "db.url must be a PostgreSQL JDBC URL")
    val query = url.split("\\?", 2).lift(1).getOrElse("")
    val schemas = query.split("&").toSeq.filter(_.nonEmpty).flatMap { parameter =>
      val parts = parameter.split("=", 2)
      val name  = parts(0)
      require(!name.contains("%"), "JDBC parameter names must not be percent encoded")
      if (name == "currentSchema") Some(parts.lift(1).getOrElse("")) else None
    }
    require(schemas.size <= 1, "JDBC currentSchema must occur at most once")
    val schema = schemas.headOption.getOrElse("public")
    require(
      schema.matches("[a-z][a-z0-9_]{0,62}") && !schema.startsWith("pg_") &&
        schema != "information_schema" && schema != "quartz",
      "JDBC currentSchema must be one application schema identifier"
    )
    val configuredUrl =
      if (schemas.nonEmpty) url
      else s"$url${if (url.contains("?")) "&" else "?"}currentSchema=public"
    config.withValue("db.url", ConfigValueFactory.fromAnyRef(configuredUrl))
  }
}
