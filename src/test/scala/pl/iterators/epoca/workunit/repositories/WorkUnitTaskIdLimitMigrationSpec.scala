package pl.iterators.epoca.workunit.repositories

import org.flywaydb.core.Flyway
import org.specs2.mutable.SpecificationLike

import java.net.URI
import java.sql.{Connection, DriverManager, SQLException}
import java.util.UUID
import scala.util.Try

class WorkUnitTaskIdLimitMigrationSpec extends SpecificationLike {
  sequential

  private val baseUrl      = sys.env.getOrElse("TEST_DB_URL", "jdbc:postgresql://localhost:5432/epoca")
  private val user         = sys.env.getOrElse("TEST_DB_USER", "postgres")
  private val password     = sys.env.getOrElse("TEST_DB_PASSWORD", "postgres")
  private val actor        = UUID.randomUUID()
  private val organization = UUID.randomUUID()
  private val project      = UUID.randomUUID()
  private val client       = UUID.randomUUID()
  private val nextClient   = UUID.randomUUID()
  private val astral       = new String(Character.toChars(0x1f680))
  private val auditTable   = "work_unit_task_id_trunc_audit"

  Seq("public", "ct571_application").foreach { schema =>
    s"V29 source task ID limit in $schema" should {
      "audit and truncate by Unicode characters while preserving every unrelated value and historical checksum" in database(schema) { url =>
        val values  = Seq(null, "", "valid 'quoted' task", "a" * 254, "b" * 255, astral * 254, "v" + astral * 254, "c" * 256, astral * 256, "c" * 256)
        val ids     = values.map(value => workUnit(url, schema, value))
        val changed = ids.takeRight(3)
        execute(
          url,
          schema,
          s"UPDATE work_unit SET task_id_override_set=true, override_task_id='OVERRIDE', override_version=1, override_has_history=true, override_last_changed_by='$actor', override_last_changed_at='2026-09-25T10:00:00Z' WHERE id='${changed.head}'"
        )
        execute(
          url,
          schema,
          s"INSERT INTO work_unit_override_history (id,work_unit_id,version,operation,actor_id,actor_display_name,changed_at,provenance,before_snapshot,after_snapshot) VALUES ('${UUID
              .randomUUID()}','${changed.head}',1,'Replace','$actor','Historical Actor','2026-09-25T10:00:00Z','WorkUnitOverride','{\"taskId\":\"snapshot-before\"}','{\"taskId\":\"snapshot-after\"}')"
        )
        execute(url, schema, s"UPDATE project SET client_id='$nextClient' WHERE id='$project'")
        val originals       = query(url, schema, "SELECT to_jsonb(w)::text FROM work_unit w ORDER BY id")
        val unchangedFields = query(url, schema, "SELECT (to_jsonb(w)-'task_id'-'revision')::text FROM work_unit w ORDER BY id")
        val revisions       = query(url, schema, "SELECT id::text, revision::text FROM work_unit ORDER BY id")
        val history         = query(url, schema, "SELECT to_jsonb(h)::text FROM work_unit_override_history h ORDER BY id")
        val checksums       = historicalChecksums(url, schema)
        if (schema != "public") hostilePublic(url, schema)

        val flyway = configuredFlyway(url, schema, "29")
        flyway.migrate()

        Option(flyway.info().current()).map(_.getVersion.toString) shouldEqual Some("29")
        query(url, schema, "SELECT (to_jsonb(w)-'task_id'-'revision')::text FROM work_unit w ORDER BY id") shouldEqual unchangedFields
        query(url, schema, "SELECT to_jsonb(h)::text FROM work_unit_override_history h ORDER BY id") shouldEqual history
        ids.zip(values).foreach { case (id, original) =>
          val expected = Option(original).map(v => v.codePoints().toArray.take(255).map(cp => new String(Character.toChars(cp))).mkString).orNull
          query(url, schema, s"SELECT task_id,client_id::text FROM work_unit WHERE id='$id'") shouldEqual Seq(Seq(expected, client.toString))
        }
        query(url, schema, "SELECT id::text,revision::text FROM work_unit ORDER BY id") shouldEqual revisions.map { row =>
          Seq(row.head, (row(1).toLong + (if (changed.exists(_.toString == row.head)) 1 else 0)).toString)
        }
        query(url, schema, s"SELECT work_unit_id::text,old_task_id,new_task_id FROM $auditTable ORDER BY work_unit_id") shouldEqual
          ids
            .zip(values)
            .takeRight(3)
            .map { case (id, value) => Seq(id.toString, value, if (value.startsWith(astral)) astral * 255 else "c" * 255) }
            .sortBy(_.head)
        query(url, schema, s"SELECT count(*)::text FROM $auditTable WHERE truncated_at IS NOT NULL") shouldEqual Seq(Seq("3"))
        query(
          url,
          schema,
          s"SELECT column_name,data_type,is_nullable FROM information_schema.columns WHERE table_schema='$schema' AND table_name='$auditTable' ORDER BY ordinal_position"
        ) shouldEqual Seq(
          Seq("work_unit_id", "uuid", "NO"),
          Seq("old_task_id", "text", "NO"),
          Seq("new_task_id", "text", "NO"),
          Seq("truncated_at", "timestamp with time zone", "NO")
        )
        query(
          url,
          schema,
          s"SELECT count(*)::text FROM pg_constraint WHERE conrelid='$schema.$auditTable'::regclass AND contype='p'"
        ) shouldEqual Seq(Seq("1"))
        query(
          url,
          schema,
          s"SELECT convalidated::text FROM pg_constraint WHERE conrelid='$schema.work_unit'::regclass AND conname='work_unit_task_id_maxlen_chk'"
        ) shouldEqual Seq(Seq("true"))
        rejected(workUnit(url, schema, "x" * 256)) shouldEqual "23514"
        rejected(execute(url, schema, s"UPDATE work_unit SET task_id=repeat('x',256) WHERE id='${ids.head}'")) shouldEqual "23514"
        query(
          url,
          schema,
          s"SELECT to_jsonb(w)::text FROM work_unit w WHERE char_length(task_id)<=255 OR task_id IS NULL ORDER BY id"
        ).size shouldEqual originals.size
        triggerMode(url, schema) shouldEqual "O"
        historicalChecksums(url, schema) shouldEqual checksums
        val after = query(url, schema, "SELECT to_jsonb(w)::text FROM work_unit w ORDER BY id")
        val audit = query(url, schema, s"SELECT to_jsonb(a)::text FROM $auditTable a ORDER BY work_unit_id")
        flyway.migrate().migrationsExecuted shouldEqual 0
        flyway.validate()
        query(url, schema, "SELECT to_jsonb(w)::text FROM work_unit w ORDER BY id") shouldEqual after
        query(url, schema, s"SELECT to_jsonb(a)::text FROM $auditTable a ORDER BY work_unit_id") shouldEqual audit
        historicalChecksums(url, schema) shouldEqual checksums
        execute(url, schema, s"DELETE FROM work_unit WHERE id='${changed.last}'")
        query(url, schema, s"SELECT count(*)::text FROM $auditTable WHERE work_unit_id='${changed.last}'") shouldEqual Seq(Seq("1"))
        if (schema != "public") query(url, schema, "SELECT task_id FROM public.work_unit") shouldEqual Seq(Seq("hostile-public"))
        success
      }

    }

    Seq(false, true).foreach { existingValid =>
      s"reject distinct colliding originals atomically in $schema (existing valid prefix: $existingValid)" in database(schema) { url =>
        val prefix = "sensitive" + "z" * 246
        workUnit(url, schema, prefix + "-one")
        workUnit(url, schema, if (existingValid) prefix else prefix + "-two")
        val before    = query(url, schema, "SELECT to_jsonb(w)::text FROM work_unit w ORDER BY id")
        val checksums = historicalChecksums(url, schema)
        val failure   = Try(configuredFlyway(url, schema, "29").migrate()).failed.get
        val messages  = Iterator.iterate[Throwable](failure)(_.getCause).takeWhile(_ != null).map(_.getMessage).mkString("\n")
        messages must contain("collision groups=1, affected rows=2")
        messages must not(contain(prefix))
        query(url, schema, "SELECT to_jsonb(w)::text FROM work_unit w ORDER BY id") shouldEqual before
        historicalChecksums(url, schema) shouldEqual checksums
        query(url, schema, s"SELECT to_regclass('$schema.$auditTable')::text") shouldEqual Seq(Seq(null))
        query(url, schema, "SELECT version FROM flyway_schema_history WHERE version='29'") should beEmpty
        triggerMode(url, schema) shouldEqual "O"
      }
    }
  }

  "V29 trigger and schema safety" should {
    "restore each client trigger mode and its future behavior, leaving revision advancement active" in {
      Seq("ENABLE" -> "O", "ENABLE ALWAYS" -> "A", "ENABLE REPLICA" -> "R", "DISABLE" -> "D").foreach { case (mode, expectedMode) =>
        database("ct571_application") { url =>
          val schema = "ct571_application"
          val id     = workUnit(url, schema, "q" * 256)
          execute(url, schema, s"UPDATE project SET client_id='$nextClient' WHERE id='$project'")
          execute(url, schema, s"ALTER TABLE work_unit $mode TRIGGER work_unit_client_id_from_project")
          configuredFlyway(url, schema, "29").migrate()
          triggerMode(url, schema) shouldEqual expectedMode
          query(url, schema, s"SELECT client_id::text,revision::text FROM work_unit WHERE id='$id'") shouldEqual Seq(Seq(client.toString, "1"))
          execute(url, schema, s"UPDATE work_unit SET description='Future edit' WHERE id='$id'")
          val expectedClient = if (expectedMode == "O" || expectedMode == "A") nextClient else client
          query(url, schema, s"SELECT client_id::text,revision::text FROM work_unit WHERE id='$id'") shouldEqual Seq(
            Seq(expectedClient.toString, "2")
          )
        }
      }
      success
    }

    "roll back the audit, data and trigger disable when an update fails after preflight" in database("ct571_application") { url =>
      val schema = "ct571_application"
      workUnit(url, schema, "r" * 256)
      execute(url, schema, "ALTER TABLE work_unit ENABLE ALWAYS TRIGGER work_unit_client_id_from_project")
      execute(
        url,
        schema,
        "CREATE FUNCTION reject_ct571_update() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'forced CT571 update failure'; END $$"
      )
      execute(url, schema, "CREATE TRIGGER reject_ct571_update BEFORE UPDATE ON work_unit FOR EACH ROW EXECUTE FUNCTION reject_ct571_update()")
      val before  = query(url, schema, "SELECT to_jsonb(w)::text FROM work_unit w ORDER BY id")
      val failure = Try(configuredFlyway(url, schema, "29").migrate()).failed.get
      failure.getMessage must contain("forced CT571 update failure")
      query(url, schema, "SELECT to_jsonb(w)::text FROM work_unit w ORDER BY id") shouldEqual before
      query(url, schema, s"SELECT to_regclass('$schema.$auditTable')::text") shouldEqual Seq(Seq(null))
      triggerMode(url, schema) shouldEqual "A"
    }

    "fail when the selected work_unit is absent despite a public namesake" in database("ct571_application") { url =>
      val schema = "ct571_application"
      hostilePublic(url, schema)
      execute(url, schema, "DROP TABLE work_unit CASCADE")
      val failure = Try(configuredFlyway(url, schema, "29").migrate()).failed.get
      failure.getMessage must contain("does not exist")
      query(url, schema, "SELECT task_id FROM public.work_unit") shouldEqual Seq(Seq("hostile-public"))
      query(url, schema, s"SELECT to_regclass('$schema.$auditTable')::text") shouldEqual Seq(Seq(null))
      query(url, schema, "SELECT version FROM flyway_schema_history WHERE version='29'") should beEmpty
    }
  }

  private def configuredFlyway(
    url: String,
    schema: String,
    target: String
  ): Flyway = {
    val locations = if (schema == "public") Seq("classpath:db/migration") else Seq("classpath:db/bootstrap", "classpath:db/configured")
    Flyway
      .configure()
      .dataSource(url, user, password)
      .defaultSchema(schema)
      .schemas(schema, "quartz")
      .locations(locations: _*)
      .target(target)
      .load()
  }

  private def historicalChecksums(url: String, schema: String): Seq[Seq[String]] =
    query(url, schema, "SELECT version,checksum::text FROM flyway_schema_history WHERE version::integer<=27 ORDER BY installed_rank")

  private def triggerMode(url: String, schema: String): String =
    query(
      url,
      schema,
      s"SELECT tgenabled::text FROM pg_trigger WHERE tgrelid='$schema.work_unit'::regclass AND tgname='work_unit_client_id_from_project'"
    ).head.head

  private def hostilePublic(url: String, schema: String): Unit = {
    execute(url, schema, "CREATE TABLE public.work_unit (task_id text)")
    execute(url, schema, "INSERT INTO public.work_unit VALUES ('hostile-public')")
    execute(url, schema, s"CREATE TABLE public.$auditTable (sentinel text)")
    execute(
      url,
      schema,
      "CREATE FUNCTION public.set_work_unit_client_id_from_project() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'hostile public function'; END $$"
    )
    execute(
      url,
      schema,
      "ALTER DATABASE \"" + URI.create(url.stripPrefix("jdbc:")).getPath.stripPrefix("/") + "\" SET search_path TO public,ct571_application"
    )
  }

  private def workUnit(
    url: String,
    schema: String,
    taskId: String
  ): UUID = {
    val id = UUID.randomUUID()
    connection(url, schema) { c =>
      val s = c.prepareStatement(
        s"INSERT INTO work_unit (id,organization_id,user_id,project_id,date,duration,description,task_id,created_at,modified_at,state,entry_type) VALUES ('$id','$organization','$actor','$project',DATE '2026-09-25',interval '1 hour','Original',?,'2026-09-25T09:00:00Z','2026-09-25T09:00:00Z','Active','Worklog')"
      )
      try { s.setString(1, taskId); val _ = s.executeUpdate() }
      finally s.close()
    }
    id
  }

  private def rejected(f: => Any): String = Try(f).failed.get match {
    case error: SQLException => error.getSQLState
    case error               => throw error
  }
  private def connection[A](url: String, schema: String)(f: Connection => A): A = {
    val c = DriverManager.getConnection(url, user, password)
    try { c.setSchema(schema); f(c) }
    finally c.close()
  }
  private def execute(
    url: String,
    schema: String,
    sql: String
  ): Unit = connection(url, schema) { c =>
    val s = c.createStatement()
    try { val _ = s.execute(sql) }
    finally s.close()
  }
  private def query(
    url: String,
    schema: String,
    sql: String
  ): Seq[Seq[String]] = connection(url, schema) { c =>
    val s = c.createStatement()
    try {
      val r = s.executeQuery(sql)
      try {
        val rows = Seq.newBuilder[Seq[String]]
        while (r.next()) rows += (1 to r.getMetaData.getColumnCount).map(r.getString)
        rows.result()
      } finally r.close()
    } finally s.close()
  }
  private def database[A](schema: String)(f: String => A): A = {
    val name = "ct571_task_id_" + UUID.randomUUID().toString.replace("-", "")
    require(name.matches("ct571_task_id_[0-9a-f]{32}"))
    val uri = URI.create(baseUrl.stripPrefix("jdbc:"))
    val url = "jdbc:" + new URI(uri.getScheme, uri.getUserInfo, uri.getHost, uri.getPort, "/" + name, uri.getQuery, uri.getFragment)
    execute(baseUrl, "public", s"CREATE DATABASE \"$name\"")
    try {
      configuredFlyway(url, schema, "27").migrate()
      execute(
        url,
        schema,
        s"""INSERT INTO "user" (id,first_name,last_name,is_super_admin,created_at,modified_at) VALUES ('$actor','Current','Actor',false,now(),now())"""
      )
      execute(
        url,
        schema,
        s"INSERT INTO organization (id,name,slug,created_at,created_by,modified_at,modified_by) VALUES ('$organization','Task ID','ct571',now(),'$actor',now(),'$actor')"
      )
      Seq(client, nextClient).foreach { id =>
        execute(
          url,
          schema,
          s"INSERT INTO client (id,name,color,organization_id,created_at,created_by,modified_at,modified_by,logo,type,texture) VALUES ('$id','Client','#000000','$organization',now(),'$actor',now(),'$actor','','External','')"
        )
      }
      execute(
        url,
        schema,
        s"INSERT INTO project (id,name,organization_id,client_id,created_at,created_by,modified_at,modified_by) VALUES ('$project','Project','$organization','$client',now(),'$actor',now(),'$actor')"
      )
      f(url)
    } finally execute(baseUrl, "public", s"DROP DATABASE \"$name\" WITH (FORCE)")
  }
}
