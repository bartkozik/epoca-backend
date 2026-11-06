package pl.iterators.epoca.stats.repositories

import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId
import pl.iterators.epoca.stats.domain.StatsDomain.WorkUnitPivotTableKey
import pl.iterators.epoca.stats.domain.StatsDomain.WorkUnitPivotTableKey._
import pl.iterators.epoca.stats.domain.WorkUnitPivotQuery._
import slick.jdbc.{PositionedParameters, SQLActionBuilder, SetParameter}

import java.sql.Types
import java.time.LocalDate
import java.util.UUID

private[stats] object WorkUnitPivotQuerySql {
  private final case class Dimension(sourceExpression: String, alias: String)
  private val effectiveProject = "COALESCE(wu.override_project_id, wu.project_id)"
  private val effectiveTask    = "CASE WHEN wu.task_id_override_set THEN wu.override_task_id ELSE wu.task_id END"

  private val dimensions: Map[WorkUnitPivotTableKey, Dimension] = Map(
    UserKey      -> Dimension("wu.user_id", "user_key"),
    ProjectKey   -> Dimension(effectiveProject, "project_key"),
    ClientKey    -> Dimension("p.client_id", "client_key"),
    YearKey      -> Dimension("EXTRACT(YEAR FROM wu.date)::integer", "year_key"),
    MonthYearKey -> Dimension("to_char(wu.date, 'YYYY-MM')", "month_year_key"),
    DateKey      -> Dimension("wu.date", "date_key"),
    EntryTypeKey -> Dimension("wu.entry_type", "entry_type_key"),
    TaskKey      -> Dimension(effectiveTask, "task_key")
  )

  private val orderedDimensions: Seq[(WorkUnitPivotTableKey, Dimension)] =
    WorkUnitPivotTableKey.values.map(key => key -> dimensions(key))

  private val setUuid: SetParameter[UUID] =
    SetParameter((value, parameters) => parameters.setObject(value, Types.OTHER))
  private val setLocalDate: SetParameter[LocalDate] =
    SetParameter((value, parameters) => parameters.setObject(value, Types.DATE))
  private val setInt: SetParameter[Int] = SetParameter.SetInt
  private val setUuidArray: SetParameter[Seq[UUID]] =
    arraySetParameter("uuid", identity)
  private val setIntArray: SetParameter[Seq[Int]] =
    arraySetParameter("integer", Int.box)
  private val setStringArray: SetParameter[Seq[String]] =
    arraySetParameter("text", identity)
  private val setLocalDateArray: SetParameter[Seq[LocalDate]] =
    arraySetParameter("date", value => java.sql.Date.valueOf(value))

  private final case class Fragment(sql: String, binders: Vector[PositionedParameters => Unit]) {
    def ++(other: Fragment): Fragment = Fragment(sql + other.sql, binders ++ other.binders)
  }

  private object Fragment {
    def text(sql: String): Fragment = Fragment(sql, Vector.empty)

    def parameter[A](value: A, setParameter: SetParameter[A]): Fragment =
      Fragment("?", Vector(parameters => setParameter(value, parameters)))

    def join(fragments: Seq[Fragment], separator: String): Fragment =
      fragments.reduceOption((left, right) => left ++ text(separator) ++ right).getOrElse(text(""))
  }

  def depths(
    size: Int,
    subtotals: Boolean,
    grandTotals: Boolean
  ): Seq[Int] =
    if (size == 0) Seq(0)
    else
      Seq(size) ++
        Option.when(subtotals && size >= 2)((1 until size).reverse).toSeq.flatten ++
        Option.when(grandTotals)(0)

  def coordinateCounts(
    organizationId: OrganizationId,
    request: Request,
    filters: GlobalFilters
  ): SQLActionBuilder = {
    val filtered     = filteredCte(organizationId, filters, request.filters)
    val rowDepths    = depths(request.rows.size, request.options.rowSubtotals, request.options.grandTotals)
    val columnDepths = depths(request.columns.size, request.options.columnSubtotals, request.options.grandTotals)
    val sql = filtered ++ Fragment.text(s""",
         |${coordinateCte("row_coordinates", request.rows, rowDepths)},
         |${coordinateCte("column_coordinates", request.columns, columnDepths)}
         |SELECT (SELECT COUNT(*) FROM row_coordinates)::bigint AS row_count,
         |       (SELECT COUNT(*) FROM column_coordinates)::bigint AS column_count
         |""".stripMargin)

    builder(sql)
  }

  def aggregate(
    organizationId: OrganizationId,
    request: Request,
    filters: GlobalFilters
  ): SQLActionBuilder = {
    val filtered     = filteredCte(organizationId, filters, request.filters)
    val rowDepths    = depths(request.rows.size, request.options.rowSubtotals, request.options.grandTotals)
    val columnDepths = depths(request.columns.size, request.options.columnSubtotals, request.options.grandTotals)
    val groupingSets = for {
      rowDepth    <- rowDepths
      columnDepth <- columnDepths
    } yield groupingSet(request.rows.take(rowDepth) ++ request.columns.take(columnDepth))

    val sql = filtered ++ Fragment.text(s"""
         |SELECT ${pathJson(request.rows)} AS row_path_json,
         |       ${pathJson(request.columns)} AS column_path_json,
         |       COUNT(*) AS entries_count,
         |       SUM(duration_nanos) AS duration_sum_nanos,
         |       ROUND(SUM(duration_nanos) / NULLIF(SUM(worklog_count), 0)) AS duration_avg_nanos
         |FROM filtered
         |GROUP BY GROUPING SETS (${groupingSets.mkString(", ")})
         |HAVING COUNT(*) > 0
         |""".stripMargin)

    builder(sql)
  }

  def availableFilterMembers(
    organizationId: OrganizationId,
    request: Request,
    filters: GlobalFilters
  ): Option[SQLActionBuilder] =
    Option.when(request.filters.nonEmpty) {
      val filtered = filteredCte(organizationId, filters, Seq.empty)
      val branches = request.filters.zipWithIndex.map { case (requestedFilter, requestIndex) =>
        val otherPredicates = pivotPredicates(request.filters.filterNot(_.key == requestedFilter.key), expression = dimensionAlias)
        Fragment.text("SELECT ") ++
          Fragment.parameter(requestIndex, setInt) ++
          Fragment.text(s"""::integer AS request_index,
               |       to_jsonb(${dimensionAlias(requestedFilter.key)})::text AS raw_value
               |FROM filtered""".stripMargin) ++
          whereClause(otherPredicates) ++
          Fragment.text(s"\nGROUP BY ${dimensionAlias(requestedFilter.key)}\n")
      }

      builder(filtered ++ Fragment.text("\n") ++ Fragment.join(branches, "UNION ALL\n"))
    }

  private def filteredCte(
    organizationId: OrganizationId,
    filters: GlobalFilters,
    pivotFilters: Seq[Filter]
  ): Fragment = {
    val globalPredicates = Seq(
      Some(Fragment.text("wu.organization_id = ") ++ Fragment.parameter(organizationId.asInstanceOf[UUID], setUuid)),
      Some(Fragment.text("wu.date >= ") ++ Fragment.parameter(filters.dateFrom.asInstanceOf[LocalDate], setLocalDate)),
      filters.dateTo.map(date => Fragment.text("wu.date <= ") ++ Fragment.parameter(date.asInstanceOf[LocalDate], setLocalDate)),
      arrayPredicate(effectiveProject, filters.projectIds.map(_.asInstanceOf[UUID]), "uuid", setUuidArray),
      arrayPredicate("wu.user_id", filters.userIds.map(_.asInstanceOf[UUID]), "uuid", setUuidArray),
      arrayPredicate("p.client_id", filters.clientIds.map(_.asInstanceOf[UUID]), "uuid", setUuidArray),
      filters.reportAuthorizedProjectIds.map { projectIds =>
        arrayPredicate("wu.project_id", projectIds.map(_.asInstanceOf[UUID]), "uuid", setUuidArray).getOrElse(Fragment.text("FALSE"))
      },
      filters.reportAuthorizedProjectIds.map { projectIds =>
        arrayPredicate(effectiveProject, projectIds.map(_.asInstanceOf[UUID]), "uuid", setUuidArray).getOrElse(Fragment.text("FALSE"))
      },
      Some(Fragment.text("wu.archived_at IS NULL"))
    ).flatten
    val predicates = globalPredicates ++ pivotPredicates(pivotFilters, expression = dimensionSource)
    val selectList = orderedDimensions
      .map { case (_, dimension) =>
        s"${dimension.sourceExpression} AS ${dimension.alias}"
      }
      .mkString(",\n       ")

    Fragment.text(s"""WITH filtered AS MATERIALIZED (
         |  SELECT $selectList,
         |       CASE WHEN wu.entry_type = 'Worklog'
         |         THEN EXTRACT(EPOCH FROM wu.duration)::numeric * 1000000000
         |         ELSE 0::numeric
         |       END AS duration_nanos,
         |       CASE WHEN wu.entry_type = 'Worklog' THEN 1::bigint ELSE 0::bigint END AS worklog_count
         |  FROM work_unit wu
         |  JOIN project p ON p.id = $effectiveProject
         |  JOIN "user" u ON u.id = wu.user_id AND u.account_type = 'Human'
         |  WHERE """.stripMargin) ++ Fragment.join(predicates, "\n    AND ") ++ Fragment.text("\n)")
  }

  private def coordinateCte(
    name: String,
    keys: Seq[WorkUnitPivotTableKey],
    allowedDepths: Seq[Int]
  ): String =
    if (keys.isEmpty) s"$name AS (SELECT 1::bigint AS structural_coordinate)"
    else
      s"""$name AS (
         |  SELECT 1::bigint AS coordinate
         |  FROM filtered
         |  GROUP BY GROUPING SETS (${allowedDepths.map(depth => groupingSet(keys.take(depth))).mkString(", ")})
         |  HAVING COUNT(*) > 0
         |)""".stripMargin

  private def pathJson(keys: Seq[WorkUnitPivotTableKey]): String =
    "(" + keys.foldLeft("'{}'::jsonb") { (json, key) =>
      val alias = dimensionAlias(key)
      s"$json || CASE WHEN GROUPING($alias) = 0 THEN jsonb_build_object('${key.entryName}', to_jsonb($alias)) ELSE '{}'::jsonb END"
    } + ")::text"

  private def groupingSet(keys: Seq[WorkUnitPivotTableKey]): String =
    keys.map(dimensionAlias).mkString("(", ", ", ")")

  private def pivotPredicates(filters: Seq[Filter], expression: WorkUnitPivotTableKey => String): Seq[Fragment] =
    filters.flatMap {
      case Filter(_, None)             => None
      case Filter(_, Some(Seq()))      => Some(Fragment.text("FALSE"))
      case Filter(key, Some(selected)) => Some(selectionPredicate(key, expression(key), selected))
    }

  private def selectionPredicate(
    key: WorkUnitPivotTableKey,
    expression: String,
    selected: Seq[FieldValue]
  ): Fragment =
    key match {
      case ClientKey => clientSelectionPredicate(expression, selected)
      case TaskKey   => taskSelectionPredicate(expression, selected)
      case UserKey | ProjectKey =>
        selectedArrayPredicate(expression, selected, "uuid", setUuidArray) { case FieldValue.Uuid(value) => value }
      case YearKey =>
        selectedArrayPredicate(expression, selected, "integer", setIntArray) { case FieldValue.Year(value) => value }
      case MonthYearKey =>
        selectedArrayPredicate(expression, selected, "text", setStringArray) { case FieldValue.Month(value) => value.toString }
      case DateKey =>
        selectedArrayPredicate(expression, selected, "date", setLocalDateArray) { case FieldValue.Date(value) => value }
      case EntryTypeKey =>
        selectedArrayPredicate(expression, selected, "text", setStringArray) { case FieldValue.EntryType(value) => value.entryName }
    }

  private def clientSelectionPredicate(expression: String, selected: Seq[FieldValue]): Fragment = {
    val hasNull    = selected.contains(FieldValue.Null)
    val uuidValues = selected.collect { case FieldValue.Uuid(value) => value }
    val containsInvalidValue = selected.exists {
      case FieldValue.Null | FieldValue.Uuid(_) => false
      case _                                    => true
    }

    if (containsInvalidValue || (!hasNull && uuidValues.isEmpty)) Fragment.text("FALSE")
    else {
      val predicates =
        Seq(Option.when(hasNull)(Fragment.text(s"$expression IS NULL")), arrayPredicate(expression, uuidValues, "uuid", setUuidArray)).flatten
      val joined = Fragment.join(predicates, " OR ")
      if (predicates.size >= 2) Fragment.text("(") ++ joined ++ Fragment.text(")") else joined
    }
  }

  private def taskSelectionPredicate(expression: String, selected: Seq[FieldValue]): Fragment = {
    val hasNull      = selected.contains(FieldValue.Null)
    val stringValues = selected.collect { case FieldValue.TaskId(value) => value }
    val containsInvalidValue = selected.exists {
      case FieldValue.Null | FieldValue.TaskId(_) => false
      case _                                      => true
    }

    if (containsInvalidValue || (!hasNull && stringValues.isEmpty)) Fragment.text("FALSE")
    else {
      val predicates =
        Seq(Option.when(hasNull)(Fragment.text(s"$expression IS NULL")), arrayPredicate(expression, stringValues, "text", setStringArray)).flatten
      val joined = Fragment.join(predicates, " OR ")
      if (predicates.size >= 2) Fragment.text("(") ++ joined ++ Fragment.text(")") else joined
    }
  }

  private def selectedArrayPredicate[A](
    expression: String,
    selected: Seq[FieldValue],
    postgresType: String,
    setParameter: SetParameter[Seq[A]]
  )(
    extract: PartialFunction[FieldValue, A]
  ): Fragment = {
    val values = selected.collect(extract)
    if (values.size != selected.size) Fragment.text("FALSE")
    else arrayPredicate(expression, values, postgresType, setParameter).getOrElse(Fragment.text("FALSE"))
  }

  private def arrayPredicate[A](
    expression: String,
    values: Seq[A],
    postgresType: String,
    setParameter: SetParameter[Seq[A]]
  ): Option[Fragment] =
    Option.when(values.nonEmpty) {
      Fragment.text(s"$expression = ANY(") ++
        Fragment.parameter(values, setParameter) ++
        Fragment.text(s"::$postgresType[])")
    }

  private def whereClause(predicates: Seq[Fragment]): Fragment =
    if (predicates.isEmpty) Fragment.text("")
    else Fragment.text("\nWHERE ") ++ Fragment.join(predicates, "\n  AND ")

  private def dimensionSource(key: WorkUnitPivotTableKey): String = dimensions(key).sourceExpression
  private def dimensionAlias(key: WorkUnitPivotTableKey): String  = dimensions(key).alias

  private def arraySetParameter[A](postgresType: String, toJdbc: A => AnyRef): SetParameter[Seq[A]] =
    SetParameter { (values, parameters) =>
      val sqlArray       = parameters.ps.getConnection.createArrayOf(postgresType, values.iterator.map(toJdbc).toArray[AnyRef])
      val parameterIndex = parameters.pos + 1
      parameters.ps.setArray(parameterIndex, sqlArray)
      parameters.pos = parameterIndex
    }

  private def builder(fragment: Fragment): SQLActionBuilder =
    SQLActionBuilder(
      fragment.sql,
      SetParameter { (_, parameters) =>
        fragment.binders.foreach(bind => bind(parameters))
      }
    )
}
