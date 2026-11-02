package pl.iterators.epoca.workunit.repositories

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.postgresql.util.PSQLException
import org.specs2.mutable.SpecificationLike

import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.sql.{Connection, DriverManager, ResultSet}
import java.time.OffsetDateTime
import java.util.{Comparator, UUID}
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

class WorkUnitPmClassificationMigrationSpec extends SpecificationLike {
  sequential

  private val baseDatabaseUrl   = sys.env.getOrElse("TEST_DB_URL", "jdbc:postgresql://localhost:5432/epoca")
  private val databaseUser      = sys.env.getOrElse("TEST_DB_USER", "postgres")
  private val databasePassword  = sys.env.getOrElse("TEST_DB_PASSWORD", "postgres")
  private val migrationLocation = sys.env.getOrElse("CT511_PM_MIGRATION_LOCATION", "classpath:db/migration")

  private val historicalV22Size   = 3233
  private val historicalV22Sha256 = "34e4f12489f39d7b9e0d84818056ebf80970459a2d6ac8bd07ecfc70cbce22a5"
  private val actorFirstName      = " Ada "
  private val actorLastName       = "Łovelace "
  private val actorDisplayName    = s"$actorFirstName $actorLastName"
  private val requiredLegacyLockStatement =
    "LOCK TABLE public.work_unit, public.work_unit_correction_history IN ACCESS EXCLUSIVE MODE;"

  private case class FixtureIds(
    actorId: UUID,
    organizationId: UUID,
    sourceClientId: UUID,
    firstPmClientId: UUID,
    secondPmClientId: UUID,
    sourceProjectId: UUID,
    firstPmProjectId: UUID,
    secondPmProjectId: UUID)

  private case class SourceState(
    projectId: UUID,
    taskId: Option[String],
    duration: String,
    description: String)

  private case class CurrentPmState(
    pmProjectId: Option[UUID],
    pmTaskId: Option[String],
    version: Long,
    lastChangedBy: UUID,
    lastChangedAt: OffsetDateTime)

  private case class HistoryPmState(
    id: UUID,
    workUnitId: UUID,
    version: Long,
    operation: String,
    actorId: UUID,
    actorDisplayName: String,
    changedAt: OffsetDateTime,
    beforePmProjectId: Option[UUID],
    beforePmProjectName: Option[String],
    beforePmClientId: Option[UUID],
    beforePmClientName: Option[String],
    beforePmTaskId: Option[String],
    afterPmProjectId: Option[UUID],
    afterPmProjectName: Option[String],
    afterPmClientId: Option[UUID],
    afterPmClientName: Option[String],
    afterPmTaskId: Option[String])

  private case class OldSnapshot(
    correctedProjectId: Option[UUID],
    taskOverrideSet: Boolean,
    correctedTaskId: Option[String])

  "historical V22" should {
    "retain the exact staged migration bytes" in {
      val bytes = migrationResourceBytes("db/migration/V22__add_work_unit_corrections.sql")

      bytes.length shouldEqual historicalV22Size
      sha256(bytes) shouldEqual historicalV22Sha256
    }
  }

  "V22 and V23 PM classification migrations" should {
    "migrate a clean V1-V21 database without changing source work-unit semantics" in withTemporaryDatabase { databaseUrl =>
      configuredFlyway(databaseUrl, Some(MigrationVersion.fromVersion("21"))).migrate()
      val fixture  = seedFixture(databaseUrl)
      val workUnit = insertWorkUnit(databaseUrl, fixture, 1)
      val before   = sourceState(databaseUrl, workUnit)
      val rowCount = scalarLong(databaseUrl, "SELECT count(*) FROM public.work_unit")

      configuredFlyway(databaseUrl, Some(MigrationVersion.fromVersion("23"))).migrate()

      flywayCurrentVersion(databaseUrl) shouldEqual "23"
      sourceState(databaseUrl, workUnit) shouldEqual before
      scalarLong(databaseUrl, "SELECT count(*) FROM public.work_unit") shouldEqual rowCount
      scalarLong(databaseUrl, "SELECT count(*) FROM public.work_unit_pm_classification") shouldEqual 0L
      scalarLong(databaseUrl, "SELECT count(*) FROM public.work_unit_pm_classification_history") shouldEqual 0L
      successfulRepeatableCount(databaseUrl, historicalRepeatableName) shouldEqual 1L
      assertFinalSchema(databaseUrl)
      true should beTrue
    }

    "upgrade a V22 database with the historical repeatable migration recorded by Flyway" in withTemporaryDatabase { databaseUrl =>
      withHistoricalCorrectionMigrations { historicalLocation =>
        configuredHistoricalFlyway(databaseUrl, Some(MigrationVersion.fromVersion("22")), historicalLocation).migrate()
      }

      flywayCurrentVersion(databaseUrl) shouldEqual "22"
      successfulRepeatableCount(databaseUrl, historicalRepeatableName) shouldEqual 1L
      indexDefinitions(databaseUrl).keySet should contain("work_unit_effective_project_idx")

      configuredFlyway(databaseUrl, Some(MigrationVersion.fromVersion("23"))).migrate()

      flywayCurrentVersion(databaseUrl) shouldEqual "23"
      successfulRepeatableCount(databaseUrl, historicalRepeatableName) shouldEqual 2L
      indexDefinitions(databaseUrl).keySet should not contain "work_unit_effective_project_idx"
      assertFinalSchema(databaseUrl)
      true should beTrue
    }

    "block legacy correction writers before copy and verification until the migration transaction ends" in withTemporaryDatabase { databaseUrl =>
      configuredFlyway(databaseUrl, Some(MigrationVersion.fromVersion("22"))).migrate()
      val fixture  = seedFixture(databaseUrl)
      val workUnit = insertWorkUnit(databaseUrl, fixture, 1)

      withConnection(databaseUrl) { migrationConnection =>
        withConnection(databaseUrl) { writerConnection =>
          migrationConnection.setAutoCommit(false)
          writerConnection.setAutoCommit(false)
          try {
            executeOnConnection(migrationConnection, requiredLegacyLockStatement)
            accessExclusiveLocks(migrationConnection) shouldEqual Set("work_unit", "work_unit_correction_history")

            setLocalLockTimeout(writerConnection)
            assertLockTimeout(updateLegacyCorrection(writerConnection, fixture, workUnit))
            writerConnection.rollback()

            setLocalLockTimeout(writerConnection)
            assertLockTimeout(insertLegacyCorrectionHistory(writerConnection, fixture, workUnit, UUID.randomUUID()))
            writerConnection.rollback()

            migrationConnection.rollback()

            setLocalLockTimeout(writerConnection)
            writeLegacyCorrection(writerConnection, fixture, workUnit, UUID.randomUUID())
            writerConnection.commit()
          } finally {
            val _ = Try(writerConnection.rollback())
            val _ = Try(migrationConnection.rollback())
          }
        }
      }

      scalarLong(databaseUrl, "SELECT count(*) FROM public.work_unit WHERE correction_version = 1") shouldEqual 1L
      scalarLong(databaseUrl, "SELECT count(*) FROM public.work_unit_correction_history") shouldEqual 1L
      firstSqlStatement("db/migration/V23__migrate_work_unit_corrections_to_pm_classification.sql") shouldEqual requiredLegacyLockStatement
    }

    "map staged V22 current and history states exactly, including inactive Clear rows" in withTemporaryDatabase { databaseUrl =>
      configuredFlyway(databaseUrl, Some(MigrationVersion.fromVersion("22"))).migrate()
      val fixture   = seedFixture(databaseUrl)
      val workUnits = (1 to 6).map(index => insertWorkUnit(databaseUrl, fixture, index))
      val Seq(projectOnly, taskOnly, both, explicitTaskClear, inheritedTask, clearCurrent) = workUnits

      setOldCurrent(databaseUrl, projectOnly, Some(fixture.firstPmProjectId), taskOverrideSet = false, None, 1L, fixture.actorId)
      setOldCurrent(databaseUrl, taskOnly, None, taskOverrideSet = true, Some("PM-TASK-2"), 2L, fixture.actorId)
      setOldCurrent(databaseUrl, both, Some(fixture.firstPmProjectId), taskOverrideSet = true, Some("PM-TASK-3"), 3L, fixture.actorId)
      setOldCurrent(databaseUrl, explicitTaskClear, Some(fixture.firstPmProjectId), taskOverrideSet = true, None, 4L, fixture.actorId)
      setOldCurrent(databaseUrl, inheritedTask, Some(fixture.secondPmProjectId), taskOverrideSet = false, None, 5L, fixture.actorId)
      setOldCurrent(databaseUrl, clearCurrent, None, taskOverrideSet = false, None, 6L, fixture.actorId)

      val historyIds = Seq.fill(3)(UUID.randomUUID())
      insertOldHistory(
        databaseUrl,
        fixture,
        both,
        historyIds(0),
        1L,
        "Replace",
        OldSnapshot(None, taskOverrideSet = false, None),
        OldSnapshot(Some(fixture.firstPmProjectId), taskOverrideSet = false, None)
      )
      insertOldHistory(
        databaseUrl,
        fixture,
        both,
        historyIds(1),
        2L,
        "Clear",
        OldSnapshot(Some(fixture.firstPmProjectId), taskOverrideSet = true, Some("PM-TASK-OLD")),
        OldSnapshot(None, taskOverrideSet = true, None)
      )
      insertOldHistory(
        databaseUrl,
        fixture,
        both,
        historyIds(2),
        3L,
        "Replace",
        OldSnapshot(None, taskOverrideSet = false, None),
        OldSnapshot(Some(fixture.firstPmProjectId), taskOverrideSet = true, Some("PM-TASK-3"))
      )
      execute(
        databaseUrl,
        "CREATE INDEX work_unit_effective_project_idx ON public.work_unit (organization_id, (COALESCE(corrected_project_id, project_id)))"
      )

      val sourceBefore      = workUnits.map(id => id -> sourceState(databaseUrl, id)).toMap
      val oldCurrentCount   = scalarLong(databaseUrl, "SELECT count(*) FROM public.work_unit WHERE correction_version > 0")
      val oldHistoryCount   = scalarLong(databaseUrl, "SELECT count(*) FROM public.work_unit_correction_history")
      val oldMaxHistoryByWu = maxVersionByWorkUnit(databaseUrl, "work_unit_correction_history")

      configuredFlyway(databaseUrl, Some(MigrationVersion.fromVersion("23"))).migrate()

      flywayCurrentVersion(databaseUrl) shouldEqual "23"
      workUnits.map(id => id -> sourceState(databaseUrl, id)).toMap shouldEqual sourceBefore
      scalarLong(databaseUrl, "SELECT count(*) FROM public.work_unit_pm_classification") shouldEqual oldCurrentCount
      scalarLong(databaseUrl, "SELECT count(*) FROM public.work_unit_pm_classification_history") shouldEqual oldHistoryCount
      maxVersionByWorkUnit(databaseUrl, "work_unit_pm_classification_history") shouldEqual oldMaxHistoryByWu
      currentPmStates(databaseUrl) shouldEqual Map(
        projectOnly       -> CurrentPmState(Some(fixture.firstPmProjectId), None, 1L, fixture.actorId, changedAt(1L)),
        taskOnly          -> CurrentPmState(None, Some("PM-TASK-2"), 2L, fixture.actorId, changedAt(2L)),
        both              -> CurrentPmState(Some(fixture.firstPmProjectId), Some("PM-TASK-3"), 3L, fixture.actorId, changedAt(3L)),
        explicitTaskClear -> CurrentPmState(Some(fixture.firstPmProjectId), None, 4L, fixture.actorId, changedAt(4L)),
        inheritedTask     -> CurrentPmState(Some(fixture.secondPmProjectId), None, 5L, fixture.actorId, changedAt(5L)),
        clearCurrent      -> CurrentPmState(None, None, 6L, fixture.actorId, changedAt(6L))
      )
      historyPmStates(databaseUrl) shouldEqual Seq(
        HistoryPmState(
          historyIds(0),
          both,
          1L,
          "Replace",
          fixture.actorId,
          actorDisplayName,
          changedAt(1L),
          None,
          None,
          None,
          None,
          None,
          Some(fixture.firstPmProjectId),
          Some("First PM project"),
          Some(fixture.firstPmClientId),
          Some("First PM client"),
          None
        ),
        HistoryPmState(
          historyIds(1),
          both,
          2L,
          "Clear",
          fixture.actorId,
          actorDisplayName,
          changedAt(2L),
          Some(fixture.firstPmProjectId),
          Some("First PM project"),
          Some(fixture.firstPmClientId),
          Some("First PM client"),
          Some("PM-TASK-OLD"),
          None,
          None,
          None,
          None,
          None
        ),
        HistoryPmState(
          historyIds(2),
          both,
          3L,
          "Replace",
          fixture.actorId,
          actorDisplayName,
          changedAt(3L),
          None,
          None,
          None,
          None,
          None,
          Some(fixture.firstPmProjectId),
          Some("First PM project"),
          Some(fixture.firstPmClientId),
          Some("First PM client"),
          Some("PM-TASK-3")
        )
      )
      assertFinalSchema(databaseUrl)
      assertPmConstraints(databaseUrl, fixture, projectOnly, taskOnly)
      true should beTrue
    }

    "fail V23 transactionally when a staged current task cannot be represented" in withTemporaryDatabase { databaseUrl =>
      configuredFlyway(databaseUrl, Some(MigrationVersion.fromVersion("22"))).migrate()
      val fixture  = seedFixture(databaseUrl)
      val workUnit = insertWorkUnit(databaseUrl, fixture, 1)
      val tooLong  = "\uD83D\uDE80" * 256
      setOldCurrent(databaseUrl, workUnit, None, taskOverrideSet = true, Some(tooLong), 1L, fixture.actorId)

      Try(configuredFlyway(databaseUrl, Some(MigrationVersion.fromVersion("23"))).migrate()) match {
        case Failure(_: org.flywaydb.core.api.FlywayException) => ok
        case Failure(error)                                    => ko(s"Expected FlywayException, got ${error.getClass.getName}: ${error.getMessage}")
        case Success(_)                                        => ko("Expected V23 to reject a 256-code-point current task")
      }

      flywayCurrentVersion(databaseUrl) shouldEqual "22"
      tableExists(databaseUrl, "work_unit_correction_history") should beTrue
      columnNames(databaseUrl, "work_unit") should contain("corrected_task_id")
      tableExists(databaseUrl, "work_unit_pm_classification") should beFalse
      scalarLong(databaseUrl, "SELECT count(*) FROM public.work_unit WHERE correction_version > 0") shouldEqual 1L
      sourceState(databaseUrl, workUnit).taskId shouldEqual Some("SOURCE-1")
    }
  }

  private def assertFinalSchema(databaseUrl: String): Unit = {
    val expectedCurrentColumns = Seq("work_unit_id", "pm_project_id", "pm_task_id", "version", "last_changed_by", "last_changed_at")
    val expectedHistoryColumns = Seq(
      "id",
      "work_unit_id",
      "version",
      "operation",
      "actor_id",
      "actor_display_name",
      "changed_at",
      "before_pm_project_id",
      "before_pm_project_name",
      "before_pm_client_id",
      "before_pm_client_name",
      "before_pm_task_id",
      "after_pm_project_id",
      "after_pm_project_name",
      "after_pm_client_id",
      "after_pm_client_name",
      "after_pm_task_id"
    )
    val expectedCurrentConstraints = Set(
      "work_unit_pm_classification_pkey",
      "work_unit_pm_classification_work_unit_id_fkey",
      "work_unit_pm_classification_pm_project_id_fkey",
      "work_unit_pm_classification_last_changed_by_fkey",
      "work_unit_pm_classification_version_check",
      "work_unit_pm_task_id_valid"
    )
    val expectedHistoryConstraints = Set(
      "work_unit_pm_classification_history_pkey",
      "work_unit_pm_classification_history_work_unit_id_fkey",
      "work_unit_pm_classification_history_actor_id_fkey",
      "work_unit_pm_classification_history_work_unit_id_version_key",
      "work_unit_pm_classification_history_version_check",
      "work_unit_pm_classification_history_operation_check",
      "work_unit_pm_classification_history_before_pm_task_id_valid",
      "work_unit_pm_classification_history_after_pm_task_id_valid"
    )
    val indexes = indexDefinitions(databaseUrl)

    require(tableExists(databaseUrl, "work_unit_pm_classification"), "Expected PM current table")
    require(tableExists(databaseUrl, "work_unit_pm_classification_history"), "Expected PM history table")
    require(!tableExists(databaseUrl, "work_unit_correction_history"), "Old correction history table remains")
    require(columnNames(databaseUrl, "work_unit_pm_classification") == expectedCurrentColumns, "Unexpected PM current columns")
    require(columnNames(databaseUrl, "work_unit_pm_classification_history") == expectedHistoryColumns, "Unexpected PM history columns")
    require(columnNames(databaseUrl, "work_unit").toSet.intersect(oldCorrectionColumns).isEmpty, "Old correction columns remain")
    require(indexes.keySet.contains("work_unit_pm_classification_project_idx"), "Missing PM project index")
    require(indexes.keySet.contains("work_unit_pm_classification_history_version_idx"), "Missing PM history index")
    require(indexes("work_unit_pm_classification_project_idx").contains("(pm_project_id)"), "Wrong PM project index")
    require(indexes("work_unit_pm_classification_history_version_idx").contains("(work_unit_id, version DESC)"), "Wrong PM history index")
    require(!indexes.contains("work_unit_effective_project_idx"), "Old effective-project index remains")
    require(expectedCurrentConstraints.subsetOf(constraintNames(databaseUrl, "work_unit_pm_classification").toSet), "Missing PM current constraints")
    require(
      expectedHistoryConstraints.subsetOf(constraintNames(databaseUrl, "work_unit_pm_classification_history").toSet),
      "Missing PM history constraints"
    )
  }

  private def assertPmConstraints(
    databaseUrl: String,
    fixture: FixtureIds,
    firstWorkUnit: UUID,
    secondWorkUnit: UUID
  ): Unit = {
    assertCheckViolation(
      executePrepared(databaseUrl, "UPDATE public.work_unit_pm_classification SET pm_task_id = ? WHERE work_unit_id = ?", "   ", firstWorkUnit),
      "work_unit_pm_task_id_valid"
    )
    assertCheckViolation(
      executePrepared(databaseUrl, "UPDATE public.work_unit_pm_classification SET version = 0 WHERE work_unit_id = ?", firstWorkUnit),
      "work_unit_pm_classification_version_check"
    )
    assertCheckViolation(
      executePrepared(
        databaseUrl,
        "UPDATE public.work_unit_pm_classification SET pm_task_id = ? WHERE work_unit_id = ?",
        "\uD83D\uDE80" * 256,
        firstWorkUnit
      ),
      "work_unit_pm_task_id_valid"
    )
    assertForeignKeyViolation(
      executePrepared(
        databaseUrl,
        "UPDATE public.work_unit_pm_classification SET pm_project_id = ? WHERE work_unit_id = ?",
        UUID.randomUUID(),
        firstWorkUnit
      ),
      "work_unit_pm_classification_pm_project_id_fkey"
    )
    insertPmHistory(databaseUrl, fixture, secondWorkUnit, UUID.randomUUID(), 99L)
    assertUniqueViolation(
      insertPmHistory(databaseUrl, fixture, secondWorkUnit, UUID.randomUUID(), 99L),
      "work_unit_pm_classification_history_work_unit_id_version_key"
    )
    ()
  }

  private def configuredFlyway(databaseUrl: String, target: Option[MigrationVersion]): Flyway =
    loadFlyway(flywayConfiguration(databaseUrl, migrationLocation), target)

  private def configuredHistoricalFlyway(
    databaseUrl: String,
    target: Option[MigrationVersion],
    location: String
  ): Flyway =
    loadFlyway(
      flywayConfiguration(databaseUrl, location)
        .configuration(java.util.Map.of("flyway.postgresql.transactional.lock", "false")),
      target
    )

  private def flywayConfiguration(databaseUrl: String, location: String): org.flywaydb.core.api.configuration.FluentConfiguration =
    Flyway
      .configure()
      .dataSource(databaseUrl, databaseUser, databasePassword)
      .locations(location)
      .schemas("public", "quartz")

  private def loadFlyway(configuration: org.flywaydb.core.api.configuration.FluentConfiguration, target: Option[MigrationVersion]): Flyway =
    target.fold(configuration.load())(version => configuration.target(version).load())

  private def seedFixture(databaseUrl: String): FixtureIds = {
    val fixture = FixtureIds(
      UUID.randomUUID(),
      UUID.randomUUID(),
      UUID.randomUUID(),
      UUID.randomUUID(),
      UUID.randomUUID(),
      UUID.randomUUID(),
      UUID.randomUUID(),
      UUID.randomUUID()
    )
    withConnection(databaseUrl) { connection =>
      connection.setAutoCommit(false)
      try {
        insert(
          connection,
          """
            INSERT INTO public."user" (
              id, first_name, last_name, avatar, color, is_super_admin, created_at, modified_at, archived_at
            ) VALUES (?, ?, ?, NULL, NULL, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL)
          """,
          fixture.actorId,
          actorFirstName,
          actorLastName
        )
        insert(
          connection,
          """
            INSERT INTO public.organization (
              id, name, slug, created_at, created_by, modified_at, modified_by, archived_at
            ) VALUES (?, 'PM migration', ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, ?, NULL)
          """,
          fixture.organizationId,
          s"ct511-pm-${fixture.organizationId}",
          fixture.actorId,
          fixture.actorId
        )
        insertClient(connection, fixture.sourceClientId, "Source client", fixture)
        insertClient(connection, fixture.firstPmClientId, "First PM client", fixture)
        insertClient(connection, fixture.secondPmClientId, "Second PM client", fixture)
        insertProject(connection, fixture.sourceProjectId, "Source project", fixture.sourceClientId, fixture)
        insertProject(connection, fixture.firstPmProjectId, "First PM project", fixture.firstPmClientId, fixture)
        insertProject(connection, fixture.secondPmProjectId, "Second PM project", fixture.secondPmClientId, fixture)
        connection.commit()
      } catch {
        case NonFatal(error) =>
          connection.rollback()
          throw error
      }
    }
    fixture
  }

  private def insertClient(
    connection: Connection,
    id: UUID,
    name: String,
    fixture: FixtureIds
  ): Unit =
    insert(
      connection,
      """
        INSERT INTO public.client (
          id, name, color, organization_id, created_at, created_by, modified_at,
          modified_by, archived_at, archived_by, logo, type, texture
        ) VALUES (?, ?, '#000000', ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, ?, NULL, NULL, '', 'External', '')
      """,
      id,
      name,
      fixture.organizationId,
      fixture.actorId,
      fixture.actorId
    )

  private def insertProject(
    connection: Connection,
    id: UUID,
    name: String,
    clientId: UUID,
    fixture: FixtureIds
  ): Unit =
    insert(
      connection,
      """
        INSERT INTO public.project (
          id, name, description, organization_id, client_id, created_at, created_by,
          modified_at, modified_by, archived_at, archived_by
        ) VALUES (?, ?, NULL, ?, ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, ?, NULL, NULL)
      """,
      id,
      name,
      fixture.organizationId,
      clientId,
      fixture.actorId,
      fixture.actorId
    )

  private def insertWorkUnit(
    databaseUrl: String,
    fixture: FixtureIds,
    index: Int
  ): UUID = {
    val id = UUID.randomUUID()
    executePrepared(
      databaseUrl,
      """
        INSERT INTO public.work_unit (
          id, organization_id, user_id, project_id, date, duration, description,
          created_at, modified_at, archived_at, task_id, client_id, state, entry_type
        ) VALUES (
          ?, ?, ?, ?, DATE '2026-08-05', (? || ' hours')::interval, ?,
          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL, ?, ?, 'Active', 'Worklog'
        )
      """,
      id,
      fixture.organizationId,
      fixture.actorId,
      fixture.sourceProjectId,
      index.toString,
      s"SOURCE DESCRIPTION $index",
      s"SOURCE-$index",
      fixture.sourceClientId
    )
    id
  }

  private def setOldCurrent(
    databaseUrl: String,
    workUnitId: UUID,
    correctedProjectId: Option[UUID],
    taskOverrideSet: Boolean,
    correctedTaskId: Option[String],
    version: Long,
    actorId: UUID
  ): Unit =
    executePrepared(
      databaseUrl,
      s"""
        UPDATE public.work_unit
        SET corrected_project_id = ?,
            task_id_override_set = ?,
            corrected_task_id = ?,
            work_unit_revision = ?,
            correction_version = ?,
            last_corrected_by = ?,
            last_corrected_at = TIMESTAMPTZ '${changedAt(version)}'
        WHERE id = ?
      """,
      correctedProjectId.orNull,
      Boolean.box(taskOverrideSet),
      correctedTaskId.orNull,
      Long.box(version),
      Long.box(version),
      actorId,
      workUnitId
    )

  private def insertOldHistory(
    databaseUrl: String,
    fixture: FixtureIds,
    workUnitId: UUID,
    id: UUID,
    version: Long,
    operation: String,
    before: OldSnapshot,
    after: OldSnapshot
  ): Unit = {
    val sourceTaskId = "SOURCE-3"
    executePrepared(
      databaseUrl,
      s"""
        INSERT INTO public.work_unit_correction_history (
          id, work_unit_id, version, actor_id, changed_at, source_project_id, source_task_id,
          before_corrected_project_id, before_task_id_override_set, before_corrected_task_id,
          before_effective_project_id, before_effective_task_id,
          after_corrected_project_id, after_task_id_override_set, after_corrected_task_id,
          after_effective_project_id, after_effective_task_id, operation
        ) VALUES (
          ?, ?, ?, ?, TIMESTAMPTZ '${changedAt(version)}', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
        )
      """,
      id,
      workUnitId,
      Long.box(version),
      fixture.actorId,
      fixture.sourceProjectId,
      sourceTaskId,
      before.correctedProjectId.orNull,
      Boolean.box(before.taskOverrideSet),
      before.correctedTaskId.orNull,
      before.correctedProjectId.getOrElse(fixture.sourceProjectId),
      effectiveTask(sourceTaskId, before).orNull,
      after.correctedProjectId.orNull,
      Boolean.box(after.taskOverrideSet),
      after.correctedTaskId.orNull,
      after.correctedProjectId.getOrElse(fixture.sourceProjectId),
      effectiveTask(sourceTaskId, after).orNull,
      operation
    )
  }

  private def effectiveTask(sourceTaskId: String, snapshot: OldSnapshot): Option[String] =
    if (snapshot.taskOverrideSet) snapshot.correctedTaskId else Some(sourceTaskId)

  private def insertPmHistory(
    databaseUrl: String,
    fixture: FixtureIds,
    workUnitId: UUID,
    id: UUID,
    version: Long
  ): Unit =
    executePrepared(
      databaseUrl,
      """
        INSERT INTO public.work_unit_pm_classification_history (
          id, work_unit_id, version, operation, actor_id, actor_display_name, changed_at,
          before_pm_project_id, before_pm_project_name, before_pm_client_id, before_pm_client_name, before_pm_task_id,
          after_pm_project_id, after_pm_project_name, after_pm_client_id, after_pm_client_name, after_pm_task_id
        ) VALUES (?, ?, ?, 'Replace', ?, ?, CURRENT_TIMESTAMP, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL)
      """,
      id,
      workUnitId,
      Long.box(version),
      fixture.actorId,
      actorDisplayName
    )

  private def currentPmStates(databaseUrl: String): Map[UUID, CurrentPmState] =
    withConnection(databaseUrl) { connection =>
      val statement = connection.prepareStatement("""
          SELECT work_unit_id, pm_project_id, pm_task_id, version, last_changed_by, last_changed_at
          FROM public.work_unit_pm_classification
          ORDER BY work_unit_id
        """)
      try
        rows(statement.executeQuery()) { resultSet =>
          resultSet.getObject("work_unit_id", classOf[UUID]) -> CurrentPmState(
            Option(resultSet.getObject("pm_project_id", classOf[UUID])),
            Option(resultSet.getString("pm_task_id")),
            resultSet.getLong("version"),
            resultSet.getObject("last_changed_by", classOf[UUID]),
            resultSet.getObject("last_changed_at", classOf[OffsetDateTime])
          )
        }.toMap
      finally statement.close()
    }

  private def historyPmStates(databaseUrl: String): Seq[HistoryPmState] =
    withConnection(databaseUrl) { connection =>
      val statement = connection.prepareStatement("""
          SELECT *
          FROM public.work_unit_pm_classification_history
          ORDER BY work_unit_id, version
        """)
      try
        rows(statement.executeQuery()) { resultSet =>
          HistoryPmState(
            resultSet.getObject("id", classOf[UUID]),
            resultSet.getObject("work_unit_id", classOf[UUID]),
            resultSet.getLong("version"),
            resultSet.getString("operation"),
            resultSet.getObject("actor_id", classOf[UUID]),
            resultSet.getString("actor_display_name"),
            resultSet.getObject("changed_at", classOf[OffsetDateTime]),
            Option(resultSet.getObject("before_pm_project_id", classOf[UUID])),
            Option(resultSet.getString("before_pm_project_name")),
            Option(resultSet.getObject("before_pm_client_id", classOf[UUID])),
            Option(resultSet.getString("before_pm_client_name")),
            Option(resultSet.getString("before_pm_task_id")),
            Option(resultSet.getObject("after_pm_project_id", classOf[UUID])),
            Option(resultSet.getString("after_pm_project_name")),
            Option(resultSet.getObject("after_pm_client_id", classOf[UUID])),
            Option(resultSet.getString("after_pm_client_name")),
            Option(resultSet.getString("after_pm_task_id"))
          )
        }
      finally statement.close()
    }

  private def sourceState(databaseUrl: String, workUnitId: UUID): SourceState =
    withConnection(databaseUrl) { connection =>
      val statement = connection.prepareStatement("""
          SELECT project_id, task_id, duration::text, description
          FROM public.work_unit
          WHERE id = ?
        """)
      try {
        statement.setObject(1, workUnitId)
        singleResult(statement.executeQuery()) { resultSet =>
          SourceState(
            resultSet.getObject("project_id", classOf[UUID]),
            Option(resultSet.getString("task_id")),
            resultSet.getString("duration"),
            resultSet.getString("description")
          )
        }
      } finally statement.close()
    }

  private def maxVersionByWorkUnit(databaseUrl: String, table: String): Map[UUID, Long] = {
    require(Set("work_unit_correction_history", "work_unit_pm_classification_history").contains(table))
    withConnection(databaseUrl) { connection =>
      val statement = connection.prepareStatement(s"SELECT work_unit_id, max(version) FROM public.$table GROUP BY work_unit_id")
      try rows(statement.executeQuery())(row => row.getObject(1, classOf[UUID]) -> row.getLong(2)).toMap
      finally statement.close()
    }
  }

  private def columnNames(databaseUrl: String, table: String): Seq[String] =
    withConnection(databaseUrl) { connection =>
      val statement = connection.prepareStatement("""
          SELECT column_name
          FROM information_schema.columns
          WHERE table_schema = 'public' AND table_name = ?
          ORDER BY ordinal_position
        """)
      try {
        statement.setString(1, table)
        rows(statement.executeQuery())(_.getString(1))
      } finally statement.close()
    }

  private def constraintNames(databaseUrl: String, table: String): Seq[String] =
    withConnection(databaseUrl) { connection =>
      val statement = connection.prepareStatement("""
          SELECT constraint_row.conname
          FROM pg_constraint constraint_row
          JOIN pg_class relation ON relation.oid = constraint_row.conrelid
          JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
          WHERE namespace.nspname = 'public' AND relation.relname = ?
          ORDER BY constraint_row.conname
        """)
      try {
        statement.setString(1, table)
        rows(statement.executeQuery())(_.getString(1))
      } finally statement.close()
    }

  private def indexDefinitions(databaseUrl: String): Map[String, String] =
    withConnection(databaseUrl) { connection =>
      val statement = connection.prepareStatement("""
          SELECT indexname, indexdef
          FROM pg_indexes
          WHERE schemaname = 'public'
            AND tablename IN ('work_unit', 'work_unit_pm_classification', 'work_unit_pm_classification_history')
        """)
      try rows(statement.executeQuery())(row => row.getString(1) -> row.getString(2)).toMap
      finally statement.close()
    }

  private def tableExists(databaseUrl: String, table: String): Boolean =
    withConnection(databaseUrl) { connection =>
      val statement = connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL")
      try {
        statement.setString(1, s"public.$table")
        singleResult(statement.executeQuery())(_.getBoolean(1))
      } finally statement.close()
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
      try singleResult(statement.executeQuery())(_.getString(1))
      finally statement.close()
    }

  private def scalarLong(databaseUrl: String, sql: String): Long =
    withConnection(databaseUrl) { connection =>
      val statement = connection.prepareStatement(sql)
      try singleResult(statement.executeQuery())(_.getLong(1))
      finally statement.close()
    }

  private def successfulRepeatableCount(databaseUrl: String, script: String): Long =
    withConnection(databaseUrl) { connection =>
      val statement = connection.prepareStatement("""
          SELECT count(*)
          FROM public.flyway_schema_history
          WHERE version IS NULL AND script = ? AND success
        """)
      try {
        statement.setString(1, script)
        singleResult(statement.executeQuery())(_.getLong(1))
      } finally statement.close()
    }

  private def assertCheckViolation(action: => Any, constraint: String): Unit =
    assertSqlStateViolation(action, "23514", constraint)

  private def assertForeignKeyViolation(action: => Any, constraint: String): Unit =
    assertSqlStateViolation(action, "23503", constraint)

  private def assertUniqueViolation(action: => Any, constraint: String): Unit =
    assertSqlStateViolation(action, "23505", constraint)

  private def assertSqlStateViolation(
    action: => Any,
    sqlState: String,
    expectedConstraint: String
  ): Unit =
    Try(action) match {
      case Failure(error: PSQLException) =>
        require(error.getSQLState == sqlState, s"Expected SQLSTATE $sqlState, got ${error.getSQLState}")
        require(
          Option(error.getServerErrorMessage).map(_.getConstraint).contains(expectedConstraint),
          s"Expected constraint $expectedConstraint, got ${Option(error.getServerErrorMessage).map(_.getConstraint)}"
        )
      case Failure(error) => throw new AssertionError(s"Expected PostgreSQL violation, got ${error.getClass.getName}", error)
      case Success(_)     => throw new AssertionError(s"Expected PostgreSQL constraint $expectedConstraint to reject the statement")
    }

  private def insert(
    connection: Connection,
    sql: String,
    parameters: AnyRef*
  ): Unit = {
    val statement = connection.prepareStatement(sql)
    try {
      parameters.zipWithIndex.foreach { case (value, index) => statement.setObject(index + 1, value) }
      require(statement.executeUpdate() == 1, "Expected exactly one inserted row")
    } finally statement.close()
  }

  private def executePrepared(
    databaseUrl: String,
    sql: String,
    parameters: AnyRef*
  ): Unit =
    withConnection(databaseUrl) { connection =>
      val statement = connection.prepareStatement(sql)
      try {
        parameters.zipWithIndex.foreach { case (value, index) => statement.setObject(index + 1, value) }
        val _ = statement.executeUpdate()
      } finally statement.close()
    }

  private def execute(databaseUrl: String, sql: String): Unit =
    withConnection(databaseUrl) { connection =>
      val statement = connection.createStatement()
      try {
        val _ = statement.executeUpdate(sql)
      } finally statement.close()
    }

  private def executeOnConnection(connection: Connection, sql: String): Unit = {
    val statement = connection.createStatement()
    try {
      val _ = statement.execute(sql)
    } finally statement.close()
  }

  private def setLocalLockTimeout(connection: Connection): Unit =
    executeOnConnection(connection, "SET LOCAL lock_timeout = '250ms'")

  private def accessExclusiveLocks(connection: Connection): Set[String] = {
    val statement = connection.prepareStatement("""
        SELECT relation.relname
        FROM pg_locks lock_row
        JOIN pg_class relation ON relation.oid = lock_row.relation
        JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
        WHERE lock_row.pid = pg_backend_pid()
          AND lock_row.granted
          AND lock_row.mode = 'AccessExclusiveLock'
          AND namespace.nspname = 'public'
          AND relation.relname IN ('work_unit', 'work_unit_correction_history')
      """)
    try rows(statement.executeQuery())(_.getString(1)).toSet
    finally statement.close()
  }

  private def writeLegacyCorrection(
    connection: Connection,
    fixture: FixtureIds,
    workUnitId: UUID,
    historyId: UUID
  ): Unit = {
    updateLegacyCorrection(connection, fixture, workUnitId)
    insertLegacyCorrectionHistory(connection, fixture, workUnitId, historyId)
  }

  private def updateLegacyCorrection(
    connection: Connection,
    fixture: FixtureIds,
    workUnitId: UUID
  ): Unit = {
    val update = connection.prepareStatement("""
        UPDATE public.work_unit
        SET corrected_project_id = ?,
            work_unit_revision = 1,
            correction_version = 1,
            last_corrected_by = ?,
            last_corrected_at = ?
        WHERE id = ?
      """)
    try {
      update.setObject(1, fixture.firstPmProjectId)
      update.setObject(2, fixture.actorId)
      update.setObject(3, changedAt(1L))
      update.setObject(4, workUnitId)
      require(update.executeUpdate() == 1, "Expected one legacy current row update")
    } finally update.close()
  }

  private def insertLegacyCorrectionHistory(
    connection: Connection,
    fixture: FixtureIds,
    workUnitId: UUID,
    historyId: UUID
  ): Unit =
    insert(
      connection,
      """
        INSERT INTO public.work_unit_correction_history (
          id, work_unit_id, version, actor_id, changed_at, source_project_id, source_task_id,
          before_corrected_project_id, before_task_id_override_set, before_corrected_task_id,
          before_effective_project_id, before_effective_task_id,
          after_corrected_project_id, after_task_id_override_set, after_corrected_task_id,
          after_effective_project_id, after_effective_task_id, operation
        ) VALUES (?, ?, 1, ?, ?, ?, 'SOURCE-1', NULL, FALSE, NULL, ?, 'SOURCE-1', ?, FALSE, NULL, ?, 'SOURCE-1', 'Replace')
      """,
      historyId,
      workUnitId,
      fixture.actorId,
      changedAt(1L),
      fixture.sourceProjectId,
      fixture.sourceProjectId,
      fixture.firstPmProjectId,
      fixture.firstPmProjectId
    )

  private def assertLockTimeout(action: => Any): Unit =
    Try(action) match {
      case Failure(error: PSQLException) =>
        require(error.getSQLState == "55P03", s"Expected SQLSTATE 55P03, got ${error.getSQLState}")
      case Failure(error) => throw new AssertionError(s"Expected PostgreSQL lock timeout, got ${error.getClass.getName}", error)
      case Success(_)     => throw new AssertionError("Expected legacy writer to time out on the migration lock")
    }

  private def migrationResourceBytes(resourceName: String): Array[Byte] = {
    val stream = Option(getClass.getClassLoader.getResourceAsStream(resourceName))
      .getOrElse(throw new IllegalArgumentException(s"Missing migration resource: $resourceName"))
    try stream.readAllBytes()
    finally stream.close()
  }

  private def firstSqlStatement(resourceName: String): String = {
    val sql       = new String(migrationResourceBytes(resourceName), StandardCharsets.UTF_8)
    val semicolon = sql.indexOf(';')
    require(semicolon >= 0, s"Missing SQL statement terminator in $resourceName")
    sql.substring(0, semicolon + 1)
  }

  private def sha256(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(byte => f"${byte & 0xff}%02x").mkString

  private def changedAt(version: Long): OffsetDateTime =
    OffsetDateTime.parse(f"2026-08-05T10:00:$version%02dZ")

  private val oldCorrectionColumns = Set(
    "corrected_project_id",
    "task_id_override_set",
    "corrected_task_id",
    "work_unit_revision",
    "correction_version",
    "last_corrected_by",
    "last_corrected_at"
  )

  private val historicalRepeatableName = "R__add_work_unit_effective_project_index.sql"
  private val historicalRepeatableSql =
    """CREATE INDEX CONCURRENTLY work_unit_effective_project_idx
      |    ON work_unit (organization_id, (COALESCE(corrected_project_id, project_id)));
      |""".stripMargin
  private val historicalVersionedMigrations = Seq(
    "V1__user.sql",
    "V2__organization.sql",
    "V3__client.sql",
    "V4__mail.sql",
    "V5__refresh_token.sql",
    "V6__project.sql",
    "V7__add_logo_to_client.sql",
    "V8__work_unit.sql",
    "V9__add_task_id_to_work_unit.sql",
    "V10__saml_config.sql",
    "V11__add_role_to_client.sql",
    "V12__add_client_id_to_work_unit.sql",
    "V13__work_unit_state.sql",
    "V14__add_texture_client.sql",
    "V15__add_id_organization_user.sql",
    "V16__work_unit_order.sql",
    "V17__updated_template_db_version.sql",
    "V18__add_missing_foreign_keys.sql",
    "V19__derive_work_unit_client_id_from_project.sql",
    "V20__add_work_unit_idempotency.sql",
    "V21__add_work_unit_entry_type.sql",
    "V22__add_work_unit_corrections.sql"
  )

  private def withHistoricalCorrectionMigrations[T](test: String => T): T = {
    val directory = Files.createTempDirectory("ct511-historical-migrations-")
    try {
      historicalVersionedMigrations.foreach { fileName =>
        val _ = Files.write(directory.resolve(fileName), migrationResourceBytes(s"db/migration/$fileName"))
      }
      val _ = Files.write(directory.resolve(historicalRepeatableName), historicalRepeatableSql.getBytes(StandardCharsets.UTF_8))
      test(s"filesystem:${directory.toAbsolutePath}")
    } finally deleteDirectory(directory)
  }

  private def deleteDirectory(directory: Path): Unit = {
    val paths = Files.walk(directory)
    try
      paths.sorted(Comparator.reverseOrder()).forEach { path =>
        val _ = Files.deleteIfExists(path)
      }
    finally paths.close()
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
    val databaseName = s"ct511_pm_${UUID.randomUUID().toString.replace("-", "")}"
    val identifier   = validatedDatabaseIdentifier(databaseName)
    var created      = false
    try {
      executeAdminStatement(s"CREATE DATABASE $identifier")
      created = true
      test(databaseUrl(databaseName))
    } finally if (created) executeAdminStatement(s"DROP DATABASE $identifier WITH (FORCE)")
  }

  private def validatedDatabaseIdentifier(databaseName: String): String = {
    require(databaseName.matches("^ct511_pm_[0-9a-f]{32}$"), s"Unsafe temporary database name: $databaseName")
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
