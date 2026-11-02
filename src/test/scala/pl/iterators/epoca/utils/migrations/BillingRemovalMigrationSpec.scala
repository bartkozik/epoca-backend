package pl.iterators.epoca.utils.migrations

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import pl.iterators.epoca.utils.PgRepositorySpec

import java.net.URI
import java.nio.charset.StandardCharsets
import java.sql.{Connection, DriverManager}
import java.util.UUID

/** V30 and V31 (billing, invoice attachments) and the V32 invitation draft columns stay in history; V37 drops them. */
class BillingRemovalMigrationSpec extends PgRepositorySpec {
  private val baseUrl  = sys.env.getOrElse("TEST_DB_URL", "jdbc:postgresql://localhost:5432/epoca")
  private val dbUser   = sys.env.getOrElse("TEST_DB_USER", "postgres")
  private val dbSecret = sys.env.getOrElse("TEST_DB_PASSWORD", "postgres")

  private val lineages = Vector("public" -> "classpath:db/migration", "removal_tenant" -> "classpath:db/configured")
  private val removedTables =
    Vector("billing_stream", "billing_party_version", "billing_terms_version", "billing_history", "invoice_attachment", "invoice_attachment_receipt")
  private val removedColumns  = Vector("profile_draft", "terms_draft", "profile_prepared_by", "terms_prepared_by")
  private val removalResource = "db/migration/V37__drop_billing_and_invitation_cooperation.sql"

  "billing removal migration" should {
    "drop billing, invoice attachment and invitation draft objects while keeping invitations in both lineages" in {
      lineages.foreach { case (schema, location) =>
        withTemporaryDatabase { url =>
          flyway(url, schema, location, Some("36")).migrate()
          val actor      = UUID.randomUUID()
          val org        = UUID.randomUUID()
          val invitation = UUID.randomUUID()
          withConnection(url) { c =>
            seed(c, schema, actor, org)
            val stream = UUID.randomUUID()
            execute(c, s"INSERT INTO $schema.billing_stream (id,organization_id,user_id,kind) VALUES (?,?,?,'MemberTerms')", stream, org, actor)
            execute(
              c,
              s"INSERT INTO $schema.billing_terms_version (id,stream_id,effective_from,hourly_rate,currency,created_at,modified_at,created_by,modified_by) VALUES (?,?,DATE '2026-10-01',100,'PLN',now(),now(),?,?)",
              UUID.randomUUID(),
              stream,
              actor,
              actor
            )
            execute(
              c,
              s"""INSERT INTO $schema.invitation (id,identity_value,identity_type,roles,organization_id,created_at,created_by,modified_at,modified_by,terms_draft,terms_prepared_by) VALUES (?,'anna@example.test','MailAddress',ARRAY['Employee'],?,now(),?,now(),?,'{"hourlyRate":"100"}'::jsonb,?)""",
              invitation,
              org,
              actor,
              actor,
              actor
            )
            execute(
              c,
              s"""INSERT INTO $schema.invitation_audit (id,invitation_id,actor_id,action,occurred_at,before_data,after_data) VALUES (?,?,?,'Accepted',now(),'{"cooperation":{}}'::jsonb,'{"invitationId":"x","cooperation":{},"termsVersionId":null}'::jsonb)""",
              UUID.randomUUID(),
              invitation,
              actor
            )
            execute(
              c,
              s"""INSERT INTO $schema.invitation_command (actor_id,organization_id,operation,request_id,payload_hash,invitation_id,result_json) VALUES (?,?,'Create',?,?,?,'{"id":"x","cooperation":{}}'::jsonb)""",
              actor,
              org,
              UUID.randomUUID(),
              Array.fill[Byte](32)(1),
              invitation
            )
          }

          val latest = flyway(url, schema, location)
          latest.migrate()
          latest.validate()
          latest.info().current().getVersion.toString shouldEqual "37"

          withConnection(url) { c =>
            assertRemoved(c, schema)
            count(c, s"SELECT count(*) FROM $schema.invitation WHERE id=?", invitation) shouldEqual 1L
            count(
              c,
              s"SELECT count(*) FROM $schema.invitation_audit WHERE jsonb_exists(before_data,'cooperation') OR jsonb_exists_any(after_data,ARRAY['cooperation','termsVersionId'])"
            ) shouldEqual 0L
            count(c, s"SELECT count(*) FROM $schema.invitation_audit WHERE jsonb_exists(after_data,'invitationId')") shouldEqual 1L
            count(c, s"SELECT count(*) FROM $schema.invitation_command WHERE jsonb_exists(result_json,'cooperation')") shouldEqual 0L
            count(c, s"SELECT count(*) FROM $schema.invitation_command WHERE jsonb_exists(result_json,'id')") shouldEqual 1L
          }
        }
      }
      ok
    }

    "be safe to apply again when the objects are already gone" in {
      lineages.foreach { case (schema, location) =>
        withTemporaryDatabase { url =>
          flyway(url, schema, location).migrate()
          val source = new String(getClass.getClassLoader.getResourceAsStream(removalResource).readAllBytes(), StandardCharsets.UTF_8)
          withConnection(url) { c =>
            val statement = c.createStatement()
            try { val _ = statement.execute(source.replace("${flyway:defaultSchema}", schema)); () }
            finally statement.close()
            assertRemoved(c, schema)
          }
        }
      }
      ok
    }
  }

  private def assertRemoved(c: Connection, schema: String): Unit = {
    removedTables.foreach { table =>
      count(c, "SELECT count(*) FROM information_schema.tables WHERE table_schema=? AND table_name=?", schema, table) shouldEqual 0L
    }
    removedColumns.foreach { column =>
      count(
        c,
        "SELECT count(*) FROM information_schema.columns WHERE table_schema=? AND table_name='invitation' AND column_name=?",
        schema,
        column
      ) shouldEqual 0L
    }
    count(
      c,
      "SELECT count(*) FROM pg_constraint k JOIN pg_namespace n ON n.oid=k.connamespace WHERE n.nspname=? AND (k.conname LIKE 'billing%' OR k.conname LIKE 'invoice%' OR k.conname LIKE 'invitation%draft%' OR k.conname LIKE 'invitation%prepared_by%')",
      schema
    ) shouldEqual 0L
    count(c, "SELECT count(*) FROM pg_indexes WHERE schemaname=? AND (indexname LIKE 'billing%' OR indexname LIKE 'invoice%')", schema) shouldEqual 0L
    ()
  }

  private def seed(
    c: Connection,
    schema: String,
    actor: UUID,
    org: UUID
  ): Unit = {
    execute(
      c,
      s"INSERT INTO $schema.\"user\" (id,first_name,last_name,is_super_admin,created_at,modified_at) VALUES (?,'Removal','Test',false,now(),now())",
      actor
    )
    execute(
      c,
      s"INSERT INTO $schema.organization (id,name,slug,created_at,created_by,modified_at,modified_by) VALUES (?,'Removal test',?,now(),?,now(),?)",
      org,
      s"removal-$org",
      actor,
      actor
    )
  }

  private def execute(
    c: Connection,
    sql: String,
    values: AnyRef*
  ): Unit = {
    val statement = c.prepareStatement(sql)
    try {
      values.zipWithIndex.foreach { case (value, index) => statement.setObject(index + 1, value) }
      require(statement.executeUpdate() == 1)
    } finally statement.close()
  }

  private def count(
    c: Connection,
    sql: String,
    values: AnyRef*
  ): Long = {
    val statement = c.prepareStatement(sql)
    try {
      values.zipWithIndex.foreach { case (value, index) => statement.setObject(index + 1, value) }
      val result = statement.executeQuery()
      try { require(result.next()); result.getLong(1) }
      finally result.close()
    } finally statement.close()
  }

  private def flyway(
    url: String,
    schema: String,
    location: String,
    target: Option[String] = None
  ): Flyway = {
    val config = Flyway.configure().dataSource(url, dbUser, dbSecret).locations(location).schemas(schema, "quartz")
    target.fold(config.load())(version => config.target(MigrationVersion.fromVersion(version)).load())
  }

  private def withTemporaryDatabase[T](test: String => T): T = {
    val name = s"billing_removal_${UUID.randomUUID().toString.replace("-", "")}"
    withConnection(baseUrl) { c =>
      val statement = c.createStatement()
      try { val _ = statement.executeUpdate(s"CREATE DATABASE \"$name\""); () }
      finally statement.close()
    }
    try {
      val uri         = URI.create(baseUrl.stripPrefix("jdbc:"))
      val databaseUri = new URI(uri.getScheme, uri.getUserInfo, uri.getHost, uri.getPort, s"/$name", uri.getQuery, uri.getFragment)
      test(s"jdbc:$databaseUri")
    } finally
      withConnection(baseUrl) { c =>
        val statement = c.createStatement()
        try { val _ = statement.executeUpdate(s"DROP DATABASE \"$name\" WITH (FORCE)"); () }
        finally statement.close()
      }
  }

  private def withConnection[T](url: String)(use: Connection => T): T = {
    val connection = DriverManager.getConnection(url, dbUser, dbSecret)
    try use(connection)
    finally connection.close()
  }
}
