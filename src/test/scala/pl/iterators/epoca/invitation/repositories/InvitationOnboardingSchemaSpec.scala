package pl.iterators.epoca.invitation.repositories

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.postgresql.util.PSQLException
import pl.iterators.epoca.utils.PgRepositorySpec

import java.net.URI
import java.sql.{Connection, DriverManager}
import java.util.UUID
import scala.util.{Failure, Success, Try}

class InvitationOnboardingSchemaSpec extends PgRepositorySpec {
  private val baseUrl  = sys.env("TEST_DB_URL")
  private val dbUser   = sys.env.getOrElse("TEST_DB_USER", "postgres")
  private val dbSecret = sys.env.getOrElse("TEST_DB_PASSWORD", "postgres")

  "invitation onboarding schema" should {
    "enforce pending uniqueness and v2 required fields in both migration lineages" in {
      Seq("public" -> "classpath:db/migration", "invitation_tenant" -> "classpath:db/configured").foreach { case (schema, location) =>
        withTemporaryDatabase { url =>
          flyway(url, schema = schema, location = location).migrate()
          withConnection(url) { c =>
            val actor    = UUID.randomUUID()
            val org      = UUID.randomUUID()
            val otherOrg = UUID.randomUUID()
            seed(c, schema, actor, org)
            organization(c, schema, actor, otherOrg)
            val first = invitation(c, schema, actor, org, " Anna@example.test ")
            assertViolation("23505", "invitation_pending_email_key")(invitation(c, schema, actor, org, "anna@example.test"))
            invitation(c, schema, actor, otherOrg, "anna@example.test")
            execute(c, s"UPDATE $schema.invitation SET archived_at=now() WHERE id=?", first)
            invitation(c, schema, actor, org, "anna@example.test")
            assertViolation("23514", "invitation_v2_shape")(invitation(c, schema, actor, org, "missing@example.test", null))
            Seq("ARRAY[]::text[]", "ARRAY['Owner','Manager']", "ARRAY['Invalid']", "ARRAY[NULL]::text[]").foreach { roles =>
              assertViolation("23514", "invitation_v2_shape") {
                execute(c, s"UPDATE $schema.invitation SET roles=$roles WHERE id=?", first)
              }
            }
            assertViolation("23514", "invitation_delivery_result_shape") {
              execute(
                c,
                s"INSERT INTO $schema.invitation_delivery_attempt (id,invitation_id,generation,started_at,safe_error_code) VALUES (?,?,1,now(),'MAIL_FAILED')",
                UUID.randomUUID(),
                first
              )
            }
            val expired = invitation(c, schema, actor, org, "expired@example.test")
            execute(c, s"UPDATE $schema.invitation SET expire_at=now()-interval '1 day' WHERE id=?", expired)
            assertViolation("23505", "invitation_pending_email_key")(invitation(c, schema, actor, org, "EXPIRED@example.test"))
            val bindingSql =
              s"INSERT INTO $schema.saml_identity_binding (organization_id,trust_epoch,issuer,subject,user_id,canonical_email,verified_at,proof) VALUES (?,?,'issuer','subject',?,'anna@example.test',now(),'{}')"
            execute(c, bindingSql, org, Long.box(1), actor)
            assertViolation("23505", "saml_identity_binding_pkey")(execute(c, bindingSql, org, Long.box(1), actor))
            execute(c, bindingSql, org, Long.box(2), actor)
            assertViolation("23503", "saml_identity_binding_user_id_fkey")(execute(c, bindingSql, otherOrg, Long.box(1), UUID.randomUUID()))
            execute(c, s"INSERT INTO $schema.saml_request (id,used,created_at) VALUES (?,false,now())", "legacy-request")
            assertViolation("23514", "saml_request_metadata_shape") {
              execute(c, s"UPDATE $schema.saml_request SET purpose='Invitation' WHERE id=?", "legacy-request")
            }
            assertViolation("23503", "invitation_organization_id_fkey") {
              execute(c, s"DELETE FROM $schema.organization WHERE id=?", org)
            }
            count(c, s"SELECT count(*) FROM $schema.saml_identity_binding") shouldEqual 2L
          }
        }
      }
      true should beTrue
    }

    "match JVM trim for generated keys and reject control-padded pending duplicates in both schemas" in {
      Seq("public" -> "classpath:db/migration", "invitation_tenant" -> "classpath:db/configured").foreach { case (schema, location) =>
        withTemporaryDatabase { url =>
          flyway(url, schema = schema, location = location).migrate()
          withConnection(url) { c =>
            val actor = UUID.randomUUID(); val org = UUID.randomUUID()
            seed(c, schema, actor, org)
            (1 to 32).foreach { code =>
              val padding = code.toChar.toString
              val email   = s"trim-$code@example.test"
              val id      = invitation(c, schema, actor, org, padding + email.toUpperCase(java.util.Locale.ROOT) + padding)
              assert(
                count(c, s"SELECT count(*) FROM $schema.invitation WHERE id=? AND canonical_email=?", id, email) == 1L,
                s"Generated key must remove U+${code.toHexString}"
              )
              assertViolation("23505", "invitation_pending_email_key")(invitation(c, schema, actor, org, email))
            }
          }
        }
      }
      true should beTrue
    }

    "preserve non-ASCII case in invitation keys and SAML canonical-email checks in both schemas" in {
      val cases = Seq(
        " I@EXAMPLE.TEST "          -> "i@example.test",
        "\tİI@EXAMPLE.TEST\n"       -> "İi@example.test",
        "Σσς@EXAMPLE.TEST"          -> "Σσς@example.test",
        "KK@EXAMPLE.TEST"           -> "Kk@example.test",
        " D’ANNA+İΣK@EXAMPLE.TEST " -> "d’anna+İΣK@example.test"
      )
      Seq("public" -> "classpath:db/migration", "invitation_tenant" -> "classpath:db/configured").foreach { case (schema, location) =>
        withTemporaryDatabase { url =>
          flyway(url, schema = schema, location = location).migrate()
          withConnection(url) { c =>
            val actor = UUID.randomUUID(); val org = UUID.randomUUID()
            seed(c, schema, actor, org)
            cases.zipWithIndex.foreach { case ((input, canonical), index) =>
              val id = invitation(c, schema, actor, org, input)
              assert(count(c, s"SELECT count(*) FROM $schema.invitation WHERE id=? AND canonical_email=?", id, canonical) == 1L)
              assert(pl.iterators.epoca.user.repositories.EmailIdentityRepository.canonical(input) == canonical)
              execute(
                c,
                s"INSERT INTO $schema.saml_identity_binding (organization_id,trust_epoch,issuer,subject,user_id,canonical_email,verified_at,proof) VALUES (?,1,'issuer',?,?,?,now(),'{}')",
                org,
                index.toString,
                actor,
                canonical
              )
              execute(
                c,
                s"INSERT INTO $schema.saml_request (id,used,created_at,verified_issuer,verified_subject,verified_canonical_email,verified_at) VALUES (?,false,now(),'issuer','subject',?,now())",
                index.toString,
                canonical
              )
              assertViolation("23514", "saml_identity_binding_canonical_email_check") {
                execute(
                  c,
                  s"UPDATE $schema.saml_identity_binding SET canonical_email=? WHERE organization_id=? AND subject=?",
                  "\u001f" + canonical,
                  org,
                  index.toString
                )
              }
              assertViolation("23514", "saml_request_verified_shape") {
                execute(c, s"UPDATE $schema.saml_request SET verified_canonical_email=? WHERE id=?", "\t" + canonical, index.toString)
              }
            }
            Seq("İ", "Σ", "σ", "ς", "K", "k").foreach(local => invitation(c, schema, actor, org, local + "@example.test"))
          }
        }
      }
      true should beTrue
    }

    "preserve legacy rows when upgrading V30 in both schemas" in {
      Seq("public" -> "classpath:db/migration", "invitation_tenant" -> "classpath:db/configured").foreach { case (schema, location) =>
        withTemporaryDatabase { url =>
          flyway(url, Some("30"), schema, location).migrate()
          val actor = UUID.randomUUID(); val org = UUID.randomUUID()
          withConnection(url) { c => seed(c, schema, actor, org); legacy(c, schema, actor, org, "legacy@example.test") }
          flyway(url, schema = schema, location = location).migrate()
          withConnection(url) { c =>
            count(c, s"SELECT count(*) FROM $schema.invitation WHERE protocol_version=1 AND first_name IS NULL AND token_hash IS NULL") shouldEqual 1L
            count(c, s"SELECT count(*) FROM $schema.saml_identity_binding") shouldEqual 0L
          }
        }
      }
      true should beTrue
    }

    "stop duplicate legacy pending upgrades with a count and no addresses in both schemas" in {
      Seq("public" -> "classpath:db/migration", "invitation_tenant" -> "classpath:db/configured").foreach { case (schema, location) =>
        withTemporaryDatabase { url =>
          flyway(url, Some("30"), schema, location).migrate()
          withConnection(url) { c =>
            val actor = UUID.randomUUID(); val org = UUID.randomUUID()
            seed(c, schema, actor, org)
            legacy(c, schema, actor, org, "\t\u001fPrivate@example.test\r\n")
            legacy(c, schema, actor, org, "private@example.test")
          }
          val failure  = Try(flyway(url, schema = schema, location = location).migrate()).failed.get
          val messages = Iterator.iterate(failure)(_.getCause).takeWhile(_ != null).map(_.getMessage).mkString(" ")
          messages must contain("1 duplicate pending invitation email groups")
          messages.toLowerCase must not contain "private@example.test"
        }
      }
      true should beTrue
    }
  }

  private def seed(
    c: Connection,
    s: String,
    actor: UUID,
    org: UUID
  ): Unit = {
    execute(
      c,
      s"INSERT INTO $s.\"user\" (id,first_name,last_name,is_super_admin,created_at,modified_at) VALUES (?,'Test','Actor',false,now(),now())",
      actor
    )
    organization(c, s, actor, org)
  }
  private def organization(
    c: Connection,
    s: String,
    actor: UUID,
    org: UUID
  ): Unit =
    execute(
      c,
      s"INSERT INTO $s.organization (id,name,slug,created_at,created_by,modified_at,modified_by) VALUES (?,'Invitation test',?,now(),?,now(),?)",
      org,
      s"invitation-$org",
      actor,
      actor
    )
  private def legacy(
    c: Connection,
    s: String,
    actor: UUID,
    org: UUID,
    email: String
  ): Unit =
    execute(
      c,
      s"INSERT INTO $s.invitation (id,identity_value,identity_type,roles,organization_id,created_at,created_by,modified_at,modified_by) VALUES (?,?,'MailAddress',ARRAY['Employee'],?,now(),?,now(),?)",
      UUID.randomUUID(),
      email,
      org,
      actor,
      actor
    )
  private def invitation(
    c: Connection,
    s: String,
    actor: UUID,
    org: UUID,
    email: String,
    first: String = "Anna"
  ): UUID = {
    val id = UUID.randomUUID()
    execute(
      c,
      s"INSERT INTO $s.invitation (id,identity_value,identity_type,roles,organization_id,created_at,created_by,modified_at,modified_by,protocol_version,first_name,last_name,expire_at,token_hash,locale) VALUES (?,?,'MailAddress',ARRAY['Employee'],?,now(),?,now(),?,2,?,'Test',now()+interval '1 day',?,'en')",
      id,
      email,
      org,
      actor,
      actor,
      first,
      Array.fill[Byte](32)(1)
    )
    id
  }
  private def assertViolation(state: String, constraint: String)(action: => Any): Unit = Try(action) match {
    case Failure(error: PSQLException) =>
      require(error.getSQLState == state, s"Expected SQLSTATE $state, got ${error.getSQLState}")
      val expectedConflict = if (constraint == "invitation_pending_email_key") Some(InvitationOnboardingRepository.PendingEmailConflict) else None
      require(InvitationOnboardingRepository.conflict(error) == expectedConflict)
      require(
        Option(error.getServerErrorMessage).map(_.getConstraint).contains(constraint),
        s"Expected $constraint, got ${Option(error.getServerErrorMessage).map(_.getConstraint)}"
      )
    case Failure(error) => throw new AssertionError(s"Expected PostgreSQL violation, got ${error.getClass.getName}", error)
    case Success(_)     => throw new AssertionError(s"Expected $constraint violation, but statement succeeded")
  }

  private def execute(
    connection: Connection,
    sql: String,
    values: AnyRef*
  ): Unit = {
    val statement = connection.prepareStatement(sql)
    try {
      values.zipWithIndex.foreach { case (value, index) => statement.setObject(index + 1, value) }
      require(statement.executeUpdate() == 1)
    } finally statement.close()
  }

  private def count(
    connection: Connection,
    sql: String,
    values: AnyRef*
  ): Long = {
    val statement = connection.prepareStatement(sql)
    try {
      values.zipWithIndex.foreach { case (value, index) => statement.setObject(index + 1, value) }
      val result = statement.executeQuery()
      try { require(result.next()); result.getLong(1) }
      finally result.close()
    } finally statement.close()
  }

  private def flyway(
    url: String,
    target: Option[String] = None,
    schema: String,
    location: String
  ): Flyway = {
    val config = Flyway.configure().dataSource(url, dbUser, dbSecret).locations(location).schemas(schema, "quartz")
    target.fold(config.load())(version => config.target(MigrationVersion.fromVersion(version)).load())
  }

  private def withTemporaryDatabase[T](test: String => T): T = {
    val name = s"ct208_invitation_${UUID.randomUUID().toString.replace("-", "")}"
    require(name.matches("ct208_invitation_[0-9a-f]{32}"))
    withConnection(baseUrl) { connection =>
      val statement = connection.createStatement()
      try { val _ = statement.executeUpdate(s"CREATE DATABASE \"$name\""); () }
      finally statement.close()
    }
    try {
      val uri         = URI.create(baseUrl.stripPrefix("jdbc:"))
      val databaseUri = new URI(uri.getScheme, uri.getUserInfo, uri.getHost, uri.getPort, s"/$name", uri.getQuery, uri.getFragment)
      test(s"jdbc:$databaseUri")
    } finally
      withConnection(baseUrl) { connection =>
        val statement = connection.createStatement()
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
