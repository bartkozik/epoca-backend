package pl.iterators.epoca.workunit.repositories

import org.flywaydb.core.Flyway
import org.specs2.mutable.SpecificationLike
import pl.iterators.epoca.utils.db.BaseRepository.driver.api._
import pl.iterators.epoca.utils.db.PageSizeAndNumber
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.WorkUnitId

import java.net.URI
import java.sql.{Connection, DriverManager, SQLException}
import java.util.UUID
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Try

class WorkUnitOverrideUndoMigrationSpec extends SpecificationLike {
  sequential

  private val baseUrl      = sys.env.getOrElse("TEST_DB_URL", "jdbc:postgresql://localhost:5432/epoca")
  private val user         = sys.env.getOrElse("TEST_DB_USER", "postgres")
  private val password     = sys.env.getOrElse("TEST_DB_PASSWORD", "postgres")
  private val actor        = UUID.randomUUID()
  private val organization = UUID.randomUUID()
  private val project      = UUID.randomUUID()
  private val client       = UUID.randomUUID()

  "V27 correction ancestry" should {
    "link full corrections across version gaps and Clear while preserving all existing event and current-state data" in database { url =>
      val id               = workUnit(url)
      val a                = history(url, id, 2)
      val b                = history(url, id, 7)
      val clear            = history(url, id, 8, operation = "Clear")
      val legacyOnly       = workUnit(url)
      val legacy           = history(url, legacyOnly, 1, provenance = "LegacyPmSnapshot")
      val mixed            = workUnit(url)
      val fullBeforeLegacy = history(url, mixed, 1)
      val boundary         = history(url, mixed, 2, provenance = "LegacyPmSnapshot")
      val fullAfterLegacy  = history(url, mixed, 5)
      val nextFull         = history(url, mixed, 9)
      val currentOnly      = workUnit(url)
      execute(
        url,
        s"UPDATE work_unit SET override_version=9, override_last_changed_by='$actor', override_last_changed_at='2026-09-17T10:00:00Z' WHERE id='$currentOnly'"
      )
      val originalHistory   = query(url, "SELECT to_jsonb(h)::text FROM work_unit_override_history h ORDER BY id")
      val originalWorkUnits = query(url, "SELECT to_jsonb(w)::text FROM work_unit w ORDER BY id")

      migrate(url, "27")

      query(url, s"SELECT parent_correction_id::text FROM work_unit_override_history WHERE id='$b'") shouldEqual Seq(Seq(a.toString))
      query(url, s"SELECT parent_correction_id::text FROM work_unit_override_history WHERE id='$clear'") shouldEqual Seq(Seq(b.toString))
      query(url, s"SELECT parent_correction_id::text FROM work_unit_override_history WHERE id='$nextFull'") shouldEqual Seq(
        Seq(fullAfterLegacy.toString)
      )
      Seq(a, legacy, fullBeforeLegacy, boundary, fullAfterLegacy).foreach { event =>
        query(url, s"SELECT parent_correction_id::text FROM work_unit_override_history WHERE id='$event'") shouldEqual Seq(Seq(null))
      }
      query(
        url,
        "SELECT (to_jsonb(h) - 'parent_correction_id' - 'reverts_event_id')::text FROM work_unit_override_history h ORDER BY id"
      ) shouldEqual originalHistory
      query(url, "SELECT to_jsonb(w)::text FROM work_unit w ORDER BY id") shouldEqual originalWorkUnits
      query(url, "SELECT count(*)::text FROM work_unit_override_history WHERE reverts_event_id IS NOT NULL") shouldEqual Seq(Seq("0"))
    }

    "reject missing, cross-worklog, self, future and legacy parents" in database { url =>
      val id      = workUnit(url)
      val other   = workUnit(url)
      val earlier = history(url, other, 1)
      val future  = history(url, id, 10)
      val legacy  = history(url, id, 1, provenance = "LegacyPmSnapshot")
      migrate(url, "27")
      Seq(UUID.randomUUID(), earlier, future, legacy).foreach { parent =>
        rejected(history(url, id, 5, parent = Some(parent))) shouldEqual "23514"
      }
      val self = UUID.randomUUID()
      rejected(history(url, id, 5, event = self, parent = Some(self))) shouldEqual "23514"
    }

    "accept an Undo only once with its target's parent and reject invalid targets or operation shapes" in database { url =>
      val id      = workUnit(url)
      val other   = workUnit(url)
      val a       = history(url, id, 1)
      val b       = history(url, id, 2, operation = "Clear")
      val foreign = history(url, other, 1)
      val legacy  = history(url, id, 3, provenance = "LegacyPmSnapshot")
      val future  = history(url, id, 20)
      migrate(url, "27")
      rejected(history(url, id, 4, operation = "Undo", reverts = Some(b))) shouldEqual "23514"
      Seq(UUID.randomUUID(), foreign, legacy, future).foreach { target =>
        rejected(history(url, id, 4, operation = "Undo", reverts = Some(target))) shouldEqual "23514"
      }
      val self = UUID.randomUUID()
      rejected(history(url, id, 4, operation = "Undo", event = self, reverts = Some(self))) shouldEqual "23514"
      rejected(history(url, id, 4, operation = "Undo")) shouldEqual "23514"
      rejected(history(url, id, 4, reverts = Some(a))) shouldEqual "23514"
      rejected(history(url, id, 4, operation = "Undo", provenance = "LegacyPmSnapshot", reverts = Some(a))) shouldEqual "23514"
      val undo = history(url, id, 4, operation = "Undo", parent = Some(a), reverts = Some(b))
      rejected(history(url, id, 5, operation = "Undo", parent = Some(a), reverts = Some(b))) shouldEqual "23505"
      rejected(history(url, id, 5, operation = "Undo", reverts = Some(undo))) shouldEqual "23514"
      rejected(history(url, id, 5, parent = Some(undo))) shouldEqual "23514"
      history(url, id, 5, operation = "Undo", reverts = Some(a))
      // Existing deletion is a state transition; audit links must not prevent it.
      execute(url, s"UPDATE work_unit SET state='Archived', archived_at=now() WHERE id='$id'")
      execute(url, s"UPDATE work_unit SET state='Deleted' WHERE id='$id'")
      query(url, s"SELECT count(*)::text FROM work_unit_override_history WHERE work_unit_id='$id'") shouldEqual Seq(Seq("6"))
    }
  }

  "Correction ancestry repository" should {
    "round-trip ancestry and restrict exact lookups and reversals to the worklog, requested events and version bound" in database { url =>
      val id      = workUnit(url)
      val other   = workUnit(url)
      val a       = history(url, id, 1)
      val b       = history(url, id, 2)
      val foreign = history(url, other, 1)
      migrate(url, "27")
      history(url, other, 2, operation = "Undo", reverts = Some(foreign))
      val repository                 = new WorkUnitOverrideHistoryRepositoryImpl
      val db                         = Database.forURL(url, user = user, password = password, driver = "org.postgresql.Driver")
      def run[A](action: DBIO[A]): A = Await.result(db.run(action), 10.seconds)
      try {
        val latest = run(repository.list(WorkUnitId(id), 2, Some(PageSizeAndNumber(1, 1)))).head
        latest.id shouldEqual b
        latest.parentCorrectionId shouldEqual Some(a)
        latest.revertsEventId should beNone
        run(repository.find(WorkUnitId(id), b, 2)) shouldEqual Some(latest)
        run(repository.find(WorkUnitId(id), b, 1)) should beNone
        run(repository.find(WorkUnitId(other), b, Long.MaxValue)) should beNone
        run(repository.find(WorkUnitId(id), UUID.randomUUID(), 2)) should beNone

        val undoB = latest.copy(id = UUID.randomUUID(), version = 3, operation = "Undo", revertsEventId = Some(b))
        run(repository.insert(undoB)) shouldEqual undoB
        run(repository.find(WorkUnitId(id), undoB.id, 3)) shouldEqual Some(undoB)
        val undoA = undoB.copy(id = UUID.randomUUID(), version = 4, parentCorrectionId = None, revertsEventId = Some(a))
        run(repository.insert(undoA)) shouldEqual undoA
        run(repository.reversals(WorkUnitId(id), Seq(a, b, foreign), 2)) should beEmpty
        run(repository.reversals(WorkUnitId(id), Seq(a, b, foreign), 3)) shouldEqual Seq(undoB)
        run(repository.reversals(WorkUnitId(id), Seq(a, b, foreign), 4)).toSet shouldEqual Set(undoA, undoB)
        run(repository.reversals(WorkUnitId(id), Seq(b), 4)) shouldEqual Seq(undoB)
        run(repository.reversals(WorkUnitId(other), Seq(a, b), Long.MaxValue)) should beEmpty
        run(repository.reversals(WorkUnitId(id), Seq.empty, Long.MaxValue)) should beEmpty
      } finally db.close()
    }
  }

  private def rejected(f: => UUID): String = Try(f).failed.get match {
    case error: SQLException => error.getSQLState
    case error               => throw error
  }
  private def workUnit(url: String): UUID = {
    val id = UUID.randomUUID()
    execute(
      url,
      s"INSERT INTO work_unit (id,organization_id,user_id,project_id,date,duration,description,task_id,created_at,modified_at,state,entry_type) VALUES ('$id','$organization','$actor','$project',DATE '2026-09-17',interval '1 hour','Original','SOURCE',now(),now(),'Active','Worklog')"
    )
    id
  }
  private def nullable(value: Option[UUID]): String = value.fold("NULL")(v => s"'$v'")
  private def history(
    url: String,
    id: UUID,
    version: Long,
    operation: String = "Replace",
    provenance: String = "WorkUnitOverride",
    event: UUID = UUID.randomUUID(),
    parent: Option[UUID] = None,
    reverts: Option[UUID] = None
  ): UUID = {
    val projectClient = s"""{"projectId":"$project","projectName":"Historical project","clientId":"$client","clientName":"Historical client"}"""
    val snapshot =
      if (provenance == "WorkUnitOverride")
        s"""'{"source":{"projectClient":$projectClient,"taskId":"SOURCE"},"effective":{"projectClient":$projectClient,"taskId":null},"projectId":null,"taskIdOverrideSet":true,"taskId":{"mode":"Clear"}}'::jsonb"""
      else "NULL"
    val hasLinks = parent.nonEmpty || reverts.nonEmpty || operation == "Undo"
    val columns  = if (hasLinks) ",parent_correction_id,reverts_event_id" else ""
    val values   = if (hasLinks) s",${nullable(parent)},${nullable(reverts)}" else ""
    execute(
      url,
      s"INSERT INTO work_unit_override_history (id,work_unit_id,version,operation,actor_id,actor_display_name,changed_at,provenance,before_snapshot,after_snapshot,before_pm_project_name,after_pm_task_id$columns) VALUES ('$event','$id',$version,'$operation','$actor','Historical Actor','2026-09-17T10:00:00.123456Z','$provenance',$snapshot,$snapshot,'Archived name','OLD-TASK'$values)"
    )
    event
  }
  private def migrate(url: String, target: String): Unit = {
    val _ = Flyway
      .configure()
      .dataSource(url, user, password)
      .schemas("public", "quartz")
      .locations("classpath:db/migration")
      .target(target)
      .load()
      .migrate()
  }
  private def connection[A](url: String)(f: Connection => A): A = {
    val c = DriverManager.getConnection(url, user, password)
    try f(c)
    finally c.close()
  }
  private def execute(url: String, sql: String): Unit = connection(url) { c =>
    val s = c.createStatement()
    try { val _ = s.execute(sql) }
    finally s.close()
  }
  private def query(url: String, sql: String): Seq[Seq[String]] = connection(url) { c =>
    val s = c.createStatement()
    try {
      val r = s.executeQuery(sql)
      try {
        val b = Seq.newBuilder[Seq[String]]
        while (r.next()) b += (1 to r.getMetaData.getColumnCount).map(r.getString)
        b.result()
      } finally r.close()
    } finally s.close()
  }
  private def database[A](f: String => A): A = {
    val name = "ct567_undo_" + UUID.randomUUID().toString.replace("-", "")
    require(name.matches("ct567_undo_[0-9a-f]{32}"))
    val uri = URI.create(baseUrl.stripPrefix("jdbc:"))
    val url = "jdbc:" + new URI(uri.getScheme, uri.getUserInfo, uri.getHost, uri.getPort, "/" + name, uri.getQuery, uri.getFragment)
    execute(baseUrl, s"CREATE DATABASE \"$name\"")
    try {
      migrate(url, "26")
      execute(
        url,
        s"""INSERT INTO "user" (id,first_name,last_name,is_super_admin,created_at,modified_at) VALUES ('$actor','Current','Actor',false,now(),now())"""
      )
      execute(
        url,
        s"INSERT INTO organization (id,name,slug,created_at,created_by,modified_at,modified_by) VALUES ('$organization','Undo','undo',now(),'$actor',now(),'$actor')"
      )
      execute(
        url,
        s"INSERT INTO client (id,name,color,organization_id,created_at,created_by,modified_at,modified_by,logo,type,texture) VALUES ('$client','Client','#000000','$organization',now(),'$actor',now(),'$actor','','External','')"
      )
      execute(
        url,
        s"INSERT INTO project (id,name,organization_id,client_id,created_at,created_by,modified_at,modified_by) VALUES ('$project','Project','$organization','$client',now(),'$actor',now(),'$actor')"
      )
      f(url)
    } finally execute(baseUrl, s"DROP DATABASE \"$name\" WITH (FORCE)")
  }
}
