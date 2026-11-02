package pl.iterators.epoca.auth.repositories

import org.flywaydb.core.Flyway
import pl.iterators.epoca.utils.PgRepositorySpec

import java.net.URI
import java.sql.{Connection, DriverManager}
import java.util.UUID

class AccountAuthenticationMigrationSpec extends PgRepositorySpec {
  private val baseUrl  = sys.env("TEST_DB_URL")
  private val dbUser   = sys.env.getOrElse("TEST_DB_USER", "postgres")
  private val dbSecret = sys.env.getOrElse("TEST_DB_PASSWORD", "postgres")

  "V34 account evidence" should {
    "preserve legacy refresh/SAML rows without manufacturing proof in both migration lineages" in {
      Seq("public" -> "classpath:db/migration", "auth_tenant" -> "classpath:db/configured").foreach { case (schema, location) =>
        withTemporaryDatabase { url =>
          def flyway(target: String): Flyway = Flyway
            .configure()
            .dataSource(url, dbUser, dbSecret)
            .schemas(schema, "quartz")
            .defaultSchema(schema)
            .locations(location)
            .target(target)
            .load()
          flyway("33").migrate()
          withConnection(url) { c =>
            execute(
              c,
              s"""INSERT INTO $schema."user" (id,first_name,last_name,is_super_admin,created_at,modified_at)
              VALUES ('00000000-0000-0000-0000-000000000033','Legacy','User',false,now(),now())"""
            )
            execute(
              c,
              s"""INSERT INTO $schema.refresh_token(user_id,device_id,value,authed_by,last_authed_at,created_at)
              VALUES ('00000000-0000-0000-0000-000000000033','legacy-device','legacy-token',ARRAY[]::uuid[],now(),now())"""
            )
            execute(c, s"INSERT INTO $schema.saml_request(id,used,created_at) VALUES ('legacy-request',false,now())")
            val before  = snapshot(c, schema)
            val upgrade = flyway("34")
            upgrade.migrate()
            upgrade.validate()
            snapshot(c, schema, removeProof = true) shouldEqual before
            count(c, s"SELECT count(*) FROM $schema.refresh_token WHERE account_authentication IS NULL") shouldEqual 1L
            count(c, s"SELECT count(*) FROM $schema.saml_request WHERE account_authentication IS NULL") shouldEqual 1L
            count(
              c,
              s"SELECT count(*) FROM information_schema.columns WHERE table_schema='$schema' AND table_name IN ('refresh_token','saml_request') AND column_name='account_authentication' AND data_type='jsonb' AND is_nullable='YES'"
            ) shouldEqual 2L
            upgrade.migrate().migrationsExecuted shouldEqual 0
          }
        }
      }
      true shouldEqual true
    }
  }
  private def snapshot(
    c: Connection,
    schema: String,
    removeProof: Boolean = false
  ): String = {
    val remove    = if (removeProof) " - 'account_authentication'" else ""
    val statement = c.createStatement()
    try {
      val result = statement.executeQuery(
        s"SELECT (SELECT (to_jsonb(r)$remove)::text FROM $schema.refresh_token r) || (SELECT (to_jsonb(s)$remove)::text FROM $schema.saml_request s)"
      )
      try { require(result.next()); result.getString(1) }
      finally result.close()
    } finally statement.close()
  }
  private def count(c: Connection, sql: String): Long = {
    val statement = c.createStatement()
    try {
      val result = statement.executeQuery(sql);
      try { require(result.next()); result.getLong(1) }
      finally result.close()
    } finally statement.close()
  }
  private def execute(c: Connection, sql: String): Unit = {
    val statement = c.createStatement()
    try { val _ = statement.execute(sql); () }
    finally statement.close()
  }
  private def withConnection[T](url: String)(use: Connection => T): T = {
    val connection = DriverManager.getConnection(url, dbUser, dbSecret)
    try use(connection)
    finally connection.close()
  }
  private def withTemporaryDatabase[T](use: String => T): T = {
    val name = "ct208_auth_" + UUID.randomUUID().toString.replace("-", "")
    require(name.matches("ct208_auth_[0-9a-f]{32}"))
    withConnection(baseUrl)(execute(_, s"CREATE DATABASE $name"))
    try {
      val uri = URI.create(baseUrl.stripPrefix("jdbc:"))
      use("jdbc:" + new URI(uri.getScheme, uri.getUserInfo, uri.getHost, uri.getPort, "/" + name, null, null))
    } finally withConnection(baseUrl)(execute(_, s"DROP DATABASE $name WITH (FORCE)"))
  }
}
