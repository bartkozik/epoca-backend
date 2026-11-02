package pl.iterators.epoca.stats.repositories

import io.circe.Json
import org.specs2.mutable.SpecificationLike
import pl.iterators.epoca.client.domain.ClientDomain.ClientId
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId
import pl.iterators.epoca.project.domain.ProjectDomain.ProjectId
import pl.iterators.epoca.stats.domain.StatsDomain.{WorkUnitPivotTableKey, WorkUnitPivotTableValue}
import pl.iterators.epoca.stats.domain.WorkUnitPivotQuery._
import pl.iterators.epoca.stats.services.WorkUnitPivotQueryRequestValidator
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.{WorkUnitDate, WorkUnitEntryType}
import slick.jdbc.{PositionedParameters, SQLActionBuilder}

import java.lang.reflect.{InvocationHandler, Method, Proxy}
import java.sql.{Array => SqlArray, Connection, PreparedStatement}
import java.time.{LocalDate, YearMonth}
import java.util.UUID
import scala.collection.mutable.ArrayBuffer

class WorkUnitPivotQuerySqlSpec extends SpecificationLike {
  import WorkUnitPivotQuerySqlSpec.ArrayBinding

  private val organizationId = OrganizationId(UUID.fromString("10000000-0000-4000-8000-000000000001"))
  private val projectId      = ProjectId(UUID.fromString("20000000-0000-4000-8000-000000000002"))
  private val userId         = UserId(UUID.fromString("30000000-0000-4000-8000-000000000003"))
  private val clientId       = ClientId(UUID.fromString("40000000-0000-4000-8000-000000000004"))
  private val pivotUuid      = UUID.fromString("50000000-0000-4000-8000-000000000005")
  private val dateFrom       = WorkUnitDate(LocalDate.of(2026, 1, 2))
  private val dateTo         = WorkUnitDate(LocalDate.of(2026, 12, 30))

  private val noOptions = Options(rowSubtotals = false, columnSubtotals = false, grandTotals = false)
  private val globals =
    GlobalFilters(dateFrom = dateFrom, dateTo = Some(dateTo), projectIds = Seq(projectId), userIds = Seq(userId), clientIds = Seq(clientId))

  "WorkUnitPivotQuerySql.depths" should {
    "keep the structural coordinate for an empty axis" in {
      WorkUnitPivotQuerySql.depths(0, subtotals = false, grandTotals = false) shouldEqual Seq(0)
      WorkUnitPivotQuerySql.depths(0, subtotals = true, grandTotals = true) shouldEqual Seq(0)
    }

    "avoid a redundant subtotal for a one-level axis" in {
      WorkUnitPivotQuerySql.depths(1, subtotals = false, grandTotals = false) shouldEqual Seq(1)
      WorkUnitPivotQuerySql.depths(1, subtotals = true, grandTotals = false) shouldEqual Seq(1)
      WorkUnitPivotQuerySql.depths(1, subtotals = true, grandTotals = true) shouldEqual Seq(1, 0)
    }

    "return leaf, descending prefixes, and grand total according to the options" in {
      WorkUnitPivotQuerySql.depths(4, subtotals = false, grandTotals = false) shouldEqual Seq(4)
      WorkUnitPivotQuerySql.depths(4, subtotals = true, grandTotals = false) shouldEqual Seq(4, 3, 2, 1)
      WorkUnitPivotQuerySql.depths(4, subtotals = false, grandTotals = true) shouldEqual Seq(4, 0)
      WorkUnitPivotQuerySql.depths(4, subtotals = true, grandTotals = true) shouldEqual Seq(4, 3, 2, 1, 0)
    }
  }

  "coordinateCounts" should {
    "apply OAuth report policy to source and effective projects inside the materialized source" in {
      val allowedProjects = Seq(projectId, ProjectId(UUID.fromString("20000000-0000-4000-8000-000000000099")))
      val securedGlobals  = globals.copy(reportAuthorizedProjectIds = Some(allowedProjects))
      val builder         = WorkUnitPivotQuerySql.coordinateCounts(organizationId, requestWithFilters(), securedGlobals)
      val sql             = normalized(statement(builder))

      sql must contain("wu.project_id = any(?::uuid[])")
      sql must contain("coalesce(wu.override_project_id, wu.project_id) = any(?::uuid[])")
      sql must not(contain("work_unit_pm_classification"))
      bindings(builder).map(_._2) must contain(ArrayBinding("uuid", allowedProjects.map(_.asInstanceOf[UUID])))
    }

    "fail closed when the OAuth report policy contains no projects" in {
      val securedGlobals = globals.copy(reportAuthorizedProjectIds = Some(Seq.empty))
      val sql            = normalized(statement(WorkUnitPivotQuerySql.coordinateCounts(organizationId, requestWithFilters(), securedGlobals)))

      sql must contain("and false")
    }

    "encode structural empty axes and non-empty grouping sets in one statement" in {
      val request = Request(
        rows = Seq.empty,
        columns = Seq(WorkUnitPivotTableKey.UserKey, WorkUnitPivotTableKey.YearKey),
        values = Seq(WorkUnitPivotTableValue.EntriesCount),
        filters = Seq.empty,
        options = Options(rowSubtotals = false, columnSubtotals = true, grandTotals = true)
      )

      val sql = normalized(statement(WorkUnitPivotQuerySql.coordinateCounts(organizationId, request, globals)))

      sql must contain("with filtered as materialized")
      sql must contain("select 1::bigint as structural_coordinate")
      sql must contain("column_coordinates as")
      sql must contain("group by grouping sets ((user_key, year_key), (user_key), ())")
      sql must contain("having count(*) > 0")
      sql must contain("select (select count(*) from row_coordinates)::bigint as row_count")
      sql must contain("(select count(*) from column_coordinates)::bigint as column_count")
    }

    "apply row and column subtotal options independently" in {
      val request = Request(
        rows = Seq(WorkUnitPivotTableKey.UserKey, WorkUnitPivotTableKey.ProjectKey),
        columns = Seq(WorkUnitPivotTableKey.YearKey, WorkUnitPivotTableKey.MonthYearKey),
        values = Seq(WorkUnitPivotTableValue.EntriesCount),
        filters = Seq.empty,
        options = Options(rowSubtotals = true, columnSubtotals = false, grandTotals = false)
      )

      val sql = normalized(statement(WorkUnitPivotQuerySql.coordinateCounts(organizationId, request, globals)))

      sql must contain("group by grouping sets ((user_key, project_key), (user_key))")
      sql must contain("group by grouping sets ((year_key, month_year_key))")
    }
  }

  "aggregate" should {
    "use the secure materialized source and exact source-based metrics" in {
      val request = Request(
        rows = Seq(WorkUnitPivotTableKey.UserKey),
        columns = Seq(WorkUnitPivotTableKey.ClientKey),
        values = Seq(WorkUnitPivotTableValue.EntriesCount, WorkUnitPivotTableValue.DurationSum, WorkUnitPivotTableValue.DurationAvg),
        filters = Seq.empty,
        options = Options(rowSubtotals = true, columnSubtotals = true, grandTotals = true)
      )

      val sql = normalized(statement(WorkUnitPivotQuerySql.aggregate(organizationId, request, globals)))

      sql must contain("with filtered as materialized")
      sql must contain("from work_unit wu join project p on p.id = coalesce(wu.override_project_id, wu.project_id)")
      sql must contain("join \"user\" u on u.id = wu.user_id and u.account_type = 'human'")
      sql must contain("wu.organization_id = ?")
      sql must contain("wu.date >= ?")
      sql must contain("wu.date <= ?")
      sql must contain("wu.archived_at is null")
      sql must contain(
        "case when wu.entry_type = 'worklog' then extract(epoch from wu.duration)::numeric * 1000000000 else 0::numeric end as duration_nanos"
      )
      sql must contain("case when wu.entry_type = 'worklog' then 1::bigint else 0::bigint end as worklog_count")
      sql must contain("count(*) as entries_count")
      sql must contain("sum(duration_nanos) as duration_sum_nanos")
      sql must contain("round(sum(duration_nanos) / nullif(sum(worklog_count), 0)) as duration_avg_nanos")
      sql must contain("group by grouping sets ((user_key, client_key), (user_key), (client_key), ())")
      sql must contain("having count(*) > 0")
    }

    "group and select by the free-text task id" in {
      val request = Request(
        rows = Seq(WorkUnitPivotTableKey.TaskKey),
        columns = Seq.empty,
        values = Seq(WorkUnitPivotTableValue.EntriesCount),
        filters = Seq.empty,
        options = noOptions
      )

      val sql = normalized(statement(WorkUnitPivotQuerySql.aggregate(organizationId, request, globals)))

      sql must contain("case when wu.task_id_override_set then wu.override_task_id else wu.task_id end as task_key")
      sql must contain("group by grouping sets ((task_key))")
    }

    "conditionally emit requested JSON keys without stripping a grouped client null" in {
      val request = Request(
        rows = Seq(WorkUnitPivotTableKey.YearKey, WorkUnitPivotTableKey.ClientKey),
        columns = Seq(WorkUnitPivotTableKey.DateKey),
        values = Seq(WorkUnitPivotTableValue.EntriesCount),
        filters = Seq.empty,
        options = Options(rowSubtotals = true, columnSubtotals = false, grandTotals = true)
      )

      val sql = normalized(statement(WorkUnitPivotQuerySql.aggregate(organizationId, request, globals)))

      sql must contain("grouping(year_key) = 0")
      sql must contain("jsonb_build_object('yearkey', to_jsonb(year_key))")
      sql must contain("grouping(client_key) = 0")
      sql must contain("jsonb_build_object('clientkey', to_jsonb(client_key))")
      sql must contain("grouping(date_key) = 0")
      sql must contain("jsonb_build_object('datekey', to_jsonb(date_key))")
      sql must not(contain("jsonb_strip_nulls"))
      sql.indexOf("'yearkey'") must beLessThan(sql.indexOf("'clientkey'"))
    }

    "cover all seven dimensions only through their static expressions and aliases" in {
      val request = Request(
        rows = WorkUnitPivotTableKey.values.take(4),
        columns = WorkUnitPivotTableKey.values.drop(4),
        values = Seq(WorkUnitPivotTableValue.EntriesCount),
        filters = Seq.empty,
        options = noOptions
      )

      val sql = normalized(statement(WorkUnitPivotQuerySql.aggregate(organizationId, request, globals)))

      Seq(
        "wu.user_id as user_key",
        "coalesce(wu.override_project_id, wu.project_id) as project_key",
        "p.client_id as client_key",
        "extract(year from wu.date)::integer as year_key",
        "to_char(wu.date, 'yyyy-mm') as month_year_key",
        "wu.date as date_key",
        "wu.entry_type as entry_type_key"
      ).foreach(sql must contain(_))
      success
    }
  }

  "filter binding" should {
    "bind organization, dates, global lists, and every typed pivot value" in {
      val request = Request(
        rows = Seq.empty,
        columns = Seq.empty,
        values = Seq(WorkUnitPivotTableValue.EntriesCount),
        filters = Seq(
          Filter(WorkUnitPivotTableKey.UserKey, Some(Seq(FieldValue.Uuid(pivotUuid)))),
          Filter(WorkUnitPivotTableKey.ProjectKey, Some(Seq(FieldValue.Uuid(pivotUuid)))),
          Filter(WorkUnitPivotTableKey.ClientKey, Some(Seq(FieldValue.Uuid(pivotUuid)))),
          Filter(WorkUnitPivotTableKey.YearKey, Some(Seq(FieldValue.Year(2026)))),
          Filter(WorkUnitPivotTableKey.MonthYearKey, Some(Seq(FieldValue.Month(YearMonth.of(2026, 7))))),
          Filter(WorkUnitPivotTableKey.DateKey, Some(Seq(FieldValue.Date(LocalDate.of(2026, 7, 8))))),
          Filter(WorkUnitPivotTableKey.EntryTypeKey, Some(Seq(FieldValue.EntryType(WorkUnitEntryType.Note))))
        ),
        options = noOptions
      )
      val builder = WorkUnitPivotQuerySql.aggregate(organizationId, request, globals)
      val sql     = statement(builder)
      val bound   = bindings(builder)

      Seq(
        organizationId.toString,
        dateFrom.toString,
        dateTo.toString,
        projectId.toString,
        userId.toString,
        clientId.toString,
        pivotUuid.toString,
        "2026-07",
        "2026-07-08",
        "Note"
      ).foreach(value => sql must not(contain(value)))
      sql.count(_ == '?') shouldEqual 13
      bound.collect { case (_, value) if !value.isInstanceOf[ArrayBinding] => value } must containTheSameElementsAs(
        Seq[Any](organizationId, dateFrom, dateTo)
      )
      bound.collect { case (_, array: ArrayBinding) => array } must containTheSameElementsAs(
        Seq(
          ArrayBinding("uuid", Seq(projectId)),
          ArrayBinding("uuid", Seq(userId)),
          ArrayBinding("uuid", Seq(clientId)),
          ArrayBinding("uuid", Seq(pivotUuid)),
          ArrayBinding("uuid", Seq(pivotUuid)),
          ArrayBinding("uuid", Seq(pivotUuid)),
          ArrayBinding("integer", Seq(2026)),
          ArrayBinding("text", Seq("2026-07")),
          ArrayBinding("date", Seq(java.sql.Date.valueOf(LocalDate.of(2026, 7, 8)))),
          ArrayBinding("text", Seq("Note"))
        )
      )
    }

    "omit unrestricted selections and render defensive empty selections as false" in {
      val unrestricted = requestWithFilters(Filter(WorkUnitPivotTableKey.UserKey, None))
      val impossible   = requestWithFilters(Filter(WorkUnitPivotTableKey.UserKey, Some(Seq.empty)))

      normalized(statement(WorkUnitPivotQuerySql.aggregate(organizationId, unrestricted, globals))) must not(contain("user_key = any"))
      normalized(statement(WorkUnitPivotQuerySql.aggregate(organizationId, impossible, globals))) must contain("and false")
    }

    "omit the optional upper date predicate when dateTo is absent" in {
      val sql = normalized(statement(WorkUnitPivotQuerySql.aggregate(organizationId, requestWithFilters(), globals.copy(dateTo = None))))

      sql must not(contain("wu.date <= ?"))
    }

    "render nullable client selections without changing their values into SQL text" in {
      val nullOnly = requestWithFilters(Filter(WorkUnitPivotTableKey.ClientKey, Some(Seq(FieldValue.Null))))
      val mixed    = requestWithFilters(Filter(WorkUnitPivotTableKey.ClientKey, Some(Seq(FieldValue.Null, FieldValue.Uuid(pivotUuid)))))

      normalized(statement(WorkUnitPivotQuerySql.aggregate(organizationId, nullOnly, globals))) must contain("and p.client_id is null")
      val mixedSql = normalized(statement(WorkUnitPivotQuerySql.aggregate(organizationId, mixed, globals)))
      mixedSql must contain("and (p.client_id is null or p.client_id = any(?::uuid[]))")
      mixedSql must not(contain(pivotUuid.toString))
      bindings(WorkUnitPivotQuerySql.aggregate(organizationId, mixed, globals)).map(_._2) must contain(ArrayBinding("uuid", Seq(pivotUuid)))
    }

    "render nullable task id selections without changing their values into SQL text" in {
      val nullOnly = requestWithFilters(Filter(WorkUnitPivotTableKey.TaskKey, Some(Seq(FieldValue.Null))))
      val mixed    = requestWithFilters(Filter(WorkUnitPivotTableKey.TaskKey, Some(Seq(FieldValue.Null, FieldValue.TaskId("PROJ-1")))))

      normalized(statement(WorkUnitPivotQuerySql.aggregate(organizationId, nullOnly, globals))) must contain(
        "and case when wu.task_id_override_set then wu.override_task_id else wu.task_id end is null"
      )
      val mixedSql = normalized(statement(WorkUnitPivotQuerySql.aggregate(organizationId, mixed, globals)))
      mixedSql must contain(
        "and (case when wu.task_id_override_set then wu.override_task_id else wu.task_id end is null or case when wu.task_id_override_set then wu.override_task_id else wu.task_id end = any(?::text[]))"
      )
    }
  }

  "availableFilterMembers" should {
    "return no statement when no filter facets were requested" in {
      WorkUnitPivotQuerySql.availableFilterMembers(organizationId, requestWithFilters(), globals) should beNone
    }

    "build one union-all statement, preserve request indexes, and ignore only each branch's own selection" in {
      val firstFilter  = Filter(WorkUnitPivotTableKey.UserKey, Some(Seq(FieldValue.Uuid(pivotUuid))))
      val secondFilter = Filter(WorkUnitPivotTableKey.EntryTypeKey, Some(Seq(FieldValue.EntryType(WorkUnitEntryType.Note))))
      val builder      = WorkUnitPivotQuerySql.availableFilterMembers(organizationId, requestWithFilters(firstFilter, secondFilter), globals).get
      val sql          = normalized(statement(builder))
      val branches     = sql.split(" union all ").toSeq

      branches must haveSize(2)
      branches.head must contain("?::integer as request_index")
      branches.head must contain("to_jsonb(user_key)::text as raw_value")
      branches.head must not(contain("user_key = any"))
      branches.head must contain("entry_type_key = any(?::text[])")
      branches(1) must contain("to_jsonb(entry_type_key)::text as raw_value")
      branches(1) must contain("user_key = any(?::uuid[])")
      branches(1) must not(contain("entry_type_key = any"))
      bindings(builder).map(_._2) shouldEqual Seq[Any](
        organizationId,
        dateFrom,
        dateTo,
        ArrayBinding("uuid", Seq(projectId)),
        ArrayBinding("uuid", Seq(userId)),
        ArrayBinding("uuid", Seq(clientId)),
        0,
        ArrayBinding("text", Seq("Note")),
        1,
        ArrayBinding("uuid", Seq(pivotUuid))
      )
    }

    "facet every supported dimension through the closed mapping" in {
      val filters = WorkUnitPivotTableKey.values.map(key => Filter(key, None))
      val sql     = normalized(statement(WorkUnitPivotQuerySql.availableFilterMembers(organizationId, requestWithFilters(filters: _*), globals).get))

      Seq("user_key", "project_key", "client_key", "year_key", "month_year_key", "date_key", "entry_type_key", "task_key")
        .foreach(alias => sql must contain(s"to_jsonb($alias)::text as raw_value"))
      sql.split(" union all ") must haveSize(8)
    }

    "bound parameter count when seven high-cardinality selections are faceted" in {
      val memberCount = 1600
      val uuids       = Vector.tabulate(memberCount)(index => UUID.fromString(f"60000000-0000-4000-8000-${index.toLong}%012d"))
      val globalFilters = GlobalFilters(
        dateFrom = dateFrom,
        dateTo = None,
        projectIds = uuids.map(ProjectId(_)),
        userIds = uuids.map(UserId(_)),
        clientIds = uuids.map(ClientId(_))
      )
      val request = requestWithFilters(
        Filter(WorkUnitPivotTableKey.UserKey, Some(uuids.map(FieldValue.Uuid))),
        Filter(WorkUnitPivotTableKey.ProjectKey, Some(uuids.map(FieldValue.Uuid))),
        Filter(WorkUnitPivotTableKey.ClientKey, Some(FieldValue.Null +: uuids.take(memberCount - 1).map(FieldValue.Uuid))),
        Filter(WorkUnitPivotTableKey.YearKey, Some(Vector.tabulate(memberCount)(index => FieldValue.Year(1000 + index)))),
        Filter(
          WorkUnitPivotTableKey.MonthYearKey,
          Some(Vector.tabulate(memberCount)(index => FieldValue.Month(YearMonth.of(1900, 1).plusMonths(index.toLong))))
        ),
        Filter(
          WorkUnitPivotTableKey.DateKey,
          Some(Vector.tabulate(memberCount)(index => FieldValue.Date(LocalDate.of(2020, 1, 1).plusDays(index.toLong))))
        ),
        Filter(
          WorkUnitPivotTableKey.EntryTypeKey,
          Some(Vector.tabulate(memberCount)(index => FieldValue.EntryType(if (index % 2 == 0) WorkUnitEntryType.Worklog else WorkUnitEntryType.Note)))
        )
      )
      val builder       = WorkUnitPivotQuerySql.availableFilterMembers(organizationId, request, globalFilters).get
      val sql           = normalized(statement(builder))
      val bound         = bindings(builder)
      val arrayBindings = bound.collect { case (_, array: ArrayBinding) => array }
      val scalarValues  = bound.collect { case (_, value) if !value.isInstanceOf[ArrayBinding] => value }

      sql.count(_ == '?') shouldEqual 54
      sql.count(_ == '?') must beLessThan(65535)
      countOccurrences(sql, "?::uuid[]") shouldEqual 21
      countOccurrences(sql, "?::integer[]") shouldEqual 6
      countOccurrences(sql, "?::text[]") shouldEqual 12
      countOccurrences(sql, "?::date[]") shouldEqual 6
      arrayBindings must haveSize(45)
      arrayBindings.count(binding => binding.typeName == "uuid" && binding.values.size == memberCount) shouldEqual 15
      arrayBindings.count(binding => binding.typeName == "uuid" && binding.values.size == memberCount - 1) shouldEqual 6
      arrayBindings.count(binding => binding.typeName == "integer" && binding.values.size == memberCount) shouldEqual 6
      arrayBindings.count(binding => binding.typeName == "text" && binding.values.size == memberCount) shouldEqual 12
      arrayBindings.count(binding => binding.typeName == "date" && binding.values.size == memberCount) shouldEqual 6
      scalarValues must containTheSameElementsAs(Seq[Any](organizationId, dateFrom) ++ (0 until 7))
      sql must not(contain(uuids.head.toString))
    }
  }

  "type boundary" should {
    "reject an injection-like raw field before it can reach the builder" in {
      val json = Json.obj(
        "rows"    -> Json.arr(Json.fromString("UserKey); DROP TABLE work_unit; --")),
        "columns" -> Json.arr(),
        "values"  -> Json.arr(Json.fromString("EntriesCount")),
        "filters" -> Json.arr(),
        "options" -> Json.obj(
          "rowSubtotals"    -> Json.fromBoolean(false),
          "columnSubtotals" -> Json.fromBoolean(false),
          "grandTotals"     -> Json.fromBoolean(false)
        )
      )

      new WorkUnitPivotQueryRequestValidator().validate(json) must beLeft
    }
  }

  private def requestWithFilters(filters: Filter*): Request =
    Request(rows = Seq.empty, columns = Seq.empty, values = Seq(WorkUnitPivotTableValue.EntriesCount), filters = filters, options = noOptions)

  private def statement(builder: SQLActionBuilder): String =
    builder.as[Int].statements.head

  private def normalized(sql: String): String = sql.toLowerCase.replaceAll("\\s+", " ").trim

  private def countOccurrences(value: String, part: String): Int = value.sliding(part.length).count(_ == part)

  private def bindings(builder: SQLActionBuilder): Seq[(String, Any)] = {
    val calls = ArrayBuffer.empty[(String, Any)]
    val connectionHandler = new InvocationHandler {
      override def invoke(
        proxy: Any,
        method: Method,
        args: Array[AnyRef]
      ): AnyRef =
        if (method.getName == "createArrayOf") {
          val typeName = args(0).asInstanceOf[String]
          val values   = args(1).asInstanceOf[Array[AnyRef]]
          Proxy
            .newProxyInstance(
              getClass.getClassLoader,
              Array(classOf[SqlArray]),
              new InvocationHandler {
                override def invoke(
                  proxy: Any,
                  method: Method,
                  args: Array[AnyRef]
                ): AnyRef = method.getName match {
                  case "getBaseTypeName" => typeName
                  case "getArray"        => values
                  case _                 => null
                }
              }
            )
            .asInstanceOf[AnyRef]
        } else null
    }
    val connection = Proxy
      .newProxyInstance(getClass.getClassLoader, Array(classOf[Connection]), connectionHandler)
      .asInstanceOf[Connection]
    val preparedStatementHandler = new InvocationHandler {
      override def invoke(
        proxy: Any,
        method: Method,
        args: Array[AnyRef]
      ): AnyRef =
        if (method.getName == "getConnection") connection
        else {
          if (method.getName.startsWith("set") && args != null && args.length >= 2) {
            val value = args(1) match {
              case sqlArray: SqlArray =>
                ArrayBinding(sqlArray.getBaseTypeName, sqlArray.getArray.asInstanceOf[Array[AnyRef]].toSeq)
              case scalar => scalar
            }
            calls += method.getName -> value
          }
          null
        }
    }
    val preparedStatement = Proxy
      .newProxyInstance(getClass.getClassLoader, Array(classOf[PreparedStatement]), preparedStatementHandler)
      .asInstanceOf[PreparedStatement]

    builder.setParameter((), new PositionedParameters(preparedStatement))
    calls.toSeq
  }
}

private object WorkUnitPivotQuerySqlSpec {
  final case class ArrayBinding(typeName: String, values: Seq[Any])
}
