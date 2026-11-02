package pl.iterators.epoca.workunit.repositories

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.postgresql.util.PSQLException
import org.specs2.mutable.SpecificationLike

import java.net.URI
import java.sql.{Connection, DriverManager, ResultSet}
import java.util.UUID
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

class WorkUnitEntryTypeMigrationSpec extends SpecificationLike {
  sequential

  private val baseDatabaseUrl   = sys.env.getOrElse("TEST_DB_URL", "jdbc:postgresql://localhost:5432/epoca")
  private val databaseUser      = sys.env.getOrElse("TEST_DB_USER", "postgres")
  private val databasePassword  = sys.env.getOrElse("TEST_DB_PASSWORD", "postgres")
  private val migrationLocation = sys.env.getOrElse("CT508_MIGRATION_LOCATION", "classpath:db/migration")

  private case class FixtureIds(
    userId: UUID,
    organizationId: UUID,
    clientId: UUID,
    projectId: UUID)

  private case class Column(
    name: String,
    dataType: String,
    nullable: Boolean,
    default: Option[String])

  "V21 work unit entry type" should {
    "add the entry type contract and backfill legacy work units" in withTemporaryDatabase { databaseUrl =>
      val flywayAtV20 = configuredFlyway(databaseUrl, Some(MigrationVersion.fromVersion("20")))
      val _           = flywayAtV20.migrate()
      val fixture     = seedFixture(databaseUrl)
      val legacyIds = Seq("Active", "Draft", "Archived").map { state =>
        state -> insertWorkUnit(databaseUrl, fixture, state = state, duration = "1 hour", entryType = None)
      }.toMap

      val flywayAtV21 = configuredFlyway(databaseUrl, Some(MigrationVersion.fromVersion("21")))
      val _           = flywayAtV21.migrate()

      flywayCurrentVersion(databaseUrl) shouldEqual "21"
      entryTypeColumn(databaseUrl) shouldEqual Column("entry_type", "text", nullable = false, default = Some("'Worklog'::text"))

      val definitions = checkConstraintDefinitions(databaseUrl)
      definitions.keySet shouldEqual Set("work_unit_entry_type_check", "work_unit_entry_type_duration_check")
      definitions("work_unit_entry_type_check").contains("entry_type = ANY") should beTrue
      definitions("work_unit_entry_type_check").contains("'Worklog'::text") should beTrue
      definitions("work_unit_entry_type_check").contains("'Note'::text") should beTrue
      definitions("work_unit_entry_type_duration_check").contains("duration >= '00:00:01'::interval") should beTrue
      definitions("work_unit_entry_type_duration_check").contains("duration = '00:00:00'::interval") should beTrue

      workUnitEntryTypes(databaseUrl, legacyIds.values.toSeq) shouldEqual legacyIds.values.map(_ -> "Worklog").toMap

      val defaultedWorklogId = insertWorkUnit(databaseUrl, fixture, state = "Active", duration = "1 second", entryType = None)
      workUnitEntryTypes(databaseUrl, Seq(defaultedWorklogId)) shouldEqual Map(defaultedWorklogId -> "Worklog")

      val noteId = insertWorkUnit(databaseUrl, fixture, state = "Active", duration = "0 seconds", entryType = Some("Note"))
      workUnitEntryTypes(databaseUrl, Seq(noteId)) shouldEqual Map(noteId -> "Note")

      assertCheckViolation(
        insertWorkUnit(databaseUrl, fixture, state = "Active", duration = "1 second", entryType = Some("Unknown")),
        "work_unit_entry_type_check"
      )
      assertCheckViolation(
        insertWorkUnit(databaseUrl, fixture, state = "Active", duration = "0 seconds", entryType = Some("Worklog")),
        "work_unit_entry_type_duration_check"
      )
      assertCheckViolation(
        insertWorkUnit(databaseUrl, fixture, state = "Active", duration = "0.5 seconds", entryType = Some("Worklog")),
        "work_unit_entry_type_duration_check"
      )
      assertCheckViolation(
        insertWorkUnit(databaseUrl, fixture, state = "Active", duration = "1 second", entryType = Some("Note")),
        "work_unit_entry_type_duration_check"
      )

      true should beTrue
    }

    "classify a legacy zero-duration work unit as a note" in withTemporaryDatabase { databaseUrl =>
      val flywayAtV20 = configuredFlyway(databaseUrl, Some(MigrationVersion.fromVersion("20")))
      val _           = flywayAtV20.migrate()
      val fixture     = seedFixture(databaseUrl)
      val workUnitId  = insertWorkUnit(databaseUrl, fixture, state = "Active", duration = "0 seconds", entryType = None)

      val _ = configuredFlyway(databaseUrl, Some(MigrationVersion.fromVersion("21"))).migrate()

      flywayCurrentVersion(databaseUrl) shouldEqual "21"
      workUnitEntryTypes(databaseUrl, Seq(workUnitId)) shouldEqual Map(workUnitId -> "Note")
      workUnitHasZeroDuration(databaseUrl, workUnitId) should beTrue
    }
  }

  private def configuredFlyway(databaseUrl: String, target: Option[MigrationVersion]): Flyway = {
    val configuration = Flyway
      .configure()
      .dataSource(databaseUrl, databaseUser, databasePassword)
      .locations(migrationLocation)
      .schemas("public", "quartz")

    target.fold(configuration.load())(version => configuration.target(version).load())
  }

  private def entryTypeColumn(databaseUrl: String): Column =
    withConnection(databaseUrl) { connection =>
      val statement = connection.prepareStatement("""
          SELECT column_name, data_type, is_nullable, column_default
          FROM information_schema.columns
          WHERE table_schema = 'public'
            AND table_name = 'work_unit'
            AND column_name = 'entry_type'
        """)
      try
        singleResult(statement.executeQuery()) { resultSet =>
          Column(
            resultSet.getString("column_name"),
            resultSet.getString("data_type"),
            resultSet.getString("is_nullable") == "YES",
            Option(resultSet.getString("column_default"))
          )
        }
      finally statement.close()
    }

  private def checkConstraintDefinitions(databaseUrl: String): Map[String, String] =
    withConnection(databaseUrl) { connection =>
      val statement = connection.prepareStatement("""
          SELECT constraint_row.conname, pg_get_constraintdef(constraint_row.oid)
          FROM pg_constraint constraint_row
          WHERE constraint_row.conrelid = 'public.work_unit'::regclass
            AND constraint_row.conname IN ('work_unit_entry_type_check', 'work_unit_entry_type_duration_check')
            AND constraint_row.contype = 'c'
          ORDER BY constraint_row.conname
        """)
      try
        rows(statement.executeQuery()) { resultSet =>
          resultSet.getString(1) -> resultSet.getString(2).trim.replaceAll("\\s+", " ")
        }.toMap
      finally statement.close()
    }

  private def flywayCurrentVersion(databaseUrl: String): String =
    withConnection(databaseUrl) { connection =>
      val statement = connection.prepareStatement("""
          SELECT version
          FROM public.flyway_schema_history
          WHERE success
          ORDER BY installed_rank DESC
          LIMIT 1
        """)
      try singleResult(statement.executeQuery())(_.getString(1))
      finally statement.close()
    }

  private def seedFixture(databaseUrl: String): FixtureIds = {
    val ids = FixtureIds(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
    withConnection(databaseUrl) { connection =>
      connection.setAutoCommit(false)
      try {
        insert(
          connection,
          """
            INSERT INTO public."user" (
              id, first_name, last_name, avatar, color, is_super_admin, created_at, modified_at, archived_at
            )
            VALUES (?, 'Migration', 'Test', NULL, NULL, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL)
          """,
          ids.userId
        )
        insert(
          connection,
          """
            INSERT INTO public.organization (
              id, name, slug, created_at, created_by, modified_at, modified_by, archived_at
            )
            VALUES (?, 'Migration test', ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, ?, NULL)
          """,
          ids.organizationId,
          s"ct508-migration-${ids.organizationId}",
          ids.userId,
          ids.userId
        )
        insert(
          connection,
          """
            INSERT INTO public.client (
              id, name, color, organization_id, created_at, created_by, modified_at,
              modified_by, archived_at, archived_by, logo, type, texture
            )
            VALUES (?, 'Migration client', '#000000', ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, ?, NULL, NULL, '', 'External', '')
          """,
          ids.clientId,
          ids.organizationId,
          ids.userId,
          ids.userId
        )
        insert(
          connection,
          """
            INSERT INTO public.project (
              id, name, description, organization_id, client_id, created_at, created_by,
              modified_at, modified_by, archived_at, archived_by
            )
            VALUES (?, 'Migration project', NULL, ?, ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, ?, NULL, NULL)
          """,
          ids.projectId,
          ids.organizationId,
          ids.clientId,
          ids.userId,
          ids.userId
        )
        connection.commit()
      } catch {
        case NonFatal(error) =>
          connection.rollback()
          throw error
      }
    }
    ids
  }

  private def insertWorkUnit(
    databaseUrl: String,
    fixture: FixtureIds,
    state: String,
    duration: String,
    entryType: Option[String]
  ): UUID = {
    val workUnitId = UUID.randomUUID()
    withConnection(databaseUrl) { connection =>
      val (sql, parameters) = entryType match {
        case Some(value) =>
          (
            """
              INSERT INTO public.work_unit (
                id, organization_id, user_id, project_id, date, duration, description,
                created_at, modified_at, archived_at, task_id, client_id, state, entry_type
              )
              VALUES (?, ?, ?, ?, DATE '2026-01-01', ?::interval, 'Migration entry type test',
                CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL, NULL, ?, ?, ?)
            """,
            Seq[AnyRef](workUnitId, fixture.organizationId, fixture.userId, fixture.projectId, duration, fixture.clientId, state, value)
          )
        case None =>
          (
            """
              INSERT INTO public.work_unit (
                id, organization_id, user_id, project_id, date, duration, description,
                created_at, modified_at, archived_at, task_id, client_id, state
              )
              VALUES (?, ?, ?, ?, DATE '2026-01-01', ?::interval, 'Migration entry type test',
                CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL, NULL, ?, ?)
            """,
            Seq[AnyRef](workUnitId, fixture.organizationId, fixture.userId, fixture.projectId, duration, fixture.clientId, state)
          )
      }
      insert(connection, sql, parameters: _*)
    }
    workUnitId
  }

  private def workUnitEntryTypes(databaseUrl: String, workUnitIds: Seq[UUID]): Map[UUID, String] =
    withConnection(databaseUrl) { connection =>
      val placeholders = workUnitIds.map(_ => "?").mkString(", ")
      val statement    = connection.prepareStatement(s"SELECT id, entry_type FROM public.work_unit WHERE id IN ($placeholders)")
      try {
        workUnitIds.zipWithIndex.foreach { case (id, index) => statement.setObject(index + 1, id) }
        rows(statement.executeQuery())(resultSet => resultSet.getObject("id", classOf[UUID]) -> resultSet.getString("entry_type")).toMap
      } finally statement.close()
    }

  private def workUnitHasZeroDuration(databaseUrl: String, workUnitId: UUID): Boolean =
    withConnection(databaseUrl) { connection =>
      val statement = connection.prepareStatement("""
          SELECT duration = INTERVAL '0 seconds'
          FROM public.work_unit
          WHERE id = ?
        """)
      try {
        statement.setObject(1, workUnitId)
        singleResult(statement.executeQuery())(_.getBoolean(1))
      } finally statement.close()
    }

  private def assertCheckViolation(action: => Any, expectedConstraint: String): Unit =
    Try(action) match {
      case Failure(error: PSQLException) =>
        require(error.getSQLState == "23514", s"Expected SQLSTATE 23514, got ${error.getSQLState}")
        require(
          Option(error.getServerErrorMessage).map(_.getConstraint).contains(expectedConstraint),
          s"Expected check constraint $expectedConstraint, got ${Option(error.getServerErrorMessage).map(_.getConstraint)}"
        )
      case Failure(error) => throw new AssertionError(s"Expected PostgreSQL check violation, got ${error.getClass.getName}", error)
      case Success(_)     => throw new AssertionError("Expected PostgreSQL check violation, but insert succeeded")
    }

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

  private def singleResult[T](resultSet: ResultSet)(read: ResultSet => T): T =
    try {
      require(resultSet.next(), "Expected one result row")
      val value = read(resultSet)
      require(!resultSet.next(), "Expected exactly one result row")
      value
    } finally resultSet.close()

  private def rows[T](resultSet: ResultSet)(read: ResultSet => T): List[T] =
    try {
      val result = List.newBuilder[T]
      while (resultSet.next()) result += read(resultSet)
      result.result()
    } finally resultSet.close()

  private def withTemporaryDatabase[T](test: String => T): T = {
    val databaseName = s"ct508_work_unit_entry_type_${UUID.randomUUID().toString.replace("-", "")}"
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
    require(databaseName.matches("^ct508_work_unit_entry_type_[0-9a-f]{32}$"), s"Unsafe temporary database name: $databaseName")
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
