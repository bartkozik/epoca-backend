package pl.iterators.epoca.workunit.repositories

import org.flywaydb.core.Flyway
import org.specs2.mutable.SpecificationLike

import java.net.URI
import java.sql.{Connection, DriverManager}
import java.util.UUID
import scala.util.Try

class WorkUnitOverrideMigrationSpec extends SpecificationLike {
  sequential

  private val baseUrl      = sys.env.getOrElse("TEST_DB_URL", "jdbc:postgresql://localhost:5432/epoca")
  private val user         = sys.env.getOrElse("TEST_DB_USER", "postgres")
  private val password     = sys.env.getOrElse("TEST_DB_PASSWORD", "postgres")
  private val actor        = UUID.randomUUID()
  private val organization = UUID.randomUUID()
  private val source       = UUID.randomUUID()
  private val target       = UUID.randomUUID()
  private val client       = UUID.randomUUID()

  "V26 work-unit overrides" should {
    "preserve active, current-only, history-only, cleared and gapped states without manufacturing audit events" in database { url =>
      val active      = workUnit(url)
      val currentOnly = workUnit(url)
      val historyOnly = workUnit(url)
      val cleared     = workUnit(url)
      val untouched   = workUnit(url)
      current(url, active, 7, Some(target), Some("TARGET-7"))
      val event = history(url, active, 7, Some(target), Some("TARGET-7"))
      current(url, currentOnly, 9, Some(target), None)
      history(url, historyOnly, 2, None, Some("OLD"))
      history(url, historyOnly, 7, Some(target), Some("RECOVERED"))
      history(url, cleared, 1, Some(target), Some("REMOVED"))
      history(url, cleared, 4, None, None)

      migrate(url, "26")

      query(
        url,
        s"SELECT project_id::text, task_id, override_project_id::text, override_task_id, task_id_override_set::text, revision::text, override_version::text, override_has_history::text FROM work_unit WHERE id='$active'"
      ) shouldEqual
        Seq(Seq(source.toString, "SOURCE", target.toString, "TARGET-7", "true", "7", "7", "true"))
      query(
        url,
        s"SELECT override_version::text, override_has_history::text, task_id_override_set::text FROM work_unit WHERE id='$currentOnly'"
      ) shouldEqual Seq(Seq("9", "false", "false"))
      query(
        url,
        s"SELECT override_project_id::text, override_task_id, override_version::text FROM work_unit WHERE id='$historyOnly'"
      ) shouldEqual Seq(Seq(target.toString, "RECOVERED", "7"))
      query(
        url,
        s"SELECT override_project_id::text, override_task_id, override_version::text, override_has_history::text FROM work_unit WHERE id='$cleared'"
      ) shouldEqual Seq(Seq(null, null, "4", "true"))
      query(url, s"SELECT revision::text, override_version::text, override_has_history::text FROM work_unit WHERE id='$untouched'") shouldEqual Seq(
        Seq("0", "0", "false")
      )
      query(url, "SELECT count(*)::text FROM work_unit_override_history") shouldEqual Seq(Seq("5"))
      query(
        url,
        s"SELECT id::text, actor_id::text, actor_display_name, provenance, before_pm_task_id, after_pm_task_id FROM work_unit_override_history WHERE id='$event'"
      ) shouldEqual
        Seq(Seq(event.toString, actor.toString, "Historical Actor", "LegacyPmSnapshot", null, "TARGET-7"))
      query(url, "SELECT to_regclass('public.work_unit_pm_classification')::text") shouldEqual Seq(Seq(null))
    }

    "reject contradictory current/event values atomically" in database { url =>
      val id = workUnit(url)
      current(url, id, 7, Some(target), Some("CURRENT"))
      history(url, id, 7, Some(target), Some("EVENT"))
      Try(migrate(url, "26")).isFailure should beTrue
      query(url, "SELECT count(*)::text FROM work_unit_pm_classification") shouldEqual Seq(Seq("1"))
      query(url, "SELECT to_regclass('public.work_unit_override_history')::text") shouldEqual Seq(Seq(null))
    }

    "reject a current version older than history" in database { url =>
      val id = workUnit(url)
      current(url, id, 3, Some(target), None)
      history(url, id, 7, Some(target), None)
      Try(migrate(url, "26")).isFailure should beTrue
      query(url, "SELECT count(*)::text FROM work_unit_pm_classification_history") shouldEqual Seq(Seq("1"))
    }
  }

  private def workUnit(url: String): UUID = {
    val id = UUID.randomUUID()
    execute(
      url,
      s"INSERT INTO work_unit (id,organization_id,user_id,project_id,date,duration,description,task_id,created_at,modified_at,state,entry_type) VALUES ('$id','$organization','$actor','$source',DATE '2026-09-17',interval '1 hour','Source description','SOURCE',now(),now(),'Active','Worklog')"
    )
    id
  }
  private def nullable(value: Option[Any]): String = value.fold("NULL")(v => "'" + v.toString.replace("'", "''") + "'")
  private def current(
    url: String,
    id: UUID,
    version: Int,
    project: Option[UUID],
    task: Option[String]
  ): Unit =
    execute(
      url,
      s"INSERT INTO work_unit_pm_classification VALUES ('$id',${nullable(project)},${nullable(task)},$version,'$actor','2026-09-17T10:00:00Z')"
    )
  private def history(
    url: String,
    id: UUID,
    version: Int,
    project: Option[UUID],
    task: Option[String]
  ): UUID = {
    val event = UUID.randomUUID()
    execute(
      url,
      s"INSERT INTO work_unit_pm_classification_history (id,work_unit_id,version,operation,actor_id,actor_display_name,changed_at,after_pm_project_id,after_pm_project_name,after_pm_client_id,after_pm_client_name,after_pm_task_id) VALUES ('$event','$id',$version,'${if (project.isEmpty && task.isEmpty) "Clear"
        else "Replace"}','$actor','Historical Actor','2026-09-17T10:00:00Z',${nullable(project)},${nullable(
          project.map(_ => "Old project name")
        )},${nullable(project.map(_ => client))},${nullable(project.map(_ => "Old client name"))},${nullable(task)})"
    )
    event
  }
  private def migrate(url: String, targetVersion: String): Unit = {
    val _ = Flyway
      .configure()
      .dataSource(url, user, password)
      .schemas("public", "quartz")
      .locations("classpath:db/migration")
      .target(targetVersion)
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
    val name = "ct567_override_" + UUID.randomUUID().toString.replace("-", "")
    require(name.matches("ct567_override_[0-9a-f]{32}"))
    val uri = URI.create(baseUrl.stripPrefix("jdbc:"))
    val url = "jdbc:" + new URI(uri.getScheme, uri.getUserInfo, uri.getHost, uri.getPort, "/" + name, uri.getQuery, uri.getFragment)
    execute(baseUrl, s"CREATE DATABASE \"$name\"")
    try {
      migrate(url, "25")
      execute(
        url,
        s"""INSERT INTO "user" (id,first_name,last_name,is_super_admin,created_at,modified_at) VALUES ('$actor','Current','Actor',false,now(),now())"""
      )
      execute(
        url,
        s"INSERT INTO organization (id,name,slug,created_at,created_by,modified_at,modified_by) VALUES ('$organization','Overrides','overrides',now(),'$actor',now(),'$actor')"
      )
      execute(
        url,
        s"INSERT INTO client (id,name,color,organization_id,created_at,created_by,modified_at,modified_by,logo,type,texture) VALUES ('$client','Client','#000000','$organization',now(),'$actor',now(),'$actor','','External','')"
      )
      Seq(source, target).foreach(p =>
        execute(
          url,
          s"INSERT INTO project (id,name,organization_id,client_id,created_at,created_by,modified_at,modified_by) VALUES ('$p','Project','$organization','$client',now(),'$actor',now(),'$actor')"
        )
      )
      f(url)
    } finally execute(baseUrl, s"DROP DATABASE \"$name\" WITH (FORCE)")
  }
}
