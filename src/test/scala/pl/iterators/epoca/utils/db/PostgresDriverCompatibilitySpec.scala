package pl.iterators.epoca.utils.db

import io.circe.Json
import org.specs2.mutable.SpecificationLike
import pl.iterators.epoca.organization.domain.OrganizationDomain
import pl.iterators.epoca.organization.domain.OrganizationDomain._
import pl.iterators.epoca.utils.db.PostgresDriver.api._
import pl.iterators.epoca.workunit.domain.WorkUnitDomain
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.WorkUnitDate
import slick.lifted.ProvenShape
import slick.sql.SqlStreamingAction

import java.net.URI
import java.sql.Timestamp
import java.util.Properties
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}
import scala.util.{Failure, Success}

final case class CompatibilityValue(value: String)
final case class CompatibilityRow(
  id: OrganizationId,
  name: OrganizationName,
  wrapper: CompatibilityValue,
  role: OrganizationUserRole,
  roles: Seq[OrganizationUserRole],
  createdAt: OrganizationCreatedAt,
  date: WorkUnitDate,
  payload: Json,
  related: Seq[OrganizationId],
  attributes: Map[String, String],
  optionalPayload: Option[Json])
final class CompatibilityTable(tag: Tag) extends Table[CompatibilityRow](tag, "ct526_kebs_compatibility") {
  def id: Rep[OrganizationDomain.OrganizationId]               = column[OrganizationId]("id", O.PrimaryKey)
  def name: Rep[OrganizationDomain.OrganizationName]           = column[OrganizationName]("name")
  def wrapper: Rep[CompatibilityValue]                         = column[CompatibilityValue]("wrapper")
  def role: Rep[OrganizationUserRole]                          = column[OrganizationUserRole]("role")
  def roles: Rep[Seq[OrganizationUserRole]]                    = column[Seq[OrganizationUserRole]]("roles")
  def createdAt: Rep[OrganizationDomain.OrganizationCreatedAt] = column[OrganizationCreatedAt]("created_at")
  def date: Rep[WorkUnitDomain.WorkUnitDate]                   = column[WorkUnitDate]("date")
  def payload: Rep[Json]                                       = column[Json]("payload")
  def related: Rep[Seq[OrganizationDomain.OrganizationId]]     = column[Seq[OrganizationId]]("related")
  def attributes: Rep[Map[String, String]]                     = column[Map[String, String]]("attributes")
  def optionalPayload: Rep[Option[Json]]                       = column[Option[Json]]("optional_payload")
  def * : ProvenShape[CompatibilityRow] =
    (
      id,
      name,
      wrapper,
      role,
      roles,
      createdAt,
      date,
      payload,
      related,
      attributes,
      optionalPayload
    ) <> (CompatibilityRow.tupled, CompatibilityRow.unapply)
}
class PostgresDriverCompatibilitySpec extends SpecificationLike {
  sequential

  implicit private val ec: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

  private val rows            = TableQuery[CompatibilityTable]
  private val testDatabaseUrl = sys.env.getOrElse("TEST_DB_URL", "epoca")
  private val expectedDatabaseName =
    Option(URI.create(testDatabaseUrl.stripPrefix("jdbc:")).getPath).getOrElse(testDatabaseUrl).stripPrefix("/")

  require(expectedDatabaseName.nonEmpty && !expectedDatabaseName.contains('/'), "TEST_DB_URL must identify exactly one database")
  require(
    !testDatabaseUrl.matches("(?i).*[?&](currentSchema|ApplicationName)=.*"),
    "TEST_DB_URL must not override the compatibility spec's fail-closed connection properties"
  )

  "PostgresDriver" should {
    "store typed mappings in accepted PostgreSQL representations" in {
      val createSchema = rows.schema.create
      val insertRows   = rows ++= PostgresDriverCompatibilitySpec.typedRows
      val readFacts    = PostgresDriverCompatibilitySpec.typedStorageFacts
      val action = for {
        identity <- PostgresDriverCompatibilitySpec.databaseIdentityFacts
        _ = identity mustEqual expectedIdentityFacts
        _     <- sqlu"""CREATE EXTENSION IF NOT EXISTS hstore"""
        _     <- createSchema
        _     <- insertRows
        facts <- readFacts
      } yield facts mustEqual PostgresDriverCompatibilitySpec.expectedTypedStorageFacts

      withCompatibilityDatabase(action)
    }

    "read accepted raw PostgreSQL representations through typed mappings" in {
      val createSchema = rows.schema.create
      val readRich = rows
        .filter(_.id === PostgresDriverCompatibilitySpec.rawRichRow.id)
        .result
        .head
      val readEdge = rows
        .filter(_.id === PostgresDriverCompatibilitySpec.rawEdgeRow.id)
        .result
        .head
      val action = for {
        identity <- PostgresDriverCompatibilitySpec.databaseIdentityFacts
        _ = identity mustEqual expectedIdentityFacts
        _    <- sqlu"""CREATE EXTENSION IF NOT EXISTS hstore"""
        _    <- createSchema
        _    <- PostgresDriverCompatibilitySpec.insertRawRows
        rich <- readRich
        edge <- readEdge
      } yield Seq(rich, edge) mustEqual Seq(PostgresDriverCompatibilitySpec.rawRichRow, PostgresDriverCompatibilitySpec.rawEdgeRow)

      withCompatibilityDatabase(action)
    }
  }

  private val expectedIdentityFacts = Seq(
    s"database=$expectedDatabaseName",
    "server_version_num=130004",
    "current_schema=public",
    "search_path=public",
    "flyway_total=38",
    "flyway_success=38",
    "compatibility_table_count=0",
    "hstore_count=0"
  )

  private def withCompatibilityDatabase[T](action: DBIO[T]): T = {
    val properties = new Properties()
    properties.setProperty("ApplicationName", "ct526-task7a-compatibility")
    properties.setProperty("currentSchema", "public")
    val database = Database.forURL(
      url = testDatabaseUrl,
      user = sys.env.getOrElse("TEST_DB_USER", "postgres"),
      password = sys.env.getOrElse("TEST_DB_PASSWORD", "postgres"),
      driver = "org.postgresql.Driver",
      executor = AsyncExecutor(getClass.getCanonicalName, minThreads = 1, maxThreads = 1, queueSize = 20, maxConnections = 1),
      prop = properties
    )

    try
      Await.result(
        database.run {
          action.map[T](result => throw AbortTx(result)).transactionally.asTry.map[T] {
            case Failure(AbortTx(value)) => value.asInstanceOf[T]
            case Failure(error)          => throw error
            case Success(_)              => sys.error("The compatibility transaction must abort")
          }
        },
        30.seconds
      )
    finally database.close()
  }

  private case class AbortTx[T](value: T) extends Exception
}
object PostgresDriverCompatibilitySpec {
  private val typedRichRow = CompatibilityRow(
    id = OrganizationId(java.util.UUID.fromString("00000000-0000-0000-0000-000000000701")),
    name = OrganizationName("compatibility"),
    wrapper = CompatibilityValue("one-field"),
    role = OrganizationUserRole.Manager,
    roles = Seq(OrganizationUserRole.Manager, OrganizationUserRole.Employee),
    createdAt = OrganizationCreatedAt(java.time.Instant.parse("2026-08-13T10:15:30Z")),
    date = WorkUnitDate(java.time.LocalDate.parse("2026-08-13")),
    payload = Json.obj("nested" -> Json.obj("null" -> Json.Null, "flag" -> Json.fromBoolean(true)), "empty" -> Json.arr()),
    related = Seq(OrganizationId(java.util.UUID.fromString("00000000-0000-0000-0000-000000000702"))),
    attributes = Map("key" -> "value"),
    optionalPayload = Some(Json.Null)
  )

  private val typedEdgeRow = CompatibilityRow(
    id = OrganizationId(java.util.UUID.fromString("00000000-0000-0000-0000-000000000704")),
    name = OrganizationName("edge"),
    wrapper = CompatibilityValue(""),
    role = OrganizationUserRole.Employee,
    roles = Seq.empty,
    createdAt = OrganizationCreatedAt(java.time.Instant.parse("2026-08-14T11:16:31Z")),
    date = WorkUnitDate(java.time.LocalDate.parse("2026-08-14")),
    payload = Json.Null,
    related = Seq.empty,
    attributes = Map.empty,
    optionalPayload = None
  )

  val typedRows: Seq[CompatibilityRow] = Seq(typedRichRow, typedEdgeRow)

  val rawRichRow: CompatibilityRow = CompatibilityRow(
    id = OrganizationId(java.util.UUID.fromString("00000000-0000-0000-0000-000000000705")),
    name = OrganizationName("raw-name"),
    wrapper = CompatibilityValue("raw-wrapper"),
    role = OrganizationUserRole.Employee,
    roles = Seq(OrganizationUserRole.Employee, OrganizationUserRole.Manager),
    createdAt = OrganizationCreatedAt(Timestamp.valueOf("2026-08-14 11:22:33").toInstant),
    date = WorkUnitDate(java.time.LocalDate.parse("2026-08-14")),
    payload = Json.obj("empty" -> Json.arr(), "nested" -> Json.obj("null" -> Json.Null, "value" -> Json.fromString("raw"))),
    related = Seq(OrganizationId(java.util.UUID.fromString("00000000-0000-0000-0000-000000000708"))),
    attributes = Map("raw" -> "seed"),
    optionalPayload = Some(Json.Null)
  )

  val rawEdgeRow: CompatibilityRow = CompatibilityRow(
    id = OrganizationId(java.util.UUID.fromString("00000000-0000-0000-0000-000000000706")),
    name = OrganizationName("raw-edge"),
    wrapper = CompatibilityValue(""),
    role = OrganizationUserRole.Manager,
    roles = Seq.empty,
    createdAt = OrganizationCreatedAt(Timestamp.valueOf("2026-08-15 01:02:03").toInstant),
    date = WorkUnitDate(java.time.LocalDate.parse("2026-08-15")),
    payload = Json.Null,
    related = Seq.empty,
    attributes = Map.empty,
    optionalPayload = None
  )

  val databaseIdentityFacts: SqlStreamingAction[Vector[String], String, Effect] = sql"""
    select fact
    from (values
      (8, 'hstore_count=' || (select count(*)::text from pg_extension where extname = 'hstore')),
      (3, 'current_schema=' || current_schema()),
      (6, 'flyway_success=' || (select count(*)::text from flyway_schema_history where success)),
      (1, 'database=' || current_database()),
      (7, 'compatibility_table_count=' || (
        select count(*)::text
        from pg_class c
        join pg_namespace n on n.oid = c.relnamespace
        where n.nspname = 'public' and c.relname = 'ct526_kebs_compatibility')),
      (4, 'search_path=' || current_setting('search_path')),
      (2, 'server_version_num=' || current_setting('server_version_num')),
      (5, 'flyway_total=' || (select count(*)::text from flyway_schema_history))
    ) identity(position, fact)
    order by position
  """.as[String]

  val typedStorageFacts: SqlStreamingAction[Vector[String], String, Effect] = sql"""
    select t.id::text || '|' || facts.fact
    from ct526_kebs_compatibility t
    cross join lateral (values
      (1, 'id_type=' || pg_typeof(t.id)::text),
      (2, 'id=' || t.id::text),
      (3, 'name_type=' || pg_typeof(t.name)::text),
      (4, 'name=' || t.name),
      (5, 'wrapper_type=' || pg_typeof(t.wrapper)::text),
      (6, 'wrapper=' || t.wrapper),
      (7, 'role_type=' || pg_typeof(t.role)::text),
      (8, 'role=' || t.role),
      (9, 'roles_type=' || pg_typeof(t.roles)::text),
      (10, 'roles=' || t.roles::text),
      (11, 'roles_cardinality=' || cardinality(t.roles)::text),
      (12, 'created_at_type=' || pg_typeof(t.created_at)::text),
      (13, 'created_at=' || t.created_at::text),
      (14, 'date_type=' || pg_typeof(t.date)::text),
      (15, 'date=' || t.date::text),
      (16, 'payload_type=' || pg_typeof(t.payload)::text),
      (17, 'payload_json_type=' || jsonb_typeof(t.payload)),
      (18, 'payload_nested_null=' || coalesce(((t.payload -> 'nested' -> 'null') = 'null'::jsonb)::text, 'false')),
      (19, 'payload_nested_flag=' || coalesce(t.payload #>> '{nested,flag}', '<missing>')),
      (20, 'payload_empty=' || coalesce((t.payload -> 'empty')::text, '<missing>')),
      (21, 'related_type=' || pg_typeof(t.related)::text),
      (22, 'related=' || t.related::text),
      (23, 'related_cardinality=' || cardinality(t.related)::text),
      (24, 'attributes_type=' || pg_typeof(t.attributes)::text),
      (25, 'attributes_key=' || coalesce(t.attributes -> 'key', '<missing>')),
      (26, 'attributes_count=' || cardinality(akeys(t.attributes))::text),
      (27, 'optional_payload_type=' || pg_typeof(t.optional_payload)::text),
      (28, 'optional_payload_is_sql_null=' || (t.optional_payload is null)::text),
      (29, 'optional_payload_json_type=' || coalesce(jsonb_typeof(t.optional_payload), '<sql-null>')),
      (30, 'payload_exact=' || case t.id
        when '00000000-0000-0000-0000-000000000701'::uuid then
          (t.payload = '{"nested":{"null":null,"flag":true},"empty":[]}'::jsonb)::text
        when '00000000-0000-0000-0000-000000000704'::uuid then
          (t.payload = 'null'::jsonb)::text
        else 'false'
      end)
    ) facts(position, fact)
    where t.id in (
      '00000000-0000-0000-0000-000000000701'::uuid,
      '00000000-0000-0000-0000-000000000704'::uuid)
    order by t.id, facts.position
  """.as[String]

  val insertRawRows: SqlStreamingAction[Vector[Int], Int, Effect]#ResultAction[Int, NoStream, Effect] = sqlu"""
    insert into ct526_kebs_compatibility
      (id, name, wrapper, role, roles, created_at, date, payload, related, attributes, optional_payload)
    values
      (
        '00000000-0000-0000-0000-000000000705'::uuid,
        'raw-name'::varchar,
        'raw-wrapper'::varchar,
        'Employee'::varchar,
        array['Employee', 'Manager']::text[],
        '2026-08-14 11:22:33'::timestamp,
        '2026-08-14'::date,
        '{"empty":[],"nested":{"null":null,"value":"raw"}}'::jsonb,
        array['00000000-0000-0000-0000-000000000708'::uuid]::uuid[],
        '"raw"=>"seed"'::hstore,
        'null'::jsonb
      ),
      (
        '00000000-0000-0000-0000-000000000706'::uuid,
        'raw-edge'::varchar,
        ''::varchar,
        'Manager'::varchar,
        array[]::text[],
        '2026-08-15 01:02:03'::timestamp,
        '2026-08-15'::date,
        'null'::jsonb,
        array[]::uuid[],
        ''::hstore,
        null::jsonb
      )
  """

  private def storedTimestamp(value: OrganizationCreatedAt): String =
    Timestamp.from(value).toLocalDateTime.toString.replace('T', ' ')

  private val richPrefix = "00000000-0000-0000-0000-000000000701|"
  private val edgePrefix = "00000000-0000-0000-0000-000000000704|"

  val expectedTypedStorageFacts: Seq[String] = Seq(
    richPrefix + "id_type=uuid",
    richPrefix + "id=00000000-0000-0000-0000-000000000701",
    richPrefix + "name_type=character varying",
    richPrefix + "name=compatibility",
    richPrefix + "wrapper_type=character varying",
    richPrefix + "wrapper=one-field",
    richPrefix + "role_type=character varying",
    richPrefix + "role=Manager",
    richPrefix + "roles_type=text[]",
    richPrefix + "roles={Manager,Employee}",
    richPrefix + "roles_cardinality=2",
    richPrefix + "created_at_type=timestamp without time zone",
    richPrefix + s"created_at=${storedTimestamp(typedRichRow.createdAt)}",
    richPrefix + "date_type=date",
    richPrefix + "date=2026-08-13",
    richPrefix + "payload_type=jsonb",
    richPrefix + "payload_json_type=object",
    richPrefix + "payload_nested_null=true",
    richPrefix + "payload_nested_flag=true",
    richPrefix + "payload_empty=[]",
    richPrefix + "related_type=uuid[]",
    richPrefix + "related={00000000-0000-0000-0000-000000000702}",
    richPrefix + "related_cardinality=1",
    richPrefix + "attributes_type=hstore",
    richPrefix + "attributes_key=value",
    richPrefix + "attributes_count=1",
    richPrefix + "optional_payload_type=jsonb",
    richPrefix + "optional_payload_is_sql_null=false",
    richPrefix + "optional_payload_json_type=null",
    richPrefix + "payload_exact=true",
    edgePrefix + "id_type=uuid",
    edgePrefix + "id=00000000-0000-0000-0000-000000000704",
    edgePrefix + "name_type=character varying",
    edgePrefix + "name=edge",
    edgePrefix + "wrapper_type=character varying",
    edgePrefix + "wrapper=",
    edgePrefix + "role_type=character varying",
    edgePrefix + "role=Employee",
    edgePrefix + "roles_type=text[]",
    edgePrefix + "roles={}",
    edgePrefix + "roles_cardinality=0",
    edgePrefix + "created_at_type=timestamp without time zone",
    edgePrefix + s"created_at=${storedTimestamp(typedEdgeRow.createdAt)}",
    edgePrefix + "date_type=date",
    edgePrefix + "date=2026-08-14",
    edgePrefix + "payload_type=jsonb",
    edgePrefix + "payload_json_type=null",
    edgePrefix + "payload_nested_null=false",
    edgePrefix + "payload_nested_flag=<missing>",
    edgePrefix + "payload_empty=<missing>",
    edgePrefix + "related_type=uuid[]",
    edgePrefix + "related={}",
    edgePrefix + "related_cardinality=0",
    edgePrefix + "attributes_type=hstore",
    edgePrefix + "attributes_key=<missing>",
    edgePrefix + "attributes_count=0",
    edgePrefix + "optional_payload_type=jsonb",
    edgePrefix + "optional_payload_is_sql_null=true",
    edgePrefix + "optional_payload_json_type=<sql-null>",
    edgePrefix + "payload_exact=true"
  )
}
