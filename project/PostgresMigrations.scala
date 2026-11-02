import sbt.Keys._
import sbt._
import _root_.flywaysbt.FlywayPlugin
import _root_.flywaysbt.FlywayPlugin.autoImport._

object PostgresMigrations {
  private def requireMatchingOverride(
    property: String,
    environment: String,
    expected: Set[String]
  ): Unit =
    Seq(sys.props.get(s"flyway.$property"), sys.env.get(environment)).flatten.foreach { value =>
      require(expected.contains(value), s"Flyway $property override conflicts with the configured application schema/lineage")
    }

  private def schemaFromUrl(url: String): String = {
    require(url.isEmpty || url.startsWith("jdbc:postgresql:"), "DB_URL must be a PostgreSQL JDBC URL")
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
    schema
  }

  private def commonSettings(prefix: String) = Seq(
    flywaySchemas := {
      val url    = flywayUrl.value
      val schema = schemaFromUrl(url)
      requireMatchingOverride("url", "FLYWAY_URL", Set(url))
      requireMatchingOverride("defaultSchema", "FLYWAY_DEFAULT_SCHEMA", Set(schema))
      requireMatchingOverride("schemas", "FLYWAY_SCHEMAS", Set(schema, s"$schema,quartz"))
      Seq(schema, "quartz")
    },
    flywayLocations := {
      val schema     = schemaFromUrl(flywayUrl.value)
      val configured = sys.env.get(prefix + "MIGRATION_BOOTSTRAP")
      require(configured.forall(value => value == "true" || value == "false"), "MIGRATION_BOOTSTRAP must be true or false")
      val bootstrap = configured.map(_.toBoolean).getOrElse(schema != "public")
      val resources = (Compile / resourceDirectory).value.getPath
      val lineage   = if (schema == "public") "migration" else "configured"
      val locations = Seq(s"filesystem:$resources/db/$lineage") ++ (if (bootstrap) Seq(s"filesystem:$resources/db/bootstrap") else Seq.empty)
      requireMatchingOverride("locations", "FLYWAY_LOCATIONS", Set(locations.mkString(",")))
      locations
    }
  )

  def settings: Seq[Setting[_]] = {
    val url      = sys.env.get("DB_URL").map(url => flywayUrl := url)
    val user     = sys.env.get("DB_USER").map(user => flywayUser := user)
    val password = sys.env.get("DB_PASSWORD").map(password => flywayPassword := password)

    commonSettings("DB_") ++
      Seq(url, user, password).flatten
  }

  def testSettings: Seq[Setting[_]] = {
    val url      = sys.env.get("TEST_DB_URL").map(url => flywayUrl := url)
    val user     = sys.env.get("TEST_DB_USER").map(user => flywayUser := user)
    val password = sys.env.get("TEST_DB_PASSWORD").map(password => flywayPassword := password)

    FlywayPlugin.flywayBaseSettings(Test) ++
      commonSettings("TEST_DB_") ++
      Seq(url, user, password).flatten
  }

  def itSettings: Seq[Setting[_]] = {
    val url      = sys.env.get("TEST_DB_URL").map(url => flywayUrl := url)
    val user     = sys.env.get("TEST_DB_USER").map(user => flywayUser := user)
    val password = sys.env.get("TEST_DB_PASSWORD").map(password => flywayPassword := password)

    FlywayPlugin.flywayBaseSettings(IntegrationTest) ++
      commonSettings("TEST_DB_") ++
      Seq(url, user, password).flatten
  }
}
