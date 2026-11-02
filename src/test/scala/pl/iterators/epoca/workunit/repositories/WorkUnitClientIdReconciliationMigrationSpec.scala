package pl.iterators.epoca.workunit.repositories

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.specs2.mutable.SpecificationLike

import java.net.URI
import java.sql.{Connection, DriverManager, ResultSet}
import java.util.UUID
import scala.util.control.NonFatal

class WorkUnitClientIdReconciliationMigrationSpec extends SpecificationLike {

  private val baseDatabaseUrl   = sys.env.getOrElse("TEST_DB_URL", "jdbc:postgresql://localhost:5432/epoca")
  private val databaseUser      = sys.env.getOrElse("TEST_DB_USER", "postgres")
  private val databasePassword  = sys.env.getOrElse("TEST_DB_PASSWORD", "postgres")
  private val migrationLocation = sys.env.getOrElse("CT430_MIGRATION_LOCATION", "classpath:db/migration")

  private case class SeedIds(
    userId: UUID,
    organizationId: UUID,
    correctClientId: UUID,
    wrongClientId: UUID,
    projectId: UUID,
    workUnitId: UUID)

  private object SeedIds {
    def random: SeedIds = {
      val correctClientId = UUID.randomUUID()
      val wrongClientId   = new UUID(correctClientId.getMostSignificantBits, correctClientId.getLeastSignificantBits ^ 1L)
      SeedIds(
        userId = UUID.randomUUID(),
        organizationId = UUID.randomUUID(),
        correctClientId = correctClientId,
        wrongClientId = wrongClientId,
        projectId = UUID.randomUUID(),
        workUnitId = UUID.randomUUID()
      )
    }
  }

  "V19 work unit client ID reconciliation" should {
    "repair a client mismatch that exists at V18" in withTemporaryDatabase { databaseUrl =>
      val ids = SeedIds.random

      val flywayAtV18 = configuredFlyway(databaseUrl, Some(MigrationVersion.fromVersion("18")))
      val _           = flywayAtV18.migrate()
      seedMismatch(databaseUrl, ids)

      mismatchCount(databaseUrl) shouldEqual 1L

      val flywayAtV19 = configuredFlyway(databaseUrl, Some(MigrationVersion.fromVersion("19")))
      val _           = flywayAtV19.migrate()

      Option(flywayAtV19.info().current()).map(_.getVersion.toString) shouldEqual Some("19")
      migrationHistoryVersion(databaseUrl) shouldEqual "19"
      rawLegacyClientId(databaseUrl, ids.workUnitId) shouldEqual Some(ids.correctClientId.toString)
      mismatchCount(databaseUrl) shouldEqual 0L
    }
  }

  private def configuredFlyway(databaseUrl: String, target: Option[MigrationVersion]): Flyway = {
    val configuration = Flyway
      .configure()
      .dataSource(databaseUrl, databaseUser, databasePassword)
      .locations(migrationLocation)
      .schemas("public", "quartz")

    target match {
      case Some(version) => configuration.target(version).load()
      case None          => configuration.load()
    }
  }

  private def withTemporaryDatabase[T](test: String => T): T = {
    val databaseName = s"ct430_work_unit_reconciliation_${UUID.randomUUID().toString.replace("-", "")}"
    val identifier   = validatedDatabaseIdentifier(databaseName)
    var created      = false

    try {
      executeAdminStatement(s"CREATE DATABASE $identifier")
      created = true
      test(databaseUrl(databaseName))
    } finally
      if (created) {
        executeAdminStatement(s"DROP DATABASE $identifier WITH (FORCE)")
      }
  }

  private def validatedDatabaseIdentifier(databaseName: String): String = {
    require(databaseName.matches("^ct430_work_unit_reconciliation_[0-9a-f]{32}$"), s"Unsafe temporary database name: $databaseName")
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

  private def seedMismatch(databaseUrl: String, ids: SeedIds): Unit =
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
            VALUES (?, 'Migration test', 'ct430-migration-test', CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, ?, NULL)
          """,
          ids.organizationId,
          ids.userId,
          ids.userId
        )
        insertClient(connection, ids.correctClientId, "Correct client", ids)
        insertClient(connection, ids.wrongClientId, "Wrong client", ids)
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
          ids.correctClientId,
          ids.userId,
          ids.userId
        )
        insert(
          connection,
          """
            INSERT INTO public.work_unit (
              id, organization_id, user_id, project_id, date, duration, description,
              created_at, modified_at, archived_at, task_id, client_id, state
            )
            VALUES (
              ?, ?, ?, ?, DATE '2026-01-01', INTERVAL '1 hour', 'Migration reconciliation test',
              CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL, NULL, ?, 'Active'
            )
          """,
          ids.workUnitId,
          ids.organizationId,
          ids.userId,
          ids.projectId,
          ids.wrongClientId
        )
        connection.commit()
      } catch {
        case NonFatal(error) =>
          connection.rollback()
          throw error
      }
    }

  private def insertClient(
    connection: Connection,
    clientId: UUID,
    name: String,
    ids: SeedIds
  ): Unit =
    insert(
      connection,
      """
        INSERT INTO public.client (
          id, name, color, organization_id, created_at, created_by, modified_at,
          modified_by, archived_at, archived_by, logo, type, texture
        )
        VALUES (?, ?, '#000000', ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, ?, NULL, NULL, '', 'External', '')
      """,
      clientId,
      name,
      ids.organizationId,
      ids.userId,
      ids.userId
    )

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

  private def migrationHistoryVersion(databaseUrl: String): String =
    withConnection(databaseUrl) { connection =>
      val statement = connection.prepareStatement("""
          SELECT version
          FROM public.flyway_schema_history
          WHERE success
          ORDER BY installed_rank DESC
          LIMIT 1
        """)
      try {
        val resultSet = statement.executeQuery()
        try singleResult(resultSet)(_.getString(1))
        finally resultSet.close()
      } finally statement.close()
    }

  private def rawLegacyClientId(databaseUrl: String, workUnitId: UUID): Option[String] =
    withConnection(databaseUrl) { connection =>
      val statement = connection.prepareStatement("SELECT client_id::text FROM public.work_unit WHERE id = ?")
      try {
        statement.setObject(1, workUnitId)
        val resultSet = statement.executeQuery()
        try Option(singleResult(resultSet)(_.getString(1)))
        finally resultSet.close()
      } finally statement.close()
    }

  private def mismatchCount(databaseUrl: String): Long =
    withConnection(databaseUrl) { connection =>
      val statement = connection.prepareStatement("""
          SELECT COUNT(*)
          FROM public.work_unit wu
          JOIN public.project p ON p.id = wu.project_id
          WHERE wu.client_id IS DISTINCT FROM p.client_id
        """)
      try {
        val resultSet = statement.executeQuery()
        try singleResult(resultSet)(_.getLong(1))
        finally resultSet.close()
      } finally statement.close()
    }

  private def singleResult[T](resultSet: ResultSet)(read: ResultSet => T): T = {
    require(resultSet.next(), "Expected one result row")
    val value = read(resultSet)
    require(!resultSet.next(), "Expected exactly one result row")
    value
  }

  private def withConnection[T](databaseUrl: String)(use: Connection => T): T = {
    val connection = DriverManager.getConnection(databaseUrl, databaseUser, databasePassword)
    try use(connection)
    finally connection.close()
  }
}
