package pl.iterators.epoca.workunit.repositories

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.specs2.mutable.SpecificationLike

import java.net.URI
import java.sql.{Connection, DriverManager, ResultSet}
import java.time.OffsetDateTime
import java.util.UUID
import scala.util.{Failure, Success, Try}

class WorkUnitIdempotencyMigrationSpec extends SpecificationLike {

  private val baseDatabaseUrl   = sys.env.getOrElse("TEST_DB_URL", "jdbc:postgresql://localhost:5432/epoca")
  private val databaseUser      = sys.env.getOrElse("TEST_DB_USER", "postgres")
  private val databasePassword  = sys.env.getOrElse("TEST_DB_PASSWORD", "postgres")
  private val migrationLocation = sys.env.getOrElse("CT528_MIGRATION_LOCATION", "classpath:db/migration")

  private case class FixtureIds(userId: UUID, organizationId: UUID)

  private case class LedgerRow(
    operation: String,
    targetWorkUnitId: Option[UUID],
    state: String,
    outcomeStatus: Option[Int],
    canonicalRequest: Option[String],
    outcomeBody: Option[String],
    resultWorkUnitId: Option[UUID],
    responseExpiresAt: Option[OffsetDateTime],
    idempotencyKey: UUID = UUID.randomUUID())

  private case class Column(
    name: String,
    dataType: String,
    nullable: Boolean)

  private case class ForeignKey(
    childColumn: String,
    parentTable: String,
    parentColumn: String)

  private case class PartialIndex(
    keyColumns: Seq[String],
    keyAttributeCount: Int,
    allAttributeCount: Int,
    predicate: String,
    valid: Boolean,
    unique: Boolean)

  "V20 work unit idempotency ledger" should {
    "create the persistent idempotency contract" in withTemporaryDatabase { databaseUrl =>
      val flyway = Flyway
        .configure()
        .dataSource(databaseUrl, databaseUser, databasePassword)
        .locations(migrationLocation)
        .schemas("public", "quartz")
        .target(MigrationVersion.fromVersion("20"))
        .load()

      val _   = flyway.migrate()
      val ids = seedFixtures(databaseUrl)

      uniqueConstraintColumns(databaseUrl) shouldEqual List("organization_id", "actor_id", "idempotency_key")
      columnContract(databaseUrl) shouldEqual Seq(
        Column("claim_id", "uuid", false),
        Column("organization_id", "uuid", false),
        Column("actor_id", "uuid", false),
        Column("idempotency_key", "uuid", false),
        Column("operation", "text", false),
        Column("target_work_unit_id", "uuid", true),
        Column("fingerprint_version", "integer", false),
        Column("canonical_request", "jsonb", true),
        Column("fingerprint", "text", false),
        Column("state", "text", false),
        Column("outcome_status", "integer", true),
        Column("outcome_body", "jsonb", true),
        Column("result_work_unit_id", "uuid", true),
        Column("created_at", "timestamp with time zone", false),
        Column("response_expires_at", "timestamp with time zone", true)
      )
      foreignKeys(databaseUrl) shouldEqual Seq(ForeignKey("actor_id", "user", "id"), ForeignKey("organization_id", "organization", "id"))
      flywayCurrentVersion(databaseUrl) shouldEqual "20"
      normalizedStateCheckDefinition(
        databaseUrl
      ) shouldEqual "CHECK ((state = ANY (ARRAY['Processing'::text, 'Completed'::text, 'Tombstone'::text])))"
      partialIndex(databaseUrl) shouldEqual Some(
        PartialIndex(Seq("response_expires_at"), 1, 1, "(state = 'Completed'::text)", valid = true, unique = false)
      )

      insertLedgerRow(databaseUrl, ids, LedgerRow("Create", None, "Processing", None, Some("{}"), None, None, None))
      insertLedgerRow(databaseUrl, ids, LedgerRow("Create", None, "Processing", None, None, None, None, None))
      insertLedgerRow(databaseUrl, ids, LedgerRow("Update", Some(UUID.randomUUID()), "Processing", None, Some("{}"), None, None, None))
      insertLedgerRow(
        databaseUrl,
        ids,
        LedgerRow("Create", None, "Completed", Some(200), Some("{}"), Some("{}"), Some(UUID.randomUUID()), Some(expiry()))
      )
      insertLedgerRow(databaseUrl, ids, LedgerRow("Create", None, "Completed", Some(200), Some("{}"), Some("{}"), None, Some(expiry())))
      insertLedgerRow(
        databaseUrl,
        ids,
        LedgerRow("Update", Some(UUID.randomUUID()), "Tombstone", Some(204), None, None, Some(UUID.randomUUID()), Some(expiry()))
      )
      insertLedgerRow(databaseUrl, ids, LedgerRow("Update", Some(UUID.randomUUID()), "Tombstone", Some(204), None, None, None, Some(expiry())))

      assertCheckViolation(
        insertLedgerRow(databaseUrl, ids, LedgerRow("Create", None, "Unknown", None, None, None, None, None)),
        "work_unit_idempotency_state_check"
      )
      assertCheckViolation(
        insertLedgerRow(databaseUrl, ids, LedgerRow("Create", Some(UUID.randomUUID()), "Processing", None, Some("{}"), None, None, None)),
        "work_unit_idempotency_command_target_check"
      )
      assertCheckViolation(
        insertLedgerRow(databaseUrl, ids, LedgerRow("Update", None, "Processing", None, Some("{}"), None, None, None)),
        "work_unit_idempotency_command_target_check"
      )
      assertCheckViolation(insertLedgerRow(databaseUrl, ids, LedgerRow("Create", None, "Processing", Some(200), Some("{}"), None, None, None)))
      assertCheckViolation(insertLedgerRow(databaseUrl, ids, LedgerRow("Create", None, "Processing", None, Some("{}"), Some("{}"), None, None)))
      assertCheckViolation(insertLedgerRow(databaseUrl, ids, LedgerRow("Create", None, "Processing", None, Some("{}"), None, None, Some(expiry()))))
      assertCheckViolation(
        insertLedgerRow(databaseUrl, ids, LedgerRow("Create", None, "Processing", None, Some("{}"), None, Some(UUID.randomUUID()), None))
      )
      assertCheckViolation(
        insertLedgerRow(databaseUrl, ids, LedgerRow("Create", None, "Completed", None, Some("{}"), Some("{}"), None, Some(expiry())))
      )
      assertCheckViolation(
        insertLedgerRow(databaseUrl, ids, LedgerRow("Create", None, "Completed", Some(200), Some("{}"), None, None, Some(expiry())))
      )
      assertCheckViolation(insertLedgerRow(databaseUrl, ids, LedgerRow("Create", None, "Completed", Some(200), Some("{}"), Some("{}"), None, None)))
      assertCheckViolation(insertLedgerRow(databaseUrl, ids, LedgerRow("Create", None, "Tombstone", None, None, None, None, Some(expiry()))))
      assertCheckViolation(insertLedgerRow(databaseUrl, ids, LedgerRow("Create", None, "Tombstone", Some(204), None, None, None, None)))
      assertCheckViolation(
        insertLedgerRow(databaseUrl, ids, LedgerRow("Create", None, "Tombstone", Some(204), None, Some("{}"), None, Some(expiry())))
      )
      assertCheckViolation(
        insertLedgerRow(databaseUrl, ids, LedgerRow("Create", None, "Tombstone", Some(204), Some("{}"), None, None, Some(expiry())))
      )

      val duplicateKey = UUID.randomUUID()
      insertLedgerRow(databaseUrl, ids, LedgerRow("Create", None, "Processing", None, None, None, None, None, duplicateKey))
      assertUniqueViolation(insertLedgerRow(databaseUrl, ids, LedgerRow("Create", None, "Processing", None, None, None, None, None, duplicateKey)))
      true should beTrue
    }
  }

  private def uniqueConstraintColumns(databaseUrl: String): List[String] =
    withConnection(databaseUrl) { connection =>
      val statement = connection.prepareStatement("""
          SELECT attribute.attname
          FROM pg_constraint constraint_row
          JOIN unnest(constraint_row.conkey) WITH ORDINALITY AS key_columns(attribute_number, ordinal_position) ON TRUE
          JOIN pg_attribute attribute
            ON attribute.attrelid = constraint_row.conrelid AND attribute.attnum = key_columns.attribute_number
          WHERE constraint_row.conrelid = 'public.work_unit_idempotency'::regclass
            AND constraint_row.conname = 'work_unit_idempotency_scope_key_unique'
            AND constraint_row.contype = 'u'
          ORDER BY key_columns.ordinal_position
        """)
      try rows(statement.executeQuery())(_.getString("attname"))
      finally statement.close()
    }

  private def columnContract(databaseUrl: String): Seq[Column] =
    withConnection(databaseUrl) { connection =>
      val statement = connection.prepareStatement("""
          SELECT column_name, data_type, is_nullable
          FROM information_schema.columns
          WHERE table_schema = 'public'
            AND table_name = 'work_unit_idempotency'
          ORDER BY ordinal_position
        """)
      try rows(statement.executeQuery())(resultSet => Column(resultSet.getString(1), resultSet.getString(2), resultSet.getString(3) == "YES"))
      finally statement.close()
    }

  private def foreignKeys(databaseUrl: String): Seq[ForeignKey] =
    withConnection(databaseUrl) { connection =>
      val statement = connection.prepareStatement("""
          SELECT child_column.attname, parent_table.relname, parent_column.attname
          FROM pg_constraint constraint_row
          JOIN pg_class parent_table ON parent_table.oid = constraint_row.confrelid
          JOIN LATERAL unnest(constraint_row.conkey) WITH ORDINALITY AS child_key(attribute_number, ordinal_position) ON TRUE
          JOIN LATERAL unnest(constraint_row.confkey) WITH ORDINALITY AS parent_key(attribute_number, ordinal_position)
            ON parent_key.ordinal_position = child_key.ordinal_position
          JOIN pg_attribute child_column
            ON child_column.attrelid = constraint_row.conrelid AND child_column.attnum = child_key.attribute_number
          JOIN pg_attribute parent_column
            ON parent_column.attrelid = constraint_row.confrelid AND parent_column.attnum = parent_key.attribute_number
          WHERE constraint_row.conrelid = 'public.work_unit_idempotency'::regclass
            AND constraint_row.contype = 'f'
          ORDER BY child_column.attname
        """)
      try rows(statement.executeQuery())(resultSet => ForeignKey(resultSet.getString(1), resultSet.getString(2), resultSet.getString(3)))
      finally statement.close()
    }

  private def flywayCurrentVersion(databaseUrl: String): String =
    withConnection(databaseUrl) { connection =>
      val statement = connection.prepareStatement("""
          SELECT version
          FROM public.flyway_schema_history
          WHERE success AND version IS NOT NULL
          ORDER BY installed_rank DESC
          LIMIT 1
        """)
      try {
        val resultSet = statement.executeQuery()
        try {
          require(resultSet.next(), "Expected one migration history row")
          resultSet.getString(1)
        } finally resultSet.close()
      } finally statement.close()
    }

  private def normalizedStateCheckDefinition(databaseUrl: String): String = {
    val definition = singleColumn(
      databaseUrl,
      """
        SELECT pg_get_constraintdef(constraint_row.oid)
        FROM pg_constraint constraint_row
        WHERE constraint_row.conrelid = 'public.work_unit_idempotency'::regclass
          AND constraint_row.conname = ?
          AND constraint_row.contype = 'c'
      """,
      "work_unit_idempotency_state_check"
    )
    definition.trim.replaceAll("\\s+", " ")
  }

  private def partialIndex(databaseUrl: String): Option[PartialIndex] =
    withConnection(databaseUrl) { connection =>
      val statement = connection.prepareStatement("""
          SELECT
            string_agg(attribute.attname, ',' ORDER BY key_columns.ordinal_position),
            index_definition.indnkeyatts,
            index_definition.indnatts,
            pg_get_expr(index_definition.indpred, index_definition.indrelid),
            index_definition.indisvalid,
            index_definition.indisunique
          FROM pg_index index_definition
          JOIN pg_class index_relation ON index_relation.oid = index_definition.indexrelid
          JOIN unnest(index_definition.indkey) WITH ORDINALITY AS key_columns(attribute_number, ordinal_position)
            ON key_columns.ordinal_position <= index_definition.indnkeyatts
          JOIN pg_attribute attribute
            ON attribute.attrelid = index_definition.indrelid AND attribute.attnum = key_columns.attribute_number
          WHERE index_definition.indrelid = 'public.work_unit_idempotency'::regclass
            AND index_relation.relname = 'work_unit_idempotency_expired_response_idx'
          GROUP BY
            index_definition.indnkeyatts,
            index_definition.indnatts,
            index_definition.indpred,
            index_definition.indrelid,
            index_definition.indisvalid,
            index_definition.indisunique
        """)
      try {
        val resultSet = statement.executeQuery()
        try
          if (resultSet.next())
            Some(
              PartialIndex(
                resultSet.getString(1).split(',').toSeq,
                resultSet.getInt(2),
                resultSet.getInt(3),
                resultSet.getString(4),
                resultSet.getBoolean(5),
                resultSet.getBoolean(6)
              )
            )
          else None
        finally resultSet.close()
      } finally statement.close()
    }

  private def seedFixtures(databaseUrl: String): FixtureIds = {
    val ids = FixtureIds(UUID.randomUUID(), UUID.randomUUID())
    withConnection(databaseUrl) { connection =>
      insert(
        connection,
        """
          INSERT INTO public."user" (id, first_name, last_name, avatar, color, is_super_admin, created_at, modified_at, archived_at)
          VALUES (?, 'Migration', 'Test', NULL, NULL, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL)
        """,
        ids.userId
      )
      insert(
        connection,
        """
          INSERT INTO public.organization (id, name, slug, created_at, created_by, modified_at, modified_by, archived_at)
          VALUES (?, 'Migration test', 'ct528-migration-test', CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, ?, NULL)
        """,
        ids.organizationId,
        ids.userId,
        ids.userId
      )
    }
    ids
  }

  private def insertLedgerRow(
    databaseUrl: String,
    ids: FixtureIds,
    row: LedgerRow
  ): Unit =
    withConnection(databaseUrl) { connection =>
      val statement = connection.prepareStatement("""
          INSERT INTO public.work_unit_idempotency (
            claim_id, organization_id, actor_id, idempotency_key, operation, target_work_unit_id,
            fingerprint_version, canonical_request, fingerprint, state, outcome_status, outcome_body,
            result_work_unit_id, created_at, response_expires_at
          )
          VALUES (?, ?, ?, ?, ?, ?, 1, ?::jsonb, 'fingerprint', ?, ?, ?::jsonb, ?, CURRENT_TIMESTAMP, ?)
        """)
      try {
        statement.setObject(1, UUID.randomUUID())
        statement.setObject(2, ids.organizationId)
        statement.setObject(3, ids.userId)
        statement.setObject(4, row.idempotencyKey)
        statement.setString(5, row.operation)
        statement.setObject(6, row.targetWorkUnitId.orNull, java.sql.Types.OTHER)
        statement.setObject(7, row.canonicalRequest.orNull, java.sql.Types.OTHER)
        statement.setString(8, row.state)
        row.outcomeStatus.fold(statement.setNull(9, java.sql.Types.INTEGER))(statement.setInt(9, _))
        statement.setObject(10, row.outcomeBody.orNull, java.sql.Types.OTHER)
        statement.setObject(11, row.resultWorkUnitId.orNull, java.sql.Types.OTHER)
        row.responseExpiresAt.fold(statement.setNull(12, java.sql.Types.TIMESTAMP_WITH_TIMEZONE))(statement.setObject(12, _))
        val insertedRows = statement.executeUpdate()
        require(insertedRows == 1, s"Expected one inserted row, got $insertedRows")
      } finally statement.close()
    }

  private def assertCheckViolation(action: => Unit, expectedConstraint: String = "work_unit_idempotency_terminal_check"): Unit =
    Try(action) match {
      case Failure(error: org.postgresql.util.PSQLException) =>
        require(error.getSQLState == "23514", s"Expected PostgreSQL check violation SQLSTATE 23514, got ${error.getSQLState}")
        require(
          Option(error.getServerErrorMessage).map(_.getConstraint).contains(expectedConstraint),
          s"Expected check constraint $expectedConstraint, got ${Option(error.getServerErrorMessage).map(_.getConstraint)}"
        )
      case Failure(error) => throw new AssertionError(s"Expected PostgreSQL check violation, got ${error.getClass.getName}", error)
      case Success(_)     => throw new AssertionError("Expected PostgreSQL check violation, but insert succeeded")
    }

  private def assertUniqueViolation(action: => Unit): Unit =
    Try(action) match {
      case Failure(error: org.postgresql.util.PSQLException) =>
        require(error.getSQLState == "23505", s"Expected PostgreSQL unique violation SQLSTATE 23505, got ${error.getSQLState}")
        require(
          Option(error.getServerErrorMessage).map(_.getConstraint).contains("work_unit_idempotency_scope_key_unique"),
          s"Expected unique constraint work_unit_idempotency_scope_key_unique, got ${Option(error.getServerErrorMessage).map(_.getConstraint)}"
        )
      case Failure(error) => throw new AssertionError(s"Expected PostgreSQL unique violation, got ${error.getClass.getName}", error)
      case Success(_)     => throw new AssertionError("Expected PostgreSQL unique violation, but insert succeeded")
    }

  private def expiry(): OffsetDateTime = OffsetDateTime.now().plusHours(1)

  private def insert(
    connection: Connection,
    sql: String,
    parameters: AnyRef*
  ): Unit = {
    val statement = connection.prepareStatement(sql)
    try {
      parameters.zipWithIndex.foreach { case (value, index) => statement.setObject(index + 1, value) }
      val insertedRows = statement.executeUpdate()
      require(insertedRows == 1, s"Expected one inserted row, got $insertedRows")
    } finally statement.close()
  }

  private def singleColumn(
    databaseUrl: String,
    sql: String,
    parameter: String
  ): String =
    withConnection(databaseUrl) { connection =>
      val statement = connection.prepareStatement(sql)
      try {
        statement.setString(1, parameter)
        val resultSet = statement.executeQuery()
        try {
          require(resultSet.next(), "Expected one result row")
          val value = resultSet.getString(1)
          require(!resultSet.next(), "Expected exactly one result row")
          value
        } finally resultSet.close()
      } finally statement.close()
    }

  private def rows[T](resultSet: ResultSet)(read: ResultSet => T): List[T] = {
    val result = List.newBuilder[T]
    while (resultSet.next()) result += read(resultSet)
    result.result()
  }

  private def withTemporaryDatabase[T](test: String => T): T = {
    val databaseName = s"ct528_work_unit_idempotency_${UUID.randomUUID().toString.replace("-", "")}"
    val identifier   = validatedDatabaseIdentifier(databaseName)
    var created      = false

    try {
      executeAdminStatement(s"CREATE DATABASE $identifier")
      created = true
      test(databaseUrl(databaseName))
    } finally
      if (created) executeAdminStatement(s"DROP DATABASE $identifier WITH (FORCE)")
  }

  private def validatedDatabaseIdentifier(databaseName: String): String = {
    require(databaseName.matches("^ct528_work_unit_idempotency_[0-9a-f]{32}$"), s"Unsafe temporary database name: $databaseName")
    s"\"$databaseName\""
  }

  private def databaseUrl(databaseName: String): String = {
    require(baseDatabaseUrl.startsWith("jdbc:"), s"Unsupported TEST_DB_URL: $baseDatabaseUrl")
    val baseUri = URI.create(baseDatabaseUrl.stripPrefix("jdbc:"))
    require(baseUri.getHost != null, s"Unsupported TEST_DB_URL: $baseDatabaseUrl")
    val databaseUri =
      new URI(baseUri.getScheme, baseUri.getUserInfo, baseUri.getHost, baseUri.getPort, s"/$databaseName", baseUri.getQuery, baseUri.getFragment)
    s"jdbc:$databaseUri"
  }

  private def executeAdminStatement(sql: String): Unit =
    withConnection(baseDatabaseUrl) { connection =>
      val statement = connection.createStatement()
      try {
        val _ = statement.executeUpdate(sql)
      } finally statement.close()
    }

  private def withConnection[T](databaseUrl: String)(use: Connection => T): T = {
    val connection = DriverManager.getConnection(databaseUrl, databaseUser, databasePassword)
    try use(connection)
    finally connection.close()
  }
}
